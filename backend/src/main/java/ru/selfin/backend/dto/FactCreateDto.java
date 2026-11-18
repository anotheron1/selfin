package ru.selfin.backend.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import ru.selfin.backend.model.enums.Priority;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * @param rawInput  исходный текст суммы, если её ввели выражением («450+1230+890», ANO-33).
 *                  Хранится как есть, чтобы потом было видно, из чего сложился факт.
 * @param closePlan «Больше не нужно» (срез 3 п. 3, решение 52): записано меньше остатка брони, и
 *                  остаток не нужен — сумма брони становится равной погашенному, бронь исполнена.
 *                  Только у брони; {@code null} и {@code false} — «Доплачу позже», остаток держится
 */
public record FactCreateDto(
        @NotNull LocalDate date,
        @NotNull @Positive BigDecimal factAmount,
        String description,
        Priority priority,
        String rawInput,
        Boolean closePlan) {

    /** Без «Больше не нужно» — остаток брони держится. */
    public FactCreateDto(LocalDate date, BigDecimal factAmount, String description, Priority priority,
                         String rawInput) {
        this(date, factAmount, description, priority, rawInput, null);
    }
}
