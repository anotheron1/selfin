package ru.selfin.backend.dto.wishlist;

import java.time.LocalDate;

/**
 * Запрос на конверсию wishlist-item'а в реальный артефакт.
 *
 * @param sourceKind             WISHLIST | SAVINGS | CREDIT — что конвертируем
 * @param target                 PLAN_EVENT | FUND | FUND_WITH_CREDIT — во что
 * @param createRecurringPayments для FUND_WITH_CREDIT — создать ли recurring PMT-правило
 * @param fundTargetDate         для target=FUND: дата цели создаваемой копилки; null = дата
 *                               источника. Фиксация растянутой примерки (ANO-16 §8) передаёт
 *                               последний день месяца последнего взноса — тогда резервирование
 *                               §6 воспроизводит ровно те взносы, что юзер видел на графике
 */
public record ConvertWishlistRequestDto(
        String sourceKind,
        String target,
        Boolean createRecurringPayments,
        LocalDate fundTargetDate
) {
    /** Старая сигнатура (без переопределения даты цели). */
    public ConvertWishlistRequestDto(String sourceKind, String target, Boolean createRecurringPayments) {
        this(sourceKind, target, createRecurringPayments, null);
    }
}
