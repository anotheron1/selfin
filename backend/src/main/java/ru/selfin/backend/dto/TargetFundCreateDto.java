package ru.selfin.backend.dto;

import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;

public record TargetFundCreateDto(
                @NotBlank String name,
                BigDecimal targetAmount,
                Integer priority) {
}
