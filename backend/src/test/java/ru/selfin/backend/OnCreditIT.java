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
import ru.selfin.backend.service.OwnerService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Срез 4, пп. 1–2 (ANO-261): отметка «в кредит» у расхода без плана и «в кредит записано» в листе «Обновить остатки».
 * «Свободно» — {@code GET /pocket}, «по записям» — лист, как на экране.
 *
 * <p>Пользователь MockMvc — синтетический владелец с «Основной картой» (V20) и «Наличными» (V38).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class OnCreditIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired JdbcTemplate jdbc;

    final LocalDate today = LocalDate.now();
    final UUID me = OwnerService.SYNTHETIC_OWNER;

    @BeforeEach
    void reset() throws Exception {
        jdbc.update("DELETE FROM financial_events");
        jdbc.update("DELETE FROM balance_checkpoints");
        jdbc.update("DELETE FROM accounts WHERE user_id = ? AND name NOT IN ('Основная карта', 'Наличные')", me);
        anchor(null, today.minusDays(1), "60000");
    }

    // ── п. 1: запись в кредит ────────────────────────────────────────────────

    @Test
    @DisplayName("в кредит: остаток основной и «Свободно» прежние; запись в журнале с отметкой и в аналитике")
    void onCredit_leavesCardAndFree_staysInJournalAndAnalytics() throws Exception {
        BigDecimal free = pocket();
        BigDecimal card = mainByRecords();

        String id = id(fact(category(0), today, "30000", "EXPENSE", true).andExpect(status().isCreated()));

        assertThat(pocket()).as("деньги ушли с кредитки, не с карты").isEqualByComparingTo(free);
        assertThat(mainByRecords()).isEqualByComparingTo(card);
        JsonNode row = journalRow(id);
        assertThat(row.get("onCredit").asBoolean()).as("журнал пишет «в кредит»").isTrue();
        assertThat(row.get("factAmount").decimalValue()).isEqualByComparingTo("30000");
        assertThat(json(mvc.perform(get("/api/v1/analytics/report?date=" + today)).andExpect(status().isOk()))
                .get("planFact").get("totalFactExpense").decimalValue())
                .as("покупка — расход месяца, как любая трата").isEqualByComparingTo("30000");
    }

    @Test
    @DisplayName("в кредит в категории с ожиданием: строка не гасится — «Свободно» не выросло на сумму покупки")
    void onCredit_doesNotSettleExpectation() throws Exception {
        String category = category(0);
        String expectation = plan(today, "6000", "MEDIUM", category);
        BigDecimal free = pocket();

        String id = id(fact(category, today, "2000", "EXPENSE", true).andExpect(status().isCreated()));

        assertThat(parentOf(id)).as("к ожиданию не встала").isNull();
        assertThat(pocket()).as("строка держит всю сумму, карта не тронута").isEqualByComparingTo(free);
        assertThat(statusOf(expectation)).isEqualTo("PLANNED");
    }

    @Test
    @DisplayName("снять отметку — «Свободно» меньше на сумму записи; поставить снова — прежнее")
    void unmark_lowersFree_markAgainRestores() throws Exception {
        BigDecimal free = pocket();
        String id = id(fact(category(0), today, "30000", "EXPENSE", true).andExpect(status().isCreated()));

        patchFact(id, "{\"factAmount\": 30000, \"onCredit\": false}").andExpect(status().isOk());
        assertThat(pocket()).isEqualByComparingTo(free.subtract(new BigDecimal("30000")));
        assertThat(journalRow(id).get("onCredit").asBoolean()).isFalse();

        patchFact(id, "{\"factAmount\": 30000, \"onCredit\": true}").andExpect(status().isOk());
        assertThat(pocket()).isEqualByComparingTo(free);
    }

    @Test
    @DisplayName("отметка у записи к ожиданию снимает её со строки — строка снова открыта; снятая встаёт обратно")
    void mark_detachesFromExpectation_unmarkRebinds() throws Exception {
        String category = category(0);
        String expectation = plan(today, "2000", "MEDIUM", category);
        String id = id(fact(category, today, "2000", "EXPENSE", false).andExpect(status().isCreated()));
        assertThat(parentOf(id)).isEqualTo(expectation);
        assertThat(statusOf(expectation)).as("погашена записью целиком").isEqualTo("EXECUTED");

        patchFact(id, "{\"factAmount\": 2000, \"onCredit\": true}").andExpect(status().isOk());
        assertThat(parentOf(id)).isNull();
        assertThat(statusOf(expectation)).as("правило заново и пересчёт старой строки").isEqualTo("PLANNED");

        patchFact(id, "{\"factAmount\": 2000, \"onCredit\": false}").andExpect(status().isOk());
        assertThat(parentOf(id)).isEqualTo(expectation);
        assertThat(statusOf(expectation)).isEqualTo("EXECUTED");
    }

    @Test
    @DisplayName("отметка у дохода, у записи к плану и у записи к брони — 400, ничего не записано")
    void onCredit_refusedForIncomeAndPlans() throws Exception {
        fact(incomeCategory(), today, "1000", "INCOME", true).andExpect(status().isBadRequest());
        assertThat(facts()).isZero();

        String booking = plan(today, "5000", "HIGH", category(0));
        mvc.perform(post("/api/v1/events/{id}/facts", booking).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\": \"" + today + "\", \"factAmount\": 1000, \"onCredit\": true}"))
                .andExpect(status().isBadRequest());
        assertThat(facts()).as("отказ — до записи").isZero();

        String toBooking = id(mvc.perform(post("/api/v1/events/{id}/facts", booking)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\": \"" + today + "\", \"factAmount\": 1000}"))
                .andExpect(status().isOk()));
        patchFact(toBooking, "{\"factAmount\": 1000, \"onCredit\": true}").andExpect(status().isBadRequest());
        assertThat(onCredit(toBooking)).isFalse();

        String income = id(fact(incomeCategory(), today, "1000", "INCOME", false).andExpect(status().isCreated()));
        patchFact(income, "{\"factAmount\": 1000, \"onCredit\": true}").andExpect(status().isBadRequest());
        assertThat(onCredit(income)).isFalse();
    }

    // ── п. 2: «в кредит записано» в листе ───────────────────────────────────

    @Test
    @DisplayName("«в кредит записано» — с самого старого обновления среди кредиток; раньше него и до его ввода — не "
            + "в счёт; запись без отметки — тоже")
    void sheet_onCreditSinceOldestCreditUpdate() throws Exception {
        String category = category(0);
        fact(category, today.minusDays(6), "100", "EXPENSE", true);   // раньше самого старого обновления
        fact(category, today.minusDays(5), "200", "EXPENSE", true);   // в день обновления, но до его ввода
        String older = credit("Платинум");
        String newer = credit("Тинькофф");
        anchor(older, today.minusDays(5), "85000");
        anchor(newer, today.minusDays(2), "40000");
        fact(category, today.minusDays(5), "1000", "EXPENSE", true);  // в день обновления, после ввода
        fact(category, today.minusDays(3), "3000", "EXPENSE", true);
        fact(category, today, "500", "EXPENSE", true);
        fact(category, today, "7000", "EXPENSE", false);

        JsonNode onCredit = sheet().get("onCredit");

        assertThat(onCredit.get("since").asText()).isEqualTo(today.minusDays(5).toString());
        assertThat(onCredit.get("amount").decimalValue()).isEqualByComparingTo("4500");
    }

    @Test
    @DisplayName("кредиток нет — суммы «в кредит записано» нет")
    void sheet_noCreditCards_noOnCredit() throws Exception {
        fact(category(0), today, "500", "EXPENSE", true);

        JsonNode sheet = sheet();

        assertThat(sheet.get("onCredit") == null || sheet.get("onCredit").isNull()).isTrue();
    }

    // ── оснастка ─────────────────────────────────────────────────────────────

    private ResultActions fact(String categoryId, LocalDate date, String amount, String type, boolean onCredit)
            throws Exception {
        return mvc.perform(post("/api/v1/events/facts").header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"date":"%s","categoryId":"%s","type":"%s","factAmount":%s,
                         "priority":"MEDIUM","description":"трата","onCredit":%s}"""
                        .formatted(date, categoryId, type, amount, onCredit)));
    }

    private String plan(LocalDate date, String amount, String priority, String categoryId) throws Exception {
        return id(mvc.perform(post("/api/v1/events").header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"date": "%s", "categoryId": "%s", "type": "EXPENSE",
                                 "plannedAmount": %s, "priority": "%s", "description": "план"}"""
                                .formatted(date, categoryId, amount, priority)))
                .andExpect(status().isOk()));
    }

    private ResultActions patchFact(String id, String body) throws Exception {
        return mvc.perform(patch("/api/v1/events/{id}/fact", id).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String credit(String name) throws Exception {
        return id(mvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"%s","kind":"CREDIT","trackBalance":true,"creditLimit":100000}""".formatted(name)))
                .andExpect(status().isCreated()));
    }

    private void anchor(String account, LocalDate date, String amount) throws Exception {
        mvc.perform(post("/api/v1/balance-checkpoints").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"%s\",\"amount\":%s%s}".formatted(date, amount,
                                account != null ? ",\"accountId\":\"" + account + "\"" : "")))
                .andExpect(status().isCreated());
    }

    private JsonNode sheet() throws Exception {
        return json(mvc.perform(get("/api/v1/balance-checkpoints/sheet")).andExpect(status().isOk()));
    }

    private BigDecimal mainByRecords() throws Exception {
        return sheet().get("cards").get(0).get("byRecords").decimalValue();
    }

    private JsonNode journalRow(String id) throws Exception {
        for (JsonNode r : json(mvc.perform(get("/api/v1/events").param("startDate", today.withDayOfMonth(1).toString())
                .param("endDate", today.toString())).andExpect(status().isOk()))) {
            if (r.get("id").asText().equals(id)) return r;
        }
        throw new AssertionError("в журнале нет записи " + id);
    }

    private String category(int n) {
        return jdbc.queryForList("SELECT id::text FROM categories WHERE user_id = ? AND type = 'EXPENSE'"
                + " AND NOT is_deleted AND system_kind IS NULL ORDER BY name", String.class, me).get(n);
    }

    private String incomeCategory() {
        return jdbc.queryForList("SELECT id::text FROM categories WHERE user_id = ? AND type = 'INCOME'"
                + " AND NOT is_deleted AND system_kind IS NULL ORDER BY name", String.class, me).get(0);
    }

    private String parentOf(String id) {
        return jdbc.queryForObject("SELECT parent_event_id::text FROM financial_events WHERE id = ?::uuid",
                String.class, id);
    }

    private String statusOf(String id) {
        return jdbc.queryForObject("SELECT status FROM financial_events WHERE id = ?::uuid", String.class, id);
    }

    private boolean onCredit(String id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT on_credit FROM financial_events WHERE id = ?::uuid",
                Boolean.class, id));
    }

    private long facts() {
        return jdbc.queryForObject("SELECT count(*) FROM financial_events WHERE event_kind = 'FACT'", Long.class);
    }

    private BigDecimal pocket() throws Exception {
        return new BigDecimal(json(mvc.perform(get("/api/v1/pocket")).andExpect(status().isOk())).get("pocket").asText());
    }

    private String id(ResultActions r) throws Exception {
        return json(r).get("id").asText();
    }

    private JsonNode json(ResultActions r) throws Exception {
        return om.readTree(r.andReturn().getResponse().getContentAsString());
    }
}
