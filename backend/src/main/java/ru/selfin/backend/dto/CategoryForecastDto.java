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
        BigDecimal projectionAmount,  // end-of-month projection (hybrid B or linear A)
        List<DailyForecastPointDto> history  // one point per day from day 1 to today
) {}
