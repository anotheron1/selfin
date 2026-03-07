package ru.selfin.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Агрегированный ответ для Дашборда.
 * Формируется аналитическим сервисом одним синхронным запросом.
 */
public record DashboardDto(
        BigDecimal currentBalance,
        BigDecimal endOfMonthForecast,
        CashGapAlert cashGapAlert,
        List<CategoryProgressBar> progressBars) {

    public record CashGapAlert(
            LocalDate gapDate,
            BigDecimal gapAmount) {
    }

    public record CategoryProgressBar(
            String categoryName,
            BigDecimal currentFact,
            BigDecimal plannedLimit,
            int percentage) {
    }
}
