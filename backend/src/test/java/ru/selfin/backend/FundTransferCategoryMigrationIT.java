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
 * ANO-224: «Переводы в копилки» — системная категория; старые базы доводит до этого миграция {@code V30}.
 *
 * <p>Своим прогоном миграцию не проверить: на базе Testcontainers очередь {@code V30} наступает на
 * таблицу без этой категории. Поэтому строка заводится здесь, а затем исполняется <b>сам файл
 * миграции</b>, а не копия его запроса.
 *
 * <p>Спека: {@code docs/superpowers/specs/2026-10-11-transfer-category-revive-design.md}.
 */
@SpringBootTest
@Testcontainers
class FundTransferCategoryMigrationIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void cleanDb() {
        jdbc.update("DELETE FROM categories WHERE name IN ('Переводы в копилки', 'Проба ANO-224')");
    }

    @Test
    @DisplayName("ANO-224: удалённая «Переводы в копилки» возвращается системной категорией расходов — как её возвращает продукт")
    void deletedCategory_isRevivedAsSystem() {
        String id = jdbc.queryForObject("""
                INSERT INTO categories (id, name, type, is_deleted, is_system, primary_income, forecast_enabled)
                VALUES (gen_random_uuid(), 'Переводы в копилки', 'INCOME', TRUE, FALSE, TRUE, TRUE) RETURNING id::text
                """, String.class);

        applyMigration();

        Map<String, Object> row = category(id);
        assertThat(row.get("is_deleted")).as("имя уникально и среди удалённых — вторую не завести").isEqualTo(false);
        assertThat(row.get("is_system")).isEqualTo(true);
        assertThat(row.get("type")).isEqualTo("EXPENSE");
        assertThat(row.get("primary_income")).isEqualTo(false);
        assertThat(row.get("forecast_enabled"))
                .as("у системной категории выключателя прогноза на экране нет").isEqualTo(false);
    }

    @Test
    @DisplayName("ANO-224: живая несистемная «Переводы в копилки» становится системной, чужая категория не трогается")
    void liveCategory_becomesSystem_otherCategoryUntouched() {
        String id = jdbc.queryForObject("""
                INSERT INTO categories (id, name, type, is_deleted, is_system, forecast_enabled)
                VALUES (gen_random_uuid(), 'Переводы в копилки', 'EXPENSE', FALSE, FALSE, TRUE) RETURNING id::text
                """, String.class);
        String other = jdbc.queryForObject("""
                INSERT INTO categories (id, name, type, is_deleted, is_system, forecast_enabled)
                VALUES (gen_random_uuid(), 'Проба ANO-224', 'EXPENSE', TRUE, FALSE, TRUE) RETURNING id::text
                """, String.class);

        applyMigration();

        Map<String, Object> row = category(id);
        assertThat(row.get("is_system")).as("«Настройки» больше не дадут её удалить").isEqualTo(true);
        assertThat(row.get("forecast_enabled")).isEqualTo(false);
        assertThat(category(other)).containsEntry("is_deleted", true).containsEntry("is_system", false)
                .containsEntry("forecast_enabled", true);
    }

    private Map<String, Object> category(String id) {
        return jdbc.queryForMap("""
                SELECT is_deleted, is_system, type, primary_income, forecast_enabled FROM categories WHERE id = ?::uuid
                """, id);
    }

    private void applyMigration() {
        try {
            String sql = new String(new ClassPathResource("db/migration/V30__fund_transfer_category_system.sql")
                    .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            jdbc.execute(sql);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
