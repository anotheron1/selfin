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
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PlanToolsTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-11-25");
    private static final LocalDate DECEMBER_12 = LocalDate.parse("2026-12-12");
    private static final UUID EXPENSE = UUID.randomUUID();
    private static final UUID AUTO = UUID.randomUUID();
    private static final UUID INCOME = UUID.randomUUID();
    private static final UUID TRANSFER = UUID.randomUUID();
    private static final UUID SOURCE = UUID.randomUUID();

    private HttpServer server;
    private volatile LocalDate fixtureToday = TODAY;
    private volatile java.util.concurrent.CountDownLatch firstWriteStarted;
    private volatile java.util.concurrent.CountDownLatch releaseFirstWrite;
    private volatile int writeToInterrupt;
    private PlanTools tools;
    private volatile String defaultCharacter = "HIGH";
    private volatile boolean loseIncomeResponse;
    private volatile boolean loseExpenseReply;
    private volatile boolean categoriesUnavailable;
    private volatile boolean returnWrongRepeatDay;
    private final List<UUID> posted = new CopyOnWriteArrayList<>();
    private final List<String> readPeriods = new CopyOnWriteArrayList<>();
    private final List<SelfinClient.PlanEvent> existing = new CopyOnWriteArrayList<>();
    private final List<Map<String, Object>> indefiniteRules = new CopyOnWriteArrayList<>();
    private final Map<UUID, SelfinClient.PlanEvent> saved = new ConcurrentHashMap<>();
    private final SelfinClient.PlanEvent historicalFact = new SelfinClient.PlanEvent(SOURCE,
            LocalDate.parse("2026-11-12"), EXPENSE, "Связь", "EXPENSE", null, new BigDecimal("1500"),
            "EXECUTED", "HIGH", "Интернет", "FACT", null, null, null, null, null, null, null, 0, null, false);

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/pocket", e -> json(e, 200,
                Map.of("trajectory", List.of(Map.of("date", fixtureToday)))));
        server.createContext("/api/v1/categories", e -> {
            if (categoriesUnavailable) {
                json(e, 503, Map.of());
                return;
            }
            json(e, 200, List.of(
                    new SelfinClient.Category(EXPENSE, "Связь", "EXPENSE", defaultCharacter, null),
                    new SelfinClient.Category(AUTO, "Машина", "EXPENSE", "MEDIUM", null),
                    new SelfinClient.Category(INCOME, "Работа", "INCOME", "MEDIUM", null),
                    new SelfinClient.Category(TRANSFER, "Накопление", "EXPENSE", "MEDIUM", "FUND_TRANSFER")));
        });
        server.createContext("/api/v1/events", this::events);
        server.createContext("/api/v1/events/indefinite-plan-rules", e -> json(e, 200, indefiniteRules));
        server.createContext("/api/v1/events/search", e -> {
            if (!e.getRequestURI().getQuery().contains("factsOnly=true")) {
                json(e, 400, Map.of());
                return;
            }
            json(e, 200, Map.of("rows", List.of(historicalFact), "total", 1));
        });
        server.start();
        tools = new PlanTools(new SelfinClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "test"));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void suggestsFromActualHistoryAndDeclineNeverWritesOrAsksAgain() throws Exception {
        var context = tools.context(new PlanTools.ContextQuery("Интернет", "EXPENSE", YearMonth.of(2026, 12), true));
        assertEquals(TODAY, context.get("today"));
        assertFalse(context.toString().contains("Накопление"));
        assertTrue(context.toString().contains(SOURCE.toString()));
        assertThrows(IllegalArgumentException.class,
                () -> tools.prepare(List.of(expense("1500", DECEMBER_12, false, UUID.randomUUID()))));

        var prepared = tools.prepare(List.of(expense("1500", DECEMBER_12, false, SOURCE)));
        UUID draft = draftId(prepared);
        var outcome = tools.save(draft, lines -> {
            assertEquals(prepared.get("lines"), lines);
            assertTrue(lines.getFirst().contains("факт «Интернет», 1500 ₽, 2026-11-12"));
            assertTrue(lines.getFirst().contains("1500.00 ₽ · 2026-12-12 · Связь · бронь"));
            assertTrue(posted.isEmpty());
            return false;
        });

        assertEquals("declined", outcome.get("status"));
        assertEquals(outcome, tools.save(draft, lines -> fail("Declined draft prompted again")));
        assertTrue(posted.isEmpty());
    }

    @Test
    void approvesTwoRowsOnceAndReplaysOnlyUncertainRowWithItsOriginalKey() throws Exception {
        loseIncomeResponse = true;
        var prepared = tools.prepare(List.of(expense("1500.01", DECEMBER_12, false, null), income()));
        UUID draft = draftId(prepared);
        var initial = tools.save(draft, lines -> {
            assertEquals(prepared.get("lines"), lines);
            assertEquals(2, lines.size());
            assertTrue(posted.isEmpty());
            return true;
        });

        assertEquals(List.of("saved", "unknown"), statuses(initial));
        assertEquals(2, saved.size());
        var recovered = tools.save(draft, lines -> fail("An approved draft prompted again"));

        assertEquals(List.of("saved", "saved"), statuses(recovered));
        assertEquals(2, saved.size());
        assertEquals(3, posted.size());
        assertNotEquals(posted.get(0), posted.get(1));
        assertEquals(posted.get(1), posted.get(2));
        assertEquals(recovered, tools.save(draft, lines -> fail("A completed draft prompted again")));
        assertEquals(3, posted.size());
    }

    @Test
    void highlightsExistingRecurringPlanAndAddsOnlyExplicitlySeparatePayment() throws Exception {
        var current = new SelfinClient.PlanEvent(UUID.randomUUID(), DECEMBER_12, EXPENSE, "Связь",
                "EXPENSE", new BigDecimal("1500"), null, "PLANNED", "HIGH", "Домашний интернет",
                "PLAN", UUID.randomUUID(), null, "MONTHLY", 12, null, DECEMBER_12, null, 0, null, false);
        existing.add(current);
        var found = tools.prepare(List.of(expense("1700", DECEMBER_12, false, null)));
        assertEquals("clarify", found.get("status"));
        assertNull(found.get("draftId"));
        assertTrue(found.toString().contains(current.id().toString()));
        assertTrue(posted.isEmpty());

        var separate = tools.prepare(List.of(expense("1700", DECEMBER_12, true, null)));
        assertEquals(List.of("saved"), statuses(tools.save(draftId(separate), lines -> true)));
        assertEquals(List.of(current), existing);
        assertEquals(1, saved.size());
    }

    @Test
    void asksBeforeAddingAOneOffBeyondAnExistingMonthlyRulesSavedOccurrences() throws Exception {
        UUID ruleId = UUID.randomUUID();
        indefiniteRules.add(Map.of(
                "id", ruleId, "categoryId", EXPENSE, "type", "EXPENSE", "priority", "HIGH",
                "description", "Домашняя связь", "plannedAmount", 1500, "frequency", "MONTHLY",
                "dayOfMonth", 12, "startDate", DECEMBER_12, "lastPlannedDate", DECEMBER_12.plusYears(3)));
        var farDate = DECEMBER_12.plusYears(4);
        var proposed = tools.prepare(List.of(expense("1500", farDate, false, null)));

        assertEquals("clarify", proposed.get("status"));
        assertTrue(proposed.toString().contains(ruleId.toString()));
        assertTrue(proposed.toString().contains("projected=true"));
        assertTrue(posted.isEmpty());

        var separate = tools.prepare(List.of(expense("1500", farDate, true, null)));
        assertEquals(List.of("saved"), statuses(tools.save(draftId(separate), lines -> true)));
        assertEquals(1, posted.size());
    }

    @Test
    void projectsYearlyShortMonthsOnlyBeyondTheLastSavedOccurrence() throws Exception {
        indefiniteRules.add(Map.ofEntries(
                Map.entry("id", UUID.randomUUID()), Map.entry("categoryId", EXPENSE),
                Map.entry("type", "EXPENSE"), Map.entry("priority", "HIGH"),
                Map.entry("description", "Годовой платёж"), Map.entry("plannedAmount", 1500),
                Map.entry("frequency", "YEARLY"), Map.entry("dayOfMonth", 29), Map.entry("monthOfYear", 2),
                Map.entry("startDate", "2028-02-29"), Map.entry("lastPlannedDate", "2032-02-29")));

        assertEquals("clarify", tools.prepare(List.of(
                expense("1500", LocalDate.parse("2033-02-28"), false, null))).get("status"));
        assertEquals("prepared", tools.prepare(List.of(
                expense("1500", LocalDate.parse("2031-02-28"), false, null))).get("status"));
        assertEquals("prepared", tools.prepare(List.of(
                expense("1500", LocalDate.parse("2033-03-28"), false, null))).get("status"));
        assertTrue(posted.isEmpty());
    }

    @Test
    void refusesConfirmedListWhenAProjectedPaymentAppearsDuringApproval() throws Exception {
        var farDate = DECEMBER_12.plusYears(4);
        UUID draft = draftId(tools.prepare(List.of(expense("1500", farDate, false, null))));
        var result = tools.save(draft, lines -> {
            indefiniteRules.add(Map.of(
                    "id", UUID.randomUUID(), "categoryId", EXPENSE, "type", "EXPENSE", "priority", "HIGH",
                    "description", "Интернет", "plannedAmount", 1500, "frequency", "MONTHLY",
                    "dayOfMonth", 12, "startDate", DECEMBER_12));
            return true;
        });

        assertEquals("stale", result.get("status"));
        assertTrue(posted.isEmpty());
    }

    @Test
    void refusesStaleConfirmedListWhenCategoryChangesDuringApproval() throws Exception {
        UUID draft = draftId(tools.prepare(List.of(expense("1500", DECEMBER_12, false, null))));
        var stale = tools.save(draft, lines -> {
            assertTrue(lines.getFirst().endsWith("бронь"));
            defaultCharacter = "MEDIUM";
            return true;
        });
        assertEquals("stale", stale.get("status"));
        assertTrue(posted.isEmpty());
    }

    @Test
    void remembersApprovalWhenFreshnessReadFailsBeforeTheFirstPost() throws Exception {
        UUID draft = draftId(tools.prepare(List.of(expense("1500", DECEMBER_12, false, null))));
        assertThrows(IOException.class, () -> tools.save(draft, lines -> {
            categoriesUnavailable = true;
            return true;
        }));
        assertTrue(posted.isEmpty());

        categoriesUnavailable = false;
        assertEquals(List.of("saved"), statuses(tools.save(draft, lines -> fail("Approval was already given"))));
        assertEquals(1, posted.size());
    }

    @Test
    void serializesFreshnessCheckAndFirstWriteOfOverlappingDrafts() throws Exception {
        var inputs = List.of(expense("1500", DECEMBER_12, false, null));
        UUID first = draftId(tools.prepare(inputs));
        UUID second = draftId(tools.prepare(inputs));
        var gate = new CyclicBarrier(2);
        PlanTools.Confirmation approval = lines -> {
            try {
                gate.await(5, TimeUnit.SECONDS);
                return true;
            } catch (Exception error) {
                throw new AssertionError(error);
            }
        };
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var one = executor.submit(() -> tools.save(first, approval));
            var two = executor.submit(() -> tools.save(second, approval));
            assertTrue(List.of(one.get(10, TimeUnit.SECONDS).get("status"),
                    two.get(10, TimeUnit.SECONDS).get("status")).containsAll(List.of("processed", "stale")));
        }
        assertEquals(1, posted.size());
    }

    @Test
    void validatesEntireListAndAllowsExplicitPlanForToday() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> tools.prepare(List.of(expense("1500", DECEMBER_12, false, null),
                        expense("0", DECEMBER_12, false, null))));
        assertThrows(IllegalArgumentException.class,
                () -> tools.prepare(List.of(expense("1550", DECEMBER_12, false, null),
                        expense("1550.00", DECEMBER_12, false, null))));
        for (var bad : List.of(expense("1.001", DECEMBER_12, false, null),
                expense("1", TODAY.minusDays(1), false, null),
                new PlanTools.RowInput("Интернет", "INCOME", BigDecimal.ONE, TODAY, EXPENSE, null, false, null, null),
                new PlanTools.RowInput("Копилка", "EXPENSE", BigDecimal.ONE, TODAY, TRANSFER, null, false, null, null),
                new PlanTools.RowInput("Интернет", "EXPENSE", BigDecimal.ONE, TODAY, UUID.randomUUID(), null, false, null, null))) {
            assertThrows(IllegalArgumentException.class, () -> tools.prepare(List.of(bad)));
        }
        assertTrue(posted.isEmpty());
        var today = tools.prepare(List.of(expense("0.01", TODAY, false, null)));
        assertEquals(List.of("saved"), statuses(tools.save(draftId(today), lines -> true)));
        assertEquals(TODAY, saved.values().iterator().next().date());
    }

    @Test
    void validatesFirstOccurrenceAndShowsTheExactShortMonthRule() throws Exception {
        LocalDate february = LocalDate.parse("2027-02-28");
        LocalDate march = LocalDate.parse("2027-03-31");
        for (var bad : List.of(monthly(february.minusDays(1), 31, march, false),
                monthly(february, 29, february.minusDays(1), false),
                monthly(february, 32, null, false), monthly(february, null, null, false),
                monthly(TODAY.plusMonths(36).plusDays(1), 26, null, false))) {
            assertThrows(IllegalArgumentException.class, () -> tools.prepare(List.of(bad)));
        }
        var prepared = tools.prepare(List.of(monthly(february, 31, march, false)));
        var declined = tools.save(draftId(prepared), lines -> {
            assertEquals(prepared.get("lines"), lines);
            assertTrue(lines.getFirst().contains("1500.00 ₽ за платёж · каждый месяц, 31-е число"
                    + " · первый платёж 2027-02-28 · до 2027-03-31 включительно"));
            return false;
        });
        assertEquals("declined", declined.get("status"));
        assertTrue(posted.isEmpty());
    }

    @Test
    void savesOneOffAndIndefiniteSeriesTogetherAndReplaysOnlyTheSeries() throws Exception {
        loseIncomeResponse = true;
        var monthlyIncome = new PlanTools.RowInput("Фриланс", "INCOME", new BigDecimal("20000"),
                DECEMBER_12, INCOME, "HIGH", false, null, new PlanTools.MonthlyRepeat(12, null));
        var prepared = tools.prepare(List.of(expense("1500", DECEMBER_12, false, null), monthlyIncome));
        var draft = draftId(prepared);
        var initial = tools.save(draft, lines -> {
            assertEquals(prepared.get("lines"), lines);
            assertTrue(lines.get(1).contains("каждый месяц, 12-е число · первый платёж 2026-12-12 · без конца"));
            assertTrue(posted.isEmpty());
            return true;
        });
        assertEquals(List.of("saved", "unknown"), statuses(initial));
        var recovered = tools.save(draft, lines -> fail("An approved draft prompted again"));
        assertEquals(List.of("saved", "saved"), statuses(recovered));
        assertEquals(2, saved.size());
        assertEquals(3, posted.size());
        assertEquals(posted.get(1), posted.get(2));
        var series = saved.get(posted.get(1));
        var receipts = (List<?>) recovered.get("rows");
        assertEquals(series.id(), ((Map<?, ?>) receipts.get(1)).get("eventId"));
        assertEquals(series.recurringRuleId(), ((Map<?, ?>) receipts.get(1)).get("recurringRuleId"));
        assertNotNull(series.recurringRuleId());
        assertEquals("MONTHLY", series.recurringFrequency());
        assertEquals(12, series.recurringDayOfMonth());
        assertNull(series.recurringEndDate());
        assertFalse(((Map<?, ?>) receipts.getFirst()).containsKey("recurringRuleId"));
    }

    @Test
    void detectsLaterConflictsAndDisplaysOnlyOneOccurrenceOfEachExistingSeries() throws Exception {
        LocalDate january = DECEMBER_12.plusMonths(1);
        LocalDate february = DECEMBER_12.plusMonths(2);
        var seriesId = UUID.randomUUID();
        var first = seriesEvent(january, january, february, seriesId);
        var second = seriesEvent(february, january, february, seriesId);
        var planned = monthly(DECEMBER_12, 12, february, false);
        var draft = draftId(tools.prepare(List.of(planned)));
        existing.add(second);
        existing.add(first);

        var conflict = tools.prepare(List.of(planned));
        assertEquals("clarify", conflict.get("status"));
        var entry = (Map<?, ?>) ((List<?>) conflict.get("conflicts")).getFirst();
        var displayed = (List<?>) entry.get("existing");
        assertEquals(1, displayed.size());
        assertEquals(first.id(), ((Map<?, ?>) displayed.getFirst()).get("id"));
        assertEquals(seriesId, ((Map<?, ?>) displayed.getFirst()).get("recurringRuleId"));
        assertEquals("stale", tools.save(draft, lines -> true).get("status"));
        assertTrue(posted.isEmpty());

        var separate = tools.prepare(List.of(monthly(DECEMBER_12, 12, february, true)));
        assertEquals(List.of("saved"), statuses(tools.save(draftId(separate), lines -> true)));
        assertEquals(february, saved.get(posted.getFirst()).recurringEndDate());
    }

    @Test
    void requiresMatchingRecurrenceInTheWriteResponse() throws Exception {
        returnWrongRepeatDay = true;
        var draft = draftId(tools.prepare(List.of(monthly(DECEMBER_12, 12, DECEMBER_12.plusMonths(3), false))));
        var response = tools.save(draft, lines -> true);
        assertEquals(List.of("unknown"), statuses(response));
        assertFalse(((Map<?, ?>) ((List<?>) response.get("rows")).getFirst()).containsKey("recurringRuleId"));
        returnWrongRepeatDay = false;
        assertEquals(List.of("saved"), statuses(tools.save(draft, lines -> fail("Approval already given"))));
        assertEquals(List.of(posted.getFirst(), posted.getFirst()), posted);
    }

    @Test
    void monthContextReadsPlanAndTwelveMonthsWithAnnualExampleAndExpensePattern() throws Exception {
        var annual = monthRow("2025-12-12", AUTO, "EXPENSE", "Страховка", "PLAN", "12000", "12345.67", 0, null);
        existing.add(annual);
        existing.add(annual);
        for (int month = 5; month <= 10; month++) {
            String date = LocalDate.of(2026, month, 11).toString();
            existing.add(monthRow(date, AUTO, "EXPENSE", "Обслуживание", "FACT", null, "30000", 0, null));
            existing.add(monthRow(date, TRANSFER, "FUND_TRANSFER", "В копилку", "FACT", null, "5000", 0, null));
        }
        var series = seriesEvent(DECEMBER_12, DECEMBER_12.minusMonths(1), null, UUID.randomUUID());
        var settled = monthRow("2026-12-20", AUTO, "EXPENSE", "Шины", "PLAN", "5000", null, 2, "5000");
        existing.addAll(List.of(series, settled, historicalFact));

        var context = tools.monthContext(new MonthPlanContext.Query(YearMonth.of(2026, 12), null, null, null, null));
        assertEquals(List.of("2026-12-01…2026-12-31", "2025-11-01…2026-10-31"), readPeriods);
        var plans = (List<?>) context.get("plan");
        assertEquals(series.recurringRuleId(), ((Map<?, ?>) plans.getFirst()).get("recurringRuleId"));
        assertEquals(false, ((Map<?, ?>) plans.getFirst()).get("settled"));
        assertEquals(true, ((Map<?, ?>) plans.getLast()).get("settled"));
        var history = (Map<?, ?>) context.get("history");
        var months = (List<?>) history.get("months");
        assertEquals(12, months.size());
        for (int index = 6; index < 12; index++) {
            var categories = (List<?>) ((Map<?, ?>) months.get(index)).get("categories");
            assertEquals(List.of(Map.of("categoryId", AUTO, "categoryName", "Машина",
                    "recordedExpense", "30000", "count", 1)), categories);
        }
        var annualMonth = (Map<?, ?>) months.get(1);
        assertEquals(List.of(Map.of("categoryId", AUTO, "categoryName", "Машина",
                "recordedExpense", "12345.67", "count", 1)), annualMonth.get("categories"));
        assertTrue(((List<?>) history.get("examples")).toString().contains("Страховка"));
        assertTrue(posted.isEmpty());

        var row = new PlanTools.RowInput("Страховка", "EXPENSE", new BigDecimal("12345.67"),
                DECEMBER_12, AUTO, null, false, annual.id(), null);
        var proposed = tools.prepare(List.of(row));
        assertTrue(proposed.toString().contains("факт «Страховка», 12345.67 ₽, 2025-12-12"));
        assertEquals("declined", tools.save(draftId(proposed), lines -> false).get("status"));
        assertTrue(posted.isEmpty());
    }

    @Test
    void focusedHistoryKeepsAllRecordedTotalsAndLabelsThePartialMonth() throws Exception {
        for (int number = 0; number < 205; number++) {
            existing.add(monthRow("2026-11-15", AUTO, "EXPENSE", "Машина " + number, "FACT", null, "0.17", 0, null));
        }
        existing.add(historicalFact);
        existing.add(monthRow("2026-11-16", AUTO, "EXPENSE", "План без факта", "PLAN", "5000", null, 1, "200"));
        var month = YearMonth.of(2026, 12);
        var historyFrom = LocalDate.parse("2026-11-01");
        var historyTo = LocalDate.parse("2026-11-30");
        var context = tools.monthContext(new MonthPlanContext.Query(month, "history", historyFrom, historyTo, AUTO));
        assertFalse(context.containsKey("plan"));
        assertEquals(List.of("2026-11-01…2026-11-25"), readPeriods);
        var history = (Map<?, ?>) context.get("history");
        var november = (Map<?, ?>) ((List<?>) history.get("months")).getFirst();
        assertEquals(false, november.get("completeCalendarMonth"));
        assertEquals(List.of(Map.of("categoryId", AUTO, "categoryName", "Машина",
                "recordedExpense", "34.85", "count", 205)), november.get("categories"));
        assertEquals(20, ((List<?>) history.get("examples")).size());
        assertEquals(true, history.get("examplesTruncated"));
        assertTrue(posted.isEmpty());

        readPeriods.clear();
        var plan = tools.monthContext(new MonthPlanContext.Query(month, "plan", null, null, null));
        assertFalse(plan.containsKey("history"));
        assertEquals(List.of("2026-12-01…2026-12-31"), readPeriods);
        assertThrows(IllegalArgumentException.class,
                () -> tools.monthContext(new MonthPlanContext.Query(month, "history", historyFrom, null, null)));
        assertThrows(IllegalArgumentException.class,
                () -> tools.monthContext(new MonthPlanContext.Query(month, "history", historyFrom.minusYears(2), historyTo, null)));
    }

    private void events(HttpExchange exchange) throws IOException {
        if ("GET".equals(exchange.getRequestMethod())) {
            var rows = new ArrayList<>(existing);
            rows.addAll(saved.values());
            var query = exchange.getRequestURI().getQuery().split("&");
            var start = LocalDate.parse(query[0].substring("startDate=".length()));
            var end = LocalDate.parse(query[1].substring("endDate=".length()));
            readPeriods.add(start + "…" + end);
            rows.removeIf(row -> row.date().isBefore(start) || row.date().isAfter(end));
            json(exchange, 200, rows);
            return;
        }
        UUID key = UUID.fromString(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
        var plan = McpJsonDefaults.getMapper().readValue(exchange.getRequestBody().readAllBytes(),
                SelfinClient.PlanInput.class);
        posted.add(key);
        var repeat = plan.recurring();
        var row = saved.computeIfAbsent(key, ignored -> new SelfinClient.PlanEvent(UUID.randomUUID(), plan.date(),
                plan.categoryId(), "EXPENSE".equals(plan.type()) ? "Связь" : "Работа", plan.type(),
                plan.plannedAmount(), null, "PLANNED", plan.priority(), plan.description(), "PLAN",
                repeat == null ? null : UUID.randomUUID(), null, repeat == null ? null : repeat.frequency(),
                repeat == null ? null : repeat.dayOfMonth(), null, repeat == null ? null : repeat.startDate(),
                repeat == null ? null : repeat.endDate(), 0, null, false));
        if (firstWriteStarted != null && posted.size() == writeToInterrupt) {
            firstWriteStarted.countDown();
            try {
                if (!releaseFirstWrite.await(10, TimeUnit.SECONDS)) {
                    throw new IOException("Test response was not released");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
        }
        if (loseIncomeResponse && "INCOME".equals(plan.type())) {
            loseIncomeResponse = false;
            json(exchange, 503, Map.of());
            return;
        }
        if (loseExpenseReply && "EXPENSE".equals(plan.type())) {
            json(exchange, 503, Map.of());
            return;
        }
        json(exchange, 200, returnWrongRepeatDay && repeat != null
                ? new SelfinClient.PlanEvent(row.id(), row.date(), row.categoryId(), row.categoryName(), row.type(),
                        row.plannedAmount(), null, row.status(), row.priority(), row.description(), "PLAN",
                        row.recurringRuleId(), null, "MONTHLY", repeat.dayOfMonth() + 1, null,
                        repeat.startDate(), repeat.endDate(), 0, null, false) : row);
    }

    private static void json(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] body = McpJsonDefaults.getMapper().writeValueAsString(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private static PlanTools.RowInput expense(String amount, LocalDate date, boolean separate, UUID source) {
        return new PlanTools.RowInput("Интернет", "EXPENSE", new BigDecimal(amount), date, EXPENSE, null, separate, source, null);
    }

    private static PlanTools.RowInput monthly(LocalDate date, Integer day, LocalDate end, boolean separate) {
        return new PlanTools.RowInput("Интернет", "EXPENSE", new BigDecimal("1500"), date, EXPENSE, null,
                separate, null, new PlanTools.MonthlyRepeat(day, end));
    }

    private static SelfinClient.PlanEvent seriesEvent(LocalDate date, LocalDate start, LocalDate end, UUID seriesId) {
        return new SelfinClient.PlanEvent(UUID.randomUUID(), date, EXPENSE, "Связь", "EXPENSE",
                new BigDecimal("1500"), null, "PLANNED", "HIGH", "Новый тариф", "PLAN", seriesId,
                null, "MONTHLY", 12, null, start, end, 0, null, false);
    }

    private static SelfinClient.PlanEvent monthRow(String date, UUID category, String type, String name,
                                                  String kind, String planned, String actual, int linked, String linkedAmount) {
        return new SelfinClient.PlanEvent(UUID.randomUUID(), LocalDate.parse(date), category, "Машина", type,
                planned == null ? null : new BigDecimal(planned), actual == null ? null : new BigDecimal(actual),
                "PLANNED", "MEDIUM", name, kind, null, null, null, null, null, null, null,
                linked, linkedAmount == null ? null : new BigDecimal(linkedAmount), false);
    }

    private static PlanTools.RowInput income() {
        return new PlanTools.RowInput("Фриланс", "INCOME", new BigDecimal("20000"), DECEMBER_12.plusDays(4),
                INCOME, "HIGH", false, null, null);
    }

    private static UUID draftId(Map<String, Object> prepared) {
        assertEquals("prepared", prepared.get("status"));
        return (UUID) prepared.get("draftId");
    }


    @Test
    void reviewSeriesAndMatchingOneOffInTheSameBatchRequireClarification() throws Exception {
        var january = DECEMBER_12.plusMonths(1);
        var duplicate = new PlanTools.RowInput("Подключение", "EXPENSE", new BigDecimal("1500"),
                january, EXPENSE, null, false, null, null);
        var repeat = monthly(DECEMBER_12, 12, january, false);
        var prepared = tools.prepare(List.of(repeat, duplicate));
        assertEquals("clarify", prepared.get("status"));
        assertNull(prepared.get("draftId"));
        assertEquals("clarify", tools.prepare(List.of(duplicate, repeat)).get("status"));
        var separate = new PlanTools.RowInput(duplicate.name(), duplicate.type(), duplicate.amount(),
                duplicate.date(), duplicate.categoryId(), duplicate.character(), true, null, null);
        assertEquals("prepared", tools.prepare(List.of(repeat, separate)).get("status"));
        assertEquals("prepared", tools.prepare(List.of(separate, repeat)).get("status"));
        var afterEnd = expense("1500", january.plusMonths(1), false, null);
        assertEquals("prepared", tools.prepare(List.of(repeat, afterEnd)).get("status"));
        assertTrue(posted.isEmpty());
    }

    @Test
    void overlappingSeriesUseTheLastDayOfShortMonths() throws Exception {
        var end = LocalDate.parse("2027-02-28");
        var first = monthly(LocalDate.parse("2026-12-30"), 30, end, false);
        var second = new PlanTools.RowInput("Второй тариф", "EXPENSE", new BigDecimal("1500"),
                LocalDate.parse("2026-12-31"), EXPENSE, null, false, null,
                new PlanTools.MonthlyRepeat(31, end));
        assertEquals("clarify", tools.prepare(List.of(first, second)).get("status"));
    }

    @Test
    void indefiniteSeriesOverlapsPaymentBeyondCurrentlyMaterializedOccurrences() throws Exception {
        var series = monthly(DECEMBER_12, 12, null, false);
        var farFuture = expense("1500", DECEMBER_12.plusYears(4), false, null);
        assertEquals("clarify", tools.prepare(List.of(series, farFuture)).get("status"));
        assertEquals("clarify", tools.prepare(List.of(farFuture, series)).get("status"));
    }

    @Test
    void indefiniteSeriesChecksSavedPaymentsBeyondCurrentMaterialization() throws Exception {
        existing.add(monthRow("2030-12-12", EXPENSE, "EXPENSE", "Будущий платёж", "PLAN",
                "1500", null, 0, null));
        assertEquals("clarify", tools.prepare(List.of(monthly(DECEMBER_12, 12, null, false))).get("status"));
        assertEquals("prepared", tools.prepare(List.of(monthly(DECEMBER_12, 12, null, true))).get("status"));
    }

    @Test
    void indefiniteSeriesIsStaleWhenAConflictingFarFuturePaymentAppearsDuringConfirmation() throws Exception {
        UUID draft = draftId(tools.prepare(List.of(monthly(DECEMBER_12, 12, null, false))));
        var result = tools.save(draft, lines -> {
            existing.add(monthRow("2030-12-12", EXPENSE, "EXPENSE", "Будущий платёж", "PLAN",
                    "1500", null, 0, null));
            return true;
        });
        assertEquals("stale", result.get("status"));
        assertTrue(posted.isEmpty());
    }

    @Test
    void reviewFirstMonthEarlierMatchingSinglePaymentRequiresClarification() throws Exception {
        existing.add(monthRow("2026-12-05", EXPENSE, "EXPENSE", "Интернет", "PLAN", "1700", null, 0, null));
        assertEquals("clarify", tools.prepare(List.of(monthly(DECEMBER_12, 12, null, false))).get("status"));
    }

    @Test
    void fixedSeriesChecksLaterDatesInItsLastPaymentMonth() throws Exception {
        var series = monthly(DECEMBER_12, 12, LocalDate.parse("2027-01-12"), false);
        UUID draft = draftId(tools.prepare(List.of(series)));
        var result = tools.save(draft, lines -> {
            existing.add(monthRow("2027-01-25", EXPENSE, "EXPENSE", "Интернет", "PLAN", "1700", null, 0, null));
            return true;
        });
        assertEquals("stale", result.get("status"));
        assertEquals("clarify", tools.prepare(List.of(series)).get("status"));
        assertTrue(posted.isEmpty());
    }

    @Test
    void fixedSeriesIgnoresMonthWhenEndPrecedesItsNextPayment() throws Exception {
        existing.add(monthRow("2027-01-02", EXPENSE, "EXPENSE", "Интернет", "PLAN", "1700", null, 0, null));
        var series = monthly(DECEMBER_12, 12, LocalDate.parse("2027-01-05"), false);
        assertEquals("prepared", tools.prepare(List.of(series)).get("status"));
    }

    @Test
    void categoryTextFindsContextWithoutCausingPaymentConflicts() throws Exception {
        var unrelated = monthRow("2026-12-13", AUTO, "EXPENSE", "Страховка", "PLAN", "500", null, 0, null);
        existing.add(unrelated);
        var context = tools.context(new PlanTools.ContextQuery("Машина", "EXPENSE", YearMonth.of(2026, 12), false));
        assertTrue(context.get("existing").toString().contains(unrelated.id().toString()));
        var payment = new PlanTools.RowInput("Машина", "EXPENSE", new BigDecimal("1500"),
                DECEMBER_12, AUTO, null, false, null, null);
        UUID draft = draftId(tools.prepare(List.of(payment)));
        var result = tools.save(draft, lines -> {
            existing.clear();
            return true;
        });
        assertEquals(List.of("saved"), statuses(result));
    }

    @Test
    void reviewInterruptedBatchResumesConfirmedTailUsingOriginalAttempt() throws Exception {
        UUID draft = interruptAfterFirstWrite(List.of(expense("1500", DECEMBER_12, false, null),
                expense("1500", DECEMBER_12, true, null)));
        var recovered = tools.save(draft, lines -> fail("An approved draft prompted again"));
        assertEquals(List.of("saved", "saved"), statuses(recovered));
        assertEquals(3, posted.size());
        assertEquals(posted.getFirst(), posted.get(1));
        assertEquals(2, saved.size());
    }

    @Test
    void uncertainWriteKeepsMatchingPendingRowRetryableAndSavesUnrelatedRow() throws Exception {
        loseExpenseReply = true;
        UUID draft = interruptWrite(List.of(expense("1500", DECEMBER_12, false, null),
                expense("1500", DECEMBER_12, true, null), income()),
                1, List.of("unknown", "not_sent", "not_sent"));
        var uncertain = tools.save(draft, lines -> fail("An approved draft prompted again"));
        assertEquals(List.of("unknown", "not_sent", "saved"), statuses(uncertain));
        assertEquals(2, saved.size());
        assertEquals(posted.getFirst(), posted.get(1));

        loseExpenseReply = false;
        var resolved = tools.save(draft, lines -> fail("An approved draft prompted again"));
        assertEquals(List.of("saved", "saved", "saved"), statuses(resolved));
        assertEquals(3, saved.size());
        assertEquals(posted.getFirst(), posted.get(3));
    }

    @Test
    void uncertainSeriesWriteKeepsItsProjectedConflictRetryable() throws Exception {
        var farDate = DECEMBER_12.plusYears(4);
        loseExpenseReply = true;
        UUID draft = interruptWrite(List.of(monthly(DECEMBER_12, 12, null, false),
                        expense("1500", farDate, true, null)),
                1, List.of("unknown", "not_sent"));
        var savedSeries = saved.values().iterator().next();
        indefiniteRules.add(Map.of(
                "id", savedSeries.recurringRuleId(), "categoryId", EXPENSE, "type", "EXPENSE",
                "priority", savedSeries.priority(), "description", savedSeries.description(),
                "plannedAmount", savedSeries.plannedAmount(), "frequency", "MONTHLY",
                "dayOfMonth", 12, "startDate", DECEMBER_12, "lastPlannedDate", DECEMBER_12));

        var uncertain = tools.save(draft, lines -> fail("An approved draft prompted again"));
        assertEquals(List.of("unknown", "not_sent"), statuses(uncertain));
        assertEquals(2, posted.size());

        loseExpenseReply = false;
        var resolved = tools.save(draft, lines -> fail("An approved draft prompted again"));
        assertEquals(List.of("saved", "saved"), statuses(resolved));
        assertEquals(4, posted.size());
        assertEquals(posted.getFirst(), posted.get(2));
    }

    @Test
    void uncertainWriteDoesNotHideAnotherChangedPayment() throws Exception {
        loseExpenseReply = true;
        UUID draft = interruptAfterFirstWrite(List.of(expense("1500", DECEMBER_12, false, null),
                expense("1500", DECEMBER_12, true, null)));
        existing.add(monthRow("2026-12-13", EXPENSE, "EXPENSE", "Интернет", "PLAN", "1700", null, 0, null));

        var result = tools.save(draft, lines -> fail("An approved draft prompted again"));
        assertEquals(List.of("unknown", "stale"), statuses(result));
        assertEquals(2, posted.size());
        assertEquals(posted.getFirst(), posted.get(1));
        assertEquals(1, saved.size());
    }

    @Test
    void retryDoesNotSendPendingRowThatNowMatchesAnotherPayment() throws Exception {
        UUID draft = interruptAfterFirstWrite(List.of(income(), expense("1500", DECEMBER_12, false, null)));
        existing.add(monthRow("2026-12-12", EXPENSE, "EXPENSE", "Интернет", "PLAN", "1500", null, 0, null));
        var recovered = tools.save(draft, lines -> fail("An approved draft prompted again"));
        assertEquals(List.of("saved", "stale"), statuses(recovered));
        assertEquals(2, posted.size());
        assertEquals(posted.getFirst(), posted.get(1));
        assertEquals(1, saved.size());
    }

    @Test
    void retryDoesNotIgnoreAnEditedPaymentFromTheSameDraft() throws Exception {
        var pending = new PlanTools.RowInput("Электричество", "EXPENSE", new BigDecimal("1500"),
                DECEMBER_12.plusDays(1), EXPENSE, null, false, null, null);
        UUID draft = interruptWrite(List.of(expense("1500", DECEMBER_12, false, null), income(), pending),
                2, List.of("saved", "unknown", "not_sent"));
        UUID firstKey = posted.getFirst();
        var original = saved.get(firstKey);
        saved.put(firstKey, new SelfinClient.PlanEvent(original.id(), pending.date(), original.categoryId(),
                original.categoryName(), original.type(), original.plannedAmount(), null, original.status(),
                original.priority(), pending.name(), "PLAN", null, null, null, null, null, null, null, 0, null, false));

        var recovered = tools.save(draft, lines -> fail("An approved draft prompted again"));
        assertEquals(List.of("saved", "saved", "stale"), statuses(recovered));
        assertEquals(3, posted.size());
        assertEquals(posted.get(1), posted.get(2));
        assertEquals(2, saved.size());
    }

    private UUID interruptAfterFirstWrite(List<PlanTools.RowInput> rows) throws Exception {
        return interruptWrite(rows, 1, List.of("unknown", "not_sent"));
    }

    private UUID interruptWrite(List<PlanTools.RowInput> rows, int writeNumber, List<String> expected) throws Exception {
        writeToInterrupt = writeNumber;
        firstWriteStarted = new java.util.concurrent.CountDownLatch(1);
        releaseFirstWrite = new java.util.concurrent.CountDownLatch(1);
        UUID draft = draftId(tools.prepare(rows));
        var outcome = new java.util.concurrent.FutureTask<>(() -> tools.save(draft, lines -> true));
        var worker = Thread.startVirtualThread(outcome);
        try {
            assertTrue(firstWriteStarted.await(7, TimeUnit.SECONDS));
            worker.interrupt();
            assertEquals(expected, statuses(outcome.get(7, TimeUnit.SECONDS)));
            assertEquals(writeNumber, posted.size());
        } finally {
            releaseFirstWrite.countDown();
        }
        return draft;
    }

    @Test
    void reviewPlanContextUsesTheSameBeginningOfTheWordAsJournalSearch() throws Exception {
        var plan = monthRow("2026-12-12", EXPENSE, "EXPENSE", "Оплата интернета", "PLAN", "1500", null, 0, null);
        existing.add(plan);
        var result = tools.context(new PlanTools.ContextQuery("Интернет", "EXPENSE", YearMonth.of(2026, 12), true));
        assertTrue(result.get("existing").toString().contains(plan.id().toString()));
    }

    @Test
    void reviewBlankPlanDescriptionFallsBackToCategoryName() {
        var plan = monthRow("2026-12-12", AUTO, "EXPENSE", "  ", "PLAN", "1500", null, 0, null);
        assertEquals("Машина", PlanTools.eventView(plan).get("name"));
    }

    @Test
    void reviewMonthPlanKeepsTheAmountAlreadyPaid() throws Exception {
        existing.add(monthRow("2026-12-12", AUTO, "EXPENSE", "ТО", "PLAN", "1500", null, 1, "500.25"));
        var context = tools.monthContext(new MonthPlanContext.Query(YearMonth.of(2026, 12), "plan", null, null, null));
        Map<?, ?> view = (Map<?, ?>) ((List<?>) context.get("plan")).getFirst();
        assertEquals(false, view.get("settled"));
        assertEquals("500.25", view.get("linkedFactsAmount"));
    }

    @Test
    void reviewTheCurrentMonthIsPartialEvenOnItsLastCalendarDay() throws Exception {
        fixtureToday = LocalDate.parse("2026-11-30");
        var context = tools.monthContext(new MonthPlanContext.Query(YearMonth.of(2026, 12), "history",
                fixtureToday.withDayOfMonth(1), fixtureToday, null));
        Map<?, ?> history = (Map<?, ?>) context.get("history");
        assertEquals(false, ((Map<?, ?>) ((List<?>) history.get("months")).getFirst()).get("completeCalendarMonth"));
    }

    private static List<?> statuses(Map<String, Object> response) {
        return ((List<?>) response.get("rows")).stream().map(row -> ((Map<?, ?>) row).get("status")).toList();
    }
}
