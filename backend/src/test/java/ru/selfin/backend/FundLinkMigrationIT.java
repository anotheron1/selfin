package ru.selfin.backend;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.selfin.backend.repository.FundTransactionRepository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ANO-163: копилки, привязанные к счёту ДО истории привязок (миграция {@code V26}).
 *
 * <p>Как и в {@code DisposedFundMigrationIT}, миграцию её собственным прогоном не проверить:
 * на Testcontainers очередь {@code V26} наступает при пустых таблицах, переносить нечего.
 * Поэтому заводятся копилки в состоянии «привязана до правки» — счёт есть, строки истории
 * нет, — и к ним применяется INSERT, прочитанный из файла миграции.
 *
 * <p>Спека: {@code docs/superpowers/specs/2026-09-24-fund-link-history-design.md}, §5.
 */
@SpringBootTest
@Testcontainers
class FundLinkMigrationIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired FundTransactionRepository fundTxRepo;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void cleanDb() {
        jdbc.update("DELETE FROM fund_transactions");
        jdbc.update("DELETE FROM fund_account_links");
        jdbc.update("DELETE FROM target_funds");
    }

    @Test
    @DisplayName("ANO-163: старая привязка после переноса считается так же, как старым запросом")
    void legacyLinks_keepOldNumbers() {
        LocalDate twoMonthsAgo = LocalDate.now().minusMonths(2);
        String accountId = jdbc.queryForObject(
                "SELECT id::text FROM accounts WHERE is_default = true AND is_deleted = false",
                String.class);
        insertLegacyLinkedFund("Живая на счёте", accountId, false, twoMonthsAgo);
        insertLegacyLinkedFund("Удалённая на счёте", accountId, true, twoMonthsAgo);

        assertThat(fundTxRepo.sumEnvelopeFundsByTransactionDateLessThanEqual(LocalDate.now()))
                .as("без истории деньги копилок на счёте посчитались бы — ради этого перенос и нужен")
                .isEqualByComparingTo("40000");

        applyMigrationSql();

        assertThat(fundTxRepo.sumEnvelopeFundsByTransactionDateLessThanEqual(LocalDate.now()))
                .as("сегодня — как старым запросом: копилки на счёте отдельно не складываются")
                .isEqualByComparingTo("0");
        assertThat(fundTxRepo.sumEnvelopeFundsByTransactionDateLessThanEqual(twoMonthsAgo))
                .as("и в прошлом тоже: старый запрос исключал их за все даты, "
                        + "миграция не сдвигает ни одного числа")
                .isEqualByComparingTo("0");
    }

    // ── оснастка ─────────────────────────────────────────────────────────────

    /**
     * Копилка в состоянии «привязана до ANO-163»: счёт задан, строки истории нет. Родилась три
     * месяца назад, взнос 20 000 — два месяца назад, пока была конвертом.
     */
    private void insertLegacyLinkedFund(String name, String accountId, boolean deleted,
                                        LocalDate contributedOn) {
        String fundId = jdbc.queryForObject("SELECT gen_random_uuid()::text", String.class);
        jdbc.update("""
                INSERT INTO target_funds
                    (id, name, target_amount, current_balance, status, purchase_type,
                     priority, is_deleted, created_at, account_id)
                VALUES (?::uuid, ?, 100000, 0, 'FUNDING', 'SAVINGS',
                        100, ?, now() - interval '3 months', ?::uuid)
                """, fundId, name, deleted, accountId);
        jdbc.update("""
                INSERT INTO fund_transactions
                    (id, fund_id, idempotency_key, amount, transaction_date, is_deleted, created_at)
                VALUES (gen_random_uuid(), ?::uuid, gen_random_uuid(), 20000, ?::date, false, now())
                """, fundId, contributedOn.toString());
    }

    /**
     * INSERT переноса, прочитанный из самой миграции, а не её копия: копию нечем сверить с
     * оригиналом, и правка одного без другого оставила бы проверку зелёной.
     */
    private void applyMigrationSql() {
        String migration;
        try {
            migration = new ClassPathResource("db/migration/V26__fund_account_links.sql")
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        int start = migration.indexOf("INSERT INTO fund_account_links");
        assertThat(start).as("в миграции нет переноса старых привязок").isNotNegative();
        jdbc.update(migration.substring(start, migration.indexOf(';', start)));
    }
}
