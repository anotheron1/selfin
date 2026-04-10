package ru.selfin.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ru.selfin.backend.dto.*;
import ru.selfin.backend.exception.ResourceNotFoundException;
import ru.selfin.backend.model.*;
import ru.selfin.backend.model.enums.*;
import ru.selfin.backend.repository.*;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FinancialEventService {

    private final FinancialEventRepository eventRepository;
    private final CategoryRepository categoryRepository;
    private final TargetFundRepository targetFundRepository;
    private final CategoryService categoryService;
    private final Clock clock;

    @Autowired @Lazy
    private TargetFundService targetFundService;

    /**
     * Возвращает список событий за период {@code [start, end]} с обогащением:
     * для PLAN-записей — агрегаты привязанных FACT-записей (количество, сумма),
     * для FACT-записей — описание родительского PLAN,
     * для FUND_TRANSFER — название целевого фонда.
     *
     * @param start начало периода (включительно)
     * @param end   конец периода (включительно)
     * @return обогащённый список DTO, отсортированный по дате
     */
    public List<FinancialEventDto> findByPeriod(LocalDate start, LocalDate end) {
        List<FinancialEvent> events =
                eventRepository.findAllByDeletedFalseAndDateBetweenOrderByDateAsc(start, end);

        // Aggregate fact counts/amounts for PLAN enrichment
        List<UUID> planIds = events.stream()
                .filter(e -> e.getEventKind() == EventKind.PLAN)
                .map(FinancialEvent::getId)
                .toList();

        Map<UUID, FactAggregateProjection> aggByPlan = planIds.isEmpty()
                ? Collections.emptyMap()
                : eventRepository.findFactAggregatesByPlanIds(planIds).stream()
                        .collect(Collectors.toMap(FactAggregateProjection::getParentEventId, p -> p));

        // Load parent plans for FACT enrichment (parentPlanDescription)
        Set<UUID> parentIds = events.stream()
                .filter(e -> e.getEventKind() == EventKind.FACT && e.getParentEventId() != null)
                .map(FinancialEvent::getParentEventId)
                .collect(Collectors.toSet());

        Map<UUID, FinancialEvent> parentById = parentIds.isEmpty()
                ? Collections.emptyMap()
                : eventRepository.findAllById(parentIds).stream()
                        .collect(Collectors.toMap(FinancialEvent::getId, e -> e));

        // Batch-load fund names to avoid N+1 queries
        Set<UUID> fundIds = events.stream()
                .map(FinancialEvent::getTargetFundId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<UUID, String> fundNameById = fundIds.isEmpty()
                ? Collections.emptyMap()
                : targetFundRepository.findAllById(fundIds).stream()
                        .collect(Collectors.toMap(TargetFund::getId, TargetFund::getName));

        return events.stream()
                .map(e -> toDto(e, aggByPlan.get(e.getId()), parentById.get(e.getParentEventId()), fundNameById.get(e.getTargetFundId())))
                .toList();
    }

    /**
     * Создаёт новое PLAN-событие с гарантией идемпотентности: если событие
     * с таким {@code idempotencyKey} уже существует, возвращает его без изменений.
     * Для FUND_TRANSFER без categoryId автоматически подставляется системная категория.
     *
     * @param idempotencyKey UUID из заголовка {@code Idempotency-Key}
     * @param dto            данные для создания события
     * @return DTO созданного или ранее существующего события
     * @throws ResourceNotFoundException если категория не найдена
     */
    @Transactional
    public FinancialEventDto createIdempotent(UUID idempotencyKey, FinancialEventCreateDto dto) {
        return eventRepository.findByIdempotencyKey(idempotencyKey)
                .map(e -> toDto(e, null, null))
                .orElseGet(() -> {
                    Category category;
                    if (dto.type() == EventType.FUND_TRANSFER && dto.categoryId() == null) {
                        category = targetFundService.getOrCreateFundTransferCategory();
                    } else {
                        category = categoryRepository.findById(dto.categoryId())
                                .filter(c -> !c.isDeleted())
                                .orElseThrow(() -> new ResourceNotFoundException(
                                        "Category", dto.categoryId()));
                    }
                    FinancialEvent event = FinancialEvent.builder()
                            .idempotencyKey(idempotencyKey)
                            .eventKind(EventKind.PLAN)
                            .date(dto.date())
                            .category(category)
                            .type(dto.type())
                            .plannedAmount(dto.plannedAmount())
                            .priority(dto.priority() != null ? dto.priority() : category.getPriority())
                            .description(dto.description())
                            .rawInput(dto.rawInput())
                            .targetFundId(dto.targetFundId())
                            .build();
                    return toDto(eventRepository.save(event), null, null);
                });
    }

    /**
     * Создаёт standalone FACT-запись без родительского PLAN.
     * Используется для внеплановых трат, когда план не создавался.
     */
    @Transactional
    public FinancialEventDto createStandaloneFact(StandaloneFactCreateDto dto) {
        Category category = categoryRepository.findById(dto.categoryId())
                .filter(c -> !c.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("Category", dto.categoryId()));

        FinancialEvent fact = FinancialEvent.builder()
                .idempotencyKey(UUID.randomUUID())
                .eventKind(EventKind.FACT)
                .date(dto.date())
                .category(category)
                .type(dto.type())
                .factAmount(dto.factAmount())
                .priority(dto.priority() != null ? dto.priority() : category.getPriority())
                .status(EventStatus.EXECUTED)
                .description(dto.description())
                .build();

        return toDto(eventRepository.save(fact), null, null);
    }

    /**
     * Обновляет существующий PLAN-событие. FACT-записи обновлять через этот метод нельзя
     * (выбросит 400). При наличии старого factAmount пишет в лог предупреждение
     * (обратная совместимость с событиями до V12).
     *
     * @param id  идентификатор события
     * @param dto новые данные
     * @return обновлённый DTO
     * @throws ResourceNotFoundException если событие не найдено
     * @throws ResponseStatusException   400 при попытке обновить FACT-запись
     */
    @Transactional
    public FinancialEventDto update(UUID id, FinancialEventCreateDto dto) {
        FinancialEvent event = eventRepository.findById(id)
                .filter(e -> !e.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("FinancialEvent", id));

        if (event.getEventKind() == EventKind.FACT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Cannot update a FACT record via PUT — use the fact-specific endpoint");
        }

        Category category;
        if (dto.type() == EventType.FUND_TRANSFER && dto.categoryId() == null) {
            category = targetFundService.getOrCreateFundTransferCategory();
        } else {
            category = categoryRepository.findById(dto.categoryId())
                    .filter(c -> !c.isDeleted())
                    .orElseThrow(() -> new ResourceNotFoundException("Category", dto.categoryId()));
        }

        // Capture old fact for audit log (kept for backward compat on old executed events)
        BigDecimal oldFact = event.getFactAmount();

        event.setDate(dto.date());
        event.setCategory(category);
        event.setType(dto.type());
        event.setPlannedAmount(dto.plannedAmount());
        event.setPriority(dto.priority() != null ? dto.priority() : category.getPriority());
        event.setDescription(dto.description());
        event.setRawInput(dto.rawInput());
        event.setTargetFundId(dto.targetFundId());

        if (oldFact != null) {
            log.info("plan_update event_id={} category={} planned={} (fact retained on FACT record)",
                    id, category.getName(), dto.plannedAmount());
        }

        return toDto(eventRepository.save(event), null, null);
    }

    /**
     * Создаёт FACT-запись, привязанную к существующему PLAN-событию.
     * Родительский PLAN переводится в статус EXECUTED (если был PLANNED).
     * FACT наследует категорию и тип от PLAN.
     *
     * @param planId идентификатор родительского PLAN-события
     * @param dto    фактическая сумма и описание
     * @return DTO созданной FACT-записи
     * @throws ResourceNotFoundException если PLAN не найден или удалён
     */
    @Transactional
    public FinancialEventDto createLinkedFact(UUID planId, FactCreateDto dto) {
        FinancialEvent plan = eventRepository.findById(planId)
                .filter(e -> !e.isDeleted() && e.getEventKind() == EventKind.PLAN)
                .orElseThrow(() -> new ResourceNotFoundException("FinancialEvent (PLAN)", planId));

        FinancialEvent fact = FinancialEvent.builder()
                .idempotencyKey(UUID.randomUUID())
                .eventKind(EventKind.FACT)
                .parentEventId(planId)
                .date(dto.date())
                .category(plan.getCategory())
                .type(plan.getType())
                .factAmount(dto.factAmount())
                .priority(Priority.MEDIUM)
                .status(EventStatus.EXECUTED)
                .description(dto.description())
                .build();

        FinancialEvent savedFact = eventRepository.save(fact);

        if (plan.getStatus() == EventStatus.PLANNED) {
            plan.setStatus(EventStatus.EXECUTED);
            eventRepository.save(plan);
        }

        return toDto(savedFact, null, plan);
    }

    /**
     * Обновляет фактическую сумму события (PATCH-семантика).
     * Автоматически переключает статус: PLANNED ↔ EXECUTED в зависимости от наличия factAmount.
     * Для FUND_TRANSFER при первичном заполнении факта инициирует перевод в копилку.
     *
     * @param id  идентификатор события
     * @param dto новая фактическая сумма (может быть {@code null} для отмены)
     * @return обновлённый DTO
     * @throws ResourceNotFoundException если событие не найдено
     */
    @Transactional
    public FinancialEventDto updateFact(UUID id, FinancialEventUpdateFactDto dto) {
        FinancialEvent event = eventRepository.findById(id)
                .filter(e -> !e.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("FinancialEvent", id));

        BigDecimal oldFact = event.getFactAmount();
        event.setFactAmount(dto.factAmount());
        if (dto.description() != null) event.setDescription(dto.description());

        if (dto.factAmount() != null && event.getStatus() == EventStatus.PLANNED)
            event.setStatus(EventStatus.EXECUTED);
        else if (dto.factAmount() == null && event.getStatus() == EventStatus.EXECUTED)
            event.setStatus(EventStatus.PLANNED);

        if (event.getType() == EventType.FUND_TRANSFER
                && event.getTargetFundId() != null
                && dto.factAmount() != null
                && oldFact == null
                && event.getIdempotencyKey() != null) {
            targetFundService.doTransferForEvent(
                    event.getTargetFundId(), dto.factAmount(), event.getIdempotencyKey());
        }

        BigDecimal delta = (dto.factAmount() != null ? dto.factAmount() : BigDecimal.ZERO)
                .subtract(oldFact != null ? oldFact : BigDecimal.ZERO);
        log.info("fact_patch event_id={} category={} fact_old={} fact_new={} delta={}",
                id, event.getCategory().getName(), oldFact, dto.factAmount(), delta);

        return toDto(eventRepository.save(event), null, null);
    }

    /**
     * Циклически переключает приоритет события: HIGH → MEDIUM → LOW → HIGH.
     *
     * @param id идентификатор события
     * @return обновлённый DTO
     * @throws ResourceNotFoundException если событие не найдено
     */
    @Transactional
    public FinancialEventDto cyclePriority(UUID id) {
        FinancialEvent event = eventRepository.findById(id)
                .filter(e -> !e.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("FinancialEvent", id));
        event.setPriority(categoryService.nextPriority(event.getPriority()));
        return toDto(eventRepository.save(event), null, null);
    }

    /**
     * Возвращает список хотелок: LOW-приоритетные PLANNED-события без даты.
     *
     * @return список DTO хотелок
     */
    public List<FinancialEventDto> findWishlist() {
        LocalDate cutoff = LocalDate.now(clock).withDayOfMonth(1);
        return eventRepository.findWishlistItems(Priority.LOW, EventStatus.PLANNED, cutoff)
                .stream().map(e -> toDto(e, null, null)).toList();
    }

    /**
     * Мягко удаляет событие. Для PLAN с привязанными FACT-записями выбрасывает 409 CONFLICT.
     * При удалении FACT-записи проверяет, остались ли у родительского PLAN другие факты;
     * если нет — возвращает PLAN в статус PLANNED.
     *
     * @param id идентификатор события
     * @throws ResourceNotFoundException если событие не найдено
     * @throws ResponseStatusException   409 при наличии привязанных FACT-записей у PLAN
     */
    @Transactional
    public void softDelete(UUID id) {
        FinancialEvent event = eventRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("FinancialEvent", id));

        if (event.getEventKind() == EventKind.PLAN) {
            List<FactAggregateProjection> aggs =
                    eventRepository.findFactAggregatesByPlanIds(List.of(id));
            if (!aggs.isEmpty() && aggs.get(0).getCount() > 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Cannot delete PLAN with linked FACTs — delete FACTs first");
            }
        }

        event.setDeleted(true);
        eventRepository.save(event);
        eventRepository.flush();

        // If deleting a FACT, revert parent PLAN to PLANNED if it has no other FACTs
        if (event.getEventKind() == EventKind.FACT && event.getParentEventId() != null) {
            eventRepository.findById(event.getParentEventId()).ifPresent(plan -> {
                List<FactAggregateProjection> aggs =
                        eventRepository.findFactAggregatesByPlanIds(List.of(plan.getId()));
                if (aggs.isEmpty() || aggs.get(0).getCount() == 0) {
                    plan.setStatus(EventStatus.PLANNED);
                    eventRepository.save(plan);
                }
            });
        }
    }

    /** Convenience overload used by callers that don't need enrichment. */
    public FinancialEventDto toDto(FinancialEvent e) {
        return toDto(e, null, null);
    }

    /**
     * Конвертирует entity в DTO с обогащением: агрегаты FACT-записей, описание
     * родительского PLAN, название фонда. Загружает fundName из БД (N+1 допустим
     * для единичных вызовов; пакетная загрузка — в {@link #findByPeriod}).
     *
     * @param e          entity события
     * @param agg        агрегат привязанных FACT-записей (может быть {@code null})
     * @param parentPlan родительский PLAN (может быть {@code null})
     * @return обогащённый DTO
     */
    public FinancialEventDto toDto(FinancialEvent e,
                                   FactAggregateProjection agg,
                                   FinancialEvent parentPlan) {
        String fundName = null;
        if (e.getTargetFundId() != null) {
            fundName = targetFundRepository.findById(e.getTargetFundId())
                    .map(TargetFund::getName).orElse(null);
        }
        return toDto(e, agg, parentPlan, fundName);
    }

    /**
     * Внутренний маппер entity → DTO с уже загруженным именем фонда.
     *
     * @param e          entity события
     * @param agg        агрегат привязанных FACT (может быть {@code null})
     * @param parentPlan родительский PLAN (может быть {@code null})
     * @param fundName   название целевого фонда (может быть {@code null})
     * @return обогащённый DTO
     */
    private FinancialEventDto toDto(FinancialEvent e,
                                    FactAggregateProjection agg,
                                    FinancialEvent parentPlan,
                                    String fundName) {
        int linkedFactsCount = agg != null ? agg.getCount().intValue() : 0;
        BigDecimal linkedFactsAmount = agg != null ? agg.getTotalAmount() : null;

        String parentPlanDescription = null;
        if (parentPlan != null) {
            parentPlanDescription = parentPlan.getDescription() != null
                    ? parentPlan.getDescription()
                    : parentPlan.getCategory().getName();
        }

        return new FinancialEventDto(
                e.getId(), e.getDate(),
                e.getCategory().getId(), e.getCategory().getName(),
                e.getType(), e.getPlannedAmount(), e.getFactAmount(),
                e.getStatus(), e.getPriority(), e.getDescription(),
                e.getRawInput(), e.getCreatedAt(),
                e.getTargetFundId(), fundName, e.getUrl(),
                e.getEventKind(), e.getParentEventId(),
                linkedFactsCount, linkedFactsAmount, parentPlanDescription);
    }

    /**
     * Создаёт хотелку: LOW-приоритетное PLANNED-событие типа EXPENSE без даты.
     * Категория «Хотелки» создаётся автоматически при первом вызове.
     *
     * @param dto описание и сумма хотелки
     * @return DTO созданного события
     */
    @Transactional
    public FinancialEventDto createWishlistItem(WishlistCreateDto dto) {
        Category category = categoryRepository.findByNameAndDeletedFalse(SystemCategory.WISHLIST_NAME)
                .orElseGet(() -> categoryRepository.save(
                        Category.builder()
                                .name(SystemCategory.WISHLIST_NAME)
                                .type(CategoryType.EXPENSE)
                                .system(true)
                                .build()));

        FinancialEvent event = FinancialEvent.builder()
                .eventKind(EventKind.PLAN)
                .category(category)
                .type(EventType.EXPENSE)
                .priority(Priority.LOW)
                .status(EventStatus.PLANNED)
                .description(dto.description())
                .plannedAmount(dto.plannedAmount())
                .url(dto.url())
                .build();

        return toDto(eventRepository.save(event), null, null);
    }
}
