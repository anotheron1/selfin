package ru.selfin.backend;

import jakarta.persistence.Column;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Table;
import jakarta.persistence.metamodel.EntityType;
import org.hibernate.annotations.TenantId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.flyway.autoconfigure.FlywayDataSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.support.Repositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.selfin.backend.model.Category;
import ru.selfin.backend.model.enums.CategoryType;
import ru.selfin.backend.repository.CategoryRepository;
import ru.selfin.backend.service.OwnerContext;
import ru.selfin.backend.service.OwnerService;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Второй слой изоляции (ANO-147) — механизм и схема, двойник {@link RowLevelSecurityIT}: {@code @TenantId} на
 * сущностях, составные ключи ссылок, владелец в нативных запросах. По всем ручкам второй слой проверяет второй тест
 * {@link TwoPeopleIsolationIT} — тот же сценарий с выключенным RLS.
 *
 * <p>{@code jdbc} — роль владельца таблиц, с владельцем потока на соединении ({@code testsupport/DataAsTableOwner}).
 *
 * <p>Спека: docs/superpowers/specs/2026-10-15-tenant-id-second-layer-design.md
 */
@SpringBootTest
@Testcontainers
class SecondLayerIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired JdbcTemplate jdbc;
    @Autowired @FlywayDataSource DataSource tableOwner;
    @Autowired EntityManagerFactory entities;
    @Autowired ApplicationContext context;
    @Autowired CategoryRepository categories;
    @Autowired OwnerService owners;

    @Test
    @DisplayName("у каждой таблицы с владельцем — сущность с @TenantId на user_id, и наоборот: новая без него не пройдёт")
    void everyOwnerTableHasTenantEntity() {
        Set<String> tenantTables = entities.getMetamodel().getEntities().stream().map(EntityType::getJavaType)
                .filter(type -> Arrays.stream(type.getDeclaredFields()).anyMatch(SecondLayerIT::isOwnerTenantId))
                .map(type -> type.getAnnotation(Table.class).name())
                .collect(Collectors.toSet());
        assertThat(tenantTables).containsExactlyInAnyOrderElementsOf(ownerTables());
    }

    @Test
    @DisplayName("каждая ссылка между таблицами с владельцем — составным ключом по user_id: на чужую строку не сослаться")
    void everyReferenceKeepsOwner() {
        String[] owned = ownerTables().toArray(String[]::new);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_constraint c
                 WHERE c.contype = 'f' AND c.conrelid::regclass::text = ANY (?) AND c.confrelid::regclass::text = ANY (?)
                """, Long.class, owned, owned)).as("ссылки между таблицами с владельцем есть").isPositive();
        List<String> withoutOwner = jdbc.queryForList("""
                SELECT c.conrelid::regclass || ' ' || c.conname FROM pg_constraint c
                  JOIN pg_attribute child ON child.attrelid = c.conrelid AND child.attname = 'user_id'
                  JOIN pg_attribute parent ON parent.attrelid = c.confrelid AND parent.attname = 'user_id'
                 WHERE c.contype = 'f' AND c.conrelid::regclass::text = ANY (?)
                   AND NOT coalesce(array_position(c.conkey, child.attnum) = array_position(c.confkey, parent.attnum), false)
                """, String.class, (Object) owned);
        assertThat(withoutOwner).as("ключи, что пускают ссылку на строку другого владельца").isEmpty();
    }

    @Test
    @DisplayName("каждый нативный запрос пишет владельца — current_owner(): фильтр Hibernate нативного SQL не касается")
    void everyNativeQueryNamesOwner() {
        Repositories repositories = new Repositories(context);
        List<String> natives = new ArrayList<>();
        List<String> withoutOwner = new ArrayList<>();
        for (Class<?> domain : repositories) {
            Class<?> repository = repositories.getRepositoryInformationFor(domain).orElseThrow().getRepositoryInterface();
            for (Method method : repository.getDeclaredMethods()) {
                Query query = method.getAnnotation(Query.class);
                if (query == null || !query.nativeQuery()) continue;
                natives.add(repository.getSimpleName() + "." + method.getName());
                if (!query.value().contains("current_owner()")) withoutOwner.add(natives.getLast());
            }
        }
        assertThat(natives).as("нативные запросы есть — сторож не холостой").isNotEmpty();
        assertThat(withoutOwner).as("нативные запросы без владельца").isEmpty();
    }

    @Test
    @DisplayName("без владельца — ни строки, а запись — отказ: и без RLS «никто» не владеет ничем")
    void noOwnerSeesAndWritesNothing() {
        withoutRowLevelSecurity(() -> as(null, () -> {
            assertThat(categories.findAll()).isEmpty();
            assertThatThrownBy(() -> categories.saveAndFlush(
                    Category.builder().name("Ничья").type(CategoryType.EXPENSE).build()))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .rootCause().hasMessageContaining("users");
        }));
        assertThat(categories.findAll()).as("у синтетического владельца — стартовые категории").isNotEmpty();
    }

    @Test
    @DisplayName("писать другому нельзя: строка с чужим владельцем — отказ Hibernate, до базы")
    void cannotWriteForSomeoneElse() {
        UUID other = owners.resolve("second-layer-other");
        assertThatThrownBy(() -> categories.save(Category.builder().name("Подброшенная").type(CategoryType.EXPENSE)
                .userId(other).build()))
                .rootCause().hasMessageContaining("assigned tenant id differs");
    }

    @Test
    @DisplayName("вставка ролью владельца таблиц без владельца сеанса — отказ: синтетического запасного нет")
    void tableOwnerInsertWithoutOwnerFails() {
        assertThatThrownBy(() -> new JdbcTemplate(tableOwner).update("""
                INSERT INTO categories (id, name, type, is_deleted, is_system, forecast_enabled)
                VALUES (gen_random_uuid(), 'Без владельца', 'EXPENSE', FALSE, FALSE, FALSE)
                """)).rootCause().hasMessageContaining("null value in column \"user_id\"");
    }

    // ── помощники ───────────────────────────────────────────────────────────────

    private static boolean isOwnerTenantId(Field field) {
        Column column = field.getAnnotation(Column.class);
        return field.isAnnotationPresent(TenantId.class) && column != null && column.name().equals("user_id");
    }

    private List<String> ownerTables() {
        return jdbc.queryForList("""
                SELECT table_name FROM information_schema.columns
                 WHERE table_schema = 'public' AND column_name = 'user_id'""", String.class);
    }

    /** RLS выключен на время работы: чужое может прятать только приложение. */
    private void withoutRowLevelSecurity(Runnable work) {
        ownerTables().forEach(table -> jdbc.execute("ALTER TABLE " + table + " DISABLE ROW LEVEL SECURITY"));
        try {
            work.run();
        } finally {
            ownerTables().forEach(table -> jdbc.execute("ALTER TABLE " + table + " ENABLE ROW LEVEL SECURITY"));
        }
    }

    private static void as(UUID owner, Runnable work) {
        UUID previous = OwnerContext.get();
        OwnerContext.set(owner);
        try {
            work.run();
        } finally {
            OwnerContext.set(previous);
        }
    }
}
