package ru.selfin.backend.dto.strategy;

import java.util.List;

/**
 * Контейнер разбивки точки timeline на income и expense items.
 * Заполняется, только если запрос пришёл с {@code withBreakdown=true}.
 */
public record BreakdownDto(
        List<BreakdownItemDto> incomeItems,
        List<BreakdownItemDto> expenseItems
) {}
