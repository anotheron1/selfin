package ru.selfin.backend;

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

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ANO-157: мнение только при основаниях.
 *
 * <p>Человек, ничего не вводивший, не мог отложить деньги в копилку: свободные деньги равны
 * нулю не потому, что их нет, а потому что продукт не знает — и этот ноль читался как запрет.
 *
 * <p>Здесь же закреплено, что при основаниях ничего не изменилось: ANO-87 обещает
 * предупреждение с подтверждением, и оно остаётся. И что два разных 409 различимы: тот,
 * который можно подтвердить, несёт код, а безусловный — нет.
 *
 * <p>Спека: {@code docs/superpowers/specs/2026-09-13-empty-state-transfer-design.md}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class EmptyStateTransferIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;

    /**
     * Возвращает денежное состояние к нулю перед каждым тестом — то есть к состоянию нового
     * пользователя. Контейнер один на класс, и без уборки тесты протекают друг в друга
     * (на этом уже спотыкались в {@link FundMoneyFlowIT}).
     */
    @BeforeEach
    void resetMoneyState() {
        jdbc.update("DELETE FROM fund_transactions");
        jdbc.update("DELETE FROM financial_events WHERE type = 'FUND_TRANSFER'");
        jdbc.update("DELETE FROM target_funds");
        jdbc.update("DELETE FROM balance_checkpoints");
    }

    @Test
    @DisplayName("ANO-157: без единого введённого числа перевод в копилку проходит молча")
    void transferOnEmptyState_passesWithoutConfirmation() throws Exception {
        assertThat(hasAnyBasis())
                .as("тест бесполезен, если основания всё-таки есть — проверяем пустоту явно")
                .isFalse();
        String fundId = createFund("Отпуск");

        transfer(fundId, new BigDecimal("5000"), null).andExpect(status().isOk());

        assertThat(fundBalance(fundId))
                .as("оснований судить об остатке нет — значит нет и мнения")
                .isEqualByComparingTo("5000");
    }

    @Test
    @DisplayName("ANO-157: якорь введён — предупреждение возвращается и несёт код")
    void transferOverBalance_withAnchor_isConfirmable() throws Exception {
        anchorDefaultAccount("1000");
        String fundId = createFund("Отпуск");

        transfer(fundId, new BigDecimal("5000"), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details[0]").value("CONFIRM_REQUIRED"));

        transfer(fundId, new BigDecimal("5000"), true).andExpect(status().isOk());

        assertThat(fundBalance(fundId))
                .as("подтверждение обязано пропускать — это не запрет, а вопрос")
                .isEqualByComparingTo("5000");
    }

    @Test
    @DisplayName("ANO-87: снять больше накопленного — отказ без кода, подтверждать нечего")
    void withdrawOverFundBalance_isNotConfirmable() throws Exception {
        String fundId = createFund("Отпуск");
        transfer(fundId, new BigDecimal("5000"), null).andExpect(status().isOk());

        transfer(fundId, new BigDecimal("-5001"), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details").isEmpty());

        assertThat(fundBalance(fundId))
                .as("отказ не должен списать ничего").isEqualByComparingTo("5000");
    }

    // ── оснастка ─────────────────────────────────────────────────────────────

    /** Есть ли у продукта хоть какое-то основание судить об остатке (ANO-157). */
    private boolean hasAnyBasis() {
        Integer anchors = jdbc.queryForObject("SELECT count(*) FROM balance_checkpoints", Integer.class);
        Integer facts = jdbc.queryForObject(
                "SELECT count(*) FROM financial_events WHERE is_deleted = false"
                        + " AND fact_amount IS NOT NULL AND wishlist_status IS NULL", Integer.class);
        return (anchors != null && anchors > 0) || (facts != null && facts > 0);
    }

    /** Якорь ВЧЕРАШНИМ днём: при якоре «сегодня» переводы ведут себя иначе (ANO-125). */
    private void anchorDefaultAccount(String amount) {
        String accountId = jdbc.queryForObject(
                "SELECT id::text FROM accounts WHERE is_default = true AND is_deleted = false",
                String.class);
        jdbc.update("""
                INSERT INTO balance_checkpoints (id, date, amount, account_id, created_at)
                VALUES (gen_random_uuid(), CURRENT_DATE - 1, ?::numeric, ?::uuid, now())
                """, amount, accountId);
    }

    /** Копилка без счёта — базовый случай: у неё собственный баланс и перевод в неё разрешён. */
    private String createFund(String name) {
        String id = jdbc.queryForObject("SELECT gen_random_uuid()::text", String.class);
        jdbc.update("""
                INSERT INTO target_funds
                    (id, name, target_amount, current_balance, status, purchase_type,
                     priority, is_deleted, created_at)
                VALUES (?::uuid, ?, 1000000, 0, 'FUNDING', 'SAVINGS', 100, false, now())
                """, id, name);
        return id;
    }

    /** @param confirm {@code null} — без подтверждения */
    private ResultActions transfer(String fundId, BigDecimal amount, Boolean confirm)
            throws Exception {
        String body = confirm == null
                ? "{\"amount\": " + amount.toPlainString() + "}"
                : "{\"amount\": " + amount.toPlainString() + ", \"confirm\": " + confirm + "}";
        return mockMvc.perform(post("/api/v1/funds/{id}/transfer", fundId)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private BigDecimal fundBalance(String fundId) {
        return jdbc.queryForObject(
                "SELECT current_balance FROM target_funds WHERE id = ?::uuid",
                BigDecimal.class, fundId);
    }
}
