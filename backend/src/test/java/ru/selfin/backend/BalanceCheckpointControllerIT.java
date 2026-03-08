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
    void dashboard_currentBalance_includesCheckpointAmount() throws Exception {
        // Используем дату в далёком будущем — там нет засеянных событий,
        // поэтому currentBalance должен быть равен ровно сумме чекпоинта.
        String cpDate = "2099-06-01";
        String asOfDate = "2099-06-15";

        mockMvc.perform(post("/api/v1/balance-checkpoints")
                .contentType(MediaType.APPLICATION_JSON)
                .content(String.format("""
                        {"date":"%s","amount":55000}
                        """, cpDate)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/analytics/dashboard?date=" + asOfDate))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentBalance").value(55000));
    }
}
