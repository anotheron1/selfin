package ru.selfin.backend.model.enums;

/**
 * Статус item'а в модуле планирования /wishlist.
 *
 * <ul>
 *   <li>{@code OPEN} — на обсуждении; влияет на симуляцию /wishlist, не влияет на /strategy и /budget.</li>
 *   <li>{@code FIXED} — решение принято; влияет на /strategy и /capital (через delta); может иметь конверсию.</li>
 *   <li>{@code DISMISSED} — отклонён; виден в свёрнутой секции, не влияет ни на что.</li>
 * </ul>
 */
public enum WishlistStatus {
    OPEN,
    FIXED,
    DISMISSED
}
