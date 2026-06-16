package ru.selfin.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.selfin.backend.dto.strategy.StrategyTimelineDto;
import ru.selfin.backend.dto.wishlist.TimelineSnapshot;

/**
 * Координатор стратегической шкалы. Базовый timeline собирает {@link BaselineTimelineBuilder};
 * поверх него накладываются delta зафиксированных (FIXED) хотелок (см. Task 2.6).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class StrategyTimelineService {

    private final BaselineTimelineBuilder baselineBuilder;

    public StrategyTimelineDto getTimeline(int horizonMonths, boolean withBreakdown) {
        TimelineSnapshot snap = baselineBuilder.build(horizonMonths, withBreakdown);
        return new StrategyTimelineDto(
                snap.firstMonth(),
                snap.currentMonth(),
                snap.horizonEnd(),
                snap.predictionWindowMonths(),
                snap.fanEnabled(),
                snap.points()
        );
    }
}
