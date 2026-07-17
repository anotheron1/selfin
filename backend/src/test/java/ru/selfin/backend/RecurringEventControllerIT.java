package ru.selfin.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.assertj.core.api.AssertionsForInterfaceTypes;
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
import ru.selfin.backend.repository.FinancialEventRepository;
import ru.selfin.backend.repository.RecurringRuleRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Интеграционные тесты HTTP-уровня для recurring-events (Chunk 4).
 * Тестирует полный стек: HTTP → Controller → Service → Repository → БД.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class RecurringEventControllerIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    RecurringRuleRepository ruleRepository;

    @Autowired
    FinancialEventRepository eventRepository;

    /** Получить первую попавшуюся категорию из БД (засеяны V2 миграцией) */
    private String getFirstCategoryId() throws Exception {
        String body = mockMvc.perform(get("/api/v1/categories"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var list = objectMapper.readValue(body, java.util.List.class);
        @SuppressWarnings("unchecked")
        var first = (Map<String, Object>) list.get(0);
        return (String) first.get("id");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Task 4.1 — create monthly with end_date generates 12 events
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void createRecurring_monthly_with_endDate_generates_full_horizon() throws Exception {
        String catId = getFirstCategoryId();
        String idem = UUID.randomUUID().toString();
        String startDate = LocalDate.now().plusDays(1).toString();   // I3 — будущее
        String endDate = LocalDate.now().plusMonths(11).plusDays(1).toString();

        String body = """
            {
              "date": "%s",
              "categoryId": "%s",
              "type": "EXPENSE",
              "plannedAmount": 80000,
              "priority": "HIGH",
              "description": "Ипотека",
              "recurring": {
                "frequency": "MONTHLY",
                "dayOfMonth": %d,
                "startDate": "%s",
                "endDate": "%s"
              }
            }
            """.formatted(startDate, catId, LocalDate.now().plusDays(1).getDayOfMonth(), startDate, endDate);

        String createResp = mockMvc.perform(post("/api/v1/events")
                        .header("Idempotency-Key", idem)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recurringRuleId").exists())
                .andReturn().getResponse().getContentAsString();
        String ruleId = objectMapper.readTree(createResp).get("recurringRuleId").asText();

        // Список событий по периоду должен содержать 12 шт. с тем же ruleId
        // (фильтруем по ruleId: другие тесты класса делят одну БД).
        String list = mockMvc.perform(get("/api/v1/events")
                        .param("startDate", startDate)
                        .param("endDate", endDate))
                .andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> arr = objectMapper.readValue(list, List.class);
        assertThat(arr.stream().filter(e -> ruleId.equals(e.get("recurringRuleId"))))
                .hasSize(12);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Task 4.2 — scope=FOLLOWING update changes only future events
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void scopeFollowing_update_amount_changes_only_future_events() throws Exception {
        String catId = getFirstCategoryId();
        // Create 12 monthly events starting tomorrow
        LocalDate startDate = LocalDate.now().plusDays(1);
        int dayOfMonth = startDate.getDayOfMonth();
        LocalDate endDate = startDate.plusMonths(11);

        String createBody = """
            {
              "date": "%s",
              "categoryId": "%s",
              "type": "EXPENSE",
              "plannedAmount": 10000,
              "priority": "MEDIUM",
              "description": "Аренда",
              "recurring": {
                "frequency": "MONTHLY",
                "dayOfMonth": %d,
                "startDate": "%s",
                "endDate": "%s"
              }
            }
            """.formatted(startDate, catId, dayOfMonth, startDate, endDate);

        String createResp = mockMvc.perform(post("/api/v1/events")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String ruleId = objectMapper.readTree(createResp).get("recurringRuleId").asText();

        // Fetch all 12 events, sort by date, pick the 6th (index 5)
        String listJson = mockMvc.perform(get("/api/v1/events")
                        .param("startDate", startDate.toString())
                        .param("endDate", endDate.toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Фильтруем по ruleId: другие тесты класса делят одну БД.
        List<Map<String, Object>> allEvents = objectMapper.readValue(listJson, List.class);
        List<Map<String, Object>> events = allEvents.stream()
                .filter(e -> ruleId.equals(e.get("recurringRuleId")))
                .toList();
        assertThat(events).hasSize(12);
        // Events are returned ordered by date (repository ORDER BY date ASC)
        Map<String, Object> sixthEvent = events.get(5);
        String sixthId = (String) sixthEvent.get("id");
        String sixthDate = (String) sixthEvent.get("date");

        // PUT the 6th event with scope=FOLLOWING and new amount 20000
        String updateBody = """
            {
              "date": "%s",
              "categoryId": "%s",
              "type": "EXPENSE",
              "plannedAmount": 20000,
              "priority": "MEDIUM",
              "description": "Аренда повышенная",
              "recurring": {
                "frequency": "MONTHLY",
                "dayOfMonth": %d,
                "startDate": "%s",
                "endDate": "%s"
              }
            }
            """.formatted(sixthDate, catId, dayOfMonth, startDate, endDate);

        mockMvc.perform(put("/api/v1/events/" + sixthId)
                        .param("scope", "FOLLOWING")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isOk());

        // Re-list after update
        String updatedListJson = mockMvc.perform(get("/api/v1/events")
                        .param("startDate", startDate.toString())
                        .param("endDate", endDate.toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<Map<String, Object>> allUpdatedEvents = objectMapper.readValue(updatedListJson, List.class);
        List<Map<String, Object>> updatedEvents = allUpdatedEvents.stream()
                .filter(e -> ruleId.equals(e.get("recurringRuleId")))
                .toList();
        assertThat(updatedEvents).hasSize(12);

        // First 5 events: old amount (10000)
        for (int i = 0; i < 5; i++) {
            Object amount = updatedEvents.get(i).get("plannedAmount");
            assertThat(((Number) amount).doubleValue())
                    .as("Event %d should have old amount", i + 1)
                    .isEqualTo(10000.0);
        }

        // Last 7 events (index 5-11): new amount (20000)
        for (int i = 5; i < 12; i++) {
            Object amount = updatedEvents.get(i).get("plannedAmount");
            assertThat(((Number) amount).doubleValue())
                    .as("Event %d should have new amount", i + 1)
                    .isEqualTo(20000.0);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Task 4.3 — FACT preserved during scope=ALL edit
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void scopeAll_update_preserves_executed_fact_event() throws Exception {
        String catId = getFirstCategoryId();
        LocalDate startDate = LocalDate.now().plusDays(1);
        int dayOfMonth = startDate.getDayOfMonth();
        LocalDate endDate = startDate.plusMonths(5);

        String createBody = """
            {
              "date": "%s",
              "categoryId": "%s",
              "type": "EXPENSE",
              "plannedAmount": 5000,
              "priority": "MEDIUM",
              "description": "Связь",
              "recurring": {
                "frequency": "MONTHLY",
                "dayOfMonth": %d,
                "startDate": "%s",
                "endDate": "%s"
              }
            }
            """.formatted(startDate, catId, dayOfMonth, startDate, endDate);

        String createResp = mockMvc.perform(post("/api/v1/events")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String headEventId = objectMapper.readTree(createResp).get("id").asText();
        String headEventDate = objectMapper.readTree(createResp).get("date").asText();

        // PATCH-fact the head event to turn it EXECUTED
        String patchBody = """
            { "factAmount": 4900, "description": "Оплачено" }
            """;
        mockMvc.perform(patch("/api/v1/events/" + headEventId + "/fact")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patchBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"));

        // PUT scope=ALL with new plannedAmount=9999
        String updateBody = """
            {
              "date": "%s",
              "categoryId": "%s",
              "type": "EXPENSE",
              "plannedAmount": 9999,
              "priority": "MEDIUM",
              "description": "Связь обновлена",
              "recurring": {
                "frequency": "MONTHLY",
                "dayOfMonth": %d,
                "startDate": "%s",
                "endDate": "%s"
              }
            }
            """.formatted(headEventDate, catId, dayOfMonth, startDate, endDate);

        mockMvc.perform(put("/api/v1/events/" + headEventId)
                        .param("scope", "ALL")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isOk());

        // Re-list all events for this period
        String listJson = mockMvc.perform(get("/api/v1/events")
                        .param("startDate", startDate.toString())
                        .param("endDate", endDate.toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<Map<String, Object>> events = objectMapper.readValue(listJson, List.class);

        // The EXECUTED head event should keep old plannedAmount (5000) and have factAmount set
        Map<String, Object> executedEvent = events.stream()
                .filter(e -> headEventId.equals(e.get("id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("EXECUTED event not found in list"));

        assertThat(executedEvent.get("status")).isEqualTo("EXECUTED");
        assertThat(((Number) executedEvent.get("factAmount")).doubleValue()).isEqualTo(4900.0);
        // I4: EXECUTED rows must never be modified by the rule — plannedAmount stays at original value
        assertThat(((Number) executedEvent.get("plannedAmount")).doubleValue())
                .as("EXECUTED event plannedAmount must remain at original value (5000), not updated to 9999")
                .isEqualTo(5000.0);

        // All PLANNED events (non-executed) should have new amount 9999
        events.stream()
                .filter(e -> "PLANNED".equals(e.get("status")))
                .forEach(e -> {
                    Object amount = e.get("plannedAmount");
                    assertThat(((Number) amount).doubleValue())
                            .as("PLANNED event should have new amount")
                            .isEqualTo(9999.0);
                });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Task 4.4 — scope=ALL delete marks rule deleted, FACT alive
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void scopeAll_delete_marks_rule_deleted_no_plan_events_remain() throws Exception {
        String catId = getFirstCategoryId();
        LocalDate startDate = LocalDate.now().plusDays(1);
        int dayOfMonth = startDate.getDayOfMonth();
        LocalDate endDate = startDate.plusMonths(5);

        String createBody = """
            {
              "date": "%s",
              "categoryId": "%s",
              "type": "EXPENSE",
              "plannedAmount": 3000,
              "priority": "LOW",
              "description": "Подписка",
              "recurring": {
                "frequency": "MONTHLY",
                "dayOfMonth": %d,
                "startDate": "%s",
                "endDate": "%s"
              }
            }
            """.formatted(startDate, catId, dayOfMonth, startDate, endDate);

        String createResp = mockMvc.perform(post("/api/v1/events")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String headEventId = objectMapper.readTree(createResp).get("id").asText();
        String ruleId = objectMapper.readTree(createResp).get("recurringRuleId").asText();

        // DELETE with scope=ALL
        mockMvc.perform(delete("/api/v1/events/" + headEventId)
                        .param("scope", "ALL"))
                .andExpect(status().isNoContent());

        // Re-list events for the period — should return 0 active PLAN events for this rule
        String listJson = mockMvc.perform(get("/api/v1/events")
                        .param("startDate", startDate.toString())
                        .param("endDate", endDate.toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<Map<String, Object>> events = objectMapper.readValue(listJson, List.class);
        long eventsForRule = events.stream()
                .filter(e -> ruleId.equals(e.get("recurringRuleId")))
                .count();
        assertThat(eventsForRule)
                .as("All PLAN events for the rule must be soft-deleted")
                .isZero();

        // Verify rule is marked deleted in DB
        assertThat(ruleRepository.findById(UUID.fromString(ruleId)))
                .isPresent()
                .hasValueSatisfying(rule -> assertThat(rule.isDeleted())
                        .as("Rule must be soft-deleted")
                        .isTrue());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Regression — scope=FOLLOWING с головного события не должен падать 500:
    // endDate = headDate - 1 нарушил бы chk_end_after_start; ветка эквивалентна ALL.
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void scopeFollowing_delete_from_head_event_retires_whole_rule() throws Exception {
        String catId = getFirstCategoryId();
        LocalDate startDate = LocalDate.now().plusDays(1);
        int dayOfMonth = startDate.getDayOfMonth();
        LocalDate endDate = startDate.plusMonths(5);

        String createBody = """
            {
              "date": "%s",
              "categoryId": "%s",
              "type": "EXPENSE",
              "plannedAmount": 3000,
              "priority": "LOW",
              "description": "Подписка",
              "recurring": {
                "frequency": "MONTHLY",
                "dayOfMonth": %d,
                "startDate": "%s",
                "endDate": "%s"
              }
            }
            """.formatted(startDate, catId, dayOfMonth, startDate, endDate);

        String createResp = mockMvc.perform(post("/api/v1/events")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String headEventId = objectMapper.readTree(createResp).get("id").asText();
        String ruleId = objectMapper.readTree(createResp).get("recurringRuleId").asText();

        mockMvc.perform(delete("/api/v1/events/" + headEventId)
                        .param("scope", "FOLLOWING"))
                .andExpect(status().isNoContent());

        String listJson = mockMvc.perform(get("/api/v1/events")
                        .param("startDate", startDate.toString())
                        .param("endDate", endDate.toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<Map<String, Object>> events = objectMapper.readValue(listJson, List.class);
        long eventsForRule = events.stream()
                .filter(e -> ruleId.equals(e.get("recurringRuleId")))
                .count();
        assertThat(eventsForRule)
                .as("FOLLOWING с головы не оставляет ни одного PLAN-события")
                .isZero();

        assertThat(ruleRepository.findById(UUID.fromString(ruleId)))
                .isPresent()
                .hasValueSatisfying(rule -> {
                    assertThat(rule.isDeleted())
                            .as("FOLLOWING с головы эквивалентен ALL — правило soft-deleted")
                            .isTrue();
                    assertThat(rule.getEndDate())
                            .as("endDate обязан удовлетворять chk_end_after_start")
                            .isAfterOrEqualTo(rule.getStartDate());
                });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Task 4.5 — indefinite rule lazy-extends through getPlanner
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void indefiniteRule_lazyExtend_through_planner() throws Exception {
        // Per spec: createFromDto generates up to now+36mo horizon.
        // extendIndefiniteRules is called by FundPlannerService.getPlanner().
        //
        // Simulating gap in coverage by soft-deleting the tail; planner must lazily
        // regenerate them via extendIndefiniteRules.
        //
        // The partial unique index uq_events_rule_date_active only covers active rows
        // (is_deleted=false), so soft-deleted events do not block re-insertion of new
        // active rows for the same (rule_id, date) pairs — this is the intended design.
        String catId = getFirstCategoryId();
        LocalDate startDate = LocalDate.now().plusDays(1);
        int dayOfMonth = startDate.getDayOfMonth();

        // Create indefinite rule (no endDate)
        String createBody = """
            {
              "date": "%s",
              "categoryId": "%s",
              "type": "INCOME",
              "plannedAmount": 50000,
              "priority": "HIGH",
              "description": "Зарплата бессрочная",
              "recurring": {
                "frequency": "MONTHLY",
                "dayOfMonth": %d,
                "startDate": "%s"
              }
            }
            """.formatted(startDate, catId, dayOfMonth, startDate);

        String createResp = mockMvc.perform(post("/api/v1/events")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String ruleId = objectMapper.readTree(createResp).get("recurringRuleId").asText();
        UUID ruleUuid = UUID.fromString(ruleId);

        // Fetch all active events for this rule, sorted by date
        List<ru.selfin.backend.model.FinancialEvent> allForRule = eventRepository.findAll().stream()
                .filter(e -> !e.isDeleted()
                        && e.getRecurringRule() != null
                        && e.getRecurringRule().getId().equals(ruleUuid))
                .sorted(java.util.Comparator.comparing(ru.selfin.backend.model.FinancialEvent::getDate))
                .collect(java.util.stream.Collectors.toList());

        // createFromDto generates 36mo worth of events — expect ~36 occurrences
        assertThat(allForRule.size())
                .as("Should have generated ~36 monthly events at creation")
                .isGreaterThanOrEqualTo(35);

        // Soft-delete the last 3 events to simulate a gap in coverage
        int totalBeforeDeletion = allForRule.size();
        List<ru.selfin.backend.model.FinancialEvent> tailToDelete =
                allForRule.subList(totalBeforeDeletion - 3, totalBeforeDeletion);
        for (ru.selfin.backend.model.FinancialEvent event : tailToDelete) {
            event.setDeleted(true);
            eventRepository.save(event);
        }

        // countBefore reflects the gap (~33 active events)
        long countBefore = eventRepository.findAll().stream()
                .filter(e -> !e.isDeleted()
                        && e.getRecurringRule() != null
                        && e.getRecurringRule().getId().equals(ruleUuid))
                .count();
        assertThat(countBefore)
                .as("After soft-deleting 3 tail events, active count should be ~33")
                .isEqualTo(totalBeforeDeletion - 3);

        // GET planner triggers extendIndefiniteRules — must regenerate the missing tail
        mockMvc.perform(get("/api/v1/funds/planner"))
                .andExpect(status().isOk());

        // Count events after planner call
        long countAfter = eventRepository.findAll().stream()
                .filter(e -> !e.isDeleted()
                        && e.getRecurringRule() != null
                        && e.getRecurringRule().getId().equals(ruleUuid))
                .count();

        // extendIndefiniteRules must have regenerated the 3 deleted tail events
        assertThat(countAfter)
                .as("Lazy-extend must regenerate missing tail events: countAfter > countBefore")
                .isGreaterThan(countBefore);

        // Coverage must be restored to at least now+36mo horizon
        assertThat(countAfter)
                .as("Lazy-extend must restore coverage to spec horizon (~36 events)")
                .isGreaterThanOrEqualTo(36);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Task 4.6 — retroactive recurring create rejected (I3)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void retroactiveRecurring_create_rejected_with_400() throws Exception {
        String catId = getFirstCategoryId();
        // startDate = yesterday (past) — violates I3
        LocalDate yesterday = LocalDate.now().minusDays(1);

        String body = """
            {
              "date": "%s",
              "categoryId": "%s",
              "type": "EXPENSE",
              "plannedAmount": 1000,
              "priority": "MEDIUM",
              "description": "Просроченная подписка",
              "recurring": {
                "frequency": "MONTHLY",
                "dayOfMonth": %d,
                "startDate": "%s",
                "endDate": "%s"
              }
            }
            """.formatted(yesterday, catId, yesterday.getDayOfMonth(),
                yesterday, yesterday.plusMonths(6));

        long ruleCountBefore = ruleRepository.count();

        mockMvc.perform(post("/api/v1/events")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        // No new rule should have been created
        long ruleCountAfter = ruleRepository.count();
        assertThat(ruleCountAfter)
                .as("No recurring_rule row should have been inserted")
                .isEqualTo(ruleCountBefore);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Task 4.7 — YEARLY 29 February clamp at create
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void yearly_29February_clamped_to_last_day_of_month() throws Exception {
        String catId = getFirstCategoryId();
        // Non-leap year 2027, day=29, monthOfYear=2
        // Expected dates: 2027-02-28, 2028-02-29, 2029-02-28, 2030-02-28
        String startDate = "2027-02-28";
        String endDate = "2030-03-01";

        String body = """
            {
              "date": "%s",
              "categoryId": "%s",
              "type": "EXPENSE",
              "plannedAmount": 12000,
              "priority": "MEDIUM",
              "description": "Годовая страховка",
              "recurring": {
                "frequency": "YEARLY",
                "dayOfMonth": 29,
                "monthOfYear": 2,
                "startDate": "%s",
                "endDate": "%s"
              }
            }
            """.formatted(startDate, catId, startDate, endDate);

        mockMvc.perform(post("/api/v1/events")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recurringRuleId").exists());

        String listJson = mockMvc.perform(get("/api/v1/events")
                        .param("startDate", startDate)
                        .param("endDate", endDate))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<Map<String, Object>> events = objectMapper.readValue(listJson, List.class);
        assertThat(events).hasSize(4);

        List<String> expectedDates = List.of("2027-02-28", "2028-02-29", "2029-02-28", "2030-02-28");
        List<String> actualDates = events.stream()
                .map(e -> (String) e.get("date"))
                .toList();

        assertThat(actualDates).containsExactlyElementsOf(expectedDates);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Task 4.8 — start_date immutability via PUT (I8)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void startDate_immutable_put_returns_400() throws Exception {
        String catId = getFirstCategoryId();
        LocalDate startDate = LocalDate.now().plusDays(1);
        int dayOfMonth = startDate.getDayOfMonth();
        LocalDate endDate = startDate.plusMonths(5);

        String createBody = """
            {
              "date": "%s",
              "categoryId": "%s",
              "type": "EXPENSE",
              "plannedAmount": 2000,
              "priority": "MEDIUM",
              "description": "Ежемесячный платеж",
              "recurring": {
                "frequency": "MONTHLY",
                "dayOfMonth": %d,
                "startDate": "%s",
                "endDate": "%s"
              }
            }
            """.formatted(startDate, catId, dayOfMonth, startDate, endDate);

        String createResp = mockMvc.perform(post("/api/v1/events")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String headEventId = objectMapper.readTree(createResp).get("id").asText();

        // Attempt to PUT with a different startDate — should return 400 (I8)
        LocalDate differentStartDate = startDate.plusDays(5);
        String updateBody = """
            {
              "date": "%s",
              "categoryId": "%s",
              "type": "EXPENSE",
              "plannedAmount": 2500,
              "priority": "MEDIUM",
              "description": "Ежемесячный платеж изменен",
              "recurring": {
                "frequency": "MONTHLY",
                "dayOfMonth": %d,
                "startDate": "%s",
                "endDate": "%s"
              }
            }
            """.formatted(startDate, catId, dayOfMonth, differentStartDate, endDate);

        mockMvc.perform(put("/api/v1/events/" + headEventId)
                        .param("scope", "ALL")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isBadRequest());

        // Verify rule's startDate is unchanged in DB
        String ruleId = objectMapper.readTree(createResp).get("recurringRuleId").asText();
        assertThat(ruleRepository.findById(UUID.fromString(ruleId)))
                .isPresent()
                .hasValueSatisfying(rule ->
                        assertThat(rule.getStartDate())
                                .as("startDate must remain unchanged (I8)")
                                .isEqualTo(startDate));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Task 4.9 — FACT inherits recurring_rule_id
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void fact_inherits_recurringRuleId_from_parent_plan() throws Exception {
        String catId = getFirstCategoryId();
        LocalDate startDate = LocalDate.now().plusDays(1);
        int dayOfMonth = startDate.getDayOfMonth();
        LocalDate endDate = startDate.plusMonths(5);

        String createBody = """
            {
              "date": "%s",
              "categoryId": "%s",
              "type": "EXPENSE",
              "plannedAmount": 7000,
              "priority": "HIGH",
              "description": "Рекуррентный платеж",
              "recurring": {
                "frequency": "MONTHLY",
                "dayOfMonth": %d,
                "startDate": "%s",
                "endDate": "%s"
              }
            }
            """.formatted(startDate, catId, dayOfMonth, startDate, endDate);

        String createResp = mockMvc.perform(post("/api/v1/events")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String planId = objectMapper.readTree(createResp).get("id").asText();
        String ruleId = objectMapper.readTree(createResp).get("recurringRuleId").asText();

        // PATCH /events/{planId}/fact to attach a factAmount — turns plan EXECUTED
        String factPatchBody = """
            { "factAmount": 6800, "description": "Оплачено фактически" }
            """;
        mockMvc.perform(patch("/api/v1/events/" + planId + "/fact")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(factPatchBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"))
                .andExpect(jsonPath("$.factAmount").value(6800));

        // Verify the event has recurringRuleId set (PATCH returns the updated PLAN event)
        String planAfterPatch = mockMvc.perform(get("/api/v1/events")
                        .param("startDate", startDate.toString())
                        .param("endDate", startDate.toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<Map<String, Object>> planEvents = objectMapper.readValue(planAfterPatch, List.class);
        Map<String, Object> planEvent = planEvents.stream()
                .filter(e -> planId.equals(e.get("id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Plan event not found"));

        assertThat(planEvent.get("recurringRuleId"))
                .as("PLAN event must retain recurringRuleId after PATCH-fact")
                .isEqualTo(ruleId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Task 4.10 — invalid recurring.frequency=WEEKLY rejected with 400
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void invalidFrequency_weekly_returns_400() throws Exception {
        String catId = getFirstCategoryId();
        LocalDate startDate = LocalDate.now().plusDays(1);

        // WEEKLY is not a valid RecurringFrequency enum value — Jackson will throw
        // HttpMessageNotReadableException → GlobalExceptionHandler returns 400
        String body = """
            {
              "date": "%s",
              "categoryId": "%s",
              "type": "EXPENSE",
              "plannedAmount": 1000,
              "priority": "MEDIUM",
              "description": "Еженедельно",
              "recurring": {
                "frequency": "WEEKLY",
                "dayOfMonth": 5,
                "startDate": "%s"
              }
            }
            """.formatted(startDate, catId, startDate);

        mockMvc.perform(post("/api/v1/events")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }
}
