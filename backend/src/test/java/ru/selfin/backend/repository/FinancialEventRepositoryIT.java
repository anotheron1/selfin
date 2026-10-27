package ru.selfin.backend.repository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.selfin.backend.model.Category;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.EventKind;
import ru.selfin.backend.model.enums.CategoryType;
import ru.selfin.backend.model.enums.EventStatus;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.Priority;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ANO-232: просрочка на главном экране («раньше по плану») и имена в её расшифровке шли в порядке хранения строк —
 * запрос {@code findOverdueMandatoryExpenses} был без {@code ORDER BY}, и порядок менялся от чужих записей. Юниты
 * этот запрос подменяют; что он сам отдаёт строки по дате, показывает только настоящая база.
 *
 * <p>Спека: {@code docs/superpowers/specs/2026-10-27-overdue-order-design.md}
 */
@SpringBootTest
@Testcontainers
class FinancialEventRepositoryIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired FinancialEventRepository eventRepository;
    @Autowired CategoryRepository categoryRepository;

    @AfterEach
    void cleanDb() {
        eventRepository.deleteAll();
        categoryRepository.deleteAll();
    }

    @Test
    @DisplayName("Просрочка — по дате, а в один день — по id, как бы строки ни лежали в таблице")
    void overdue_comesInDateOrder() {
        Category category = categoryRepository.save(Category.builder()
                .name("Обязательное ANO-232").type(CategoryType.EXPENSE).build());
        LocalDate today = LocalDate.now();
        LocalDate sameDay = today.minusDays(20);
        save(category, today.minusDays(5));
        FinancialEvent early = save(category, today.minusDays(40));
        // Шесть строк одного дня: случайные id легли бы по порядку случайно — один раз из 720.
        for (int i = 0; i < 6; i++) save(category, sameDay);
        // Правка переписывает строку в конец таблицы — так порядок и уезжал после чужих и своих записей.
        early.setDescription("тронута последней");
        eventRepository.save(early);

        List<FinancialEvent> overdue = eventRepository.findOverdueMandatoryExpenses(today.minusDays(100), today);

        assertThat(overdue).extracting(FinancialEvent::getDate)
                .containsExactly(today.minusDays(40), sameDay, sameDay, sameDay, sameDay, sameDay, sameDay,
                        today.minusDays(5));
        // Postgres сравнивает uuid по байтам без знака, а UUID.compareTo в Java — половинками со знаком: «8a…» для неё
        // меньше «09…». Порядок базы совпадает с порядком канонических строк uuid — по ним и сверяем.
        List<String> oneDay = overdue.stream().filter(e -> e.getDate().equals(sameDay))
                .map(FinancialEvent::getId).map(UUID::toString).toList();
        assertThat(oneDay).as("в один день — по id: порядок не зависит от того, какую строку трогали").isSorted();
    }

    private FinancialEvent save(Category category, LocalDate date) {
        return eventRepository.save(FinancialEvent.builder()
                .date(date)
                .category(category)
                .type(EventType.EXPENSE)
                .eventKind(EventKind.PLAN)
                .plannedAmount(new BigDecimal("1000"))
                .status(EventStatus.PLANNED)
                .priority(Priority.HIGH)
                .description("бронь")
                .deleted(false)
                .build());
    }
}
