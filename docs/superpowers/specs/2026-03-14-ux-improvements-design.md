# UX Improvements — Design Spec
Date: 2026-03-14

## Overview

Five independent UX improvements to the selfin personal finance app.

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
2. Категория (dropdown, optional — transaction may have name only)
3. Сумма план / факт
4. Дата
5. Приоритет (conditional — see below)

### Priority Override Logic

- If the selected category has `priority = HIGH` → no priority selector shown; transaction inherits HIGH.
- If the selected category has `priority = MEDIUM` or `LOW` → priority selector appears with three options: "обяз" (HIGH) / "·" (MEDIUM, default) / "хотелка" (LOW).
- If no category selected → priority selector always shown.

### Display Hierarchy (everywhere: Budget, Analytics, History)

- **Name present:** display name prominently; category name below in smaller muted text.
- **No name:** display only category name.

### Dashboard — Plan/Fact Block

Under each category progress bar, list transaction names (small muted text) for all transactions in that category that have a name set.

### Goals — Wishlist ("Хотелки")

Wishlist shows individual LOW-priority transactions, not categories. Display: transaction name if present, otherwise category name. Transactions are transferred to the wishlist when their priority is LOW (either inherited from category or overridden at transaction level).

### Backend

No new database fields or migrations. The `description` column semantics are unchanged — only the UI label and display logic change.

---

## 3. Goals Page — Pocket Balance Header

**Current:** Single figure — current pocket balance.

**New:** Two figures displayed in the header card:

| Label | Value |
|---|---|
| На конец месяца | Current pocket balance + sum of all unexecuted planned events through end of current month |
| На конец планов · до DD MMM YYYY | Current pocket balance + sum of all future planned events across all months; date = latest planned event date |

**Rationale:** User sees financial horizon and can decide which wishes/goals to schedule in future months based on projected pocket balance.

---

## 4. Analytics Page

### Column Spacing

Increase padding between table columns so five numbers per column are not cramped. Use `px-3` or `px-4` on cells instead of current tight spacing.

### Delta Sign Logic

Delta sign is now type-aware, so green always means "good":

- **Expenses:** `delta = plan - fact`. Positive (green) = spent less than planned. Negative (red) = overspent.
- **Income:** `delta = fact - plan`. Positive (green) = earned more than planned. Negative (red) = fell short.

### Remove Duplicate Block

The "Доходы" block located below "Отчёт план-факт" duplicates information already shown in the plan-fact table. Remove it.

---

## 5. Budget Page

### Income/Expense Header

The expense fact column always shows 0 in current implementation. Fix: show fact for expenses only if `factAmount > 0`, otherwise display plan only. Alternatively simplify the header to: `Доходы план/факт · Расходы план`.

### Timeline Layout Within Weeks

Replace the flat event list inside each week with a two-column timeline:

```
│ Пн  │  Аренда офиса              5 000 ₽  обяз
│ 10  │  Продукты              1 200 ₽  хотелка
│─────│──────────────────────────────────────────
│ Ср  │  Зарплата                 80 000 ₽
│ 12  │
│─────│──────────────────────────────────────────
│ Пт  │  Интернет                   800 ₽  обяз
│ 14  │
```

- **Left column:** Day of week abbreviation (Пн/Вт/...) + day number, fixed width, vertically centered.
- **Right column:** List of events for that day.
- **Days with no events:** not shown.
- **Separator:** thin horizontal line or spacing between day groups.
- If a day has multiple events, the left date label spans all of them (using flex alignment or a single cell with `rowSpan`-equivalent approach).

---

## Affected Files (Frontend Only)

| Area | Files |
|---|---|
| Layout | `App.tsx`, shared wrappers |
| Form | `components/Fab.tsx` (QuickAddModal) |
| Display everywhere | `pages/Budget.tsx`, `pages/Dashboard.tsx`, `pages/Analytics.tsx` |
| Goals | `pages/Funds.tsx`, `components/WishlistSection.tsx` |
| Analytics delta | `pages/Analytics.tsx` |
| Budget timeline | `pages/Budget.tsx` |

No backend changes required.
