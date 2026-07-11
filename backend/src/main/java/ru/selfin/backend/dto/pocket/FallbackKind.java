package ru.selfin.backend.dto.pocket;

/**
 * Тип фолбэка горизонта (спека ANO-14 §4). Label горизонта обязан быть правдивым,
 * а boolean не различает «доходов нет вовсе» и «нашёлся только первый» — отсюда enum.
 * В DTO наружу уходит по-прежнему boolean: {@code Horizon.fallback = kind != NONE}.
 */
public enum FallbackKind {
    /** Горизонт заякорен ровно так, как просил скоуп. */
    NONE,
    /** Плановых доходов в окне поиска нет вовсе — условные asOf+30 дней. */
    NO_INCOMES,
    /** SECOND_INCOME: найден только первый доход — горизонт max(доход, asOf+30) накрывает его. */
    SECOND_NOT_FOUND
}
