package ru.selfin.backend.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.flyway.autoconfigure.FlywayDataSource;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import ru.selfin.backend.service.OwnerContext;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Две роли базы (ANO-144). Миграции — ролью из {@code spring.datasource.*}, владельцем таблиц: RLS ей не мешает.
 * Приложение — ролью {@code selfin_app}, на которую действуют политики, и с владельцем на каждом соединении.
 *
 * <p>Роль пулу ставит свой пост-процессор, а не {@code spring.datasource.hikari.username}: Boot 4 после привязки
 * свойств переписывает пулу пользователя из connection details, если они не из свойств, — у контейнеров тестов.
 * IT тогда тихо остались бы суперпользователем, и проверка изоляции была бы зелёной вхолостую.
 *
 * <p>Спека: docs/superpowers/specs/2026-10-14-row-level-security-design.md
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
     * Владелец — на каждое соединение из пула: свой у потока, без него — пустой. Каждая выдача перезаписывает
     * прежнего, поэтому соединение не унесёт чужого владельца в следующий запрос. На выдаче, а не на транзакции:
     * ленивые загрузки после транзакции идут на соединении вне её.
     */
    static final class OwnerAwareDataSource extends DelegatingDataSource {

        OwnerAwareDataSource(DataSource pool) {
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
