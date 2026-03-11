package ru.selfin.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.selfin.backend.dto.FinancialEventCreateDto;
import ru.selfin.backend.dto.FinancialEventDto;
import ru.selfin.backend.dto.FinancialEventUpdateFactDto;
import ru.selfin.backend.exception.ResourceNotFoundException;
import ru.selfin.backend.model.Category;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.enums.EventStatus;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.Priority;
import ru.selfin.backend.repository.CategoryRepository;
import ru.selfin.backend.repository.FinancialEventRepository;
import ru.selfin.backend.repository.TargetFundRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Сервис управления финансовыми событиями (доходы, расходы, переводы в фонды).
 * Реализует план-факт модель: каждое событие хранит плановую сумму ({@code plannedAmount})
 * и фактическую ({@code factAmount}). Статус управляется автоматически по наличию факта.
 *
 * <p>Все операции мутации защищены идемпотентностью через клиентский {@code Idempotency-Key}
 * (для создания) или soft-delete вместо физического удаления.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FinancialEventService {

        private final FinancialEventRepository eventRepository;
        private final CategoryRepository categoryRepository;
        private final TargetFundRepository targetFundRepository;

        @Autowired @Lazy
        private TargetFundService targetFundService;

        /**
         * Возвращает все не удалённые события за период {@code [start, end]} включительно,
         * отсортированные по дате по возрастанию.
         *
         * @param start начало периода (включительно)
         * @param end   конец периода (включительно)
         * @return список DTO событий, пустой список если событий нет
         */
        public List<FinancialEventDto> findByPeriod(LocalDate start, LocalDate end) {
                return eventRepository.findAllByDeletedFalseAndDateBetweenOrderByDateAsc(start, end)
                                .stream().map(this::toDto).toList();
        }

        /**
         * Идемпотентное создание события.
         * Если событие с данным {@code idempotencyKey} уже существует — возвращает его без
         * повторного создания в БД. Это защищает от дублирования при сетевых ретраях.
         *
         * @param idempotencyKey UUID, сгенерированный клиентом; должен быть уникален на одно намерение
         * @param dto            данные нового события
         * @return созданное или найденное по ключу событие
         * @throws ResourceNotFoundException если указанная категория не найдена или удалена
         */
        @Transactional
        public FinancialEventDto createIdempotent(UUID idempotencyKey, FinancialEventCreateDto dto) {
                return eventRepository.findByIdempotencyKey(idempotencyKey)
                                .map(this::toDto)
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
                                                        .date(dto.date())
                                                        .category(category)
                                                        .type(dto.type())
                                                        .plannedAmount(dto.plannedAmount())
                                                        .factAmount(dto.factAmount())
                                                        .priority(dto.priority() != null ? dto.priority() : Priority.MEDIUM)
                                                        .description(dto.description())
                                                        .rawInput(dto.rawInput())
                                                        .targetFundId(dto.targetFundId())
                                                        .build();
                                        return toDto(eventRepository.save(event));
                                });
        }

        /**
         * Полное обновление события: все поля заменяются значениями из {@code dto}.
         * Статус пересчитывается автоматически: наличие {@code factAmount} → {@code EXECUTED},
         * отсутствие → {@code PLANNED}. Изменение факта пишется в аудит-лог.
         *
         * @param id  идентификатор существующего события
         * @param dto новые данные для всех полей события
         * @return обновлённое событие
         * @throws ResourceNotFoundException если событие или категория не найдены / удалены
         */
        @Transactional
        public FinancialEventDto update(UUID id, FinancialEventCreateDto dto) {
                FinancialEvent event = eventRepository.findById(id)
                                .filter(e -> !e.isDeleted())
                                .orElseThrow(() -> new ResourceNotFoundException("FinancialEvent", id));
                Category category = categoryRepository.findById(dto.categoryId())
                                .filter(c -> !c.isDeleted())
                                .orElseThrow(() -> new ResourceNotFoundException("Category", dto.categoryId()));

                // Сохраняем старый факт ДО перезаписи — для корректного аудит-лога
                BigDecimal oldFact = event.getFactAmount();

                event.setDate(dto.date());
                event.setCategory(category);
                event.setType(dto.type());
                event.setPlannedAmount(dto.plannedAmount());
                event.setFactAmount(dto.factAmount());
                event.setPriority(dto.priority() != null ? dto.priority() : Priority.MEDIUM);
                event.setDescription(dto.description());
                event.setRawInput(dto.rawInput());
                event.setTargetFundId(dto.targetFundId());
                // Авто-статус: если передан factAmount → EXECUTED, иначе оставляем PLANNED
                if (dto.factAmount() != null && event.getStatus() == EventStatus.PLANNED) {
                        event.setStatus(EventStatus.EXECUTED);
                } else if (dto.factAmount() == null && event.getStatus() == EventStatus.EXECUTED) {
                        event.setStatus(EventStatus.PLANNED);
                }

                // Аудит-лог изменения фактической суммы
                if (dto.factAmount() != null) {
                        BigDecimal delta = dto.factAmount()
                                        .subtract(oldFact != null ? oldFact : BigDecimal.ZERO);
                        log.info("fact_update event_id={} category={} planned={} fact_old={} fact_new={} delta={}",
                                        id, category.getName(),
                                        dto.plannedAmount(), oldFact, dto.factAmount(), delta);
                }
                return toDto(eventRepository.save(event));
        }

        /**
         * Частичное обновление: только фактическая сумма и комментарий.
         * Используется {@code PATCH /events/{id}/fact} из UI-экрана "Бюджет".
         * Не требует пересылки всего тела события — меняет только то, что вводит пользователь.
         * Статус пересчитывается так же, как в {@link #update}.
         *
         * @param id  идентификатор существующего события
         * @param dto фактическая сумма (может быть {@code null} для снятия отметки) и комментарий
         * @return обновлённое событие
         * @throws ResourceNotFoundException если событие не найдено или удалено
         */
        @Transactional
        public FinancialEventDto updateFact(UUID id, FinancialEventUpdateFactDto dto) {
                FinancialEvent event = eventRepository.findById(id)
                                .filter(e -> !e.isDeleted())
                                .orElseThrow(() -> new ResourceNotFoundException("FinancialEvent", id));

                BigDecimal oldFact = event.getFactAmount();
                event.setFactAmount(dto.factAmount());
                if (dto.description() != null) {
                        event.setDescription(dto.description());
                }

                // Авто-статус по наличию фактической суммы
                if (dto.factAmount() != null && event.getStatus() == EventStatus.PLANNED) {
                        event.setStatus(EventStatus.EXECUTED);
                } else if (dto.factAmount() == null && event.getStatus() == EventStatus.EXECUTED) {
                        event.setStatus(EventStatus.PLANNED);
                }

                // Если это FUND_TRANSFER и исполняется впервые — выполняем реальный перевод в фонд
                if (event.getType() == EventType.FUND_TRANSFER
                                && event.getTargetFundId() != null
                                && dto.factAmount() != null
                                && oldFact == null
                                && event.getIdempotencyKey() != null) {
                        targetFundService.doTransferForEvent(
                                        event.getTargetFundId(), dto.factAmount(), event.getIdempotencyKey());
                }

                // Аудит-лог
                BigDecimal delta = (dto.factAmount() != null ? dto.factAmount() : BigDecimal.ZERO)
                                .subtract(oldFact != null ? oldFact : BigDecimal.ZERO);
                log.info("fact_patch event_id={} category={} fact_old={} fact_new={} delta={}",
                                id, event.getCategory().getName(), oldFact, dto.factAmount(), delta);

                return toDto(eventRepository.save(event));
        }

        /**
         * Циклически меняет приоритет события: HIGH → MEDIUM → LOW → HIGH.
         *
         * @param id идентификатор события
         * @return обновлённое событие
         * @throws ResourceNotFoundException если событие не найдено или удалено
         */
        @Transactional
        public FinancialEventDto cyclePriority(UUID id) {
                FinancialEvent event = eventRepository.findById(id)
                                .filter(e -> !e.isDeleted())
                                .orElseThrow(() -> new ResourceNotFoundException("FinancialEvent", id));
                event.setPriority(nextPriority(event.getPriority()));
                return toDto(eventRepository.save(event));
        }

        private Priority nextPriority(Priority current) {
                return switch (current) {
                        case HIGH -> Priority.MEDIUM;
                        case MEDIUM -> Priority.LOW;
                        case LOW -> Priority.HIGH;
                };
        }

        /**
         * Возвращает нереализованные хотелки: события с приоритетом LOW,
         * статусом PLANNED и датой раньше сегодня.
         *
         * @return список DTO, отсортированный по дате по возрастанию
         */
        public List<FinancialEventDto> findWishlist() {
                return eventRepository.findAllByDeletedFalseAndPriorityAndStatusAndDateBeforeOrderByDateAsc(
                                Priority.LOW, EventStatus.PLANNED, LocalDate.now())
                                .stream().map(this::toDto).toList();
        }

        /**
         * Помечает событие как удалённое (soft delete).
         * Событие не удаляется физически и не возвращается в запросах по периоду,
         * но сохраняется в БД для аудита.
         *
         * @param id идентификатор события
         * @throws ResourceNotFoundException если событие не найдено
         */
        @Transactional
        public void softDelete(UUID id) {
                FinancialEvent event = eventRepository.findById(id)
                                .orElseThrow(() -> new ResourceNotFoundException("FinancialEvent", id));
                event.setDeleted(true);
                eventRepository.save(event);
        }

        /**
         * Конвертирует entity в DTO для передачи клиенту.
         * Включает денормализованные поля категории ({@code categoryId}, {@code categoryName})
         * для удобства рендеринга без дополнительных запросов.
         *
         * @param e entity финансового события
         * @return DTO с полными данными события
         */
        public FinancialEventDto toDto(FinancialEvent e) {
                String fundName = null;
                if (e.getTargetFundId() != null) {
                        fundName = targetFundRepository.findById(e.getTargetFundId())
                                        .map(f -> f.getName())
                                        .orElse(null);
                }
                return new FinancialEventDto(
                                e.getId(), e.getDate(),
                                e.getCategory().getId(), e.getCategory().getName(),
                                e.getType(), e.getPlannedAmount(), e.getFactAmount(),
                                e.getStatus(), e.getPriority(), e.getDescription(),
                                e.getRawInput(), e.getCreatedAt(),
                                e.getTargetFundId(), fundName);
        }
}
