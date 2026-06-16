package ru.selfin.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.selfin.backend.model.Category;
import ru.selfin.backend.model.EventKind;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.TargetFund;
import ru.selfin.backend.model.enums.CategoryType;
import ru.selfin.backend.model.enums.EventStatus;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.FundPurchaseType;
import ru.selfin.backend.model.enums.FundStatus;
import ru.selfin.backend.model.enums.Priority;
import ru.selfin.backend.model.enums.WishlistStatus;
import ru.selfin.backend.repository.CategoryRepository;
import ru.selfin.backend.repository.FinancialEventRepository;
import ru.selfin.backend.repository.RecurringRuleRepository;
import ru.selfin.backend.repository.TargetFundRepository;
import ru.selfin.backend.repository.UserSettingsRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Интеграционные тесты HTTP-уровня для модуля /wishlist (Chunk 4).
 * Тестирует полный стек: HTTP → Controller → Service → Repository → БД (Testcontainers).
 *
 * <p>Ключевой field-mapping: {@code WishlistItemDto.targetDate} берётся из
 * {@code FinancialEvent.date}; чтобы хотелка дала непустую delta — у события должна быть
 * будущая {@code date} и {@code priority=LOW} (DB-constraint chk_wishlist_status_only_low).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class WishlistControllerIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper om;
    @Autowired FinancialEventRepository eventRepository;
    @Autowired TargetFundRepository fundRepository;
    @Autowired RecurringRuleRepository ruleRepository;
    @Autowired CategoryRepository categoryRepository;
    @Autowired UserSettingsRepository userSettingsRepository;

    @AfterEach
    void cleanDb() {
        // FK-safe order: события и фонды ссылаются друг на друга через converted_to_* (ON DELETE SET NULL),
        // recurring-правила ссылаются события. Чистим events → funds → rules → settings.
        eventRepository.deleteAll();
        fundRepository.deleteAll();
        ruleRepository.deleteAll();
        userSettingsRepository.deleteAll();
    }

    /** Первая не-удалённая EXPENSE-категория (засеяны миграцией V2). */
    private Category seededExpenseCategory() {
        return categoryRepository.findAll().stream()
                .filter(c -> !c.isDeleted() && c.getType() == CategoryType.EXPENSE)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No EXPENSE category found in DB"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Task 4.1 — simulation happy path on empty DB
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void getSimulation_emptyDb_returnsDefaults() throws Exception {
        mockMvc.perform(get("/api/v1/wishlist/simulation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.thresholds.cashBufferMonths").value(1.0));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Task 4.2 — wishlist item appears in simulation with delta; DISMISSED hidden
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void getSimulation_wishlistEvent_appearsWithDelta_andDismissedHidden() throws Exception {
        Category cat = seededExpenseCategory();
        LocalDate targetDate = LocalDate.now().plusMonths(6);

        // OPEN wishlist event with a future date → produces a single-month negative delta.
        FinancialEvent openItem = eventRepository.save(FinancialEvent.builder()
                .priority(Priority.LOW)
                .wishlistStatus(WishlistStatus.OPEN)
                .type(EventType.EXPENSE)
                .eventKind(EventKind.PLAN)
                .status(EventStatus.PLANNED)
                .plannedAmount(new BigDecimal("150000"))
                .date(targetDate)
                .category(cat)
                .description("Новый ноутбук")
                .build());

        // DISMISSED wishlist event — must NOT appear in the simulation list.
        FinancialEvent dismissedItem = eventRepository.save(FinancialEvent.builder()
                .priority(Priority.LOW)
                .wishlistStatus(WishlistStatus.DISMISSED)
                .type(EventType.EXPENSE)
                .eventKind(EventKind.PLAN)
                .status(EventStatus.PLANNED)
                .plannedAmount(new BigDecimal("99000"))
                .date(LocalDate.now().plusMonths(3))
                .category(cat)
                .description("Отклонённая хотелка")
                .build());

        String body = mockMvc.perform(get("/api/v1/wishlist/simulation"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        var root = om.readValue(body, Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) root.get("items");

        // The OPEN item is present with kind=WISHLIST, matching targetDate and a 1-element negative delta.
        Map<String, Object> open = items.stream()
                .filter(i -> openItem.getId().toString().equals(i.get("id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("OPEN wishlist item missing from simulation"));

        assertThat(open.get("kind")).isEqualTo("WISHLIST");
        assertThat(open.get("targetDate")).isEqualTo(targetDate.toString());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> delta = (List<Map<String, Object>>) open.get("delta");
        assertThat(delta).hasSize(1);
        assertThat(Double.parseDouble(delta.get(0).get("accountDelta").toString()))
                .as("wishlist outflow lowers the account")
                .isLessThan(0.0);

        // The DISMISSED item must be absent.
        boolean dismissedPresent = items.stream()
                .anyMatch(i -> dismissedItem.getId().toString().equals(i.get("id")));
        assertThat(dismissedPresent)
                .as("DISMISSED wishlist items must not appear in the simulation")
                .isFalse();
    }
}
