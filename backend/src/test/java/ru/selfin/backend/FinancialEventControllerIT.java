package ru.selfin.backend;

import com.fasterxml.jackson.databind.JsonNode;
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

import static org.assertj.core.api.Assertions.assertThat;
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
                BigDecimal.valueOf(1000), null, "Тестовый расход", null, null, null);

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
                BigDecimal.valueOf(500), null, null, null, null, null);

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
                BigDecimal.valueOf(2000), null, null, null, null, null);

        String created = mockMvc.perform(post("/api/v1/events")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(create)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String eventId = objectMapper.readTree(created).get("id").asText();

        FinancialEventCreateDto update = new FinancialEventCreateDto(
                LocalDate.now(), UUID.fromString(catId), EventType.EXPENSE,
                BigDecimal.valueOf(1800), null, null, null, null, null);

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
                BigDecimal.valueOf(5000), null, "Плановый расход", null, null, null);

        String planBody = mockMvc.perform(post("/api/v1/events")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(planDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventKind").value("PLAN"))
                .andReturn().getResponse().getContentAsString();

        String planId = objectMapper.readTree(planBody).get("id").asText();

        // Создаём связанный FACT
        FactCreateDto factDto = new FactCreateDto(LocalDate.now(), BigDecimal.valueOf(4850), "Фактический расход", null, null);

        mockMvc.perform(post("/api/v1/events/" + planId + "/facts").header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(factDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventKind").value("FACT"))
                .andExpect(jsonPath("$.parentEventId").value(planId))
                .andExpect(jsonPath("$.factAmount").value(4850));

        // ANO-155: 4 850 из 5 000 обязательство не закрывают — план остаётся PLANNED
        // с остатком 150. Статус значит «погашен полностью», а не «тронут»: раньше его
        // ставил первый же факт, и чек на 300 снимал резерв продуктов на 20 000.
        String today = LocalDate.now().toString();
        mockMvc.perform(get("/api/v1/events?startDate=" + today + "&endDate=" + today))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + planId + "')].status").value("PLANNED"));
    }

    @Test
    void createLinkedFact_withPriority_persistsPriority() throws Exception {
        String catId = getFirstCategoryId();
        FinancialEventCreateDto planDto = new FinancialEventCreateDto(
                LocalDate.now(), UUID.fromString(catId), EventType.EXPENSE,
                BigDecimal.valueOf(5000), null, "Тест приоритета", null, null, null);

        String planBody = mockMvc.perform(post("/api/v1/events")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(planDto)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String planId = objectMapper.readTree(planBody).get("id").asText();

        FactCreateDto factDto = new FactCreateDto(LocalDate.now(), BigDecimal.valueOf(5000), null,
                Priority.LOW, null);

        mockMvc.perform(post("/api/v1/events/" + planId + "/facts").header("Idempotency-Key", UUID.randomUUID().toString())
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
                BigDecimal.valueOf(3000), null, "Удаляемый план", null, null, null);

        String planBody = mockMvc.perform(post("/api/v1/events")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(planDto)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String planId = objectMapper.readTree(planBody).get("id").asText();

        // Привязываем факт к плану
        FactCreateDto factDto = new FactCreateDto(LocalDate.now(), BigDecimal.valueOf(2900), null, null, null);

        mockMvc.perform(post("/api/v1/events/" + planId + "/facts").header("Idempotency-Key", UUID.randomUUID().toString())
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
                        BigDecimal.valueOf(300), null, "Удал.", null, null, null))))
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

    // ── ANO-183: характер строки с экрана «Хотелки» не меняется ──
    //
    // Инвариант I1 спеки 29.05 держала только база: запись упиралась в chk_wishlist_status_only_low,
    // и наружу уходило 409 «Operation conflicts with a data constraint», по которому экран не может
    // сказать, в чём дело. Теперь сервис отказывает 400 до записи, а ответ /events говорит журналу,
    // какая строка — хотелка с экрана «Хотелки».

    /** Хотелка со сроком — та, что видна в журнале; ручкой экрана «Хотелки». */
    private JsonNode createDatedWishlistItem(String description, LocalDate date) throws Exception {
        String body = mockMvc.perform(post("/api/v1/events/wishlist")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "description", description, "plannedAmount", 49504,
                                "date", date.toString()))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    /** Строка журнала за день — так, как её получает экран. */
    private JsonNode journalRow(String id, LocalDate date) throws Exception {
        String body = mockMvc.perform(get("/api/v1/events?startDate=" + date + "&endDate=" + date))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        for (JsonNode row : objectMapper.readTree(body)) {
            if (id.equals(row.get("id").asText())) return row;
        }
        throw new AssertionError("строки " + id + " нет в журнале за " + date);
    }

    private String createPlan(LocalDate date, Priority priority, String description) throws Exception {
        String body = mockMvc.perform(post("/api/v1/events")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new FinancialEventCreateDto(
                                date, UUID.fromString(getFirstCategoryId()), EventType.EXPENSE,
                                BigDecimal.valueOf(700), priority, description, null, null, null))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    @Test
    void cyclePriority_wishlistRow_returns400_andRowStaysWishlist() throws Exception {
        LocalDate date = LocalDate.now().plusDays(3);
        String id = createDatedWishlistItem("Проба ANO-183 точка", date).get("id").asText();

        mockMvc.perform(patch("/api/v1/events/" + id + "/priority"))
                .andExpect(status().isBadRequest());

        assertThat(journalRow(id, date).get("priority").asText()).isEqualTo("LOW");
        mockMvc.perform(get("/api/v1/wishlist/simulation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id == '" + id + "')].status").value("OPEN"));

        // Граница: обычная строка характера «Хотелка» меняется точкой, как прежде.
        String plainId = createPlan(date, Priority.LOW, "Обычная хотелка журнала");
        mockMvc.perform(patch("/api/v1/events/" + plainId + "/priority"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priority").value("HIGH"));
    }

    @Test
    void updateEvent_wishlistRow_otherCharacter_returns400_sameCharacter_saves() throws Exception {
        LocalDate date = LocalDate.now().plusDays(4);
        JsonNode created = createDatedWishlistItem("Проба ANO-183 форма", date);
        String id = created.get("id").asText();
        UUID categoryId = UUID.fromString(created.get("categoryId").asText());

        mockMvc.perform(put("/api/v1/events/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new FinancialEventCreateDto(
                                date, categoryId, EventType.EXPENSE, BigDecimal.valueOf(49504),
                                Priority.HIGH, "Проба ANO-183 форма", null, null, null))))
                .andExpect(status().isBadRequest());
        assertThat(journalRow(id, date).get("priority").asText()).isEqualTo("LOW");

        // Граница: правка суммы с прежним характером проходит — форма шлёт его всегда.
        mockMvc.perform(put("/api/v1/events/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new FinancialEventCreateDto(
                                date, categoryId, EventType.EXPENSE, BigDecimal.valueOf(50000),
                                Priority.LOW, "Проба ANO-183 форма", null, null, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plannedAmount").value(50000))
                .andExpect(jsonPath("$.priority").value("LOW"));
    }

    @Test
    void getByPeriod_tellsWishlistRowFromRegularOne() throws Exception {
        LocalDate date = LocalDate.now().plusDays(5);
        String wishId = createDatedWishlistItem("Проба ANO-183 журнал", date).get("id").asText();
        String plainId = createPlan(date, Priority.LOW, "Обычная хотелка журнала");

        assertThat(journalRow(wishId, date).path("wishlistStatus").asText()).isEqualTo("OPEN");
        JsonNode plainStatus = journalRow(plainId, date).path("wishlistStatus");
        assertThat(plainStatus.isNull() || plainStatus.isMissingNode())
                .as("у обычной строки характера «Хотелка» статуса хотелки нет: %s", plainStatus)
                .isTrue();
    }
}
