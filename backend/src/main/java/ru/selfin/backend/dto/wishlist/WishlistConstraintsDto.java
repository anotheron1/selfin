package ru.selfin.backend.dto.wishlist;

import java.math.BigDecimal;

public record WishlistConstraintsDto(
        BigDecimal monthlyExpensesAvg,
        BigDecimal monthlyIncomeAvg,
        BigDecimal currentCapital,
        BigDecimal maxWishlistAmount,
        BigDecimal maxCreditAmount
) {}
