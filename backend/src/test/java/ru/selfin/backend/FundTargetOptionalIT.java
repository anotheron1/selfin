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
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ANO-199: цель копилки необязательна, и «Цель достигнута» выводится из накопленного и цели
 * при любой их смене.
 *
 * <p>Поле формы подписано «необязательно» с 16.03 — вместе со спекой планировщика, где копилка
 * без цели описана явно, — а сервер требовал цель с 08.03. Обход «цель 0» закрывал копилку на
 * первом же взносе, а правка цели статус не пересчитывала: «Цель достигнута» при 50%.
 *
 * <p>Спека: {@code docs/superpowers/specs/2026-09-27-fund-target-optional-design.md}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class FundTargetOptionalIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;

    /** Контейнер один на класс: без уборки копилки одного теста видны соседнему. */
    @BeforeEach
    void resetFunds() {
        jdbc.update("DELETE FROM fund_transactions");
        jdbc.update("DELETE FROM financial_events WHERE type = 'FUND_TRANSFER'");
        jdbc.update("DELETE FROM fund_account_links");
        jdbc.update("DELETE FROM target_funds");
    }

    @Test
    @DisplayName("копилка без цели создаётся: цели нет, статус «копится»")
    void createWithoutTarget() throws Exception {
        String id = createFund("{\"name\": \"Подушка\"}");

        JsonNode fund = fund(id);
        assertThat(fund.get("targetAmount").isNull()).isTrue();
        assertThat(fund.get("status").asText()).isEqualTo("FUNDING");
    }

    @Test
    @DisplayName("копилка без цели принимает деньги и не становится «достигнутой»")
    void noTarget_neverReached() throws Exception {
        String id = createFund("{\"name\": \"Подушка\"}");

        transfer(id, "1000").andExpect(status().isOk());

        assertThat(fund(id).get("status").asText()).isEqualTo("FUNDING");
    }

    @Test
    @DisplayName("цель 0 — то же, что без цели: взнос 1 ₽ копилку не закрывает")
    void zeroTarget_isNoTarget() throws Exception {
        String id = createFund("{\"name\": \"Подушка\", \"targetAmount\": 0}");

        transfer(id, "1").andExpect(status().isOk());

        assertThat(fund(id).get("status").asText()).isEqualTo("FUNDING");
    }

    @Test
    @DisplayName("цель подняли выше накопленного — копилка снова копится")
    void raiseTarget_reopens() throws Exception {
        String id = createFund("{\"name\": \"Отпуск\", \"targetAmount\": 100}");
        transfer(id, "100").andExpect(status().isOk());
        assertThat(fund(id).get("status").asText()).isEqualTo("REACHED");

        updateFund(id, "{\"name\": \"Отпуск\", \"targetAmount\": 200}").andExpect(status().isOk());

        assertThat(fund(id).get("status").asText()).isEqualTo("FUNDING");
    }

    @Test
    @DisplayName("цель опустили до накопленного — «Цель достигнута»")
    void lowerTarget_reaches() throws Exception {
        String id = createFund("{\"name\": \"Отпуск\", \"targetAmount\": 200}");
        transfer(id, "100").andExpect(status().isOk());
        assertThat(fund(id).get("status").asText()).isEqualTo("FUNDING");

        updateFund(id, "{\"name\": \"Отпуск\", \"targetAmount\": 50}").andExpect(status().isOk());

        assertThat(fund(id).get("status").asText()).isEqualTo("REACHED");
    }

    @Test
    @DisplayName("цель стёрли правкой — копилка без цели и копится")
    void clearTarget() throws Exception {
        String id = createFund("{\"name\": \"Отпуск\", \"targetAmount\": 100}");
        transfer(id, "100").andExpect(status().isOk());

        updateFund(id, "{\"name\": \"Отпуск\"}").andExpect(status().isOk());

        JsonNode fund = fund(id);
        assertThat(fund.get("targetAmount").isNull()).isTrue();
        assertThat(fund.get("status").asText()).isEqualTo("FUNDING");
    }

    /**
     * У копилки на счёте поле баланса — не её деньги: остаток берётся со счёта (§3.3), и статус
     * по балансу для неё не ведётся нигде. Решать его по полю при правке значило бы решать по
     * чужому числу; как показывать ей «достигнута» — C11 (ANO-164, ANO-174).
     */
    @Test
    @DisplayName("копилка на счёте: правка цели статус по полю баланса не решает")
    void accountBackedFund_statusNotDerivedFromField() throws Exception {
        String accountId = firstTrackedAccountId();
        String id = jdbc.queryForObject("SELECT gen_random_uuid()::text", String.class);
        jdbc.update("""
                INSERT INTO target_funds
                    (id, name, target_amount, current_balance, status, purchase_type,
                     is_deleted, created_at, account_id)
                VALUES (?::uuid, 'Цель на карте', 100, 0, 'REACHED', 'SAVINGS', false, now(), ?::uuid)
                """, id, accountId);

        updateFund(id, "{\"name\": \"Цель на карте\", \"targetAmount\": 200, \"accountId\": \"%s\"}"
                .formatted(accountId)).andExpect(status().isOk());

        assertThat(fund(id).get("status").asText()).isEqualTo("REACHED");
    }

    // ── оснастка ────────────────────────────────────────────────────────────

    private String createFund(String body) throws Exception {
        String response = mockMvc.perform(post("/api/v1/funds")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asText();
    }

    private ResultActions updateFund(String id, String body) throws Exception {
        return mockMvc.perform(put("/api/v1/funds/{id}", id)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** Как кнопка «Пополнить» после «отложить всё равно»: подтверждено, без даты. */
    private ResultActions transfer(String fundId, String amount) throws Exception {
        return mockMvc.perform(post("/api/v1/funds/{id}/transfer", fundId)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": %s, \"confirm\": true}".formatted(amount)));
    }

    /** Копилка такой, какой её читает экран «Цели» — {@code GET /funds}. */
    private JsonNode fund(String id) throws Exception {
        String response = mockMvc.perform(get("/api/v1/funds"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        for (JsonNode f : objectMapper.readTree(response).get("funds")) {
            if (f.get("id").asText().equals(id)) return f;
        }
        throw new AssertionError("копилки " + id + " нет в GET /funds");
    }

    private String firstTrackedAccountId() {
        return jdbc.queryForObject(
                "SELECT id::text FROM accounts WHERE is_deleted = false"
                        + " AND track_balance = true ORDER BY is_default DESC, created_at LIMIT 1",
                String.class);
    }
}
