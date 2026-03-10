package ru.selfin.backend.model.enums;

/**
 * Область применения операции обновления или удаления для повторяющихся событий.
 * <ul>
 *   <li>{@code THIS} — только текущее событие</li>
 *   <li>{@code THIS_AND_FOLLOWING} — текущее и все последующие события в серии</li>
 * </ul>
 */
public enum EventScope {
    THIS,
    THIS_AND_FOLLOWING
}
