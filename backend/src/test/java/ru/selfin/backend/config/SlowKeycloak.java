package ru.selfin.backend.config;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Keycloak для тестов ключей (ANO-244): отдаёт ключи реалма по тому же пути, что настоящий, — с задержкой
 * {@link #delay} — и считает запросы; токены подписывает тем же ключом. Задержка — чтобы проверить, сколько декодер
 * ждёт ключи: библиотека по умолчанию ждёт полсекунды.
 */
final class SlowKeycloak implements AutoCloseable {

    static final String CERTS = "/realms/selfin/protocol/openid-connect/certs";

    final AtomicInteger hits = new AtomicInteger();
    volatile Duration delay = Duration.ZERO;

    private final RSAKey key;
    private final HttpServer server;

    private SlowKeycloak(RSAKey key, HttpServer server) {
        this.key = key;
        this.server = server;
    }

    static SlowKeycloak start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            SlowKeycloak keycloak = new SlowKeycloak(new RSAKeyGenerator(2048).keyID("selfin-test").generate(), server);
            server.createContext(CERTS, exchange -> {
                keycloak.hits.incrementAndGet();
                try {
                    Thread.sleep(keycloak.delay.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                byte[] body = new JWKSet(keycloak.key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                // Декодер, не дождавшись, уже закрыл соединение — запись в него и есть то, что проверяется.
                try {
                    exchange.sendResponseHeaders(200, body.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                } catch (IOException closedByClient) {
                    exchange.close();
                }
            });
            // Ждущий ответ не держит остальные: каждый запрос — своим потоком.
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            return keycloak;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    String certs() {
        return url() + CERTS;
    }

    /** Токен, подписанный ключом реалма: издатель, аудитория, срок; выпущен за пять минут до конца срока. */
    String token(String issuer, String audience, Instant expires) {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject("person")
                .audience(audience)
                .issueTime(Date.from(expires.minusSeconds(300)))
                .expirationTime(Date.from(expires))
                .build();
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
