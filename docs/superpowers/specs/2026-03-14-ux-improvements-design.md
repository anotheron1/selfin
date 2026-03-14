# UX Improvements — Design Spec
Date: 2026-03-14

## Overview

Five independent UX improvements to the selfin personal finance app. All changes are frontend-only unless noted.

---

## 1. Layout Overflow Fix

**Problem:** Long category names cause all page blocks to clip horizontally across all pages.

**Root cause:** Flex children without `min-w-0` do not shrink below their content size, causing overflow.

**Fix:** Add `min-w-0` to flex children and `break-words` / `truncate` to text elements throughout the app. No backend changes.

**Scope:** All pages and shared components where flex layout contains text.

---

## 2. Transaction Name System

### Form Changes

The `description` field is repurposed as "Название транзакции" (transaction name). The label "Комментарий" is replaced with "Название". The field remains optional.

Form fields (in order):
1. Название транзакции (text, optional)
2. Категория (dropdown, optional — transaction may have a name with no category)
3. Сумма план / факт
4. Дата
5. Приоритет (conditional — see below)

### Priority Override Logic

- If the selected category has `priority = HIGH` → no priority selector shown; transaction inherits HIGH and submits `priority = HIGH`.
- If the selected category has `priority = MEDIUM` or `LOW` → priority selector appears with all three options: "обяз" (HIGH) / "·" (MEDIUM) / "хотелка" (LOW). Default selection = MEDIUM. The user may raise or lower priority freely — including raising above the category's priority.
- If no category selected → priority selector always shown, default = MEDIUM.
- FUND_TRANSFER type: no priority selector shown; always submit `priority = MEDIUM`.
- When selector is shown and user has not changed it, submit `priority = MEDIUM`.

### Display Hierarchy (everywhere: Budget, Analytics, History)

- **Name present:** display name prominently; category name below in smaller muted text.
- **No name:** display only category name.
- **No name and no category:** display "Без названия" as fallback.

### Dashboard — Plan/Fact Block

Under each category progress bar, list transaction names (small muted text, single line with ellipsis truncation) for all transactions in that category that have a name set. Show at most 5 names; if more, append "и ещё N".

### Goals — Wishlist ("Хотелки")

Wishlist shows individual LOW-priority transactions, not categories. Display: transaction name if present, otherwise category name, otherwise "Без названия". Transactions appear in the wishlist when their effective priority is LOW (inherited from category or overridden at transaction level).

### Backend

No new database fields or migrations. The `description` column semantics are unchanged — only the UI label and display logic change.

---

## 3. Goals Page — Pocket Balance Header

**Current:** Single figure — current pocket balance.

**New:** Two projected figures, computed on the frontend using existing endpoints.

**Data sources:**
- Base: `pocketBalance` from `GET /api/v1/funds` (FundsOverview)
- Events: `GET /api/v1/events` with appropriate date ranges, filtered to `status = PLANNED`

**Formulas:**

| Label | Formula |
|---|---|
| На конец месяца: X ₽ | `pocketBalance + Σ(PLANNED INCOME events in current month) − Σ(PLANNED EXPENSE events in current month)` |
| На конец планов: X ₽ · до DD MMM YYYY | Same formula but across all future months; date = max(date) across all PLANNED events. Query: GET /events with endDate = today + 730 days. |

If no PLANNED events exist in the next 730 days, hide the "На конец планов" row and show only "На конец месяца".

**No backend changes required.** Both figures are computed on the frontend from existing APIs.

**Rationale:** User sees financial horizon and can decide which wishes/goals to schedule in future months based on projected pocket balance.

---

## 4. Analytics Page

### Column Spacing

Increase padding between table columns so five numbers per column are not cramped. Use `px-3` or `px-4` on cells.

### Delta Sign Logic

Delta sign is now type-aware, so green always means "good":

The backend always returns `delta = fact - plan` for all rows.

- **Expenses (category rows and expense total):** negate the backend value: `displayDelta = -row.delta`. Positive (green) = spent less than planned. Negative (red) = overspent.
- **Income (category rows and income total):** use as-is: `displayDelta = row.delta`. Positive (green) = earned more than planned. Negative (red) = fell short.
- **Balance row:** unchanged — positive balance is green.

### Remove Duplicate Block

Remove the `IncomeGapSection` component (currently rendered after `PlanFactSection` in `Analytics.tsx`). It duplicates income data already shown in the plan-fact table. The income rows inside `PlanFactSection` are kept.

---

## 5. Budget Page

### Income/Expense Header

Simplify the header: show fact only when `factAmount > 0`. If no facts yet, show:
- Доходы: план X ₽
- Расходы: план X ₽

If facts exist, show план/факт for both. This avoids the confusing "факт 0" state.

### Timeline Layout Within Weeks

Replace the flat event list inside each week with a CSS Grid two-column timeline:
- Grid: `grid-cols-[48px_1fr]` (fixed 48px left column)
- **Left column:** Day-of-week abbreviation (Пн/Вт/...) + day number, muted text, vertically aligned to the first event row
- **Right column:** List of events for that day
- **Days with no events:** not shown
- **Separator between day groups:** thin horizontal border or `gap-y` spacing

For days with multiple events, the date label is shown only once, aligned to the top of the group. Implementation: wrap each day group in a single grid row where the left cell spans the event list via flexbox stacking in the right cell (not CSS rowspan, since the layout is div-based).

---

## Affected Files (Frontend Only)

| Area | Files |
|---|---|
| Layout | `App.tsx`, shared wrappers, all pages |
| Form | `components/Fab.tsx` (QuickAddModal) |
| Display everywhere | `pages/Budget.tsx`, `pages/Dashboard.tsx`, `pages/Analytics.tsx` |
| Goals | `pages/Funds.tsx`, `components/WishlistSection.tsx` (card layout inverted: name primary, category secondary — not just a label rename) |
| Analytics delta + remove block | `pages/Analytics.tsx` |
| Budget timeline | `pages/Budget.tsx` |

No backend changes required.
