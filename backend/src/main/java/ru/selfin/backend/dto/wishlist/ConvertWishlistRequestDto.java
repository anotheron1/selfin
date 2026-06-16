package ru.selfin.backend.dto.wishlist;

/**
 * Запрос на конверсию wishlist-item'а в реальный артефакт.
 *
 * @param sourceKind             WISHLIST | SAVINGS | CREDIT — что конвертируем
 * @param target                 PLAN_EVENT | FUND | FUND_WITH_CREDIT — во что
 * @param createRecurringPayments для FUND_WITH_CREDIT — создать ли recurring PMT-правило
 */
public record ConvertWishlistRequestDto(
        String sourceKind,
        String target,
        Boolean createRecurringPayments
) {}
