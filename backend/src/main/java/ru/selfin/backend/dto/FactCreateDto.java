package ru.selfin.backend.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import ru.selfin.backend.model.enums.Priority;

import java.math.BigDecimal;
import java.time.LocalDate;

public record FactCreateDto(
        @NotNull LocalDate date,
        @NotNull @Positive BigDecimal factAmount,
        String description,
        Priority priority) {
}
