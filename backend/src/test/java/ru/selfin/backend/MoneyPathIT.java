package ru.selfin.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Срез 3, PR 1 «Путь денег»: «Свободно» через весь путь — ручки, сборщик входа и движок.
 * Спека — {@code docs/superpowers/specs/2026-11-18-slice3-journal-input-design.md}, пп. 1–5.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class MoneyPathIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;

    final LocalDate today = LocalDate.now();

    @BeforeEach
    void resetMoneyState() {
        jdbc.update("DELETE FROM fund_transactions");
        jdbc.update("DELETE FROM financial_events");
        jdbc.update("DELETE FROM recurring_rule");
        jdbc.update("DELETE FROM target_funds");
        jdbc.update("DELETE FROM balance_checkpoints");
    }

    // ── п. 4: погашение плана — по всем его фактам ───────────────────────────

    @Test
    @DisplayName("п. 4: факт до сверки гасит строку впереди — /pocket держит остаток")
    void factBeforeCheckpoint_settlesPlanAhead() throws Exception {
        String plan = createPlan(today.plusDays(5), "6000", "MEDIUM", expenseCategory(0));
        linkedFact(plan, today.minusDays(1), "1200").andExpect(status().isOk());
        anchor(today, "60000");

        assertThat(pocket())
                .as("сверка 60 000 уже без 1 200; держится остаток строки 4 800, а не 6 000")
                .isEqualByComparingTo("55200");
    }

    @Test
    @DisplayName("п. 4: факт в окне гасит строку один раз")
    void factInWindow_countsOnce() throws Exception {
        anchor(today.minusDays(1), "60000");
        String plan = createPlan(today.plusDays(5), "6000", "MEDIUM", expenseCategory(0));
        linkedFact(plan, today, "1200").andExpect(status().isOk());

        assertThat(pocket())
                .as("на счёте 58 800, держится 4 800")
                .isEqualByComparingTo("54000");
    }

    // ── п. 1: трата встаёт к ожиданию сама ───────────────────────────────────

    @Test
    @DisplayName("п. 1: запись в категории ожидания встаёт к строке недели — «Свободно» прежнее; «Отменить» возвращает строке сумму")
    void standaloneFact_bindsToWeekExpectation() throws Exception {
        anchor(today.minusDays(1), "60000");
        String category = expenseCategory(0);
        String plan = createPlan(today, "6000", "MEDIUM", category);
        BigDecimal before = pocket();

        JsonNode fact = json(standaloneFact(category, today, "1200", "MEDIUM").andExpect(status().isCreated()));

        assertThat(fact.get("parentEventId").asText()).isEqualTo(plan);
        assertThat(pocket()).as("трата была в плане").isEqualByComparingTo(before);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/events/{id}", fact.get("id").asText()))
                .andExpect(status().isNoContent());
        assertThat(pocket()).as("счёт вернул 1 200, строка снова держит 6 000").isEqualByComparingTo(before);
    }

    @Test
    @DisplayName("п. 1: бронь той же категории запись к себе не берёт — «Свободно» меньше на трату")
    void standaloneFact_doesNotBindToBooking() throws Exception {
        anchor(today.minusDays(1), "60000");
        String category = expenseCategory(0);
        createPlan(today, "6000", "HIGH", category);
        BigDecimal before = pocket();

        JsonNode fact = json(standaloneFact(category, today, "1200", "MEDIUM").andExpect(status().isCreated()));

        assertThat(fact.get("parentEventId").isNull()).isTrue();
        assertThat(before.subtract(pocket())).isEqualByComparingTo("1200");
    }

    @Test
    @DisplayName("п. 1: запись с характером «Бронь» к ожиданию не встаёт")
    void highFact_doesNotBindToExpectation() throws Exception {
        anchor(today.minusDays(1), "60000");
        String category = expenseCategory(0);
        createPlan(today, "6000", "MEDIUM", category);

        JsonNode fact = json(standaloneFact(category, today, "1200", "HIGH").andExpect(status().isCreated()));

        assertThat(fact.get("parentEventId").isNull()).isTrue();
    }

    // ── п. 2: дата, категория и вид у записи ─────────────────────────────────

    @Test
    @DisplayName("п. 2: новая категория без ожидания — запись выходит из плана, строка снова держит всё")
    void patchCategory_leavesExpectation() throws Exception {
        anchor(today.minusDays(1), "60000");
        String category = expenseCategory(0);
        createPlan(today, "6000", "MEDIUM", category);
        String fact = id(standaloneFact(category, today, "1200", "MEDIUM").andExpect(status().isCreated()));
        BigDecimal before = pocket();

        JsonNode patched = json(patchFact(fact, """
                {"factAmount": 1200, "categoryId": "%s"}""".formatted(expenseCategory(1))).andExpect(status().isOk()));

        assertThat(patched.get("parentEventId").isNull()).isTrue();
        assertThat(patched.get("categoryId").asText()).isEqualTo(expenseCategory(1));
        assertThat(before.subtract(pocket())).as("трата больше не в плане").isEqualByComparingTo("1200");
    }

    @Test
    @DisplayName("п. 2: дата позже сегодня — 400")
    void patchFutureDate_rejected() throws Exception {
        String fact = id(standaloneFact(expenseCategory(0), today, "1200", "MEDIUM").andExpect(status().isCreated()));
        patchFact(fact, "{\"factAmount\": 1200, \"date\": \"" + today.plusDays(1) + "\"}")
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("п. 2: запись к брони — смена категории 400, новая дата — бронь держится")
    void patchBookingFact() throws Exception {
        String category = expenseCategory(0);
        String booking = createPlan(today, "6000", "HIGH", category);
        String fact = id(linkedFact(booking, today, "1200").andExpect(status().isOk()));

        patchFact(fact, "{\"factAmount\": 1200, \"categoryId\": \"" + expenseCategory(1) + "\"}")
                .andExpect(status().isBadRequest());
        JsonNode patched = json(patchFact(fact, "{\"factAmount\": 1200, \"date\": \"" + today.minusDays(3) + "\"}")
                .andExpect(status().isOk()));

        assertThat(patched.get("date").asText()).isEqualTo(today.minusDays(3).toString());
        assertThat(patched.get("parentEventId").asText()).isEqualTo(booking);
    }

    @Test
    @DisplayName("п. 2: перевод в копилку — новая дата, движение копилки идёт за ней")
    void patchTransferDate_movesFundMovement() throws Exception {
        anchor(today.minusDays(10), "60000");
        String fund = jdbc.queryForObject("SELECT gen_random_uuid()::text", String.class);
        jdbc.update("""
                INSERT INTO target_funds (id, name, target_amount, current_balance, status, purchase_type, is_deleted, created_at)
                VALUES (?::uuid, 'Отпуск', 1000000, 0, 'FUNDING', 'SAVINGS', false, now())
                """, fund);
        mockMvc.perform(post("/api/v1/funds/{id}/transfer", fund)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": 5000}"))
                .andExpect(status().isOk());
        String transfer = jdbc.queryForObject(
                "SELECT id::text FROM financial_events WHERE type = 'FUND_TRANSFER' AND is_deleted = false", String.class);

        patchFact(transfer, "{\"factAmount\": 5000, \"categoryId\": \"" + expenseCategory(0) + "\"}")
                .andExpect(status().isBadRequest());
        patchFact(transfer, "{\"factAmount\": 5000, \"date\": \"" + today.minusDays(4) + "\"}")
                .andExpect(status().isOk());

        assertThat(movementDate(transfer)).isEqualTo(today.minusDays(4));

        // Ревью Codex на #230: сумма и дата вместе — движение берёт и то, и другое.
        patchFact(transfer, "{\"factAmount\": 4000, \"date\": \"" + today.minusDays(6) + "\"}")
                .andExpect(status().isOk());
        assertThat(movementDate(transfer)).isEqualTo(today.minusDays(6));
    }

    LocalDate movementDate(String transfer) {
        return jdbc.queryForObject("""
                SELECT t.transaction_date FROM fund_transactions t
                JOIN financial_events e ON e.idempotency_key = t.idempotency_key
                WHERE e.id = ?::uuid AND t.is_deleted = false""", LocalDate.class, transfer);
    }

    // ── п. 3: «Больше не нужно» ──────────────────────────────────────────────

    @Test
    @DisplayName("п. 3: бронь 10 000, записано 7 000 с closePlan — бронь 7 000, исполнена, «Свободно» на 3 000 больше")
    void closePlan_closesBookingAtPaid() throws Exception {
        anchor(today.minusDays(1), "60000");
        String booking = createPlan(today.plusDays(3), "10000", "HIGH", expenseCategory(0));
        BigDecimal before = pocket();

        mockMvc.perform(post("/api/v1/events/{id}/facts", booking)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\": \"" + today + "\", \"factAmount\": 7000, \"closePlan\": true}"))
                .andExpect(status().isOk());

        var row = jdbc.queryForMap("SELECT planned_amount, status FROM financial_events WHERE id = ?::uuid", booking);
        assertThat((BigDecimal) row.get("planned_amount")).isEqualByComparingTo("7000");
        assertThat(row.get("status")).isEqualTo("EXECUTED");
        assertThat(pocket().subtract(before))
                .as("без признака «Свободно» прежнее: 7 000 ушли, остаток брони держит 3 000")
                .isEqualByComparingTo("3000");
    }

    @Test
    @DisplayName("п. 3: closePlan у ожидания — 400")
    void closePlan_onExpectation_rejected() throws Exception {
        String plan = createPlan(today, "6000", "MEDIUM", expenseCategory(0));
        mockMvc.perform(post("/api/v1/events/{id}/facts", plan)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\": \"" + today + "\", \"factAmount\": 1000, \"closePlan\": true}"))
                .andExpect(status().isBadRequest());
    }

    // ── п. 5: правка серии со сменой числа ───────────────────────────────────

    final LocalDate seriesStart = today.plusMonths(1).withDayOfMonth(12);
    final LocalDate seriesEnd = seriesStart.plusMonths(5);

    @Test
    @DisplayName("п. 5: серия 12-го, ALL с 15-м — 200, ответ — строка 15-го того же месяца")
    void seriesAll_dayChange() throws Exception {
        String head = createSeries();
        JsonNode r = json(putSeries(head, "ALL", seriesStart.withDayOfMonth(15), 15, seriesEnd).andExpect(status().isOk()));
        assertThat(r.get("date").asText()).isEqualTo(seriesStart.withDayOfMonth(15).toString());
    }

    @Test
    @DisplayName("п. 5: FOLLOWING с 15-м и с 10-м — 200, строка того же месяца на новое число")
    void seriesFollowing_dayChange() throws Exception {
        createSeries();
        LocalDate second = seriesStart.plusMonths(1);
        JsonNode later = json(putSeries(rowOn(second), "FOLLOWING", second.withDayOfMonth(15), 15, seriesEnd)
                .andExpect(status().isOk()));
        assertThat(later.get("date").asText()).isEqualTo(second.withDayOfMonth(15).toString());

        JsonNode earlier = json(putSeries(later.get("id").asText(), "FOLLOWING", second.withDayOfMonth(10), 10, seriesEnd)
                .andExpect(status().isOk()));
        assertThat(earlier.get("date").asText())
                .as("число раньше прежнего — строка месяца не теряется")
                .isEqualTo(second.withDayOfMonth(10).toString());
        assertThat(seriesDates()).containsExactly(seriesStart, second.withDayOfMonth(10),
                second.plusMonths(1).withDayOfMonth(10), second.plusMonths(2).withDayOfMonth(10),
                second.plusMonths(3).withDayOfMonth(10), second.plusMonths(4).withDayOfMonth(10));
    }

    @Test
    @DisplayName("п. 5: «Закончить серию» — FOLLOWING с концом на дату строки: дальше строк нет, эта осталась")
    void seriesFinish() throws Exception {
        createSeries();
        LocalDate second = seriesStart.plusMonths(1);
        putSeries(rowOn(second), "FOLLOWING", second, 12, second).andExpect(status().isOk());
        assertThat(seriesDates()).containsExactly(seriesStart, second);
    }

    @Test
    @DisplayName("п. 5: годовая серия, FOLLOWING на месяц раньше — строка того же года не теряется")
    void yearlySeries_earlierMonth() throws Exception {
        LocalDate start = LocalDate.of(today.getYear() + 1, 6, 12);
        LocalDate end = start.plusYears(2);
        String body = """
                {"date": "%s", "categoryId": "%s", "type": "EXPENSE", "plannedAmount": 5000,
                 "priority": "HIGH", "description": "Страховка",
                 "recurring": {"frequency": "YEARLY", "dayOfMonth": 12, "monthOfYear": %d, "startDate": "%s", "endDate": "%s"}}
                """;
        mockMvc.perform(post("/api/v1/events").header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted(start, expenseCategory(0), start.getMonthValue(), start, end)))
                .andExpect(status().isOk());
        LocalDate second = start.plusYears(1);
        LocalDate moved = second.minusMonths(1);

        JsonNode r = json(mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/events/{id}", rowOn(second)).param("scope", "FOLLOWING")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted(moved, expenseCategory(0), moved.getMonthValue(), start, end)))
                .andExpect(status().isOk()));

        assertThat(r.get("date").asText()).isEqualTo(moved.toString());
    }

    String createSeries() throws Exception {
        return id(mockMvc.perform(post("/api/v1/events")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(seriesBody(seriesStart, 12, seriesStart, seriesEnd)))
                .andExpect(status().isOk()));
    }

    ResultActions putSeries(String id, String scope, LocalDate date, int day, LocalDate end) throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/api/v1/events/{id}", id).param("scope", scope)
                .contentType(MediaType.APPLICATION_JSON)
                .content(seriesBody(date, day, seriesStart, end)));
    }

    String seriesBody(LocalDate date, int day, LocalDate start, LocalDate end) {
        return """
                {"date": "%s", "categoryId": "%s", "type": "EXPENSE", "plannedAmount": 5000,
                 "priority": "HIGH", "description": "Аренда",
                 "recurring": {"frequency": "MONTHLY", "dayOfMonth": %d, "startDate": "%s", "endDate": "%s"}}
                """.formatted(date, expenseCategory(0), day, start, end);
    }

    String rowOn(LocalDate date) {
        return jdbc.queryForObject("SELECT id::text FROM financial_events WHERE date = ? AND is_deleted = false"
                + " AND recurring_rule_id IS NOT NULL", String.class, date);
    }

    java.util.List<LocalDate> seriesDates() {
        return jdbc.queryForList("SELECT date FROM financial_events WHERE is_deleted = false"
                + " AND recurring_rule_id IS NOT NULL ORDER BY date", LocalDate.class);
    }

    // ── оснастка ─────────────────────────────────────────────────────────────

    ResultActions patchFact(String id, String body) throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .patch("/api/v1/events/{id}/fact", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    ResultActions standaloneFact(String categoryId, LocalDate date, String amount, String priority) throws Exception {
        return mockMvc.perform(post("/api/v1/events/facts")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"date": "%s", "categoryId": "%s", "type": "EXPENSE",
                         "factAmount": %s, "priority": "%s", "description": "трата"}
                        """.formatted(date, categoryId, amount, priority)));
    }

    String expenseCategory(int n) {
        return jdbc.queryForList("SELECT id::text FROM categories WHERE type = 'EXPENSE' AND is_deleted = false"
                + " AND system_kind IS NULL ORDER BY name", String.class).get(n);
    }

    void anchor(LocalDate date, String amount) throws Exception {
        mockMvc.perform(post("/api/v1/balance-checkpoints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\": \"" + date + "\", \"amount\": " + amount + "}"))
                .andExpect(status().isCreated());
    }

    String createPlan(LocalDate date, String amount, String priority, String categoryId) throws Exception {
        return id(mockMvc.perform(post("/api/v1/events")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"date": "%s", "categoryId": "%s", "type": "EXPENSE",
                                 "plannedAmount": %s, "priority": "%s", "description": "план"}
                                """.formatted(date, categoryId, amount, priority)))
                .andExpect(status().isOk()));
    }

    ResultActions linkedFact(String planId, LocalDate date, String amount) throws Exception {
        return mockMvc.perform(post("/api/v1/events/{id}/facts", planId)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"date\": \"" + date + "\", \"factAmount\": " + amount + "}"));
    }

    String id(ResultActions r) throws Exception {
        return json(r).get("id").asText();
    }

    JsonNode json(ResultActions r) throws Exception {
        return objectMapper.readTree(r.andReturn().getResponse().getContentAsString());
    }

    BigDecimal pocket() throws Exception {
        return new BigDecimal(json(mockMvc.perform(get("/api/v1/pocket")).andExpect(status().isOk()))
                .get("pocket").asText());
    }

    // ── п. 12: что будет со «Свободно» — строка ожидания по правилу п. 1 ─────

    @Test
    @DisplayName("п. 12: fact-preview называет строку, к которой встанет запись, и сколько к ней уже записано")
    void factPreview_namesExpectationLine() throws Exception {
        String category = expenseCategory(0);
        String plan = createPlan(today, "6000", "MEDIUM", category);
        standaloneFact(category, today, "1200", "MEDIUM").andExpect(status().isCreated());

        JsonNode line = json(preview(category, today, "MEDIUM").andExpect(status().isOk()));

        assertThat(line.get("id").asText()).isEqualTo(plan);
        assertThat(line.get("name").asText()).isEqualTo("план");
        assertThat(line.get("date").asText()).isEqualTo(today.toString());
        assertThat(new BigDecimal(line.get("plannedAmount").asText())).isEqualByComparingTo("6000");
        assertThat(new BigDecimal(line.get("recorded").asText())).isEqualByComparingTo("1200");
    }

    @Test
    @DisplayName("п. 12: бронь той же категории и запись с характером «Бронь» — строки нет, ответ без тела")
    void factPreview_nothingForBookingOrHighRecord() throws Exception {
        String category = expenseCategory(0);
        createPlan(today, "6000", "HIGH", category);
        preview(category, today, "MEDIUM").andExpect(status().isNoContent());

        createPlan(today, "6000", "MEDIUM", category);
        preview(category, today, "HIGH").andExpect(status().isNoContent());
    }

    ResultActions preview(String categoryId, LocalDate date, String priority) throws Exception {
        return mockMvc.perform(get("/api/v1/events/fact-preview")
                .param("categoryId", categoryId).param("date", date.toString())
                .param("type", "EXPENSE").param("priority", priority));
    }
}
