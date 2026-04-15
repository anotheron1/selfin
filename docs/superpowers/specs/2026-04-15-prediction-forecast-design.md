# Prediction Forecast — Design Spec

**Date:** 2026-04-15  
**Status:** Approved

## Goal

Add end-of-month spend predictions per category to the Dashboard, with a prediction-adjusted pocket balance in the Funds popup. The feature answers: "If I continue at this pace, will I stay within budget?"

## Scope

In scope: Category model, new PredictionService, Dashboard progress bars, Funds popup.  
Out of scope: Budget page, Analytics page, recurring events, any other page.

---

## Section 1: Data Model

### New Flyway migration

```sql
ALTER TABLE categories ADD COLUMN forecast_enabled BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE categories SET forecast_enabled = TRUE
WHERE type = 'EXPENSE' AND is_deleted = FALSE
  AND name IN ('Еда / Продукты','Кафе / Рестораны','Транспорт',
               'Одежда','Развлечения','Подписки','Прочее');
```

Variable expense categories enabled by default. Mandatory categories (аренда, кредит, etc.) disabled — they are single events with no "pace" to extrapolate.

### Category entity

```java
@Column(name = "forecast_enabled")
private boolean forecastEnabled;
```

### CategoryDto / CategoryUpdateDto

Add `forecastEnabled: boolean`. The existing category edit screen gets a toggle "Отслеживать прогноз" — no new screen needed.

---

## Section 2: Backend — PredictionService

**Package:** `ru.selfin.backend.service.PredictionService`

### Core algorithm (hybrid model)

For each `forecast_enabled` category:

```
if category has PLAN events in the month:
    projection = currentFact + sum(plannedAmount of PLAN events with date > today)  // Hybrid B

else:
    dailyRate = currentFact / daysElapsed
    projection = currentFact + dailyRate × (daysInMonth − daysElapsed)             // Linear A
```

For categories where `forecast_enabled = false`: no prediction computed, progress bar rendered as-is.

### Forecast history (for sparkline — no new table)

Reconstructed from existing `financial_events` data:

```
for each day d from 1 to today:
    factOnDayD  = sum(factAmount) of FACT events with date ≤ d in this category
    plansAfterD = sum(plannedAmount) of PLAN events with date > d in this category
    projOnDayD  = factOnDayD + plansAfterD   // linear if no plans
    → point (d, factOnDayD, projOnDayD)
```

No new storage needed. Computed on demand per API call.

### Public interface

```java
// Single category forecast
CategoryForecast forecast(String categoryName, List<FinancialEvent> monthEvents, LocalDate today);

// All forecast_enabled categories for a month
MonthlyForecast forecastMonth(YearMonth month);

// Daily history for sparkline
List<DailyForecastPoint> forecastHistory(String categoryName, List<FinancialEvent> monthEvents, LocalDate today);
```

### DTOs

```java
record CategoryForecast(
    String categoryName,
    BigDecimal currentFact,
    BigDecimal plannedLimit,
    BigDecimal projectionAmount,       // end-of-month projection
    List<DailyForecastPoint> history
)

record DailyForecastPoint(
    int day,
    BigDecimal cumulativeFact,
    BigDecimal projectedTotal          // end-of-month forecast as of this day
)

record MonthlyForecast(
    List<CategoryForecast> categories,
    BigDecimal netPredictionDelta      // sum of (projection − plan) across all categories
)
```

### Callers

Both services call `PredictionService` — this makes the кармашек ↔ prediction link explicit in code:

- `DashboardService.buildProgressBars()` → calls `forecastMonth()` for per-category data + history
- `TargetFundService.calcPocketBalance()` → calls `forecastMonth().netPredictionDelta` for `predictionAdjustedPocket`

```java
// In TargetFundService:
// predictionAdjustedPocket = afterAllExpenses − netPredictionDelta
//
// For plan-based categories delta = 0: "после всех расходов" already reflects actual
// spending pace because pocketBalance tracks executed facts, not planned amounts.
// Delta > 0 only for forecast_enabled categories without planned events
// (linear extrapolation adds spending that кармашек does not see).
```

### New endpoint

```
GET /api/v1/analytics/forecast?month=YYYY-MM
```

Returns `MonthlyForecast`. Dashboard and Funds pages call it independently on load.

---

## Section 3: Dashboard UI — Progress Bars

### CategoryProgressBar DTO additions

```java
BigDecimal projectionAmount;        // null if forecastEnabled = false
boolean forecastEnabled;
List<DailyForecastPoint> history;   // empty if forecastEnabled = false
```

### Bar scaling

```
if projection ≤ plannedLimit:
    barMax = plannedLimit

else:
    barMax = max(plannedLimit × 1.25, projection × 1.1)
```

Plan marker sits at `plannedLimit / barMax × 100%`. For large overruns the plan marker drifts left — the scale of the problem is immediately visible.

### Amounts row (C2 format)

```
Еда / Продукты     30к / 40к / ~60к
```

Third value (`~projection`) shown only when `forecastEnabled = true`. Color: red if projection > plan, green if ≤ plan.

### Needle

Thin vertical line at `projectionAmount / barMax × 100%`. Red if overspend, green if within plan. No badge.

### Hover → sparkline tooltip (180×65 px SVG)

Three lines:
1. **Solid blue** — cumulative fact per day (days 1 → today)
2. **Dashed orange** — end-of-month projection recorded each day (history of forecast — shows how prediction evolved)
3. **Dashed red** — current projection forward (today → day 30)

Plus: dashed white horizontal at `plannedLimit`.

Points are dense (day spacing ~5.8 px) — readable as continuous lines.

### Non-forecast categories

Bars render unchanged. No needle, no sparkline, no third number. Status badge as today.

---

## Section 4: Кармашек — Funds Popup

### FundsOverview additions

```java
BigDecimal predictionAdjustedPocket;     // null when equal to afterAllExpenses
List<String> forecastContributors;       // ["Прочее (+4к)", "Транспорт (+3.5к)"]
```

Backend returns `null` for `predictionAdjustedPocket` when delta = 0 (all categories have plans). Frontend renders nothing in that case.

### Popup UI

After the "После всех расходов" row, conditionally:

```
┌────────────────────────────────────────┐
│  По текущему темпу          −19 500 ₽  │
│  Прочее (+4к), Транспорт (+3.5к)       │
└────────────────────────────────────────┘
```

- Highlighted box (slightly different background from the rows above)
- Amount color: red if worse than "после всех", green if better
- Contributors listed as small text underneath the amount
- Block hidden entirely when `predictionAdjustedPocket === null`

---

## Architecture Summary

```
Category.forecastEnabled
         ↓
 PredictionService  (hybrid B + linear A)
     ↙                    ↘
DashboardService      TargetFundService
(progress bars         (predictionAdjustedPocket)
 + sparkline history)
     ↓                         ↓
Dashboard.tsx             Funds.tsx popup
(needle + ~projection     ("По текущему темпу"
 + hover chart)            only when delta ≠ 0)
```

## Key Constraints

- No new database tables — forecast history reconstructed from existing events
- `forecast_enabled` flag is user-controlled per category — no hardcoding
- Prediction-adjusted кармашек displayed only when it meaningfully differs from plan
- Mandatory expense categories (HIGH priority) excluded from forecasting by default
