package ru.selfin.backend.config;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ключи Keycloak с запасом — в самом приложении (ANO-244): Bearer-декодер, который собирает Boot, и фабрика декодеров
 * входа, которую берёт {@code oauth2Login}, ждут ключи секунду. Адреса Keycloak — у {@link SlowKeycloak}: издатель —
 * публичный адрес, ключи — внутренний, как на сервере.
 *
 * <p>Спека: docs/superpowers/specs/2026-10-26-keycloak-keys-timeout-design.md
 */
@SpringBootTest
@Testcontainers
class KeycloakKeysTimeoutIT {

    private static final String PUBLIC = "http://issuer.test";
    private static final SlowKeycloak keycloak = SlowKeycloak.start();

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @DynamicPropertySource
    static void keycloakAddresses(DynamicPropertyRegistry properties) {
        properties.add("selfin.keycloak.url", () -> PUBLIC);
        properties.add("selfin.keycloak.internal-url", keycloak::url);
    }

    @AfterAll
    static void stop() {
        keycloak.close();
    }

    @Autowired JwtDecoder bearer;
    @Autowired JwtDecoderFactory<ClientRegistration> idTokens;
    @Autowired ClientRegistrationRepository registrations;

    @Test
    @DisplayName("Bearer-декодер приложения: ключи за секунду — токен принят")
    void bearerWaitsForSlowKeys() {
        keycloak.delay = Duration.ofSeconds(1);
        String token = keycloak.token(PUBLIC + "/realms/selfin", "selfin", Instant.now().plusSeconds(300));
        assertThat(bearer.decode(token).getSubject()).isEqualTo("person");
    }

    @Test
    @DisplayName("декодер входа из контекста: ключи за секунду — ID token принят")
    void idTokenWaitsForSlowKeys() {
        keycloak.delay = Duration.ofSeconds(1);
        String idToken = keycloak.token(PUBLIC + "/realms/selfin", "selfin", Instant.now().plusSeconds(300));
        JwtDecoder decoder = idTokens.createDecoder(registrations.findByRegistrationId("keycloak"));
        assertThat(decoder.decode(idToken).getSubject()).isEqualTo("person");
    }
}
