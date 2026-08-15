package ru.selfin.backend;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.selfin.backend.model.Account;
import ru.selfin.backend.model.BalanceCheckpoint;
import ru.selfin.backend.model.Category;
import ru.selfin.backend.model.EventKind;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.enums.CategoryType;
import ru.selfin.backend.model.enums.EventStatus;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.Priority;
import ru.selfin.backend.model.enums.WishlistStatus;
import ru.selfin.backend.repository.AccountRepository;
import ru.selfin.backend.repository.BalanceCheckpointRepository;
import ru.selfin.backend.repository.CategoryRepository;
import ru.selfin.backend.repository.FinancialEventRepository;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * ЛОВУШКА ревью Task 2.1/2.3 (ANO-9): {@code CapitalService.liquidAt} до этой правки суммировал
 * факты через {@code FinancialEventRepository.sumFactByTypeBetween}, который НЕ фильтрует
 * {@code wishlistStatus} — в отличие от {@code AccountBalanceService.factsDelta} и
 * {@code PocketEngine.calculate}, которые хотелки из currentBalance исключают сознательно.
 *
 * <p>Реальный (не замоканный) DB-тест: только он может доказать, что JPQL-запрос
 * {@code sumFactByTypeBetween} действительно не смотрит на {@code wishlist_status} — юнит-тест
 * на моке подтвердить это не может, он лишь проверяет, что {@code CapitalService} передаёт
 * репозиторию правильные аргументы, а не то, что сам SQL фильтрует верно.
 *
 * <p><b>История числа.</b> ДО правки (ANO-9 Task 2.3) этот тест — на неизменённом
 * {@code CapitalService.liquidAt} — был запущен и зафиксировал {@code liquid = 85000}: факт по
 * хотелке (−15 000, EXPENSE) ошибочно вычитался из ликвида, хотя кармашек его игнорирует.
 * ПОСЛЕ правки {@code liquidAt} идёт через {@code AccountBalanceService.balanceAt} →
 * {@code factsDelta}, который фильтрует {@code wishlistStatus == null} — результат стал
 * {@code 100000} (факт по хотелке не влияет на ликвид, как и на кармашек). Решение осознанное:
 * капитал и кармашек обязаны показывать одну и ту же ликвидность (см. Javadoc
 * {@link ru.selfin.backend.service.CapitalService#liquidAt}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class CapitalWishlistFactIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired MockMvc mockMvc;
    @Autowired AccountRepository accountRepository;
    @Autowired BalanceCheckpointRepository checkpointRepository;
    @Autowired FinancialEventRepository eventRepository;
    @Autowired CategoryRepository categoryRepository;

    @AfterEach
    void cleanDb() {
        eventRepository.deleteAll();
        checkpointRepository.deleteAll();
        categoryRepository.deleteAll();
    }

    @Test
    void liquid_wishlistExpenseFact_doesNotReduceLiquid() throws Exception {
        Account defaultAccount = accountRepository.findByDefaultAccountTrueAndDeletedFalse().orElseThrow();
        checkpointRepository.save(BalanceCheckpoint.builder()
                .date(LocalDate.now().minusDays(10))
                .amount(new BigDecimal("100000"))
                .account(defaultAccount)
                .build());

        Category category = categoryRepository.save(Category.builder()
                .name("Хотелка ANO-9 IT").type(CategoryType.EXPENSE).build());

        // Факт по хотелке: wishlistStatus != null, но factAmount задан (то самое сочетание,
        // которое ревью назвало ловушкой — не должно уменьшать ликвид капитала).
        eventRepository.save(FinancialEvent.builder()
                .date(LocalDate.now().minusDays(3))
                .category(category)
                .type(EventType.EXPENSE)
                .eventKind(EventKind.FACT)
                .factAmount(new BigDecimal("15000"))
                .status(EventStatus.EXECUTED)
                .priority(Priority.LOW) // chk_wishlist_status_only_low (V18): хотелки только LOW
                .wishlistStatus(WishlistStatus.FIXED)
                .deleted(false)
                .build());

        // 100 000 (чекпоинт), факт по хотелке НЕ вычитается (согласовано с кармашком).
        mockMvc.perform(get("/api/v1/capital/summary"))
                .andExpect(jsonPath("$.liquid").value(100000));
    }
}
