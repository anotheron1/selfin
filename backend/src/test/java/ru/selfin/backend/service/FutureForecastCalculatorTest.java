package ru.selfin.backend.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Правило «план есть план, прогноз отдельно» (ANO-36). */
class FutureForecastCalculatorTest {

    private static final UUID PRODUCTS = UUID.randomUUID();
    private static final UUID CAFE = UUID.randomUUID();
    private static final YearMonth SEP = YearMonth.of(2026, 9);
    private static final YearMonth OCT = YearMonth.of(2026, 10);

    @Test
    void categoryWithPlan_addsOnlyTheDifference_notTheWholeMedian() {
        // Реальный случай Кирилла: «Продукты» план 32 000, медиана 35 818.
        // Сложение дало бы 67 818 — «съел на две зарплаты продуктов».
        var result = FutureForecastCalculator.forecastByMonth(
                Map.of(PRODUCTS, new BigDecimal("35818")),
                Map.of(SEP, Map.of(PRODUCTS, new BigDecimal("32000"))),
                List.of(SEP));

        assertThat(result.get(SEP)).isEqualByComparingTo("3818");
    }

    @Test
    void categoryWithoutPlan_addsWholeMedian() {
        // «Кафе, рестики» — плана нет, значит вся медиана это трата сверх плана
        var result = FutureForecastCalculator.forecastByMonth(
                Map.of(CAFE, new BigDecimal("15134")),
                Map.of(SEP, Map.of()),
                List.of(SEP));

        assertThat(result.get(SEP)).isEqualByComparingTo("15134");
    }

    @Test
    void planAboveMedian_addsNothing_andNeverGoesNegative() {
        // Запланировал больше, чем обычно тратишь — прогноз не должен «возвращать» деньги
        var result = FutureForecastCalculator.forecastByMonth(
                Map.of(PRODUCTS, new BigDecimal("30000")),
                Map.of(SEP, Map.of(PRODUCTS, new BigDecimal("50000"))),
                List.of(SEP));

        assertThat(result.get(SEP)).isEqualByComparingTo("0");
    }

    @Test
    void sumsAcrossCategories_perMonthIndependently() {
        var result = FutureForecastCalculator.forecastByMonth(
                Map.of(PRODUCTS, new BigDecimal("35818"), CAFE, new BigDecimal("15134")),
                Map.of(
                        SEP, Map.of(PRODUCTS, new BigDecimal("32000")),
                        OCT, Map.of()   // в октябре планов нет вовсе
                ),
                List.of(SEP, OCT));

        assertThat(result.get(SEP)).isEqualByComparingTo("18952");   // 3818 + 15134
        assertThat(result.get(OCT)).isEqualByComparingTo("50952");   // 35818 + 15134
    }

    @Test
    void zeroOrMissingMedian_isIgnored() {
        var result = FutureForecastCalculator.forecastByMonth(
                Map.of(PRODUCTS, BigDecimal.ZERO),
                Map.of(SEP, Map.of()),
                List.of(SEP));

        assertThat(result.get(SEP)).isEqualByComparingTo("0");
    }

    @Test
    void monthWithoutForecast_isStillPresent() {
        // Явный ноль лучше отсутствующего ключа: движок не должен угадывать
        var result = FutureForecastCalculator.forecastByMonth(
                Map.of(), Map.of(), List.of(SEP, OCT));

        assertThat(result).containsOnlyKeys(SEP, OCT);
        assertThat(result.get(SEP)).isEqualByComparingTo("0");
    }
}
