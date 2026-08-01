package ru.selfin.backend.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import ru.selfin.backend.model.enums.Priority;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * @param rawInput исходный текст суммы, если её ввели выражением («450+1230+890», ANO-33).
 *                 Хранится как есть, чтобы потом было видно, из чего сложился факт.
 */
public record FactCreateDto(
        @NotNull LocalDate date,
        @NotNull @Positive BigDecimal factAmount,
        String description,
        Priority priority,
        String rawInput) {
}
