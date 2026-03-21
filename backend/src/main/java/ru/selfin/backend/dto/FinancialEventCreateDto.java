package ru.selfin.backend.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.Priority;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record FinancialEventCreateDto(
                @NotNull LocalDate date,
                UUID categoryId,
                @NotNull EventType type,
                @PositiveOrZero BigDecimal plannedAmount,
                Priority priority,
                String description,
                String rawInput,
                UUID targetFundId) {
}
