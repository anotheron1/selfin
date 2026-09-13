package ru.selfin.backend;

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

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * ANO-103. Миграция V22 снимает chk_event_converted_only_fixed и chk_fund_converted_only_fixed.
 *
 * <p><b>Почему тест устроен именно так.</b> Обычный IT на Testcontainers эту починку проверить
 * не может: он поднимает СВЕЖУЮ базу из текущих файлов миграций, а в них этих ограничений нет
 * с 17.06.2026 — коммит f26ea93 убрал их из текста уже применённой V18, и Flyway применённые
 * версии не перезапускает. Контрольная сумма в истории оказалась подогнана под новый текст,
 * поэтому {@code flyway validate} молчит. В итоге ограничения живут в базах, созданных ДО той
 * даты (стенд — одна из них), и отсутствуют в свежих. Тест «просто поменяй статус» прошёл бы
 * на свежей базе и без V22 — то есть был бы пустым.
 *
 * <p>Поэтому тест сначала ВОССОЗДАЁТ состояние старой базы, а потом исполняет настоящий файл
 * V22 с classpath. Файл читается, а не копируется в тест: вычеркните из миграции строку —
 * и соответствующая проверка покраснеет. Это и есть мутация, которой тест проверяется.
 */
@SpringBootTest
@Testcontainers
class ConvertedOnlyFixedDropIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    private static final String EVENT_CONSTRAINT = "chk_event_converted_only_fixed";
    private static final String FUND_CONSTRAINT = "chk_fund_converted_only_fixed";

    @Autowired JdbcTemplate jdbc;

    /** Возвращает базу в состояние «создана до 17.06.2026»: оба ограничения на месте. */
    @BeforeEach
    void recreateLegacyConstraints() {
        dropIfPresent("financial_events", EVENT_CONSTRAINT);
        dropIfPresent("target_funds", FUND_CONSTRAINT);
        jdbc.execute("ALTER TABLE financial_events ADD CONSTRAINT " + EVENT_CONSTRAINT
                + " CHECK ((converted_to_event_id IS NULL AND converted_to_fund_id IS NULL)"
                + " OR wishlist_status = 'FIXED')");
        jdbc.execute("ALTER TABLE target_funds ADD CONSTRAINT " + FUND_CONSTRAINT
                + " CHECK ((converted_to_event_id IS NULL AND converted_to_fund_id IS NULL)"
                + " OR wishlist_status = 'FIXED')");
    }

    @Test
    @DisplayName("V22 снимает оба ограничения на базе, где они есть")
    void v22_dropsBothLegacyConstraints() {
        assertThat(constraintExists(EVENT_CONSTRAINT))
                .as("предусловие: старая база несёт ограничение на событиях").isTrue();
        assertThat(constraintExists(FUND_CONSTRAINT))
                .as("предусловие: старая база несёт ограничение на копилках").isTrue();

        applyMigrationV22();

        assertThat(constraintExists(EVENT_CONSTRAINT))
                .as("V22 обязана снять ограничение с financial_events").isFalse();
        assertThat(constraintExists(FUND_CONSTRAINT))
                .as("V22 обязана снять ограничение с target_funds").isFalse();
    }

    @Test
    @DisplayName("после V22 сконвертированная строка уходит в OPEN и в DISMISSED, ссылка на артефакт жива")
    void afterV22_convertedRowCanLeaveFixed() {
        applyMigrationV22();

        // Строка в ровно той ловушке, что описана в ANO-103: FIXED и со ссылкой на артефакт.
        String eventId = insertConvertedFixedEvent();

        assertThatCode(() -> setStatus(eventId, "OPEN"))
                .as("возврат в обсуждение — прямое обещание спеки").doesNotThrowAnyException();
        assertThatCode(() -> setStatus(eventId, "DISMISSED"))
                .as("окончательный отказ — второй выход, тоже закрытый до V22")
                .doesNotThrowAnyException();

        // Ссылка переживает возврат: её сохранность и есть смысл перехода «артефакт остаётся».
        assertThat(jdbc.queryForObject(
                "SELECT converted_to_event_id IS NOT NULL FROM financial_events WHERE id = ?::uuid",
                Boolean.class, eventId)).isTrue();
    }

    @Test
    @DisplayName("V22 идемпотентна: на свежей базе без ограничений это no-op, повтор не падает")
    void v22_isNoOpWhenConstraintsAbsent() {
        applyMigrationV22();
        assertThatCode(this::applyMigrationV22).doesNotThrowAnyException();
        assertThat(constraintExists(EVENT_CONSTRAINT)).isFalse();
        assertThat(constraintExists(FUND_CONSTRAINT)).isFalse();
    }

    // ── оснастка ─────────────────────────────────────────────────────────────

    /**
     * Исполняет НАСТОЯЩИЙ файл миграции с classpath, а не его копию в тесте.
     * Именно поэтому правка V22 меняет исход теста.
     */
    private void applyMigrationV22() {
        String sql;
        try (var in = new ClassPathResource("db/migration/V22__drop_converted_only_fixed.sql")
                .getInputStream()) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("V22 не читается с classpath", e);
        }
        statementsOf(sql).forEach(jdbc::execute);
    }

    /** Режет файл на команды, отбрасывая комментарии и пустые строки. */
    private static List<String> statementsOf(String sql) {
        String withoutComments = Arrays.stream(sql.split("\\R"))
                .filter(l -> !l.stripLeading().startsWith("--"))
                .reduce("", (a, b) -> a + "\n" + b);
        return Arrays.stream(withoutComments.split(";"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private boolean constraintExists(String name) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM pg_constraint WHERE conname = ?", Integer.class, name);
        return n != null && n > 0;
    }

    private void dropIfPresent(String table, String constraint) {
        jdbc.execute("ALTER TABLE " + table + " DROP CONSTRAINT IF EXISTS " + constraint);
    }

    /**
     * Хотелка в ровно той ловушке, что описана в ANO-103: FIXED и со ссылкой на созданный
     * артефакт. Артефакт заводится настоящей строкой — {@code converted_to_event_id} несёт
     * внешний ключ на ту же таблицу, случайный UUID туда не проходит.
     */
    private String insertConvertedFixedEvent() {
        String categoryId = jdbc.queryForObject(
                "SELECT id::text FROM categories WHERE type = 'EXPENSE' LIMIT 1", String.class);
        String artifactId = insertPlainEvent(categoryId, "ANO-103 created plan", null);
        return insertPlainEvent(categoryId, "ANO-103 converted wish", artifactId);
    }

    /** @param convertedTo id артефакта либо {@code null} для обычного события */
    private String insertPlainEvent(String categoryId, String description, String convertedTo) {
        String id = jdbc.queryForObject("SELECT gen_random_uuid()::text", String.class);
        jdbc.update("""
                INSERT INTO financial_events
                    (id, date, type, event_kind, status, planned_amount, category_id,
                     description, priority, is_deleted, wishlist_status, converted_to_event_id)
                VALUES (?::uuid, CURRENT_DATE, 'EXPENSE', 'PLAN', 'PLANNED', 10000, ?::uuid,
                        ?, 'LOW', FALSE, ?, ?::uuid)
                """, id, categoryId, description,
                convertedTo == null ? null : "FIXED", convertedTo);
        return id;
    }

    private void setStatus(String eventId, String status) {
        jdbc.update("UPDATE financial_events SET wishlist_status = ? WHERE id = ?::uuid",
                status, eventId);
    }
}
