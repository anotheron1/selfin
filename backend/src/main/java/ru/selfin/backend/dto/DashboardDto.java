package ru.selfin.backend.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Ответ дашборда после ANO-14: только прогресс-бары план/факт.
 *
 * <p>Баланс, зарплатные горизонты и алерт кассового разрыва отдаёт кармашек
 * ({@code GET /pocket}: currentBalance, horizon, minPoint) — одна истина,
 * много представлений. Прежние поля этого DTO удалены как параллельный расчёт.
 *
 * @param progressBars список прогресс-баров расходных категорий: план vs факт
 *                     за текущий месяц целиком
 */
public record DashboardDto(List<CategoryProgressBar> progressBars) {

    /**
     * Прогресс-бар исполнения бюджета по категории расходов.
     *
     * @param categoryName      название категории
     * @param currentFact       суммарный факт за текущий месяц
     * @param plannedLimit      суммарный план за текущий месяц
     * @param percentage        процент исполнения: {@code fact/plan * 100} (0..∞, >100 = перерасход)
     * @param projectionAmount  прогнозная сумма на конец месяца; {@code null} если {@code forecastEnabled = false}
     * @param forecastEnabled   включен ли прогноз по категории
     * @param history           история дневных точек прогноза; пусто если {@code forecastEnabled = false}
     */
    public record CategoryProgressBar(
            String categoryName,
            BigDecimal currentFact,
            BigDecimal plannedLimit,
            int percentage,
            BigDecimal projectionAmount,
            boolean forecastEnabled,
            List<DailyForecastPointDto> history) {
    }
}
