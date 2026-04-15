package ru.selfin.backend.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Full monthly forecast: per-category results plus the delta relevant for pocket balance.
 *
 * <p>netPredictionDelta is the sum of extrapolated future spending for forecast_enabled
 * categories that have NO plan events (linear-only categories).
 * For plan-based categories, delta = 0 because кармашек already accounts for them
 * via pocketBalance (which tracks executed facts) + remaining plan events.</p>
 */
public record MonthlyForecastDto(
        List<CategoryForecastDto> categories,
        BigDecimal netPredictionDelta
) {}
