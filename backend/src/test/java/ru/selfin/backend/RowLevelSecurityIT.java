package ru.selfin.backend;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.selfin.backend.service.OwnerContext;
import ru.selfin.backend.service.OwnerService;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ограничитель изоляции (ANO-144): политики RLS на каждой таблице с владельцем, приложение — ролью
 * {@code selfin_app}, на которую они действуют, владелец — на каждом соединении из пула.
 *
 * <p>{@code jdbc} — роль владельца таблиц (так IT готовят данные), {@code dataSource} — пул приложения.
 * В пуле одно соединение: проверка «пул не уносит владельца» идёт на том же физическом соединении.
 *
 * <p>Спека: docs/superpowers/specs/2026-10-14-row-level-security-design.md
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.hikari.maximum-pool-size=1")
@AutoConfigureMockMvc
@Testcontainers
class RowLevelSecurityIT {

    static final String SECOND_SUB = "second-person";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired OwnerService owners;

    @Test
    @DisplayName("у каждой таблицы с владельцем — RLS и политика owner_rows: новая таблица без них не пройдёт")
    void everyOwnerTableIsGuarded() {
        List<String> ownerTables = jdbc.queryForList("""
                SELECT table_name FROM information_schema.columns
                 WHERE table_schema = 'public' AND column_name = 'user_id' ORDER BY table_name
                """, String.class);
        assertThat(ownerTables).hasSize(11);
        List<String> unguarded = jdbc.queryForList("""
                SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                 WHERE n.nspname = 'public' AND c.relname = ANY (?)
                   AND NOT (c.relrowsecurity AND EXISTS (SELECT 1 FROM pg_policies p
                             WHERE p.schemaname = 'public' AND p.tablename = c.relname AND p.policyname = 'owner_rows'))
                """, String.class, (Object) ownerTables.toArray(String[]::new));
        assertThat(unguarded).as("таблицы без RLS или без политики").isEmpty();
    }

    @Test
    @DisplayName("пул приложения — selfin_app: не суперпользователь, без BYPASSRLS, не владелец таблиц")
    void appPoolIsAppRole() {
        Map<String, Object> role = app().queryForMap("""
                SELECT current_user AS name, r.rolsuper, r.rolbypassrls,
                       (SELECT count(*) FROM pg_tables t WHERE t.schemaname = 'public' AND t.tableowner = current_user) AS owned
                  FROM pg_roles r WHERE r.rolname = current_user
                """);
        assertThat(role).containsEntry("name", "selfin_app").containsEntry("rolsuper", false)
                .containsEntry("rolbypassrls", false).containsEntry("owned", 0L);
    }

    @Test
    @DisplayName("второй человек не видит категорий первого и пишет своё — людей различает сервер")
    void secondPersonSeesOnlyOwn() throws Exception {
        assertThat(categories(null)).as("первый — синтетический владелец со стартовыми категориями").isNotEmpty();
        assertThat(categories(second())).as("второму чужие категории не видны").isEmpty();

        mvc.perform(post("/api/v1/categories").with(second()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Своя второго\",\"type\":\"EXPENSE\"}"))
                .andExpect(status().isOk());

        assertThat(categories(second())).containsExactly("Своя второго");
        assertThat(categories(null)).doesNotContain("Своя второго");
        assertThat(jdbc.queryForObject("SELECT user_id FROM categories WHERE name = 'Своя второго'", UUID.class))
                .as("строку получил второй, а не синтетический").isEqualTo(secondOwner())
                .isNotEqualTo(OwnerService.SYNTHETIC_OWNER);
    }

    @Test
    @DisplayName("писать чужому нельзя: строка с чужим user_id — отказ политики")
    void cannotWriteForSomeoneElse() {
        UUID second = secondOwner();
        assertThatThrownBy(() -> as(second, () -> app().update("""
                INSERT INTO categories (id, name, type, is_deleted, is_system, forecast_enabled, user_id)
                VALUES (gen_random_uuid(), 'Подброшенная', 'EXPENSE', FALSE, FALSE, FALSE, ?)
                """, OwnerService.SYNTHETIC_OWNER)))
                .rootCause().hasMessageContaining("violates row-level security policy");
    }

    @Test
    @DisplayName("без владельца — ни строки, а запись — отказ: забытый владелец не отдаёт чужое")
    void noOwnerSeesNothing() {
        as(null, () -> {
            assertThat(app().queryForObject("SELECT count(*) FROM categories", Long.class)).isZero();
            // Владелец в строке явно: без него запись остановил бы NOT NULL раньше политики (V33).
            assertThatThrownBy(() -> app().update("""
                    INSERT INTO categories (id, name, type, is_deleted, is_system, forecast_enabled, user_id)
                    VALUES (gen_random_uuid(), 'Ничья', 'EXPENSE', FALSE, FALSE, FALSE, ?)
                    """, OwnerService.SYNTHETIC_OWNER)).rootCause().hasMessageContaining("violates row-level security policy");
            return null;
        });
    }

    @Test
    @DisplayName("пул не уносит владельца: соединение после второго человека без владельца пусто")
    void poolDoesNotCarryOwner() {
        UUID second = secondOwner();
        as(second, () -> app().update("""
                INSERT INTO categories (id, name, type, is_deleted, is_system, forecast_enabled)
                VALUES (gen_random_uuid(), 'След второго', 'EXPENSE', FALSE, FALSE, FALSE)
                """));
        assertThat(as(second, () -> app().queryForObject("SELECT current_setting('selfin.user_id')", String.class)))
                .isEqualTo(second.toString());
        assertThat(as(null, () -> app().queryForObject("SELECT count(*) FROM categories", Long.class)))
                .as("то же физическое соединение, владелец снят").isZero();
    }

    // ── помощники ───────────────────────────────────────────────────────────────

    private JdbcTemplate app() {
        return new JdbcTemplate(dataSource);
    }

    private static RequestPostProcessor second() {
        return oidcLogin().idToken(t -> t.subject(SECOND_SUB));
    }

    private UUID secondOwner() {
        return owners.resolve(SECOND_SUB);
    }

    /** Имена категорий, которые видит человек: {@code null} — пользователь MockMvc по умолчанию. */
    private List<String> categories(RequestPostProcessor who) throws Exception {
        var request = get("/api/v1/categories");
        String body = mvc.perform(who == null ? request : request.with(who))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(body, "$[*].name");
    }

    private static <T> T as(UUID owner, Supplier<T> work) {
        UUID previous = OwnerContext.get();
        OwnerContext.set(owner);
        try {
            return work.get();
        } finally {
            OwnerContext.set(previous);
        }
    }
}
