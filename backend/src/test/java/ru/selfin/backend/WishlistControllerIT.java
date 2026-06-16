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
}
