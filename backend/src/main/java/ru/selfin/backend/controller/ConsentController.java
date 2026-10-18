package ru.selfin.backend.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ru.selfin.backend.service.Consent;
import ru.selfin.backend.service.OwnerService;

import java.time.Clock;

/**
 * Галочка согласия на обработку персональных данных (ANO-196).
 *
 * <ul>
 *   <li>до входа — отметка в сеансе: с ней наш адрес входа пускает в Keycloak, и после входа человек заводится с этим
 *       согласием;</li>
 *   <li>вошедший из браузера, которого ещё нет, заводится сразу — и тоже с отметкой, чтобы переход в Keycloak после
 *       галочки прошёл;</li>
 *   <li>скрипт по Bearer заводит своего человека — сеанса у него нет.</li>
 * </ul>
 *
 * Версия не та, что на сервере, — 409: экран устарел, и согласие на другой текст не записывается.
 *
 * <p>Спека: docs/superpowers/specs/2026-10-18-consent-design.md
 */
@RestController
@RequestMapping("/api/v1/consent")
@RequiredArgsConstructor
public class ConsentController {

    record ConsentRequest(String version) {
    }

    private final OwnerService owners;
    private final Clock clock;

    @PostMapping
    public ResponseEntity<Void> accept(@RequestBody ConsentRequest body, Authentication authentication,
                                       HttpServletRequest request) {
        if (!Consent.CURRENT.equals(body.version())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Текст согласия обновился — обновите страницу");
        }
        Consent consent = Consent.current(clock);
        if (!(authentication instanceof JwtAuthenticationToken)) {
            request.getSession().setAttribute(Consent.SESSION_KEY, consent);
        }
        // Без входа Authentication здесь нет: аргумент берётся из getUserPrincipal, а у анонима его нет.
        if (authentication != null) {
            owners.resolve(authentication.getName(), consent);
        }
        return ResponseEntity.noContent().build();
    }
}
