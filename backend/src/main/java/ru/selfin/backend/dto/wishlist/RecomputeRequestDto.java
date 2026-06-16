package ru.selfin.backend.dto.wishlist;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Запрос на пересчёт delta-вектора одного item'а при изменении слайдеров суммы/даты
 * (без сохранения). Поля {@code rate}/{@code termMonths} — только для CREDIT.
 *
 * @param kind       WISHLIST | SAVINGS | CREDIT
 * @param amount     сумма
 * @param targetDate целевая дата
 * @param rate       годовая ставка (CREDIT)
 * @param termMonths срок в месяцах (CREDIT)
 */
public record RecomputeRequestDto(
        String kind,
        BigDecimal amount,
        LocalDate targetDate,
        BigDecimal rate,
        Integer termMonths
) {}
