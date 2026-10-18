package ru.selfin.backend;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.selfin.backend.service.Consent;

import javax.sql.DataSource;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Сборка для сервера (ANO-197, шаг 3 плана спринта 6): за HTTPS-прокси и со своим паролем роли приложения.
 *
 * <p>Схему прокси видит только настоящий Tomcat — её разбирает он сам ({@code server.forward-headers-strategy=native}),
 * не MockMvc. Пароль роли — свой, не пароль разработки из {@code V32}: приложение со своим паролем стартует, только если
 * после миграций пароль роли поставлен из настройки.
 *
 * <p>Спека: docs/superpowers/specs/2026-10-17-server-compose-design.md
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "selfin.db.app-password=" + ServerSetupIT.APP_PASSWORD)
@Testcontainers
class ServerSetupIT {

    static final String APP_PASSWORD = "server-own-secret";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @LocalServerPort
    int port;

    @Autowired
    DataSource pool;

    @Test
    @DisplayName("пароль роли приложения — из настройки, а не пароль разработки из V32: пул приложения входит со своим")
    void appRoleGetsPasswordFromSettings() throws Exception {
        try (Connection connection = pool.getConnection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT current_user")) {
            result.next();
            assertThat(result.getString(1)).isEqualTo("selfin_app");
        }
    }

    @Test
    @DisplayName("за HTTPS-прокси адрес возврата из Keycloak — https, cookie сеанса — Secure")
    void behindHttpsProxy() throws Exception {
        Start start = signInStart("X-Forwarded-Proto", "https");
        assertThat(start.keycloak().statusCode()).isEqualTo(302);
        assertThat(start.redirect()).contains("redirect_uri=https://localhost/login/oauth2/code/keycloak");
        assertThat(start.sessionCookie()).contains("Secure");
    }

    @Test
    @DisplayName("за HTTPS-прокси на нестандартном порту адрес возврата сохраняет порт из X-Forwarded-Port (ревью Codex на #171)")
    void behindHttpsProxyOnOwnPort() throws Exception {
        assertThat(signInStart("X-Forwarded-Proto", "https", "X-Forwarded-Port", "8443").redirect())
                .contains("redirect_uri=https://localhost:8443/login/oauth2/code/keycloak");
    }

    @Test
    @DisplayName("без прокси — как прежде: адрес возврата http, cookie без Secure — стенд и разработка не задеты")
    void withoutProxy() throws Exception {
        Start start = signInStart();
        assertThat(start.redirect()).contains("redirect_uri=http://localhost:" + port + "/login/oauth2/code/keycloak");
        assertThat(start.sessionCookie()).doesNotContain("Secure");
    }

    /** Галочка и переход в Keycloak: сеанс рождается на галочке, адрес возврата — у перехода. */
    private record Start(HttpResponse<Void> consent, HttpResponse<Void> keycloak) {

        String redirect() {
            return URLDecoder.decode(keycloak.headers().firstValue(HttpHeaders.LOCATION).orElseThrow(), UTF_8);
        }

        String sessionCookie() {
            return consent.headers().allValues(HttpHeaders.SET_COOKIE).stream()
                    .filter(cookie -> cookie.startsWith("JSESSIONID=")).findFirst().orElseThrow();
        }
    }

    /**
     * Начало входа, как у экрана (ANO-196): галочка с CSRF кладёт отметку в сеанс, и только с ней наш адрес входа уводит
     * в Keycloak. {@code proxy} — заголовки, которые проставил бы прокси, парами «имя, значение», — на каждом запросе.
     */
    private Start signInStart(String... proxy) throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        String xsrf = cookie(http.send(request("/api/v1/version", proxy).build(), HttpResponse.BodyHandlers.discarding()),
                "XSRF-TOKEN");
        HttpResponse<Void> consent = http.send(request("/api/v1/consent", proxy)
                        .header(HttpHeaders.COOKIE, "XSRF-TOKEN=" + xsrf).header("X-XSRF-TOKEN", xsrf)
                        .header(HttpHeaders.CONTENT_TYPE, "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"version\":\"" + Consent.CURRENT + "\"}")).build(),
                HttpResponse.BodyHandlers.discarding());
        HttpResponse<Void> keycloak = http.send(request("/oauth2/authorization/keycloak", proxy)
                        .header(HttpHeaders.COOKIE, "JSESSIONID=" + cookie(consent, "JSESSIONID")).build(),
                HttpResponse.BodyHandlers.discarding());
        return new Start(consent, keycloak);
    }

    private HttpRequest.Builder request(String path, String... proxy) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        for (int i = 0; i < proxy.length; i += 2) request.header(proxy[i], proxy[i + 1]);
        return request;
    }

    private static String cookie(HttpResponse<?> response, String name) {
        return response.headers().allValues(HttpHeaders.SET_COOKIE).stream().filter(c -> c.startsWith(name + "="))
                .findFirst().orElseThrow().replaceAll("^" + name + "=([^;]*).*", "$1");
    }
}
