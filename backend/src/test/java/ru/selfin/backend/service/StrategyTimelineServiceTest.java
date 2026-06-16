package ru.selfin.backend.service;

import org.junit.jupiter.api.Test;
import ru.selfin.backend.dto.strategy.StrategyTimelineDto;
import ru.selfin.backend.dto.strategy.StrategyTimelinePointDto;
import ru.selfin.backend.dto.wishlist.TimelineSnapshot;

import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the thin {@link StrategyTimelineService} coordinator.
 * The heavy timeline-building logic is tested in {@link BaselineTimelineBuilderTest}.
 */
class StrategyTimelineServiceTest {

    private final BaselineTimelineBuilder baselineBuilder = mock(BaselineTimelineBuilder.class);
    private final StrategyTimelineService service = new StrategyTimelineService(baselineBuilder);

    @Test
    void getTimeline_delegates_to_baselineBuilder_and_maps_fields() {
        YearMonth first = YearMonth.of(2026, 1);
        YearMonth current = YearMonth.of(2026, 6);
        YearMonth horizon = YearMonth.of(2026, 9);
        List<StrategyTimelinePointDto> points = List.of();

        TimelineSnapshot snap = new TimelineSnapshot(first, current, horizon, 6, false, points);
        when(baselineBuilder.build(3, true)).thenReturn(snap);

        StrategyTimelineDto dto = service.getTimeline(3, true);

        assertThat(dto.firstActivityMonth()).isEqualTo(first);
        assertThat(dto.currentMonth()).isEqualTo(current);
        assertThat(dto.horizonEnd()).isEqualTo(horizon);
        assertThat(dto.predictionWindowMonths()).isEqualTo(6);
        assertThat(dto.fanEnabled()).isFalse();
        assertThat(dto.points()).isEmpty();
    }
}
