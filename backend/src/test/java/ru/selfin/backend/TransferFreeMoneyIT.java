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
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ANO-88, решение владельца 26.09 (вариант А): «Пополнить фонд» переспрашивает по тому же числу,
 * что карточка называет «свободно», — по кармашку, а не по остатку на счёте.
 *
 * <p>Порог — кармашек ПОСЛЕ перевода: перевод в зафиксированную копилку сам уменьшает её резерв,
 * и сравнение с кармашком «до» переспрашивало бы на плановом взносе. Считать «после» можно только
 * настоящей транзакцией — поэтому это IT: юнит на моках отката не увидит.
 *
 * <p>Спека: {@code docs/superpowers/specs/2026-09-26-transfer-free-money-design.md}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class TransferFreeMoneyIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;

    private final LocalDate today = LocalDate.now();

    @BeforeEach
    void resetMoneyState() {
        jdbc.update("DELETE FROM fund_transactions");
        jdbc.update("DELETE FROM financial_events");
        jdbc.update("DELETE FROM target_funds");
        jdbc.update("DELETE FROM balance_checkpoints");
        jdbc.update("DELETE FROM categories WHERE name LIKE 'ANO-88 %'");
    }

    @Test
    @DisplayName("ANO-88: сверх кармашка, но в пределах остатка счёта — переспрашивает и ничего не записывает")
    void transferOverPocket_asksToConfirm_andLeavesNothing() throws Exception {
        // Счёт 100 000, завтра бронь 90 000 → минимум 10 000, кармашек 10 000 (НЗ 0).
        anchorDefaultAccount("100000");
        plan("EXPENSE", today.plusDays(1), "90000");
        String fundId = createFund("Отпуск", null, null, null);

        transfer(fundId, new BigDecimal("20000"), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details[0]").value("CONFIRM_REQUIRED"));

        assertThat(fundBalance(fundId)).as("вопрос — не перевод: копилка пуста").isEqualByComparingTo("0");
        assertThat(count("fund_transactions")).as("и истории перевода нет").isZero();
        assertThat(count("financial_events WHERE type = 'FUND_TRANSFER'"))
                .as("и факта перевода в журнале нет").isZero();

        transfer(fundId, new BigDecimal("20000"), true).andExpect(status().isOk());
        assertThat(fundBalance(fundId)).as("подтверждение пропускает").isEqualByComparingTo("20000");
    }

    @Test
    @DisplayName("ANO-88: в пределах кармашка — проходит без вопроса")
    void transferWithinPocket_passes() throws Exception {
        anchorDefaultAccount("100000");
        plan("EXPENSE", today.plusDays(1), "90000");
        String fundId = createFund("Отпуск", null, null, null);

        transfer(fundId, new BigDecimal("10000"), null).andExpect(status().isOk());

        assertThat(fundBalance(fundId)).isEqualByComparingTo("10000");
    }

    @Test
    @DisplayName("ANO-88: плановый взнос в зафиксированную копилку больше кармашка «до» — проходит: перевод освобождает резерв")
    void plannedContribution_releasesReserve_passes() throws Exception {
        // Доход и бронь 5-го следующего месяца, копилка 30 000 к концу того же месяца — один
        // взнос в день дохода. Кармашек до перевода: 100 000 + 10 000 − 75 000 − 30 000 = 5 000.
        // После перевода 30 000 резерва нет: 70 000 + 10 000 − 75 000 = 5 000 — не хуже.
        LocalDate payday = YearMonth.from(today).plusMonths(1).atDay(5);
        anchorDefaultAccount("100000");
        plan("INCOME", payday, "10000");
        plan("EXPENSE", payday, "75000");
        String fundId = createFund("Египет", "FIXED", payday.withDayOfMonth(payday.lengthOfMonth()),
                "30000");

        transfer(fundId, new BigDecimal("30000"), null).andExpect(status().isOk());

        assertThat(fundBalance(fundId)).isEqualByComparingTo("30000");
    }

    @Test
    @DisplayName("ANO-88: вопрос — по горизонту, который выбран на карточке, а не по «до дохода»")
    void transferChecksTheScopeTheCardShows() throws Exception {
        // До дохода 5-го кармашек 100 000; на двух месяцах — бронь 105 000 20-го, и кармашек
        // 100 000 + 10 000 − 105 000 = 5 000. Перевод 20 000 безопасен до дохода и уводит
        // в минус на двух месяцах.
        LocalDate payday = YearMonth.from(today).plusMonths(1).atDay(5);
        anchorDefaultAccount("100000");
        plan("INCOME", payday, "10000");
        plan("EXPENSE", payday.withDayOfMonth(20), "105000");
        String fundId = createFund("Отпуск", null, null, null);

        transfer(fundId, new BigDecimal("20000"), null, "MONTHS:2")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details[0]").value("CONFIRM_REQUIRED"));
        transfer(fundId, new BigDecimal("20000"), null, null).andExpect(status().isOk());
    }

    // ── оснастка ─────────────────────────────────────────────────────────────

    /** Якорь вчерашним днём: при якоре «сегодня» переводы ведут себя иначе (ANO-125). */
    private void anchorDefaultAccount(String amount) {
        String accountId = jdbc.queryForObject(
                "SELECT id::text FROM accounts WHERE is_default = true AND is_deleted = false",
                String.class);
        jdbc.update("""
                INSERT INTO balance_checkpoints (id, date, amount, account_id, created_at)
                VALUES (gen_random_uuid(), CURRENT_DATE - 1, ?::numeric, ?::uuid, now())
                """, amount, accountId);
    }

    /** Плановое событие-бронь: сумма и дата известны. */
    private void plan(String type, LocalDate date, String amount) {
        String categoryId = jdbc.queryForObject("SELECT gen_random_uuid()::text", String.class);
        jdbc.update("""
                INSERT INTO categories (id, name, type, is_deleted)
                VALUES (?::uuid, ?, ?, FALSE)
                """, categoryId, "ANO-88 " + type + " " + date, type);
        jdbc.update("""
                INSERT INTO financial_events
                    (id, date, category_id, type, planned_amount, status, is_deleted,
                     created_at, priority, event_kind)
                VALUES (gen_random_uuid(), ?, ?::uuid, ?, ?::numeric, 'PLANNED', FALSE, now(), 'HIGH', 'PLAN')
                """, date, categoryId, type, amount);
    }

    /** Копилка без счёта; {@code wishlistStatus = FIXED} с датой — кармашек резервирует взносы. */
    private String createFund(String name, String wishlistStatus, LocalDate targetDate, String target) {
        String id = jdbc.queryForObject("SELECT gen_random_uuid()::text", String.class);
        jdbc.update("""
                INSERT INTO target_funds
                    (id, name, target_amount, current_balance, status, purchase_type,
                     priority, is_deleted, created_at, wishlist_status, target_date)
                VALUES (?::uuid, ?, ?::numeric, 0, 'FUNDING', 'SAVINGS', 100, false, now(), ?, ?)
                """, id, name, target != null ? target : "1000000", wishlistStatus, targetDate);
        return id;
    }

    /** @param confirm {@code null} — без подтверждения */
    private ResultActions transfer(String fundId, BigDecimal amount, Boolean confirm) throws Exception {
        return transfer(fundId, amount, confirm, null);
    }

    /** @param scope горизонт карточки кармашка; {@code null} — «до дохода», как по умолчанию */
    private ResultActions transfer(String fundId, BigDecimal amount, Boolean confirm, String scope)
            throws Exception {
        String body = "{\"amount\": " + amount.toPlainString()
                + (confirm == null ? "" : ", \"confirm\": " + confirm)
                + (scope == null ? "" : ", \"scope\": \"" + scope + "\"")
                + "}";
        return mockMvc.perform(post("/api/v1/funds/{id}/transfer", fundId)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private BigDecimal fundBalance(String fundId) {
        return jdbc.queryForObject(
                "SELECT current_balance FROM target_funds WHERE id = ?::uuid", BigDecimal.class, fundId);
    }

    private int count(String fromWhere) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM " + fromWhere, Integer.class);
        return n == null ? 0 : n;
    }
}
