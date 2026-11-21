package ru.selfin.backend;

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
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Срез 4, п. 5 (решение 59): «Наличные» — карта для трат у каждого. Старые базы доводит {@code V38}.
 *
 * <p>Своим прогоном миграцию не проверить: на базе Testcontainers {@code V38} видит одного синтетического
 * владельца. Люди заводятся здесь, а затем исполняется <b>сам файл миграции</b> — поэтому он повторяемый.
 * {@code jdbc} — роль владельца таблиц: строки разных людей видны мимо RLS.
 */
@SpringBootTest
@Testcontainers
class CashCardMigrationIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("без наличных — карта «Наличные»; наличные без остатка — ничего; наличные с остатком — не тронуты, "
            + "карта для трат рядом; второй прогон ничего не добавляет")
    void threePeople() {
        UUID none = person();
        UUID plain = person();
        account(plain, "Кошелёк", true, false);
        UUID tracked = person();
        account(tracked, "Эталон", true, true);

        applyMigration();
        applyMigration();

        assertThat(cash(none)).extracting(r -> r.get("name"), r -> r.get("track_balance"), r -> r.get("category"))
                .containsExactly(tuple("Наличные", false, "Наличные"));
        assertThat(categories(none, "Наличные")).as("категория заведена расходом").containsExactly("EXPENSE");

        assertThat(cash(plain)).extracting(r -> r.get("name")).containsExactly("Кошелёк");
        assertThat(categories(plain, "Наличные")).as("карте не нужна — не заводится").isEmpty();

        assertThat(cash(tracked)).extracting(r -> r.get("name"), r -> r.get("track_balance"))
                .containsExactlyInAnyOrder(tuple("Эталон", true), tuple("Наличные", false));
        assertThat(categories(tracked, "Наличные")).containsExactly("EXPENSE");
    }

    @Test
    @DisplayName("удалённые наличные без остатка не в счёт — карта заводится")
    void deletedCash_doesNotCount() {
        UUID person = person();
        UUID old = account(person, "Старый кошелёк", true, false);
        jdbc.update("UPDATE accounts SET is_deleted = TRUE WHERE id = ?", old);

        applyMigration();

        assertThat(cash(person)).extracting(r -> r.get("name")).containsExactlyInAnyOrder("Старый кошелёк", "Наличные");
    }

    @Test
    @DisplayName("своя расходная «Наличные» уже есть — карта берёт её, второй не заводится")
    void ownExpenseCategory_isTaken() {
        UUID person = person();
        UUID own = category(person, "Наличные", "EXPENSE", false);

        applyMigration();

        assertThat(cash(person)).singleElement().satisfies(r -> assertThat(r).containsEntry("category_id", own));
        assertThat(categories(person, "Наличные")).hasSize(1);
    }

    @Test
    @DisplayName("имя занято доходной или удалённой категорией — карта без категории, миграция не падает")
    void nameTaken_cardWithoutCategory() {
        UUID income = person();
        category(income, "Наличные", "INCOME", false);
        UUID deleted = person();
        category(deleted, "Наличные", "EXPENSE", true);

        applyMigration();

        assertThat(cash(income)).singleElement().satisfies(r -> assertThat(r.get("category_id")).isNull());
        assertThat(cash(deleted)).singleElement().satisfies(r -> assertThat(r.get("category_id")).isNull());
        assertThat(categories(income, "Наличные")).containsExactly("INCOME");
    }

    @Test
    @DisplayName("база из дампа без умолчаний столбцов — миграция проходит (стенд 21.11: у categories.is_deleted "
            + "умолчания нет)")
    void noColumnDefaults() {
        UUID person = person();
        List<Map<String, Object>> defaults = jdbc.queryForList("""
                SELECT table_name, column_name, column_default FROM information_schema.columns
                 WHERE table_schema = current_schema() AND table_name IN ('categories', 'accounts')
                   AND column_default IS NOT NULL
                """);
        defaults.forEach(d -> jdbc.execute("ALTER TABLE %s ALTER COLUMN %s DROP DEFAULT"
                .formatted(d.get("table_name"), d.get("column_name"))));
        try {
            applyMigration();
        } finally {
            defaults.forEach(d -> jdbc.execute("ALTER TABLE %s ALTER COLUMN %s SET DEFAULT %s"
                    .formatted(d.get("table_name"), d.get("column_name"), d.get("column_default"))));
        }

        assertThat(cash(person)).extracting(r -> r.get("name"), r -> r.get("track_balance"), r -> r.get("category"))
                .containsExactly(tuple("Наличные", false, "Наличные"));
    }

    private UUID person() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, external_id) VALUES (?, ?)", id, "cash-" + id);
        return id;
    }

    private UUID account(UUID owner, String name, boolean cash, boolean tracked) {
        return jdbc.queryForObject("""
                INSERT INTO accounts (id, user_id, name, kind, track_balance)
                VALUES (gen_random_uuid(), ?, ?, ?, ?) RETURNING id
                """, UUID.class, owner, name, cash ? "CASH" : "DEBIT", tracked);
    }

    private UUID category(UUID owner, String name, String type, boolean deleted) {
        return jdbc.queryForObject("""
                INSERT INTO categories (id, user_id, name, type, is_deleted)
                VALUES (gen_random_uuid(), ?, ?, ?, ?) RETURNING id
                """, UUID.class, owner, name, type, deleted);
    }

    /** Счета CASH человека с именем категории карты. */
    private List<Map<String, Object>> cash(UUID owner) {
        return jdbc.queryForList("""
                SELECT a.name, a.track_balance, a.purpose_category_id AS category_id, c.name AS category
                  FROM accounts a LEFT JOIN categories c ON c.id = a.purpose_category_id
                 WHERE a.user_id = ? AND a.kind = 'CASH'
                """, owner);
    }

    private List<String> categories(UUID owner, String name) {
        return jdbc.queryForList("SELECT type FROM categories WHERE user_id = ? AND name = ?", String.class, owner, name);
    }

    private void applyMigration() {
        try {
            jdbc.execute(new String(new ClassPathResource("db/migration/V38__cash_card.sql")
                    .getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
