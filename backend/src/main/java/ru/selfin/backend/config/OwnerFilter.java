package ru.selfin.backend.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.selfin.backend.service.OwnerContext;
import ru.selfin.backend.service.OwnerService;

import java.io.IOException;
import java.util.UUID;

/**
 * Владелец запроса — в {@link OwnerContext} на время запроса (ANO-144): с ним пул пускает к строкам владельца.
 * Стоит после авторизации: вошедший уже известен. Без входа — открытые адреса — владельца нет, и база пуста.
 */
final class OwnerFilter extends OncePerRequestFilter {

    private final OwnerService owners;

    OwnerFilter(OwnerService owners) {
        this.owners = owners;
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
        UUID previous = OwnerContext.get();
        OwnerContext.set(owners.current());
        try {
            chain.doFilter(request, response);
        } finally {
            OwnerContext.set(previous);
        }
    }
}
