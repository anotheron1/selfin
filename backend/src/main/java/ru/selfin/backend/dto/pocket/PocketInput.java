package ru.selfin.backend.dto.pocket;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Вход чистого движка. Собирается PocketService (спека §3.1).
 *
 * @param events          события для БАЛАНСА и ТРАЕКТОРИИ (диапазон дат: чекпоинт..горизонт)
 * @param wishlistEvents  отдельная выборка хотелок (OPEN + FIXED) — date-range их не достаёт
 * @param overdueEvents   просроченные обязательные PLAN(PLANNED) HIGH EXPENSE без FACT-детей,
 *                        БЕЗ границы месяца (спека §3.4)
 * @param checkpointDate  null = чекпоинта нет, баланс от нуля
 * @param fallbackKind    тип фолбэка горизонта (NONE = заякорен как просил скоуп);
 *                        различает «доходов нет» и «второй не найден» для правдивого label
 * @param unplannedForecast прогноз незапланированных трат текущего месяца (≥ 0)
 * @param forecastContributors имена категорий-виновников прогноза (для details)
 * @param futureForecast прогноз СВЕРХ ПЛАНА по будущим месяцам (ANO-36): месяц → сумма ≥ 0.
 *                       План уже сидит в {@code events}, поэтому здесь только разница
 *                       {@code max(0, медиана − план)} — иначе трата считалась бы дважды.
 *                       Отдельная величина, с планом не смешивается.
 * @param otherAccountsBalance свободные деньги с прочих счетов, кроме дефолтного (спека §4.1,
 *                       ANO-9 Task 2.2). Дефолтный считает сам движок через
 *                       {@code checkpointAmount}/{@code events} — суммировать его здесь ещё
 *                       раз значит задвоение.
 * @param creditRestoreReserve резерв возврата кредитных карт к планке (§4.2). Используется
 *                       в Task 4.1, здесь только проносится через вход.
 * @param semiLiquidBalance полу-ликвид: вклады, для третьего числа кармашка (§4.3).
 *                       Используется в Task 4.1.
 */
public record PocketInput(
        LocalDate asOfDate,
        BigDecimal checkpointAmount,
        LocalDate checkpointDate,
        List<EventSnapshot> events,
        List<EventSnapshot> wishlistEvents,
        List<EventSnapshot> overdueEvents,
        PocketScope scope,
        LocalDate horizonEnd,
        FallbackKind fallbackKind,
        BigDecimal bufferAmount,
        BigDecimal unplannedForecast,
        List<String> forecastContributors,
        java.util.Map<java.time.YearMonth, BigDecimal> futureForecast,
        BigDecimal otherAccountsBalance,
        BigDecimal creditRestoreReserve,
        BigDecimal semiLiquidBalance
) {
    /** Прогноз будущих месяцев, безопасный к null (старые вызовы/тесты). */
    public java.util.Map<java.time.YearMonth, BigDecimal> futureForecastOrEmpty() {
        return futureForecast != null ? futureForecast : java.util.Map.of();
    }

    public BigDecimal otherAccountsBalanceOrZero() { return orZero(otherAccountsBalance); }
    public BigDecimal creditRestoreReserveOrZero() { return orZero(creditRestoreReserve); }
    public BigDecimal semiLiquidBalanceOrZero() { return orZero(semiLiquidBalance); }

    private static BigDecimal orZero(BigDecimal v) { return v != null ? v : BigDecimal.ZERO; }
}
