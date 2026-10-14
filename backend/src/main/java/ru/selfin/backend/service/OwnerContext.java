package ru.selfin.backend.service;

import java.util.UUID;

/**
 * Владелец, от имени которого поток ходит в базу (ANO-144): пул ставит его на каждое выданное соединение
 * в {@code selfin.user_id}, и политики RLS пропускают только его строки. Нет владельца — нет строк.
 *
 * <p>У запроса его ставит фильтр после авторизации и снимает в конце запроса.
 *
 * <p>Спека: docs/superpowers/specs/2026-10-14-row-level-security-design.md
 */
public final class OwnerContext {

    private static final ThreadLocal<UUID> OWNER = new ThreadLocal<>();

    private OwnerContext() {
    }

    public static UUID get() {
        return OWNER.get();
    }

    /** {@code null} — без владельца. */
    public static void set(UUID owner) {
        if (owner == null) {
            OWNER.remove();
        } else {
            OWNER.set(owner);
        }
    }
}
