package ru.selfin.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import ru.selfin.backend.dto.DailyForecastPointDto;

/**
 * Агрегированный ответ для Дашборда.
 *
 * <p>Содержит текущий баланс и прогнозы для <b>двух зарплатных горизонтов</b>:
 * <ol>
 *   <li>Сколько останется <em>перед</em> ближайшей зп (низшая точка текущего периода).</li>
 *   <li>Сколько будет <em>после</em> получения зп (стартовый капитал следующего периода).</li>
 *   <li>Сколько останется <em>перед</em> следующей следующей зп (низшая точка второго периода).</li>
 * </ol>
 * Это позволяет увидеть кассовый разрыв или стрессовый минимум не только в текущем
 * периоде, но и в следующем — даже если баланс в ноль не уходит.
 *
 * <p>Формируется аналитическим сервисом одним синхронным запросом.
 * CQRS и кэширование не применяются — скорость PostgreSQL на данных
 * одного пользователя достаточна.
 *
 * @param currentBalance              текущий баланс на сегодня: факт-доходы минус факт-расходы
 *                                    (начиная от последнего {@code BalanceCheckpoint})
 * @param endOfMonthForecast          прогноз баланса на конец текущего месяца;
 *                                    используется как запасное отображение если нет зп-событий
 * @param nextSalaryDate              дата ближайшего запланированного (не исполненного) INCOME-события,
 *                                    строго после сегодня; {@code null} если таких событий нет в горизонте 70 дней
 * @param balanceBeforeNextSalary     прогнозный баланс в последний день перед {@code nextSalaryDate};
 *                                    отражает «низшую точку» текущего периода — насколько туго будет
 *                                    до следующей зп; {@code null} если {@code nextSalaryDate == null}
 * @param balanceAfterNextSalary      прогнозный баланс в конце дня {@code nextSalaryDate}, включая
 *                                    само поступление зп и все прочие события того же дня;
 *                                    показывает «стартовый капитал» следующего периода;
 *                                    {@code null} если {@code nextSalaryDate == null}
 * @param secondSalaryDate            дата второго ближайшего запланированного INCOME-события;
 *                                    {@code null} если второго события нет в горизонте 70 дней
 * @param balanceBeforeSecondSalary   прогнозный баланс в последний день перед {@code secondSalaryDate};
 *                                    отражает «низшую точку» второго периода;
 *                                    {@code null} если {@code secondSalaryDate == null}
 * @param cashGapAlert                первый день потенциального кассового разрыва в горизонте
 *                                    планирования (до второй зп или конца месяца), или {@code null}
 *                                    если баланс остаётся неотрицательным
 * @param progressBars                список прогресс-баров расходных категорий: план vs факт
 *                                    за текущий месяц целиком
 */
public record DashboardDto(
        BigDecimal currentBalance,
        BigDecimal endOfMonthForecast,
        LocalDate nextSalaryDate,
        BigDecimal balanceBeforeNextSalary,
        BigDecimal balanceAfterNextSalary,
        LocalDate secondSalaryDate,
        BigDecimal balanceBeforeSecondSalary,
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
