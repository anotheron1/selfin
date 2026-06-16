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

    // ─────────────────────────────────────────────────────────────────────────
    // Task 4.3 — convert WISHLIST → PLAN_EVENT end-to-end
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void convertWishlistToPlanEvent_fixesSourceAndCreatesArtifact() throws Exception {
        Category cat = seededExpenseCategory();
        LocalDate targetDate = LocalDate.now().plusMonths(6);

        FinancialEvent src = eventRepository.save(FinancialEvent.builder()
                .priority(Priority.LOW)
                .wishlistStatus(WishlistStatus.OPEN)
                .type(EventType.EXPENSE)
                .eventKind(EventKind.PLAN)
                .status(EventStatus.PLANNED)
                .plannedAmount(new BigDecimal("150000"))
                .date(targetDate)
                .category(cat)
                .description("Хотелка для конверсии")
                .build());

        String convertBody = """
                {"sourceKind":"WISHLIST","target":"PLAN_EVENT"}
                """;

        String resp = mockMvc.perform(post("/api/v1/wishlist/items/" + src.getId() + "/convert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(convertBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.newStatus").value("FIXED"))
                .andExpect(jsonPath("$.convertedTo.kind").value("EVENT"))
                .andExpect(jsonPath("$.convertedTo.id").exists())
                .andReturn().getResponse().getContentAsString();

        UUID artifactId = UUID.fromString(om.readTree(resp).get("convertedTo").get("id").asText());

        // Reload source: FIXED + convertedToEventId points at the new artifact.
        FinancialEvent reloaded = eventRepository.findById(src.getId()).orElseThrow();
        assertThat(reloaded.getWishlistStatus()).isEqualTo(WishlistStatus.FIXED);
        assertThat(reloaded.getConvertedToEventId()).isEqualTo(artifactId);

        // The artifact is a brand-new PLAN event at the target date with wishlist_status = null.
        FinancialEvent artifact = eventRepository.findById(artifactId).orElseThrow();
        assertThat(artifact.getId()).isNotEqualTo(src.getId());
        assertThat(artifact.getWishlistStatus()).isNull();
        assertThat(artifact.getEventKind()).isEqualTo(EventKind.PLAN);
        assertThat(artifact.getDate()).isEqualTo(targetDate);

        // And it is queryable via GET /events for the target day (FinancialEventDto carries no
        // wishlist_status field, so null-status is asserted on the entity above, not the JSON).
        String listJson = mockMvc.perform(get("/api/v1/events")
                        .param("startDate", targetDate.toString())
                        .param("endDate", targetDate.toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> events = om.readValue(listJson, List.class);
        boolean artifactReturned = events.stream()
                .anyMatch(e -> artifactId.toString().equals(e.get("id")));
        assertThat(artifactReturned)
                .as("artifact PLAN event must be returned by GET /events at the target date")
                .isTrue();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Task 4.4 — double conversion returns 409
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void doubleConversion_returns409_referencingExistingArtifact() throws Exception {
        Category cat = seededExpenseCategory();
        LocalDate targetDate = LocalDate.now().plusMonths(4);

        FinancialEvent src = eventRepository.save(FinancialEvent.builder()
                .priority(Priority.LOW)
                .wishlistStatus(WishlistStatus.OPEN)
                .type(EventType.EXPENSE)
                .eventKind(EventKind.PLAN)
                .status(EventStatus.PLANNED)
                .plannedAmount(new BigDecimal("120000"))
                .date(targetDate)
                .category(cat)
                .description("Двойная конверсия")
                .build());

        String convertBody = """
                {"sourceKind":"WISHLIST","target":"PLAN_EVENT"}
                """;

        // First conversion succeeds and produces the artifact.
        String firstResp = mockMvc.perform(post("/api/v1/wishlist/items/" + src.getId() + "/convert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(convertBody))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID artifactId = UUID.fromString(om.readTree(firstResp).get("convertedTo").get("id").asText());

        // Second conversion of the already-FIXED source → 409 Conflict.
        mockMvc.perform(post("/api/v1/wishlist/items/" + src.getId() + "/convert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(convertBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));

        // The conflict left the source untouched: it still references the original (existing) artifact id.
        FinancialEvent reloaded = eventRepository.findById(src.getId()).orElseThrow();
        assertThat(reloaded.getWishlistStatus()).isEqualTo(WishlistStatus.FIXED);
        assertThat(reloaded.getConvertedToEventId())
                .as("double-convert must not replace or clear the existing artifact reference")
                .isEqualTo(artifactId);

        // And no duplicate artifact PLAN event was created at the target date.
        long artifactsAtDate = eventRepository.findAll().stream()
                .filter(e -> !e.isDeleted()
                        && e.getWishlistStatus() == null
                        && e.getEventKind() == EventKind.PLAN
                        && targetDate.equals(e.getDate()))
                .count();
        assertThat(artifactsAtDate)
                .as("exactly one artifact event should exist after a failed second conversion")
                .isEqualTo(1);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Task 4.5 — convert CREDIT → FUND_WITH_CREDIT + recurring rule
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void convertCredit_createsFundAndRecurringRule() throws Exception {
        TargetFund src = fundRepository.save(TargetFund.builder()
                .name("Машина в кредит")
                .purchaseType(FundPurchaseType.CREDIT)
                .status(FundStatus.FUNDING)
                .wishlistStatus(WishlistStatus.OPEN)
                .targetAmount(new BigDecimal("2000000"))
                .targetDate(LocalDate.now().plusMonths(2))
                .creditRate(new BigDecimal("16.5"))
                .creditTermMonths(60)
                .build());

        long rulesBefore = ruleRepository.count();
        long fundsBefore = fundRepository.count();

        String convertBody = """
                {"sourceKind":"CREDIT","target":"FUND_WITH_CREDIT","createRecurringPayments":true}
                """;

        String resp = mockMvc.perform(post("/api/v1/wishlist/items/" + src.getId() + "/convert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(convertBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.newStatus").value("FIXED"))
                .andExpect(jsonPath("$.artifactKind").value("FUND_WITH_CREDIT"))
                .andExpect(jsonPath("$.convertedTo.kind").value("FUND"))
                .andExpect(jsonPath("$.recurringRuleId").exists())
                .andReturn().getResponse().getContentAsString();

        var root = om.readTree(resp);
        assertThat(root.get("recurringRuleId").isNull())
                .as("FUND_WITH_CREDIT + createRecurringPayments must return a non-null recurringRuleId")
                .isFalse();
        UUID recurringRuleId = UUID.fromString(root.get("recurringRuleId").asText());
        UUID newFundId = UUID.fromString(root.get("convertedTo").get("id").asText());

        // A brand-new TargetFund was created (distinct from the source).
        assertThat(fundRepository.count()).isGreaterThan(fundsBefore);
        TargetFund newFund = fundRepository.findById(newFundId).orElseThrow();
        assertThat(newFund.getId()).isNotEqualTo(src.getId());
        assertThat(newFund.getPurchaseType()).isEqualTo(FundPurchaseType.CREDIT);

        // A RecurringRule was created and is retrievable by the returned id.
        assertThat(ruleRepository.count()).isGreaterThan(rulesBefore);
        assertThat(ruleRepository.findById(recurringRuleId))
                .as("the returned recurringRuleId must resolve to a persisted RecurringRule")
                .isPresent();

        // Source fund: FIXED + convertedToFundId set.
        TargetFund reloaded = fundRepository.findById(src.getId()).orElseThrow();
        assertThat(reloaded.getWishlistStatus()).isEqualTo(WishlistStatus.FIXED);
        assertThat(reloaded.getConvertedToFundId()).isEqualTo(newFundId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Task 4.6 — status FIXED→OPEN preserves the converted artifact
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void statusFixedToOpen_preservesArtifact() throws Exception {
        Category cat = seededExpenseCategory();
        LocalDate targetDate = LocalDate.now().plusMonths(5);

        FinancialEvent src = eventRepository.save(FinancialEvent.builder()
                .priority(Priority.LOW)
                .wishlistStatus(WishlistStatus.OPEN)
                .type(EventType.EXPENSE)
                .eventKind(EventKind.PLAN)
                .status(EventStatus.PLANNED)
                .plannedAmount(new BigDecimal("140000"))
                .date(targetDate)
                .category(cat)
                .description("Хотелка, которую открутят назад")
                .build());

        // Convert to a PLAN event → source becomes FIXED with convertedToEventId set.
        String resp = mockMvc.perform(post("/api/v1/wishlist/items/" + src.getId() + "/convert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceKind":"WISHLIST","target":"PLAN_EVENT"}
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID artifactId = UUID.fromString(om.readTree(resp).get("convertedTo").get("id").asText());

        // Move the source back to OPEN ("вернуть в обсуждение"). This now SUCCEEDS: the former
        // chk_event_converted_only_fixed constraint was dropped, because a converted item may
        // legitimately return to OPEN/DISMISSED while keeping its conversion link. The void
        // controller method maps to 200 OK on success, and the artifact reference is preserved.
        mockMvc.perform(patch("/api/v1/events/" + src.getId() + "/wishlist-status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"OPEN"}
                                """))
                .andExpect(status().isOk());

        // Source is OPEN again, convertedToEventId is still set, and the artifact event still
        // exists and is not deleted.
        FinancialEvent reloaded = eventRepository.findById(src.getId()).orElseThrow();
        assertThat(reloaded.getWishlistStatus())
                .as("FIXED→OPEN status change must take effect")
                .isEqualTo(WishlistStatus.OPEN);
        assertThat(reloaded.getConvertedToEventId())
                .as("converted artifact reference must survive a FIXED→OPEN status change")
                .isEqualTo(artifactId);

        assertThat(eventRepository.findById(artifactId))
                .as("the converted artifact event must still exist")
                .isPresent()
                .hasValueSatisfying(a -> assertThat(a.isDeleted()).isFalse());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Task 4.7 — settings round-trip + validation
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void wishlistSettings_roundTrip_andRejectsNegativeBuffer() throws Exception {
        // PUT new thresholds.
        mockMvc.perform(put("/api/v1/settings/wishlist")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"capitalThresholdRub":1000000,"cashBufferMonths":2.0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capitalThresholdRub").value(1000000))
                .andExpect(jsonPath("$.cashBufferMonths").value(2.0));

        // GET returns the same values.
        mockMvc.perform(get("/api/v1/settings/wishlist"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capitalThresholdRub").value(1000000))
                .andExpect(jsonPath("$.cashBufferMonths").value(2.0));

        // PUT with a negative buffer is rejected with 400.
        mockMvc.perform(put("/api/v1/settings/wishlist")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"capitalThresholdRub":1000000,"cashBufferMonths":-1}
                                """))
                .andExpect(status().isBadRequest());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Task 4.8 — FIXED-without-conversion wishlist item affects /strategy timeline
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void fixedWishlistItem_affectsStrategyTimeline() throws Exception {
        Category cat = seededExpenseCategory();
        LocalDate targetDate = LocalDate.now().plusMonths(6);
        String targetYm = YearMonth.now().plusMonths(6).toString();   // "YYYY-MM"
        BigDecimal amount = new BigDecimal("300000");

        // FIXED wishlist event WITHOUT conversion → contributes a delta overlay onto /strategy.
        FinancialEvent fixed = eventRepository.save(FinancialEvent.builder()
                .priority(Priority.LOW)
                .wishlistStatus(WishlistStatus.FIXED)
                .convertedToEventId(null)
                .convertedToFundId(null)
                .type(EventType.EXPENSE)
                .eventKind(EventKind.PLAN)
                .status(EventStatus.PLANNED)
                .plannedAmount(amount)
                .date(targetDate)
                .category(cat)
                .description("Зафиксированная хотелка")
                .build());

        double withFixed = balanceAtMonth(targetYm);

        // Move it to DISMISSED → overlay disappears; the baseline PLAN contribution is unchanged
        // across both calls, so the difference isolates exactly the FIXED overlay.
        mockMvc.perform(patch("/api/v1/events/" + fixed.getId() + "/wishlist-status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"DISMISSED"}
                                """))
                .andExpect(status().isOk());

        double withoutFixed = balanceAtMonth(targetYm);

        assertThat(withFixed)
                .as("the FIXED outflow must lower the strategy balance vs. the dismissed state")
                .isLessThan(withoutFixed);
        assertThat(withoutFixed - withFixed)
                .as("removing the FIXED overlay restores exactly the item amount (within rounding)")
                .isCloseTo(amount.doubleValue(), org.assertj.core.data.Offset.offset(1.0));
    }

    /** GET /api/v1/strategy/timeline and read {@code balance} of the point with the given yearMonth. */
    private double balanceAtMonth(String yearMonth) throws Exception {
        String body = mockMvc.perform(get("/api/v1/strategy/timeline"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var dto = om.readValue(body, Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> points = (List<Map<String, Object>>) dto.get("points");
        Map<String, Object> point = points.stream()
                .filter(p -> yearMonth.equals(p.get("yearMonth")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no timeline point for " + yearMonth));
        return Double.parseDouble(point.get("balance").toString());
    }
}
