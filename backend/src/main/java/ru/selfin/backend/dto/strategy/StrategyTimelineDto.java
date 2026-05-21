package ru.selfin.backend.dto.strategy;

import java.time.YearMonth;
import java.util.List;

/**
 * Корневой DTO ответа {@code GET /api/v1/strategy/timeline}.
 * См. spec, раздел 2.
 *
 * @param firstActivityMonth     минимум из первого FACT-события, чекпоинта, capital_revaluation
 * @param currentMonth           маркер «сегодня»
 * @param horizonEnd             конец оси = currentMonth + horizonMonths
 * @param predictionWindowMonths сколько месяцев истории использовано в прогнозе
 * @param fanEnabled             false если меньше 3 категорий имеют ≥3 мес истории
 * @param points                 точки по месяцам, отсортированы по yearMonth возрастающе
 */
public record StrategyTimelineDto(
        YearMonth firstActivityMonth,
        YearMonth currentMonth,
        YearMonth horizonEnd,
        int predictionWindowMonths,
        boolean fanEnabled,
        List<StrategyTimelinePointDto> points
) {}
