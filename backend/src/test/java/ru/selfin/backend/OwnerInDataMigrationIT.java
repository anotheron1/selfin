package ru.selfin.backend;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.selfin.backend.model.Category;
import ru.selfin.backend.model.enums.CategoryType;
import ru.selfin.backend.repository.CategoryRepository;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ANO-145, ANO-146: владелец в данных. Миграция {@code V31} даёт каждой строке одиннадцати таблиц владельца
 * и снимает четыре замка, из-за которых второй человек физически невозможен.
 *
 * <p>Здесь Flyway проходит все миграции на чистой базе — так их видят тесты, CI и новые установки. База
 * владельца другой породы — имена ограничений от Hibernate, — её проверяет {@link OwnerInDataLegacyNamesIT}.
 *
 * <p>Спека: {@code docs/superpowers/specs/2026-10-12-owner-in-data-design.md}.
 */
@SpringBootTest
@Testcontainers
class OwnerInDataMigrationIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    static final String SYNTHETIC = "00000000-0000-0000-0000-000000000001";
    static final List<String> OWNED = List.of("accounts", "balance_checkpoints", "capital_items",
            "capital_revaluations", "categories", "financial_events", "fund_account_links", "fund_transactions",
            "recurring_rule", "target_funds", "user_settings");

    @Autowired JdbcTemplate jdbc;
    @Autowired CategoryRepository categoryRepository;

    /** Второй человек — строкой в {@code users}, как его заведёт ANO-195 при первом входе. */
    private String second;

    @BeforeEach
    void secondOwner() {
        second = jdbc.queryForObject(
                "INSERT INTO users (id, external_id) VALUES (gen_random_uuid(), 'sub-второго') RETURNING id::text",
                String.class);
    }

    /** Пробы обоих владельцев — по меткам, затем второй человек. Сиды V2 и V20 не трогаются. */
    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM fund_transactions WHERE fund_id IN (SELECT id FROM target_funds WHERE name = 'Проба ANO-146')");
        jdbc.update("DELETE FROM target_funds WHERE name = 'Проба ANO-146'");
        jdbc.update("DELETE FROM financial_events WHERE description = 'Проба ANO-146'");
        jdbc.update("DELETE FROM user_settings WHERE settings_key = 'проба-ano-146'");
        jdbc.update("DELETE FROM categories WHERE name IN ('Продукты ANO-146', 'Проба ANO-145')");
        jdbc.update("DELETE FROM accounts WHERE user_id = ?::uuid", second);
        jdbc.update("DELETE FROM users WHERE id = ?::uuid", second);
    }

    @Test
    @DisplayName("ANO-145: у 11 таблиц — user_id NOT NULL со ссылкой на users, по умолчанию — владелец сеанса (V33); у budget_snapshots — нет")
    void everyOwnedTable_hasOwnerColumn() {
        for (String table : OWNED) {
            Map<String, Object> column = jdbc.queryForMap("""
                    SELECT is_nullable, column_default FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name = ? AND column_name = 'user_id'
                    """, table);
            assertThat(column.get("is_nullable")).as(table).isEqualTo("NO");
            assertThat(column.get("column_default")).as(table + ": без синтетического запасного").isEqualTo("current_owner()");
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM pg_constraint c
                    JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)
                    WHERE c.conrelid = ?::regclass AND c.contype = 'f'
                      AND c.confrelid = 'users'::regclass AND a.attname = 'user_id'
                    """, Integer.class, table)).as(table + ": ссылка на users").isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'budget_snapshots' AND column_name = 'user_id'
                """, Integer.class)).as("снимки остаются ничьими — решение 28.09").isZero();
    }

    @Test
    @DisplayName("ANO-145: строки, что были до миграции, — у синтетического владельца, а он сам ещё ни с кем не связан")
    void existingRows_belongToSyntheticOwner() {
        // На чистой базе до V31 есть только сиды: категории V2 и счёт по умолчанию V20.
        for (String table : List.of("categories", "accounts")) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class)).as(table).isPositive();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE user_id <> ?::uuid",
                    Integer.class, SYNTHETIC)).as(table).isZero();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE id = ?::uuid AND external_id IS NULL",
                Integer.class, SYNTHETIC)).isEqualTo(1);
    }

    @Test
    @DisplayName("ANO-147: запись кодом продукта — владельцу потока, у теста синтетическому: его ставит Hibernate")
    void productWrite_getsThreadOwner() {
        Category saved = categoryRepository.save(
                Category.builder().name("Проба ANO-145").type(CategoryType.EXPENSE).build());

        assertThat(jdbc.queryForObject("SELECT user_id::text FROM categories WHERE id = ?",
                String.class, saved.getId())).isEqualTo(SYNTHETIC);
    }

    @Test
    @DisplayName("ANO-146: имя категории уникально в пределах владельца, а не на всю базу")
    void categoryName_isUniquePerOwner() {
        insertCategory(SYNTHETIC, "Продукты ANO-146");
        insertCategory(second, "Продукты ANO-146");

        assertThatThrownBy(() -> insertCategory(second, "Продукты ANO-146"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("ANO-146: счёт по умолчанию — по одному у каждого владельца")
    void defaultAccount_isOnePerOwner() {
        // У синтетического владельца счёт по умолчанию уже есть — сид V20.
        insertDefaultAccount(second);

        assertThatThrownBy(() -> insertDefaultAccount(second)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("ANO-146: настройки — свои у каждого владельца, ключ уникален в пределах владельца")
    void settingsKey_isUniquePerOwner() {
        insertSettings(SYNTHETIC, "проба-ano-146");
        insertSettings(second, "проба-ano-146");

        assertThatThrownBy(() -> insertSettings(second, "проба-ano-146"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("ANO-146: ключ идемпотентности события уникален в пределах владельца")
    void eventIdempotencyKey_isUniquePerOwner() {
        String key = uuid();
        insertCategory(second, "Продукты ANO-146"); // событию — категория своего владельца: ссылка составная (V33)
        insertEvent(SYNTHETIC, key);
        insertEvent(second, key);

        assertThatThrownBy(() -> insertEvent(second, key)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("ANO-146: ключ идемпотентности движения копилки уникален в пределах владельца")
    void movementIdempotencyKey_isUniquePerOwner() {
        String key = uuid();
        insertMovement(SYNTHETIC, insertFund(SYNTHETIC), key);
        String secondFund = insertFund(second);
        insertMovement(second, secondFund, key);

        assertThatThrownBy(() -> insertMovement(second, secondFund, key))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("ANO-145: у каждой из 11 таблиц есть полный индекс, начинающийся с user_id; у событий — ещё (user_id, date)")
    void everyOwnedTable_hasOwnerLeadingIndex() {
        for (String table : OWNED) {
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM pg_index i
                    JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = i.indkey[0]
                    WHERE i.indrelid = ?::regclass AND a.attname = 'user_id' AND i.indpred IS NULL
                    """, Integer.class, table)).as(table).isPositive();
        }
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_index i
                JOIN pg_attribute a0 ON a0.attrelid = i.indrelid AND a0.attnum = i.indkey[0]
                JOIN pg_attribute a1 ON a1.attrelid = i.indrelid AND a1.attnum = i.indkey[1]
                WHERE i.indrelid = 'financial_events'::regclass AND a0.attname = 'user_id' AND a1.attname = 'date'
                """, Integer.class)).as("журнал и расчёт выбирают события периодом").isEqualTo(1);
    }

    private String uuid() {
        return jdbc.queryForObject("SELECT gen_random_uuid()::text", String.class);
    }

    private void insertCategory(String owner, String name) {
        jdbc.update("INSERT INTO categories (id, name, type, is_deleted, user_id) VALUES (gen_random_uuid(), ?, 'EXPENSE', FALSE, ?::uuid)",
                name, owner);
    }

    private void insertDefaultAccount(String owner) {
        jdbc.update("""
                INSERT INTO accounts (id, name, kind, track_balance, is_default, user_id)
                VALUES (gen_random_uuid(), 'Основная карта', 'DEBIT', TRUE, TRUE, ?::uuid)
                """, owner);
    }

    private void insertSettings(String owner, String key) {
        jdbc.update("INSERT INTO user_settings (id, settings_key, settings_value, user_id) VALUES (gen_random_uuid(), ?, '{}'::jsonb, ?::uuid)",
                key, owner);
    }

    private void insertEvent(String owner, String key) {
        jdbc.update("""
                INSERT INTO financial_events
                    (id, idempotency_key, date, category_id, type, planned_amount, status, priority, is_deleted,
                     event_kind, created_at, description, user_id)
                VALUES (gen_random_uuid(), ?::uuid, CURRENT_DATE, (SELECT id FROM categories WHERE user_id = ?::uuid LIMIT 1),
                        'EXPENSE', 100, 'PLANNED', 'MEDIUM', FALSE, 'PLAN', now(), 'Проба ANO-146', ?::uuid)
                """, key, owner, owner);
    }

    private String insertFund(String owner) {
        return jdbc.queryForObject("""
                INSERT INTO target_funds
                    (id, name, target_amount, current_balance, status, purchase_type, priority, is_deleted, created_at, user_id)
                VALUES (gen_random_uuid(), 'Проба ANO-146', 100000, 0, 'FUNDING', 'SAVINGS', 100, FALSE, now(), ?::uuid)
                RETURNING id::text
                """, String.class, owner);
    }

    private void insertMovement(String owner, String fundId, String key) {
        jdbc.update("""
                INSERT INTO fund_transactions (id, fund_id, idempotency_key, amount, transaction_date, is_deleted, created_at, user_id)
                VALUES (gen_random_uuid(), ?::uuid, ?::uuid, 100, CURRENT_DATE, FALSE, now(), ?::uuid)
                """, fundId, key, owner);
    }
}
