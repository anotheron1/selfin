# Cash Calendar 14-Day Horizon Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the cash flow calendar on Dashboard to always show 14 days ahead from today, crossing month boundaries when needed.

**Architecture:** Single change in `AnalyticsService.getReport()` — compute `calendarEnd = max(monthEnd, today + 14)`, conditionally fetch extra events for days beyond month end, merge them into the list passed to `buildCashFlow`. Month-scoped methods (`buildPlanFact`, `buildMandatoryBurnRate`, `buildIncomeGap`) continue using the original month-only event list. Frontend unchanged.

**Tech Stack:** Java 21, Spring Boot, JUnit 5, Mockito

---

## Chunk 1: Implementation

### Task 1: Add failing test for cross-month cash flow horizon

**Files:**
- Modify: `backend/src/test/java/ru/selfin/backend/service/AnalyticsServiceTest.java`

- [ ] **Step 1: Write failing test — cash flow extends beyond month end**

Add a test where `TODAY` is March 28 (3 days to month end), verifying that cashFlow includes days from April (up to April 11 = today + 14). Also verify that `planFact` does NOT include April events.

```java
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
    assertThat(pfCategories).doesNotContain("Зарплата");
}
```

Also add the `incomeOn` helper:

```java
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
            .plannedAmount(planned)
            .factAmount(fact)
            .status(fact != null ? EventStatus.EXECUTED : EventStatus.PLANNED)
            .priority(Priority.MEDIUM)
            .deleted(false)
            .build();
}
```

- [ ] **Step 2: Run tests to verify the new test fails**

Run: `JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -pl backend -Dtest=AnalyticsServiceTest#cashFlow_extendsBeyondMonthEnd -Dsurefire.useFile=false -q`

Expected: FAIL — the second `findAllByDeletedFalseAndDateBetween` call is never made, April events missing from cashFlow.

### Task 2: Implement 14-day horizon in AnalyticsService

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/service/AnalyticsService.java:43-69,71-90`

- [ ] **Step 3: Add constant and modify `getReport()`**

Add constant after line 46:

```java
/** Горизонт кассового календаря: количество дней вперёд от текущей даты. */
private static final int CASH_FLOW_HORIZON_DAYS = 14;
```

Replace `getReport()` method (lines 48-69) with:

```java
/**
 * Формирует полный аналитический отчёт за месяц, в котором находится {@code asOfDate}.
 * Прошлые дни используют фактические суммы (при наличии), будущие — плановые.
 * <p>
 * Кассовый календарь расширяется на {@value #CASH_FLOW_HORIZON_DAYS} дней вперёд
 * от {@code asOfDate}, при необходимости захватывая события следующего месяца.
 * Остальные разделы отчёта (план-факт, burn rate, income gap) остаются в рамках месяца.
 *
 * @param asOfDate опорная дата расчёта (обычно сегодня)
 * @return агрегированный {@link AnalyticsReportDto}
 */
