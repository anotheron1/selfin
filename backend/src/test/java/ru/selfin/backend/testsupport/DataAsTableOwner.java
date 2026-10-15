package ru.selfin.backend.testsupport;

import org.springframework.boot.flyway.autoconfigure.FlywayDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.selfin.backend.config.DatabaseRolesConfig.OwnerAwareDataSource;

import javax.sql.DataSource;

/**
 * IT готовят и проверяют данные ролью владельца таблиц (ANO-144): {@code JdbcTemplate} тестов — на источнике
 * данных Flyway, мимо RLS. Миграционные IT исполняют им DDL, остальные — «сырые» вставки и проверки.
 * Приложение же ходит ролью {@code selfin_app}, как в проде, — на неё и действуют политики.
 *
 * <p>Владелец на соединении — тот же, что у потока теста (ANO-147): вставка без {@code user_id} получает его
 * значением по умолчанию, как вставка приложения. Миграции идут без владельца — для них такая вставка отказ.
 *
 * <p>Обычный {@code @Configuration}, а не {@code @TestConfiguration}: его подхватывает сканирование
 * {@code @SpringBootApplication}, и каждый IT получает его без правки.
 */
@Configuration
public class DataAsTableOwner {

    @Bean
    @Primary
    JdbcTemplate jdbcTemplate(@FlywayDataSource DataSource tableOwner) {
        return new JdbcTemplate(new OwnerAwareDataSource(tableOwner));
    }
}
