package ru.selfin.backend.dto.wishlist;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Один item на странице /wishlist (хотелка / копилка / кредит).
 * @param convertedTo {kind, id} артефакта или null
 */
public record WishlistItemDto(
        UUID id,
        String kind,                 // WISHLIST | SAVINGS | CREDIT
        String name,
        BigDecimal amount,
        LocalDate targetDate,
        String status,               // OPEN | FIXED | DISMISSED
        ConvertedToDto convertedTo,
        List<MonthDeltaDto> delta,
        // kind-specific (nullable when not applicable):
        UUID categoryId,             // WISHLIST
        BigDecimal monthlyContribution,  // SAVINGS
        BigDecimal rate,             // CREDIT
        Integer termMonths,          // CREDIT
        BigDecimal monthlyPMT        // CREDIT
) {
    /** Ссылка на сконвертированный артефакт. */
    public record ConvertedToDto(String kind, UUID id) {}  // kind: EVENT | FUND
}
