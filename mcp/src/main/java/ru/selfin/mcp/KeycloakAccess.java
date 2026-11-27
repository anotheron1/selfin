package ru.selfin.mcp;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.BadJWTException;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.TypeRef;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class KeycloakAccess {
    static final String READ = "selfin-mcp:read";
    static final String WRITE = "selfin-mcp:write";
    private static final String EXCHANGER = "selfin-mcp";
    private static final String API_AUDIENCE = "selfin";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    private final String issuer;
    private final String resource;
    private final URI tokenEndpoint;
    private final String authorization;
    private final DefaultJWTProcessor<SecurityContext> jwt = new DefaultJWTProcessor<>();

    KeycloakAccess(String issuer, URI internalIssuer, String resource, String secret) throws IOException {
        this.issuer = issuer;
        this.resource = resource;
        tokenEndpoint = URI.create(internalIssuer + "/protocol/openid-connect/token");
        authorization = "Basic " + Base64.getEncoder().encodeToString(
                (encode(EXCHANGER) + ":" + encode(secret)).getBytes(StandardCharsets.UTF_8));
        var keys = URI.create(internalIssuer + "/protocol/openid-connect/certs").toURL();
        var jwks = JWKSourceBuilder.<SecurityContext>create(keys, new DefaultResourceRetriever(3000, 3000, 64 * 1024))
                .build();
        jwt.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, jwks));
    }

    Principal authenticate(String bearer) throws InvalidToken {
        try {
            var claims = verify(bearer);
            if (!claims.getAudience().containsAll(List.of(resource, EXCHANGER))) {
                throw new InvalidToken();
            }
            var client = claims.getStringClaim("azp");
            var login = claims.getStringClaim("sid");
            if (client == null || client.isBlank() || login == null || login.isBlank()) {
                throw new InvalidToken();
            }
            var scope = claims.getStringClaim("scope");
            var scopes = scope == null ? List.<String>of() : Arrays.asList(scope.split(" "));
            return new Principal(claims.getSubject(), client, login,
                    claims.getExpirationTime().toInstant(), Set.copyOf(scopes));
        } catch (BadJOSEException | JOSEException | ParseException e) {
            throw new InvalidToken();
        }
    }

    SelfinClient.AccessToken forApi(Principal principal, String bearer) {
        return new SelfinClient.AccessToken() {
            private String delegated;
            private Instant delegatedUntil = Instant.EPOCH;

            @Override
            public String get() throws IOException, InterruptedException {
                requireCurrent(principal);
                if (delegatedUntil.isAfter(Instant.now().plusSeconds(3))) {
                    return delegated;
                }
                // Exchange after confirmation too, if the previously delegated token expired.
                var response = HTTP.send(HttpRequest.newBuilder(tokenEndpoint)
                        .timeout(Duration.ofSeconds(8))
                        .header("Authorization", authorization)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Atoken-exchange"
                                        + "&subject_token_type=urn%3Aietf%3Aparams%3Aoauth%3Atoken-type%3Aaccess_token"
                                        + "&audience=" + API_AUDIENCE + "&subject_token=" + encode(bearer)))
                        .build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    throw new SelfinClient.ApiException(response.statusCode());
                }
                var payload = McpJsonDefaults.getMapper().readValue(response.body(), new TypeRef<Map<String, Object>>() {});
                if (payload == null || !(payload.get("access_token") instanceof String token)) {
                    throw new IOException("Missing delegated access");
                }
                try {
                    var claims = verify(token);
                    if (!claims.getAudience().equals(List.of(API_AUDIENCE))
                            || !principal.subject().equals(claims.getSubject())) {
                        throw new IOException("Unexpected delegated access");
                    }
                    requireCurrent(principal);
                    delegated = token;
                    delegatedUntil = claims.getExpirationTime().toInstant();
                    return delegated;
                } catch (BadJOSEException | JOSEException | ParseException e) {
                    throw new IOException("Invalid delegated access");
                }
            }
        };
    }

    private JWTClaimsSet verify(String token) throws BadJOSEException, JOSEException, ParseException {
        JWTClaimsSet claims = jwt.process(token, null);
        if (!issuer.equals(claims.getIssuer()) || !"Bearer".equals(claims.getStringClaim("typ"))
                || claims.getSubject() == null || claims.getSubject().isBlank() || claims.getExpirationTime() == null
                || !claims.getExpirationTime().toInstant().isAfter(Instant.now())
                || (claims.getNotBeforeTime() != null && claims.getNotBeforeTime().toInstant().isAfter(Instant.now()))) {
            throw new BadJWTException("Invalid access claims");
        }
        return claims;
    }

    private static void requireCurrent(Principal principal) throws SelfinClient.ApiException {
        if (!principal.expiresAt().isAfter(Instant.now())) {
            throw new SelfinClient.ApiException(401);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    record Principal(String subject, String client, String loginSession, Instant expiresAt, Set<String> scopes) {}

    static final class InvalidToken extends Exception {}
}
