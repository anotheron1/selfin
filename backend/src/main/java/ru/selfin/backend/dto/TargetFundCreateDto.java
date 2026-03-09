package ru.selfin.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

public record TargetFundCreateDto(
                @NotBlank String name,
                @NotNull @PositiveOrZero BigDecimal targetAmount,
                Integer priority,
                java.time.LocalDate targetDate) {
}
