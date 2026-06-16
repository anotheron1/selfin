package ru.selfin.backend.service;

import org.junit.jupiter.api.Test;
import ru.selfin.backend.dto.strategy.StrategyTimelineDto;
import ru.selfin.backend.dto.strategy.StrategyTimelinePointDto;
import ru.selfin.backend.dto.wishlist.MonthDeltaDto;
import ru.selfin.backend.dto.wishlist.TimelineSnapshot;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the thin {@link StrategyTimelineService} coordinator.
 * The heavy timeline-building logic is tested in {@link BaselineTimelineBuilderTest}.
 */
class StrategyTimelineServiceTest {

    private final BaselineTimelineBuilder baselineBuilder = mock(BaselineTimelineBuilder.class);
    private final WishlistSimulationService wishlistService = mock(WishlistSimulationService.class);
    private final StrategyTimelineService service = new StrategyTimelineService(baselineBuilder, wishlistService);

    @Test
    void getTimeline_delegates_to_baselineBuilder_and_maps_fields() {
        YearMonth first = YearMonth.of(2026, 1);
        YearMonth current = YearMonth.of(2026, 6);
        YearMonth horizon = YearMonth.of(2026, 9);
        List<StrategyTimelinePointDto> points = List.of();

        TimelineSnapshot snap = new TimelineSnapshot(first, current, horizon, 6, false, points);
        when(baselineBuilder.build(3, true)).thenReturn(snap);
        // no FIXED deltas
        when(wishlistService.computeDeltaForFixedItems(any(), anyInt())).thenReturn(List.of());

        StrategyTimelineDto dto = service.getTimeline(3, true);

        assertThat(dto.firstActivityMonth()).isEqualTo(first);
        assertThat(dto.currentMonth()).isEqualTo(current);
        assertThat(dto.horizonEnd()).isEqualTo(horizon);
        assertThat(dto.predictionWindowMonths()).isEqualTo(6);
        assertThat(dto.fanEnabled()).isFalse();
        assertThat(dto.points()).isEmpty();
    }

    @Test
    void getTimeline_fixedItem_shiftsBalance_onFuturePoints() {
        YearMonth current = YearMonth.now();
        YearMonth first = current.minusMonths(1);
        YearMonth horizonEnd = current.plusMonths(3);

        // A future point at current+2 (k=2, monthIndex=1): balance=50000
        StrategyTimelinePointDto futurePoint = new StrategyTimelinePointDto(
                current.plusMonths(2), ru.selfin.backend.dto.strategy.StrategyPointPhase.FUTURE,
                new BigDecimal("50000"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("50000"), new BigDecimal("50000"), new BigDecimal("50000"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null);
        // A past point at first: not shifted
        StrategyTimelinePointDto pastPoint = new StrategyTimelinePointDto(
                first, ru.selfin.backend.dto.strategy.StrategyPointPhase.PAST,
                new BigDecimal("30000"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                null, null, null,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null);

        List<StrategyTimelinePointDto> points = List.of(pastPoint, futurePoint);
        TimelineSnapshot snap = new TimelineSnapshot(first, current, horizonEnd, 6, false, points);
        when(baselineBuilder.build(3, true)).thenReturn(snap);

        // FIXED item outflow of 20000 at monthIndex=1 (current+2)
        when(wishlistService.computeDeltaForFixedItems(eq(current), eq(3)))
                .thenReturn(List.of(new MonthDeltaDto(1, new BigDecimal("-20000"), new BigDecimal("-20000"), null, null)));

        StrategyTimelineDto dto = service.getTimeline(3, true);

        // Past point untouched
        assertThat(dto.points().get(0).balance()).isEqualByComparingTo("30000");
        // Future point shifted by -20000 (running sum after monthIndex=1 applied)
        assertThat(dto.points().get(1).balance()).isEqualByComparingTo("30000"); // 50000 + (-20000)
        assertThat(dto.points().get(1).balanceConfirmed()).isEqualByComparingTo("30000");
    }
}
