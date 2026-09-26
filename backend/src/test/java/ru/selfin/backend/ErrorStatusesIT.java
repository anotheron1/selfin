package ru.selfin.backend;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ANO-85: ошибка клиента — не 500. Иначе 500 перестаёт быть сигналом поломки: забытый параметр
 * и настоящее падение выглядят одинаково — {@code "Internal server error"}.
 *
 * <p>Через настоящие ручки, ровно те запросы, что дали 500 на стенде 26.09: проверяется правило
 * в собранном приложении, а не обработчик сам по себе.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class ErrorStatusesIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired MockMvc mockMvc;

    @Test
    @DisplayName("ANO-85: нет обязательного параметра — 400")
    void missingParameter_400() throws Exception {
        expect(mockMvc.perform(get("/api/v1/analytics/multi-month")), 400);
    }

    @Test
    @DisplayName("ANO-85: параметр не той формы (дата) — 400")
    void malformedDateParameter_400() throws Exception {
        expect(mockMvc.perform(get("/api/v1/analytics/multi-month")
                .param("startDate", "недата").param("endDate", "2026-09-30")), 400);
    }

    @Test
    @DisplayName("ANO-85: Idempotency-Key не UUID — 400")
    void malformedIdempotencyKey_400() throws Exception {
        expect(mockMvc.perform(post("/api/v1/events")
                .header("Idempotency-Key", "not-a-uuid")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"date\":\"2026-10-01\",\"type\":\"EXPENSE\",\"plannedAmount\":1}")), 400);
    }

    @Test
    @DisplayName("ANO-85: id в пути не UUID — 400")
    void malformedPathId_400() throws Exception {
        expect(mockMvc.perform(post("/api/v1/wishlist/items/{id}/convert", "undefined")
                .contentType(MediaType.APPLICATION_JSON).content("{}")), 400);
        expect(mockMvc.perform(put("/api/v1/events/{id}", "not-a-uuid")
                .contentType(MediaType.APPLICATION_JSON).content("{}")), 400);
    }

    @Test
    @DisplayName("ANO-85: нет такого пути — 404")
    void unknownPath_404() throws Exception {
        expect(mockMvc.perform(get("/api/v1/analytics/nonexistent")), 404);
    }

    @Test
    @DisplayName("ANO-85: путь есть, метода нет — 405")
    void unsupportedMethod_405() throws Exception {
        expect(mockMvc.perform(delete("/api/v1/analytics/dashboard")), 405);
    }

    @Test
    @DisplayName("ANO-85: тело не того типа — 415")
    void unsupportedMediaType_415() throws Exception {
        expect(mockMvc.perform(post("/api/v1/funds")
                .contentType(MediaType.TEXT_PLAIN).content("x")), 415);
    }

    /** Статус и тело согласны, и тело не выдаёт клиентскую ошибку за поломку сервера. */
    private static void expect(ResultActions r, int code) throws Exception {
        r.andExpect(status().is(code))
                .andExpect(jsonPath("$.status").value(code))
                .andExpect(jsonPath("$.message").value(not("Internal server error")));
    }
}
