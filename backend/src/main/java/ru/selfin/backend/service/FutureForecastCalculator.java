package ru.selfin.backend.service;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Прогноз трат на БУДУЩИЕ месяцы (ANO-36). Pure, без Spring.
 *
 * <p>Задача: до этого кармашек на горизонте 3–6 месяцев видел только план, а план
 * содержит примерно половину реальной жизни (регулярные платежи). Баланс рос монотонно,
 * минимум оставался в текущем месяце, и «2-й доход», «3 мес» и «6 мес» давали одно
 * и то же число — формально верное, но обещавшее благополучие, которого нет.
 *
 * <h2>Правило: план есть план, прогноз отдельно</h2>
 *
 * Медиана по категории — это ВСЁ, что обычно тратится в ней за месяц, а не добавка
 * к плану. План — часть той же траты, просто посчитанная заранее. Поэтому:
 *
 * <pre>ожидание(категория, месяц) = max(план, медиана)</pre>
 *
 * Плановые события уже вычтены из траектории, поэтому прогноз добавляет только разницу:
 *
 * <pre>прогноз(категория, месяц) = max(0, медиана − план)</pre>
 *
 * Для «Продуктов» с планом 32 000 и медианой 35 818 это даёт 3 818 сверх плана,
 * то есть 35 818 всего — а не 67 818, как получилось бы при сложении.
 *
 * <p>Тот же принцип уже действует в прогнозе текущего месяца
 * ({@code PredictionService.forecastFromEvents} копит netDelta только по категориям
 * БЕЗ планов) — здесь он обобщён на категории с планом через разницу.
 *
 * <p>Величина возвращается отдельно по месяцам и нигде не смешивается с планом:
 * движок держит её как самостоятельный {@code forecastCum} и показывает отдельной
 * строкой breakdown.
 */
public final class FutureForecastCalculator {

    private FutureForecastCalculator() {}

    /**
     * Сколько ожидается СВЕРХ плана в каждом будущем месяце.
     *
     * @param medians       категория → медианная трата за месяц по истории; категории
     *                      с недостаточной историей caller обязан отсеять заранее
     * @param plannedByMonth (месяц, категория) → сумма плановых расходов этого месяца
     * @param months        месяцы, для которых нужен прогноз (обычно от следующего до горизонта)
     * @return месяц → сумма сверх плана (≥ 0); месяцы без превышения присутствуют с нулём
     */
    public static Map<YearMonth, BigDecimal> forecastByMonth(
            Map<UUID, BigDecimal> medians,
            Map<YearMonth, Map<UUID, BigDecimal>> plannedByMonth,
            Iterable<YearMonth> months) {

        Map<YearMonth, BigDecimal> result = new LinkedHashMap<>();
        for (YearMonth ym : months) {
            Map<UUID, BigDecimal> plans = plannedByMonth.getOrDefault(ym, Map.of());
            BigDecimal total = BigDecimal.ZERO;
            for (Map.Entry<UUID, BigDecimal> e : medians.entrySet()) {
                BigDecimal median = e.getValue() != null ? e.getValue() : BigDecimal.ZERO;
                if (median.signum() <= 0) continue;
                BigDecimal planned = plans.getOrDefault(e.getKey(), BigDecimal.ZERO);
                BigDecimal beyondPlan = median.subtract(planned);
                if (beyondPlan.signum() > 0) total = total.add(beyondPlan);
            }
            result.put(ym, total);
        }
        return result;
    }
}
