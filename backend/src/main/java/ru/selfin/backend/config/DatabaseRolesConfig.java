package ru.selfin.backend.config;

import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.flyway.autoconfigure.FlywayDataSource;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import ru.selfin.backend.service.OwnerContext;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Две роли базы (ANO-144). Миграции — ролью из {@code spring.datasource.*}, владельцем таблиц: RLS ей не мешает.
 * Приложение — ролью {@code selfin_app}, на которую действуют политики, и с владельцем на каждом соединении.
 *
 * <p>Роль пулу ставит свой пост-процессор, а не {@code spring.datasource.hikari.username}: Boot 4 после привязки
 * свойств переписывает пулу пользователя из connection details, если они не из свойств, — у контейнеров тестов.
 * IT тогда тихо остались бы суперпользователем, и проверка изоляции была бы зелёной вхолостую.
 *
 * <p>Ошибка той же природы на сервере — пул под ролью, которая обходит RLS, — останавливает старт (ANO-229).
 *
 * <p>Спеки: docs/superpowers/specs/2026-10-14-row-level-security-design.md,
 * docs/superpowers/specs/2026-10-15-app-role-guard-design.md
 */
@Configuration
public class DatabaseRolesConfig {

    /** Миграции — тем, кто вошёл по {@code spring.datasource.*}: владелец таблиц, без роли приложения. */
    @Bean(defaultCandidate = false)
    @FlywayDataSource
    DataSource flywayDataSource(JdbcConnectionDetails login) {
        return DataSourceBuilder.create().type(SimpleDriverDataSource.class)
                .url(login.getJdbcUrl()).username(login.getUsername()).password(login.getPassword())
                .build();
    }

    /**
     * Пароль роли приложения — из {@code selfin.db.app-password}, после каждого {@code migrate} (ANO-197): {@code V32}
     * заводит роль с паролем разработки, на сервере — свой. Ставит роль миграций, владелец таблиц; в кавычки пароль берёт
     * сам Postgres ({@code format('%L')}), в текст ошибок он не попадает. Ручного шага на сервере нет — и забыть его нельзя.
     *
     * <p>Спека: docs/superpowers/specs/2026-10-17-server-compose-design.md
     */
    @Bean
    Callback appRolePassword(Environment environment) {
        return new Callback() {
            @Override
            public boolean supports(Event event, Context context) {
                return event == Event.AFTER_MIGRATE;
            }

            @Override
            public boolean canHandleInTransaction(Event event, Context context) {
                return true;
            }

            @Override
            public void handle(Event event, Context context) {
                try (PreparedStatement quote = context.getConnection()
                        .prepareStatement("SELECT format('ALTER ROLE %I PASSWORD %L', ?, ?)")) {
                    quote.setString(1, environment.getRequiredProperty("selfin.db.app-username"));
                    quote.setString(2, environment.getRequiredProperty("selfin.db.app-password"));
                    try (ResultSet alter = quote.executeQuery(); Statement statement = context.getConnection().createStatement()) {
                        alter.next();
                        statement.execute(alter.getString(1));
                    }
                } catch (SQLException e) {
                    throw new IllegalStateException("пароль роли приложения не поставлен: " + e.getSQLState(), e);
                }
            }

            @Override
            public String getCallbackName() {
                return "appRolePassword";
            }
        };
    }

    /** Пул приложения: роль {@code selfin_app} — после всех пост-процессоров Boot, и владелец на выдаче. */
    @Bean
    static BeanPostProcessor appRolePool(Environment environment) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String name) {
                if (bean instanceof HikariDataSource pool) {
                    pool.setUsername(environment.getRequiredProperty("selfin.db.app-username"));
                    pool.setPassword(environment.getRequiredProperty("selfin.db.app-password"));
                }
                return bean;
            }

            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                return bean instanceof HikariDataSource pool ? new OwnerAwareDataSource(pool) : bean;
            }
        };
    }

    /**
     * Роль пула — та, на которую действуют политики RLS (ANO-229). Под суперпользователем, ролью с {@code BYPASSRLS}
     * или владельцем таблицы политики на месте, а защиты нет — и ни один тест этого не увидит: CI проверяет свою
     * настройку, не серверную. Проверка — после всех бинов, то есть после миграций, и до старта веб-сервера.
     * Выключателя нет: настройка «не проверять» была бы той же ошибкой сервера.
     */
    @Bean
    SmartInitializingSingleton appRoleGuard(DataSource pool) {
        return () -> requireRowLevelSecurityRole(pool);
    }

    /**
     * Отказ, если RLS роль пула пропускает. Владелец — не только по имени: Postgres пускает мимо RLS всякого, у кого
     * права роли-владельца, то есть и её члена, — отсюда {@code pg_has_role}.
     */
    static void requireRowLevelSecurityRole(DataSource pool) {
        Map<String, Object> role = new JdbcTemplate(pool).queryForMap("""
                SELECT r.rolname, r.rolsuper, r.rolbypassrls,
                       (SELECT string_agg(c.relname, ', ' ORDER BY c.relname)
                          FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                         WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p')
                           AND pg_has_role(c.relowner, 'USAGE')
                           AND EXISTS (SELECT 1 FROM pg_attribute a
                                        WHERE a.attrelid = c.oid AND a.attname = 'user_id' AND NOT a.attisdropped)) AS owned
                  FROM pg_roles r WHERE r.rolname = current_user
                """);
        List<String> why = new ArrayList<>();
        if ((Boolean) role.get("rolsuper")) why.add("суперпользователь");
        if ((Boolean) role.get("rolbypassrls")) why.add("BYPASSRLS");
        if (role.get("owned") != null) why.add("владелец таблиц " + role.get("owned"));
        if (!why.isEmpty()) {
            throw new IllegalStateException(("Пул приложения — роль «%s», а политики RLS на неё не действуют: %s. Под такой "
                    + "ролью видны деньги всех людей — приложение не стартует. Роль приложения задаёт "
                    + "selfin.db.app-username, нужна selfin_app (ANO-229).").formatted(role.get("rolname"), String.join("; ", why)));
        }
    }

    /**
     * Владелец — на каждое соединение из пула: свой у потока, без него — пустой. Каждая выдача перезаписывает
     * прежнего, поэтому соединение не унесёт чужого владельца в следующий запрос. На выдаче, а не на транзакции:
     * ленивые загрузки после транзакции идут на соединении вне её.
     *
     * <p>Открыт для IT: их данные ролью владельца таблиц пишутся тем же владельцем, что у потока теста (ANO-147).
     */
    public static final class OwnerAwareDataSource extends DelegatingDataSource {

        public OwnerAwareDataSource(DataSource pool) {
            super(pool);
        }

        @Override
        public Connection getConnection() throws SQLException {
            return withOwner(super.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return withOwner(super.getConnection(username, password));
        }

        private static Connection withOwner(Connection connection) throws SQLException {
            UUID owner = OwnerContext.get();
            try (PreparedStatement statement = connection.prepareStatement("SELECT set_config('selfin.user_id', ?, false)")) {
                statement.setString(1, owner == null ? "" : owner.toString());
                statement.execute();
            } catch (SQLException e) {
                connection.close();
                throw e;
            }
            return connection;
        }
    }
}
