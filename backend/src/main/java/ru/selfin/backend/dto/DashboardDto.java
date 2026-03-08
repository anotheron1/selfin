package ru.selfin.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Агрегированный ответ для Дашборда.
 * Формируется аналитическим сервисом одним синхронным запросом по событиям текущего месяца.
 *
 * @param currentBalance      текущий баланс на сегодня: сумма факт-доходов минус факт-расходов
 *                            (при отсутствии факта используется план)
 * @param endOfMonthForecast  прогноз баланса на конец месяца: текущий баланс плюс плановые
 *                            суммы будущих событий
 * @param cashGapAlert        первый день потенциального кассового разрыва в текущем месяце,
 *                            или {@code null} если разрыва нет
 * @param progressBars        список прогресс-баров расходных категорий: план vs факт
 */
public record DashboardDto(
        BigDecimal currentBalance,
        BigDecimal endOfMonthForecast,
        CashGapAlert cashGapAlert,
        List<CategoryProgressBar> progressBars) {

    /**
     * Алерт о кассовом разрыве: первый день когда нарастающий баланс уходит в минус.
     *
     * @param gapDate   дата первого дня с отрицательным балансом
     * @param gapAmount сумма дефицита (отрицательное значение)
     */
    public record CashGapAlert(
            LocalDate gapDate,
            BigDecimal gapAmount) {
    }

    /**
     * Прогресс-бар исполнения бюджета по категории расходов.
     *
     * @param categoryName  название категории
     * @param currentFact   суммарный факт за текущий месяц
     * @param plannedLimit  суммарный план за текущий месяц
     * @param percentage    процент исполнения: {@code fact/plan * 100} (0..∞, >100 = перерасход)
     */
    public record CategoryProgressBar(
            String categoryName,
            BigDecimal currentFact,
            BigDecimal plannedLimit,
            int percentage) {
    }
}
