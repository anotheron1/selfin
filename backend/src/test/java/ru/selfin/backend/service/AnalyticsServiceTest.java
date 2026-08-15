package ru.selfin.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.selfin.backend.dto.AnalyticsReportDto;
import ru.selfin.backend.dto.MultiMonthReportDto;
import ru.selfin.backend.dto.MultiMonthReportDto.*;
import ru.selfin.backend.model.Account;
import ru.selfin.backend.model.Category;
import ru.selfin.backend.model.EventKind;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.enums.AccountKind;
import ru.selfin.backend.model.enums.CategoryType;
import ru.selfin.backend.model.enums.EventStatus;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.Priority;
import ru.selfin.backend.model.BalanceCheckpoint;
import ru.selfin.backend.repository.AccountRepository;
import ru.selfin.backend.repository.BalanceCheckpointRepository;
import ru.selfin.backend.repository.FinancialEventRepository;
import ru.selfin.backend.testsupport.AccountFixtures;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AnalyticsServiceTest {

    private FinancialEventRepository eventRepository;
    private AccountRepository accountRepository;
    private BalanceCheckpointRepository checkpointRepository;
    private AnalyticsService service;

    private static final LocalDate TODAY = LocalDate.of(2026, 3, 15);

    @BeforeEach
    void setUp() {
        eventRepository = mock(FinancialEventRepository.class);
        accountRepository = mock(AccountRepository.class);
        checkpointRepository = mock(BalanceCheckpointRepository.class);
        // Реальный AccountBalanceService поверх замоканных репозиториев — иначе подмена
        // якоря в calcStartBalance (ANO-9 Task 2.2а) не проверяется, только её обвязка
        // (тот же приём, что в PocketInputAssemblerOwnerScenarioTest).
        AccountBalanceService accountBalanceService =
                new AccountBalanceService(accountRepository, checkpointRepository, eventRepository);
        service = new AnalyticsService(eventRepository, accountBalanceService);
        // По умолчанию — дефолтного счёта нет (Mockito отдаёt Optional.empty() на незастабленный
        // Optional-метод), т.е. "чекпоинта нет вовсе" — старое поведение по умолчанию.
    }

    private static BalanceCheckpoint checkpoint(Account account, LocalDate date, long amount) {
        return BalanceCheckpoint.builder()
                .id(UUID.randomUUID()).date(date).amount(BigDecimal.valueOf(amount)).account(account)
                .build();
    }

    private void anchorDefaultAccountAt(Account defaultAccount, LocalDate date, long amount, LocalDate asOfDate) {
        when(accountRepository.findByDefaultAccountTrueAndDeletedFalse()).thenReturn(Optional.of(defaultAccount));
        when(checkpointRepository.findLatestForAccountAt(defaultAccount.getId(), asOfDate))
                .thenReturn(Optional.of(checkpoint(defaultAccount, date, amount)));
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
        when(eventRepository.findAllByDeletedFalseAndDateBetween(any(), any())).thenReturn(List.of());

        AnalyticsReportDto report = service.getReport(LocalDate.of(2026, 4, 9));

        // No checkpoint → startBalance = 0, first cash flow day has runningBalance = 0
        assertThat(report.cashFlow()).isNotNull();
        assertThat(report.cashFlow().get(0).runningBalance()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void getReport_checkpointInCurrentMonth_nobridge() {
        LocalDate asOfDate = LocalDate.of(2026, 4, 9);
        LocalDate checkpointDate = LocalDate.of(2026, 4, 5);
        Account defaultAccount = AccountFixtures.defaultAccount();
        anchorDefaultAccountAt(defaultAccount, checkpointDate, 50000, asOfDate);
        // Stub the main-month query explicitly so the verify below is unambiguous
        when(eventRepository.findAllByDeletedFalseAndDateBetween(
                eq(LocalDate.of(2026, 4, 1)), eq(LocalDate.of(2026, 4, 30)))).thenReturn(List.of());

        AnalyticsReportDto report = service.getReport(asOfDate);

        // Checkpoint in same month → startBalance = checkpoint amount, no bridge call
        assertThat(report.cashFlow()).isNotNull();
        assertThat(report.cashFlow().get(0).runningBalance()).isEqualByComparingTo(BigDecimal.valueOf(50000));
        // Verify bridge NOT called for previous month
        verify(eventRepository, never()).findAllByDeletedFalseAndDateBetween(
                eq(checkpointDate), eq(LocalDate.of(2026, 3, 31)));
    }

    @Test
    void getReport_checkpointInPreviousMonth_bridgeEventsApplied() {
        LocalDate asOfDate = LocalDate.of(2026, 4, 9);
        LocalDate checkpointDate = LocalDate.of(2026, 3, 20);
        Account defaultAccount = AccountFixtures.defaultAccount();
        anchorDefaultAccountAt(defaultAccount, checkpointDate, 30000, asOfDate);
        when(eventRepository.findAllByDeletedFalseAndDateBetween(any(), any())).thenReturn(List.of());

        service.getReport(asOfDate);

        // Bridge called for [checkpointDate, March 31]
        verify(eventRepository).findAllByDeletedFalseAndDateBetween(
                eq(checkpointDate), eq(LocalDate.of(2026, 3, 31)));
    }

    @Test
    void getReport_checkpointTwoMonthsAgo_bridgeEventsApplied() {
        LocalDate asOfDate = LocalDate.of(2026, 4, 9);
        LocalDate checkpointDate = LocalDate.of(2026, 2, 15);
        Account defaultAccount = AccountFixtures.defaultAccount();
        anchorDefaultAccountAt(defaultAccount, checkpointDate, 20000, asOfDate);
        when(eventRepository.findAllByDeletedFalseAndDateBetween(any(), any())).thenReturn(List.of());

        service.getReport(asOfDate);

        // Bridge called for [checkpointDate, March 31] (day before April 1)
        verify(eventRepository).findAllByDeletedFalseAndDateBetween(
                eq(checkpointDate), eq(LocalDate.of(2026, 3, 31)));
    }

    @Test
    @DisplayName("ANO-9 Task 2.2а: вклад, переякоренный ПОЗЖЕ основной карты, не становится "
            + "стартовым балансом месяца (было: побеждал глобально самый свежий чекпоинт "
            + "checkpointRepository.findTopByOrderByDateDesc(), слепой к счёту)")
    void getReport_depositWithFresherCheckpoint_doesNotBecomeStartBalance() {
        LocalDate asOfDate = LocalDate.of(2026, 3, 15);
        LocalDate monthStart = asOfDate.withDayOfMonth(1); // 2026-03-01

        Account defaultAccount = AccountFixtures.defaultAccount();
        Account deposit = AccountFixtures.account(AccountKind.DEPOSIT, true).build();

        // Дефолтная карта: чекпоинт РОВНО на monthStart — сумма чекпоинта возвращается как есть,
        // без моста (cp.date не before monthStart), детерминированно без доп. стабов на bridge.
        when(accountRepository.findByDefaultAccountTrueAndDeletedFalse()).thenReturn(Optional.of(defaultAccount));
        when(checkpointRepository.findLatestForAccountAt(defaultAccount.getId(), asOfDate))
                .thenReturn(Optional.of(checkpoint(defaultAccount, monthStart, 50_000)));
        // Вклад переякорен ПОЗЖЕ карты (12 марта > 1 марта) — старый слепой к счёту запрос
        // выбрал бы именно его как "глобально самый свежий чекпоинт".
        when(checkpointRepository.findLatestForAccountAt(deposit.getId(), asOfDate))
                .thenReturn(Optional.of(checkpoint(deposit, LocalDate.of(2026, 3, 12), 300_000)));
        // ВАЖНО (ревью): calcStartBalance сейчас спрашивает только defaultAccount() и не обходит
        // список счетов, поэтому без этого стаба фикстура вклада выше — мёртвый груз: тест
        // остаётся зелёным даже если её убрать целиком. Стаб даёт правдоподобным мутациям
        // (регрессия к «обойти все счета, взять глобально свежий чекпоинт») реальные данные,
        // чтобы под мутацией тест падал именно на 300 000 (вклад), а не на обезличенный 0.
        when(accountRepository.findAllByDeletedFalseOrderBySortOrderAscNameAsc())
                .thenReturn(List.of(defaultAccount, deposit));
        when(eventRepository.findAllByDeletedFalseAndDateBetween(any(), any())).thenReturn(List.of());

        AnalyticsReportDto report = service.getReport(asOfDate);

        // 50 000 (якорь ДЕФОЛТНОЙ карты), а не 300 000 (остаток вклада).
        assertThat(report.cashFlow().get(0).runningBalance()).isEqualByComparingTo(BigDecimal.valueOf(50_000));
    }

    // ─── buildPriorityBreakdown ───────────────────────────────────────────────

    @Test
    void buildPriorityBreakdown_aggregatesByPriorityAndKind() {
        FinancialEvent highPlan = makeEvent(EventKind.PLAN, Priority.HIGH, CategoryType.EXPENSE,
                BigDecimal.valueOf(10000), null);
        FinancialEvent highFact = makeEvent(EventKind.FACT, Priority.HIGH, CategoryType.EXPENSE,
                null, BigDecimal.valueOf(9000));
        FinancialEvent medPlan = makeEvent(EventKind.PLAN, Priority.MEDIUM, CategoryType.EXPENSE,
                BigDecimal.valueOf(5000), null);
        FinancialEvent incFact = makeEvent(EventKind.FACT, Priority.MEDIUM, CategoryType.INCOME,
                null, BigDecimal.valueOf(80000));

        when(eventRepository.findAllByDeletedFalseAndDateBetween(any(), any()))
                .thenReturn(List.of(highPlan, highFact, medPlan, incFact));

        AnalyticsReportDto report = service.getReport(LocalDate.of(2026, 4, 9));

        AnalyticsReportDto.PriorityBreakdown b = report.priorityBreakdown();
        assertThat(b.highPlanned()).isEqualByComparingTo(BigDecimal.valueOf(10000));
        assertThat(b.highFact()).isEqualByComparingTo(BigDecimal.valueOf(9000));
        assertThat(b.mediumPlanned()).isEqualByComparingTo(BigDecimal.valueOf(5000));
        assertThat(b.mediumFact()).isEqualByComparingTo(BigDecimal.ZERO); // income fact must not pollute expense buckets
        assertThat(b.totalIncomeFact()).isEqualByComparingTo(BigDecimal.valueOf(80000));
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private FinancialEvent makeEvent(EventKind kind, Priority priority, CategoryType catType,
            BigDecimal planned, BigDecimal fact) {
        EventType evtType = catType == CategoryType.INCOME ? EventType.INCOME : EventType.EXPENSE;
        Category cat = Category.builder()
                .id(UUID.randomUUID())
                .name("Test")
                .type(catType)
                .build();
        return FinancialEvent.builder()
                .id(UUID.randomUUID())
                .date(LocalDate.of(2026, 4, 5))
                .category(cat)
                .type(evtType)
                .eventKind(kind)
                .plannedAmount(planned)
                .factAmount(fact)
                .status(fact != null ? EventStatus.EXECUTED : EventStatus.PLANNED)
                .priority(priority)
                .deleted(false)
                .build();
    }

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
