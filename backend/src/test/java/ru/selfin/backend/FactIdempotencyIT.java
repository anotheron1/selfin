package ru.selfin.backend;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ANO-192: повтор записи факта с тем же ключом не пишет второй раз.
 *
 * <p>Сценарий — потерянный ответ: сервер записал, телефон ответа не получил, человек жмёт снова.
 * Приложение повторяет запись с тем же {@code Idempotency-Key} — правило ключа попытки живёт в
 * {@code frontend/src/lib/attemptKey.ts}, — и сервер обязан вернуть уже записанное. До правки
 * обе ручки фактов заголовок не читали: на стенде 27.09 два касания кружка брони на 192 ₽ дали
 * «факт 384 ₽».
 *
 * <p>Спека: {@code docs/superpowers/specs/2026-09-27-write-attempt-key-design.md}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class FactIdempotencyIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;

    private static final LocalDate TODAY = LocalDate.now();

    /** Контейнер один на класс: факты одного теста сбили бы счёт соседнего. */
    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM fund_transactions");
        jdbc.update("DELETE FROM financial_events");
        jdbc.update("DELETE FROM target_funds");
    }

    @Test
    @DisplayName("факт к плану: тот же ключ дважды — один факт, повтор возвращает его же")
    void linkedFact_sameKeyTwice_recordsOnce() throws Exception {
        String planId = createExpensePlan("192");
        UUID key = UUID.randomUUID();

        String first = idOf(linkedFact(planId, "192", key).andExpect(status().isOk()));
        String second = idOf(linkedFact(planId, "192", key).andExpect(status().isOk()));

        assertThat(second).as("повтор возвращает уже записанный факт").isEqualTo(first);
        assertThat(factsOf(planId)).as("второго факта нет").isEqualTo(1);
    }

    @Test
    @DisplayName("факт без плана: тот же ключ дважды — один факт, повтор возвращает его же")
    void standaloneFact_sameKeyTwice_recordsOnce() throws Exception {
        UUID key = UUID.randomUUID();

        String first = idOf(standaloneFact("150", key).andExpect(status().isCreated()));
        String second = idOf(standaloneFact("150", key).andExpect(status().isCreated()));

        assertThat(second).as("повтор возвращает уже записанный факт").isEqualTo(first);
        assertThat(standaloneFacts()).as("второго факта нет").isEqualTo(1);
    }

    /**
     * У факта перевода ключ факта — и ключ движения копилки (ANO-169). Повтор не должен ни
     * записать второй факт, ни сдвинуть копилку второй раз.
     */
    @Test
    @DisplayName("факт к плану перевода: тот же ключ дважды — копилка получила деньги один раз")
    void transferFact_sameKeyTwice_movesFundOnce() throws Exception {
        String fundId = createFund("Проба ANO-192");
        String planId = createTransferPlan(fundId, "1000");
        UUID key = UUID.randomUUID();

        linkedFact(planId, "1000", key).andExpect(status().isOk());
        linkedFact(planId, "1000", key).andExpect(status().isOk());

        assertThat(factsOf(planId)).as("второго факта нет").isEqualTo(1);
        assertThat(fundBalance(fundId)).as("копилка получила перевод один раз").isEqualByComparingTo("1000");
        assertThat(movementsOf(fundId)).as("движение одно").isEqualTo(1);
    }

    /** Две одинаковые покупки за день — два факта: сервер различает записи по ключу, а не по сумме. */
    @Test
    @DisplayName("разные ключи — два факта, даже с одинаковой суммой и датой")
    void differentKeys_recordTwice() throws Exception {
        String planId = createExpensePlan("500");

        linkedFact(planId, "100", UUID.randomUUID()).andExpect(status().isOk());
        linkedFact(planId, "100", UUID.randomUUID()).andExpect(status().isOk());

        assertThat(factsOf(planId)).isEqualTo(2);
    }

    @Test
    @DisplayName("факт к плану без ключа — 400, факта нет")
    void linkedFact_withoutKey_is400() throws Exception {
        String planId = createExpensePlan("192");

        mockMvc.perform(post("/api/v1/events/{planId}/facts", planId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(linkedBody("192")))
                .andExpect(status().isBadRequest());

        assertThat(factsOf(planId)).isZero();
    }

    @Test
    @DisplayName("факт без плана без ключа — 400, факта нет")
    void standaloneFact_withoutKey_is400() throws Exception {
        mockMvc.perform(post("/api/v1/events/facts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(standaloneBody("150")))
                .andExpect(status().isBadRequest());

        assertThat(standaloneFacts()).isZero();
    }

    // ── оснастка ────────────────────────────────────────────────────────────

    private ResultActions linkedFact(String planId, String amount, UUID key) throws Exception {
        return mockMvc.perform(withKey(post("/api/v1/events/{planId}/facts", planId), key)
                .content(linkedBody(amount)));
    }

    private ResultActions standaloneFact(String amount, UUID key) throws Exception {
        return mockMvc.perform(withKey(post("/api/v1/events/facts"), key)
                .content(standaloneBody(amount)));
    }

    private static MockHttpServletRequestBuilder withKey(MockHttpServletRequestBuilder request, UUID key) {
        return request.header("Idempotency-Key", key.toString()).contentType(MediaType.APPLICATION_JSON);
    }

    private static String linkedBody(String amount) {
        return "{\"date\": \"%s\", \"factAmount\": %s}".formatted(TODAY, amount);
    }

    private String standaloneBody(String amount) {
        return """
                {"date": "%s", "categoryId": "%s", "type": "EXPENSE", "factAmount": %s,
                 "description": "Проба ANO-192"}
                """.formatted(TODAY, expenseCategoryId(), amount);
    }

    private String createExpensePlan(String amount) throws Exception {
        return createPlan("""
                {"date": "%s", "type": "EXPENSE", "categoryId": "%s", "plannedAmount": %s,
                 "priority": "HIGH", "description": "Проба ANO-192"}
                """.formatted(TODAY, expenseCategoryId(), amount));
    }

    /** План перевода — как его создаёт быстрый ввод: без категории, характер «Ожидание». */
    private String createTransferPlan(String fundId, String amount) throws Exception {
        return createPlan("""
                {"date": "%s", "type": "FUND_TRANSFER", "plannedAmount": %s,
                 "priority": "MEDIUM", "targetFundId": "%s"}
                """.formatted(TODAY, amount, fundId));
    }

    private String createPlan(String body) throws Exception {
        return idOf(mockMvc.perform(withKey(post("/api/v1/events"), UUID.randomUUID()).content(body))
                .andExpect(status().isOk()));
    }

    private String createFund(String name) {
        String id = jdbc.queryForObject("SELECT gen_random_uuid()::text", String.class);
        jdbc.update("""
                INSERT INTO target_funds
                    (id, name, target_amount, current_balance, status, purchase_type,
                     is_deleted, created_at)
                VALUES (?::uuid, ?, 1000000, 0, 'FUNDING', 'SAVINGS', false, now())
                """, id, name);
        return id;
    }

    private String expenseCategoryId() {
        return jdbc.queryForObject(
                "SELECT id::text FROM categories WHERE type = 'EXPENSE' AND is_deleted = false"
                        + " ORDER BY name LIMIT 1",
                String.class);
    }

    private String idOf(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString())
                .get("id").asText();
    }

    private int factsOf(String planId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM financial_events WHERE parent_event_id = ?::uuid AND is_deleted = false",
                Integer.class, planId);
        return n == null ? 0 : n;
    }

    private int standaloneFacts() {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM financial_events
                WHERE event_kind = 'FACT' AND parent_event_id IS NULL AND is_deleted = false
                """, Integer.class);
        return n == null ? 0 : n;
    }

    private BigDecimal fundBalance(String fundId) {
        return jdbc.queryForObject("SELECT current_balance FROM target_funds WHERE id = ?::uuid",
                BigDecimal.class, fundId);
    }

    private int movementsOf(String fundId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM fund_transactions WHERE fund_id = ?::uuid AND is_deleted = false",
                Integer.class, fundId);
        return n == null ? 0 : n;
    }
}
