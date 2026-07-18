package ru.selfin.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Интеграционные тесты CRUD-операций с чекпоинтами баланса и их влияния на Dashboard.
 * Тестирует полный стек: HTTP → Controller → Service → Repository → PostgreSQL.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class BalanceCheckpointControllerIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void create_thenAppearsInList() throws Exception {
        String created = mockMvc.perform(post("/api/v1/balance-checkpoints")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"date":"2026-03-01","amount":100000}
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.date").value("2026-03-01"))
                .andExpect(jsonPath("$.amount").value(100000))
                .andReturn().getResponse().getContentAsString();

        String id = objectMapper.readTree(created).get("id").asText();

        mockMvc.perform(get("/api/v1/balance-checkpoints"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + id + "')]").exists());
    }

    @Test
    void create_negativeAmount_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/balance-checkpoints")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"date":"2026-03-01","amount":-1}
                        """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void create_missingDate_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/balance-checkpoints")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"amount":50000}
                        """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void update_changesDateAndAmount() throws Exception {
        String created = mockMvc.perform(post("/api/v1/balance-checkpoints")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"date":"2026-02-01","amount":50000}
                        """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String id = objectMapper.readTree(created).get("id").asText();

        mockMvc.perform(put("/api/v1/balance-checkpoints/" + id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"date":"2026-02-15","amount":75000}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.date").value("2026-02-15"))
                .andExpect(jsonPath("$.amount").value(75000));
    }

    @Test
    void update_unknownId_returns404() throws Exception {
        String unknownId = "00000000-0000-0000-0000-000000000000";
        mockMvc.perform(put("/api/v1/balance-checkpoints/" + unknownId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"date":"2026-01-01","amount":0}
                        """))
                .andExpect(status().isNotFound());
    }

    @Test
    void delete_removesCheckpoint() throws Exception {
        String created = mockMvc.perform(post("/api/v1/balance-checkpoints")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"date":"2025-12-01","amount":30000}
                        """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String id = objectMapper.readTree(created).get("id").asText();

        mockMvc.perform(delete("/api/v1/balance-checkpoints/" + id))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/balance-checkpoints"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + id + "')]").doesNotExist());
    }

    @Test
    void delete_unknownId_returns404() throws Exception {
        String unknownId = "00000000-0000-0000-0000-000000000001";
        mockMvc.perform(delete("/api/v1/balance-checkpoints/" + unknownId))
                .andExpect(status().isNotFound());
    }

    @Test
    void history_carriesIntervalDrift() throws Exception {
        // ANO-15 §4: даты в глубоком прошлом (2020) — гарантированно смежные в цепочке,
        // другие тесты класса живут в 2025-2026. Фактов между нет → computed = prev.amount.
        String aId = createCp("2020-01-01", 10_000);
        String bId = createCp("2020-01-15", 8_000);
        try {
            String body = mockMvc.perform(get("/api/v1/balance-checkpoints"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            com.fasterxml.jackson.databind.JsonNode b = findById(body, bId);
            org.assertj.core.api.Assertions.assertThat(b.get("computedBalance").decimalValue())
                    .isEqualByComparingTo(java.math.BigDecimal.valueOf(10_000));
            org.assertj.core.api.Assertions.assertThat(b.get("drift").decimalValue())
                    .isEqualByComparingTo(java.math.BigDecimal.valueOf(-2_000));
        } finally {
            deleteCp(aId);
            deleteCp(bId);
        }
    }

    @Test
    void sameDayDuplicate_laterCreatedWins() throws Exception {
        // ANO-15 §4: дубль дня («исправил опечатку») — якорь/цепочка детерминированы по createdAt
        String firstId = createCp("2020-02-01", 100);
        String fixedId = createCp("2020-02-01", 200);
        try {
            String body = mockMvc.perform(get("/api/v1/balance-checkpoints"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            com.fasterxml.jackson.databind.JsonNode fixed = findById(body, fixedId);
            // Поздняя правка стоит выше ранней того же дня: её prev = первая запись дня
            org.assertj.core.api.Assertions.assertThat(fixed.get("computedBalance").decimalValue())
                    .isEqualByComparingTo(java.math.BigDecimal.valueOf(100));
            org.assertj.core.api.Assertions.assertThat(fixed.get("drift").decimalValue())
                    .isEqualByComparingTo(java.math.BigDecimal.valueOf(100));
        } finally {
            deleteCp(firstId);
            deleteCp(fixedId);
        }
    }

    private String createCp(String date, long amount) throws Exception {
        String body = mockMvc.perform(post("/api/v1/balance-checkpoints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"date":"%s","amount":%d}
                                """.formatted(date, amount)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    private void deleteCp(String id) throws Exception {
        mockMvc.perform(delete("/api/v1/balance-checkpoints/" + id)).andExpect(status().isNoContent());
    }

    private com.fasterxml.jackson.databind.JsonNode findById(String listBody, String id) throws Exception {
        for (com.fasterxml.jackson.databind.JsonNode n : objectMapper.readTree(listBody)) {
            if (id.equals(n.get("id").asText())) return n;
        }
        throw new AssertionError("checkpoint " + id + " not found in list");
    }

    @Test
    void pocket_currentBalance_includesCheckpointAmount() throws Exception {
        // Дата чекпоинта обязана быть @PastOrPresent (будущее — 400), поэтому «сегодня».
        // После ANO-13/14 дашборд отдаёт только progressBars; единая истина по балансу —
        // GET /pocket (PocketResultDto.currentBalance). Событий в этом классе никто
        // не создаёт, а этот чекпоинт — самый поздний по дате (остальные тесты класса
        // используют прошлые даты), так что currentBalance равен ровно его сумме.
        String cpDate = java.time.LocalDate.now().toString();

        mockMvc.perform(post("/api/v1/balance-checkpoints")
                .contentType(MediaType.APPLICATION_JSON)
                .content(String.format("""
                        {"date":"%s","amount":55000}
                        """, cpDate)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/pocket"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentBalance").value(55000));
    }
}
