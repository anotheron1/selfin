package ru.selfin.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Smoke-тест: контекст приложения поднимается на настоящем PostgreSQL (все Flyway-миграции).
 * Бывший BackendApplicationTests требовал локальный PostgreSQL на 5432 и падал без него
 * в фазе `test` — теперь это *IT на Testcontainers в фазе `verify`.
 */
@SpringBootTest
@Testcontainers
class BackendApplicationIT {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

	@Test
	void contextLoads() {
	}

}
