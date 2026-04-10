package ru.selfin.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.selfin.backend.dto.AnalyticsReportDto;
import ru.selfin.backend.dto.MultiMonthReportDto;
import ru.selfin.backend.dto.MultiMonthReportDto.*;
import ru.selfin.backend.model.Category;
import ru.selfin.backend.model.EventKind;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.enums.CategoryType;
import ru.selfin.backend.model.enums.EventStatus;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.Priority;
import ru.selfin.backend.model.BalanceCheckpoint;
import ru.selfin.backend.repository.BalanceCheckpointRepository;
import ru.selfin.backend.repository.FinancialEventRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AnalyticsServiceTest {

    private FinancialEventRepository eventRepository;
    private BalanceCheckpointRepository checkpointRepository;
    private AnalyticsService service;

    private static final LocalDate TODAY = LocalDate.of(2026, 3, 15);

    @BeforeEach
    void setUp() {
        eventRepository = mock(FinancialEventRepository.class);
        checkpointRepository = mock(BalanceCheckpointRepository.class);
        when(checkpointRepository.findTopByOrderByDateDesc()).thenReturn(Optional.empty());
        service = new AnalyticsService(eventRepository, checkpointRepository);
    }

    // ─── buildPlanFact sort (via getReport) ───────────────────────────────────

    @Test
    @DisplayName("buildPlanFact: категории отсортированы по имени в русском алфавитном порядке")
    void planFact_categoriesSortedAlphabetically() {
        LocalDate monthStart = TODAY.withDayOfMonth(1);
        LocalDate monthEnd = TODAY.withDayOfMonth(TODAY.lengthOfMonth());

        List<FinancialEvent> events = List.of(
                expenseOn("Еда",    TODAY.withDayOfMonth(5), bd(10_000), bd(8_000)),
                expenseOn("Аренда", TODAY.withDayOfMonth(5), bd(30_000), bd(30_000)),
                expenseOn("Бензин", TODAY.withDayOfMonth(5), bd(5_000),  bd(3_300))
        );
        when(eventRepository.findAllByDeletedFalseAndDateBetween(monthStart, monthEnd))
                .thenReturn(events);

        AnalyticsReportDto report = service.getReport(TODAY);

        List<String> names = report.planFact().categories().stream()
                .map(c -> c.categoryName())
                .toList();
        assertThat(names).containsExactly("Аренда", "Бензин", "Еда");
    }

    // ─── getMultiMonthReport sort ─────────────────────────────────────────────

    @Test
    @DisplayName("getMultiMonthReport: строки категорий отсортированы по имени в русском алфавитном порядке")
    void multiMonth_categoriesSortedAlphabetically() {
        LocalDate start = TODAY.withDayOfMonth(1);
        LocalDate end   = TODAY.withDayOfMonth(TODAY.lengthOfMonth());
        LocalDate eventDate = TODAY.withDayOfMonth(5);

        List<FinancialEvent> events = List.of(
                expenseOn("Еда",    eventDate, bd(10_000), null),
                expenseOn("Аренда", eventDate, bd(30_000), null),
                expenseOn("Бензин", eventDate, bd(5_000),  null)
        );
        when(eventRepository.findAllByDeletedFalseAndDateBetween(start, end))
                .thenReturn(events);

        MultiMonthReportDto report = service.getMultiMonthReport(start, end);

        List<String> categoryLabels = report.rows().stream()
                .filter(r -> r.type() == RowType.CATEGORY)
                .map(RowDto::label)
                .toList();
        assertThat(categoryLabels).containsExactly("Аренда", "Бензин", "Еда");
    }

    // ─── cashFlow horizon ────────────────────────────────────────────────────

    @Test
    @DisplayName("cashFlow extends 14 days ahead, crossing month boundary; planFact stays within month")
    void cashFlow_extendsBeyondMonthEnd() {
        LocalDate today = LocalDate.of(2026, 3, 28);
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate monthEnd = today.withDayOfMonth(today.lengthOfMonth()); // March 31
        LocalDate calendarEnd = today.plusDays(14); // April 11

        // March events (month-scoped query)
        FinancialEvent marchExpense = expenseOn("Еда", LocalDate.of(2026, 3, 30), bd(5_000), null);
        // April events (extended query)
        FinancialEvent aprilIncome = incomeOn("Зарплата", LocalDate.of(2026, 4, 5), bd(100_000), null);

        when(eventRepository.findAllByDeletedFalseAndDateBetween(monthStart, monthEnd))
                .thenReturn(List.of(marchExpense));
        when(eventRepository.findAllByDeletedFalseAndDateBetween(monthEnd.plusDays(1), calendarEnd))
                .thenReturn(List.of(aprilIncome));

        AnalyticsReportDto report = service.getReport(today);

        // cashFlow should contain both March and April days
        List<LocalDate> cashFlowDates = report.cashFlow().stream()
                .map(AnalyticsReportDto.CashFlowDay::date)
                .toList();
        assertThat(cashFlowDates).contains(
                LocalDate.of(2026, 3, 28),  // today
                LocalDate.of(2026, 3, 30),  // march expense
                LocalDate.of(2026, 4, 5)    // april income
        );

        // Last cashFlow date should not exceed calendarEnd
        LocalDate lastDate = cashFlowDates.get(cashFlowDates.size() - 1);
        assertThat(lastDate).isBeforeOrEqualTo(calendarEnd);

        // planFact must NOT include April events — only Еда category
        List<String> pfCategories = report.planFact().categories().stream()
                .map(AnalyticsReportDto.CategoryPlanFact::categoryName)
                .toList();
        assertThat(pfCategories).containsExactly("Еда");
    }

    @Test
    @DisplayName("cashFlow stays within month when today+14 < monthEnd")
    void cashFlow_staysWithinMonth_whenHorizonInsideMonth() {
        // TODAY = March 15, today+14 = March 29, monthEnd = March 31
        // calendarEnd = max(March 29, March 31) = March 31 — no second query
        LocalDate monthStart = TODAY.withDayOfMonth(1);
        LocalDate monthEnd = TODAY.withDayOfMonth(TODAY.lengthOfMonth());

        FinancialEvent expense = expenseOn("Еда", LocalDate.of(2026, 3, 20), bd(3_000), null);
        when(eventRepository.findAllByDeletedFalseAndDateBetween(monthStart, monthEnd))
                .thenReturn(List.of(expense));

        AnalyticsReportDto report = service.getReport(TODAY);

        // Only one repository call (no extended query)
        verify(eventRepository, times(1)).findAllByDeletedFalseAndDateBetween(any(), any());

        // cashFlow should contain the event day and today
        List<LocalDate> dates = report.cashFlow().stream()
                .map(AnalyticsReportDto.CashFlowDay::date).toList();
        assertThat(dates).contains(TODAY, LocalDate.of(2026, 3, 20));
    }

    // ─── calcStartBalance (via getReport cashFlow[0].runningBalance) ─────────

    @Test
    void getReport_noCheckpoint_startBalanceIsZero() {
        when(checkpointRepository.findTopByOrderByDateDesc()).thenReturn(Optional.empty());
        when(eventRepository.findAllByDeletedFalseAndDateBetween(any(), any())).thenReturn(List.of());

        AnalyticsReportDto report = service.getReport(LocalDate.of(2026, 4, 9));

        // No checkpoint → startBalance = 0, first cash flow day has runningBalance = 0
        assertThat(report.cashFlow()).isNotNull();
        assertThat(report.cashFlow().get(0).runningBalance()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void getReport_checkpointInCurrentMonth_nobridge() {
        LocalDate checkpointDate = LocalDate.of(2026, 4, 5);
        BalanceCheckpoint cp = new BalanceCheckpoint();
        cp.setDate(checkpointDate);
        cp.setAmount(BigDecimal.valueOf(50000));
        when(checkpointRepository.findTopByOrderByDateDesc()).thenReturn(Optional.of(cp));
        // Stub the main-month query explicitly so the verify below is unambiguous
        when(eventRepository.findAllByDeletedFalseAndDateBetween(
                eq(LocalDate.of(2026, 4, 1)), eq(LocalDate.of(2026, 4, 30)))).thenReturn(List.of());

        AnalyticsReportDto report = service.getReport(LocalDate.of(2026, 4, 9));

        // Checkpoint in same month → startBalance = checkpoint amount, no bridge call
        assertThat(report.cashFlow()).isNotNull();
        assertThat(report.cashFlow().get(0).runningBalance()).isEqualByComparingTo(BigDecimal.valueOf(50000));
        // Verify bridge NOT called for previous month
        verify(eventRepository, never()).findAllByDeletedFalseAndDateBetween(
                eq(checkpointDate), eq(LocalDate.of(2026, 3, 31)));
    }

    @Test
    void getReport_checkpointInPreviousMonth_bridgeEventsApplied() {
        LocalDate checkpointDate = LocalDate.of(2026, 3, 20);
        BalanceCheckpoint cp = new BalanceCheckpoint();
        cp.setDate(checkpointDate);
        cp.setAmount(BigDecimal.valueOf(30000));
        when(checkpointRepository.findTopByOrderByDateDesc()).thenReturn(Optional.of(cp));
        when(eventRepository.findAllByDeletedFalseAndDateBetween(any(), any())).thenReturn(List.of());

        service.getReport(LocalDate.of(2026, 4, 9));

        // Bridge called for [checkpointDate, March 31]
        verify(eventRepository).findAllByDeletedFalseAndDateBetween(
                eq(checkpointDate), eq(LocalDate.of(2026, 3, 31)));
    }

    @Test
    void getReport_checkpointTwoMonthsAgo_bridgeEventsApplied() {
        LocalDate checkpointDate = LocalDate.of(2026, 2, 15);
        BalanceCheckpoint cp = new BalanceCheckpoint();
        cp.setDate(checkpointDate);
        cp.setAmount(BigDecimal.valueOf(20000));
        when(checkpointRepository.findTopByOrderByDateDesc()).thenReturn(Optional.of(cp));
        when(eventRepository.findAllByDeletedFalseAndDateBetween(any(), any())).thenReturn(List.of());

        service.getReport(LocalDate.of(2026, 4, 9));

        // Bridge called for [checkpointDate, March 31] (day before April 1)
        verify(eventRepository).findAllByDeletedFalseAndDateBetween(
                eq(checkpointDate), eq(LocalDate.of(2026, 3, 31)));
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private FinancialEvent expenseOn(String categoryName, LocalDate date,
            BigDecimal planned, BigDecimal fact) {
        Category cat = Category.builder()
                .id(UUID.randomUUID())
                .name(categoryName)
                .type(CategoryType.EXPENSE)
                .build();
        return FinancialEvent.builder()
                .id(UUID.randomUUID())
                .date(date)
                .category(cat)
                .type(EventType.EXPENSE)
                .eventKind(EventKind.PLAN)
                .plannedAmount(planned)
                .factAmount(fact)
                .status(fact != null ? EventStatus.EXECUTED : EventStatus.PLANNED)
                .priority(Priority.MEDIUM)
                .deleted(false)
                .build();
    }

    private FinancialEvent incomeOn(String categoryName, LocalDate date,
            BigDecimal planned, BigDecimal fact) {
        Category cat = Category.builder()
                .id(UUID.randomUUID())
                .name(categoryName)
                .type(CategoryType.INCOME)
                .build();
        return FinancialEvent.builder()
                .id(UUID.randomUUID())
                .date(date)
                .category(cat)
                .type(EventType.INCOME)
                .eventKind(EventKind.PLAN)
                .plannedAmount(planned)
                .factAmount(fact)
                .status(fact != null ? EventStatus.EXECUTED : EventStatus.PLANNED)
                .priority(Priority.MEDIUM)
                .deleted(false)
                .build();
    }

    private static BigDecimal bd(long value) {
        return BigDecimal.valueOf(value);
    }
}
