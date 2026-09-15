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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ANO-138: хотелки, чья конверсия создала событие с пустой датой (миграция {@code V25}).
 *
 * <p><b>Чего этот класс НЕ делает и почему.</b> Проверить миграцию её собственным прогоном
 * нельзя: на базе Testcontainers очередь {@code V25} наступает на пустых таблицах, и любое
 * утверждение о результате зелёное по построению. Это та же ловушка, которую разобрала
 * {@code V22} про ANO-103.
 *
 * <p>Поэтому проверяется <b>то же SQL на тех же данных</b>: заводится пара «FIXED-хотелка →
 * событие с пустой датой», применяется в точности запрос миграции, проверяется результат.
 * Расхождение между SQL здесь и SQL в {@code V25} — единственный способ обмануть эту
 * проверку, поэтому запрос скопирован дословно.
 *
 * <p>Спека: {@code docs/superpowers/specs/2026-09-15-wishlist-conversion-date-design.md}.
 */
@SpringBootTest
@Testcontainers
class DatelessConversionMigrationIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void cleanDb() {
        jdbc.update("UPDATE financial_events SET converted_to_event_id = NULL");
        jdbc.update("DELETE FROM financial_events");
        jdbc.update("DELETE FROM categories");
    }

    @Test
    @DisplayName("ANO-138: пара «FIXED-хотелка → событие без даты» разбирается обратно в обсуждение")
    void brokenPair_isReturnedToDiscussion() {
        String cat = insertCategory("Хотелки-1");
        String artifact = insertEvent(cat, "NULL", "NULL", "NULL", "Ноут");
        String wish = insertEvent(cat, "NULL", "'FIXED'", "'" + artifact + "'::uuid", "Ноут");

        applyMigrationSql();

        assertThat(isDeleted(artifact))
                .as("пустышка помечена удалённой: она всё равно невидима везде, где с ней можно что-то сделать")
                .isTrue();
        assertThat(wishlistStatus(wish))
                .as("хотелка вернулась в обсуждение — ровно то, что было до нажатия")
                .isEqualTo("OPEN");
        assertThat(convertedToEventId(wish))
                .as("ссылку снять обязательно: иначе ensureNotConverted отдаст 409 на повторной конверсии")
                .isNull();
        assertThat(isDeleted(wish))
                .as("саму хотелку миграция не удаляет")
                .isFalse();
    }

    @Test
    @DisplayName("ANO-138: здоровая конверсия не трогается")
    void healthyConversion_isLeftAlone() {
        // Без этого случая миграция «пометить удалёнными все события с пустой датой»
        // прошла бы зелёной.
        String cat = insertCategory("Хотелки-2");
        String artifact = insertEvent(cat, "DATE '2027-03-15'", "NULL", "NULL", "Велосипед");
        String wish = insertEvent(cat, "NULL", "'FIXED'", "'" + artifact + "'::uuid", "Велосипед");

        applyMigrationSql();

        assertThat(isDeleted(artifact))
                .as("у плана есть срок — он живой и видимый, трогать его не за что")
                .isFalse();
        assertThat(wishlistStatus(wish)).isEqualTo("FIXED");
        assertThat(convertedToEventId(wish)).isEqualTo(artifact);
    }

    @Test
    @DisplayName("ANO-138: сирота с пустой датой не трогается — её происхождение недоказуемо")
    void orphan_isLeftAlone() {
        String cat = insertCategory("Хотелки-3");
        String orphan = insertEvent(cat, "NULL", "NULL", "NULL", "Сирота");

        applyMigrationSql();

        assertThat(isDeleted(orphan))
                .as("на неё никто не ссылается: доказать, что она от конверсии, нечем")
                .isFalse();
    }

    // ====== helpers ======

    private String insertCategory(String name) {
        String id = jdbc.queryForObject("SELECT gen_random_uuid()::text", String.class);
        jdbc.update("""
                INSERT INTO categories (id, name, type, is_deleted)
                VALUES (?::uuid, ?, 'EXPENSE', FALSE)
                """, id, name);
        return id;
    }

    /**
     * @param dateExpr     SQL-выражение даты: {@code NULL} или {@code DATE '2027-03-15'}
     * @param statusExpr   SQL-выражение wishlist-статуса: {@code NULL} или {@code 'FIXED'}
     * @param convertedExpr SQL-выражение ссылки на артефакт: {@code NULL} или {@code '<uuid>'::uuid}
     */
    private String insertEvent(String categoryId, String dateExpr, String statusExpr,
                               String convertedExpr, String description) {
        String id = jdbc.queryForObject("SELECT gen_random_uuid()::text", String.class);
        jdbc.update("""
                INSERT INTO financial_events
                    (id, date, category_id, type, planned_amount, status, is_deleted,
                     description, created_at, priority, event_kind, wishlist_status,
                     converted_to_event_id)
                VALUES (?::uuid, %s, ?::uuid, 'EXPENSE', 150000, 'PLANNED', FALSE,
                        ?, now(), 'LOW', 'PLAN', %s, %s)
                """.formatted(dateExpr, statusExpr, convertedExpr), id, categoryId, description);
        return id;
    }

    private boolean isDeleted(String eventId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT is_deleted FROM financial_events WHERE id = ?::uuid", Boolean.class, eventId));
    }

    private String wishlistStatus(String eventId) {
        return jdbc.queryForObject(
                "SELECT wishlist_status FROM financial_events WHERE id = ?::uuid", String.class, eventId);
    }

    private String convertedToEventId(String eventId) {
        return jdbc.queryForObject(
                "SELECT converted_to_event_id::text FROM financial_events WHERE id = ?::uuid",
                String.class, eventId);
    }

    /** Дословно тело {@code V25__return_dateless_conversions.sql}. Расхождение здесь — дыра в проверке. */
    private void applyMigrationSql() {
        jdbc.update("""
                WITH broken AS (
                    SELECT s.id AS src_id, e.id AS event_id
                    FROM financial_events s
                    JOIN financial_events e ON e.id = s.converted_to_event_id
                    WHERE s.is_deleted = FALSE
                      AND e.is_deleted = FALSE
                      AND e.date IS NULL
                ),
                killed AS (
                    UPDATE financial_events e
                       SET is_deleted = TRUE, updated_at = CURRENT_TIMESTAMP
                      FROM broken b
                     WHERE e.id = b.event_id
                    RETURNING e.id
                )
                UPDATE financial_events s
                   SET wishlist_status = 'OPEN',
                       converted_to_event_id = NULL,
                       updated_at = CURRENT_TIMESTAMP
                  FROM broken b
                 WHERE s.id = b.src_id
                """);
    }
}
