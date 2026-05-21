package ru.selfin.backend.dto.strategy;

import java.math.BigDecimal;

/**
 * Один элемент разбивки за месяц (для tooltip).
 *
 * @param category    название категории (готовое для отображения)
 * @param amount      сумма (положительная для income и expense; знак подразумевается типом списка)
 * @param isRecurring флаг recurring-события (для иконки ↻ во фронте)
 * @param isPredicted флаг прогнозного значения (для пометки «прогноз» во фронте)
 */
public record BreakdownItemDto(
        String category,
        BigDecimal amount,
        boolean isRecurring,
        boolean isPredicted
) {}
