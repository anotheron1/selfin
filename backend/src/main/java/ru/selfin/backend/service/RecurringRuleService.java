package ru.selfin.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ru.selfin.backend.dto.RecurringConfigDto;
import ru.selfin.backend.model.Category;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.RecurringRule;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.Priority;
import ru.selfin.backend.repository.FinancialEventRepository;
import ru.selfin.backend.repository.RecurringRuleRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class RecurringRuleService {

    private final RecurringRuleRepository ruleRepo;
    private final FinancialEventRepository eventRepo;
    private final RecurringEventGenerator generator;

    /**
     * @param rule   created and persisted rule (id assigned)
     * @param events generated PLAN events in ascending date order;
     *               {@code events.get(0)} is the earliest occurrence (head event).
     */
    public record CreateResult(RecurringRule rule, List<FinancialEvent> events) {}

    @Transactional
    public CreateResult createFromDto(Category category, EventType type, BigDecimal plannedAmount,
                                      Priority priority, String description,
                                      java.util.UUID targetFundId, String headRawInput,
                                      RecurringConfigDto cfg) {
        validateConfig(cfg);
        RecurringRule rule = RecurringRule.builder()
                .category(category)
                .eventType(type)
                .plannedAmount(plannedAmount)
                .priority(priority != null ? priority : Priority.MEDIUM)
                .description(description)
                .frequency(cfg.frequency())
                .dayOfMonth(cfg.dayOfMonth())
                .monthOfYear(cfg.monthOfYear())
                .startDate(cfg.startDate())
                .endDate(cfg.endDate())
                .build();
        rule = ruleRepo.save(rule);

        LocalDate horizonEnd = cfg.endDate() != null
                ? cfg.endDate()
                : LocalDate.now().plusMonths(36);
        List<FinancialEvent> events = generator.generate(rule, cfg.startDate(), horizonEnd);
        // Propagate targetFundId to ALL events (FUND_TRANSFER recurring needs it on every row).
        events.forEach(e -> e.setTargetFundId(targetFundId));
        // rawInput only on head event — represents user's original NL input at creation.
        if (!events.isEmpty() && headRawInput != null) {
            events.get(0).setRawInput(headRawInput);
        }
        eventRepo.saveAll(events);
        return new CreateResult(rule, events);
    }

    // Keep the 6-arg form delegating to the new 8-arg form for back-compat with Chunk-2 tests.
    @Transactional
    public CreateResult createFromDto(Category category, EventType type, BigDecimal plannedAmount,
                                      Priority priority, String description, RecurringConfigDto cfg) {
        return createFromDto(category, type, plannedAmount, priority, description, null, null, cfg);
    }

    @Transactional
    public void regenerate(RecurringRule rule, LocalDate from) {
        // Pessimistic lock on the rule prevents two parallel scope=ALL/FOLLOWING edits
        // (or one edit racing with extendIndefiniteRules) from producing
        // DataIntegrityViolationException on the partial unique index uq_events_rule_date_active.
        // Re-fetched rule supersedes the caller's reference for downstream operations.
        RecurringRule locked = ruleRepo.findForUpdate(rule.getId())
                .orElseThrow(() -> new IllegalStateException("rule disappeared: " + rule.getId()));

        LocalDate horizonEnd = locked.getEndDate() != null
                ? locked.getEndDate()
                : LocalDate.now().plusMonths(36);

        eventRepo.softDeletePlanEventsByRuleFromDate(locked.getId(), from);
        java.util.Set<LocalDate> executed = eventRepo.findExecutedDatesByRule(locked.getId());

        List<FinancialEvent> fresh = generator.generate(locked, from, horizonEnd).stream()
                .filter(e -> !executed.contains(e.getDate()))
                .toList();
        eventRepo.saveAll(fresh);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void extendIndefiniteRules(LocalDate requiredThrough) {
        for (java.util.UUID ruleId : ruleRepo.findIndefiniteActiveIds()) {
            // Per spec section 2 "Lazy-расширение бессрочных" — error handling:
            // "Если расширение упало — не заваливаем весь запрос планировщика,
            // логируем и читаем то, что есть." So wrap each rule independently.
            try {
                RecurringRule rule = ruleRepo.findForUpdate(ruleId)
                        .orElseThrow(() -> new IllegalStateException("rule disappeared: " + ruleId));
                LocalDate maxExisting = eventRepo.findMaxActiveDateByRule(ruleId).orElse(null);
                LocalDate from = (maxExisting != null) ? maxExisting.plusDays(1) : rule.getStartDate();
                if (from.isAfter(requiredThrough)) continue;
                List<FinancialEvent> extra = generator.generate(rule, from, requiredThrough);
                if (!extra.isEmpty()) {
                    eventRepo.saveAll(extra);
                }
            } catch (Exception e) {
                log.warn("Failed to extend indefinite rule {}: {}", ruleId, e.getMessage());
            }
        }
    }

    @Transactional
    public void deleteScope(FinancialEvent triggerEvent, ru.selfin.backend.model.enums.ScopeEnum scope) {
        RecurringRule rule = triggerEvent.getRecurringRule();
        if (rule == null) {
            throw new IllegalArgumentException("Scope requires recurring event");
        }
        LocalDate cutoff = (scope == ru.selfin.backend.model.enums.ScopeEnum.FOLLOWING)
                ? triggerEvent.getDate()
                : rule.getStartDate();
        eventRepo.softDeletePlanEventsByRuleFromDate(rule.getId(), cutoff);

        if (scope == ru.selfin.backend.model.enums.ScopeEnum.FOLLOWING) {
            rule.setEndDate(triggerEvent.getDate().minusDays(1));
        } else {
            rule.setDeleted(true);
            LocalDate lastExec = eventRepo.findMaxExecutedDateByRule(rule.getId()).orElse(null);
            rule.setEndDate(lastExec != null ? lastExec : rule.getStartDate().minusDays(1));
        }
        ruleRepo.save(rule);
    }

    /**
     * Применяет редактируемые поля из FinancialEventCreateDto к правилу.
     * Запрещено: смена startDate, type, frequency (см. spec, I8). Эти поля игнорируются
     * для frequency/type, но изменение startDate вызывает 400.
     * Также проверяет, что endDate не раньше startDate правила.
     */
    public void applyDtoToRule(RecurringRule rule, ru.selfin.backend.dto.FinancialEventCreateDto dto) {
        if (dto.recurring() != null && dto.recurring().startDate() != null
                && !dto.recurring().startDate().equals(rule.getStartDate())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "start_date is immutable; delete the rule and create a new one (I8)");
        }
        if (dto.recurring() != null && dto.recurring().endDate() != null
                && dto.recurring().endDate().isBefore(rule.getStartDate())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "endDate must be >= startDate (I2)");
        }
        // Editable per-rule fields:
        rule.setPlannedAmount(dto.plannedAmount());
        rule.setPriority(dto.priority() != null ? dto.priority() : rule.getPriority());
        rule.setDescription(dto.description());
        if (dto.recurring() != null) {
            // dayOfMonth / monthOfYear are conceptually editable; spec §3 leaves this as a
            // contract decision. We allow updating these — they only affect future regenerated
            // dates; existing EXECUTED dates are preserved by regenerate().
            rule.setDayOfMonth(dto.recurring().dayOfMonth());
            rule.setMonthOfYear(dto.recurring().monthOfYear());
            rule.setEndDate(dto.recurring().endDate());
        }
    }

    private void validateConfig(RecurringConfigDto cfg) {
        if (cfg.startDate() == null) {
            throw new IllegalArgumentException("startDate is required");
        }
        if (cfg.startDate().isBefore(LocalDate.now())) {
            throw new IllegalArgumentException("startDate must be today or later (I3)");
        }
        if (cfg.frequency() == ru.selfin.backend.model.enums.RecurringFrequency.YEARLY
                && cfg.monthOfYear() == null) {
            throw new IllegalArgumentException("monthOfYear required for YEARLY (I1)");
        }
        if (cfg.frequency() == ru.selfin.backend.model.enums.RecurringFrequency.MONTHLY
                && cfg.monthOfYear() != null) {
            throw new IllegalArgumentException("monthOfYear must be null for MONTHLY (I1)");
        }
        if (cfg.endDate() != null && cfg.endDate().isBefore(cfg.startDate())) {
            throw new IllegalArgumentException("endDate must be >= startDate (I2)");
        }
    }
}
