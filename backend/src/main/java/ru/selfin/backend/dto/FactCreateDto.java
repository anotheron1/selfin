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
 * @param onCredit  «в кредит» — у записи к плану не бывает (срез 4, п. 1): {@code true} — 400. Поле есть, чтобы
 *                  отказ был виден: без него отметку молча съел бы разбор тела
 */
public record FactCreateDto(
        @NotNull LocalDate date,
        @NotNull @Positive BigDecimal factAmount,
        String description,
        Priority priority,
        String rawInput,
        Boolean closePlan,
        Boolean onCredit) {

    /** Без «Больше не нужно» — остаток брони держится. */
    public FactCreateDto(LocalDate date, BigDecimal factAmount, String description, Priority priority,
                         String rawInput) {
        this(date, factAmount, description, priority, rawInput, null, null);
    }

    public FactCreateDto(LocalDate date, BigDecimal factAmount, String description, Priority priority,
                         String rawInput, Boolean closePlan) {
        this(date, factAmount, description, priority, rawInput, closePlan, null);
    }
}
