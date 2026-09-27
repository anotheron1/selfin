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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ANO-95: виновник дня минимума называется категорией, когда у крупнейшего расхода нет описания.
 *
 * <p>На стенде 27.09 плашка разрыва говорила «12 октября — ожидается дефицит 122 212 ₽» и молчала
 * о причине: в тот день безымянный расход на 8 000 («Продукты») и «Бензин» на 3 300. Движок видит
 * только описание; категорию подставляет сервис по id строки — через настоящую базу это и
 * проверяется: связь с категорией ленивая.
 *
 * <p>Спека: {@code docs/superpowers/specs/2026-09-27-min-point-culprit-category-design.md}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class PocketCulpritIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;

    private static final LocalDate DAY = LocalDate.now().plusDays(10);

    /** Контейнер один на класс: строки одного теста сдвинули бы минимум соседнего. */
    @BeforeEach
    void resetEvents() {
        jdbc.update("DELETE FROM financial_events");
    }

    @Test
    @DisplayName("крупнейший расход дня минимума без описания — виновник назван категорией")
    void unnamedCulprit_isNamedByCategory() throws Exception {
        String produkty = createCategory("Продукты ANO-95");
        String auto = createCategory("Авто ANO-95");
        createPlan(produkty, 8_000, null);
        createPlan(auto, 3_300, "Бензин");

        JsonNode min = pocket().get("minPoint");

        assertThat(min.get("date").asText()).isEqualTo(DAY.toString());
        assertThat(min.get("drivenBy").asText()).isEqualTo("Продукты ANO-95");
    }

    @Test
    @DisplayName("у крупнейшего расхода описание есть — виновник назван описанием")
    void namedCulprit_keepsDescription() throws Exception {
        String auto = createCategory("Авто-2 ANO-95");
        createPlan(auto, 8_000, "Страховка");
        createPlan(auto, 3_300, "Бензин");

        assertThat(pocket().get("minPoint").get("drivenBy").asText()).isEqualTo("Страховка");
    }

    // ── оснастка ────────────────────────────────────────────────────────────

    /** Горизонт — до следующего дня после {@link #DAY}: минимум не зависит от доходов в базе. */
    private JsonNode pocket() throws Exception {
        String body = mockMvc.perform(get("/api/v1/pocket").param("scope", "DATE:" + DAY.plusDays(1)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private String createCategory(String name) throws Exception {
        String body = mockMvc.perform(post("/api/v1/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"%s\", \"type\": \"EXPENSE\"}".formatted(name)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    private void createPlan(String categoryId, long amount, String description) throws Exception {
        String desc = description == null ? "" : ", \"description\": \"%s\"".formatted(description);
        mockMvc.perform(post("/api/v1/events")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"date": "%s", "categoryId": "%s", "type": "EXPENSE",
                                 "plannedAmount": %d, "priority": "HIGH"%s}
                                """.formatted(DAY, categoryId, amount, desc)))
                .andExpect(status().isOk());
    }
}
