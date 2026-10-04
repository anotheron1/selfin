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

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ANO-215: план покупки из зафиксированной хотелки — бронь. Дата прошла, факта нет — кармашек
 * держит его, как платёж по кредиту, а не отпускает, как прогноз.
 *
 * <p>Конверсия принимает только дату в будущем, поэтому «прошло время» здесь — сдвиг даты
 * созданного плана назад, подготовкой данных. Всё остальное — через API, как экран.
 *
 * <p>Спека: {@code docs/superpowers/specs/2026-10-04-converted-purchase-booking-design.md}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class ConvertedPurchaseBookingIT {

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
    @DisplayName("ANO-215: план из зафиксированной хотелки с прошедшей датой кармашек держит бронью")
    void convertedPurchase_pastDate_isHeldAsBooking() throws Exception {
        checkpoint(today.minusDays(10), 100_000);
        UUID wish = wishlist("Ноутбук IT", 30_000, today.plusDays(1));
        setWishlistStatus(wish, "FIXED");
        UUID plan = convertToPlan(wish);
        // Прошли два дня, покупка не записана.
        jdbc.update("UPDATE financial_events SET date = ? WHERE id = ?::uuid", today.minusDays(2), plan.toString());

        JsonNode pocket = getJson("/api/v1/pocket");

        assertThat(pocket.get("pocket").decimalValue()).as("100 000 минус решённая покупка")
                .isEqualByComparingTo("70000");
        JsonNode held = null;
        for (JsonNode u : pocket.get("upcoming")) {
            if (plan.toString().equals(u.get("id").asText())) held = u;
        }
        assertThat(held).as("в «раньше по плану»").isNotNull();
        assertThat(held.get("overdue").asBoolean()).isTrue();
        assertThat(held.get("priority").asText()).isEqualTo("HIGH");
    }

    // ── API, как им пользуется фронт ────────────────────────────────────────

    private JsonNode getJson(String url) throws Exception {
        String json = mockMvc.perform(get(url))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json);
    }

    private void checkpoint(LocalDate date, long amount) throws Exception {
        mockMvc.perform(post("/api/v1/balance-checkpoints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("date", date.toString(), "amount", amount))))
                .andExpect(status().isCreated());
    }

    private UUID wishlist(String description, long amount, LocalDate date) throws Exception {
        String json = mockMvc.perform(post("/api/v1/events/wishlist")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "description", description, "plannedAmount", amount, "date", date.toString()))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(json).get("id").asText());
    }

    private void setWishlistStatus(UUID id, String status) throws Exception {
        mockMvc.perform(patch("/api/v1/events/{id}/wishlist-status", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", status))))
                .andExpect(status().is2xxSuccessful());
    }

    /** Конверсия в плановое событие; возвращает id созданного плана. */
    private UUID convertToPlan(UUID id) throws Exception {
        String json = mockMvc.perform(post("/api/v1/wishlist/items/{id}/convert", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "sourceKind", "WISHLIST", "target", "PLAN_EVENT"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(json).get("convertedTo").get("id").asText());
    }
}
