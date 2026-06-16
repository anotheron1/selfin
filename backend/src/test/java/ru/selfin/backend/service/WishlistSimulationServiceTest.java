package ru.selfin.backend.service;

import org.junit.jupiter.api.Test;
import ru.selfin.backend.dto.wishlist.MonthDeltaDto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
}
