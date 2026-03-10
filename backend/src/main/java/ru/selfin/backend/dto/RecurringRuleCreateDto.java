package ru.selfin.backend.dto;

import jakarta.validation.constraints.AssertTrue;
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
) {

    @AssertTrue(message = "dayOfMonth (1-28) required for MONTHLY frequency")
    public boolean isDayOfMonthValid() {
        if (frequency == RecurringFrequency.MONTHLY) {
            return dayOfMonth != null && dayOfMonth >= 1 && dayOfMonth <= 28;
        }
        return true;
    }

    @AssertTrue(message = "dayOfWeek required for WEEKLY frequency")
    public boolean isDayOfWeekValid() {
        if (frequency == RecurringFrequency.WEEKLY) {
            return dayOfWeek != null;
        }
        return true;
    }
}
