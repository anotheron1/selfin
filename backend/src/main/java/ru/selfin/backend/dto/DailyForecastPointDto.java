package ru.selfin.backend.dto;

import java.math.BigDecimal;

/**
 * One day's data point for the sparkline chart.
 * Both cumulativeFact and projectedTotal represent amounts in rubles.
 */
public record DailyForecastPointDto(
        int day,                      // day-of-month, 1-based
        BigDecimal cumulativeFact,    // running total of actual spending up to this day
        BigDecimal projectedTotal     // end-of-month forecast as computed on this day
) {}
