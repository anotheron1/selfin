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

    @Autowired @Lazy
    private TargetFundService targetFundService;

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

    @Transactional
    public FinancialEventDto cyclePriority(UUID id) {
        FinancialEvent event = eventRepository.findById(id)
                .filter(e -> !e.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("FinancialEvent", id));
        event.setPriority(nextPriority(event.getPriority()));
        return toDto(eventRepository.save(event), null, null);
    }

    private Priority nextPriority(Priority current) {
        return switch (current) {
            case HIGH -> Priority.MEDIUM;
            case MEDIUM -> Priority.LOW;
            case LOW -> Priority.HIGH;
        };
    }

    public List<FinancialEventDto> findWishlist() {
        return eventRepository.findWishlistItems(Priority.LOW, EventStatus.PLANNED, LocalDate.now())
                .stream().map(e -> toDto(e, null, null)).toList();
    }

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

    @Transactional
    public FinancialEventDto createWishlistItem(WishlistCreateDto dto) {
        Category category = categoryRepository.findByNameAndDeletedFalse("Хотелки")
                .orElseGet(() -> categoryRepository.save(
                        Category.builder()
                                .name("Хотелки")
                                .type(CategoryType.EXPENSE)
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
