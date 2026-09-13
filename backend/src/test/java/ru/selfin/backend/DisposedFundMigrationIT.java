package ru.selfin.backend;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.selfin.backend.repository.FundTransactionRepository;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ANO-156: копилки, удалённые ДО перехода на компенсирующее движение (миграция {@code V24}).
 *
 * <p><b>Чего этот класс НЕ делает и почему.</b> Проверить саму миграцию её собственным
 * прогоном нельзя: на базе Testcontainers очередь {@code V24} наступает, когда
 * компенсировать нечего — таблицы пусты. Утверждение вида «у всех удалённых копилок движения
 * обнулены» здесь зелёное по построению, то есть не проверяет ничего.
 *
 * <p>Поэтому проверяется <b>то же SQL на тех же данных</b>: заводится копилка в состоянии
 * «удалена до правки», применяется в точности запрос миграции, и проверяется результат.
 * Расхождение между SQL здесь и SQL в {@code V24} — единственный способ обмануть эту
 * проверку, поэтому запрос скопирован дословно.
 *
 * <p>Спека: {@code docs/superpowers/specs/2026-09-13-fund-disposal-as-movement-design.md}.
 */
@SpringBootTest
@Testcontainers
class DisposedFundMigrationIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired FundTransactionRepository fundTxRepo;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void cleanDb() {
        jdbc.update("DELETE FROM fund_transactions");
        jdbc.update("DELETE FROM target_funds");
    }

    @Test
    @DisplayName("ANO-156: легаси-копилка после компенсации ведёт себя как свежая")
    void legacyDisposedFund_behavesLikeFreshOne() {
        LocalDate past = LocalDate.now().minusMonths(1);
        insertDisposedFundWithMovement(new BigDecimal("20000"), past);

        assertThat(fundTxRepo.sumEnvelopeFundsByTransactionDateLessThanEqual(LocalDate.now()))
                .as("до компенсации деньги удалённой копилки висят в капитале — это ANO-86")
                .isEqualByComparingTo("20000");

        applyMigrationSql();

        assertThat(fundTxRepo.sumEnvelopeFundsByTransactionDateLessThanEqual(past))
                .as("прошлое сохранено: месяц назад деньги в копилке были")
                .isEqualByComparingTo("20000");
        assertThat(fundTxRepo.sumEnvelopeFundsByTransactionDateLessThanEqual(LocalDate.now()))
                .as("сегодня их нет — ANO-86 не воскресла")
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("ANO-156: копилка с балансом, но без движений, в минус не уходит")
    void fundWithBalanceButNoMovements_isNotDrivenNegative() {
        // Ради этого случая миграция компенсирует сумму движений, а не current_balance.
        String fundId = jdbc.queryForObject("SELECT gen_random_uuid()::text", String.class);
        jdbc.update("""
                INSERT INTO target_funds
                    (id, name, target_amount, current_balance, status, purchase_type,
                     priority, is_deleted, created_at)
                VALUES (?::uuid, 'Легаси без движений', 100000, 5000, 'FUNDING', 'SAVINGS',
                        100, true, now())
                """, fundId);

        applyMigrationSql();

        assertThat(fundTxRepo.sumEnvelopeFundsByTransactionDateLessThanEqual(LocalDate.now()))
                .as("компенсировать нечего — миграция не имеет права создавать отрицательные деньги")
                .isEqualByComparingTo("0");
    }

    // ── оснастка ─────────────────────────────────────────────────────────────

    /** Копилка в состоянии «удалена до ANO-156»: движение есть, компенсации нет. */
    private String insertDisposedFundWithMovement(BigDecimal amount, LocalDate date) {
        String fundId = jdbc.queryForObject("SELECT gen_random_uuid()::text", String.class);
        jdbc.update("""
                INSERT INTO target_funds
                    (id, name, target_amount, current_balance, status, purchase_type,
                     priority, is_deleted, created_at)
                VALUES (?::uuid, 'Легаси', 100000, ?::numeric, 'FUNDING', 'SAVINGS',
                        100, true, now())
                """, fundId, amount.toPlainString());
        jdbc.update("""
                INSERT INTO fund_transactions
                    (id, fund_id, idempotency_key, amount, transaction_date, is_deleted, created_at)
                VALUES (gen_random_uuid(), ?::uuid, gen_random_uuid(), ?::numeric, ?::date,
                        false, now())
                """, fundId, amount.toPlainString(), date.toString());
        return fundId;
    }

    /** Дословно тело {@code V24__compensate_disposed_funds.sql}. Расхождение — дыра в проверке. */
    private void applyMigrationSql() {
        jdbc.update("""
                INSERT INTO fund_transactions
                    (id, fund_id, idempotency_key, amount, transaction_date, is_deleted, created_at)
                SELECT gen_random_uuid(), f.id, gen_random_uuid(), -SUM(t.amount), CURRENT_DATE,
                       false, now()
                FROM target_funds f
                JOIN fund_transactions t ON t.fund_id = f.id AND t.is_deleted = false
                WHERE f.is_deleted = true
                  AND f.account_id IS NULL
                GROUP BY f.id
                HAVING SUM(t.amount) <> 0
                """);
        jdbc.update("""
                UPDATE target_funds
                SET current_balance = 0
                WHERE is_deleted = true
                  AND account_id IS NULL
                  AND COALESCE(current_balance, 0) <> 0
                """);
    }
}
