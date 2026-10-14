package ru.selfin.backend.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.selfin.backend.BackendApplication;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Приложение не стартует под ролью, которая обходит RLS (ANO-229): суперпользователь, {@code BYPASSRLS}, владелец
 * таблицы с {@code user_id} — или член роли-владельца: Postgres пускает мимо RLS и его. Роли-нарушители заводятся
 * в базе контейнера; проверка — тем же кодом, что при старте, и сам старт приложения под суперпользователем.
 *
 * <p>{@code jdbc} — роль владельца таблиц, суперпользователь контейнера: роли и пробную таблицу заводит она.
 *
 * <p>Спека: docs/superpowers/specs/2026-10-15-app-role-guard-design.md
 */
@SpringBootTest
@Testcontainers
class AppRoleGuardIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired DataSource pool;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("роль приложения selfin_app проверку проходит")
    void appRolePasses() {
        assertThatCode(() -> DatabaseRolesConfig.requireRowLevelSecurityRole(pool)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("суперпользователь — отказ: RLS на него не действует")
    void superuserRefused() {
        assertThatThrownBy(() -> DatabaseRolesConfig.requireRowLevelSecurityRole(
                login(postgres.getUsername(), postgres.getPassword())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("«" + postgres.getUsername() + "»").hasMessageContaining("суперпользователь");
    }

    @Test
    @DisplayName("роль с BYPASSRLS — отказ")
    void bypassRlsRefused() {
        jdbc.execute("CREATE ROLE guard_bypass LOGIN PASSWORD 'guard' BYPASSRLS");
        assertThatThrownBy(() -> DatabaseRolesConfig.requireRowLevelSecurityRole(login("guard_bypass", "guard")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("«guard_bypass»").hasMessageContaining("BYPASSRLS");
    }

    @Test
    @DisplayName("владелец таблицы с user_id — отказ, и член роли-владельца — тоже: Postgres пускает мимо RLS обоих")
    void tableOwnerAndOwnerMemberRefused() {
        jdbc.execute("CREATE ROLE guard_owner LOGIN PASSWORD 'guard'");
        jdbc.execute("CREATE ROLE guard_member LOGIN PASSWORD 'guard' IN ROLE guard_owner");
        jdbc.execute("CREATE TABLE guard_probe (id UUID PRIMARY KEY, user_id UUID)");
        jdbc.execute("ALTER TABLE guard_probe OWNER TO guard_owner");
        try {
            for (String role : new String[] {"guard_owner", "guard_member"}) {
                assertThatThrownBy(() -> DatabaseRolesConfig.requireRowLevelSecurityRole(login(role, "guard")))
                        .as(role).isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("«" + role + "»").hasMessageContaining("владелец таблиц guard_probe");
            }
        } finally {
            jdbc.execute("DROP TABLE guard_probe");
        }
    }

    @Test
    @DisplayName("приложение под суперпользователем не стартует: проверка стоит на старте")
    void appRefusesToStartAsSuperuser() {
        // Аргументами командной строки: свойства построителя — значения по умолчанию, их перебил бы application.properties.
        assertThatThrownBy(() -> new SpringApplicationBuilder(BackendApplication.class).run(
                        "--spring.datasource.url=" + postgres.getJdbcUrl(),
                        "--spring.datasource.username=" + postgres.getUsername(),
                        "--spring.datasource.password=" + postgres.getPassword(),
                        "--selfin.db.app-username=" + postgres.getUsername(),
                        "--selfin.db.app-password=" + postgres.getPassword(),
                        "--server.port=0")
                .close())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("суперпользователь");
    }

    private static DataSource login(String role, String password) {
        return new DriverManagerDataSource(postgres.getJdbcUrl(), role, password);
    }
}
