package ru.selfin.backend.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.RecurringFrequency;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.UUID;

public record RecurringRuleCreateDto(
        @NotNull UUID categoryId,
        @NotNull EventType eventType,
        UUID targetFundId,
        @NotNull @PositiveOrZero BigDecimal plannedAmount,
        Boolean mandatory,
        String description,
        @NotNull RecurringFrequency frequency,
        Integer dayOfMonth,
        DayOfWeek dayOfWeek,
        @NotNull LocalDate startDate,
        LocalDate endDate
) {}
