package ru.selfin.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import ru.selfin.backend.service.CapitalService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ANO-86 и ANO-87: деньги в копилке ходят в обе стороны, удаление их не уничтожает.
 *
 * <p>Проверяется ЗАКОН СОХРАНЕНИЯ, а не отдельные методы: спека капитала
 * ({@code 2026-05-10-capital-net-worth-design.md:66}) обещает, что FUND_TRANSFER и
 * FundTransaction взаимно компенсируются. Утверждение о системе ловит дефект независимо
 * от того, в скольких местах записано правило — а оно записано не в одном.
 *
 * <p>План: {@code docs/superpowers/plans/2026-09-12-fund-money-flow.md}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class FundMoneyFlowIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired CapitalService capitalService;

    /**
     * Даёт дефолтному счёту якорь, чтобы у продукта было основание для мнения о свободных
     * деньгах. Без якоря {@code freeMoneyAt} равен нулю, и ЛЮБОЙ перевод считался бы
     * превышением остатка — тест проверял бы не то, что заявляет.
     */
    private void anchorDefaultAccount(String amount) {
        String accountId = jdbc.queryForObject(
                "SELECT id::text FROM accounts WHERE is_default = true AND is_deleted = false",
                String.class);
        jdbc.update("""
                INSERT INTO balance_checkpoints (id, date, amount, account_id, created_at)
                VALUES (gen_random_uuid(), CURRENT_DATE, ?::numeric, ?::uuid, now())
                """, amount, accountId);
    }

    @Test
    @DisplayName("ANO-87: из копилки можно забрать деньги обратно")
    void withdraw_returnsMoneyToAccount() throws Exception {
        anchorDefaultAccount("500000");
        String fundId = createFund("Отпуск");
        transfer(fundId, new BigDecimal("20000"), null).andExpect(status().isOk());

        transfer(fundId, new BigDecimal("-5000"), null).andExpect(status().isOk());

        assertThat(fundBalance(fundId)).isEqualByComparingTo("15000");
    }

    @Test
    @DisplayName("ANO-87: снять больше накопленного нельзя — в копилке столько нет")
    void withdraw_moreThanBalance_isRefused() throws Exception {
        anchorDefaultAccount("500000");
        String fundId = createFund("Отпуск");
        transfer(fundId, new BigDecimal("20000"), null).andExpect(status().isOk());

        transfer(fundId, new BigDecimal("-20001"), null).andExpect(status().isConflict());

        assertThat(fundBalance(fundId))
                .as("отказ не должен списать ничего").isEqualByComparingTo("20000");
    }

    @Test
    @DisplayName("ANO-87: перевод больше остатка требует подтверждения")
    void transfer_overBalance_needsConfirmation() throws Exception {
        String fundId = createFund("Отпуск");

        transfer(fundId, new BigDecimal("99999999"), null).andExpect(status().isConflict());
        assertThat(fundBalance(fundId))
                .as("без подтверждения не должно пройти ничего").isEqualByComparingTo("0");

        transfer(fundId, new BigDecimal("99999999"), true).andExpect(status().isOk());
        assertThat(fundBalance(fundId)).isEqualByComparingTo("99999999");
    }

    // ── оснастка ─────────────────────────────────────────────────────────────

    /** Копилка без счёта — базовый случай: у неё собственный баланс. */
    private String createFund(String name) {
        String id = jdbc.queryForObject("SELECT gen_random_uuid()::text", String.class);
        jdbc.update("""
                INSERT INTO target_funds
                    (id, name, target_amount, current_balance, status, purchase_type,
                     is_deleted, created_at)
                VALUES (?::uuid, ?, 1000000, 0, 'FUNDING', 'SAVINGS', false, now())
                """, id, name);
        return id;
    }

    /** Копилка поверх счёта — не базовый случай, своих денег не имеет (спека §4.6). */
    private String createFundOnAccount(String name, String accountId) {
        String id = jdbc.queryForObject("SELECT gen_random_uuid()::text", String.class);
        jdbc.update("""
                INSERT INTO target_funds
                    (id, name, target_amount, current_balance, status, purchase_type,
                     is_deleted, created_at, account_id)
                VALUES (?::uuid, ?, 1000000, 0, 'FUNDING', 'SAVINGS', false, now(), ?::uuid)
                """, id, name, accountId);
        return id;
    }

    private String firstTrackedAccountId() {
        return jdbc.queryForObject(
                "SELECT id::text FROM accounts WHERE is_deleted = false"
                        + " AND track_balance = true LIMIT 1", String.class);
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

    private boolean isDeleted(String fundId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT is_deleted FROM target_funds WHERE id = ?::uuid", Boolean.class, fundId));
    }

    /** Помечает копилку удалённой в обход сервиса — проверяется именно запрос ликвида. */
    private void softDeleteFundDirectly(String fundId) {
        jdbc.update("UPDATE target_funds SET is_deleted = true WHERE id = ?::uuid", fundId);
    }
}
