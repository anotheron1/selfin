# UX Fixes 2 — Design Spec

**Date:** 2026-03-16
**Scope:** Three independent improvements: scrollbar style, alphabetical category sorting, and pocket balance correctness.
**Stack:** Spring Boot / Java 21 backend + React 18 / TypeScript / Tailwind CSS frontend.
**No API contract changes** — all three fixes are internal (new repository methods + CSS class + backend sort).

---

## 1. Scrollbar Style Fix

**Problem:** `Dashboard.tsx` uses a plain `<div>` with `overflow-y-auto` (required to prevent the Radix ScrollArea `display:table` width-expansion bug). The native browser scrollbar style differs from the Radix ScrollArea overlay scrollbar used on all other pages.

**Solution:** Hide the native scrollbar on the outer page `<div>` so it behaves identically to Radix ScrollArea pages (scroll works via touch/trackpad, no visible scrollbar track).

The horizontal scrollbar inside `CashFlowSection` (the calendar strip) is **intentionally kept**. It uses `style={{ scrollbarWidth: 'thin' }}` to give users a visual affordance that the strip is horizontally scrollable. This is a narrow inner element, not the full-page scroll, and the thin style is appropriate there.

### Changes

**`frontend/src/index.css`** — add utility class:
```css
/* Hides scrollbar on WebKit (Chrome, Safari) and Firefox/Edge */
.scrollbar-none::-webkit-scrollbar { display: none; }
.scrollbar-none { scrollbar-width: none; -ms-overflow-style: none; }
```

**`frontend/src/pages/Dashboard.tsx`** — outer `<div>` at line 68:
- Remove `scrollbarWidth: 'thin'` and `scrollbarColor: ...` from the inline `style` prop (keep `height: ...` style).
- Add `scrollbar-none` to `className`.

### Verification
- Test on Chrome/Safari (WebKit): no scrollbar track visible on page scroll.
- Test on Firefox: no scrollbar track visible on page scroll.
- CashFlowSection horizontal strip: thin scrollbar still visible — confirm intentional.

---

## 2. Alphabetical Category Sorting

**Problem:** Categories appear in creation order in the FAB form, Dashboard progress bars, and Analytics plan/fact table.

**Solution:** Sort alphabetically using `Collator.getInstance(new Locale("ru", "RU"))` in Java after fetching from the database. This guarantees correct Russian alphabetical order independent of JVM default locale and PostgreSQL `LC_COLLATE` settings (which default to `en_US.utf8` in Docker).

Do **not** rely on `ORDER BY name ASC` in the DB or `String.toLowerCase()` comparisons — both are unreliable for Cyrillic under non-Russian locale settings.

### Changes

**`CategoryService.findAll()`** (covers FAB form):
```java
// Existing call:
List<Category> cats = categoryRepository.findAllByDeletedFalse();
// Add sort before mapping to DTOs:
Collator collator = Collator.getInstance(new Locale("ru", "RU"));
cats.sort((a, b) -> collator.compare(a.getName(), b.getName()));
```
The repository method `findAllByDeletedFalse()` is unchanged.

**`DashboardService.buildProgressBars()`** (covers Dashboard progress bars):
```java
Collator collator = Collator.getInstance(new Locale("ru", "RU"));
bars.sort((a, b) -> collator.compare(a.categoryName(), b.categoryName()));
// then return bars
```

**`AnalyticsService` plan-fact builder** (covers Analytics plan/fact table rows):
```java
Collator collator = Collator.getInstance(new Locale("ru", "RU"));
categories.sort((a, b) -> collator.compare(a.categoryName(), b.categoryName()));
// then return
```
Note: the plan-fact aggregation lives in `AnalyticsService`, **not** `FinancialEventService`.

### Edge cases
- Empty or single-element list: `List.sort` is a no-op; safe.
- `Collator` is not thread-safe but is created as a local variable in each method call; no concurrency issue.

---

## 3. Pocket Balance Correctness

**Problem:** `TargetFundService.calcPocketBalance` uses `sumEffectiveByType[FromDate]`, which includes both `EXECUTED` (using `factAmount`) and `PLANNED` (using `plannedAmount`) events. After the user added a year's worth of planned salary events, the pocket showed ~600 000 ₽ while the Dashboard correctly showed ~44 000 ₽ (executed-only).

**Root cause query:**
```sql
SELECT COALESCE(SUM(CASE WHEN factAmount IS NOT NULL THEN factAmount ELSE plannedAmount END), 0)
FROM FinancialEvent WHERE type = :type AND deleted = false
-- ↑ includes ALL statuses — inflates pocket with future planned income/expense
```

