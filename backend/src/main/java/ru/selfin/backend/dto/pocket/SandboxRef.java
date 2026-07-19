package ru.selfin.backend.dto.pocket;

import java.util.UUID;

/**
 * Ссылка на элемент примерки/резервирования: событие-хотелка или фонд-копилка
 * (спека sandbox §4, §9). Ключ операционального «сидит в baseline».
 */
public record SandboxRef(RefType type, UUID id) {

    public enum RefType { EVENT, FUND }

    public static SandboxRef event(UUID id) {
        return new SandboxRef(RefType.EVENT, id);
    }

    public static SandboxRef fund(UUID id) {
        return new SandboxRef(RefType.FUND, id);
    }
}
