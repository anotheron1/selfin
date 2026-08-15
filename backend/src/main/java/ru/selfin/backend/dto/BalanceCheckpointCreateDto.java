package ru.selfin.backend.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Ре-якорь остатка (ANO-15). Счёт указывать НЕ обязательно.
 *
 * @param accountId чей остаток вводится. {@code null} — счёт-приёмник по умолчанию. Так «ввёл
 *                  остаток» днём 1 остаётся однокликовым, а старый фронт продолжает работать
 *                  без правок (план Task 3.3). Для {@code AccountKind.CREDIT} сюда вводится
 *                  ДОСТУПНЫЙ остаток, а не долг (§3.2).
 */
public record BalanceCheckpointCreateDto(
        @NotNull @PastOrPresent LocalDate date,
        @NotNull @PositiveOrZero BigDecimal amount,
        UUID accountId
) {

    /** Совместимость с местами, где счёт не выбирается: остаток попадёт на счёт-приёмник. */
    public BalanceCheckpointCreateDto(LocalDate date, BigDecimal amount) {
        this(date, amount, null);
    }
}
