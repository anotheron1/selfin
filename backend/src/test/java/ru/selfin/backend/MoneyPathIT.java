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

        assertThat(jdbc.queryForObject("""
                SELECT t.transaction_date FROM fund_transactions t
                JOIN financial_events e ON e.idempotency_key = t.idempotency_key
                WHERE e.id = ?::uuid AND t.is_deleted = false""", LocalDate.class, transfer))
                .isEqualTo(today.minusDays(4));
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
                + " AND name NOT IN ('Хотелки', 'Цели', 'Переводы в копилки', 'Кредит') ORDER BY name", String.class).get(n);
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
}
