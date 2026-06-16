package ru.selfin.backend.dto.wishlist;

import ru.selfin.backend.dto.strategy.StrategyTimelinePointDto;

import java.time.YearMonth;
import java.util.List;

/**
 * Внутренний результат {@link ru.selfin.backend.service.BaselineTimelineBuilder}:
 * полный timeline БЕЗ влияния хотелок (past + current + future, обогащённый капиталом).
 *
 * <p>Используется и {@code WishlistSimulationService} (как baseline для симуляции),
 * и {@code StrategyTimelineService} (как основа, поверх которой накладываются FIXED-items).
 *
 * @param firstMonth     первый месяц активности
 * @param currentMonth   текущий месяц
 * @param horizonEnd     последний месяц горизонта
 * @param predictionWindowMonths окно прогноза (мес)
 * @param fanEnabled     включён ли веер неопределённости
 * @param points         все точки (past + current + future), обогащённые капиталом
 */
public record TimelineSnapshot(
        YearMonth firstMonth,
        YearMonth currentMonth,
        YearMonth horizonEnd,
        int predictionWindowMonths,
        boolean fanEnabled,
        List<StrategyTimelinePointDto> points
) {}
