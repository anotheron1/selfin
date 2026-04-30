package ru.selfin.backend.model.enums;

/**
 * Scope edit/delete операций над recurring-событием.
 *
 * <ul>
 *   <li>{@code THIS} — только этот экземпляр (правило не трогаем)</li>
 *   <li>{@code FOLLOWING} — этот экземпляр и все будущие PLAN'ы</li>
 *   <li>{@code ALL} — всё правило целиком</li>
 * </ul>
 *
 * <p>Применимо только к recurring-событиям (см. инвариант I6).
 */
public enum ScopeEnum {
    THIS,
    FOLLOWING,
    ALL
}
