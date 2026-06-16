package ru.selfin.backend.dto.wishlist;

import java.math.BigDecimal;

/**
 * @param capitalThresholdRub null = критерий капитала выключен
 * @param cashBufferMonths    буфер счёта в месяцах расходов (дефолт 1.0)
 */
public record WishlistThresholdsDto(
        BigDecimal capitalThresholdRub,
        BigDecimal cashBufferMonths
) {}
