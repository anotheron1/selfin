package ru.selfin.backend.dto.wishlist;

import java.util.UUID;

/**
 * Результат конверсии: новый статус источника + ссылка на созданный артефакт.
 *
 * @param wishlistItemId  id исходного item'а
 * @param newStatus       новый статус источника (всегда FIXED)
 * @param convertedTo     {kind, id} созданного артефакта (EVENT|FUND)
 * @param artifactKind    PLAN_EVENT | FUND | FUND_WITH_CREDIT
 * @param recurringRuleId id созданного recurring-правила (только FUND_WITH_CREDIT + createRecurringPayments), иначе null
 */
public record ConvertWishlistResponseDto(
        UUID wishlistItemId,
        String newStatus,
        WishlistItemDto.ConvertedToDto convertedTo,
        String artifactKind,
        UUID recurringRuleId
) {}
