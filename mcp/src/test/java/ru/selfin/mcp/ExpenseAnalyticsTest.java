package ru.selfin.mcp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.json.McpJsonDefaults;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class ExpenseAnalyticsTest {
    private static final LocalDate TODAY = LocalDate.parse("2026-11-25");
    private static final LocalDate OCTOBER = LocalDate.parse("2026-10-01");
    private static final LocalDate NOVEMBER = LocalDate.parse("2026-11-01");
    private static final UUID CAFE = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();

    private HttpServer server;
    private ExpenseAnalytics analytics;
    private volatile boolean denyJournal;
    private final List<SelfinClient.PlanEvent> journal = new CopyOnWriteArrayList<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/pocket", e -> json(e, 200, Map.of("trajectory", List.of(Map.of("date", TODAY)))));
        server.createContext("/api/v1/events", this::events);
        server.createContext("/api/v1/analytics/report", e -> {
            requests.add(e.getRequestMethod() + " " + e.getRequestURI());
            json(e, 200, Map.of("planFact", Map.of(
                    "categories", List.of(
                            Map.of("categoryName", "Кафе", "type", "EXPENSE", "planned", "2000.00", "fact", "2400.00"),
                            Map.of("categoryName", "Зарплата", "type", "INCOME", "planned", "3000.00", "fact", "2000.00"),
                            Map.of("categoryName", "Копилка", "type", "FUND_TRANSFER", "planned", "600.00", "fact", "700.00")),
                    "totalPlannedIncome", "3000.00", "totalFactIncome", "2000.00",
                    "totalPlannedExpense", "2000.00", "totalFactExpense", "2400.00")));
        });
        server.start();
        analytics = new ExpenseAnalytics(new SelfinClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "test"));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void totalsIncludeEveryMatchingExpenseEvenWhenEntriesAreLimited() throws Exception {
        for (int number = 0; number < 205; number++) {
            journal.add(row(NOVEMBER.plusDays(1), CAFE, "EXPENSE", "FACT", "Обед " + number, null, "0.17", null, false));
        }
        var legacy = row(NOVEMBER.plusDays(2), CAFE, "EXPENSE", "PLAN", "Обед раньше", "10.00", "1.25", null, false);
        journal.addAll(List.of(legacy, legacy,
                row(NOVEMBER.plusDays(3), CAFE, "EXPENSE", "PLAN", "План на обеды", "3000", null, "3.40", false),
                row(NOVEMBER.plusDays(3), CAFE, "EXPENSE", "FACT", "Обед по ожиданию", null, "3.40", null, false),
                row(TODAY, CAFE, "EXPENSE", "FACT", "Обед в кредит", null, "2.30", null, true),
                row(TODAY, OTHER, "EXPENSE", "FACT", "Ужин", null, "8.00", null, false),
                row(TODAY, OTHER, "INCOME", "FACT", "Обед возврат", null, "1000", null, false),
                row(TODAY, OTHER, "FUND_TRANSFER", "FACT", "Обед в копилку", null, "999", null, false)));
        var answer = analytics.search(new ExpenseAnalytics.Query(NOVEMBER, NOVEMBER.plusMonths(1).minusDays(1),
                null, "обед", true));
        assertEquals(TODAY, answer.get("to"));
        assertEquals(208, answer.get("count"));
        assertEquals("41.80", answer.get("recordedExpense"));
        assertEquals(false, answer.get("completeCalendarMonths"));
        var categories = (List<?>) answer.get("categories");
        assertEquals(Map.of("categoryId", CAFE, "categoryName", "Кафе", "recordedExpense", "41.80", "count", 208),
                categories.getFirst());
        var entries = (List<?>) answer.get("entries");
        assertEquals(200, entries.size());
        assertEquals(true, answer.get("entriesTruncated"));
        assertEquals(true, ((Map<?, ?>) entries.getFirst()).get("onCredit"));
        assertEquals("Обед 204", ((Map<?, ?>) entries.get(3)).get("name"));
        assertEquals(List.of("GET /api/v1/events?startDate=2026-11-01&endDate=2026-11-25"), requests);
    }

    @Test
    void searchesWordsAcrossCategoryAndNameAndLabelsIncompleteMonths() throws Exception {
        journal.addAll(List.of(
                row(OCTOBER.plusDays(12), CAFE, "EXPENSE", "FACT", "Ёжик", null, "12.50", null, false),
                row(NOVEMBER.plusDays(12), CAFE, "EXPENSE", "FACT", "ЕЖИК", null, "13.75", null, false),
                row(NOVEMBER.plusDays(13), OTHER, "EXPENSE", "FACT", "Ежик Кафе", null, "100", null, false),
                row(TODAY.plusDays(1), CAFE, "EXPENSE", "FACT", "Ежик", null, "800", null, false)));
        var answer = analytics.search(new ExpenseAnalytics.Query(OCTOBER, TODAY.plusDays(1), CAFE, "ёж КАФ", false));
        assertEquals("26.25", answer.get("recordedExpense"));
        assertFalse(answer.containsKey("entries"));
        var months = (List<?>) answer.get("months");
        assertEquals(2, months.size());
        assertEquals(true, ((Map<?, ?>) months.getFirst()).get("completeCalendarMonth"));
        assertEquals(false, ((Map<?, ?>) months.getLast()).get("completeCalendarMonth"));
        assertEquals(TODAY, ((Map<?, ?>) months.getLast()).get("to"));
    }

    @Test
    void usesOnlyTheRequestedDirectionsOfSelfinsMonthlyPlanFactReport() throws Exception {
        var expenses = analytics.planFact(new ExpenseAnalytics.PlanFactQuery(YearMonth.from(OCTOBER), "EXPENSE"));
        assertEquals(List.of("GET /api/v1/analytics/report?date=2026-10-01"), requests);
        assertEquals(Map.of("planned", "2000.00", "recorded", "2400.00", "difference", "400.00"),
                expenses.get("expenses"));
        assertEquals(true, expenses.get("completeCalendarMonth"));
        assertFalse(expenses.containsKey("income"));
        assertEquals(1, ((List<?>) expenses.get("categories")).size());

        var both = analytics.planFact(new ExpenseAnalytics.PlanFactQuery(YearMonth.from(NOVEMBER), null));
        assertEquals(NOVEMBER, both.get("from"));
        assertEquals(TODAY, both.get("today"));
        assertEquals(NOVEMBER.plusMonths(1).minusDays(1), both.get("planThrough"));
        assertEquals(false, both.get("completeCalendarMonth"));
        assertEquals(Map.of("planned", "3000.00", "recorded", "2000.00", "difference", "-1000.00"), both.get("income"));
        assertEquals(2, ((List<?>) both.get("categories")).size());
    }

    @Test
    void distinguishesNoMatchingRecordsFromAnAccessFailure() throws Exception {
        var query = new ExpenseAnalytics.Query(null, null, null, "нет", true);
        var empty = analytics.search(query);
        assertEquals(NOVEMBER, empty.get("from"));
        assertEquals(TODAY, empty.get("to"));
        assertEquals(0, empty.get("count"));
        assertEquals(List.of(), empty.get("entries"));
        assertEquals(false, empty.get("entriesTruncated"));
        denyJournal = true;
        var denied = assertThrows(SelfinClient.ApiException.class, () -> analytics.search(query));
        assertTrue(denied.accessDenied());
        assertThrows(IllegalArgumentException.class,
                () -> analytics.search(new ExpenseAnalytics.Query(NOVEMBER, null, null, null, false)));
    }

    private void events(HttpExchange exchange) throws IOException {
        requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
        if (denyJournal) {
            json(exchange, 403, Map.of());
            return;
        }
        var parameters = exchange.getRequestURI().getQuery().split("&");
        var start = LocalDate.parse(parameters[0].substring("startDate=".length()));
        var end = LocalDate.parse(parameters[1].substring("endDate=".length()));
        json(exchange, 200, journal.stream().filter(e -> !e.date().isBefore(start) && !e.date().isAfter(end))
                .sorted(Comparator.comparing(SelfinClient.PlanEvent::date)).toList());
    }

    private static SelfinClient.PlanEvent row(LocalDate date, UUID category, String type, String kind, String title,
                                             String planned, String fact, String linked, boolean credit) {
        return new SelfinClient.PlanEvent(UUID.randomUUID(), date, category, CAFE.equals(category) ? "Кафе" : "Разное", type,
                planned == null ? null : new BigDecimal(planned), fact == null ? null : new BigDecimal(fact),
                fact == null ? "PLANNED" : "EXECUTED", "MEDIUM", title, kind, null, null, null,
                null, null, null, null, linked == null ? 0 : 1, linked == null ? null : new BigDecimal(linked), credit);
    }

    private static void json(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] data = McpJsonDefaults.getMapper().writeValueAsBytes(value);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, data.length);
        try (var output = exchange.getResponseBody()) {
            output.write(data);
        }
    }
}
