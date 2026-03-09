package ru.selfin.backend.dto;

import ru.selfin.backend.model.enums.EventStatus;
import ru.selfin.backend.model.enums.EventType;

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
        boolean mandatory,
        String description,
        String rawInput,
        LocalDateTime createdAt,
        UUID recurringRuleId        // NEW
) {
}
