package ru.selfin.mcp;

import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SelfinMcpTest {

    private static final String TOKEN = "synthetic-credential";
    private static final String POCKET = """
            {
              "pocket": -1234.56,
              "checkpointDate": null,
              "horizon": {"type":"NEXT_INCOME","endDate":"2026-12-24","label":"До 24 декабря","fallback":true},
              "planHasExpectations": false,
              "breakdown": [{"type":"PLANNED_EXPENSES","label":"Плановые расходы","amount":-5000.01,
                             "details":["Синтетическая личная заметка"]},
                            {"type":"POCKET","label":"Свободно","amount":-1234.56},
                            {"type":"UNPLANNED_FORECAST","label":"С обычными тратами","amount":-777.77}],
              "trajectory": [{"date":"2026-12-01"}]
            }
            """;

    private final AtomicReference<String> upstreamRequest = new AtomicReference<>();
    private HttpServer http;
    private URI base;
    private volatile int status = 200;
    private volatile String body = POCKET;

    @BeforeEach
    void start() throws IOException {
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/api/v1/pocket", exchange -> {
            upstreamRequest.set(exchange.getRequestMethod() + " " + exchange.getRequestURI()
                    + " " + exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        http.start();
        base = URI.create("http://127.0.0.1:" + http.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        http.stop(0);
    }

    @Test
    void returnsEngineAmountHorizonAndAggregateBreakdownWithoutJournalDetails() {
        var result = call(Map.of());

        assertEquals(Boolean.FALSE, result.isError());
        assertEquals("GET /api/v1/pocket Bearer " + TOKEN, upstreamRequest.get());
        assertEquals(
                Map.of(
                        "available", "-1234.56",
                        "currency", "RUB",
                        "horizon", Map.of("endDate", "2026-12-24", "label", "До 24 декабря", "fallback", true),
                        "planHasExpectations", false,
                        "breakdown", List.of(Map.of("type", "PLANNED_EXPENSES", "label", "Плановые расходы", "amount", "-5000.01"))
                ),
                result.structuredContent()
        );
        String text = ((McpSchema.TextContent) result.content().getFirst()).text();
        assertFalse(text.contains("Синтетическая личная заметка"));
        assertFalse(text.contains("trajectory"));
        assertFalse(text.contains(TOKEN));
    }

    @Test
    void forwardsActualCheckpointDateAndPreservesZero() {
        body = POCKET.replace("\"checkpointDate\": null", "\"checkpointDate\": \"2026-11-24\"")
                .replace("\"pocket\": -1234.56", "\"pocket\": 0");

        var result = call(Map.of());

        assertEquals(Boolean.FALSE, result.isError());
        var data = (Map<?, ?>) result.structuredContent();
        assertEquals("0", data.get("available"));
        assertEquals("2026-11-24", data.get("checkpointDate"));
    }

    @Test
    void rejectsUnexpectedArgumentsBeforeCallingSelfin() {
        var result = call(Map.of("ownerId", "anything"));

        assertEquals(Boolean.TRUE, result.isError());
        assertNull(upstreamRequest.get());
    }

    @Test
    void hidesUpstreamBodyOnUnauthorizedResponse() {
        status = 401;
        body = "{\"debug\":\"" + TOKEN + "\"}";

        var result = call(Map.of());

        assertEquals(Boolean.TRUE, result.isError());
        assertNull(result.structuredContent());
        String disclosure = ((McpSchema.TextContent) result.content().getFirst()).text();
        assertFalse(disclosure.contains(TOKEN));
        assertTrue(disclosure.contains("доступ"));
    }

    @Test
    void treatsMissingHorizonAsErrorInsteadOfInventingPeriod() {
        body = "{\"pocket\": 500}";

        assertEquals(Boolean.TRUE, call(Map.of()).isError());
    }

    @Test
    void rejectsSendingBearerOverPlainHttpOutsideLoopback() {
        assertThrows(IllegalArgumentException.class,
                () -> new SelfinClient(URI.create("http://example.test"), TOKEN));
    }

    private McpSchema.CallToolResult call(Map<String, Object> arguments) {
        return SelfinMcp.getAvailableMoney(new SelfinClient(base, TOKEN), arguments);
    }
}
