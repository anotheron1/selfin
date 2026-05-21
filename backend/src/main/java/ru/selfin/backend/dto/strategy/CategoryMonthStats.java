package ru.selfin.backend.dto.strategy;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Возврат метода {@code PredictionService.getStatsForCategory()}.
 * См. spec, раздел 3.
 *
 * @param categoryId      идентификатор категории
 * @param monthsOfHistory сколько месяцев истории учтено (0..historyWindowMonths)
 * @param median          медиана (P50) траты по месяцам
 * @param p25             P25 траты
 * @param p75             P75 траты
 */
public record CategoryMonthStats(
        UUID categoryId,
        int monthsOfHistory,
        BigDecimal median,
        BigDecimal p25,
        BigDecimal p75
) {}
