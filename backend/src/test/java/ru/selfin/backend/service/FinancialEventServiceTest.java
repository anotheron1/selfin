package ru.selfin.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.selfin.backend.dto.*;
import ru.selfin.backend.exception.ResourceNotFoundException;
import ru.selfin.backend.model.*;
import ru.selfin.backend.model.enums.*;
import ru.selfin.backend.repository.CategoryRepository;
import ru.selfin.backend.repository.FactAggregateProjection;
import ru.selfin.backend.repository.FinancialEventRepository;
import ru.selfin.backend.repository.TargetFundRepository;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FinancialEventServiceTest {

    private final FinancialEventRepository eventRepository = mock(FinancialEventRepository.class);
    private final CategoryRepository categoryRepository = mock(CategoryRepository.class);
    private final TargetFundRepository targetFundRepository = mock(TargetFundRepository.class);
    private final CategoryService categoryService = mock(CategoryService.class);
    private final TargetFundService targetFundService = mock(TargetFundService.class);
    private final RecurringRuleService ruleService = mock(RecurringRuleService.class);

    // Build service and inject the @Lazy TargetFundService + RecurringRuleService via reflection
    private final FinancialEventService service;
    {
        service = new FinancialEventService(eventRepository, categoryRepository, targetFundRepository, categoryService, Clock.systemDefaultZone(), ruleService);
        try {
            var field = FinancialEventService.class.getDeclaredField("targetFundService");
            field.setAccessible(true);
            field.set(service, targetFundService);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("createLinkedFact: бросает ResourceNotFoundException если plan не найден")
    void createLinkedFact_planNotFound_throws() {
        when(eventRepository.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                service.createLinkedFact(UUID.randomUUID(),
                        new FactCreateDto(LocalDate.now(), BigDecimal.TEN, null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("createLinkedFact: бросает ResourceNotFoundException если запись — FACT, не PLAN")
    void createLinkedFact_notAPlan_throws() {
        UUID factId = UUID.randomUUID();
        FinancialEvent fact = aFact(factId);
        when(eventRepository.findById(factId)).thenReturn(Optional.of(fact));

        assertThatThrownBy(() ->
                service.createLinkedFact(factId,
                        new FactCreateDto(LocalDate.now(), BigDecimal.TEN, null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("createLinkedFact: создаёт FACT запись и переводит план в EXECUTED")
    void createLinkedFact_success_createsFact() {
        UUID planId = UUID.randomUUID();
        Category cat = category();
        FinancialEvent plan = aPlan(planId, cat, EventStatus.PLANNED);

        when(eventRepository.findById(planId)).thenReturn(Optional.of(plan));
        when(targetFundRepository.findById(any())).thenReturn(Optional.empty());

        FinancialEvent savedFact = FinancialEvent.builder()
                .id(UUID.randomUUID())
                .eventKind(EventKind.FACT)
                .parentEventId(planId)
                .date(LocalDate.now())
                .category(cat)
                .type(EventType.EXPENSE)
                .factAmount(BigDecimal.TEN)
                .status(EventStatus.EXECUTED)
                .build();
        // first save = fact, second save = plan status update
        when(eventRepository.save(any())).thenReturn(savedFact, plan);
        when(eventRepository.findFactAggregatesByPlanIds(any())).thenReturn(Collections.emptyList());

        FinancialEventDto result = service.createLinkedFact(planId,
                new FactCreateDto(LocalDate.now(), BigDecimal.TEN, "оплатил", null));

        assertThat(result.eventKind()).isEqualTo(EventKind.FACT);
        assertThat(result.parentEventId()).isEqualTo(planId);
        assertThat(result.factAmount()).isEqualByComparingTo(BigDecimal.TEN);
        assertThat(plan.getStatus()).isEqualTo(EventStatus.EXECUTED);
        verify(eventRepository, times(2)).save(any());
    }

    @Test
    @DisplayName("createLinkedFact: без priority в DTO — наследует от плана")
    void createLinkedFact_noPriority_inheritsPlanPriority() {
        UUID planId = UUID.randomUUID();
        Category cat = category(); // returns Priority.HIGH
        FinancialEvent plan = aPlan(planId, cat, EventStatus.PLANNED);

        when(eventRepository.findById(planId)).thenReturn(Optional.of(plan));
        when(targetFundRepository.findById(any())).thenReturn(Optional.empty());

        FinancialEvent[] saved = new FinancialEvent[1];
        when(eventRepository.save(any())).thenAnswer(inv -> {
            FinancialEvent e = inv.getArgument(0);
            if (e.getEventKind() == EventKind.FACT) saved[0] = e;
            return e;
        });
        when(eventRepository.findFactAggregatesByPlanIds(any())).thenReturn(Collections.emptyList());

        service.createLinkedFact(planId,
                new FactCreateDto(LocalDate.now(), BigDecimal.TEN, null, null));

        assertThat(saved[0].getPriority()).isEqualTo(Priority.HIGH);
    }

    @Test
    @DisplayName("createLinkedFact: priority в DTO — использует его, не наследует")
    void createLinkedFact_withPriority_usesDtoPriority() {
        UUID planId = UUID.randomUUID();
        Category cat = category(); // returns Priority.HIGH
        FinancialEvent plan = aPlan(planId, cat, EventStatus.PLANNED);

        when(eventRepository.findById(planId)).thenReturn(Optional.of(plan));
        when(targetFundRepository.findById(any())).thenReturn(Optional.empty());

        FinancialEvent[] saved = new FinancialEvent[1];
        when(eventRepository.save(any())).thenAnswer(inv -> {
            FinancialEvent e = inv.getArgument(0);
            if (e.getEventKind() == EventKind.FACT) saved[0] = e;
            return e;
        });
        when(eventRepository.findFactAggregatesByPlanIds(any())).thenReturn(Collections.emptyList());

        service.createLinkedFact(planId,
                new FactCreateDto(LocalDate.now(), BigDecimal.TEN, null, Priority.LOW));

        assertThat(saved[0].getPriority()).isEqualTo(Priority.LOW);
    }

    @Test
    @DisplayName("softDelete: бросает 409 при попытке удалить PLAN с привязанными FACTs")
    void softDelete_planWithFacts_throws409() {
        UUID planId = UUID.randomUUID();
        FinancialEvent plan = aPlan(planId, category(), EventStatus.EXECUTED);
        when(eventRepository.findById(planId)).thenReturn(Optional.of(plan));

        FactAggregateProjection agg = mock(FactAggregateProjection.class);
        when(agg.getCount()).thenReturn(1L);
        when(eventRepository.findFactAggregatesByPlanIds(List.of(planId))).thenReturn(List.of(agg));

        assertThatThrownBy(() -> service.softDelete(planId))
                .hasMessageContaining("Cannot delete PLAN");
    }

    @Test
    @DisplayName("softDelete: удаление FACT переводит родительский PLAN обратно в PLANNED")
    void softDelete_lastFact_revertsPlanToPlanned() {
        UUID planId = UUID.randomUUID();
        UUID factId = UUID.randomUUID();
        Category cat = category();
        FinancialEvent plan = aPlan(planId, cat, EventStatus.EXECUTED);
        FinancialEvent fact = FinancialEvent.builder()
                .id(factId)
                .eventKind(EventKind.FACT)
                .parentEventId(planId)
                .category(cat)
                .type(EventType.EXPENSE)
                .factAmount(BigDecimal.TEN)
                .status(EventStatus.EXECUTED)
                .deleted(false)
                .build();

        when(eventRepository.findById(factId)).thenReturn(Optional.of(fact));
        when(eventRepository.findById(planId)).thenReturn(Optional.of(plan));
        // After deletion, no remaining facts
        when(eventRepository.findFactAggregatesByPlanIds(List.of(planId)))
                .thenReturn(Collections.emptyList());
        when(eventRepository.save(any())).thenReturn(fact);
        doNothing().when(eventRepository).flush();

        service.softDelete(factId);

        assertThat(plan.getStatus()).isEqualTo(EventStatus.PLANNED);
        verify(eventRepository, times(2)).save(any()); // deleted fact + reverted plan
    }

    // --- Task 3.7 test ---

    @Test
    @DisplayName("createLinkedFact: FACT inherits recurringRule from parent PLAN")
    void createLinkedFact_inherits_recurringRule_from_parent_plan() {
        UUID planId = UUID.randomUUID();
        Category cat = Category.builder().id(UUID.randomUUID()).build();
        RecurringRule rule = RecurringRule.builder().id(UUID.randomUUID()).build();
        FinancialEvent plan = FinancialEvent.builder()
                .id(planId)
                .eventKind(EventKind.PLAN)
                .category(cat)
                .type(EventType.EXPENSE)
                .recurringRule(rule)
                .priority(Priority.HIGH)
                .status(EventStatus.PLANNED)
                .build();

        when(eventRepository.findById(planId)).thenReturn(java.util.Optional.of(plan));
        when(eventRepository.save(any(FinancialEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(targetFundRepository.findById(any())).thenReturn(java.util.Optional.empty());

        var factDto = new ru.selfin.backend.dto.FactCreateDto(
                LocalDate.now(), new BigDecimal("90000"), "Оплачено", null);

        service.createLinkedFact(planId, factDto);

        ArgumentCaptor<FinancialEvent> cap = ArgumentCaptor.forClass(FinancialEvent.class);
        verify(eventRepository, atLeastOnce()).save(cap.capture());
        // First saved entity is the new FACT row.
        FinancialEvent fact = cap.getAllValues().get(0);
        assertThat(fact.getEventKind()).isEqualTo(EventKind.FACT);
        assertThat(fact.getRecurringRule()).isSameAs(rule);
    }

    // --- Task 3.3 tests ---

    @Test
    @DisplayName("createIdempotent: recurring != null delegates to ruleService and returns head event")
    void createIdempotent_withRecurring_delegatesToRuleService() {
        UUID idempKey = UUID.randomUUID();
        Category cat = category();
        when(eventRepository.findByIdempotencyKey(idempKey)).thenReturn(Optional.empty());
        when(categoryRepository.findById(cat.getId())).thenReturn(Optional.of(cat));

        RecurringRule rule = RecurringRule.builder().id(UUID.randomUUID()).build();
        LocalDate start = LocalDate.now().plusMonths(1).withDayOfMonth(15);
        FinancialEvent headEvent = FinancialEvent.builder()
                .id(UUID.randomUUID()).eventKind(EventKind.PLAN).category(cat)
                .type(EventType.EXPENSE).plannedAmount(new BigDecimal("5000"))
                .date(start).status(EventStatus.PLANNED).priority(Priority.HIGH)
                .recurringRule(rule)
                .build();
        FinancialEvent tailEvent = FinancialEvent.builder()
                .id(UUID.randomUUID()).eventKind(EventKind.PLAN).category(cat)
                .type(EventType.EXPENSE).plannedAmount(new BigDecimal("5000"))
                .date(start.plusMonths(1)).status(EventStatus.PLANNED).priority(Priority.HIGH)
                .recurringRule(rule)
                .build();

        RecurringConfigDto cfg = new RecurringConfigDto(RecurringFrequency.MONTHLY, 15, null, start, null);
        when(ruleService.createFromDto(eq(cat), eq(EventType.EXPENSE), any(), eq(Priority.HIGH),
                any(), any(), any(), eq(cfg)))
                .thenReturn(new RecurringRuleService.CreateResult(rule, List.of(headEvent, tailEvent)));
        when(eventRepository.save(any(FinancialEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(targetFundRepository.findById(any())).thenReturn(Optional.empty());

        var dto = new FinancialEventCreateDto(start, cat.getId(), EventType.EXPENSE,
                new BigDecimal("5000"), Priority.HIGH, "Ипотека", null, null, cfg);

        FinancialEventDto result = service.createIdempotent(idempKey, dto);

        verify(ruleService).createFromDto(eq(cat), eq(EventType.EXPENSE), any(), eq(Priority.HIGH),
                eq("Ипотека"), isNull(), isNull(), eq(cfg));
        ArgumentCaptor<FinancialEvent> cap = ArgumentCaptor.forClass(FinancialEvent.class);
        verify(eventRepository).save(cap.capture());
        assertThat(cap.getValue().getIdempotencyKey()).isEqualTo(idempKey);
        assertThat(result.eventKind()).isEqualTo(EventKind.PLAN);
    }

    @Test
    @DisplayName("createIdempotent: recurring == null does NOT call ruleService")
    void createIdempotent_withoutRecurring_doesNotCallRuleService() {
        UUID idempKey = UUID.randomUUID();
        Category cat = category();
        when(eventRepository.findByIdempotencyKey(idempKey)).thenReturn(Optional.empty());
        when(categoryRepository.findById(cat.getId())).thenReturn(Optional.of(cat));
        when(eventRepository.save(any(FinancialEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(targetFundRepository.findById(any())).thenReturn(Optional.empty());

        var dto = new FinancialEventCreateDto(LocalDate.now(), cat.getId(), EventType.EXPENSE,
                new BigDecimal("5000"), Priority.HIGH, null, null, null, null);

        service.createIdempotent(idempKey, dto);

        verifyNoInteractions(ruleService);
    }

    // --- Task 3.4b tests ---

    @Test
    @DisplayName("update THIS on recurring event: updates only that event, no ruleService interaction")
    void update_THIS_on_recurring_event_updates_only_that_event() {
        UUID id = UUID.randomUUID();
        Category cat = category();
        RecurringRule rule = RecurringRule.builder().id(UUID.randomUUID()).build();
        FinancialEvent event = FinancialEvent.builder()
                .id(id).eventKind(EventKind.PLAN).category(cat)
                .type(EventType.EXPENSE).plannedAmount(new BigDecimal("5000"))
                .date(LocalDate.now()).status(EventStatus.PLANNED).priority(Priority.HIGH)
                .recurringRule(rule).deleted(false).build();

        when(eventRepository.findById(id)).thenReturn(Optional.of(event));
        when(categoryRepository.findById(cat.getId())).thenReturn(Optional.of(cat));
        when(eventRepository.save(any(FinancialEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(targetFundRepository.findById(any())).thenReturn(Optional.empty());

        var dto = new FinancialEventCreateDto(LocalDate.now(), cat.getId(), EventType.EXPENSE,
                new BigDecimal("6000"), Priority.HIGH, null, null, null, null);

        service.update(id, ScopeEnum.THIS, dto);

        verifyNoInteractions(ruleService);
        assertThat(event.getPlannedAmount()).isEqualByComparingTo("6000");
    }

    @Test
    @DisplayName("update THIS on FACT throws 400")
    void update_THIS_on_FACT_throws_400() {
        UUID id = UUID.randomUUID();
        FinancialEvent fact = aFact(id);
        when(eventRepository.findById(id)).thenReturn(Optional.of(fact));

        var dto = new FinancialEventCreateDto(LocalDate.now(), null, EventType.EXPENSE,
                BigDecimal.ONE, null, null, null, null, null);

        assertThatThrownBy(() -> service.update(id, ScopeEnum.THIS, dto))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    @DisplayName("update FOLLOWING on non-recurring event throws 400")
    void update_FOLLOWING_on_non_recurring_event_throws_400() {
        UUID id = UUID.randomUUID();
        Category cat = category();
        FinancialEvent event = FinancialEvent.builder()
                .id(id).eventKind(EventKind.PLAN).category(cat)
                .type(EventType.EXPENSE).plannedAmount(new BigDecimal("5000"))
                .date(LocalDate.now()).status(EventStatus.PLANNED).priority(Priority.HIGH)
                .recurringRule(null).deleted(false).build();
        when(eventRepository.findById(id)).thenReturn(Optional.of(event));

        var dto = new FinancialEventCreateDto(LocalDate.now(), cat.getId(), EventType.EXPENSE,
                BigDecimal.ONE, null, null, null, null, null);

        assertThatThrownBy(() -> service.update(id, ScopeEnum.FOLLOWING, dto))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    @DisplayName("update FOLLOWING calls applyDtoToRule then regenerate from event date")
    void update_FOLLOWING_calls_applyDtoToRule_then_regenerate_from_event_date() {
        UUID id = UUID.randomUUID();
        Category cat = category();
        RecurringRule rule = RecurringRule.builder().id(UUID.randomUUID())
                .startDate(LocalDate.now().minusMonths(2)).build();
        LocalDate eventDate = LocalDate.now().plusMonths(1);
        FinancialEvent event = FinancialEvent.builder()
                .id(id).eventKind(EventKind.PLAN).category(cat)
                .type(EventType.EXPENSE).plannedAmount(new BigDecimal("5000"))
                .date(eventDate).status(EventStatus.PLANNED).priority(Priority.HIGH)
                .recurringRule(rule).deleted(false).build();

        when(eventRepository.findById(id)).thenReturn(Optional.of(event));
        when(eventRepository.findActiveByRuleAndDate(rule.getId(), eventDate))
                .thenReturn(Optional.of(event));
        when(targetFundRepository.findById(any())).thenReturn(Optional.empty());

        var dto = new FinancialEventCreateDto(eventDate, cat.getId(), EventType.EXPENSE,
                new BigDecimal("7000"), null, null, null, null, null);

        service.update(id, ScopeEnum.FOLLOWING, dto);

        verify(ruleService).applyDtoToRule(rule, dto);
        verify(ruleService).regenerate(rule, eventDate);
    }

    @Test
    @DisplayName("update FOLLOWING with changed startDate throws 400 (I8)")
    void update_FOLLOWING_with_changed_startDate_throws_400() {
        UUID id = UUID.randomUUID();
        Category cat = category();
        LocalDate ruleStart = LocalDate.now().plusMonths(1).withDayOfMonth(15);
        RecurringRule rule = RecurringRule.builder()
                .id(UUID.randomUUID())
                .startDate(ruleStart)
                .build();
        FinancialEvent event = FinancialEvent.builder()
                .id(id).eventKind(EventKind.PLAN).category(cat)
                .type(EventType.EXPENSE).plannedAmount(new BigDecimal("5000"))
                .date(ruleStart).status(EventStatus.PLANNED).priority(Priority.HIGH)
                .recurringRule(rule).deleted(false).build();

        when(eventRepository.findById(id)).thenReturn(Optional.of(event));
        when(eventRepository.findActiveByRuleAndDate(rule.getId(), ruleStart))
                .thenReturn(Optional.of(event));

        // Attempt to change startDate to ruleStart + 1 day
        RecurringConfigDto cfgWithChangedStart = new RecurringConfigDto(
                RecurringFrequency.MONTHLY, 15, null,
                ruleStart.plusDays(1),   // <-- different from rule.startDate
                null);
        var dto = new FinancialEventCreateDto(ruleStart, cat.getId(), EventType.EXPENSE,
                new BigDecimal("5000"), Priority.HIGH, null, null, null, cfgWithChangedStart);

        // applyDtoToRule on the real ruleService would throw; wire it up here
        doThrow(new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST,
                "start_date is immutable; delete the rule and create a new one (I8)"))
                .when(ruleService).applyDtoToRule(rule, dto);

        assertThatThrownBy(() -> service.update(id, ScopeEnum.FOLLOWING, dto))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .satisfies(ex -> {
                    var rse = (org.springframework.web.server.ResponseStatusException) ex;
                    assertThat(rse.getStatusCode())
                            .isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST);
                    assertThat(rse.getReason()).contains("start_date is immutable");
                });
    }

    @Test
    @DisplayName("update ALL calls regenerate from rule startDate")
    void update_ALL_calls_regenerate_from_rule_startDate() {
        UUID id = UUID.randomUUID();
        Category cat = category();
        LocalDate ruleStart = LocalDate.now().minusMonths(3).withDayOfMonth(1);
        RecurringRule rule = RecurringRule.builder().id(UUID.randomUUID())
                .startDate(ruleStart).build();
        LocalDate eventDate = LocalDate.now().plusMonths(1);
        FinancialEvent event = FinancialEvent.builder()
                .id(id).eventKind(EventKind.PLAN).category(cat)
                .type(EventType.EXPENSE).plannedAmount(new BigDecimal("5000"))
                .date(eventDate).status(EventStatus.PLANNED).priority(Priority.HIGH)
                .recurringRule(rule).deleted(false).build();

        when(eventRepository.findById(id)).thenReturn(Optional.of(event));
        when(eventRepository.findActiveByRuleAndDate(rule.getId(), eventDate))
                .thenReturn(Optional.of(event));
        when(targetFundRepository.findById(any())).thenReturn(Optional.empty());

        var dto = new FinancialEventCreateDto(eventDate, cat.getId(), EventType.EXPENSE,
                new BigDecimal("7000"), null, null, null, null, null);

        service.update(id, ScopeEnum.ALL, dto);

        verify(ruleService).applyDtoToRule(rule, dto);
        verify(ruleService).regenerate(rule, ruleStart);
    }

    // --- Task 3.4c tests ---

    @Test
    @DisplayName("delete THIS: soft-deletes only the event")
    void delete_THIS_softDeletes_only_event() {
        UUID id = UUID.randomUUID();
        Category cat = category();
        FinancialEvent event = FinancialEvent.builder()
                .id(id).eventKind(EventKind.PLAN).category(cat)
                .type(EventType.EXPENSE).date(LocalDate.now())
                .status(EventStatus.PLANNED).priority(Priority.HIGH)
                .deleted(false).build();
        when(eventRepository.findById(id)).thenReturn(Optional.of(event));
        when(eventRepository.save(any())).thenReturn(event);

        service.delete(id, ScopeEnum.THIS);

        assertThat(event.isDeleted()).isTrue();
        verify(eventRepository).save(event);
        verifyNoInteractions(ruleService);
    }

    @Test
    @DisplayName("delete FOLLOWING on non-recurring event throws 400")
    void delete_FOLLOWING_on_non_recurring_throws_400() {
        UUID id = UUID.randomUUID();
        FinancialEvent event = FinancialEvent.builder()
                .id(id).eventKind(EventKind.PLAN).category(category())
                .type(EventType.EXPENSE).date(LocalDate.now())
                .recurringRule(null).deleted(false).build();
        when(eventRepository.findById(id)).thenReturn(Optional.of(event));

        assertThatThrownBy(() -> service.delete(id, ScopeEnum.FOLLOWING))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    @DisplayName("delete FOLLOWING delegates to ruleService.deleteScope")
    void delete_FOLLOWING_delegates_to_ruleService_deleteScope() {
        UUID id = UUID.randomUUID();
        RecurringRule rule = RecurringRule.builder().id(UUID.randomUUID()).build();
        FinancialEvent event = FinancialEvent.builder()
                .id(id).eventKind(EventKind.PLAN).category(category())
                .type(EventType.EXPENSE).date(LocalDate.now())
                .recurringRule(rule).deleted(false).build();
        when(eventRepository.findById(id)).thenReturn(Optional.of(event));

        service.delete(id, ScopeEnum.FOLLOWING);

        verify(ruleService).deleteScope(event, ScopeEnum.FOLLOWING);
    }

    @Test
    @DisplayName("delete ALL delegates to ruleService.deleteScope")
    void delete_ALL_delegates_to_ruleService_deleteScope() {
        UUID id = UUID.randomUUID();
        RecurringRule rule = RecurringRule.builder().id(UUID.randomUUID()).build();
        FinancialEvent event = FinancialEvent.builder()
                .id(id).eventKind(EventKind.PLAN).category(category())
                .type(EventType.EXPENSE).date(LocalDate.now())
                .recurringRule(rule).deleted(false).build();
        when(eventRepository.findById(id)).thenReturn(Optional.of(event));

        service.delete(id, ScopeEnum.ALL);

        verify(ruleService).deleteScope(event, ScopeEnum.ALL);
    }

    // --- helpers ---

    private Category category() {
        return Category.builder()
                .id(UUID.randomUUID()).name("Коммуналка")
                .type(CategoryType.EXPENSE).priority(Priority.HIGH).build();
    }

    private FinancialEvent aPlan(UUID id, Category cat, EventStatus status) {
        return FinancialEvent.builder()
                .id(id).eventKind(EventKind.PLAN).category(cat)
                .type(EventType.EXPENSE).plannedAmount(new BigDecimal("5000"))
                .status(status).priority(Priority.HIGH).date(LocalDate.now()).build();
    }

    private FinancialEvent aFact(UUID id) {
        return FinancialEvent.builder()
                .id(id).eventKind(EventKind.FACT).category(category())
                .type(EventType.EXPENSE).factAmount(BigDecimal.TEN)
                .status(EventStatus.EXECUTED).build();
    }
}
