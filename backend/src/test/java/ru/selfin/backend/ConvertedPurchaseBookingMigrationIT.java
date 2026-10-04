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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ANO-215: уже созданные планы покупки из зафиксированных хотелок переводятся в бронь (миграция
 * {@code V28}).
 *
 * <p>Своим прогоном миграцию не проверить: на базе Testcontainers очередь {@code V28} наступает
 * на пустых таблицах, и любое утверждение о результате зелёное по построению (ловушка {@code V22},
 * {@code V25}, {@code V27}). Поэтому данные заводятся здесь, а затем исполняется <b>сам файл
 * миграции</b>, а не копия его запроса.
 *
 * <p>Спека: {@code docs/superpowers/specs/2026-10-04-converted-purchase-booking-design.md}.
 */
@SpringBootTest
@Testcontainers
class ConvertedPurchaseBookingMigrationIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired JdbcTemplate jdbc;

    private String category;

    @AfterEach
    void cleanDb() {
        jdbc.update("UPDATE financial_events SET converted_to_event_id = NULL");
        jdbc.update("UPDATE target_funds SET converted_to_event_id = NULL");
        jdbc.update("DELETE FROM financial_events");
        jdbc.update("DELETE FROM target_funds");
        if (category != null) jdbc.update("DELETE FROM categories WHERE id = ?::uuid", category);
    }

    @Test
    @DisplayName("ANO-215: план из конверсии хотелки-события и копилки — бронь, и при удалённом источнике тоже")
    void convertedPurchasePlans_becomeBookings() {
        category = insertCategory();
        String fromWish = insertPlan("LOW");
        insertWishlistSource(fromWish, false);
        String fromFund = insertPlan("LOW");
        insertFundSource(fromFund);
        String fromDeletedWish = insertPlan("LOW");
        insertWishlistSource(fromDeletedWish, true);

        applyMigration();

        assertThat(priority(fromWish)).isEqualTo("HIGH");
        assertThat(priority(fromFund)).as("копилка, сконвертированная в плановое событие").isEqualTo("HIGH");
        assertThat(priority(fromDeletedWish)).as("ссылка удалённого источника остаётся").isEqualTo("HIGH");
    }

    @Test
    @DisplayName("ANO-215: своя «Хотелка» человека, характер, выбранный вручную, и строка хотелки не трогаются — и миграция не падает")
    void unrelatedRows_areLeftAlone() {
        category = insertCategory();
        String ownLow = insertPlan("LOW");
        String chosenByHand = insertPlan("MEDIUM");
        String source = insertWishlistSource(chosenByHand, false);
        // Ссылка на строку хотелки: так конверсия не делает, но миграция обязана пройти, а не упасть
        // на chk_wishlist_status_only_low.
        insertFundSource(source);

        applyMigration();

        assertThat(priority(ownLow)).as("своя строка человека, не из конверсии").isEqualTo("LOW");
        assertThat(priority(chosenByHand)).as("характер сменил сам человек").isEqualTo("MEDIUM");
        assertThat(priority(source)).as("строка хотелки — всегда «Хотелка»").isEqualTo("LOW");
    }

    private void applyMigration() {
        try {
            String sql = new String(new ClassPathResource("db/migration/V28__converted_purchase_booking.sql")
                    .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            jdbc.execute(sql);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private String uuid() {
        return jdbc.queryForObject("SELECT gen_random_uuid()::text", String.class);
    }

    private String insertCategory() {
        String id = uuid();
        jdbc.update("""
                INSERT INTO categories (id, name, type, is_deleted, is_system)
                VALUES (?::uuid, ?, 'EXPENSE', FALSE, FALSE)
                """, id, "Техника " + id);
        return id;
    }

    /** Плановая строка без статуса хотелки — так конверсия заводит план покупки. */
    private String insertPlan(String priority) {
        String id = uuid();
        jdbc.update("""
                INSERT INTO financial_events
                    (id, date, category_id, type, planned_amount, status, is_deleted, description,
                     created_at, priority, event_kind)
                VALUES (?::uuid, DATE '2026-10-01', ?::uuid, 'EXPENSE', 148512, 'PLANNED', FALSE,
                        'Ноутбук', now(), ?, 'PLAN')
                """, id, category, priority);
        return id;
    }

    /** Хотелка-событие, сконвертированная в план {@code planId}. */
    private String insertWishlistSource(String planId, boolean deleted) {
        String id = uuid();
        jdbc.update("""
                INSERT INTO financial_events
                    (id, date, category_id, type, planned_amount, status, is_deleted, description,
                     created_at, priority, event_kind, wishlist_status, converted_to_event_id)
                VALUES (?::uuid, DATE '2026-10-01', ?::uuid, 'EXPENSE', 148512, 'PLANNED', ?,
                        'Ноутбук', now(), 'LOW', 'PLAN', 'FIXED', ?::uuid)
                """, id, category, deleted, planId);
        return id;
    }

    /** Копилка, сконвертированная в план {@code planId}. */
    private void insertFundSource(String planId) {
        jdbc.update("""
                INSERT INTO target_funds (id, name, target_amount, purchase_type, wishlist_status, converted_to_event_id)
                VALUES (?::uuid, 'Ноутбук', 148512, 'SAVINGS', 'FIXED', ?::uuid)
                """, uuid(), planId);
    }

    private String priority(String id) {
        return jdbc.queryForObject("SELECT priority FROM financial_events WHERE id = ?::uuid", String.class, id);
    }
}
