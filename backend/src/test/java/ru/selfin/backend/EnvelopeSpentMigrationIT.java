package ru.selfin.backend;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Р10-А (ANO-212): старым «потрачено на цель» миграция {@code V29} пишет трату, какую с правки пишет
 * сам продукт.
 *
 * <p>Своим прогоном миграцию не проверить: на базе Testcontainers очередь {@code V29} наступает на
 * пустых таблицах (ловушка {@code V22}, {@code V25}, {@code V27}). Поэтому данные заводятся здесь, а
 * затем исполняется <b>сам файл миграции</b>, а не копия его запроса.
 *
 * <p>Спека: {@code docs/superpowers/specs/2026-10-05-envelope-share-design.md}.
 */
@SpringBootTest
@Testcontainers
class EnvelopeSpentMigrationIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired JdbcTemplate jdbc;

    private String transferCategory;

    @BeforeEach
    void category() {
        transferCategory = uuid();
        jdbc.update("INSERT INTO categories (id, name, type, is_deleted, is_system) VALUES (?::uuid, ?, 'EXPENSE', FALSE, FALSE)",
                transferCategory, "Переводы " + transferCategory);
    }

    @AfterEach
    void cleanDb() {
        jdbc.update("DELETE FROM fund_transactions");
        jdbc.update("DELETE FROM financial_events");
        jdbc.update("DELETE FROM target_funds");
        jdbc.update("DELETE FROM categories WHERE id = ?::uuid OR name = 'Цели'", transferCategory);
    }

    @Test
    @DisplayName("Р10-А: «потрачено» до правки — трата днём и временем движения-списания, с именем копилки, в «Цели»; перевод снова «В копилку»")
    void legacySpentFund_getsSpendingOfItsOutflow() {
        String fund = insertFund("Отпуск", true, null);
        String inKey = uuid();
        insertTransfer(fund, inKey, "20000", "2026-08-01", "Отпуск");
        insertMovement(fund, inKey, "20000", "2026-08-01", LocalDateTime.of(2026, 8, 1, 9, 0));
        String outKey = uuid();
        insertMovement(fund, outKey, "-20000", "2026-09-10", LocalDateTime.of(2026, 9, 10, 18, 30));

        applyMigration();

        List<Map<String, Object>> spending = jdbc.queryForList("""
                SELECT e.idempotency_key::text AS key, e.date::text AS date, e.created_at, e.fact_amount,
                       e.description, e.event_kind, e.status, e.target_fund_id, c.name AS category, c.is_system
                FROM financial_events e JOIN categories c ON c.id = e.category_id
                WHERE e.type = 'EXPENSE'
                """);
        assertThat(spending).hasSize(1);
        Map<String, Object> s = spending.get(0);
        assertThat(s.get("key")).as("ключ движения-списания").isEqualTo(outKey);
        assertThat(s.get("date")).isEqualTo("2026-09-10");
        assertThat(s.get("created_at").toString()).as("время записи — правило дня сверки").startsWith("2026-09-10 18:30");
        assertThat((BigDecimal) s.get("fact_amount")).isEqualByComparingTo("20000");
        assertThat(s.get("description")).isEqualTo("Отпуск");
        assertThat(s.get("event_kind")).isEqualTo("FACT");
        assertThat(s.get("status")).isEqualTo("EXECUTED");
        assertThat(s.get("target_fund_id")).isNull();
        assertThat(s.get("category")).isEqualTo("Цели");
        assertThat(s.get("is_system")).isEqualTo(true);

        assertThat(description(inKey)).as("перевод откладывал деньги — слова «Пополнить»").isEqualTo("В копилку: Отпуск");

        applyMigration();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_events WHERE type = 'EXPENSE'", Integer.class))
                .as("повторный прогон трату не задваивает").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM categories WHERE name = 'Цели'", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("Р10-А: «вернуть», «снять», живая копилка и копилка на счёте не трогаются; без старых «потрачено» категория не заводится")
    void unrelatedFunds_areLeftAlone() {
        // «Вернуть»: обратный перевод с ключом движения. Описание перевода совпадает с именем — так
        // назвал сам человек, а не прежняя ветка «потрачено».
        String returned = insertFund("Машина", true, null);
        String returnInKey = uuid();
        insertTransfer(returned, returnInKey, "5000", "2026-08-01", "Машина");
        insertMovement(returned, returnInKey, "5000", "2026-08-01", LocalDateTime.of(2026, 8, 1, 9, 0));
        String returnOutKey = uuid();
        insertTransfer(returned, returnOutKey, "-5000", "2026-09-01", "В копилку: Машина");
        insertMovement(returned, returnOutKey, "-5000", "2026-09-01", LocalDateTime.of(2026, 9, 1, 9, 0));
        // Живая копилка со снятием.
        String live = insertFund("Отпуск", false, null);
        String withdrawKey = uuid();
        insertMovement(live, uuid(), "7000", "2026-08-01", LocalDateTime.of(2026, 8, 1, 9, 0));
        insertTransfer(live, withdrawKey, "-2000", "2026-09-01", "В копилку: Отпуск");
        insertMovement(live, withdrawKey, "-2000", "2026-09-01", LocalDateTime.of(2026, 9, 1, 9, 0));
        // Копилка на счёте: своих денег у неё нет.
        String account = jdbc.queryForObject("SELECT id::text FROM accounts WHERE is_default = true", String.class);
        String onAccount = insertFund("Ремонт", true, account);
        insertMovement(onAccount, uuid(), "-3000", "2026-09-01", LocalDateTime.of(2026, 9, 1, 9, 0));

        applyMigration();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_events WHERE type = 'EXPENSE'", Integer.class))
                .isZero();
        assertThat(description(returnInKey)).as("переименования не было — восстанавливать нечего").isEqualTo("Машина");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM categories WHERE name = 'Цели'", Integer.class))
                .as("системная категория видна в «Настройках» — без нужды её не заводим").isZero();
    }

    @Test
    @DisplayName("Р10-А, ревью Codex на #139: удалённая «Цели» возвращается, а не остаётся под тратами удалённой")
    void deletedGoalsCategory_isRevived() {
        String goals = uuid();
        jdbc.update("""
                INSERT INTO categories (id, name, type, is_deleted, is_system, primary_income)
                VALUES (?::uuid, 'Цели', 'INCOME', TRUE, FALSE, TRUE)
                """, goals);
        String fund = insertFund("Отпуск", true, null);
        insertMovement(fund, uuid(), "-20000", "2026-09-10", LocalDateTime.of(2026, 9, 10, 18, 30));

        applyMigration();

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT is_deleted, is_system, type, primary_income FROM categories WHERE id = ?::uuid
                """, goals);
        assertThat(row.get("is_deleted")).as("имя уникально и среди удалённых — вторую «Цели» не завести").isEqualTo(false);
        assertThat(row.get("is_system")).isEqualTo(true);
        assertThat(row.get("type")).isEqualTo("EXPENSE");
        assertThat(row.get("primary_income")).isEqualTo(false);
        assertThat(jdbc.queryForObject("SELECT category_id::text FROM financial_events WHERE type = 'EXPENSE'", String.class))
                .isEqualTo(goals);
    }

    private void applyMigration() {
        try {
            String sql = new String(new ClassPathResource("db/migration/V29__spent_fund_expenses.sql")
                    .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            jdbc.execute(sql);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private String uuid() {
        return jdbc.queryForObject("SELECT gen_random_uuid()::text", String.class);
    }

    private String insertFund(String name, boolean deleted, String accountId) {
        String id = uuid();
        jdbc.update("""
                INSERT INTO target_funds
                    (id, name, target_amount, current_balance, status, purchase_type, priority, is_deleted, created_at, account_id)
                VALUES (?::uuid, ?, 100000, 0, 'FUNDING', 'SAVINGS', 100, ?, now(), ?::uuid)
                """, id, name, deleted, accountId);
        return id;
    }

    private void insertTransfer(String fundId, String key, String amount, String date, String description) {
        jdbc.update("""
                INSERT INTO financial_events
                    (id, idempotency_key, date, category_id, type, fact_amount, status, priority, is_deleted,
                     event_kind, created_at, target_fund_id, description)
                VALUES (gen_random_uuid(), ?::uuid, ?::date, ?::uuid, 'FUND_TRANSFER', ?::numeric, 'EXECUTED',
                        'MEDIUM', FALSE, 'FACT', now(), ?::uuid, ?)
                """, key, date, transferCategory, amount, fundId, description);
    }

    private void insertMovement(String fundId, String key, String amount, String date, LocalDateTime createdAt) {
        jdbc.update("""
                INSERT INTO fund_transactions (id, fund_id, idempotency_key, amount, transaction_date, is_deleted, created_at)
                VALUES (gen_random_uuid(), ?::uuid, ?::uuid, ?::numeric, ?::date, FALSE, ?)
                """, fundId, key, amount, date, createdAt);
    }

    private String description(String key) {
        return jdbc.queryForObject("SELECT description FROM financial_events WHERE idempotency_key = ?::uuid",
                String.class, key);
    }
}
