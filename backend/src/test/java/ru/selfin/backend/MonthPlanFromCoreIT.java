package ru.selfin.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Р4 (ANO-206): в плане месяца «Журнала» и «Аналитики» — только то, что в плане у ядра.
 * Обсуждаемая и отложенная хотелки живут на «Хотелках», у сконвертированной деньги несёт
 * созданный план; зафиксированная — обычная строка. Нижняя строка таблицы за 3, 6, 12 месяцев —
 * «Доходы минус расходы», без переводов в копилки.
 *
 * <p>Каждый тест — свой экран: юниты сервисов мокают репозиторий и правила хотелок не видят.
 *
 * <p>Спека: {@code docs/superpowers/specs/2026-10-04-month-plan-from-core-design.md}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class MonthPlanFromCoreIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;

    private static final String WISHLIST_CATEGORY = "Хотелки";

    /** Конверсии нужен срок в будущем — месяц хотелок следующий. */
    private final LocalDate day = LocalDate.now().plusMonths(1).withDayOfMonth(10);
    private final YearMonth month = YearMonth.from(day);
    private final LocalDate today = LocalDate.now();

    @AfterEach
    void cleanDb() {
        jdbc.update("DELETE FROM fund_transactions");
        jdbc.update("DELETE FROM financial_events");
        jdbc.update("DELETE FROM target_funds");
    }

    /**
     * Обычный план 10 000, хотелки: обсуждается 50 000, отложена 30 000, зафиксирована 7 000,
     * сконвертирована в план 20 000. В плане ядра — 10 000 + 7 000 + 20 000 = 37 000.
     */
    private record Seeded(String regular, String open, String dismissed, String fixed, String twin, String artifact) {}

    private Seeded seedWishlistMonth() throws Exception {
        String regular = plan(day, "EXPENSE", 10_000);
        String open = wishlist("Обсуждается", 50_000);
        String dismissed = wishlist("Отложена", 30_000);
        setStatus(dismissed, "DISMISSED");
        String fixed = wishlist("Зафиксирована", 7_000);
        setStatus(fixed, "FIXED");
        String twin = wishlist("Сконвертирована", 20_000);
        String artifact = convertToPlan(twin);
        return new Seeded(regular, open, dismissed, fixed, twin, artifact);
    }

    @Test
    @DisplayName("Р4: «Журнал» — без обсуждаемой, отложенной и двойника; зафиксированная и план из конверсии — строками")
    void journal_showsOnlyWhatCoreCounts() throws Exception {
        Seeded s = seedWishlistMonth();

        JsonNode events = json("/api/v1/events?startDate=" + month.atDay(1) + "&endDate=" + month.atEndOfMonth());
        Set<String> ids = new HashSet<>();
        BigDecimal plannedExpense = BigDecimal.ZERO;
        for (JsonNode e : events) {
            ids.add(e.get("id").asText());
            if ("PLAN".equals(e.get("eventKind").asText()) && "EXPENSE".equals(e.get("type").asText())) {
                plannedExpense = plannedExpense.add(e.get("plannedAmount").decimalValue());
            }
        }

        assertThat(ids).as("в плане ядра").contains(s.regular(), s.fixed(), s.artifact());
        assertThat(ids).as("обсуждаемая и отложенная — на «Хотелках», двойник — ANO-106")
                .doesNotContain(s.open(), s.dismissed(), s.twin());
        assertThat(plannedExpense).as("«Расходы … план» журнала").isEqualByComparingTo("37000");
    }

    @Test
    @DisplayName("Р4: «По категориям за месяц» — план без хотелок вне расчёта")
    void categoryBars_planWithoutWishlistOutsidePlan() throws Exception {
        seedWishlistMonth();

        JsonNode bars = json("/api/v1/analytics/dashboard?date=" + day).get("progressBars");
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal wishlist = null;
        for (JsonNode b : bars) {
            total = total.add(b.get("plannedLimit").decimalValue());
            if (WISHLIST_CATEGORY.equals(b.get("categoryName").asText())) wishlist = b.get("plannedLimit").decimalValue();
        }

        assertThat(total).isEqualByComparingTo("37000");
        assertThat(wishlist).as("зафиксированная 7 000 и план из конверсии 20 000").isEqualByComparingTo("27000");
    }

    @Test
    @DisplayName("Р4: «Как прошёл месяц» и «Структура месяца» — план без хотелок вне расчёта")
    void monthReport_planWithoutWishlistOutsidePlan() throws Exception {
        seedWishlistMonth();

        JsonNode report = json("/api/v1/analytics/report?date=" + day);
        JsonNode planFact = report.get("planFact");
        BigDecimal wishlist = null;
        for (JsonNode c : planFact.get("categories")) {
            if (WISHLIST_CATEGORY.equals(c.get("categoryName").asText())) wishlist = c.get("planned").decimalValue();
        }
        JsonNode structure = report.get("priorityBreakdown");
        BigDecimal structureTotal = structure.get("highPlanned").decimalValue()
                .add(structure.get("mediumPlanned").decimalValue())
                .add(structure.get("lowPlanned").decimalValue());

        assertThat(planFact.get("totalPlannedExpense").decimalValue()).isEqualByComparingTo("37000");
        assertThat(wishlist).isEqualByComparingTo("27000");
        assertThat(structureTotal).as("«Структура месяца» — тот же план").isEqualByComparingTo("37000");
    }

    @Test
    @DisplayName("Р4: таблица за месяцы — расходы и «Хотелки» без хотелок вне расчёта")
    void multiMonth_expensesWithoutWishlistOutsidePlan() throws Exception {
        seedWishlistMonth();

        JsonNode rows = multiMonth(month).get("rows");

        assertThat(planned(row(rows, "TOTAL_EXPENSE", "Расходы"))).isEqualByComparingTo("37000");
        assertThat(planned(row(rows, "CATEGORY", WISHLIST_CATEGORY))).isEqualByComparingTo("27000");
    }

    @Test
    @DisplayName("Р4: «Доходы минус расходы» — без переводов в копилки, в плане и в факте; итоги план-факт — тоже")
    void netRow_incomeMinusExpense_withoutTransfers() throws Exception {
        String income = plan(today, "INCOME", 100_000);
        linkedFact(income, 100_000);
        plan(today, "EXPENSE", 10_000);
        String transfer = transferPlan(fund("Копилка Р4"), 5_000);
        linkedFact(transfer, 5_000);

        JsonNode rows = multiMonth(YearMonth.from(today)).get("rows");
        JsonNode net = rows.get(rows.size() - 1);
        JsonNode value = net.get("values").get(0);

        assertThat(net.get("label").asText()).as("«Баланс» в банке — остаток на счёте").isEqualTo("Доходы минус расходы");
        assertThat(value.get("planned").decimalValue()).as("100 000 − 10 000; перевод не расход")
                .isEqualByComparingTo("90000");
        assertThat(value.get("actual").decimalValue()).as("факт: 100 000 − 0; перевод не расход")
                .isEqualByComparingTo("100000");
        assertThat(planned(row(rows, "TOTAL_FUND_TRANSFER", "Переводы в копилки"))).as("переводы — своей строкой")
                .isEqualByComparingTo("5000");

        JsonNode planFact = json("/api/v1/analytics/report?date=" + today).get("planFact");
        assertThat(planFact.get("totalPlannedExpense").decimalValue()).as("итог «Как прошёл месяц» сходится с журналом")
                .isEqualByComparingTo("10000");
        assertThat(planFact.get("totalFactExpense").decimalValue()).isEqualByComparingTo("0");
    }

    // ── API, как им пользуется фронт ────────────────────────────────────────

    private JsonNode json(String url) throws Exception {
        return objectMapper.readTree(mockMvc.perform(get(url))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode multiMonth(YearMonth ym) throws Exception {
        return json("/api/v1/analytics/multi-month?startDate=" + ym.atDay(1) + "&endDate=" + ym.atEndOfMonth());
    }

    private static JsonNode row(JsonNode rows, String type, String label) {
        for (JsonNode r : rows) {
            if (type.equals(r.get("type").asText()) && label.equals(r.get("label").asText())) return r;
        }
        throw new AssertionError("нет строки " + type + " «" + label + "»");
    }

    /** Плановая сумма единственного месяца строки. */
    private static BigDecimal planned(JsonNode row) {
        return row.get("values").get(0).get("planned").decimalValue();
    }

    private String category(String type) throws Exception {
        for (JsonNode c : json("/api/v1/categories")) {
            boolean system = c.path("isSystem").asBoolean(false) || c.path("system").asBoolean(false);
            if (type.equals(c.get("type").asText()) && !system
                    && !WISHLIST_CATEGORY.equals(c.get("name").asText())) return c.get("id").asText();
        }
        throw new AssertionError("нет категории " + type);
    }

    private String plan(LocalDate date, String type, long amount) throws Exception {
        return create("/api/v1/events", Map.of(
                "date", date.toString(), "categoryId", category(type), "type", type,
                "plannedAmount", amount, "priority", "MEDIUM", "description", "План Р4 " + type));
    }

    private String transferPlan(String fundId, long amount) throws Exception {
        return create("/api/v1/events", Map.of(
                "date", today.toString(), "type", "FUND_TRANSFER", "plannedAmount", amount,
                "priority", "MEDIUM", "targetFundId", fundId));
    }

    private void linkedFact(String planId, long amount) throws Exception {
        create("/api/v1/events/" + planId + "/facts", Map.of("date", today.toString(), "factAmount", amount));
    }

    private String create(String url, Map<String, Object> body) throws Exception {
        String json = mockMvc.perform(post(url)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json).get("id").asText();
    }

    private String wishlist(String description, long amount) throws Exception {
        String json = mockMvc.perform(post("/api/v1/events/wishlist")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "description", description, "plannedAmount", amount, "date", day.toString()))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json).get("id").asText();
    }

    private void setStatus(String id, String status) throws Exception {
        mockMvc.perform(patch("/api/v1/events/{id}/wishlist-status", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", status))))
                .andExpect(status().is2xxSuccessful());
    }

    /** Конверсия хотелки в план; возвращает id плана. */
    private String convertToPlan(String id) throws Exception {
        String json = mockMvc.perform(post("/api/v1/wishlist/items/{id}/convert", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("sourceKind", "WISHLIST", "target", "PLAN_EVENT"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json).get("convertedTo").get("id").asText();
    }

    private String fund(String name) {
        String id = jdbc.queryForObject("SELECT gen_random_uuid()::text", String.class);
        jdbc.update("""
                INSERT INTO target_funds
                    (id, name, target_amount, current_balance, status, purchase_type, is_deleted, created_at)
                VALUES (?::uuid, ?, 1000000, 0, 'FUNDING', 'SAVINGS', false, now())
                """, id, name);
        return id;
    }
}
