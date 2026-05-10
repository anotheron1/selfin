package ru.selfin.backend.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import ru.selfin.backend.model.enums.RecurringFrequency;

import java.time.LocalDate;

/**
 * Под-DTO для recurring-блока в FinancialEventCreateDto / EventUpdateDto.
 * Поле {@code startDate} трактуется как read-only после создания (см. spec, I8).
 */
public record RecurringConfigDto(
        @NotNull RecurringFrequency frequency,
        @NotNull @Min(1) @Max(31) Integer dayOfMonth,
        @Min(1) @Max(12) Integer monthOfYear,   // только для YEARLY
        LocalDate startDate,                    // на edit игнорируется/отвергается
        LocalDate endDate                       // null = бессрочно
) {}
