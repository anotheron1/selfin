package ru.selfin.backend;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.sun.net.httpserver.HttpServer;
import jakarta.persistence.EntityManagerFactory;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2LoginAuthenticationFilter;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.selfin.backend.service.Consent;
import ru.selfin.backend.service.OwnerService;
import ru.selfin.backend.service.Registration;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Вход (ANO-195): браузер — через наш сервер, с сеансом и CSRF; скрипты — по Bearer с аудиторией selfin.
 * Владелец запроса — из {@code sub}; {@code sub} из {@code selfin.owner-sub} получает синтетического владельца.
 *
 * <p>Токены настоящие: ключ RSA и его JWKS — в самом тесте, на HTTP-сервере JDK по адресу ключей реалма.
 * Сервер проверяет их своей настройкой из application.properties — подпись, срок, издатель, аудиторию.
 *
 * <p>{@link MockMvc} здесь свой: у остальных IT каждый запрос — от вошедшего человека
 * ({@code testsupport.SignedInMockMvc}), а здесь вход и есть предмет.
 *
 * <p>Чужой странице CORS ничего не разрешает (ANO-153): экран и API — на одном адресе.
 *
 * <p>Спеки: docs/superpowers/specs/2026-10-13-server-knows-owner-design.md,
 * docs/superpowers/specs/2026-10-15-no-cors-design.md
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "selfin.owner-sub=" + SignInIT.OWNER_SUB)
@Testcontainers
class SignInIT {

    static final String OWNER_SUB = "a705ad03-owner";
    static final String SYNTHETIC_OWNER = "00000000-0000-0000-0000-000000000001";
    /** Издатель токенов — внешний адрес Keycloak из application.properties. */
    static final String ISSUER = "http://127.0.0.1:8180/realms/selfin";
    /** Страница чужого адреса — её JavaScript делает запросы к нашему API из браузера человека. */
    static final String FOREIGN_PAGE = "https://evil.example";
    /** Длина сеанса — решение владельца 15.10 (ANO-152), в секундах. */
    static final int THIRTY_DAYS = 30 * 24 * 60 * 60;

    static final RSAKey KEY = rsaKey("realm");
    static final HttpServer KEYS = serveKeys();

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    /** Ключи реалма — по внутреннему адресу Keycloak, как в docker. */
    @DynamicPropertySource
    static void keycloak(DynamicPropertyRegistry registry) {
        registry.add("selfin.keycloak.internal-url", () -> "http://127.0.0.1:" + KEYS.getAddress().getPort());
    }

    @AfterAll
    static void stopKeys() {
        KEYS.stop(0);
    }

    @Autowired
    WebApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Registration registration;

    @Autowired
    OwnerService owners;

    @Autowired
    EntityManagerFactory sessions;

    @Autowired
    ClientRegistrationRepository registrations;

    @Autowired
    FilterChainProxy security;

