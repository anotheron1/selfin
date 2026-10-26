package ru.selfin.backend.config;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ключи Keycloak — с запасом по времени (ANO-244): библиотека по умолчанию ждёт их полсекунды, и первый вход после
 * старта бэка на холодном Keycloak падал. Keycloak здесь — {@link SlowKeycloak}: ключи отдаёт с задержкой.
 *
 * <p>Спека: docs/superpowers/specs/2026-10-26-keycloak-keys-timeout-design.md
 */
class KeycloakKeysTimeoutTest {

    private static final String REALM = "http://issuer.test/realms/selfin";
    private static final Duration SLOW = Duration.ofSeconds(1);

    private static SlowKeycloak keycloak;

    @BeforeAll
    static void start() {
        keycloak = SlowKeycloak.start();
    }

    @AfterAll
    static void stop() {
        keycloak.close();
    }

    @BeforeEach
    void reset() {
        keycloak.delay = Duration.ZERO;
        keycloak.hits.set(0);
    }

    @Test
    @DisplayName("контроль: декодер библиотеки ждёт ключи полсекунды — секунда для него отказ")
    void libraryDefaultGivesUp() {
        keycloak.delay = SLOW;
        JwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(keycloak.certs()).build();
        assertThatThrownBy(() -> decoder.decode(token(REALM, "selfin")))
                .isInstanceOf(JwtException.class).hasRootCauseInstanceOf(SocketTimeoutException.class);
    }

    @Test
    @DisplayName("Bearer: ключи за секунду — токен принят")
    void bearerWaitsForSlowKeys() {
        keycloak.delay = SLOW;
        NimbusJwtDecoder.JwkSetUriJwtDecoderBuilder builder = NimbusJwtDecoder.withJwkSetUri(keycloak.certs());
        new SecurityConfig().bearerKeysWithMargin().customize(builder);
        assertThat(builder.build().decode(token(REALM, "selfin")).getSubject()).isEqualTo("person");
    }

    @Test
    @DisplayName("вход через браузер: ключи за секунду — ID token принят")
    void idTokenWaitsForSlowKeys() {
        keycloak.delay = SLOW;
        JwtDecoder decoder = new SecurityConfig().idTokenDecoders().createDecoder(registration());
        assertThat(decoder.decode(token(REALM, "selfin")).getSubject()).isEqualTo("person");
    }

    @Test
    @DisplayName("вход через браузер: проверки ID token на месте — чужая аудитория, чужой издатель, истёкший — отказ")
    void idTokenChecksKept() {
        JwtDecoder decoder = new SecurityConfig().idTokenDecoders().createDecoder(registration());
        assertThatCode(() -> decoder.decode(token(REALM, "selfin"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> decoder.decode(token(REALM, "account"))).isInstanceOf(JwtValidationException.class);
        assertThatThrownBy(() -> decoder.decode(token("http://other.test/realms/selfin", "selfin")))
                .isInstanceOf(JwtValidationException.class);
        assertThatThrownBy(() -> decoder.decode(keycloak.token(REALM, "selfin", Instant.now().minusSeconds(600))))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    @DisplayName("вход через браузер: декодер один на регистрацию — ключи запрашиваются один раз")
    void oneDecoderPerRegistration() {
        JwtDecoderFactory<ClientRegistration> factory = new SecurityConfig().idTokenDecoders();
        JwtDecoder decoder = factory.createDecoder(registration());
        assertThat(factory.createDecoder(registration())).isSameAs(decoder);
        decoder.decode(token(REALM, "selfin"));
        decoder.decode(token(REALM, "selfin"));
        assertThat(keycloak.hits).hasValue(1);
    }

    @Test
    @DisplayName("Keycloak молчит дольше запаса — отказ, а не вечное ожидание")
    void silentKeycloakRefused() {
        keycloak.delay = SLOW;
        JwtDecoder decoder = SecurityConfig
                .idTokenDecodersWith(SecurityConfig.keysClient(Duration.ofMillis(200), Duration.ofMillis(300)))
                .createDecoder(registration());
        assertThatThrownBy(() -> decoder.decode(token(REALM, "selfin")))
                .isInstanceOf(JwtException.class).hasRootCauseInstanceOf(SocketTimeoutException.class);
    }

    private static String token(String issuer, String audience) {
        return keycloak.token(issuer, audience, Instant.now().plusSeconds(300));
    }

    private static ClientRegistration registration() {
        return ClientRegistration.withRegistrationId("keycloak")
                .clientId("selfin")
                .clientSecret("secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope("openid")
                .authorizationUri(REALM + "/protocol/openid-connect/auth")
                .tokenUri(REALM + "/protocol/openid-connect/token")
                .jwkSetUri(keycloak.certs())
                .issuerUri(REALM)
                .userNameAttributeName("sub")
                .build();
    }
}
