package ru.selfin.backend.dto;

import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.RecurringFrequency;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.UUID;

public record RecurringRuleDto(
        UUID id,
        UUID categoryId,
        String categoryName,
        EventType eventType,
        UUID targetFundId,
        BigDecimal plannedAmount,
        boolean mandatory,
        String description,
        RecurringFrequency frequency,
        Integer dayOfMonth,
        DayOfWeek dayOfWeek,
        LocalDate startDate,
        LocalDate endDate
) {}
