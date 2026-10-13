package ru.selfin.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestHeaderRequestMatcher;
import ru.selfin.backend.service.OwnerService;

/**
 * Вход (ANO-195), вариант Б: сервер держит токены, у браузера — только cookie сеанса.
 *
 * <p>Две цепочки:
 * <ul>
 *   <li><b>скрипты и CI</b> — запрос с заголовком {@code Authorization}: Bearer, без сеанса и CSRF.
 *       Токен проверяет Boot по {@code spring.security.oauth2.resourceserver.*} — подпись, срок,
 *       издатель и аудиторию selfin: токен консоли Keycloak ({@code aud = account}) не пройдёт;</li>
 *   <li><b>браузер</b> — вход через наш сервер ({@code oauth2Login}), сеанс и CSRF-токен в cookie
 *       {@code XSRF-TOKEN}, которую фронт возвращает заголовком {@code X-XSRF-TOKEN}.</li>
 * </ul>
 *
 * <p>Спека: docs/superpowers/specs/2026-10-13-server-knows-owner-design.md
 */
@Configuration
public class SecurityConfig {

    @Bean
    @Order(1)
    SecurityFilterChain scripts(HttpSecurity http, OwnerService owners) throws Exception {
        return http
                .securityMatcher(new RequestHeaderRequestMatcher(HttpHeaders.AUTHORIZATION))
                .authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
                .oauth2ResourceServer(server -> server.jwt(Customizer.withDefaults()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Подделывать нечего: у скриптов нет cookie, а токен в заголовок чужая страница не положит.
                .csrf(AbstractHttpConfigurer::disable)
                .addFilterAfter(new OwnerFilter(owners), AuthorizationFilter.class)
                .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain browser(HttpSecurity http, OwnerService owners) throws Exception {
        return http
                .authorizeHttpRequests(requests -> requests
                        // Коммит сборки — по нему ждут готовности и сверяют образ (ANO-194).
                        .requestMatchers("/api/v1/version", "/error").permitAll()
                        .anyRequest().authenticated())
                .oauth2Login(Customizer.withDefaults())
                // spa(): токен — в cookie XSRF-TOKEN, читаемой фронтом, и грузится на каждом запросе:
                // cookie есть у экрана до первой записи.
                .csrf(csrf -> csrf.spa())
                // API без сеанса — 401, а не переадресация на вход: fetch прошёл бы её до Keycloak
                // и упал на CORS, а фронт так и не увидел бы, что пора входить.
                .exceptionHandling(errors -> errors.defaultAuthenticationEntryPointFor(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                        PathPatternRequestMatcher.withDefaults().matcher("/api/**")))
                // После входа — на «/»: запомненный запрос к API увёл бы браузер на JSON.
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                // Владелец запроса — после авторизации: с ним пул пускает к строкам (ANO-144).
                .addFilterAfter(new OwnerFilter(owners), AuthorizationFilter.class)
                .build();
    }
}
