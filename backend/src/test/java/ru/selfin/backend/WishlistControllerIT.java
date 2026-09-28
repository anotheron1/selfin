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
        // Факт ссылается на свой план (parent_event_id) — факты первыми (ANO-106).
        eventRepository.deleteAll(eventRepository.findAll().stream()
                .filter(e -> e.getEventKind() == EventKind.FACT).toList());
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
    // Task 4.2 — wishlist item appears in simulation with delta; DISMISSED — without delta (ANO-107)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void getSimulation_wishlistEvent_appearsWithDelta_andDismissedComesWithoutDelta() throws Exception {
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

        // DISMISSED wishlist event — приходит для раздела «Отложено», но в расчёт не входит (ANO-107).
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

        // The DISMISSED item is present — the «Отложено» section reads it — with an empty delta.
        Map<String, Object> dismissed = items.stream()
                .filter(i -> dismissedItem.getId().toString().equals(i.get("id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("DISMISSED item missing: «Отложено» has nothing to show"));
        assertThat(dismissed.get("status")).isEqualTo("DISMISSED");
        assertThat((List<?>) dismissed.get("delta"))
                .as("отложенная в расчёт не входит")
                .isEmpty();
    }

    @Test
    void dismissedItem_comesBackToDiscussion() throws Exception {
        // ANO-107: «Отложить» — не дорога в один конец. Отложенная видна без дельты, «Вернуть в
        // обсуждение» возвращает её в расчёт примерки. Было: отложенная пропадала из ответа.
        FinancialEvent src = eventRepository.save(
                datedWishlist("Велосипед", LocalDate.now().plusMonths(5)));

        setStatus(src, "DISMISSED");
        Map<String, Object> dismissed = simulationItem(src.getId());
        assertThat(dismissed.get("status")).isEqualTo("DISMISSED");
        assertThat((List<?>) dismissed.get("delta")).isEmpty();

        setStatus(src, "OPEN");
        Map<String, Object> back = simulationItem(src.getId());
        assertThat(back.get("status")).isEqualTo("OPEN");
        assertThat((List<?>) back.get("delta"))
                .as("вернувшаяся снова примеряется")
                .isNotEmpty();
    }

    private void setStatus(FinancialEvent src, String status) throws Exception {
        mockMvc.perform(patch("/api/v1/events/" + src.getId() + "/wishlist-status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"" + status + "\"}"))
                .andExpect(status().isOk());
    }

    private Map<String, Object> simulationItem(UUID id) throws Exception {
        String body = mockMvc.perform(get("/api/v1/wishlist/simulation"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) om.readValue(body, Map.class).get("items");
        return items.stream()
                .filter(i -> id.toString().equals(i.get("id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("item " + id + " missing from /wishlist/simulation"));
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
    // ANO-138 — хотелка без срока: воспроизведение из тела задачи
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void convert_wishlistWithoutDate_returns400_andChangesNothing() throws Exception {
        // Ровно тот путь, которым заведена задача: хотелка «когда-нибудь», человек жмёт
        // «Зафиксировать» и оставляет предвыбранное «Плановое событие».
        FinancialEvent src = eventRepository.save(datelessWishlist("Ноут когда-нибудь"));

        mockMvc.perform(post("/api/v1/wishlist/items/" + src.getId() + "/convert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceKind":"WISHLIST","target":"PLAN_EVENT"}
                                """))
                .andExpect(status().isBadRequest());

        FinancialEvent reloaded = eventRepository.findById(src.getId()).orElseThrow();
        assertThat(reloaded.getWishlistStatus())
                .as("хотелка обязана остаться в обсуждении: плана-то не появилось")
                .isEqualTo(WishlistStatus.OPEN);
        assertThat(reloaded.getConvertedToEventId()).isNull();

        assertThat(eventRepository.findAll().stream().filter(e -> !e.isDeleted()).count())
                .as("никакого артефакта в базе: раньше здесь оседало событие с пустой датой")
                .isEqualTo(1);
    }

    @Test
    void convert_wishlistWithPlanDate_createsVisibleEvent() throws Exception {
        // «Событие создано» и «событие видно» — разные утверждения, и дефект жил ровно
        // на их расхождении. Поэтому проверяем выборкой за месяц, а не фактом сохранения.
        FinancialEvent src = eventRepository.save(datelessWishlist("Ноут со сроком"));
        LocalDate chosen = LocalDate.now().plusMonths(2);

        String resp = mockMvc.perform(post("/api/v1/wishlist/items/" + src.getId() + "/convert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceKind":"WISHLIST","target":"PLAN_EVENT","planDate":"%s"}
                                """.formatted(chosen)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        UUID artifactId = UUID.fromString(om.readTree(resp).get("convertedTo").get("id").asText());
        assertThat(eventRepository.findById(artifactId).orElseThrow().getDate())
                .as("в план уехал срок из диалога")
                .isEqualTo(chosen);

        String listJson = mockMvc.perform(get("/api/v1/events")
                        .param("startDate", chosen.withDayOfMonth(1).toString())
                        .param("endDate", chosen.withDayOfMonth(chosen.lengthOfMonth()).toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> events = om.readValue(listJson, List.class);
        assertThat(events.stream().anyMatch(e -> artifactId.toString().equals(e.get("id"))))
                .as("план виден в выборке за свой месяц — то, чего не случалось с пустой датой")
                .isTrue();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ANO-106 — сконвертированная хотелка не событие периода
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void convertedWishlist_isNotReturnedByPeriod_itsArtifactIs() throws Exception {
        // Замер 28.09 на стенде: после конверсии GET /events отдавал две одинаковые строки,
        // журнал их показывал и складывал в «Расходы … план» дважды. Факт в исходную строку
        // закрывал её, а созданный план оставался открытым — свободные считали покупку дважды.
        LocalDate day = LocalDate.now().plusMonths(3);
        FinancialEvent toPlan = eventRepository.save(datedWishlist("В план", day));
        FinancialEvent toFund = eventRepository.save(datedWishlist("В копилку", day));

        UUID planId = convert(toPlan, """
                {"sourceKind":"WISHLIST","target":"PLAN_EVENT","planDate":"%s"}
                """.formatted(day));
        convert(toFund, """
                {"sourceKind":"WISHLIST","target":"FUND"}
                """);

        List<String> ids = idsOnDay(day);
        assertThat(ids)
                .as("из двух одинаковых строк остаётся созданный план; хотелка, ставшая копилкой, — не план дня")
                .containsExactly(planId.toString());
    }

    @Test
    void factRecordedIntoConvertedWishlist_staysVisible() throws Exception {
        // Деньги, записанные до правки в исходную строку, не пропадают: факт — не хотелка.
        LocalDate day = LocalDate.now();
        FinancialEvent src = eventRepository.save(datedWishlist("Факт в исходную", day.plusDays(1)));
        convert(src, """
                {"sourceKind":"WISHLIST","target":"PLAN_EVENT","planDate":"%s"}
                """.formatted(day.plusDays(1)));
        FinancialEvent fact = eventRepository.save(FinancialEvent.builder()
                .eventKind(EventKind.FACT).parentEventId(src.getId())
                .type(EventType.EXPENSE).status(EventStatus.EXECUTED)
                .priority(Priority.LOW).factAmount(new BigDecimal("1063"))
                .date(day).category(src.getCategory()).build());

        assertThat(idsOnDay(day)).contains(fact.getId().toString());
    }

    @Test
    void factPatchedIntoConvertedWishlistRow_keepsTheRow() throws Exception {
        // Ревью #109: старый путь PATCH /events/{id}/fact пишет факт в саму строку. Строка с
        // деньгами остаётся в журнале — прячется только обязательство без факта.
        LocalDate day = LocalDate.now();
        FinancialEvent src = eventRepository.save(datedWishlist("Факт в строку", day.plusDays(1)));
        convert(src, """
                {"sourceKind":"WISHLIST","target":"PLAN_EVENT","planDate":"%s"}
                """.formatted(day.plusDays(1)));
        mockMvc.perform(patch("/api/v1/events/" + src.getId() + "/fact")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"factAmount":1063}
                                """))
                .andExpect(status().isOk());

        assertThat(idsOnDay(day.plusDays(1))).contains(src.getId().toString());
    }

    private FinancialEvent datedWishlist(String description, LocalDate date) {
        FinancialEvent e = datelessWishlist(description);
        e.setDate(date);
        return e;
    }

    private UUID convert(FinancialEvent src, String body) throws Exception {
        String resp = mockMvc.perform(post("/api/v1/wishlist/items/" + src.getId() + "/convert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(om.readTree(resp).get("convertedTo").get("id").asText());
    }

    private List<String> idsOnDay(LocalDate day) throws Exception {
        String listJson = mockMvc.perform(get("/api/v1/events")
                        .param("startDate", day.toString())
                        .param("endDate", day.toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> events = om.readValue(listJson, List.class);
        return events.stream().map(e -> (String) e.get("id")).toList();
    }

    /** Хотелка «когда-нибудь»: срок не задан — законное состояние (WishlistCreateDto.date). */
    private FinancialEvent datelessWishlist(String description) {
        return FinancialEvent.builder()
                .priority(Priority.LOW)
                .wishlistStatus(WishlistStatus.OPEN)
                .type(EventType.EXPENSE)
                .eventKind(EventKind.PLAN)
                .status(EventStatus.PLANNED)
                .plannedAmount(new BigDecimal("150000"))
                .date(null)
                .category(seededExpenseCategory())
                .description(description)
                .build();
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
    // ANO-104 — правило платежей только у кредита
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void convert_recurringPaymentsForFund_returns400_andChangesNothing() throws Exception {
        // Воспроизведение из задачи: «копить по 30 000 в месяц» — копилка с флагом правила.
        // Было 200 и recurringRuleId: null — копилка создана, правила нет, и ни слова об этом.
        FinancialEvent src = eventRepository.save(
                datedWishlist("Отпуск со взносами", LocalDate.now().plusMonths(6)));
        long fundsBefore = fundRepository.count();
        long rulesBefore = ruleRepository.count();

        mockMvc.perform(post("/api/v1/wishlist/items/" + src.getId() + "/convert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceKind":"WISHLIST","target":"FUND","createRecurringPayments":true}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("createRecurringPayments")));

        FinancialEvent reloaded = eventRepository.findById(src.getId()).orElseThrow();
        assertThat(reloaded.getWishlistStatus())
                .as("хотелка остаётся в обсуждении: ничего не создано")
                .isEqualTo(WishlistStatus.OPEN);
        assertThat(reloaded.getConvertedToFundId()).isNull();
        assertThat(fundRepository.count()).as("копилка не создана").isEqualTo(fundsBefore);
        assertThat(ruleRepository.count()).as("правило не создано").isEqualTo(rulesBefore);
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
