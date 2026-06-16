package ru.selfin.backend.dto.wishlist;

import ru.selfin.backend.dto.strategy.StrategyTimelineDto;

import java.util.List;

/**
 * Полный ответ GET /api/v1/wishlist/simulation: baseline timeline (без хотелок) +
 * список items с их delta-векторами + пороги + ограничения для слайдеров.
 */
public record WishlistSimulationDto(
        StrategyTimelineDto baseline,
        List<WishlistItemDto> items,
        WishlistThresholdsDto thresholds,
        WishlistConstraintsDto constraints
) {}
