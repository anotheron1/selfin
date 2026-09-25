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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ANO-188: уже заведённые платежи по кредиту переводятся в бронь (миграция {@code V27}).
 *
 * <p>Своим прогоном миграцию не проверить: на базе Testcontainers очередь {@code V27} наступает
 * на пустых таблицах, и любое утверждение о результате зелёное по построению (та же ловушка,
 * что разобрана у {@code V22} и {@code V25}). Поэтому данные заводятся здесь, а затем
 * исполняется <b>сам файл миграции</b> — не копия его запроса: копия могла бы разойтись
 * с оригиналом, и проверка сверяла бы не то, что уйдёт в базу.
 *
 * <p>Спека: {@code docs/superpowers/specs/2026-09-25-credit-payment-booking-design.md}.
 */
@SpringBootTest
@Testcontainers
class CreditPaymentBookingMigrationIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired JdbcTemplate jdbc;

    private final List<String> categories = new ArrayList<>();

    @AfterEach
    void cleanDb() {
        jdbc.update("DELETE FROM financial_events");
        jdbc.update("DELETE FROM recurring_rule");
        jdbc.update("DELETE FROM target_funds");
        categories.forEach(id -> jdbc.update("DELETE FROM categories WHERE id = ?::uuid", id));
        categories.clear();
    }

    @Test
    @DisplayName("ANO-188: правило платежа по кредиту и все его плановые события — в бронь, и без ссылки на копилку тоже")
    void creditPaymentRule_becomesBooking() {
        String credit = insertCategory("Кредит", true);
        String loan = insertFund("CREDIT");
        String rule = insertRule(credit, "EXPENSE");
        String linked = insertEvent(credit, loan, rule, "2026-10-15", "EXPENSE");
        // Перегенерированное событие правила: ссылку на копилку regenerate не ставит.
        String regenerated = insertEvent(credit, null, rule, "2026-11-15", "EXPENSE");

        applyMigration();

        assertThat(rulePriority(rule)).isEqualTo("HIGH");
        assertThat(eventPriority(linked)).isEqualTo("HIGH");
        assertThat(eventPriority(regenerated))
                .as("правило опознано по событиям — переводятся все его плановые события")
                .isEqualTo("HIGH");
    }

    @Test
    @DisplayName("ANO-188: «Кредит», заведённый человеком до первой конверсии, — тоже: конверсия берёт категорию по имени")
    void userCreatedCreditCategory_isConvertedToo() {
        // creditCategory() ищет «Кредит» по имени и системную создаёт, только если такой нет.
        String credit = insertCategory("Кредит", false);
        String loan = insertFund("CREDIT");
        String rule = insertRule(credit, "EXPENSE");
        String event = insertEvent(credit, loan, rule, "2026-10-15", "EXPENSE");

        applyMigration();

        assertThat(rulePriority(rule)).isEqualTo("HIGH");
        assertThat(eventPriority(event)).isEqualTo("HIGH");
    }

    @Test
    @DisplayName("ANO-188: другая категория, накопление и разовая покупка не трогаются")
    void unrelatedRows_areLeftAlone() {
        String credit = insertCategory("Кредит", true);
        String own = insertCategory("Кредиты-свои", false);
        String loan = insertFund("CREDIT");
        String savings = insertFund("SAVINGS");

        // Другая категория человека, хоть и ссылается на копилку-кредит.
        String ownRule = insertRule(own, "EXPENSE");
        String ownEvent = insertEvent(own, loan, ownRule, "2026-10-15", "EXPENSE");
        // «Кредит», но копилка — накопление.
        String savingsRule = insertRule(credit, "EXPENSE");
        String savingsEvent = insertEvent(credit, savings, savingsRule, "2026-10-15", "EXPENSE");
        // Разовая покупка из конверсии в плановое событие: правила нет.
        String purchase = insertEvent(credit, loan, null, "2026-10-15", "EXPENSE");

        applyMigration();

        assertThat(rulePriority(ownRule)).isEqualTo("MEDIUM");
        assertThat(eventPriority(ownEvent)).isEqualTo("MEDIUM");
        assertThat(rulePriority(savingsRule)).isEqualTo("MEDIUM");
        assertThat(eventPriority(savingsEvent)).isEqualTo("MEDIUM");
        assertThat(eventPriority(purchase)).isEqualTo("MEDIUM");
    }

    private void applyMigration() {
        try {
            String sql = new String(new ClassPathResource("db/migration/V27__credit_payment_booking.sql")
                    .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            jdbc.execute(sql);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private String uuid() {
        return jdbc.queryForObject("SELECT gen_random_uuid()::text", String.class);
    }

    private String insertCategory(String name, boolean system) {
        String id = uuid();
        jdbc.update("""
                INSERT INTO categories (id, name, type, is_deleted, is_system)
                VALUES (?::uuid, ?, 'EXPENSE', FALSE, ?)
                """, id, name, system);
        categories.add(id);
        return id;
    }

    private String insertFund(String purchaseType) {
        String id = uuid();
        jdbc.update("""
                INSERT INTO target_funds (id, name, target_amount, purchase_type, credit_rate, credit_term_months)
                VALUES (?::uuid, 'Машина', 2000000, ?, ?, ?)
                """, id, purchaseType,
                "CREDIT".equals(purchaseType) ? 16.5 : null,
                "CREDIT".equals(purchaseType) ? 60 : null);
        return id;
    }

    private String insertRule(String categoryId, String type) {
        String id = uuid();
        jdbc.update("""
                INSERT INTO recurring_rule
                    (id, category_id, event_type, planned_amount, priority, description,
                     frequency, day_of_month, start_date, is_deleted, created_at)
                VALUES (?::uuid, ?::uuid, ?, 49000, 'MEDIUM', 'Машина — платёж по кредиту',
                        'MONTHLY', 15, DATE '2026-10-15', FALSE, now())
                """, id, categoryId, type);
        return id;
    }

    /** Событие правила; у одного правила на дату — одно живое событие (uq_events_rule_date_active). */
    private String insertEvent(String categoryId, String fundId, String ruleId, String date, String type) {
        String id = uuid();
        jdbc.update("""
                INSERT INTO financial_events
                    (id, date, category_id, type, planned_amount, status, is_deleted, description,
                     created_at, priority, event_kind, target_fund_id, recurring_rule_id)
                VALUES (?::uuid, ?::date, ?::uuid, ?, 49000, 'PLANNED', FALSE,
                        'Машина — платёж по кредиту', now(), 'MEDIUM', 'PLAN', ?::uuid, ?::uuid)
                """, id, date, categoryId, type, fundId, ruleId);
        return id;
    }

    private String rulePriority(String id) {
        return jdbc.queryForObject("SELECT priority FROM recurring_rule WHERE id = ?::uuid", String.class, id);
    }

    private String eventPriority(String id) {
        return jdbc.queryForObject("SELECT priority FROM financial_events WHERE id = ?::uuid", String.class, id);
    }
}