**Solution:** Add two new repository methods that sum only `EXECUTED` events with a non-null `factAmount`. Replace their use in `calcPocketBalance`. The old `sumEffectiveByType[FromDate]` methods are left untouched.

### Changes

**`FinancialEventRepository`** — add two methods using fully qualified enum reference in JPQL (JPA spec–compliant, avoids string literal comparison):

```java
@Query("SELECT COALESCE(SUM(e.factAmount), 0) FROM FinancialEvent e " +
       "WHERE e.type = :type " +
       "AND e.status = ru.selfin.backend.model.enums.EventStatus.EXECUTED " +
       "AND e.factAmount IS NOT NULL AND e.deleted = false")
BigDecimal sumFactExecutedByType(@Param("type") EventType type);

@Query("SELECT COALESCE(SUM(e.factAmount), 0) FROM FinancialEvent e " +
       "WHERE e.type = :type " +
       "AND e.status = ru.selfin.backend.model.enums.EventStatus.EXECUTED " +
       "AND e.factAmount IS NOT NULL AND e.deleted = false AND e.date >= :fromDate")
BigDecimal sumFactExecutedByTypeFromDate(@Param("type") EventType type,
                                         @Param("fromDate") LocalDate fromDate);
```

**`TargetFundService.calcPocketBalance`** — swap calls:

```java
// With checkpoint (before → after):
income = eventRepository.sumEffectiveByTypeFromDate(EventType.INCOME, fromDate);
expense = eventRepository.sumEffectiveByTypeFromDate(EventType.EXPENSE, fromDate);
→
income = eventRepository.sumFactExecutedByTypeFromDate(EventType.INCOME, fromDate);
expense = eventRepository.sumFactExecutedByTypeFromDate(EventType.EXPENSE, fromDate);

// No checkpoint (before → after):
income = eventRepository.sumEffectiveByType(EventType.INCOME);
expense = eventRepository.sumEffectiveByType(EventType.EXPENSE);
→
income = eventRepository.sumFactExecutedByType(EventType.INCOME);
expense = eventRepository.sumFactExecutedByType(EventType.EXPENSE);
```

### Post-fix behaviour
- `pocketBalance` = `checkpoint.amount + Σ(executed income factAmounts from checkpoint date) − Σ(executed expense factAmounts from checkpoint date) − Σ(fund balances)`.
- When fund balances are zero, `pocketBalance` equals `Dashboard.currentBalance` (both use the same executed-fact-only logic and the same checkpoint mechanism).
- Frontend projections in `Funds.tsx` start from the corrected `pocketBalance` and add PLANNED events at each horizon via `fetchEvents`; they will show correct values automatically.

### Edge cases
- No checkpoint: `sumFactExecutedByType` sums all executed facts regardless of date — correct for a fresh database (returns 0).
- EXECUTED events with `factAmount = null`: excluded — consistent with Dashboard's `filter(e -> e.getFactAmount() != null)`.
- `FUND_TRANSFER` type: excluded from both queries (queried by `INCOME`/`EXPENSE` only).

---

## File Summary

| File | Change |
|------|--------|
| `frontend/src/index.css` | Add `.scrollbar-none` CSS class |
| `frontend/src/pages/Dashboard.tsx` | Replace inline scrollbar styles with `scrollbar-none` class |
| `backend/.../repository/FinancialEventRepository.java` | Add `sumFactExecutedByType` and `sumFactExecutedByTypeFromDate` |
| `backend/.../service/TargetFundService.java` | Use new executed-only methods in `calcPocketBalance` |
| `backend/.../service/CategoryService.java` | Sort fetched categories with `Collator(ru_RU)` before DTO mapping |
| `backend/.../service/DashboardService.java` | Sort `progressBars` with `Collator(ru_RU)` |
| `backend/.../service/AnalyticsService.java` | Sort `CategoryPlanFact` rows with `Collator(ru_RU)` |

---

## Testing Notes
- After backend change: `GET /api/v1/funds` should return `pocketBalance ≈ Dashboard currentBalance − sum(fund.currentBalance)`.
- Verify all four pocket projections (end of month, 3m, 6m, end of plans) reflect realistic forward-looking values.
- Verify FAB category dropdown is alphabetical (Cyrillic order: А, Б, В …).
- Verify Dashboard progress bars are alphabetical.
- Verify Analytics plan/fact table rows are alphabetical.
- Scrollbar: test on Chrome (WebKit) and Firefox — no visible scrollbar on page scroll; CashFlowSection horizontal strip retains thin scrollbar.
