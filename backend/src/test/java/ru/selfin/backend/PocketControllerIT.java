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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * IT кармашка: контракт GET /pocket, настройки буфера,
 * сценарий ANO-6 (ввод немедленно меняет ответ), «факт вытесняет план».
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class PocketControllerIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    private BigDecimal pocket() throws Exception {
        String body = mockMvc.perform(get("/api/v1/pocket"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new BigDecimal(objectMapper.readTree(body).get("pocket").asText());
    }

    @Test
    void contract_defaultScope() throws Exception {
        mockMvc.perform(get("/api/v1/pocket"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pocket").exists())
                .andExpect(jsonPath("$.currentBalance").exists())
                .andExpect(jsonPath("$.buffer").exists())
                .andExpect(jsonPath("$.horizon.type").exists())
                .andExpect(jsonPath("$.horizon.endDate").exists())
                .andExpect(jsonPath("$.horizon.label").exists())
                .andExpect(jsonPath("$.minPoint.date").exists())
                .andExpect(jsonPath("$.breakdown").isArray())
                .andExpect(jsonPath("$.trajectory").isArray())
                .andExpect(jsonPath("$.wishlistCandidates").isArray());
    }

    @Test
    void scopes_monthsOkGarbage400() throws Exception {
        mockMvc.perform(get("/api/v1/pocket?scope=MONTHS:3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.horizon.type").value("MONTHS"));
        mockMvc.perform(get("/api/v1/pocket?scope=GARBAGE"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/pocket?scope=MONTHS:99"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void settings_putGetValidate() throws Exception {
        mockMvc.perform(put("/api/v1/settings/pocket")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bufferAmount": 5000}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bufferAmount").value(5000));

        mockMvc.perform(get("/api/v1/settings/pocket"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bufferAmount").value(5000));

        mockMvc.perform(put("/api/v1/settings/pocket")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bufferAmount": -1}
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/api/v1/settings/pocket")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        // вернуть 0, чтобы не влиять на другие тесты
        mockMvc.perform(put("/api/v1/settings/pocket")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bufferAmount": 0}
                                """))
                .andExpect(status().isOk());
    }

    /**
     * Сценарий ANO-6: ввод плана и факта немедленно меняет ответ кармашка.
     * План HIGH-расхода на СЕГОДНЯ −5000 → кармашек падает на 5000 (день 0 траектории);
     * факт 4000 по этому плану → факт вытесняет план: кармашек = старт − 4000.
     *
     * Дата = сегодня НАМЕРЕННО: updateFact пишет factAmount в ту же PLAN-запись,
     * а факт с БУДУЩЕЙ датой не попадает ни в баланс (date > asOf), ни в траекторию
     * (factAmount != null) — известная дыра v1, залогирована в ANO-12.
     */
    @Test
    void ano6_inputImmediatelyChangesPocket() throws Exception {
        // Категория для события (оба POST в этом API возвращают 200, не 201)
        String catBody = mockMvc.perform(post("/api/v1/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"IT-pocket-test","type":"EXPENSE"}
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String categoryId = objectMapper.readTree(catBody).get("id").asText();

        BigDecimal before = pocket();

        // План: HIGH-расход на сегодня. POST /events требует Idempotency-Key (обязательный header).
        LocalDate planDate = LocalDate.now();
        String eventBody = mockMvc.perform(post("/api/v1/events")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"date":"%s","categoryId":"%s","type":"EXPENSE",
                                 "plannedAmount":5000,"priority":"HIGH","description":"IT plan"}
                                """.formatted(planDate, categoryId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String planId = objectMapper.readTree(eventBody).get("id").asText();

        BigDecimal afterPlan = pocket();
        assertThat(before.subtract(afterPlan)).isEqualByComparingTo(new BigDecimal("5000"));

        // Факт 4000 по плану: PATCH /events/{id}/fact (FinancialEventUpdateFactDto)
        mockMvc.perform(patch("/api/v1/events/" + planId + "/fact")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"factAmount": 4000}
                                """))
                .andExpect(status().isOk());

        BigDecimal afterFact = pocket();
        // План вытеснен фактом: итоговая дельта от старта = −4000, не −9000 и не −5000
        assertThat(before.subtract(afterFact)).isEqualByComparingTo(new BigDecimal("4000"));
    }
}
