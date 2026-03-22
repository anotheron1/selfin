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
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FundPlannerServiceTest {

    private FinancialEventRepository eventRepository;
    private FundPlannerService service;

    @BeforeEach
    void setUp() {
        eventRepository = mock(FinancialEventRepository.class);
        service = new FundPlannerService(eventRepository);
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
        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);
        LocalDate tomorrow = today.plusDays(1);

        FinancialEvent past = makeEvent(yesterday, EventType.INCOME, EventStatus.PLANNED,
                Priority.MEDIUM, new BigDecimal("10000"), null);
        FinancialEvent future = makeEvent(tomorrow, EventType.INCOME, EventStatus.PLANNED,
                Priority.MEDIUM, new BigDecimal("5000"), null);

        when(eventRepository.findAllByDeletedFalseAndStatusNot(EventStatus.CANCELLED))
                .thenReturn(List.of(past, future));

        var result = service.getPlanner();
        var month0 = result.months().get(0);

        assertThat(month0.plannedIncome()).isEqualByComparingTo(new BigDecimal("5000"));
    }

    @Test
    @DisplayName("first month factExpenses includes all executed expenses (past + future)")
    void firstMonthFactExpensesIncludesPastExecuted() {
        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);
        LocalDate tomorrow = today.plusDays(1);

        // V12: PLAN record for executed event (factAmount=null, status=EXECUTED)
        FinancialEvent pastPlan = makeEvent(yesterday, EventType.EXPENSE, EventStatus.EXECUTED,
                Priority.MEDIUM, new BigDecimal("3000"), null);
        // Future planned event
        FinancialEvent futurePlanned = makeEvent(tomorrow, EventType.EXPENSE, EventStatus.PLANNED,
                Priority.MEDIUM, new BigDecimal("2000"), null);

        // Plans query returns PLAN records only
        when(eventRepository.findAllByDeletedFalseAndStatusNot(EventStatus.CANCELLED))
                .thenReturn(List.of(pastPlan, futurePlanned));

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
        LocalDate firstDayNextMonth = LocalDate.now().plusMonths(1).withDayOfMonth(1);

        FinancialEvent e = makeEvent(firstDayNextMonth, EventType.INCOME, EventStatus.PLANNED,
                Priority.MEDIUM, new BigDecimal("8000"), null);

        when(eventRepository.findAllByDeletedFalseAndStatusNot(EventStatus.CANCELLED))
                .thenReturn(List.of(e));

        var result = service.getPlanner();
        var month1 = result.months().get(1);

        assertThat(month1.plannedIncome()).isEqualByComparingTo(new BigDecimal("8000"));
    }
}
