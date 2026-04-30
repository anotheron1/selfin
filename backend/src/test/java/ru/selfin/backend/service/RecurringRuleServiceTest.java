package ru.selfin.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.selfin.backend.dto.RecurringConfigDto;
import ru.selfin.backend.model.Category;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.RecurringRule;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.Priority;
import ru.selfin.backend.model.enums.RecurringFrequency;
import ru.selfin.backend.repository.FinancialEventRepository;
import ru.selfin.backend.repository.RecurringRuleRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RecurringRuleServiceTest {

    private RecurringRuleRepository ruleRepo;
    private FinancialEventRepository eventRepo;
    private RecurringEventGenerator generator;
    private RecurringRuleService service;

    private Category category;

    @BeforeEach
    void setUp() {
        ruleRepo = mock(RecurringRuleRepository.class);
        eventRepo = mock(FinancialEventRepository.class);
        generator = new RecurringEventGenerator();    // real — пусть выдаёт настоящие даты
        service = new RecurringRuleService(ruleRepo, eventRepo, generator);
        category = Category.builder().id(UUID.randomUUID()).build();

        // ruleRepo.save returns its argument with id
        when(ruleRepo.save(any(RecurringRule.class))).thenAnswer(inv -> {
            RecurringRule r = inv.getArgument(0);
            if (r.getId() == null) r.setId(UUID.randomUUID());
            return r;
        });
        when(eventRepo.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void createFromDto_with_endDate_generates_bounded_set() {
        // Use relative dates so the test does not rot when calendar moves past pinned dates.
        // I3: startDate must be today-or-later; we anchor on next month's 15th.
        LocalDate start = LocalDate.now().plusMonths(1).withDayOfMonth(15);
        LocalDate end = start.plusMonths(2);
        RecurringConfigDto cfg = new RecurringConfigDto(
                RecurringFrequency.MONTHLY, 15, null, start, end);

        var result = service.createFromDto(category, EventType.EXPENSE, new BigDecimal("80000"),
                Priority.HIGH, "Ипотека", cfg);

        assertThat(result.rule().getStartDate()).isEqualTo(start);
        ArgumentCaptor<List<FinancialEvent>> cap = ArgumentCaptor.forClass(List.class);
        verify(eventRepo).saveAll(cap.capture());
        assertThat(cap.getValue()).extracting("date").containsExactly(
                start, start.plusMonths(1), start.plusMonths(2));
    }

    @Test
    void regenerate_softDeletes_planEvents_from_cutoff_and_skips_executed_dates() {
        UUID ruleId = UUID.randomUUID();
        RecurringRule rule = RecurringRule.builder()
                .id(ruleId)
                .category(category)
                .eventType(EventType.EXPENSE)
                .plannedAmount(new BigDecimal("80000"))
                .frequency(RecurringFrequency.MONTHLY)
                .dayOfMonth(15)
                .startDate(LocalDate.of(2026, 5, 15))
                .endDate(LocalDate.of(2026, 8, 15))
                .build();

        // 2026-06-15 уже EXECUTED — генерация должна его пропустить
        when(eventRepo.findExecutedDatesByRule(ruleId))
                .thenReturn(java.util.Set.of(LocalDate.of(2026, 6, 15)));

        service.regenerate(rule, LocalDate.of(2026, 6, 15));

        verify(eventRepo).softDeletePlanEventsByRuleFromDate(ruleId, LocalDate.of(2026, 6, 15));

        ArgumentCaptor<List<FinancialEvent>> cap = ArgumentCaptor.forClass(List.class);
        verify(eventRepo).saveAll(cap.capture());
        assertThat(cap.getValue()).extracting("date").containsExactly(
                // 2026-06-15 пропущен (EXECUTED)
                LocalDate.of(2026, 7, 15),
                LocalDate.of(2026, 8, 15));
    }

    @Test
    void regenerate_indefinite_uses_now_plus_36_months_horizon() {
        // endDate==null path: horizon = LocalDate.now().plusMonths(36)
        // Use a startDate in the future and expect the generator stops before now+36mo.
        UUID ruleId = UUID.randomUUID();
        LocalDate start = LocalDate.now().plusMonths(1).withDayOfMonth(15);
        RecurringRule rule = RecurringRule.builder()
                .id(ruleId)
                .category(category)
                .eventType(EventType.EXPENSE)
                .plannedAmount(new BigDecimal("80000"))
                .frequency(RecurringFrequency.MONTHLY)
                .dayOfMonth(15)
                .startDate(start)
                .endDate(null)               // бессрочно
                .build();

        when(eventRepo.findExecutedDatesByRule(ruleId)).thenReturn(java.util.Set.of());

        service.regenerate(rule, start);

        ArgumentCaptor<List<FinancialEvent>> cap = ArgumentCaptor.forClass(List.class);
        verify(eventRepo).saveAll(cap.capture());
        // Все сгенерированные даты должны быть <= now+36mo.
        LocalDate horizonCap = LocalDate.now().plusMonths(36);
        assertThat(cap.getValue())
                .isNotEmpty()
                .allSatisfy(e -> assertThat(e.getDate()).isBeforeOrEqualTo(horizonCap));
        // И первая дата = startDate.
        assertThat(cap.getValue().get(0).getDate()).isEqualTo(start);
    }

    @Test
    void extendIndefiniteRules_appends_only_missing_dates() {
        UUID ruleId = UUID.randomUUID();
        RecurringRule rule = RecurringRule.builder()
                .id(ruleId)
                .category(category)
                .eventType(EventType.EXPENSE)
                .plannedAmount(new BigDecimal("80000"))
                .frequency(RecurringFrequency.MONTHLY)
                .dayOfMonth(15)
                .startDate(LocalDate.of(2026, 5, 15))
                .endDate(null)               // бессрочно
                .build();

        when(ruleRepo.findIndefiniteActiveIds()).thenReturn(List.of(ruleId));
        when(ruleRepo.findForUpdate(ruleId)).thenReturn(java.util.Optional.of(rule));
        when(eventRepo.findMaxActiveDateByRule(ruleId))
                .thenReturn(java.util.Optional.of(LocalDate.of(2026, 6, 15)));

        service.extendIndefiniteRules(LocalDate.of(2026, 8, 15));

        ArgumentCaptor<List<FinancialEvent>> cap = ArgumentCaptor.forClass(List.class);
        verify(eventRepo).saveAll(cap.capture());
        assertThat(cap.getValue()).extracting("date").containsExactly(
                LocalDate.of(2026, 7, 15),
                LocalDate.of(2026, 8, 15));
    }

    @Test
    void extendIndefiniteRules_noop_when_already_covered() {
        UUID ruleId = UUID.randomUUID();
        RecurringRule rule = RecurringRule.builder()
                .id(ruleId)
                .category(category)
                .eventType(EventType.EXPENSE)
                .plannedAmount(new BigDecimal("80000"))
                .frequency(RecurringFrequency.MONTHLY)
                .dayOfMonth(15)
                .startDate(LocalDate.of(2026, 5, 15))
                .endDate(null)
                .build();
        when(ruleRepo.findIndefiniteActiveIds()).thenReturn(List.of(ruleId));
        when(ruleRepo.findForUpdate(ruleId)).thenReturn(java.util.Optional.of(rule));
        when(eventRepo.findMaxActiveDateByRule(ruleId))
                .thenReturn(java.util.Optional.of(LocalDate.of(2027, 1, 15)));   // уже за горизонтом

        service.extendIndefiniteRules(LocalDate.of(2026, 12, 31));

        verify(eventRepo, never()).saveAll(any());
    }

    @Test
    void deleteScope_FOLLOWING_setsEndDate_and_softDeletesPlan() {
        UUID ruleId = UUID.randomUUID();
        RecurringRule rule = RecurringRule.builder()
                .id(ruleId)
                .category(category)
                .eventType(EventType.EXPENSE)
                .plannedAmount(new BigDecimal("80000"))
                .frequency(RecurringFrequency.MONTHLY)
                .dayOfMonth(15)
                .startDate(LocalDate.of(2026, 5, 15))
                .endDate(LocalDate.of(2027, 5, 15))
                .build();

        FinancialEvent triggerEvent = FinancialEvent.builder()
                .id(UUID.randomUUID())
                .date(LocalDate.of(2026, 7, 15))
                .recurringRule(rule)
                .build();

        service.deleteScope(triggerEvent, ru.selfin.backend.model.enums.ScopeEnum.FOLLOWING);

        verify(eventRepo).softDeletePlanEventsByRuleFromDate(ruleId, LocalDate.of(2026, 7, 15));
        assertThat(rule.getEndDate()).isEqualTo(LocalDate.of(2026, 7, 14));
        assertThat(rule.isDeleted()).isFalse();
    }

    @Test
    void deleteScope_ALL_marksRuleDeleted_and_endsOnLastExecuted() {
        UUID ruleId = UUID.randomUUID();
        RecurringRule rule = RecurringRule.builder()
                .id(ruleId)
                .category(category)
                .eventType(EventType.EXPENSE)
                .plannedAmount(new BigDecimal("80000"))
                .frequency(RecurringFrequency.MONTHLY)
                .dayOfMonth(15)
                .startDate(LocalDate.of(2026, 5, 15))
                .endDate(LocalDate.of(2027, 5, 15))
                .build();

        FinancialEvent triggerEvent = FinancialEvent.builder()
                .id(UUID.randomUUID())
                .date(LocalDate.of(2026, 7, 15))
                .recurringRule(rule)
                .build();

        when(eventRepo.findMaxExecutedDateByRule(ruleId))
                .thenReturn(java.util.Optional.of(LocalDate.of(2026, 6, 15)));

        service.deleteScope(triggerEvent, ru.selfin.backend.model.enums.ScopeEnum.ALL);

        verify(eventRepo).softDeletePlanEventsByRuleFromDate(ruleId, rule.getStartDate());
        assertThat(rule.isDeleted()).isTrue();
        assertThat(rule.getEndDate()).isEqualTo(LocalDate.of(2026, 6, 15));
    }

    @Test
    void deleteScope_ALL_with_no_executed_events_endsBeforeStart() {
        UUID ruleId = UUID.randomUUID();
        RecurringRule rule = RecurringRule.builder()
                .id(ruleId)
                .category(category)
                .eventType(EventType.EXPENSE)
                .plannedAmount(new BigDecimal("80000"))
                .frequency(RecurringFrequency.MONTHLY)
                .dayOfMonth(15)
                .startDate(LocalDate.of(2026, 5, 15))
                .endDate(LocalDate.of(2027, 5, 15))
                .build();

        FinancialEvent triggerEvent = FinancialEvent.builder()
                .id(UUID.randomUUID())
                .date(LocalDate.of(2026, 7, 15))
                .recurringRule(rule)
                .build();

        when(eventRepo.findMaxExecutedDateByRule(ruleId)).thenReturn(java.util.Optional.empty());

        service.deleteScope(triggerEvent, ru.selfin.backend.model.enums.ScopeEnum.ALL);

        assertThat(rule.isDeleted()).isTrue();
        assertThat(rule.getEndDate()).isEqualTo(LocalDate.of(2026, 5, 14)); // startDate - 1
    }
}
