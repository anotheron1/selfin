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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Срез 3 п. 13 (ANO-230, ANO-259): системные категории и служебная копилка узнаются признаком, а не именем;
 * «Переводы в копилки» называется «Накопление». Старые базы доводит {@code V37}.
 *
 * <p>Своим прогоном миграцию не проверить: на базе Testcontainers {@code V37} наступает на пустые таблицы.
 * Строки заводятся здесь, а затем исполняется <b>сам файл миграции</b> — поэтому он повторяемый.
 */
@SpringBootTest
@Testcontainers
class SystemKindMigrationIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void cleanDb() {
        // Категорию карты «Наличные» (V38) держит счёт — она остаётся.
        jdbc.update("DELETE FROM categories WHERE id NOT IN (SELECT purpose_category_id FROM accounts "
                + "WHERE purpose_category_id IS NOT NULL)");
        jdbc.update("DELETE FROM target_funds");
    }

    @Test
    @DisplayName("Четыре имени получают свой признак, у живых и удалённых; другая категория — без признака")
    void fourNames_getKind() {
        String wish = category("Хотелки", true, false);
        String transfer = category("Переводы в копилки", true, true);
        String goals = category("Цели", true, false);
        // Своя «Кредит», несистемная: продукт пишет в неё платежи и сейчас — признак держит тот же путь.
        String credit = category("Кредит", false, false);
        String food = category("Продукты", false, false);

        applyMigration();

        assertThat(kind(wish)).isEqualTo("WISHLIST");
        assertThat(kind(transfer)).isEqualTo("FUND_TRANSFER");
        assertThat(kind(goals)).isEqualTo("GOAL_SPENDING");
        assertThat(kind(credit)).isEqualTo("CREDIT");
        assertThat(kind(food)).isNull();
    }

    @Test
    @DisplayName("«Переводы в копилки» становится «Накоплением»")
    void transferCategory_isRenamed() {
        String transfer = category("Переводы в копилки", true, false);

        applyMigration();

        assertThat(row(transfer)).containsEntry("name", "Накопление").containsEntry("system_kind", "FUND_TRANSFER");
    }

    @Test
    @DisplayName("Своя «Накопление» уже есть — миграция не падает, системная остаётся под старым именем")
    void ownSavingsCategory_keepsOldName() {
        String transfer = category("Переводы в копилки", true, false);
        String own = category("Накопление", false, false);

        applyMigration();

        assertThat(row(transfer)).containsEntry("name", "Переводы в копилки").containsEntry("system_kind", "FUND_TRANSFER");
        assertThat(row(own)).containsEntry("name", "Накопление");
        assertThat(kind(own)).as("своя категория с именем системной системной не становится").isNull();
    }

    @Test
    @DisplayName("Служебная копилка «POCKET» — признаком; обычная копилка — без него")
    void pocketFund_isService() {
        String pocket = fund("POCKET");
        String trip = fund("Отпуск");

        applyMigration();

        assertThat(jdbc.queryForObject("SELECT is_service FROM target_funds WHERE id = ?::uuid", Boolean.class, pocket)).isTrue();
        assertThat(jdbc.queryForObject("SELECT is_service FROM target_funds WHERE id = ?::uuid", Boolean.class, trip)).isFalse();
    }

    private String category(String name, boolean system, boolean deleted) {
        return jdbc.queryForObject("""
                INSERT INTO categories (id, name, type, is_deleted, is_system)
                VALUES (gen_random_uuid(), ?, 'EXPENSE', ?, ?) RETURNING id::text
                """, String.class, name, deleted, system);
    }

    private String fund(String name) {
        return jdbc.queryForObject("""
                INSERT INTO target_funds (id, name, current_balance, status, priority, created_at, is_deleted, purchase_type)
                VALUES (gen_random_uuid(), ?, 0, 'FUNDING', 100, now(), FALSE, 'SAVINGS') RETURNING id::text
                """, String.class, name);
    }

    private String kind(String id) {
        return jdbc.queryForObject("SELECT system_kind FROM categories WHERE id = ?::uuid", String.class, id);
    }

    private Map<String, Object> row(String id) {
        return jdbc.queryForMap("SELECT name, system_kind FROM categories WHERE id = ?::uuid", id);
    }

    private void applyMigration() {
        try {
            String sql = new String(new ClassPathResource("db/migration/V37__system_kind.sql")
                    .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            jdbc.execute(sql);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
