package ru.selfin.backend.dto;

import ru.selfin.backend.model.EventKind;
import ru.selfin.backend.model.enums.EventStatus;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.Priority;
import ru.selfin.backend.model.enums.RecurringFrequency;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

public record FinancialEventDto(
        UUID id,
        LocalDate date,
        UUID categoryId,
        String categoryName,
        EventType type,
        BigDecimal plannedAmount,
        BigDecimal factAmount,
        EventStatus status,
        Priority priority,
        String description,
        String rawInput,
        LocalDateTime createdAt,
        UUID targetFundId,
        String targetFundName,
        String url,
        // Plan/Fact split fields
        EventKind eventKind,
        UUID parentEventId,
        int linkedFactsCount,
        BigDecimal linkedFactsAmount,
        String parentPlanDescription,
        // Recurring fields (null for non-recurring events)
        UUID recurringRuleId,
        RecurringFrequency recurringFrequency,
        Integer recurringDayOfMonth,
        Integer recurringMonthOfYear) {
}
