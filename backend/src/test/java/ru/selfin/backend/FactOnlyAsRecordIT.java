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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Р3 (ANO-25): факт — только отдельной записью. {@code PATCH /events/{id}/fact} правит запись-факт,
 * а строке плана отказывает: сумма факта в самой строке — наследие, у которого была дыра — факт в
 * будущую строку снимал её из резерва сразу, а сам считался только в свой день.
 *
 * <p>Спека: {@code docs/superpowers/specs/2026-10-04-fact-only-as-record-design.md}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class FactOnlyAsRecordIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;

    private final LocalDate today = LocalDate.now();

    @AfterEach
    void cleanDb() {
        jdbc.update("DELETE FROM financial_events");
        jdbc.update("DELETE FROM balance_checkpoints");
    }

    @Test
    @DisplayName("Р3: факт в будущую строку плана — 400; строка не тронута, свободные не сдвинулись")
    void patchFactOnPlanRow_isRejected() throws Exception {
        checkpoint(today.minusDays(10), 100_000);
        String plan = plan(today.plusDays(1), 8_000);
        BigDecimal before = pocket();

        mockMvc.perform(patch("/api/v1/events/{id}/fact", plan)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"factAmount\": 8000}"))
                .andExpect(status().isBadRequest());

        assertThat(pocket()).as("раньше строка уходила из резерва сразу — свободные росли на 8 000")
                .isEqualByComparingTo(before);
        JsonNode row = eventOn(today.plusDays(1), plan);
        assertThat(row.get("factAmount").isNull()).isTrue();
        assertThat(row.get("status").asText()).isEqualTo("PLANNED");
    }

    @Test
    @DisplayName("Р3: запись-факт по-прежнему правится — сумма и описание")
    void patchFactOnFactRecord_stillWorks() throws Exception {
        checkpoint(today.minusDays(10), 100_000);
        String plan = plan(today, 5_000);
        String fact = linkedFact(plan, 5_000);

        mockMvc.perform(patch("/api/v1/events/{id}/fact", fact)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"factAmount\": 300, \"description\": \"Чек\"}"))
                .andExpect(status().isOk());

        assertThat(eventOn(today, fact).get("factAmount").decimalValue()).isEqualByComparingTo("300");
        assertThat(pocket()).as("300 ушло, 4 700 плана снова держится сегодня")
                .isEqualByComparingTo("95000");
    }

    // ── API, как им пользуется фронт ────────────────────────────────────────

    private BigDecimal pocket() throws Exception {
        String json = mockMvc.perform(get("/api/v1/pocket"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json).get("pocket").decimalValue();
    }

    private void checkpoint(LocalDate date, long amount) throws Exception {
        mockMvc.perform(post("/api/v1/balance-checkpoints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("date", date.toString(), "amount", amount))))
                .andExpect(status().isCreated());
    }

    private String plan(LocalDate date, long amount) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "date", date.toString(), "categoryId", expenseCategory(), "type", "EXPENSE",
                "plannedAmount", amount, "priority", "MEDIUM", "description", "План Р3"));
        String json = mockMvc.perform(post("/api/v1/events")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json).get("id").asText();
    }

    private String linkedFact(String planId, long amount) throws Exception {
        String json = mockMvc.perform(post("/api/v1/events/{id}/facts", planId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("date", today.toString(), "factAmount", amount))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json).get("id").asText();
    }

    private JsonNode eventOn(LocalDate date, String id) throws Exception {
        String json = mockMvc.perform(get("/api/v1/events")
                        .param("startDate", date.toString()).param("endDate", date.toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        for (JsonNode e : objectMapper.readTree(json)) {
            if (id.equals(e.get("id").asText())) return e;
        }
        throw new AssertionError("нет события " + id + " на " + date);
    }

    private String expenseCategory() throws Exception {
        String json = mockMvc.perform(get("/api/v1/categories"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        for (JsonNode c : objectMapper.readTree(json)) {
            if ("EXPENSE".equals(c.get("type").asText())) return c.get("id").asText();
        }
        throw new AssertionError("нет категории расходов");
    }
}
