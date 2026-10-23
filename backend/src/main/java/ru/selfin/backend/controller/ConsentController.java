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
 *   <li>браузер — только отметка в сеансе, вошёл он или нет: с ней наш адрес входа пускает в Keycloak, а заводит
 *       человека обработчик входа — у свежего входа. Заводить здесь нельзя (ревью Codex на #173): сеанс удалённого
 *       по просьбе ещё несёт его старый {@code sub}, и галочка завела бы старую строку до Keycloak;</li>
 *   <li>скрипт по Bearer заводит своего человека сразу — сеанса и входа у него нет.</li>
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

    @WritesData(false)
    @PostMapping
    public ResponseEntity<Void> accept(@RequestBody ConsentRequest body, Authentication authentication,
                                       HttpServletRequest request) {
        if (!Consent.CURRENT.equals(body.version())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Текст согласия обновился — обновите страницу");
        }
        Consent consent = Consent.current(clock);
        if (authentication instanceof JwtAuthenticationToken) {
            owners.resolve(authentication.getName(), consent);
        } else {
            request.getSession().setAttribute(Consent.SESSION_KEY, consent);
        }
        return ResponseEntity.noContent().build();
    }
}
