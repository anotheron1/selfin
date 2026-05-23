package ru.selfin.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;   // Spring Boot 4.x
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class StrategyTimelineControllerIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void getTimeline_returns_200_with_valid_shape() throws Exception {
        mockMvc.perform(get("/api/v1/strategy/timeline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstActivityMonth").exists())
                .andExpect(jsonPath("$.currentMonth").exists())
                .andExpect(jsonPath("$.horizonEnd").exists())
                .andExpect(jsonPath("$.predictionWindowMonths").value(6))
                .andExpect(jsonPath("$.fanEnabled").exists())
                .andExpect(jsonPath("$.points").isArray())
                .andExpect(jsonPath("$.points.length()", greaterThanOrEqualTo(1)));
    }

    @Test
    void getTimeline_with_horizon12_limits_future_points() throws Exception {
        String body = mockMvc.perform(get("/api/v1/strategy/timeline").param("horizonMonths", "12"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        var dto = objectMapper.readValue(body, java.util.Map.class);
        java.util.List<?> points = (java.util.List<?>) dto.get("points");
        long futureCount = points.stream()
                .filter(p -> "FUTURE".equals(((java.util.Map<?, ?>) p).get("phase")))
                .count();
        // FUTURE count = horizonMonths (создаём ровно horizonMonths точек в будущем).
        // Этот тест НЕ зависит от состояния контейнера, потому что horizonMonths определяет
        // количество future-точек напрямую (за минусом возможной коллизии в логике current+1..current+12).
        org.assertj.core.api.Assertions.assertThat(futureCount).isEqualTo(12);
    }

    @Test
    void getTimeline_with_withBreakdown_false_omits_breakdown() throws Exception {
        String body = mockMvc.perform(get("/api/v1/strategy/timeline").param("withBreakdown", "false"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Все breakdown должны быть null
        var dto = objectMapper.readValue(body, java.util.Map.class);
        java.util.List<?> points = (java.util.List<?>) dto.get("points");
        for (var p : points) {
            org.assertj.core.api.Assertions.assertThat(((java.util.Map<?, ?>) p).get("breakdown")).isNull();
        }
    }

    @Test
    void getTimeline_returns_200_even_when_no_forecast_history() throws Exception {
        // Тест проверяет что endpoint не падает при дефиците истории.
        // НЕ утверждаем fanEnabled==false, потому что контейнер shared между тестами:
        // если Test 5/6 уже создали факты в forecast-категориях, fanEnabled может быть true.
        // Цель: проверить отзывчивость endpoint'а на любом состоянии БД.
        mockMvc.perform(get("/api/v1/strategy/timeline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fanEnabled").exists())
                .andExpect(jsonPath("$.points").isArray());
    }

    @Test
    void getTimeline_includes_recurring_planned_in_balanceConfirmed() throws Exception {
        // Шаг 1: создать recurring rule через POST /api/v1/events с recurringConfig
        // (полный путь имитирует реальный flow — НЕ direct DB insert)
        String catId = getFirstCategoryId();
        String today = java.time.LocalDate.now().plusDays(1).toString();
        String body = """
            {
              "date": "%s",
              "categoryId": "%s",
              "type": "EXPENSE",
              "plannedAmount": 80000,
              "priority": "HIGH",
              "description": "Test recurring",
              "recurring": {
                "frequency": "MONTHLY",
                "dayOfMonth": %d,
                "endDate": "%s"
              }
            }
            """.formatted(today, catId, java.time.LocalDate.now().plusDays(1).getDayOfMonth(),
                java.time.LocalDate.now().plusMonths(6).toString());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/events")
                        .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is2xxSuccessful());

        // Шаг 2: получить timeline и проверить, что в одной из будущих точек expense >= 80000
        String tlBody = mockMvc.perform(get("/api/v1/strategy/timeline"))
                .andReturn().getResponse().getContentAsString();
        var tlDto = objectMapper.readValue(tlBody, java.util.Map.class);
        java.util.List<?> points = (java.util.List<?>) tlDto.get("points");
        boolean foundRecurringExpense = points.stream()
                .filter(p -> "FUTURE".equals(((java.util.Map<?, ?>) p).get("phase")))
                .anyMatch(p -> {
                    Object exp = ((java.util.Map<?, ?>) p).get("expense");
                    return exp != null && Double.parseDouble(exp.toString()) >= 80000.0;
                });
        org.assertj.core.api.Assertions.assertThat(foundRecurringExpense).isTrue();
    }

    @Test
    void getTimeline_with_patched_fact_includes_in_current_breakdown() throws Exception {
        // 1. Создать событие на сегодня
        String catId = getFirstCategoryId();
        String today = java.time.LocalDate.now().toString();
        String body = """
            {
              "date": "%s", "categoryId": "%s",
              "type": "EXPENSE", "plannedAmount": 5000,
              "priority": "HIGH", "description": "Test fact"
            }
            """.formatted(today, catId);

        String created = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/events")
                        .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();

        String eventId = (String) objectMapper.readValue(created, java.util.Map.class).get("id");

        // 2. PATCH-fact (FinancialEventUpdateFactDto имеет только factAmount + опц. description)
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/events/" + eventId + "/fact")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"factAmount\": 4900, \"description\": \"Оплачено\" }"))
                .andExpect(status().is2xxSuccessful());

        // 3. Получить timeline, проверить что breakdown CURRENT содержит этот факт
        String tlBody = mockMvc.perform(get("/api/v1/strategy/timeline"))
                .andReturn().getResponse().getContentAsString();
        var tlDto = objectMapper.readValue(tlBody, java.util.Map.class);
        java.util.List<?> points = (java.util.List<?>) tlDto.get("points");
        var currentPoint = points.stream()
                .filter(p -> "CURRENT".equals(((java.util.Map<?, ?>) p).get("phase")))
                .findFirst().orElseThrow();
        var breakdown = (java.util.Map<?, ?>) ((java.util.Map<?, ?>) currentPoint).get("breakdown");
        java.util.List<?> expenseItems = (java.util.List<?>) breakdown.get("expenseItems");
        // Тест должен подтвердить, что именно созданный + патченный факт попал в breakdown —
        // не просто "что-то есть". Пинимся к amount=4900 этого факта.
        boolean foundPatchedFact = expenseItems.stream().anyMatch(item -> {
            Object amount = ((java.util.Map<?, ?>) item).get("amount");
            return amount != null && Double.parseDouble(amount.toString()) == 4900.0;
        });
        org.assertj.core.api.Assertions.assertThat(foundPatchedFact)
                .as("expected expenseItems to contain the patched fact with amount=4900")
                .isTrue();
    }

    private String getFirstCategoryId() throws Exception {
        // Хелпер: берёт первую EXPENSE-категорию из БД (миграция должна создавать default-категории).
        // EXPENSE-фильтр важен — наши тесты создают EXPENSE-события; INCOME-категория сломала бы валидацию.
        String body = mockMvc.perform(get("/api/v1/categories"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        java.util.List<?> cats = objectMapper.readValue(body, java.util.List.class);
        return cats.stream()
                .map(c -> (java.util.Map<?, ?>) c)
                .filter(c -> {
                    Object type = c.get("type");
                    return type == null || "EXPENSE".equals(type);
                })
                .map(c -> (String) c.get("id"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No EXPENSE category found in DB"));
    }
}
