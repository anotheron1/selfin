package ru.selfin.backend.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientPropertiesMapper;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.servlet.JwkSetUriJwtDecoderBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenValidator;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestHeaderRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.client.RestOperations;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.selfin.backend.service.Consent;
import ru.selfin.backend.service.ConsentRequiredException;
import ru.selfin.backend.service.OwnerService;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
 * <p>Экран входа (ANO-152): кнопка «Войти через Telegram» ведёт прямо к Telegram — {@link ProviderHint};
 * вход держится 30 дней — {@link LongSessionAfterSignIn}; выход гасит и наш сеанс, и сеанс Keycloak —
 * {@link SignOutThroughKeycloak}. В Keycloak — только после галочки согласия — {@link ConsentBeforeKeycloak} (ANO-196).
 *
 * <p>Спеки: docs/superpowers/specs/2026-10-13-server-knows-owner-design.md,
 * docs/superpowers/specs/2026-10-15-sign-in-screen-design.md
 */
@Configuration
// Свойства клиента Boot включает в своей конфигурации репозитория регистраций, а она уступает нашему (ANO-152).
@EnableConfigurationProperties(OAuth2ClientProperties.class)
public class SecurityConfig {

    @Bean
    @Order(1)
    SecurityFilterChain scripts(HttpSecurity http, OwnerService owners, ObjectMapper json) throws Exception {
        return http
                .securityMatcher(new RequestHeaderRequestMatcher(HttpHeaders.AUTHORIZATION))
                .authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
                .oauth2ResourceServer(server -> server.jwt(Customizer.withDefaults()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Подделывать нечего: у скриптов нет cookie, а токен в заголовок чужая страница не положит.
                .csrf(AbstractHttpConfigurer::disable)
                .addFilterAfter(new OwnerFilter(owners, json), AuthorizationFilter.class)
                .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain browser(HttpSecurity http, OwnerService owners, ClientRegistrationRepository registrations,
                                ObjectMapper json,
                                @Value("${server.servlet.session.cookie.max-age}") Duration session) throws Exception {
        return http
                .authorizeHttpRequests(requests -> requests
                        // Коммит сборки — по нему ждут готовности и сверяют образ (ANO-194).
                        .requestMatchers("/api/v1/version", "/error").permitAll()
                        // Галочка согласия — до входа (ANO-196); CSRF у неё, как у всех записей браузера.
                        .requestMatchers(HttpMethod.POST, "/api/v1/consent").permitAll()
                        .anyRequest().authenticated())
                .oauth2Login(login -> login
                        .authorizationEndpoint(endpoint -> endpoint.authorizationRequestResolver(new ProviderHint(registrations)))
                        .successHandler(new LongSessionAfterSignIn(session, owners)))
                .addFilterBefore(new ConsentBeforeKeycloak(), OAuth2AuthorizationRequestRedirectFilter.class)
                .logout(logout -> logout.logoutSuccessHandler(new SignOutThroughKeycloak(registrations, json)))
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
                .addFilterAfter(new OwnerFilter(owners, json), AuthorizationFilter.class)
                .build();
    }

    /**
     * В Keycloak — только после галочки (ANO-196): наш адрес входа без отметки согласия в сеансе ведёт на экран входа.
     * Иначе ссылка в обход экрана завела бы учётную запись Keycloak с именем и ником из Telegram ещё до согласия.
     */
    static final class ConsentBeforeKeycloak extends OncePerRequestFilter {

        private static final RequestMatcher SIGN_IN =
                PathPatternRequestMatcher.withDefaults().matcher("/oauth2/authorization/**");

        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            return !SIGN_IN.matches(request);
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            HttpSession session = request.getSession(false);
            if (session == null || session.getAttribute(Consent.SESSION_KEY) == null) {
                response.sendRedirect("/login");
                return;
            }
            chain.doFilter(request, response);
        }
    }

    /**
     * Сколько ждать ключи реалма (ANO-244). Библиотека по умолчанию ждёт их полсекунды, и первый вход после старта бэка
     * падал, если Keycloak ещё холодный: перезагрузка сервера, перезапуск Keycloak. С запасом холодный успевает
     * ответить, а мёртвый — отказ через пять секунд, а не вечное ожидание.
     */
    static final Duration KEYS_CONNECT = Duration.ofSeconds(2);
    static final Duration KEYS_READ = Duration.ofSeconds(5);

    /** Bearer: декодер собирает Boot со своими проверками — издатель, аудитория, срок; здесь — только клиент ключей. */
    @Bean
    JwkSetUriJwtDecoderBuilderCustomizer bearerKeysWithMargin() {
        RestOperations keys = keysClient(KEYS_CONNECT, KEYS_READ);
        return builder -> builder.restOperations(keys);
    }

    /**
     * Вход через браузер: ID token проверяет декодер из этой фабрики — её берёт {@code oauth2Login}. У
     * {@link OidcIdTokenDecoderFactory} клиента не задать, поэтому декодер — тот же, что строит она: RS256, срок и
     * проверки OIDC, типы утверждений, — только с клиентом ключей.
     */
    @Bean
    JwtDecoderFactory<ClientRegistration> idTokenDecoders() {
        return idTokenDecodersWith(keysClient(KEYS_CONNECT, KEYS_READ));
    }

    /** Декодер — один на регистрацию, как у оригинала: ключи кэшируются в нём, а не запрашиваются на каждый вход. */
    static JwtDecoderFactory<ClientRegistration> idTokenDecodersWith(RestOperations keys) {
        Map<String, JwtDecoder> decoders = new ConcurrentHashMap<>();
        return registration -> decoders.computeIfAbsent(registration.getRegistrationId(), id -> {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(registration.getProviderDetails().getJwkSetUri())
                    .jwsAlgorithm(SignatureAlgorithm.RS256)
                    .restOperations(keys)
                    .build();
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                    new JwtTimestampValidator(), new OidcIdTokenValidator(registration)));
            decoder.setClaimSetConverter(OidcIdTokenDecoderFactory.createDefaultClaimTypeConverter());
            return decoder;
        });
    }

