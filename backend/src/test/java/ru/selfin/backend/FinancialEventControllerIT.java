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
import ru.selfin.backend.dto.FactCreateDto;
import ru.selfin.backend.dto.FinancialEventCreateDto;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.Priority;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Интеграционный тест с реальным PostgreSQL через Testcontainers.
 * Тестирует весь стек: HTTP → Controller → Service → Repository → БД.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class FinancialEventControllerIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    /** Получить первую попавшуюся категорию из БД (засеяны V2 миграцией) */
    private String getFirstCategoryId() throws Exception {
        String body = mockMvc.perform(get("/api/v1/categories"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var list = objectMapper.readValue(body, java.util.List.class);
        @SuppressWarnings("unchecked")
        var first = (Map<String, Object>) list.get(0);
        return (String) first.get("id");
    }

    @Test
    void createEvent_thenGetByPeriod() throws Exception {
        String catId = getFirstCategoryId();
        String idempotencyKey = UUID.randomUUID().toString();

        FinancialEventCreateDto dto = new FinancialEventCreateDto(
                LocalDate.now(), UUID.fromString(catId), EventType.EXPENSE,
                BigDecimal.valueOf(1000), null, "Тестовый расход", null, null);

        // Создаём событие
        mockMvc.perform(post("/api/v1/events")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PLANNED"))
                .andExpect(jsonPath("$.plannedAmount").value(1000));
    }

    @Test
    void createEvent_idempotent_returnsSameResult() throws Exception {
        String catId = getFirstCategoryId();
        String key = UUID.randomUUID().toString();

        FinancialEventCreateDto dto = new FinancialEventCreateDto(
                LocalDate.now(), UUID.fromString(catId), EventType.EXPENSE,
                BigDecimal.valueOf(500), null, null, null, null);

        // Первый запрос
        String first = mockMvc.perform(post("/api/v1/events")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Повторный запрос с тем же ключом → должен вернуть то же событие
        String second = mockMvc.perform(post("/api/v1/events")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        var firstId = objectMapper.readTree(first).get("id").asText();
        var secondId = objectMapper.readTree(second).get("id").asText();
        assert firstId.equals(secondId) : "Идемпотентный ключ должен возвращать одно и то же событие";
    }

    @Test
    void updateEvent_plannedAmount_updatesSuccessfully() throws Exception {
        String catId = getFirstCategoryId();

        FinancialEventCreateDto create = new FinancialEventCreateDto(
                LocalDate.now(), UUID.fromString(catId), EventType.EXPENSE,
                BigDecimal.valueOf(2000), null, null, null, null);

        String created = mockMvc.perform(post("/api/v1/events")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(create)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String eventId = objectMapper.readTree(created).get("id").asText();

        FinancialEventCreateDto update = new FinancialEventCreateDto(
                LocalDate.now(), UUID.fromString(catId), EventType.EXPENSE,
                BigDecimal.valueOf(1800), null, null, null, null);

        mockMvc.perform(put("/api/v1/events/" + eventId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PLANNED"))
                .andExpect(jsonPath("$.plannedAmount").value(1800));
    }

    @Test
    void createLinkedFact_success() throws Exception {
        String catId = getFirstCategoryId();

        // Создаём PLAN-событие
        FinancialEventCreateDto planDto = new FinancialEventCreateDto(
                LocalDate.now(), UUID.fromString(catId), EventType.EXPENSE,
                BigDecimal.valueOf(5000), null, "Плановый расход", null, null);

        String planBody = mockMvc.perform(post("/api/v1/events")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(planDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventKind").value("PLAN"))
                .andReturn().getResponse().getContentAsString();

        String planId = objectMapper.readTree(planBody).get("id").asText();

        // Создаём связанный FACT
        FactCreateDto factDto = new FactCreateDto(LocalDate.now(), BigDecimal.valueOf(4850), "Фактический расход", null);

        mockMvc.perform(post("/api/v1/events/" + planId + "/facts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(factDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventKind").value("FACT"))
                .andExpect(jsonPath("$.parentEventId").value(planId))
                .andExpect(jsonPath("$.factAmount").value(4850));

        // Проверяем, что план теперь EXECUTED
        String today = LocalDate.now().toString();
        mockMvc.perform(get("/api/v1/events?startDate=" + today + "&endDate=" + today))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + planId + "')].status").value("EXECUTED"));
    }

    @Test
    void createLinkedFact_withPriority_persistsPriority() throws Exception {
        String catId = getFirstCategoryId();
        FinancialEventCreateDto planDto = new FinancialEventCreateDto(
                LocalDate.now(), UUID.fromString(catId), EventType.EXPENSE,
                BigDecimal.valueOf(5000), null, "Тест приоритета", null, null);

        String planBody = mockMvc.perform(post("/api/v1/events")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(planDto)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String planId = objectMapper.readTree(planBody).get("id").asText();

        FactCreateDto factDto = new FactCreateDto(LocalDate.now(), BigDecimal.valueOf(5000), null,
                Priority.LOW);

        mockMvc.perform(post("/api/v1/events/" + planId + "/facts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(factDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priority").value("LOW"));
    }

    @Test
    void deletePlanWithLinkedFacts_returns409() throws Exception {
        String catId = getFirstCategoryId();

        // Создаём PLAN-событие
        FinancialEventCreateDto planDto = new FinancialEventCreateDto(
                LocalDate.now(), UUID.fromString(catId), EventType.EXPENSE,
                BigDecimal.valueOf(3000), null, "Удаляемый план", null, null);

        String planBody = mockMvc.perform(post("/api/v1/events")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(planDto)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String planId = objectMapper.readTree(planBody).get("id").asText();

        // Привязываем факт к плану
        FactCreateDto factDto = new FactCreateDto(LocalDate.now(), BigDecimal.valueOf(2900), null, null);

        mockMvc.perform(post("/api/v1/events/" + planId + "/facts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(factDto)))
                .andExpect(status().isOk());

        // Попытка удалить план с привязанными фактами → 409
        mockMvc.perform(delete("/api/v1/events/" + planId))
                .andExpect(status().isConflict());
    }

    @Test
    void deleteEvent_softDelete() throws Exception {
        String catId = getFirstCategoryId();

        String created = mockMvc.perform(post("/api/v1/events")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new FinancialEventCreateDto(
                        LocalDate.now(), UUID.fromString(catId), EventType.EXPENSE,
                        BigDecimal.valueOf(300), null, "Удал.", null, null))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String id = objectMapper.readTree(created).get("id").asText();

        // Soft delete
        mockMvc.perform(delete("/api/v1/events/" + id))
                .andExpect(status().isNoContent());

        // Событие не должно появляться в списке
        String today = LocalDate.now().toString();
        mockMvc.perform(get("/api/v1/events?startDate=" + today + "&endDate=" + today))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + id + "')]").doesNotExist());
    }
}