@Transactional(readOnly = true)
public AnalyticsReportDto getReport(LocalDate asOfDate) {
    LocalDate monthStart = asOfDate.withDayOfMonth(1);
    LocalDate monthEnd = asOfDate.withDayOfMonth(asOfDate.lengthOfMonth());
    LocalDate calendarEnd = asOfDate.plusDays(CASH_FLOW_HORIZON_DAYS);
    if (calendarEnd.isBefore(monthEnd)) {
        calendarEnd = monthEnd;
    }

    List<FinancialEvent> monthEvents = eventRepository.findAllByDeletedFalseAndDateBetween(monthStart, monthEnd);

    // Для кассового календаря: если горизонт выходит за пределы месяца,
    // подгружаем события следующего месяца и объединяем
    List<FinancialEvent> cashFlowEvents;
    if (calendarEnd.isAfter(monthEnd)) {
        List<FinancialEvent> extraEvents = eventRepository
                .findAllByDeletedFalseAndDateBetween(monthEnd.plusDays(1), calendarEnd);
        cashFlowEvents = new ArrayList<>(monthEvents);
        cashFlowEvents.addAll(extraEvents);
    } else {
        cashFlowEvents = monthEvents;
    }

    BigDecimal initialBalance = calcStartBalance(monthStart);

    return new AnalyticsReportDto(
            buildCashFlow(cashFlowEvents, monthStart, calendarEnd, asOfDate, initialBalance),
            buildPlanFact(monthEvents),
            buildMandatoryBurnRate(monthEvents, monthStart, monthEnd),
            buildIncomeGap(monthEvents));
}
```

Note: need to add `import java.util.ArrayList;` — but it's already covered by `import java.util.*;` on line 26.

- [ ] **Step 4: Rename `monthEnd` parameter to `endDate` in `buildCashFlow`**

Rename the parameter in the method signature (line 92) and in the loop (line 101):

Line 92: `LocalDate monthEnd,` → `LocalDate endDate,`
Line 101: `for (LocalDate d = monthStart; !d.isAfter(monthEnd); d = d.plusDays(1)) {` → `for (LocalDate d = monthStart; !d.isAfter(endDate); d = d.plusDays(1)) {`

- [ ] **Step 5: Update `buildCashFlow` javadoc**

Replace the javadoc of `buildCashFlow` (lines 71-90) to reflect the new semantics:

```java
/**
 * Строит кассовый календарь — список дней с нарастающим балансом.
 * <p>
 * Нарастающий баланс стартует от {@code initialBalance} — реального остатка на счёте
 * на начало месяца, рассчитанного через {@link #calcStartBalance(LocalDate)}.
 * <p>
 * Для каждого дня вычисляется {@code dailyIncome} и {@code dailyExpense}:
 * прошлые дни используют {@code factAmount} (при отсутствии — {@code plannedAmount}),
 * будущие — только {@code plannedAmount}.
 * Дни без событий скрываются, за исключением сегодняшнего дня.
 * <p>
 * Диапазон дат может выходить за пределы текущего месяца —
 * параметр {@code endDate} задаётся горизонтом кассового календаря
 * ({@value #CASH_FLOW_HORIZON_DAYS} дней вперёд от текущей даты).
 *
 * @param events         события за весь диапазон {@code [monthStart, endDate]} (без удалённых)
 * @param monthStart     первый день месяца (начало нарастающего баланса)
 * @param endDate        последний день календаря (может быть за пределами месяца)
 * @param today          сегодняшняя дата (граница прошлое / будущее)
 * @param initialBalance начальный баланс на начало месяца из чекпоинта
 * @return упорядоченный список {@link CashFlowDay} от {@code monthStart} до {@code endDate}
 */
```

- [ ] **Step 6: Update AnalyticsReportDto javadoc**

In `backend/src/main/java/ru/selfin/backend/dto/AnalyticsReportDto.java`, line 12, change:

Old: `@param cashFlow      нарастающий баланс по дням месяца`
New: `@param cashFlow      нарастающий баланс по дням (от начала месяца до горизонта кассового календаря)`

- [ ] **Step 7: Run tests to verify the new test passes**

Run: `JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -pl backend -Dtest=AnalyticsServiceTest -Dsurefire.useFile=false -q`

Expected: ALL PASS — including the new `cashFlow_extendsBeyondMonthEnd` and existing `planFact_categoriesSortedAlphabetically`.

### Task 3: Add test for mid-month (no second query needed)

**Files:**
- Modify: `backend/src/test/java/ru/selfin/backend/service/AnalyticsServiceTest.java`

- [ ] **Step 8: Write test — mid-month stays within month boundaries**

Verify that when `today + 14 < monthEnd`, no second query is made and behavior is unchanged.

```java
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
```

- [ ] **Step 9: Run all tests**

Run: `JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -pl backend -Dtest=AnalyticsServiceTest -Dsurefire.useFile=false -q`

Expected: ALL PASS.

### Task 4: Commit

- [ ] **Step 10: Commit all changes**

```bash
git add backend/src/main/java/ru/selfin/backend/service/AnalyticsService.java \
      backend/src/main/java/ru/selfin/backend/dto/AnalyticsReportDto.java \
      backend/src/test/java/ru/selfin/backend/service/AnalyticsServiceTest.java
git commit -m "feat: extend cash calendar to 14 days ahead, crossing month boundary"
```
