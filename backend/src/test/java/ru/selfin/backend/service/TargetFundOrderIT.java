package ru.selfin.backend.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.selfin.backend.dto.TargetFundDto;
import ru.selfin.backend.model.TargetFund;
import ru.selfin.backend.repository.TargetFundRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ANO-202: копилка после пополнения уезжала в конец списка на «Целях» и в быстром вводе «В копилку». Список шёл по
 * приоритету, а он у всех копилок один (100): при равенстве Postgres отдаёт строки в порядке хранения, и правка
 * переписывает строку в конец таблицы. Порядок — приоритет, потом время заведения.
 *
 * <p>Спека: {@code docs/superpowers/specs/2026-10-27-funds-order-design.md}
 */
@SpringBootTest
@Testcontainers
class TargetFundOrderIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired TargetFundService fundService;
    @Autowired TargetFundRepository fundRepository;

    @AfterEach
    void cleanDb() {
        fundRepository.deleteAll();
    }

    @Test
    @DisplayName("Копилка, тронутая последней, остаётся на своём месте — порядок по времени заведения")
    void touchedFund_keepsItsPlace() {
        // Шесть копилок: порядок по случайным id совпал бы с порядком заведения один раз из 720.
        List<String> created = List.of("Горнолыжка", "Египет", "Машина", "Ремонт", "Отпуск", "Подушка");
        TargetFund first = null;
        for (int i = 0; i < created.size(); i++) {
            TargetFund fund = save(created.get(i), LocalDateTime.of(2026, 9, 1 + i, 10, 0));
            if (i == 0) first = fund;
        }
        // Пополнение меняет остаток — строка переписывается в конец таблицы.
        first.setCurrentBalance(new BigDecimal("1000"));
        fundRepository.save(first);

        assertThat(names()).containsExactlyElementsOf(created);
    }

    @Test
    @DisplayName("Приоритет по-прежнему главнее: копилка с меньшим числом — выше, даже заведённая позже")
    void priority_stillComesFirst() {
        save("Поздняя, но главная", LocalDateTime.of(2026, 9, 3, 10, 0), 50);
        save("Ранняя", LocalDateTime.of(2026, 9, 1, 10, 0), 100);

        assertThat(names()).containsExactly("Поздняя, но главная", "Ранняя");
    }

    private List<String> names() {
        return fundService.getOverview().funds().stream().map(TargetFundDto::name).toList();
    }

    private TargetFund save(String name, LocalDateTime createdAt) {
        return save(name, createdAt, 100);
    }

    private TargetFund save(String name, LocalDateTime createdAt, int priority) {
        return fundRepository.save(TargetFund.builder()
                .name(name).priority(priority).createdAt(createdAt).build());
    }
}
