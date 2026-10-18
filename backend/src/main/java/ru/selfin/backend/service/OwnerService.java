package ru.selfin.backend.service;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.UUID;

/**
 * Владелец запроса (ANO-195): {@code sub} из токена Keycloak → строка {@code users}.
 *
 * <p>Незнакомый {@code sub} — новая строка со стартовым набором ({@link Registration}, ANO-151), но только с согласием
 * на обработку персональных данных (ANO-196): без него — {@link ConsentRequiredException}. Исключение —
 * {@code selfin.owner-sub}: этот {@code sub} получает синтетического владельца, хозяина данных, заведённых до входа
 * (V31), — если тот ещё ни с кем не связан; согласия ему не нужно — он оператор. Связь — один раз: смена настройки
 * данные не переносит. Набора синтетический не получает — данные у него свои.
 *
 * <p>Спеки: docs/superpowers/specs/2026-10-13-server-knows-owner-design.md,
 * docs/superpowers/specs/2026-10-15-starter-set-design.md, docs/superpowers/specs/2026-10-18-consent-design.md
 */
@Service
public class OwnerService {

    /** Синтетический владелец из V31 — хозяин всех данных, заведённых до входа. */
    public static final UUID SYNTHETIC_OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final JdbcTemplate jdbc;
    private final Registration registration;
    private final EntityManagerFactory sessions;
    private final String ownerSub;

    public OwnerService(JdbcTemplate jdbc, Registration registration, EntityManagerFactory sessions,
                        @Value("${selfin.owner-sub:}") String ownerSub) {
        this.jdbc = jdbc;
        this.registration = registration;
        this.sessions = sessions;
        this.ownerSub = ownerSub;
    }

    /** Владелец текущего запроса. Имя входа — {@code sub}: и у сеанса браузера, и у Bearer. */
    public UUID current() {
        return resolve(SecurityContextHolder.getContext().getAuthentication().getName());
    }

    /** Знакомый человек или владелец; незнакомого без согласия не заводит — {@link ConsentRequiredException}. */
    public UUID resolve(String sub) {
        return resolve(sub, null);
    }

    /**
     * Как {@link #resolve(String)}, а незнакомого с согласием заводит: версия текста и время галочки ложатся в
     * {@code users}.
     */
    public UUID resolve(String sub, Consent consent) {
        List<UUID> known = jdbc.queryForList("SELECT id FROM users WHERE external_id = ?", UUID.class, sub);
        if (!known.isEmpty()) {
            return known.getFirst();
        }
        if (sub.equals(ownerSub) && jdbc.update(
                "UPDATE users SET external_id = ? WHERE id = ? AND external_id IS NULL", sub, SYNTHETIC_OWNER) == 1) {
            return SYNTHETIC_OWNER;
        }
        if (consent == null) {
            throw new ConsentRequiredException();
        }
        // Новый человек — владелец потока ещё до транзакции регистрации: её соединение и сессия — уже его.
        UUID id = UUID.randomUUID();
        UUID previous = OwnerContext.get();
        OwnerContext.set(id);
        // Сессия — своя: арендатор фиксируется при открытии сессии, а из контроллера согласия (ANO-196) сессия
        // open-in-view уже открыта без владельца. Отвязанная, она не достанется транзакции регистрации — иначе строки
        // нового человека легли бы на «никого» и упёрлись в RLS. Из фильтра и обработчика входа её ещё нет.
        Object openInView = TransactionSynchronizationManager.unbindResourceIfPossible(sessions);
        try {
            if (registration.register(id, sub, consent)) {
                return id;
            }
        } finally {
            OwnerContext.set(previous);
            if (openInView != null) TransactionSynchronizationManager.bindResource(sessions, openInView);
        }
        // Два первых запроса человека разом: строку с набором завёл один, второй дождался его и прочтёт её.
        return jdbc.queryForObject("SELECT id FROM users WHERE external_id = ?", UUID.class, sub);
    }
}