    static RestOperations keysClient(Duration connect, Duration read) {
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(connect);
        requests.setReadTimeout(read);
        return new RestTemplate(requests);
    }

    /**
     * Регистрация клиента — из свойств Boot и то, что без discovery взять неоткуда (ANO-152): адрес выхода
     * Keycloak — для выхода и в нём — и издатель, по которому сверяется {@code iss} у ID token. Реалм — тот же,
     * что у Bearer.
     */
    @Bean
    ClientRegistrationRepository clientRegistrations(OAuth2ClientProperties properties,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String realm) {
        return new InMemoryClientRegistrationRepository(new OAuth2ClientPropertiesMapper(properties)
                .asClientRegistrations().values().stream()
                .map(registration -> ClientRegistration.withClientRegistration(registration)
                        .issuerUri(realm)
                        .providerConfigurationMetadata(Map.of("end_session_endpoint", realm + "/protocol/openid-connect/logout"))
                        .build())
                .toList());
    }

    /**
     * Кнопка экрана входа называет провайдера, и Keycloak уводит прямо к нему, без своей страницы (ANO-152):
     * {@code ?provider=telegram} → {@code kc_idp_hint=telegram}. Только известные провайдеры. Без подсказки — страница
     * Keycloak: ею входит тестовый человек стенда и живая проверка входа.
     */
    static final class ProviderHint implements OAuth2AuthorizationRequestResolver {

        private static final Set<String> PROVIDERS = Set.of("telegram");

        private final OAuth2AuthorizationRequestResolver resolver;

        ProviderHint(ClientRegistrationRepository registrations) {
            resolver = new DefaultOAuth2AuthorizationRequestResolver(registrations,
                    OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI);
        }

        @Override
        public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
            return hint(request, resolver.resolve(request));
        }

        @Override
        public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String registrationId) {
            return hint(request, resolver.resolve(request, registrationId));
        }

        /** Адрес Keycloak пересобирается из параметров: {@code from} копирует их, но не готовый адрес. */
        private static OAuth2AuthorizationRequest hint(HttpServletRequest request, OAuth2AuthorizationRequest authorization) {
            String provider = request.getParameter("provider");
            // Set.of не принимает null даже в contains — без провайдера проверка до него не доходит.
            if (authorization == null || provider == null || !PROVIDERS.contains(provider)) return authorization;
            return OAuth2AuthorizationRequest.from(authorization)
                    .additionalParameters(parameters -> parameters.put("kc_idp_hint", provider))
                    .build();
        }
    }

    /**
     * Вход держится 30 дней (решение владельца 15.10, ANO-152): сеанс вошедшего живёт без действий столько же, сколько
     * cookie сеанса, — у кассы приложение открывается сразу, подтверждение в Telegram — раз в месяц. Сеанс до входа
     * остаётся по умолчанию сервера, 30 минут: брошенный вход не держит память сервера месяц. После входа — на «/».
     *
     * <p>Отметка галочки живёт до входа и не дольше (ANO-196): незнакомый человек заводится с ней, а с сеанса она
     * снимается — иначе удалённый по просьбе с живым сеансом завёлся бы снова без нового согласия.
     */
    static final class LongSessionAfterSignIn extends SimpleUrlAuthenticationSuccessHandler {

        private final Duration length;
        private final OwnerService owners;

        LongSessionAfterSignIn(Duration length, OwnerService owners) {
            super("/");
            this.length = length;
            this.owners = owners;
        }

        @Override
        public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                            Authentication authentication) throws IOException, ServletException {
            HttpSession session = request.getSession();
            session.setMaxInactiveInterval((int) length.toSeconds());
            Consent consent = (Consent) session.getAttribute(Consent.SESSION_KEY);
            session.removeAttribute(Consent.SESSION_KEY);
            try {
                owners.resolve(authentication.getName(), consent);
            } catch (ConsentRequiredException e) {
                // Отметки нет — сеанс истёк между галочкой и возвратом: не заводим, первый запрос экрана уведёт к галочке.
            }
            super.onAuthenticationSuccess(request, response, authentication);
        }
    }

    /**
     * Выход — и у нас, и в Keycloak (ANO-152): иначе сеанс Keycloak жив, и «Войти» впустит без подтверждения.
     * Экран шлёт {@code POST /logout} запросом с заголовком CSRF: форма с CSRF {@code spa()} не проходит — параметр
     * {@code _csrf} сверяется как замаскированный токен, а у экрана только сырой. Поэтому вместо переадресации —
     * 200 и адрес выхода Keycloak с возвратом на экран входа: по нему экран уходит сам, и браузер гасит сеанс
     * Keycloak. Без сеанса — сразу на экран входа.
     */
    static final class SignOutThroughKeycloak extends OidcClientInitiatedLogoutSuccessHandler {

        private final ObjectMapper json;

        SignOutThroughKeycloak(ClientRegistrationRepository registrations, ObjectMapper json) {
            super(registrations);
            this.json = json;
            setPostLogoutRedirectUri("{baseUrl}/login");
            setDefaultTargetUrl("/login");
        }

        @Override
        public void onLogoutSuccess(HttpServletRequest request, HttpServletResponse response,
                                    Authentication authentication) throws IOException {
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            json.writeValue(response.getWriter(), Map.of("redirect", determineTargetUrl(request, response, authentication)));
        }
    }
}
