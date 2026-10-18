package ru.selfin.backend.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.selfin.backend.dto.ErrorResponse;
import ru.selfin.backend.service.ConsentRequiredException;
import ru.selfin.backend.service.OwnerContext;
import ru.selfin.backend.service.OwnerService;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Владелец запроса — в {@link OwnerContext} на время запроса (ANO-144): с ним пул пускает к строкам владельца.
 * Стоит после авторизации: вошедший уже известен. Без входа — открытые адреса — владельца нет, и база пуста.
 *
 * <p>Вошедший, которого сервер ещё не завёл и без согласия не заведёт (ANO-196), получает 403 с кодом
 * {@code CONSENT_REQUIRED}. Без владельца проходят только согласие и коммит сборки — им строки не нужны.
 */
final class OwnerFilter extends OncePerRequestFilter {

    private static final RequestMatcher WITHOUT_OWNER = new OrRequestMatcher(
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/consent"),
            PathPatternRequestMatcher.withDefaults().matcher("/api/v1/version"));

    private final OwnerService owners;
    private final ObjectMapper json;

    OwnerFilter(OwnerService owners, ObjectMapper json) {
        this.owners = owners;
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            chain.doFilter(request, response);
            return;
        }
        UUID owner;
        try {
            owner = owners.current();
        } catch (ConsentRequiredException e) {
            if (WITHOUT_OWNER.matches(request)) {
                chain.doFilter(request, response);
            } else {
                response.setStatus(HttpStatus.FORBIDDEN.value());
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                json.writeValue(response.getOutputStream(), ErrorResponse.of(HttpStatus.FORBIDDEN.value(),
                        e.getMessage(), List.of(ConsentRequiredException.CODE)));
            }
            return;
        }
        UUID previous = OwnerContext.get();
        OwnerContext.set(owner);
        try {
            chain.doFilter(request, response);
        } finally {
            OwnerContext.set(previous);
        }
    }
}
