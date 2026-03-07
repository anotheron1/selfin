package ru.selfin.backend.dto;

import jakarta.validation.constraints.NotNull;
import ru.selfin.backend.model.enums.EventType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record FinancialEventCreateDto(
                @NotNull LocalDate date,
                @NotNull UUID categoryId,
                @NotNull EventType type,
                BigDecimal plannedAmount,
                BigDecimal factAmount,
                Boolean mandatory,
                String description,
                String rawInput) {
}
