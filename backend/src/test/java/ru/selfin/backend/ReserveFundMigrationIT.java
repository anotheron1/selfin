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
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Срез 4, п. 7 (ANO-263): НЗ — копилка. Старые базы доводит {@code V40}: «сколько держать» — из {@code bufferAmount}.
 *
 * <p>Своим прогоном миграцию не проверить: на базе Testcontainers {@code V40} видит одного синтетического владельца. Люди
 * заводятся здесь, а затем исполняется <b>сам файл миграции</b> — поэтому он повторяемый. {@code jdbc} — роль владельца
 * таблиц: строки разных людей видны мимо RLS.
 */
@SpringBootTest
@Testcontainers
class ReserveFundMigrationIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("НЗ из bufferAmount; без настройки, с null или не числом — 0; без счёта, первым; второй прогон — ничего")
    void bufferAmountMoves() {
        UUID set = person("{\"bufferAmount\": 15000.50}");
        UUID none = person(null);
        UUID nul = person("{\"bufferAmount\": null}");
        UUID text = person("{\"bufferAmount\": \"много\"}");

        applyMigration();
        applyMigration();

        assertThat(reserves(set)).singleElement().satisfies(r -> {
            assertThat((BigDecimal) r.get("target_amount")).isEqualByComparingTo("15000.50");
            assertThat(r).containsEntry("name", "НЗ").containsEntry("account_id", null).containsEntry("priority", 0)
                    .containsEntry("status", "FUNDING").containsEntry("purchase_type", "SAVINGS");
        });
        for (UUID p : List.of(none, nul, text)) {
            assertThat(reserves(p)).singleElement()
                    .satisfies(r -> assertThat((BigDecimal) r.get("target_amount")).isEqualByComparingTo("0"));
        }
    }

    @Test
    @DisplayName("удалённый НЗ не в счёт — заводится новый; живой НЗ на счёте не трогается")
    void deletedReserve_doesNotCount() {
        UUID person = person("{\"bufferAmount\": 7000}");
        applyMigration();
        jdbc.update("UPDATE target_funds SET is_deleted = TRUE WHERE user_id = ? AND is_reserve", person);

        applyMigration();

        assertThat(reserves(person)).hasSize(2)
                .filteredOn(r -> !(Boolean) r.get("is_deleted")).singleElement()
                .satisfies(r -> assertThat((BigDecimal) r.get("target_amount")).isEqualByComparingTo("7000"));
    }

    @Test
    @DisplayName("база из дампа без умолчаний столбцов — миграция проходит")
    void noColumnDefaults() {
        UUID person = person("{\"bufferAmount\": 1000}");
        List<Map<String, Object>> defaults = jdbc.queryForList("""
                SELECT table_name, column_name, column_default FROM information_schema.columns
                 WHERE table_schema = current_schema() AND table_name = 'target_funds' AND column_default IS NOT NULL
                """);
        defaults.forEach(d -> jdbc.execute("ALTER TABLE %s ALTER COLUMN %s DROP DEFAULT"
                .formatted(d.get("table_name"), d.get("column_name"))));
        try {
            applyMigration();
        } finally {
            defaults.forEach(d -> jdbc.execute("ALTER TABLE %s ALTER COLUMN %s SET DEFAULT %s"
                    .formatted(d.get("table_name"), d.get("column_name"), d.get("column_default"))));
        }

        assertThat(reserves(person)).singleElement()
                .satisfies(r -> assertThat((BigDecimal) r.get("target_amount")).isEqualByComparingTo("1000"));
    }

    private UUID person(String pocketSettings) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, external_id) VALUES (?, ?)", id, "nz-" + id);
        if (pocketSettings != null) {
            jdbc.update("INSERT INTO user_settings (id, user_id, settings_key, settings_value, updated_at) "
                    + "VALUES (gen_random_uuid(), ?, 'pocket', ?::jsonb, now())", id, pocketSettings);
        }
        return id;
    }

    private List<Map<String, Object>> reserves(UUID owner) {
        return jdbc.queryForList("""
                SELECT name, target_amount, account_id, priority, status, purchase_type, is_deleted
                  FROM target_funds WHERE user_id = ? AND is_reserve
                """, owner);
    }

    private void applyMigration() {
        try {
            jdbc.execute(new String(new ClassPathResource("db/migration/V40__reserve_fund.sql")
                    .getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
