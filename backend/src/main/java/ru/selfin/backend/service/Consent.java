package ru.selfin.backend.service;

import java.io.Serializable;
import java.time.Clock;
import java.time.Instant;

/**
 * Согласие на обработку персональных данных (ANO-196): какую версию текста человек принял и когда поставил галочку.
 * До входа оно лежит в сеансе ({@link #SESSION_KEY}), при заведении человека — в {@code users}.
 *
 * <p>Спека: docs/superpowers/specs/2026-10-18-consent-design.md
 *
 * @param version версия текста согласия, которую показал экран
 * @param at      когда человек поставил галочку
 */
public record Consent(String version, Instant at) implements Serializable {

    /**
     * Версия текста согласия — дата редакции. Та же — в {@code frontend/src/lib/consent.ts} и
     * {@code tools/keycloak-token.mjs}; сверяет сторож {@code consent.test.ts}.
     */
    public static final String CURRENT = "2026-10-18";

    /** Отметка галочки в сеансе — до входа в Keycloak. */
    public static final String SESSION_KEY = "selfin.consent";

    /** Согласие с нынешним текстом — сейчас по часам приложения (ANO-39). */
    public static Consent current(Clock clock) {
        return new Consent(CURRENT, Instant.now(clock));
    }
}
