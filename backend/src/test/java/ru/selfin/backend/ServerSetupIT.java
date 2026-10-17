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
        HttpResponse<Void> start = signInStart("https");
        assertThat(start.statusCode()).isEqualTo(302);
        assertThat(URLDecoder.decode(start.headers().firstValue(HttpHeaders.LOCATION).orElseThrow(), UTF_8))
                .contains("redirect_uri=https://localhost/login/oauth2/code/keycloak");
        assertThat(start.headers().allValues(HttpHeaders.SET_COOKIE))
                .anyMatch(cookie -> cookie.startsWith("JSESSIONID=") && cookie.contains("Secure"));
    }

    @Test
    @DisplayName("за HTTPS-прокси на нестандартном порту адрес возврата сохраняет порт из X-Forwarded-Port (ревью Codex на #171)")
    void behindHttpsProxyOnOwnPort() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/oauth2/authorization/keycloak"))
                .header("X-Forwarded-Proto", "https").header("X-Forwarded-Port", "8443").build();
        HttpResponse<Void> start = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding());
        assertThat(URLDecoder.decode(start.headers().firstValue(HttpHeaders.LOCATION).orElseThrow(), UTF_8))
                .contains("redirect_uri=https://localhost:8443/login/oauth2/code/keycloak");
    }

    @Test
    @DisplayName("без прокси — как прежде: адрес возврата http, cookie без Secure — стенд и разработка не задеты")
    void withoutProxy() throws Exception {
        HttpResponse<Void> start = signInStart(null);
        assertThat(URLDecoder.decode(start.headers().firstValue(HttpHeaders.LOCATION).orElseThrow(), UTF_8))
                .contains("redirect_uri=http://localhost:" + port + "/login/oauth2/code/keycloak");
        assertThat(start.headers().allValues(HttpHeaders.SET_COOKIE))
                .filteredOn(cookie -> cookie.startsWith("JSESSIONID="))
                .noneMatch(cookie -> cookie.contains("Secure"));
    }

    /** Начало входа: наш сервер уводит в Keycloak. {@code proto} — схема, которую проставил бы прокси. */
    private HttpResponse<Void> signInStart(String proto) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/oauth2/authorization/keycloak"));
        if (proto != null) request.header("X-Forwarded-Proto", proto);
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.discarding());
    }
}
