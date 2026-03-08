package ru.selfin.backend.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.LocalDate;

public record BalanceCheckpointCreateDto(
        @NotNull LocalDate date,
        @NotNull @PositiveOrZero BigDecimal amount
) {}
