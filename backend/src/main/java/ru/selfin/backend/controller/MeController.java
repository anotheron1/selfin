package ru.selfin.backend.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.selfin.backend.service.OwnerContext;
import ru.selfin.backend.service.VisitService;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * Чей это запрос (ANO-195): id владельца из {@code users}. До ограничителя (шаг 5 плана спринта 5)
 * по экрану не отличить, связан ли вход с синтетическим владельцем, — отличает этот ответ. С ANO-144 — тот
 * владелец, с которым запрос ходит в базу: его ставит {@code OwnerFilter}.
 *
 * <p>Имя — для строки «Вы вошли как» в «Настройках» (ANO-152): {@code name} из ID token сеанса или из токена
 * Bearer — Telegram присылает «Имя Фамилия», — иначе ник ({@code preferred_username}).
 *
 * <p>{@code lastVisit} — день прошлого визита, до сегодняшнего (ANO-102): по нему «Свободно» замечает перерыв.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class MeController {

    private final VisitService visits;

    record Me(UUID id, String name, LocalDate lastVisit) {
    }

    @GetMapping("/me")
    public Me me(Authentication authentication) {
        Map<String, Object> claims = switch (authentication.getPrincipal()) {
            case OidcUser user -> user.getClaims();
            case Jwt jwt -> jwt.getClaims();
            case null, default -> Map.of();
        };
        Object name = claims.getOrDefault("name", claims.get("preferred_username"));
        return new Me(OwnerContext.get(), name == null ? null : name.toString(), visits.lastVisit());
    }
}
