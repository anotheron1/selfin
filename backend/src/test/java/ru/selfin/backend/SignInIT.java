package ru.selfin.backend;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.selfin.backend.service.OwnerService;

import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
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

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
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
        String location = URLDecoder.decode(mvc.perform(get("/oauth2/authorization/keycloak"))
                .andExpect(status().isFound())
                .andReturn().getResponse().getHeader(HttpHeaders.LOCATION), StandardCharsets.UTF_8);
        assertThat(location)
                .startsWith(ISSUER + "/protocol/openid-connect/auth?")
                .contains("response_type=code", "client_id=selfin", "code_challenge_method=S256",
                        "redirect_uri=http://localhost/login/oauth2/code/keycloak");
    }

    // ── скрипты: Bearer ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("Bearer: свой токен — владелец из sub; повторный запрос — тот же владелец")
    void bearerOwnToken() throws Exception {
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
        OwnerService reconfigured = new OwnerService(jdbc, "owner-after-realm-reset");
        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken("owner-after-realm-reset", null, "ROLE_USER"));
        try {
            assertThat(reconfigured.current()).isNotEqualTo(OwnerService.SYNTHETIC_OWNER);
        } finally {
            SecurityContextHolder.clearContext();
        }
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
        assertThat(me(oidcLogin().idToken(t -> t.subject(OWNER_SUB)))).isEqualTo(SYNTHETIC_OWNER);
        assertThat(me(oidcLogin().idToken(t -> t.subject("browser-person"))))
                .isEqualTo(me(bearer(token(c -> c.subject("browser-person")))));
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

    /**
     * CSRF-токен, как его берёт экран: сервер выписывает cookie XSRF-TOKEN на любой запрос браузера, фронт
     * возвращает её значение заголовком. Не {@code csrf()} из spring-security-test: тот подменяет хранилище
     * токенов прямо в фильтре контекста, подмена переживает тест — и cookie после неё больше не выписывается.
     */
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
