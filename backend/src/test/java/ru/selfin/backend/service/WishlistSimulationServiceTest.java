package ru.selfin.backend.service;

import org.junit.jupiter.api.Test;
import ru.selfin.backend.dto.wishlist.MonthDeltaDto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WishlistSimulationServiceTest {

    // Pure-math helper under test is static (no Spring context needed).
    @Test
    void computeDelta_wishlist_singleMonthExpense() {
        YearMonth current = YearMonth.now();
        LocalDate target = current.plusMonths(15).atDay(10);

        List<MonthDeltaDto> delta = WishlistSimulationService.computeWishlistDelta(
                new BigDecimal("150000"), target, current, 36);

        assertThat(delta).hasSize(1);
        MonthDeltaDto d = delta.get(0);
        assertThat(d.monthIndex()).isEqualTo(14);   // current+1 == index 0, so current+15 == index 14
        assertThat(d.accountDelta()).isEqualByComparingTo("-150000");
        assertThat(d.capitalDelta()).isEqualByComparingTo("-150000");
    }

    @Test
    void computeDelta_wishlist_pastDate_empty() {
        YearMonth current = YearMonth.now();
        LocalDate past = current.minusMonths(2).atDay(10);

        List<MonthDeltaDto> delta = WishlistSimulationService.computeWishlistDelta(
                new BigDecimal("150000"), past, current, 36);

        assertThat(delta).isEmpty();
    }

    @Test
    void computeDelta_wishlist_beyondHorizon_empty() {
        YearMonth current = YearMonth.now();
        LocalDate far = current.plusMonths(50).atDay(10);

        List<MonthDeltaDto> delta = WishlistSimulationService.computeWishlistDelta(
                new BigDecimal("150000"), far, current, 36);

        assertThat(delta).isEmpty();
    }

    @Test
    void computeDelta_savings_monthlyContributionsThenPurchase() {
        YearMonth current = YearMonth.now();
        LocalDate target = current.plusMonths(12).atDay(1);   // purchaseIdx = 11
        BigDecimal amount = new BigDecimal("200000");

        var result = WishlistSimulationService.computeSavingsDelta(amount, target, current, 36);

        // Model: contribute monthly across ALL months 0..purchaseIdx (inclusive) = purchaseIdx+1 = 12 months.
        // monthly = amount / (purchaseIdx + 1) = 200000 / 12 = 16666.67.
        // account drops by monthly EVERY month 0..11 (total = amount); capital flat until purchase.
        // At purchase month (index 11): that month's contribution PLUS the consumption.
        // account axis and capital axis are independent (account=checking, capital=net worth);
        // the fund pocket is the intermediary and shows only in tooltips.
        assertThat(result.monthlyContribution()).isEqualByComparingTo("16666.67");

        // contribution months 0..10: account -= monthly, fund += monthly, capital = 0
        assertThat(result.delta().get(0).monthIndex()).isEqualTo(0);
        assertThat(result.delta().get(0).accountDelta()).isEqualByComparingTo("-16666.67");
        assertThat(result.delta().get(0).fundDelta()).isEqualByComparingTo("16666.67");
        assertThat(result.delta().get(0).capitalDelta()).isEqualByComparingTo("0");

        // purchase month (index 11): account -= monthly (final contribution), capital -= amount (consumption)
        MonthDeltaDto last = result.delta().get(result.delta().size() - 1);
        assertThat(last.monthIndex()).isEqualTo(11);
        assertThat(last.accountDelta()).isEqualByComparingTo("-16666.67");
        assertThat(last.capitalDelta()).isEqualByComparingTo("-200000");

        // total account drop across all 12 entries == amount (12 × 16666.67 ≈ 200000)
        BigDecimal totalAccount = result.delta().stream()
                .map(MonthDeltaDto::accountDelta).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(totalAccount).isEqualByComparingTo("-200000.04");  // rounding: 12 × -16666.67
    }

    @Test
    void computeDelta_savings_nextMonthTarget_degeneratesToLumpSum() {
        YearMonth current = YearMonth.now();
        LocalDate target = current.plusMonths(1).atDay(1);   // purchaseIdx = 0
        var result = WishlistSimulationService.computeSavingsDelta(
                new BigDecimal("200000"), target, current, 36);
        // No time to save: single entry at index 0, account -amount, capital -amount.
        assertThat(result.delta()).hasSize(1);
        assertThat(result.delta().get(0).monthIndex()).isEqualTo(0);
        assertThat(result.delta().get(0).accountDelta()).isEqualByComparingTo("-200000");
        assertThat(result.delta().get(0).capitalDelta()).isEqualByComparingTo("-200000");
    }

    @Test
    void computeDelta_savings_beyondHorizon_empty() {
        YearMonth current = YearMonth.now();
        LocalDate far = current.plusMonths(50).atDay(1);
        var result = WishlistSimulationService.computeSavingsDelta(
                new BigDecimal("200000"), far, current, 36);
        assertThat(result.delta()).isEmpty();
    }

    @Test
    void computeDelta_credit_lumpSumThenPMT() {
        YearMonth current = YearMonth.now();
        LocalDate target = current.plusMonths(3).atDay(1);  // purchase at index 2
        var result = WishlistSimulationService.computeCreditDelta(
                new BigDecimal("2000000"), target, current, 36,
                new BigDecimal("16.5"), 60);

        assertThat(result.monthlyPMT()).isGreaterThan(new BigDecimal("48000"))
                .isLessThan(new BigDecimal("50000"));
        // purchase month (index 2): account +amount (loan disbursed), liability +amount, capital unchanged
        MonthDeltaDto purchase = result.delta().get(0);
        assertThat(purchase.monthIndex()).isEqualTo(2);
        assertThat(purchase.accountDelta()).isEqualByComparingTo("2000000");
        assertThat(purchase.liabilityDelta()).isEqualByComparingTo("2000000");
        assertThat(purchase.capitalDelta()).isEqualByComparingTo("0");
        // first PMT month (index 3): account -PMT, liability -principalPart, capital +principalPart
        MonthDeltaDto firstPmt = result.delta().get(1);
        assertThat(firstPmt.monthIndex()).isEqualTo(3);
        assertThat(firstPmt.accountDelta()).isLessThan(BigDecimal.ZERO);
        assertThat(firstPmt.liabilityDelta()).isLessThan(BigDecimal.ZERO);
        assertThat(firstPmt.capitalDelta()).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void computeDelta_credit_clipsToHorizon() {
        YearMonth current = YearMonth.now();
        LocalDate target = current.plusMonths(1).atDay(1);  // purchase index 0
        var result = WishlistSimulationService.computeCreditDelta(
                new BigDecimal("2000000"), target, current, 36,
                new BigDecimal("16.5"), 120);   // 10y term > 36mo horizon

        // No delta entry exceeds monthIndex 35
        assertThat(result.delta()).allSatisfy(d -> assertThat(d.monthIndex()).isLessThan(36));
    }
}
