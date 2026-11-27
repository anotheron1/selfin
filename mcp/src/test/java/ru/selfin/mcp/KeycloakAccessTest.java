package ru.selfin.mcp;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KeycloakAccessTest {
    private static final String ISSUER = "https://selfin.example/auth/realms/selfin";
    private static final String RESOURCE = "https://selfin.example/mcp";

    @Test
    void onlyAcceptsCurrentKeycloakAccessForThisMcpResource() throws Exception {
        RSAKey key = new RSAKeyGenerator(2048).keyID("test-key").generate();
        byte[] keys = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/protocol/openid-connect/certs", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, keys.length);
                exchange.getResponseBody().write(keys);
            }
        });
        server.start();
        try {
            URI internal = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            var access = new KeycloakAccess(ISSUER, internal, RESOURCE, "test-only");
            var audiences = List.of(RESOURCE, "selfin-mcp");
            Instant future = Instant.now().plusSeconds(60);
            String good = token(key, ISSUER, audiences, future, "login");
            var principal = access.authenticate(good);
            assertEquals("test-owner", principal.subject());
            assertEquals(Set.of(KeycloakAccess.READ, KeycloakAccess.WRITE), principal.scopes());

            assertThrows(KeycloakAccess.InvalidToken.class, () ->
                    access.authenticate(token(key, ISSUER, List.of("selfin"), future, "login")));
            assertThrows(KeycloakAccess.InvalidToken.class, () ->
                    access.authenticate(token(key, ISSUER + "-other", audiences, future, "login")));
            assertThrows(KeycloakAccess.InvalidToken.class, () ->
                    access.authenticate(token(key, ISSUER, audiences, Instant.now().minusSeconds(1), "login")));
            assertThrows(KeycloakAccess.InvalidToken.class, () ->
                    access.authenticate(token(key, ISSUER, audiences, future, "")));
            int signature = good.lastIndexOf('.') + 1;
            String altered = good.substring(0, signature) + (good.charAt(signature) == 'A' ? 'B' : 'A')
                    + good.substring(signature + 1);
            assertThrows(KeycloakAccess.InvalidToken.class, () -> access.authenticate(altered));
        } finally {
            server.stop(0);
        }
    }

    private static String token(RSAKey key, String issuer, List<String> audiences, Instant expiry, String login)
            throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(issuer).subject("test-owner").audience(audiences)
                .expirationTime(Date.from(expiry)).claim("typ", "Bearer").claim("azp", "selfin-mcp-codex")
                .claim("sid", login).claim("scope", KeycloakAccess.READ + " " + KeycloakAccess.WRITE).build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
}
