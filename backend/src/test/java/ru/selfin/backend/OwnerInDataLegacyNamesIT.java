package ru.selfin.backend;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ANO-146: на базе той породы, что у владельца, уникальности названы Hibernate, а не {@code V1}. Там история
 * Flyway начинается с версии 2: схему когда-то завёл Hibernate, {@code V1} не выполнялась — так на дампе стенда
 * {@code stand-before-v29-2026-10-05.sql}. Миграция, снимающая ограничение по имени из {@code V1}, на такой базе
 * промахнулась бы, и второй человек упёрся бы в старый замок.
 *
 * <p>Схема поднимается до {@code V30}, ограничения переименовываются, как на стенде, — затем исполняется
 * <b>сам файл</b> {@code V31} одной транзакцией, как его исполнил бы Flyway.
 *
 * <p>Роли приложения до {@code V32} нет (ANO-144): приложение здесь — пользователем контейнера. Сверки схемы с
 * сущностями тоже нет: до {@code V31} колонки {@code user_id}, которую сущности отображают с ANO-147, ещё нет, а
 * JPA этот тест не использует.
 *
 * <p>Спека: {@code docs/superpowers/specs/2026-10-12-owner-in-data-design.md}.
 */
@SpringBootTest(properties = {"spring.flyway.target=30", "selfin.db.app-username=test", "selfin.db.app-password=test",
        "spring.jpa.hibernate.ddl-auto=none"})
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OwnerInDataLegacyNamesIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired JdbcTemplate jdbc;
    /**
     * Транзакция — на том же источнике данных, что у {@code jdbc}: с ANO-144 он у тестов — роль владельца таблиц,
     * отдельно от пула приложения, и транзакция JPA его соединение не охватила бы — откат не отменил бы индекс.
     */
    private TransactionTemplate asTableOwner() {
        return new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    }

    @Test
    @Order(1)
    @DisplayName("ANO-146: уникальность только по имени категории осталась индексом, а не ограничением — миграция падает, а не оставляет замок")
    void leftoverSingleColumnUniqueIndex_failsMigration() {
        // Одной транзакцией: отказ откатывает и индекс, и всё, что миграция успела, — следующий тест видит V30.
        assertThatThrownBy(() -> asTableOwner().executeWithoutResult(status -> {
            jdbc.execute("CREATE UNIQUE INDEX legacy_categories_name ON categories (name)");
            jdbc.execute(migration());
        })).hasMessageContaining("осталась уникальность без владельца");
    }

    @Test
    @Order(2)
    @DisplayName("ANO-146: уникальности с именами Hibernate, как на стенде и у владельца, сняты; уникальности по владельцу заведены")
    void hibernateNamedConstraints_areReplacedByOwnerScoped() {
        jdbc.execute("ALTER TABLE categories RENAME CONSTRAINT categories_name_unique TO ukt8o6pivur7nn124jehx7cygw5");
        jdbc.execute("ALTER TABLE financial_events RENAME CONSTRAINT financial_events_idempotency_key_unique TO uk8ah901xrytr2g6iijijfoeomu");
        jdbc.execute("ALTER TABLE fund_transactions RENAME CONSTRAINT fund_transactions_idempotency_key_unique TO uk4k0iv9ql6nf0rucnplt8jebo7");

        asTableOwner().executeWithoutResult(status -> jdbc.execute(migration()));

        assertThat(uniqueConstraints("categories")).containsExactly("user_id,name");
        assertThat(uniqueConstraints("financial_events")).containsExactly("user_id,idempotency_key");
        assertThat(uniqueConstraints("fund_transactions")).containsExactly("user_id,idempotency_key");
        assertThat(uniqueConstraints("user_settings")).containsExactly("user_id,settings_key");
    }

    /** Уникальные ограничения таблицы — колонки через запятую, в порядке ключа. */
    private List<String> uniqueConstraints(String table) {
        return jdbc.queryForList("""
                SELECT string_agg(a.attname, ',' ORDER BY k.ord)
                FROM pg_constraint c
                CROSS JOIN LATERAL unnest(c.conkey) WITH ORDINALITY AS k (attnum, ord)
                JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.attnum
                WHERE c.conrelid = ?::regclass AND c.contype = 'u'
                GROUP BY c.conname
                """, String.class, table);
    }

    private String migration() {
        try {
            return new String(new ClassPathResource("db/migration/V31__owner_in_data.sql")
                    .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
