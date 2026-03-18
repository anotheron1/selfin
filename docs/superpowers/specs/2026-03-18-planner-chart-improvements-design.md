# Spec: Planner Chart Improvements

**Date:** 2026-03-18
**Status:** Approved

## Overview

Three targeted improvements to the savings planner section on the Цели page:
1. Chart first month filtered to today onwards (not full month from day 1)
2. Summary bar shows avg + min across the active planning horizon (non-zero months only)
3. Tooltip for the planner section explaining all three metrics

---

## 1. Chart: First Month Starts From Today

### Problem
`FundPlannerService` currently aggregates ALL planned events in the current month regardless of date. If today is March 18, events from March 1–17 are included in the first bar, making it misleading — those are past events that may or may not have been executed.

### Solution
**Backend change in `FundPlannerService`**: for `i == 0` (current month), add a date filter so only events with `date >= LocalDate.now()` are included in the aggregation.

```java
// All events in the current month (used for factExpenses)
List<FinancialEvent> allMonthEvents = events.stream()
    .filter(e -> e.getDate() != null && YearMonth.from(e.getDate()).equals(month))
    .toList();

// For i == 0: only future events (today onwards) are used for planned aggregates
List<FinancialEvent> monthEvents = (i == 0)
    ? allMonthEvents.stream()
        .filter(e -> !e.getDate().isBefore(LocalDate.now()))
        .toList()
    : allMonthEvents;
```

`factExpenses` (only computed for `i == 0`) must use `allMonthEvents`, not `monthEvents`, so that already-executed expenses from earlier in the month are included in the chart dot. All other months (i > 0) are unaffected.

**No frontend changes needed** — the frontend already consumes the aggregated month DTO.

---

## 2. Summary Bar: Average + Minimum Over Active Horizon

### Problem
Current calculations use only the first 3 months. This is misleading when the planning horizon is longer (e.g., through end of 2026 = 10 months). Additionally, only a single average is shown with no indication of worst-case months.

### Solution
**Frontend change in `SavingsStrategySection.tsx`**: extend the calculation window to all months where `plannedIncome > 0` (active horizon), and add a minimum alongside the average.

**New metrics:**

| Label | Formula |
|---|---|
| Плановый доход | avg(plannedIncome) over active months — average only, no min |
| После обяз. расходов | avg(income − mandatory) + min(income − mandatory) with month label |
| Доступно для копилок | avg(income − allExpenses) + min(income − allExpenses) with month label |

**Active horizon filter:** `months.filter(m => m.plannedIncome > 0)`

**Display format:** `~45 000 ₽/мес (min 12 000 ₽ в авг 26)`

If min equals avg, show only the average (omit the parenthetical).

**Month label format:** reuse existing `fmtYearMonth(m.yearMonth)` → `"авг 26"`.

---

## 3. Tooltip for Planner Section

### Solution
Add a `HelpCircle` icon button next to the "Планировщик копилок" header. On click, toggle an explanatory block (same pattern as the pocket tooltip in `Funds.tsx`).

**Tooltip content:**

> **Плановый доход** — среднее плановых поступлений по месяцам в горизонте планирования (только месяцы с ненулевым доходом).
>
> **После обяз. расходов** — сколько остаётся в типичный месяц, если вычесть только HIGH-priority расходы (ипотека, коммуналка и т.д.). Это теоретический максимум, который можно направить в копилки. В скобках — худший месяц за горизонт.
>
> **Доступно для копилок** — сколько остаётся после всех запланированных расходов. Именно на эту сумму ориентируются слайдеры распределения. В скобках — худший месяц за горизонт.

**State:** `showPlannerHelp: boolean` — local state in `SavingsStrategySection`. The tooltip is rendered inside `{isOpen && ...}`, so it resets automatically when the section is collapsed. This is intentional — the tooltip is transient help, not persistent UI state.

---

## Affected Files

| File | Change |
|---|---|
| `backend/.../service/FundPlannerService.java` | Filter first month to `date >= today` |
| `frontend/.../funds/SavingsStrategySection.tsx` | New avg/min calc + tooltip toggle; add `HelpCircle` import from `lucide-react` |
| `frontend/.../funds/savingsStrategyUtils.ts` | No changes needed |

---

## Out of Scope

- Changing the chart series or visual layout
- Backend changes to avg/min (frontend has all 36 months, computes locally)
- Changing the pocket tooltip on the main Funds page
