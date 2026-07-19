package ru.selfin.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.selfin.backend.dto.pocket.EventSnapshot;
import ru.selfin.backend.dto.pocket.SyntheticKind;
import ru.selfin.backend.model.EventKind;
import ru.selfin.backend.model.enums.EventStatus;
import ru.selfin.backend.model.enums.EventType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Табличные тесты раскладки «item → дневные события» (спека sandbox §5).
 * Фикстура: asOf = 2026-07-18; плановые доходы 28.07, 15.08, 28.08, 15.09.
 */
class SandboxLayoutTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 7, 18);
    private static final List<LocalDate> INCOMES = List.of(
            LocalDate.of(2026, 7, 28),
            LocalDate.of(2026, 8, 15),
            LocalDate.of(2026, 8, 28),
            LocalDate.of(2026, 9, 15));

    // ── maxStretchMonths ────────────────────────────────────────────────────

    @Test
    @DisplayName("maxStretchMonths: след. месяц..месяц цели включительно")
    void maxStretchMonths_countsFollowingToTargetMonth() {
        assertThat(SandboxLayout.maxStretchMonths(AS_OF, LocalDate.of(2026, 12, 14))).isEqualTo(5);
        assertThat(SandboxLayout.maxStretchMonths(AS_OF, LocalDate.of(2026, 8, 10))).isEqualTo(1);
    }

    @Test
    @DisplayName("maxStretchMonths: цель в текущем месяце или прошлом → ≤ 0")
    void maxStretchMonths_currentOrPastMonth_notPositive() {
        assertThat(SandboxLayout.maxStretchMonths(AS_OF, LocalDate.of(2026, 7, 25))).isZero();
        assertThat(SandboxLayout.maxStretchMonths(AS_OF, LocalDate.of(2026, 6, 10))).isLessThan(0);
    }

    // ── layoutSavings ───────────────────────────────────────────────────────

    @Test
    @DisplayName("savings n=max: день = первый доход месяца; без дохода — 1-е число")
    void savings_fullStretch_daysFollowFirstIncome() {
        List<EventSnapshot> out = SandboxLayout.layoutSavings("Горнолыжка",
                new BigDecimal("80000.00"), LocalDate.of(2026, 12, 14), 5, AS_OF, INCOMES,
                SyntheticKind.SAVINGS_CONTRIBUTION);

        assertThat(out).extracting(EventSnapshot::date).containsExactly(
                LocalDate.of(2026, 8, 15),   // первый доход августа
                LocalDate.of(2026, 9, 15),
                LocalDate.of(2026, 10, 1),   // доходов в фикстуре нет → 1-е
                LocalDate.of(2026, 11, 1),
                LocalDate.of(2026, 12, 1));
        assertThat(out).extracting(EventSnapshot::plannedAmount)
                .allSatisfy(a -> assertThat(a).isEqualByComparingTo("16000.00"));
    }

    @Test
    @DisplayName("savings n < max: первые n месяцев, к месяцу цели не прижимаются")
    void savings_shortStretch_hugsStart() {
        List<EventSnapshot> out = SandboxLayout.layoutSavings("Горнолыжка",
                new BigDecimal("80000.00"), LocalDate.of(2026, 12, 14), 2, AS_OF, INCOMES,
                SyntheticKind.SAVINGS_CONTRIBUTION);

        assertThat(out).extracting(EventSnapshot::date).containsExactly(
                LocalDate.of(2026, 8, 15), LocalDate.of(2026, 9, 15));
        assertThat(out).extracting(EventSnapshot::plannedAmount)
                .allSatisfy(a -> assertThat(a).isEqualByComparingTo("40000.00"));
    }

    @Test
    @DisplayName("месяц взноса = месяц цели, первый доход позже цели → взнос в день цели")
    void savings_targetMonthIncomeAfterTarget_contributionOnTargetDay() {
        List<EventSnapshot> out = SandboxLayout.layoutSavings("Рюкзак",
                new BigDecimal("8500.00"), LocalDate.of(2026, 8, 10), 1, AS_OF, INCOMES,
                SyntheticKind.SAVINGS_CONTRIBUTION);

        assertThat(out).extracting(EventSnapshot::date)
                .containsExactly(LocalDate.of(2026, 8, 10)); // 15.08 > 10.08 → в сам день цели
        assertThat(out.get(0).plannedAmount()).isEqualByComparingTo("8500.00");
    }

    @Test
    @DisplayName("копейки: последний взнос добирает остаток, сумма сходится точно")
    void savings_pennies_lastContributionAbsorbsRemainder() {
        List<EventSnapshot> out = SandboxLayout.layoutSavings("Тест",
                new BigDecimal("100.00"), LocalDate.of(2026, 10, 31), 3, AS_OF, INCOMES,
                SyntheticKind.SAVINGS_CONTRIBUTION);

        assertThat(out).extracting(EventSnapshot::plannedAmount)
                .containsExactly(new BigDecimal("33.33"), new BigDecimal("33.33"), new BigDecimal("33.34"));
        assertThat(out.stream().map(EventSnapshot::plannedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("синтетика проходит фильтры движка: PLAN/PLANNED/без факта/без wishlist")
    void synthetic_passesEngineFilters() {
        List<EventSnapshot> out = SandboxLayout.layoutSavings("Копилка",
                new BigDecimal("1000.00"), LocalDate.of(2026, 9, 30), 2, AS_OF, INCOMES,
                SyntheticKind.SAVINGS_CONTRIBUTION);

        assertThat(out).allSatisfy(e -> {
            assertThat(e.eventKind()).isEqualTo(EventKind.PLAN);
            assertThat(e.status()).isEqualTo(EventStatus.PLANNED);
            assertThat(e.type()).isEqualTo(EventType.EXPENSE);
            assertThat(e.factAmount()).isNull();
            assertThat(e.wishlistStatus()).isNull();
            assertThat(e.id()).isNull();
            assertThat(e.description()).isEqualTo("Копилка");
            assertThat(e.syntheticKind()).isEqualTo(SyntheticKind.SAVINGS_CONTRIBUTION);
        });
    }

    // ── layoutOneOff ────────────────────────────────────────────────────────

    @Test
    @DisplayName("разовая: один расход в дату, TRY_ON")
    void oneOff_singleExpenseAtDate() {
        List<EventSnapshot> out = SandboxLayout.layoutOneOff("Велокресло",
                new BigDecimal("2500.00"), LocalDate.of(2026, 8, 20));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).date()).isEqualTo(LocalDate.of(2026, 8, 20));
        assertThat(out.get(0).plannedAmount()).isEqualByComparingTo("2500.00");
        assertThat(out.get(0).syntheticKind()).isEqualTo(SyntheticKind.TRY_ON);
    }

    // ── кредит ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("monthlyPmt совпадает с формулой WishlistSimulationService.computeCreditDelta")
    void monthlyPmt_matchesExistingCreditFormula() {
        BigDecimal amount = new BigDecimal("1300000.00");
        BigDecimal rate = new BigDecimal("18.0");
        int term = 60;

        BigDecimal expected = WishlistSimulationService.computeCreditDelta(
                amount, LocalDate.of(2026, 9, 1), YearMonth.of(2026, 7), term + 2, rate, term)
                .monthlyPMT();
        assertThat(SandboxLayout.monthlyPmt(amount, rate, term)).isEqualByComparingTo(expected);
    }

    @Test
    @DisplayName("нулевая ставка: PMT = сумма/срок")
    void monthlyPmt_zeroRate() {
        assertThat(SandboxLayout.monthlyPmt(new BigDecimal("1200.00"), BigDecimal.ZERO, 12))
                .isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("кредит: PMT-серия с месяца после покупки, в день числа покупки")
    void credit_pmtSeriesFromMonthAfterPurchase() {
        List<EventSnapshot> out = SandboxLayout.layoutCredit("Машина",
                new BigDecimal("300000.00"), LocalDate.of(2026, 9, 30), new BigDecimal("12.0"), 3);

        BigDecimal pmt = SandboxLayout.monthlyPmt(new BigDecimal("300000.00"), new BigDecimal("12.0"), 3);
        assertThat(out).extracting(EventSnapshot::date).containsExactly(
                LocalDate.of(2026, 10, 30), LocalDate.of(2026, 11, 30), LocalDate.of(2026, 12, 30));
        assertThat(out).extracting(EventSnapshot::plannedAmount)
                .allSatisfy(a -> assertThat(a).isEqualByComparingTo(pmt));
        assertThat(out).allSatisfy(e ->
                assertThat(e.syntheticKind()).isEqualTo(SyntheticKind.TRY_ON));
    }

    @Test
    @DisplayName("кредит: кламп day-of-month по длине месяца (31 → 30.09, 31.10, 30.11)")
    void credit_dayOfMonthClamped() {
        List<EventSnapshot> out = SandboxLayout.layoutCredit("Машина",
                new BigDecimal("300000.00"), LocalDate.of(2026, 8, 31), new BigDecimal("12.0"), 3);

        assertThat(out).extracting(EventSnapshot::date).containsExactly(
                LocalDate.of(2026, 9, 30),
                LocalDate.of(2026, 10, 31),
                LocalDate.of(2026, 11, 30));
    }
}
