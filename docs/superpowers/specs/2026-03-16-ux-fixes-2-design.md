# UX Fixes 2 — Design Spec

**Date:** 2026-03-16
**Scope:** Three independent improvements: scrollbar style, alphabetical category sorting, and pocket balance correctness.
**Stack:** Spring Boot / Java 21 backend + React 18 / TypeScript / Tailwind CSS frontend.
**No API contract changes** — all three fixes are internal (new repository methods + CSS class + backend sort).

---

## 1. Scrollbar Style Fix

**Problem:** `Dashboard.tsx` uses a plain `<div>` with `overflow-y-auto` (required to prevent the Radix ScrollArea `display:table` width-expansion bug). The native browser scrollbar style differs from the Radix ScrollArea overlay scrollbar used on all other pages.

**Solution:** Hide the native scrollbar on Dashboard so it behaves identically to Radix ScrollArea pages (scroll works via touch/trackpad, no visible scrollbar track).

### Changes

**`frontend/src/index.css`** — add one utility class:
```css
.scrollbar-none::-webkit-scrollbar { display: none; }
.scrollbar-none { scrollbar-width: none; -ms-overflow-style: none; }
```

**`frontend/src/pages/Dashboard.tsx`** — on the outer `<div>`:
- Remove `scrollbarWidth: 'thin'` and `scrollbarColor: ...` from the inline `style` prop.
- Add `scrollbar-none` to `className`.

### Edge cases
- Touch devices: no visible scrollbar anyway; no behaviour change.
- Desktop: scrollbar disappears; content still scrollable via trackpad/mousewheel. Consistent with other pages.

---

## 2. Alphabetical Category Sorting

**Problem:** Categories appear in creation order in the FAB form, Dashboard progress bars, and Analytics plan/fact table.

**Solution:** Sort alphabetically (Russian locale) at the backend for all three surfaces.

### Changes

**`CategoryService` / `CategoryRepository`** (covers FAB form):
- Replace `findAllByDeletedFalse()` with `findAllByDeletedFalseOrderByNameAsc()`.
- Spring Data derives the query automatically; no `@Query` annotation needed.

**`DashboardService.buildProgressBars()`** (covers Dashboard progress bars):
- After building the list of `CategoryProgressBar`, sort before returning:
  ```java
  bars.sort(Comparator.comparing(b -> b.categoryName().toLowerCase()));
  ```
  (Case-insensitive; Cyrillic sorts correctly with JVM's default locale for lowercase comparison.)

**`FinancialEventService` / analytics plan-fact builder** (covers Analytics table):
- After aggregating `CategoryPlanFact` rows, sort before returning:
  ```java
  rows.sort(Comparator.comparing(r -> r.categoryName().toLowerCase()));
  ```

### Edge cases
- Empty category list: sorting a 0-element or 1-element list is a no-op; safe.
- Mixed case names: `.toLowerCase()` comparison normalises, matching UX expectation.

---

## 3. Pocket Balance Correctness

**Problem:** `TargetFundService.calcPocketBalance` uses `sumEffectiveByType[FromDate]`, which includes both `EXECUTED` (using `factAmount`) and `PLANNED` (using `plannedAmount`) events. After the user added a year's worth of planned salary events, the pocket showed ~600 000 ₽ while the Dashboard correctly showed ~44 000 ₽ (executed-only).

**Root cause query:**
```sql
SELECT COALESCE(SUM(CASE WHEN factAmount IS NOT NULL THEN factAmount ELSE plannedAmount END), 0)
FROM FinancialEvent WHERE type = :type AND deleted = false
-- ↑ includes ALL statuses, inflates pocket with all future planned income
```

**Solution:** Add two new repository methods that sum only `EXECUTED` events with a non-null `factAmount`. Replace their use in `calcPocketBalance`. The old `sumEffectiveByType[FromDate]` methods are left untouched (may be used in tests or future features).

### Changes

**`FinancialEventRepository`** — add two `@Query` methods:

```java
@Query("SELECT COALESCE(SUM(e.factAmount), 0) FROM FinancialEvent e " +
       "WHERE e.type = :type AND e.status = 'EXECUTED' " +
       "AND e.factAmount IS NOT NULL AND e.deleted = false")
BigDecimal sumFactExecutedByType(@Param("type") EventType type);

@Query("SELECT COALESCE(SUM(e.factAmount), 0) FROM FinancialEvent e " +
       "WHERE e.type = :type AND e.status = 'EXECUTED' " +
       "AND e.factAmount IS NOT NULL AND e.deleted = false AND e.date >= :fromDate")
BigDecimal sumFactExecutedByTypeFromDate(@Param("type") EventType type,
                                         @Param("fromDate") LocalDate fromDate);
```

**`TargetFundService.calcPocketBalance`** — swap query calls:

```java
// Before (with checkpoint):
income = eventRepository.sumEffectiveByTypeFromDate(EventType.INCOME, fromDate);
expense = eventRepository.sumEffectiveByTypeFromDate(EventType.EXPENSE, fromDate);

// After:
income = eventRepository.sumFactExecutedByTypeFromDate(EventType.INCOME, fromDate);
expense = eventRepository.sumFactExecutedByTypeFromDate(EventType.EXPENSE, fromDate);

// Before (no checkpoint):
income = eventRepository.sumEffectiveByType(EventType.INCOME);
expense = eventRepository.sumEffectiveByType(EventType.EXPENSE);

// After:
income = eventRepository.sumFactExecutedByType(EventType.INCOME);
expense = eventRepository.sumFactExecutedByType(EventType.EXPENSE);
```

### Post-fix behaviour
- `pocketBalance` = `checkpoint.amount + Σ(executed income facts from checkpoint) − Σ(executed expense facts from checkpoint) − Σ(fund balances)`.
- Equals `Dashboard.currentBalance − Σ(fund balances)` when fund balances are zero.
- Frontend projections in `Funds.tsx` start from the corrected base and add PLANNED events via `fetchEvents`; they will show correct horizon values automatically.

### Edge cases
- No checkpoint: falls back to `sumFactExecutedByType` (all time), which is `0` for a fresh DB — correct.
- EXECUTED events with `factAmount = null`: excluded (consistent with Dashboard behaviour).
- `FUND_TRANSFER` events: not `INCOME` or `EXPENSE`; not affected by this query.

---

## File Summary

| File | Change |
|------|--------|
| `frontend/src/index.css` | Add `.scrollbar-none` CSS class |
| `frontend/src/pages/Dashboard.tsx` | Replace inline scrollbar styles with `scrollbar-none` class |
| `backend/.../repository/FinancialEventRepository.java` | Add `sumFactExecutedByType` and `sumFactExecutedByTypeFromDate` |
| `backend/.../service/TargetFundService.java` | Use new executed-only methods in `calcPocketBalance` |
| `backend/.../service/CategoryService.java` (or repository) | `findAllByDeletedFalseOrderByNameAsc()` |
| `backend/.../service/DashboardService.java` | Sort `progressBars` by name |
| `backend/.../service/FinancialEventService.java` | Sort analytics `CategoryPlanFact` rows by name |

---

## Testing Notes
- After backend change: `GET /api/v1/funds` should return `pocketBalance ≈ Dashboard currentBalance − fund balances`.
- Verify projections on Funds page align with expected net of planned events.
- Verify FAB category dropdown is alphabetical.
- Verify Dashboard progress bars and Analytics table are alphabetical.
