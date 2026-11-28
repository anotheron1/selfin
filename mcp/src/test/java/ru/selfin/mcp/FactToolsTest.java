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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class FactToolsTest {
    private static final LocalDate TODAY = LocalDate.parse("2026-11-25");
    private static final LocalDate PLAN_DATE = LocalDate.parse("2026-11-26");
    private static final UUID CAFE = UUID.randomUUID();
    private static final UUID RENT = UUID.randomUUID();
    private static final UUID EXPECTATION = UUID.randomUUID();
    private static final UUID NOVEMBER_RENT = UUID.randomUUID();
    private static final UUID DECEMBER_RENT = UUID.randomUUID();

    private HttpServer server;
    private FactTools tools;
    private final List<Attempt> attempts = new CopyOnWriteArrayList<>();
    private final List<SelfinClient.PlanEvent> plans = new CopyOnWriteArrayList<>();
    private final Map<UUID, SelfinClient.FactEvent> committed = new ConcurrentHashMap<>();
    private volatile SelfinClient.FactPreview preview;
    private volatile UUID actualParent;
    private volatile boolean loseResponse;
    private volatile boolean denyReplay;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/pocket", e -> json(e, 200, Map.of("trajectory", List.of(Map.of("date", TODAY)))));
        server.createContext("/api/v1/categories", e -> json(e, 200, List.of(
                new SelfinClient.Category(CAFE, "Кафе", "EXPENSE", "MEDIUM", null),
                new SelfinClient.Category(RENT, "Жильё", "EXPENSE", "HIGH", null))));
        server.createContext("/api/v1/events/fact-preview", e -> {
            if (preview == null) {
                e.sendResponseHeaders(204, -1);
                e.close();
            } else {
                json(e, 200, preview);
            }
        });
        server.createContext("/api/v1/events", this::events);
        server.start();
        tools = new FactTools(new SelfinClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "test"));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void standaloneDeclineKeepsJournalAndSeparatePurchaseGetsNewAttempt() throws Exception {
        var prepared = tools.prepare(lunch(false));
        var declined = tools.save(draftId(prepared), line -> {
            assertEquals(prepared.get("line"), line);
            assertEquals("Обед · расход · 420.00 ₽ · 2026-11-24 · Кафе · ожидание · самостоятельная трата", line);
            assertTrue(attempts.isEmpty());
            return false;
        });
        assertEquals("declined", declined.get("status"));
        assertEquals(declined, tools.save(draftId(prepared), line -> fail("Declined draft reopened")));
        assertTrue(committed.isEmpty());
        var saved = tools.save(draftId(tools.prepare(lunch(false))), line -> true);
        assertEquals("saved", saved.get("status"));
        assertNull(saved.get("parentEventId"));
        var conflict = tools.prepare(lunch(false));
        assertEquals("clarify", conflict.get("status"));
        assertTrue(conflict.toString().contains(saved.get("eventId").toString()));
        assertEquals("saved", tools.save(draftId(tools.prepare(lunch(true))), line -> true).get("status"));
        assertEquals("/api/v1/events/facts", attempts.getFirst().path());
        assertNotEquals(attempts.getFirst().key(), attempts.getLast().key());
        assertEquals(2, committed.size());
    }

    @Test
    void detectsChangedExpectationBeforeWritingAndReportsActualRelationIfItChangesAfterCheck() throws Exception {
        showExpectation();
        var first = tools.prepare(lunch(false));
        assertTrue(first.get("line").toString().contains("по ожиданию «Обеды», плановая дата 2026-11-30"));
        assertEquals("stale", tools.save(draftId(first), line -> {
            preview = null;
            return true;
        }).get("status"));
        assertTrue(attempts.isEmpty());

        showExpectation();
        actualParent = UUID.randomUUID();
        var recorded = tools.save(draftId(tools.prepare(lunch(false))), line -> true);
        assertEquals("saved", recorded.get("status"));
        assertEquals(actualParent, recorded.get("parentEventId"));
        assertTrue(recorded.get("message").toString().contains("Связь с ожиданием изменилась"));
    }

    @Test
    void repeatsSameCommittedStandaloneFactAfterLostResponseAndDeniedRetry() throws Exception {
        showExpectation();
        actualParent = EXPECTATION;
        loseResponse = true;
        UUID draft = draftId(tools.prepare(lunch(false)));
        assertEquals("unknown", tools.save(draft, line -> true).get("status"));
        assertEquals(1, committed.size());
        denyReplay = true;
        assertEquals("unknown", tools.save(draft, line -> fail("Approval was already given")).get("status"));
        denyReplay = false;
        preview = null;
        var receipt = tools.save(draft, line -> fail("Approval was already given"));
        assertEquals("saved", receipt.get("status"));
        assertEquals(EXPECTATION, receipt.get("parentEventId"));
        assertEquals("Обеды", receipt.get("parentName"));
        assertEquals(1, committed.size());
        assertEquals(3, attempts.size());
        assertTrue(attempts.stream().allMatch(attempts.getFirst()::equals));
        assertEquals(receipt, tools.save(draft, line -> fail("Confirmed draft reopened")));
        assertEquals(3, attempts.size());
    }

    @Test
    void targetsTheSelectedOccurrenceAndKeepsTheStandardPartialPaymentBody() throws Exception {
        plans.add(plan(NOVEMBER_RENT, PLAN_DATE));
        plans.add(plan(DECEMBER_RENT, PLAN_DATE.plusMonths(1)));
        var request = payment(NOVEMBER_RENT, PLAN_DATE);
        var prepared = tools.prepare(request);
        assertTrue(prepared.get("line").toString().contains(
                "2500.00 ₽ · 2026-11-25 · Жильё · бронь · по плану «Аренда», плановая дата 2026-11-26"));
        actualParent = NOVEMBER_RENT;
        var receipt = tools.save(draftId(prepared), line -> true);
        assertEquals("saved", receipt.get("status"));
        assertEquals(NOVEMBER_RENT, receipt.get("parentEventId"));
        assertEquals("/api/v1/events/" + NOVEMBER_RENT + "/facts", attempts.getFirst().path());
        assertEquals(Map.of("date", TODAY.toString(), "factAmount", new BigDecimal("2500.00"),
                "priority", "HIGH", "description", "Оплата аренды"), attempts.getFirst().body());

        actualParent = DECEMBER_RENT;
        var wrongParent = tools.save(draftId(tools.prepare(payment(DECEMBER_RENT, PLAN_DATE.plusMonths(1), true))),
                line -> {
                    actualParent = NOVEMBER_RENT;
                    return true;
                });
        assertEquals("unknown", wrongParent.get("status"));
    }

    @Test
    void validatesActualDateAndDoesNotAcceptAnIdForADifferentOccurrence() {
        plans.add(plan(NOVEMBER_RENT, PLAN_DATE));
        assertThrows(IllegalArgumentException.class, () -> tools.prepare(payment(NOVEMBER_RENT, PLAN_DATE.plusMonths(1))));
        assertThrows(IllegalArgumentException.class, () -> tools.prepare(payment(NOVEMBER_RENT, null)));
        assertThrows(IllegalArgumentException.class, () -> tools.prepare(new FactTools.Request("Обед",
                BigDecimal.TEN, TODAY.plusDays(1).toString(), CAFE, null, null, null, false)));
        assertTrue(attempts.isEmpty());
    }

    private void showExpectation() {
        preview = new SelfinClient.FactPreview(EXPECTATION, "Обеды", LocalDate.parse("2026-11-30"),
                new BigDecimal("3000"), BigDecimal.ZERO);
    }

    private void events(HttpExchange exchange) throws IOException {
        if ("GET".equals(exchange.getRequestMethod())) {
            var rows = new ArrayList<>(plans);
            committed.values().forEach(fact -> rows.add(new SelfinClient.PlanEvent(fact.id(), fact.date(), fact.categoryId(),
                    "Кафе", fact.type(), null, fact.factAmount(), "EXECUTED", fact.priority(), fact.description(),
                    "FACT", null, null, null, null, null, null, null, 0, null, false)));
            var query = exchange.getRequestURI().getQuery().split("&");
            var date = LocalDate.parse(query[0].substring("startDate=".length()));
            rows.removeIf(row -> !row.date().equals(date));
            json(exchange, 200, rows);
            return;
        }
        var path = exchange.getRequestURI().getPath();
        boolean linked = !path.equals("/api/v1/events/facts");
        UUID key = UUID.fromString(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
        var body = McpJsonDefaults.getMapper().readValue(exchange.getRequestBody().readAllBytes(),
                new TypeRef<Map<String, Object>>() {});
        // The generic JSON mapper materializes decimals as floating point. Normalize only the money field.
        body.put("factAmount", new BigDecimal(body.get("factAmount").toString()).setScale(2));
        attempts.add(new Attempt(path, key, body));
        if (denyReplay) {
            json(exchange, 403, Map.of());
            return;
        }
        var saved = committed.computeIfAbsent(key, ignored -> new SelfinClient.FactEvent(
                UUID.randomUUID(), LocalDate.parse((String) body.get("date")),
                linked ? RENT : UUID.fromString((String) body.get("categoryId")),
                "EXPENSE", (BigDecimal) body.get("factAmount"),
                (String) body.get("priority"), (String) body.get("description"), "FACT", false, actualParent, null));
        if (loseResponse) {
            loseResponse = false;
            json(exchange, 503, Map.of());
            return;
        }
        json(exchange, linked ? 200 : 201, saved);
    }

    private static void json(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] body = McpJsonDefaults.getMapper().writeValueAsBytes(value);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private static FactTools.Request lunch(boolean separate) {
        return new FactTools.Request("Обед", new BigDecimal("420"), "yesterday", CAFE, null, null, null, separate);
    }

    private static FactTools.Request payment(UUID planId, LocalDate date) {
        return payment(planId, date, false);
    }

    private static FactTools.Request payment(UUID planId, LocalDate date, boolean separate) {
        return new FactTools.Request("Оплата аренды", new BigDecimal("2500"), "today", null, null, planId, date, separate);
    }

    private static SelfinClient.PlanEvent plan(UUID id, LocalDate date) {
        return new SelfinClient.PlanEvent(id, date, RENT, "Жильё", "EXPENSE", new BigDecimal("10000"),
                null, "PLANNED", "HIGH", "Аренда", "PLAN", UUID.randomUUID(), null,
                "MONTHLY", 26, null, PLAN_DATE, null, 0, BigDecimal.ZERO, false);
    }

    private static UUID draftId(Map<String, Object> prepared) {
        assertEquals("prepared", prepared.get("status"));
        return (UUID) prepared.get("draftId");
    }

    private record Attempt(String path, UUID key, Map<String, Object> body) {}
}
