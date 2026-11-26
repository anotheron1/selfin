package ru.selfin.mcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.TypeRef;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static ru.selfin.mcp.RemoteMcp.required;

/** Configures the two MCP clients in an existing Selfin realm. */
final class ProvisionKeycloak {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private static final String REALM = "/admin/realms/selfin";
    private final String endpoint;
    private final String adminToken;

    private ProvisionKeycloak(String endpoint, String adminToken) {
        this.endpoint = endpoint;
        this.adminToken = adminToken;
    }

    static void run() throws IOException, InterruptedException {
        var env = System.getenv();
        String issuer = RemoteMcp.endpoint(required(env, "SELFIN_MCP_ISSUER_INTERNAL"), "keycloak", true).toString();
        if (!issuer.endsWith("/realms/selfin")) {
            throw new IllegalArgumentException("Use the internal Selfin realm URL");
        }
        String internal = issuer.substring(0, issuer.length() - "/realms/selfin".length());
        String exchangeSecret = required(env, "SELFIN_MCP_CLIENT_SECRET");
        URI redirect = URI.create(required(env, "SELFIN_MCP_OAUTH_REDIRECT_URI"));
        String host = redirect.getHost();
        if (host == null || redirect.getUserInfo() != null || redirect.getFragment() != null || redirect.toString().contains("*")
                || !("https".equals(redirect.getScheme()) || ("http".equals(redirect.getScheme())
                && Set.of("127.0.0.1", "[::1]", "localhost").contains(host)))) {
            throw new IllegalArgumentException("Use an exact HTTPS or loopback OAuth callback");
        }
        String client = required(env, "SELFIN_MCP_OAUTH_CLIENT_ID");
        if (!client.matches("selfin-mcp-[a-zA-Z0-9-]+")) {
            throw new IllegalArgumentException("Use a dedicated selfin-mcp-* OAuth client");
        }
        var admin = new ProvisionKeycloak(internal, login(internal, required(env, "KC_ADMIN_PASSWORD")));
        String read = admin.scope(KeycloakAccess.READ);
        String write = admin.scope(KeycloakAccess.WRITE);

        String exchangeId = admin.client("selfin-mcp", Map.of(
                "name", "Selfin MCP → API",
                "publicClient", false,
                "secret", exchangeSecret,
                "clientAuthenticatorType", "client-secret",
                "standardFlowEnabled", false,
                "attributes", Map.of("standard.token.exchange.enabled", "true", "access.token.lifespan", "60"),
                "defaultClientScopes", List.of("basic"),
                "optionalClientScopes", List.of()));
        admin.mapper(exchangeId, "selfin-api", "included.client.audience", "selfin");

        String clientId = admin.client(client, Map.of(
                "name", "Selfin MCP",
                "publicClient", true,
                "standardFlowEnabled", true,
                "consentRequired", true,
                "redirectUris", List.of(redirect.toString()),
                "attributes", Map.of("pkce.code.challenge.method", "S256"),
                "defaultClientScopes", List.of("basic"),
                "optionalClientScopes", List.of()));
        admin.mapper(clientId, "mcp-url", "included.custom.audience", required(env, "SELFIN_PUBLIC_URL") + "/mcp");
        admin.mapper(clientId, "mcp-exchange-client", "included.client.audience", "selfin-mcp");
        admin.request("PUT", "/clients/" + clientId + "/default-client-scopes/" + read, null, 204);
        admin.request("PUT", "/clients/" + clientId + "/optional-client-scopes/" + write, null, 204);
        System.out.println("Selfin MCP OAuth clients configured");
    }

    private String client(String name, Map<String, Object> settings) throws IOException, InterruptedException {
        var desired = new LinkedHashMap<>(settings);
        desired.putAll(Map.of("clientId", name, "protocol", "openid-connect", "enabled", true,
                "fullScopeAllowed", false, "implicitFlowEnabled", false, "directAccessGrantsEnabled", false,
                "serviceAccountsEnabled", false));
        var existing = list("/clients?clientId=" + encode(name)).stream()
                .filter(c -> name.equals(c.get("clientId"))).findFirst();
        if (existing.isPresent()) {
            var id = (String) existing.get().get("id");
            request("PUT", "/clients/" + id, desired, 204);
            return id;
        }
        request("POST", "/clients", desired, 201);
        return list("/clients?clientId=" + encode(name)).stream().filter(c -> name.equals(c.get("clientId")))
                .map(c -> (String) c.get("id")).findFirst().orElseThrow();
    }

    private String scope(String name) throws IOException, InterruptedException {
        var existing = list("/client-scopes").stream().filter(s -> name.equals(s.get("name"))).findFirst();
        if (existing.isPresent()) {
            return (String) existing.get().get("id");
        }
        request("POST", "/client-scopes", Map.of("name", name, "protocol", "openid-connect",
                "attributes", Map.of("include.in.token.scope", "true", "display.on.consent.screen", "true",
                        "consent.screen.text", name.equals(KeycloakAccess.READ)
                                ? "Чтение плана и журнала Selfin" : "Запись в Selfin с подтверждением")), 201);
        return list("/client-scopes").stream().filter(s -> name.equals(s.get("name")))
                .map(s -> (String) s.get("id")).findFirst().orElseThrow();
    }

    private void mapper(String client, String name, String property, String audience)
            throws IOException, InterruptedException {
        String path = "/clients/" + client + "/protocol-mappers/models";
        var desired = new LinkedHashMap<String, Object>(Map.of("name", name, "protocol", "openid-connect",
                "protocolMapper", "oidc-audience-mapper",
                "config", Map.of(property, audience, "access.token.claim", "true", "id.token.claim", "false")));
        var existing = list(path).stream().filter(m -> name.equals(m.get("name"))).findFirst();
        if (existing.isPresent()) {
            var id = (String) existing.get().get("id");
            desired.put("id", id);
            request("PUT", path + "/" + id, desired, 204);
        } else {
            request("POST", path, desired, 201);
        }
    }

    private List<Map<String, Object>> list(String path) throws IOException, InterruptedException {
        return McpJsonDefaults.getMapper().readValue(request("GET", path, null, 200), new TypeRef<>() {});
    }

    private String request(String method, String path, Map<String, Object> body, int status)
            throws IOException, InterruptedException {
        var response = HTTP.send(HttpRequest.newBuilder(URI.create(endpoint + REALM + path))
                .timeout(Duration.ofSeconds(8)).header("Authorization", "Bearer " + adminToken)
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(McpJsonDefaults.getMapper().writeValueAsBytes(body)))
                .build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != status) {
            throw new IOException("Keycloak MCP setup failed: " + method + " " + path + " HTTP " + response.statusCode());
        }
        return response.body();
    }

    private static String login(String endpoint, String password) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create(endpoint + "/realms/master/protocol/openid-connect/token"))
                .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=password&client_id=admin-cli&username=admin&password="
                        + encode(password))).build();
        Instant deadline = Instant.now().plusSeconds(240);
        do {
            try {
                var response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    Map<String, String> token = McpJsonDefaults.getMapper().readValue(response.body(), new TypeRef<>() {});
                    return token.get("access_token");
                }
                if (response.statusCode() == 400 || response.statusCode() == 401) {
                    break;
                }
            } catch (IOException e) {
                // Keycloak can be starting during the first Compose run.
            }
            Thread.sleep(Duration.ofSeconds(2));
        } while (Instant.now().isBefore(deadline));
        throw new IOException("Keycloak did not accept MCP setup login");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