    @LocalServerPort
    int port;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        // Вошедший по умолчанию у oidcLogin() — «user»: здесь он не владелец и заводится только с согласием (ANO-196).
        owners.resolve("user", Consent.current());
    }

    // ── без входа ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("API без входа — 401, а не переадресация: fetch прошёл бы её до Keycloak и упал на CORS")
    void apiWithoutSignIn() throws Exception {
        mvc.perform(get("/api/v1/pocket")).andExpect(status().isUnauthorized());
        // И когда адрес API открыт в браузере — 401, а не вход: правило по адресу, не по заголовку Accept.
        mvc.perform(get("/api/v1/pocket").accept(MediaType.TEXT_HTML)).andExpect(status().isUnauthorized());
        // Сеанс истёк, cookie XSRF-TOKEN у браузера осталась — запись тоже 401, и экран уйдёт на вход.
        mvc.perform(post("/api/v1/categories").with(xsrfFromServer())).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("переход страницы без входа — переадресация на вход")
    void pageWithoutSignIn() throws Exception {
        String location = mvc.perform(get("/swagger-ui.html").accept(MediaType.TEXT_HTML))
                .andExpect(status().isFound())
                .andReturn().getResponse().getHeader(HttpHeaders.LOCATION);
        assertThat(location).endsWith("/oauth2/authorization/keycloak");
    }

    @Test
    @DisplayName("коммит сборки открыт: по нему ждут готовности и сверяют образ (ANO-194)")
    void versionIsOpen() throws Exception {
        mvc.perform(get("/api/v1/version")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("вход начинается у Keycloak: клиент selfin, код с PKCE S256, возврат — на адрес экрана")
    void signInStartsAtKeycloak() throws Exception {
        assertThat(authorizationRedirect(""))
                .startsWith(ISSUER + "/protocol/openid-connect/auth?")
                .contains("response_type=code", "client_id=selfin", "code_challenge_method=S256",
                        "redirect_uri=http://localhost/login/oauth2/code/keycloak");
    }

    @Test
    @DisplayName("кнопка экрана входа — сразу в Telegram: provider=telegram становится kc_idp_hint; без него и с чужим — нет (ANO-152)")
    void signInButtonGoesStraightToTelegram() throws Exception {
        assertThat(authorizationRedirect("?provider=telegram")).contains("kc_idp_hint=telegram", "code_challenge_method=S256");
        // Без подсказки — страница Keycloak: ею входит тестовый человек стенда и живая проверка входа.
        assertThat(authorizationRedirect("")).doesNotContain("kc_idp_hint");
        assertThat(authorizationRedirect("?provider=evil")).doesNotContain("kc_idp_hint");
    }

    // ── выход ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("выход гасит и наш сеанс, и сеанс Keycloak: ответ — адрес выхода Keycloak с возвратом на экран входа (ANO-152)")
    void signOutEndsKeycloakSessionToo() throws Exception {
        MvcResult result = mvc.perform(post("/logout")
                        .with(oidcLogin().clientRegistration(registrations.findByRegistrationId("keycloak"))
                                .idToken(t -> t.tokenValue("id-token-value")))
                        .with(xsrfFromServer()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(URLDecoder.decode(JsonPath.read(result.getResponse().getContentAsString(), "$.redirect"), StandardCharsets.UTF_8))
                .startsWith(ISSUER + "/protocol/openid-connect/logout?")
                .contains("id_token_hint=id-token-value", "post_logout_redirect_uri=http://localhost/login");
        HttpSession session = result.getRequest().getSession(false);
        assertThat(session == null || ((MockHttpSession) session).isInvalid()).as("наш сеанс погашен").isTrue();
    }

    @Test
    @DisplayName("выход без сеанса — сразу на экран входа (ANO-152)")
    void signOutWithoutSessionGoesToSignIn() throws Exception {
        mvc.perform(post("/logout").with(xsrfFromServer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.redirect").value("/login"));
    }

    @Test
    @DisplayName("кто вошёл: имя из ID token у сеанса и из токена у Bearer, без имени — ник (ANO-152)")
    void meTellsWhoSignedIn() throws Exception {
        for (String sub : List.of("named", "nick", "named-bearer")) consent(bearer(token(c -> c.subject(sub))));
        mvc.perform(get("/api/v1/me").with(oidcLogin().idToken(t -> t.subject("named").claim("name", "Another One"))))
                .andExpect(jsonPath("$.name").value("Another One"));
        mvc.perform(get("/api/v1/me").with(oidcLogin().idToken(t -> t.subject("nick").claim("preferred_username", "anotheron1"))))
                .andExpect(jsonPath("$.name").value("anotheron1"));
        mvc.perform(get("/api/v1/me").with(bearer(token(c -> c.subject("named-bearer").claim("name", "Bearer Name")))))
                .andExpect(jsonPath("$.name").value("Bearer Name"));
    }

    // ── сеанс ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("вход держится 30 дней: cookie сеанса — на 30 дней, сеанс вошедшего — 30 дней без действий (ANO-152)")
    void signedInSessionLastsThirtyDays() throws Exception {
        // Cookie выписывает Tomcat, и её настройку видит только настоящий сервер, не MockMvc. Сеанс рождается
        // на галочке экрана входа (ANO-196): до неё сервер сеанса не заводит.
        HttpClient http = HttpClient.newHttpClient();
        String xsrf = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/version")).build(),
                        HttpResponse.BodyHandlers.discarding())
                .headers().allValues(HttpHeaders.SET_COOKIE).stream().filter(c -> c.startsWith("XSRF-TOKEN="))
                .findFirst().orElseThrow().replaceAll("^XSRF-TOKEN=([^;]*).*", "$1");
        HttpResponse<Void> consent = http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/consent"))
                        .header(HttpHeaders.COOKIE, "XSRF-TOKEN=" + xsrf).header("X-XSRF-TOKEN", xsrf)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .POST(HttpRequest.BodyPublishers.ofString(consentBody(Consent.CURRENT))).build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(consent.statusCode()).isEqualTo(204);
        assertThat(consent.headers().allValues(HttpHeaders.SET_COOKIE))
                .anyMatch(c -> c.startsWith("JSESSIONID=") && c.contains("Max-Age=" + THIRTY_DAYS));

        // Сеанс вошедшего продлевает обработчик успешного входа — тот, что стоит в цепочке браузера.
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        onSignIn().onAuthenticationSuccess(request, response, new TestingAuthenticationToken("someone", null));
        assertThat(request.getSession().getMaxInactiveInterval()).isEqualTo(THIRTY_DAYS);
        assertThat(response.getRedirectedUrl()).isEqualTo("/");
    }

    // ── скрипты: Bearer ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("Bearer: свой токен — владелец из sub; повторный запрос — тот же владелец")
    void bearerOwnToken() throws Exception {
        consent(bearer(token(c -> c.subject("someone"))));
        consent(bearer(token(c -> c.subject("someone-else"))));
        String first = me(bearer(token(c -> c.subject("someone"))));
        String again = me(bearer(token(c -> c.subject("someone"))));
        assertThat(first).isNotEqualTo(SYNTHETIC_OWNER).isEqualTo(again);
        assertThat(me(bearer(token(c -> c.subject("someone-else"))))).isNotEqualTo(first);
    }

    @Test
    @DisplayName("Bearer: sub из selfin.owner-sub — синтетический владелец, хозяин нынешних данных")
    void bearerOwnerSub() throws Exception {
        assertThat(me(bearer(token(c -> c.subject(OWNER_SUB))))).isEqualTo(SYNTHETIC_OWNER);
        assertThat(me(bearer(token(c -> c.subject(OWNER_SUB))))).isEqualTo(SYNTHETIC_OWNER);
    }

    @Test
    @DisplayName("связь — один раз: синтетический владелец уже связан, и новый sub из настройки его не получает")
    void ownerLinkIsOneTime() throws Exception {
        assertThat(me(bearer(token(c -> c.subject(OWNER_SUB))))).isEqualTo(SYNTHETIC_OWNER);
        // Реалм пересоздали, у владельца новый sub, его вписали в настройку: данные остаются у прежнего.
        OwnerService reconfigured = new OwnerService(jdbc, registration, sessions, "owner-after-realm-reset");
        assertThat(reconfigured.resolve("owner-after-realm-reset", Consent.current()))
                .isNotEqualTo(OwnerService.SYNTHETIC_OWNER);
        assertThat(me(bearer(token(c -> c.subject(OWNER_SUB))))).isEqualTo(SYNTHETIC_OWNER);
    }

    @Test
    @DisplayName("Bearer: аудитория account — токен консоли Keycloak, не наш: 401")
    void bearerForeignAudience() throws Exception {
        mvc.perform(get("/api/v1/me").with(bearer(token(c -> c.audience(List.of("account"))))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Bearer: чужой издатель, чужой ключ, истёкший срок — 401")
    void bearerForeignIssuerKeyOrExpired() throws Exception {
        mvc.perform(get("/api/v1/me").with(bearer(token(c -> c.issuer("http://127.0.0.1:8180/realms/master")))))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me").with(bearer(token(rsaKey("realm"), c -> { }))))
                .andExpect(status().isUnauthorized());
        Instant hourAgo = Instant.now().minusSeconds(3600);
        mvc.perform(get("/api/v1/me").with(bearer(token(c -> c.issuedAt(hourAgo).expiresAt(hourAgo.plusSeconds(300))))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Bearer пишет без CSRF: у скриптов нет cookie, подделывать нечего")
    void bearerWritesWithoutCsrf() throws Exception {
        consent(bearer(token(c -> { })));
        mvc.perform(post("/api/v1/categories").with(bearer(token(c -> { })))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Вход Bearer\",\"type\":\"EXPENSE\"}"))
                .andExpect(status().isOk());
    }

    // ── браузер: сеанс ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("сеанс: запись без CSRF — 403, с CSRF — записано")
    void sessionWriteNeedsCsrf() throws Exception {
        mvc.perform(post("/api/v1/categories").with(oidcLogin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Вход без CSRF\",\"type\":\"EXPENSE\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/categories").with(oidcLogin()).with(xsrfFromServer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Вход с CSRF\",\"type\":\"EXPENSE\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("сеанс отдаёт CSRF-токен в cookie XSRF-TOKEN — её читает фронт")
    void sessionGivesCsrfCookie() throws Exception {
        mvc.perform(get("/api/v1/me").with(oidcLogin()))
                .andExpect(status().isOk())
                .andExpect(cookie().exists("XSRF-TOKEN"))
                .andExpect(cookie().httpOnly("XSRF-TOKEN", false));
    }

    @Test
    @DisplayName("сеанс: владелец — из sub ID token, тот же, что у Bearer")
    void sessionOwnerFromSub() throws Exception {
        consent(bearer(token(c -> c.subject("browser-person"))));
        assertThat(me(oidcLogin().idToken(t -> t.subject(OWNER_SUB)))).isEqualTo(SYNTHETIC_OWNER);
        assertThat(me(oidcLogin().idToken(t -> t.subject("browser-person"))))
                .isEqualTo(me(bearer(token(c -> c.subject("browser-person")))));
    }

    // ── согласие (ANO-196) ──────────────────────────────────────────────────────

    @Test
    @DisplayName("без согласия незнакомого не заводим: 403 с кодом CONSENT_REQUIRED, строки нет (ANO-196)")
    void noConsentNoRegistration() throws Exception {
        mvc.perform(get("/api/v1/me").with(bearer(token(c -> c.subject("no-consent")))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.details[0]").value("CONSENT_REQUIRED"));
        assertThat(users("no-consent")).isZero();
    }

    @Test
    @DisplayName("согласие скрипта заводит человека с версией текста и временем (ANO-196)")
    void bearerConsentRegisters() throws Exception {
        Instant before = Instant.now().minusSeconds(1);
        consent(bearer(token(c -> c.subject("consenting"))));
        assertThat(jdbc.queryForObject("SELECT consent_version FROM users WHERE external_id = 'consenting'", String.class))
                .isEqualTo(Consent.CURRENT);
        assertThat(jdbc.queryForObject("SELECT consent_at FROM users WHERE external_id = 'consenting'", OffsetDateTime.class)
                .toInstant()).isAfter(before);
        me(bearer(token(c -> c.subject("consenting"))));
    }

    @Test
    @DisplayName("устаревшая страница — 409: согласие на другой текст не записываем (ANO-196)")
    void staleVersionRefused() throws Exception {
        mvc.perform(post("/api/v1/consent").with(bearer(token(c -> c.subject("stale-page"))))
                        .contentType(MediaType.APPLICATION_JSON).content(consentBody("1999-01-01")))
                .andExpect(status().isConflict());
        assertThat(users("stale-page")).isZero();
    }

    @Test
    @DisplayName("галочка до входа — отметка в сеансе, и только с CSRF (ANO-196)")
    void signedOutConsentGoesToSession() throws Exception {
        mvc.perform(post("/api/v1/consent").contentType(MediaType.APPLICATION_JSON).content(consentBody(Consent.CURRENT)))
                .andExpect(status().isForbidden());
        HttpSession session = mvc.perform(post("/api/v1/consent").with(xsrfFromServer())
                        .contentType(MediaType.APPLICATION_JSON).content(consentBody(Consent.CURRENT)))
                .andExpect(status().isNoContent())
                .andReturn().getRequest().getSession(false);
        assertThat(session).isNotNull();
        assertThat(session.getAttribute(Consent.SESSION_KEY)).isInstanceOf(Consent.class);
    }

    @Test
    @DisplayName("вошедший из браузера без строки: галочка заводит его и кладёт отметку для входа (ANO-196)")
    void signedInConsentRegistersAndMarks() throws Exception {
        HttpSession session = mvc.perform(post("/api/v1/consent").with(oidcLogin().idToken(t -> t.subject("browser-late")))
                        .with(xsrfFromServer()).contentType(MediaType.APPLICATION_JSON).content(consentBody(Consent.CURRENT)))
                .andExpect(status().isNoContent())
                .andReturn().getRequest().getSession(false);
        assertThat(users("browser-late")).isOne();
        assertThat(session.getAttribute(Consent.SESSION_KEY)).isInstanceOf(Consent.class);
    }

    @Test
    @DisplayName("в Keycloak — только с отметкой: без неё наш адрес входа ведёт на экран входа (ANO-196)")
    void keycloakOnlyWithConsent() throws Exception {
        mvc.perform(get("/oauth2/authorization/keycloak?provider=telegram"))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, "/login"));
        assertThat(authorizationRedirect("?provider=telegram")).startsWith(ISSUER + "/protocol/openid-connect/auth?");
    }

    @Test
    @DisplayName("вход с отметкой заводит с временем галочки и снимает отметку; без отметки — не заводит (ANO-196)")
    void signInRegistersOnlyWithConsent() throws Exception {
        Consent ticked = new Consent(Consent.CURRENT, Instant.now().minus(5, ChronoUnit.MINUTES));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession().setAttribute(Consent.SESSION_KEY, ticked);
        onSignIn().onAuthenticationSuccess(request, new MockHttpServletResponse(), new TestingAuthenticationToken("signed-in", null));
        assertThat(request.getSession().getAttribute(Consent.SESSION_KEY)).as("отметка снята").isNull();
        assertThat(jdbc.queryForObject("SELECT consent_at FROM users WHERE external_id = 'signed-in'", OffsetDateTime.class)
                .toInstant()).isCloseTo(ticked.at(), within(1, ChronoUnit.MILLIS));

        MockHttpServletRequest unmarked = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        onSignIn().onAuthenticationSuccess(unmarked, response, new TestingAuthenticationToken("signed-in-unmarked", null));
        assertThat(response.getRedirectedUrl()).isEqualTo("/");
        assertThat(users("signed-in-unmarked")).isZero();
    }

    @Test
    @DisplayName("коммит сборки открыт и вошедшему без согласия — экран сверяет версию до всего (ANO-196)")
    void versionOpenWithoutConsent() throws Exception {
        mvc.perform(get("/api/v1/version").with(bearer(token(c -> c.subject("version-only"))))).andExpect(status().isOk());
    }

    // ── чужая страница: CORS ────────────────────────────────────────────────────

    @Test
    @DisplayName("чужому адресу разрешений CORS нет — ни на открытый адрес, ни на запрос с сеансом (ANO-153)")
    void noCorsPermissionForForeignOrigin() throws Exception {
        // Предзапрос чужого адреса и без этого получает 401 от цепочки безопасности; разрешение CORS попадало
        // в ответы, прошедшие безопасность, — на открытые адреса и на запросы с сеансом.
        mvc.perform(get("/api/v1/version").header(HttpHeaders.ORIGIN, FOREIGN_PAGE))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        mvc.perform(get("/api/v1/me").with(oidcLogin()).header(HttpHeaders.ORIGIN, FOREIGN_PAGE))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    // ── помощники ───────────────────────────────────────────────────────────────

    private String me(RequestPostProcessor who) throws Exception {
        String body = mvc.perform(get("/api/v1/me").with(who))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isString())
                .andReturn().getResponse().getContentAsString();
        return body.replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1");
    }

    /** Согласие, как его даёт скрипт или экран вошедшего (ANO-196): POST с версией текста. */
    private void consent(RequestPostProcessor who) throws Exception {
        mvc.perform(post("/api/v1/consent").with(who).contentType(MediaType.APPLICATION_JSON).content(consentBody(Consent.CURRENT)))
                .andExpect(status().isNoContent());
    }

    private static String consentBody(String version) {
        return "{\"version\":\"" + version + "\"}";
    }

    /** Сеанс с отметкой галочки — как после неё на экране входа. */
    private static MockHttpSession consented() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(Consent.SESSION_KEY, Consent.current());
        return session;
    }

    private long users(String sub) {
        return jdbc.queryForObject("SELECT count(*) FROM users WHERE external_id = ?", Long.class, sub);
    }

    /**
     * CSRF-токен, как его берёт экран: сервер выписывает cookie XSRF-TOKEN на любой запрос браузера, фронт
     * возвращает её значение заголовком. Не {@code csrf()} из spring-security-test: тот подменяет хранилище
     * токенов прямо в фильтре контекста, подмена переживает тест — и cookie после неё больше не выписывается.
     */
    /** Куда сервер уводит браузер, начиная вход, — адрес Keycloak с параметрами. */
    private AuthenticationSuccessHandler onSignIn() {
        OAuth2LoginAuthenticationFilter login = security.getFilterChains().stream()
                .flatMap(chain -> chain.getFilters().stream())
                .filter(OAuth2LoginAuthenticationFilter.class::isInstance)
                .map(OAuth2LoginAuthenticationFilter.class::cast)
                .findFirst().orElseThrow();
        return ReflectionTestUtils.invokeMethod(login, "getSuccessHandler");
    }

    private String authorizationRedirect(String query) throws Exception {
        return URLDecoder.decode(mvc.perform(get("/oauth2/authorization/keycloak" + query).session(consented()))
                .andExpect(status().isFound())
                .andReturn().getResponse().getHeader(HttpHeaders.LOCATION), StandardCharsets.UTF_8);
    }

    private RequestPostProcessor xsrfFromServer() throws Exception {
        Cookie xsrf = mvc.perform(get("/api/v1/version")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(xsrf).as("сервер выписал cookie XSRF-TOKEN").isNotNull();
        return request -> {
            request.setCookies(xsrf);
            request.addHeader("X-XSRF-TOKEN", xsrf.getValue());
            return request;
        };
    }

    private static RequestPostProcessor bearer(String token) {
        return request -> {
            request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            return request;
        };
    }

    /** Токен, каким его выдал бы реалм: наш издатель, аудитория selfin, срок — пять минут. */
    private static String token(Consumer<JwtClaimsSet.Builder> change) {
        return token(KEY, change);
    }

    private static String token(RSAKey key, Consumer<JwtClaimsSet.Builder> change) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(ISSUER).audience(List.of("selfin")).subject("scripts")
                .issuedAt(now).expiresAt(now.plusSeconds(300));
        change.accept(claims);
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)))
                .encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).keyId(key.getKeyID()).build(),
                        claims.build()))
                .getTokenValue();
    }

    private static RSAKey rsaKey(String id) {
        try {
            return new RSAKeyGenerator(2048).keyID(id).generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpServer serveKeys() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            byte[] body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            server.createContext("/realms/selfin/protocol/openid-connect/certs", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
