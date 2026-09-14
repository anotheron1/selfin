package ru.selfin.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.selfin.backend.model.EventKind;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.enums.EventStatus;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.Priority;
import ru.selfin.backend.repository.FinancialEventRepository;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FundPlannerServiceTest {

    private FinancialEventRepository eventRepository;
    private RecurringRuleService recurringRuleService;
    private FundPlannerService service;

    /**
     * ANO-39: 31-е число — тот самый день, в который эти тесты падали.
     *
     * <p>31 июля 2026 сборка на {@code main} была красной не из-за регрессии: тесты брали
     * {@code today.plusDays(1)} как «будущее внутри месяца-0», а в последний день месяца
     * «завтра» уезжает в месяц-1 и выпадает из агрегата. То есть они падали бы в последний
     * день ЛЮБОГО месяца — три-четыре раза в квартал, всегда неожиданно.
     *
     * <p>Тогда их залечили выбором безопасного дня; причину — статический {@code now()} —
     * не тронули. Теперь «сегодня» задаётся здесь, и краевой день проверяется КАЖДЫЙ прогон,
     * а не в те дни, когда не повезло.
     */
    private static final Clock LAST_DAY_OF_MONTH = Clock.fixed(
            LocalDate.of(2026, 1, 31).atStartOfDay(ZoneId.systemDefault()).toInstant(),
            ZoneId.systemDefault());

    @BeforeEach
    void setUp() {
        eventRepository = mock(FinancialEventRepository.class);
        recurringRuleService = mock(RecurringRuleService.class);
        service = new FundPlannerService(eventRepository, recurringRuleService, LAST_DAY_OF_MONTH);
        // По умолчанию просроченных обязательных планов нет
        when(eventRepository.sumOverdueMandatoryExpenses(any(), any())).thenReturn(BigDecimal.ZERO);
        // По умолчанию FACT-записей текущего месяца нет
        when(eventRepository.findFactsByDateRange(any(), any())).thenReturn(List.of());
    }

    private FinancialEvent makeEvent(LocalDate date, EventType type, EventStatus status,
                                     Priority priority, BigDecimal planned, BigDecimal fact) {
        FinancialEvent e = new FinancialEvent();
        e.setId(UUID.randomUUID());
        e.setDate(date);
        e.setType(type);
        e.setStatus(status);
        e.setPriority(priority);
        e.setDeleted(false);
        // V12: PLAN records have plannedAmount only; FACT records have factAmount only
        if (fact != null) {
            e.setEventKind(EventKind.FACT);
            e.setFactAmount(fact);
            e.setPlannedAmount(null);
        } else {
            e.setEventKind(EventKind.PLAN);
            e.setPlannedAmount(planned);
            e.setFactAmount(null);
        }
        return e;
    }

    @Test
    @DisplayName("first month plannedIncome excludes past events")
    void firstMonthExcludesPastPlannedIncome() {
        LocalDate today = LocalDate.now(LAST_DAY_OF_MONTH);   // 31.01.2026
        LocalDate yesterday = today.minusDays(1);

        // Месяц-0 фильтруется как [today .. конец месяца], today входит (!isBefore(today)).
        // В последний день месяца это ЕДИНСТВЕННЫЙ день, который в месяц-0 попадает:
        // «завтра» уже февраль. Раньше эту тесноту обходили выбором дня прогона, теперь она
        // проверяется намеренно — часы стоят на 31-м (ANO-39).
        FinancialEvent past = makeEvent(yesterday, EventType.INCOME, EventStatus.PLANNED,
                Priority.MEDIUM, new BigDecimal("10000"), null);
        FinancialEvent notPast = makeEvent(today, EventType.INCOME, EventStatus.PLANNED,
                Priority.MEDIUM, new BigDecimal("5000"), null);

        when(eventRepository.findAllByDeletedFalseAndStatusNot(EventStatus.CANCELLED))
                .thenReturn(List.of(past, notPast));

        var result = service.getPlanner();
        var month0 = result.months().get(0);

        assertThat(month0.plannedIncome()).isEqualByComparingTo(new BigDecimal("5000"));
    }

    @Test
    @DisplayName("first month factExpenses includes all executed expenses (past + future)")
    void firstMonthFactExpensesIncludesPastExecuted() {
        LocalDate today = LocalDate.now(LAST_DAY_OF_MONTH);   // 31.01.2026
        LocalDate yesterday = today.minusDays(1);

        // V12: PLAN record for executed event (factAmount=null, status=EXECUTED)
        FinancialEvent pastPlan = makeEvent(yesterday, EventType.EXPENSE, EventStatus.EXECUTED,
                Priority.MEDIUM, new BigDecimal("3000"), null);
        // Ещё не прошедший план: в последний день месяца это только today — см. выше (ANO-39)
        FinancialEvent plannedToday = makeEvent(today, EventType.EXPENSE, EventStatus.PLANNED,
                Priority.MEDIUM, new BigDecimal("2000"), null);

        // Plans query returns PLAN records only
        when(eventRepository.findAllByDeletedFalseAndStatusNot(EventStatus.CANCELLED))
                .thenReturn(List.of(pastPlan, plannedToday));

        // V12: FACT record from separate query
        FinancialEvent pastFact = makeEvent(yesterday, EventType.EXPENSE, EventStatus.EXECUTED,
                Priority.MEDIUM, null, new BigDecimal("3000"));
        when(eventRepository.findFactsByDateRange(any(), any()))
                .thenReturn(List.of(pastFact));

        var result = service.getPlanner();
        var month0 = result.months().get(0);

        assertThat(month0.factExpenses()).isEqualByComparingTo(new BigDecimal("3000"));
        assertThat(month0.allPlannedExpenses()).isEqualByComparingTo(new BigDecimal("2000"));
    }

    @Test
    @DisplayName("month-0 mandatoryExpenses includes overdue HIGH plans from current month")
    void firstMonth_mandatoryExpenses_includesOverdueHighPlans() {
        when(eventRepository.findAllByDeletedFalseAndStatusNot(EventStatus.CANCELLED))
                .thenReturn(List.of());
        when(eventRepository.sumOverdueMandatoryExpenses(any(), any()))
                .thenReturn(new BigDecimal("3000"));

        var result = service.getPlanner();
        var month0 = result.months().get(0);

        assertThat(month0.mandatoryExpenses()).isEqualByComparingTo(new BigDecimal("3000"));
    }

    @Test
    @DisplayName("second month is not filtered — includes all events in that month")
    void secondMonthNotFiltered() {
        // От тех же часов, что у сервиса: иначе тест строит событие в одном месяце, а
        // планировщик считает другой, и совпадение зависит от дня прогона (ANO-39).
        LocalDate firstDayNextMonth =
                LocalDate.now(LAST_DAY_OF_MONTH).plusMonths(1).withDayOfMonth(1);

        FinancialEvent e = makeEvent(firstDayNextMonth, EventType.INCOME, EventStatus.PLANNED,
                Priority.MEDIUM, new BigDecimal("8000"), null);

        when(eventRepository.findAllByDeletedFalseAndStatusNot(EventStatus.CANCELLED))
                .thenReturn(List.of(e));

        var result = service.getPlanner();
        var month1 = result.months().get(1);

        assertThat(month1.plannedIncome()).isEqualByComparingTo(new BigDecimal("8000"));
    }
}
