package ru.selfin.mcp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.TypeRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class ExpensePreviewTest {
    private static final LocalDate TODAY = LocalDate.parse("2026-11-25");
    private static final LocalDate INCOME_DAY = LocalDate.parse("2026-12-04");
    private static final LocalDate LATER = LocalDate.parse("2026-12-10");
    private static final BigDecimal AMOUNT = new BigDecimal("1500.01");

    private HttpServer server;
    private ExpensePreview preview;
    private final List<SandboxCall> calls = new CopyOnWriteArrayList<>();
    private volatile boolean noIncome;
    private volatile boolean minimumBeforePurchase;
    private volatile boolean inconsistentResponse;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/pocket", exchange -> json(exchange,
                Map.of("trajectory", List.of(Map.of("date", TODAY)))));
        server.createContext("/api/v1/pocket/sandbox", this::sandbox);
        server.start();
        preview = new ExpensePreview(new SelfinClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "test"));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void comparesAnIncludedPurchaseInOneResponseWithExactAmountsAndServerDate() throws Exception {
        var answer = preview.preview(new ExpensePreview.Request(AMOUNT, "tomorrow", null, null));
        var period = field(answer, "requestedPeriod");
        assertEquals(Map.of("amount", "1500.01", "date", TODAY.plusDays(1)), answer.get("purchase"));
        assertEquals("included", period.get("status"));
        assertEquals(INCOME_DAY, field(period, "horizon").get("endDate"));
        assertEquals("4000.00", field(period, "baseline").get("available"));
        assertEquals("2499.99", field(period, "withPurchase").get("available"));
        assertEquals("-1500.01", period.get("difference"));
        assertEquals("6499.99", field(period, "purchaseDay").get("balanceWithPurchase"));
        assertFalse(answer.containsKey("extendedPeriod"));
        assertEquals(List.of(new SandboxCall("POST", "NEXT_INCOME", TODAY.plusDays(1), AMOUNT, null)), calls);
    }

    @Test
    void usesTheEndOfTheForecastInsteadOfTheLastDisplayedDayAndRecalculatesBothExtendedAmounts() throws Exception {
        var answer = preview.preview(new ExpensePreview.Request(AMOUNT, LATER.toString(), null, null));
        var initial = field(answer, "requestedPeriod");
        var extended = field(answer, "extendedPeriod");
        assertEquals("outside_horizon", initial.get("status"));
        assertEquals(INCOME_DAY, field(initial, "horizon").get("endDate"));
        assertEquals("4000.00", field(initial, "baseline").get("available"));
        assertFalse(initial.containsKey("withPurchase"));
        assertFalse(initial.containsKey("purchaseDay"));
        assertEquals("included", extended.get("status"));
        assertEquals(LATER, field(extended, "horizon").get("endDate"));
        assertEquals("3300.00", field(extended, "baseline").get("available"));
        assertEquals("1799.99", field(extended, "withPurchase").get("available"));
        assertEquals(List.of("NEXT_INCOME", "DATE:2026-12-10"), calls.stream().map(SandboxCall::scope).toList());
    }

    @Test
    void preservesEitherFormOfAnExplicitLimit() throws Exception {
        var untilIncome = preview.preview(new ExpensePreview.Request(AMOUNT, LATER.toString(), null, true));
        var end = INCOME_DAY.plusDays(1);
        var untilDate = preview.preview(new ExpensePreview.Request(AMOUNT, LATER.toString(), end, null));
        assertEquals("outside_horizon", field(untilIncome, "requestedPeriod").get("status"));
        assertEquals("outside_horizon", field(untilDate, "requestedPeriod").get("status"));
        assertFalse(untilIncome.containsKey("extendedPeriod"));
        assertFalse(untilDate.containsKey("extendedPeriod"));
        assertEquals(end, field(field(untilDate, "requestedPeriod"), "horizon").get("endDate"));
        assertEquals(List.of("NEXT_INCOME", "DATE:2026-12-05"), calls.stream().map(SandboxCall::scope).toList());
    }

    @Test
    void includesIncomeDayEvenIfTheMinimumDoesNotChangeAndExposesTheFallback() throws Exception {
        minimumBeforePurchase = true;
        var sameDay = preview.preview(new ExpensePreview.Request(AMOUNT, INCOME_DAY.toString(), null, null));
        var period = field(sameDay, "requestedPeriod");
        assertEquals("included", period.get("status"));
        assertEquals("0.00", period.get("difference"));
        assertEquals("3500.00", field(period, "purchaseDay").get("plannedIncome"));
        assertEquals("6499.99", field(period, "purchaseDay").get("balanceWithPurchase"));
        assertEquals(TODAY, field(field(period, "withPurchase"), "minimum").get("date"));

        noIncome = true;
        var fallback = preview.preview(new ExpensePreview.Request(AMOUNT, LATER.toString(), null, null));
        assertEquals(true, field(field(fallback, "requestedPeriod"), "horizon").get("fallback"));
        assertFalse(fallback.containsKey("extendedPeriod"));
    }

    @Test
    void rejectsInvalidDatesBeforeSimulationAndDifferentReturnedPeriods() {
        for (var invalid : List.of(
                new ExpensePreview.Request(AMOUNT, "today", null, null),
                new ExpensePreview.Request(AMOUNT, "2026-11-23", null, null),
                new ExpensePreview.Request(AMOUNT, "2026-11-31", null, null),
                new ExpensePreview.Request(AMOUNT, "2029-12-01", null, null),
                new ExpensePreview.Request(AMOUNT, "tomorrow", TODAY, null),
                new ExpensePreview.Request(BigDecimal.ZERO, "tomorrow", null, null))) {
            assertThrows(IllegalArgumentException.class, () -> preview.preview(invalid));
        }
        assertTrue(calls.isEmpty());
        inconsistentResponse = true;
        assertThrows(IOException.class, () -> preview.preview(new ExpensePreview.Request(AMOUNT, "tomorrow", null, null)));
    }

    private void sandbox(HttpExchange exchange) throws IOException {
        var input = McpJsonDefaults.getMapper().readValue(exchange.getRequestBody().readAllBytes(),
                new TypeRef<Map<String, Object>>() {});
        var tryOn = (List<?>) input.get("tryOn");
        var fields = (Map<?, ?>) tryOn.getFirst();
        if (tryOn.size() != 1 || fields.size() != 2 || !List.of().equals(input.get("exclude"))) {
            throw new IOException("A single ad-hoc purchase and empty exclude are required");
        }
        var amount = new BigDecimal(fields.get("amount").toString());
        var date = LocalDate.parse(fields.get("date").toString());
        var scope = (String) input.get("scope");
        calls.add(new SandboxCall(exchange.getRequestMethod(), scope, date, amount,
                exchange.getRequestHeaders().getFirst("Idempotency-Key")));
        boolean fallback = noIncome && scope.equals("NEXT_INCOME");
        var end = scope.startsWith("DATE:") ? LocalDate.parse(scope.substring(5))
                : fallback ? TODAY.plusDays(30) : INCOME_DAY;
        var baseline = new BigDecimal(scope.startsWith("DATE:") ? "3300.00" : "4000.00");
        var fitted = date.isAfter(end) || minimumBeforePurchase ? baseline : baseline.subtract(amount);
        var balance = new BigDecimal("8000.00");
        json(exchange, Map.of(
                "baseline", forecast(end, fallback, baseline, date, balance),
                "fitted", forecast(inconsistentResponse ? end.plusDays(1) : end, fallback, fitted, date, balance.subtract(amount))));
    }

    private static Map<String, Object> forecast(LocalDate end, boolean fallback, BigDecimal available,
                                                 LocalDate date, BigDecimal dayBalance) {
        return Map.of("pocket", available, "checkpointDate", TODAY.minusDays(1), "planHasExpectations", true,
                "horizon", Map.of("endDate", end, "label", "До " + end, "fallback", fallback),
                "minPoint", Map.of("date", TODAY, "balance", available.add(new BigDecimal("100.00"))),
                "trajectory", List.of(Map.of("date", TODAY, "balance", "8000.00", "income", "0.00"),
                        Map.of("date", date, "balance", dayBalance,
                                "income", date.equals(INCOME_DAY) ? new BigDecimal("3500.00") : BigDecimal.ZERO)));
    }

    private static void json(HttpExchange exchange, Object value) throws IOException {
        byte[] data = McpJsonDefaults.getMapper().writeValueAsBytes(value);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, data.length);
        try (var output = exchange.getResponseBody()) {
            output.write(data);
        }
    }

    private static Map<?, ?> field(Map<?, ?> object, String name) {
        return (Map<?, ?>) object.get(name);
    }

    private record SandboxCall(String method, String scope, LocalDate date, BigDecimal amount, String attemptKey) {}
}
