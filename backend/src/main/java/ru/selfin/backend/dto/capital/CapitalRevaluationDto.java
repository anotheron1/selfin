package ru.selfin.backend.dto.capital;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record CapitalRevaluationDto(
        UUID id,
        UUID itemId,
        BigDecimal value,
        LocalDate valuedAt,
        String note,
        Instant createdAt
) {}
