package ru.selfin.backend.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Forecast result for one expense category.
 */
public record CategoryForecastDto(
        String categoryName,
        BigDecimal currentFact,       // fact spent so far this month
        BigDecimal plannedLimit,      // sum of all PLAN events this month
        BigDecimal projectionAmount,  // сколько уйдёт за месяц = max(медиана, факт + непогашенные планы)
        /**
         * ANO-80: вклад категории в кармашек = {@code max(0, медиана − факт − непогашенные планы)}.
         *
         * <p>Лежит в ответе, а не пересчитывается потребителями. Раньше «даёт ли категория
         * вклад» выводилось из {@code plannedLimit == 0}, и после ANO-80 это стало враньём:
         * категория с планом теперь отдаёт разницу между нормой и планом. Величина, которую
         * каждый потребитель выводит сам, рано или поздно выводится по-разному.
         */
        BigDecimal beyondPlan,
        List<DailyForecastPointDto> history  // one point per day from day 1 to today
) {}
