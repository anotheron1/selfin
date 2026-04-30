package ru.selfin.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
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

    public record CreateResult(RecurringRule rule, List<FinancialEvent> events) {}

    @Transactional
    public CreateResult createFromDto(Category category, EventType type, BigDecimal plannedAmount,
                                      Priority priority, String description, RecurringConfigDto cfg) {
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
        eventRepo.saveAll(events);
        return new CreateResult(rule, events);
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
