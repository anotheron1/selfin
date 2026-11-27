package ru.selfin.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.HttpHeaders;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class RemoteMcp implements Filter {
    private static final String ENDPOINT = "/mcp";
    private static final String METADATA = "/.well-known/oauth-protected-resource/mcp";
    private static final String ACCESS = RemoteMcp.class.getName();
    private static final int MAX_SESSIONS = 256;
    private static final Duration SESSION_LIFETIME = Duration.ofHours(2);
    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "[::1]");

    private final String origin;
    private final KeycloakAccess auth;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    private RemoteMcp(String origin, KeycloakAccess auth) {
        this.origin = origin;
        this.auth = auth;
    }

    static void run() throws Exception {
        var env = System.getenv();
        String origin = endpoint(required(env, "SELFIN_PUBLIC_URL"), null, false).toString();
        String issuer = endpoint(required(env, "SELFIN_MCP_ISSUER"), null, true).toString();
        URI internalIssuer = endpoint(required(env, "SELFIN_MCP_ISSUER_INTERNAL"), "keycloak", true);
        URI api = URI.create(required(env, "SELFIN_MCP_BASE_URL"));
        new SelfinClient(api, () -> { throw new IOException("No request"); }, true);

        var auth = new KeycloakAccess(issuer, internalIssuer, origin + ENDPOINT,
                required(env, "SELFIN_MCP_CLIENT_SECRET"));
        var filter = new RemoteMcp(origin, auth);
        var transport = HttpServletStreamableServerTransportProvider.builder()
                .mcpEndpoint(ENDPOINT)
                .contextExtractor(request -> McpTransportContext.create(Map.of(ACCESS, request.getAttribute(ACCESS))))
                .keepAliveInterval(Duration.ofSeconds(15))
                .sessionSweepInterval(Duration.ofMinutes(15))
                .maxRequestSize(1024 * 1024)
                .build();
        var server = SelfinMcp.configure(McpServer.sync(transport).requestTimeout(Duration.ofMinutes(2)),
                (exchange, writes) -> {
                    var request = (RequestAccess) exchange.transportContext().get(ACCESS);
                    if (writes && !request.session.writable) {
                        throw new IllegalArgumentException("Подключение Selfin разрешает только чтение.");
                    }
                    var selfin = new SelfinClient(api, auth.forApi(request.principal, request.bearer), true);
                    return new SelfinMcp.Tools(selfin, request.session.plans, request.session.facts);
                });

        var tomcat = new Tomcat();
        String base = Files.createTempDirectory("selfin-mcp").toString();
        tomcat.setBaseDir(base);
        tomcat.setPort(Integer.parseInt(env.getOrDefault("SELFIN_MCP_PORT", "8080")));
        tomcat.getConnector().setProperty("address", env.getOrDefault("SELFIN_MCP_BIND", "127.0.0.1"));
        var context = tomcat.addContext("", base);
        Tomcat.addServlet(context, "mcp", transport).setAsyncSupported(true);
        context.addServletMappingDecoded(ENDPOINT, "mcp");
        Tomcat.addServlet(context, "mcp-metadata", new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
                response.setContentType("application/json");
                response.getWriter().write(McpJsonDefaults.getMapper().writeValueAsString(Map.of(
                        "resource", origin + ENDPOINT,
                        "authorization_servers", List.of(issuer),
                        "scopes_supported", List.of(KeycloakAccess.READ, KeycloakAccess.WRITE),
                        "bearer_methods_supported", List.of("header"))));
            }
        });
        context.addServletMappingDecoded(METADATA, "mcp-metadata");
        var definition = new FilterDef();
        definition.setFilterName("mcp-auth");
        definition.setFilter(filter);
        definition.setAsyncSupported("true");
        context.addFilterDef(definition);
        var mapping = new FilterMap();
        mapping.setFilterName("mcp-auth");
        mapping.addURLPattern(ENDPOINT);
        mapping.setDispatcher(DispatcherType.REQUEST.name());
        context.addFilterMap(mapping);
        tomcat.start();
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "selfin-mcp-shutdown"));
        tomcat.getServer().await();
    }

    @Override
    public void doFilter(ServletRequest input, ServletResponse output, FilterChain chain)
            throws IOException, ServletException {
        var request = (HttpServletRequest) input;
        var response = (HttpServletResponse) output;
        response.setHeader("Cache-Control", "no-store");
        var origins = Collections.list(request.getHeaders("Origin"));
        if (!origins.isEmpty() && !origins.equals(List.of(origin))) {
            response.setStatus(403);
            return;
        }
        var headers = Collections.list(request.getHeaders("Authorization"));
        String credential = headers.size() == 1 ? headers.getFirst() : "";
        if (credential.length() <= 7 || !credential.regionMatches(true, 0, "Bearer ", 0, 7)) {
            challenge(response, 401, "");
            return;
        }
        String bearer = credential.substring(7);
        KeycloakAccess.Principal principal;
        try {
            principal = auth.authenticate(bearer);
        } catch (KeycloakAccess.InvalidToken e) {
            challenge(response, 401, ", error=\"invalid_token\"");
            return;
        }
        if (!principal.scopes().contains(KeycloakAccess.READ)) {
            challenge(response, 403, ", error=\"insufficient_scope\"");
            return;
        }
        var sessionIds = Collections.list(request.getHeaders(HttpHeaders.MCP_SESSION_ID));
        if (sessionIds.size() > 1) {
            response.setStatus(400);
            return;
        }
        if (sessionIds.isEmpty()) {
            initialize(request, response, chain, principal, bearer);
            return;
        }
        String id = sessionIds.getFirst();
        Session session = sessions.get(id);
        if (session == null || session.expired()) {
            response.setStatus(404);
            return;
        }
        if (!session.matches(principal)) {
            response.setStatus(404);
            return;
        }
        request.setAttribute(ACCESS, new RequestAccess(principal, bearer, session));
        chain.doFilter(request, response);
        if (response.getStatus() == 404 || ("DELETE".equals(request.getMethod()) && response.getStatus() < 300)) {
            sessions.remove(id, session);
        }
    }

    private void initialize(HttpServletRequest request, HttpServletResponse response, FilterChain chain,
                            KeycloakAccess.Principal principal, String bearer) throws ServletException, IOException {
        synchronized (sessions) {
            sessions.values().removeIf(Session::expired);
            if (sessions.size() >= MAX_SESSIONS) {
                response.setStatus(503);
                return;
            }
            var session = new Session(principal);
            request.setAttribute(ACCESS, new RequestAccess(principal, bearer, session));
            chain.doFilter(request, new HttpServletResponseWrapper(response) {
                @Override
                public void setHeader(String name, String value) {
                    if (HttpHeaders.MCP_SESSION_ID.equalsIgnoreCase(name)) {
                        sessions.put(value, session);
                    }
                    super.setHeader(name, value);
                }
            });
        }
    }

    private void challenge(HttpServletResponse response, int status, String error) {
        String scope = status == 401 ? KeycloakAccess.READ + " " + KeycloakAccess.WRITE : KeycloakAccess.READ;
        response.setHeader("WWW-Authenticate", "Bearer resource_metadata=\"" + origin + METADATA
                + "\", scope=\"" + scope + "\"" + error);
        response.setStatus(status);
    }

    static String required(Map<String, String> env, String key) {
        String value = env.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing " + key);
        }
        return value;
    }

    static URI endpoint(String value, String internalHost, boolean hasPath) {
        URI uri = URI.create(value);
        String host = uri.getHost();
        if (host == null || uri.getRawAuthority() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null
                || (!hasPath && !uri.getPath().isEmpty()) || value.endsWith("/")
                || !("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme())
                && (host.equals(internalHost) || LOOPBACK.contains(host))))) {
            throw new IllegalArgumentException("Invalid MCP endpoint configuration");
        }
        return uri;
    }

    private static final class Session {
        private final String subject;
        private final String client;
        private final String login;
        private final boolean writable;
        private final Instant expiresAt = Instant.now().plus(SESSION_LIFETIME);
        private final PlanTools.State plans = new PlanTools.State();
        private final FactTools.State facts = new FactTools.State();

        private Session(KeycloakAccess.Principal principal) {
            subject = principal.subject();
            client = principal.client();
            login = principal.loginSession();
            writable = principal.scopes().contains(KeycloakAccess.WRITE);
        }

        private boolean matches(KeycloakAccess.Principal principal) {
            return subject.equals(principal.subject()) && Objects.equals(client, principal.client())
                    && Objects.equals(login, principal.loginSession())
                    && (!writable || principal.scopes().contains(KeycloakAccess.WRITE));
        }

        private boolean expired() {
            return !expiresAt.isAfter(Instant.now());
        }
    }

    private static final class RequestAccess {
        private final KeycloakAccess.Principal principal;
        private final String bearer;
        private final Session session;

        private RequestAccess(KeycloakAccess.Principal principal, String bearer, Session session) {
            this.principal = principal;
            this.bearer = bearer;
            this.session = session;
        }
    }
}
