package ru.selfin.backend;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.selfin.backend.service.CapitalService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ANO-86/87, страховка знака (спека {@code 2026-09-12-fund-money-flow-design.md} §5).
 *
 * <p>Обратный перевод делает {@code factAmount} и {@code FundTransaction.amount}
 * отрицательными. Этот класс фиксирует, что суммирующие места от этого не ломаются, а дают
 * НЕТТО — и фиксирует ДО появления знака в продуктовом коде, чтобы потом нельзя было
 * спутать «так и было» с «мы сломали».
 *
 * <p>Разбор чтений сделан заранее: {@code AccountService.allocatedThisMonth} фильтрует по
 * {@code type == EXPENSE} и перевода не видит; {@code BaselineTimelineBuilder.sumByType}
 * партиционирует по типу; {@code FundTransaction.amount} читается ровно в одном месте —
 * запросе ликвида. Именно его здесь и проверяем.
 */
@SpringBootTest
@Testcontainers
class FundSignedAmountIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired JdbcTemplate jdbc;
    @Autowired CapitalService capitalService;

    @Test
    @DisplayName("SUM(t.amount) по движениям копилки даёт нетто при отрицательном движении")
    void fundTransactionSum_isNetWhenNegative() {
        String fundId = insertFund("Знаковая копилка");
        insertFundTransaction(fundId, new BigDecimal("20000"));
        insertFundTransaction(fundId, new BigDecimal("-5000"));

        BigDecimal net = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount),0) FROM fund_transactions"
                        + " WHERE fund_id = ?::uuid AND is_deleted = false",
                BigDecimal.class, fundId);

        assertThat(net)
                .as("движения складываются со знаком, отдельного поля направления нет")
                .isEqualByComparingTo(new BigDecimal("15000"));
    }

    @Test
    @DisplayName("ликвид капитала принимает отрицательное движение и даёт нетто")
    void capitalLiquid_acceptsNegativeTransaction() {
        BigDecimal before = capitalService.cashLiquidAt(LocalDate.now());

        String fundId = insertFund("Копилка для ликвида");
        insertFundTransaction(fundId, new BigDecimal("20000"));
        BigDecimal afterIn = capitalService.cashLiquidAt(LocalDate.now());

        insertFundTransaction(fundId, new BigDecimal("-20000"));
        BigDecimal afterOut = capitalService.cashLiquidAt(LocalDate.now());

        assertThat(afterIn.subtract(before))
                .as("движение в копилку поднимает ликвид").isEqualByComparingTo("20000");
        assertThat(afterOut)
                .as("обратное движение возвращает ликвид ровно назад")
                .isEqualByComparingTo(before);
    }

    private String insertFund(String name) {
        String id = jdbc.queryForObject("SELECT gen_random_uuid()::text", String.class);
        jdbc.update("""
                INSERT INTO target_funds
                    (id, name, target_amount, current_balance, status, purchase_type,
                     is_deleted, created_at)
                VALUES (?::uuid, ?, 100000, 0, 'FUNDING', 'SAVINGS', false, now())
                """, id, name);
        return id;
    }

    private void insertFundTransaction(String fundId, BigDecimal amount) {
        jdbc.update("""
                INSERT INTO fund_transactions
                    (id, fund_id, amount, transaction_date, idempotency_key, is_deleted, created_at)
                VALUES (gen_random_uuid(), ?::uuid, ?, CURRENT_DATE, ?::uuid, false, now())
                """, fundId, amount, UUID.randomUUID().toString());
    }
}
