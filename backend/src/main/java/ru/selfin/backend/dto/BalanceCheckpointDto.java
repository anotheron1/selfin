package ru.selfin.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

public record BalanceCheckpointDto(
        UUID id,
        LocalDate date,
        BigDecimal amount,
        LocalDateTime createdAt
) {}
