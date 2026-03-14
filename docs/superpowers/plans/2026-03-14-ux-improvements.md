# UX Improvements — Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Five independent UX improvements: layout overflow fix, transaction naming system, Goals page projections, Analytics delta/spacing/cleanup, and Budget timeline layout.

**Architecture:** All changes are frontend-only. No backend changes or migrations. Each task modifies 1–2 existing files. Tasks 1, 4, 5 are entirely self-contained. Task 2 touches multiple display files. Task 3 is self-contained in Funds.tsx.

**Tech Stack:** React 18, TypeScript, Tailwind CSS, Vite. Dev server: `cd frontend && npm run dev` (port 5173).

**Spec:** `docs/superpowers/specs/2026-03-14-ux-improvements-design.md`

---

## Chunk 1: Layout Fix

### Task 1: Fix layout overflow from long category names

**Files:**
- Modify: `frontend/src/pages/Dashboard.tsx`
- Modify: `frontend/src/pages/Analytics.tsx`

**Root cause:** Long category names in flex containers without `min-w-0`/`truncate` and in tables without `table-fixed` expand beyond `max-w-2xl`, causing all blocks to clip on the right.

- [ ] **Step 1: Fix Dashboard progress bars**

In `Dashboard.tsx`, find the `progressBars.map` section (~line 163). Change the inner label row from:
```tsx
<div className="flex justify-between text-sm mb-1">
    <span>{bar.categoryName}</span>
    <span style={{ color: 'var(--color-text-muted)' }}>
        {fmt(bar.currentFact)} / {fmt(bar.plannedLimit)}
    </span>
</div>
```
To:
```tsx
<div className="flex justify-between gap-2 text-sm mb-1 min-w-0">
    <span className="truncate">{bar.categoryName}</span>
    <span className="shrink-0" style={{ color: 'var(--color-text-muted)' }}>
        {fmt(bar.currentFact)} / {fmt(bar.plannedLimit)}
    </span>
</div>
```

- [ ] **Step 2: Fix Analytics PlanFactGroup table**

In `Analytics.tsx`, in the `PlanFactGroup` function, make the table fixed-layout and truncate the category cell. Change:
```tsx
<table className="w-full text-sm">
    <thead>
        <tr style={{ color: 'var(--color-text-muted)', fontSize: '11px' }}>
            <th className="text-left pb-1 font-normal">Категория</th>
```
To:
```tsx
<table className="w-full text-sm table-fixed">
    <thead>
        <tr style={{ color: 'var(--color-text-muted)', fontSize: '11px' }}>
            <th className="text-left pb-1 font-normal w-2/5">Категория</th>
```

Then change each category body cell from:
```tsx
<td className="py-1.5">{row.categoryName}</td>
```
To:
```tsx
<td className="py-1.5 max-w-0">
    <span className="block truncate">{row.categoryName}</span>
</td>
```

- [ ] **Step 3: Start dev server and visually verify no clipping**

```bash
cd frontend && npm run dev
```
Open http://localhost:5173. Go to Settings, create a category with a very long name (e.g. "Очень длинное название категории для теста переполнения"). Navigate through all pages — no horizontal scroll or right-side clipping should occur.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/pages/Dashboard.tsx frontend/src/pages/Analytics.tsx
git commit -m "fix: prevent layout overflow from long category names (truncate + table-fixed)"
```

---

## Chunk 2: Transaction Name System

### Task 2: Update QuickAdd form — rename field, add priority selector

**Files:**
- Modify: `frontend/src/components/Fab.tsx`

**Changes:**
1. Rename "Комментарий" label to "Название транзакции"
2. Add `priority: 'MEDIUM'` to initial form state
3. Show priority selector when type ≠ FUND_TRANSFER and selected category priority ≠ HIGH
4. Compute effective priority on submit

- [ ] **Step 1: Add priority default to form initial state**

In `Fab.tsx`, change the `useState` initial value:
```tsx
const [form, setForm] = useState<Partial<FinancialEventCreateDto>>({
    date: new Date().toISOString().slice(0, 10),
    type: 'EXPENSE',
    priority: 'MEDIUM',
});
```
(Remove `mandatory: false` — the system now uses `priority`.)

- [ ] **Step 2: Derive selected category and canShowPrioritySelector**

After the `activeFunds` line, add:
```tsx
const selectedCategory = categories.find(c => c.id === form.categoryId);
const showPrioritySelector = form.type !== 'FUND_TRANSFER' && selectedCategory?.priority !== 'HIGH';
```

- [ ] **Step 3: Compute effective priority in handleSubmit**

Before `createEvent`, compute effective priority:
```tsx
const effectivePriority: FinancialEventCreateDto['priority'] = (() => {
    if (isFundTransfer) return 'MEDIUM';
    if (selectedCategory?.priority === 'HIGH') return 'HIGH';
    return form.priority ?? 'MEDIUM';
})();
await createEvent({ ...form as FinancialEventCreateDto, priority: effectivePriority });
```
Replace the existing `createEvent(form as FinancialEventCreateDto)` call with the above.

- [ ] **Step 4: Rename the description input and add priority selector**

Find the `{/* Комментарий */}` input block and replace the entire block with:
```tsx
{/* Название транзакции */}
<Input
    placeholder="Название транзакции (необязательно)"
    value={form.description ?? ''}
    onChange={e => setForm(f => ({ ...f, description: e.target.value, rawInput: e.target.value }))}
/>

{/* Приоритет — только если не FUND_TRANSFER и не HIGH-категория */}
{showPrioritySelector && (
    <div className="flex gap-2">
        {(['HIGH', 'MEDIUM', 'LOW'] as const).map(p => {
            const label = p === 'HIGH' ? 'обяз' : p === 'MEDIUM' ? '·' : 'хотелка';
            const isActive = form.priority === p;
            return (
                <button
                    key={p}
                    type="button"
                    className="flex-1 text-xs px-2 py-1.5 rounded border transition-colors"
                    style={isActive ? {
                        background: p === 'HIGH' ? 'rgba(239,68,68,0.15)' : p === 'LOW' ? 'rgba(100,116,139,0.15)' : 'rgba(108,99,255,0.15)',
                        borderColor: p === 'HIGH' ? 'hsl(var(--destructive))' : p === 'LOW' ? 'var(--color-border)' : 'var(--color-accent)',
                        color: p === 'HIGH' ? 'hsl(var(--destructive))' : p === 'LOW' ? 'var(--color-text-muted)' : 'var(--color-accent)',
                    } : {
                        borderColor: 'var(--color-border)',
                        color: 'var(--color-text-muted)',
                    }}
                    onClick={() => setForm(f => ({ ...f, priority: p }))}
                >
                    {label}
                </button>
            );
        })}
    </div>
)}
```

- [ ] **Step 5: Reset priority when type changes**

In `handleTypeChange`, reset priority to MEDIUM:
```tsx
const handleTypeChange = (type: 'EXPENSE' | 'INCOME' | 'FUND_TRANSFER') => {
    setForm(f => ({ ...f, type, categoryId: undefined, targetFundId: undefined, priority: 'MEDIUM' }));
};
```

- [ ] **Step 6: Reset priority when category changes**

Change category `onValueChange`:
```tsx
onValueChange={val => {
    const cat = categories.find(c => c.id === val);
    setForm(f => ({
        ...f,
        categoryId: val,
        // Keep form.priority neutral (MEDIUM) — effectivePriority derives HIGH on submit
        // This keeps the selector in a defined state whether or not it's visible
        priority: cat?.priority === 'HIGH' ? 'MEDIUM' : f.priority ?? 'MEDIUM',
    }));
}}
```

- [ ] **Step 7: Verify in dev server**

Open http://localhost:5173, click FAB. Verify:
- Field is labelled "Название транзакции"
- Priority buttons appear for EXPENSE/INCOME with non-HIGH category
- Priority buttons hidden when HIGH-priority category selected
- Priority buttons hidden for "В копилку"
- Correct priority submitted (check network tab)

- [ ] **Step 8: Commit**

```bash
git add frontend/src/components/Fab.tsx
git commit -m "feat: rename description to transaction name, add priority selector in QuickAdd form"
```

---

### Task 3: Update transaction display — name as primary, category as subtitle

**Files:**
- Modify: `frontend/src/pages/Budget.tsx`
- Modify: `frontend/src/pages/Dashboard.tsx`
- Modify: `frontend/src/components/WishlistSection.tsx`

**Rule:** If `event.description` is present → display it as primary label; category as secondary muted text. If no description → display only category name.

- [ ] **Step 1: Update Budget.tsx event display**

> **Note:** Task 7 (timeline layout) will completely replace the flat event list in `Budget.tsx`. If Task 7 is implemented in the same session, skip this step for `Budget.tsx` — the name-primary display logic is already included in the Task 7 timeline code. Only apply this step if implementing Task 3 independently without Task 7.

In `Budget.tsx`, inside `weekEvents.map(event => ...)`, find:
```tsx
const displayName = isFundTransfer
    ? `↪ ${event.targetFundName ?? 'Копилка'}`
    : event.categoryName;
```
Change to:
```tsx
const displayName = isFundTransfer
    ? `↪ ${event.targetFundName ?? 'Копилка'}`
    : event.description || event.categoryName || 'Без названия';
const displaySubtitle = !isFundTransfer && event.description
    ? event.categoryName
    : null;
```

Then find the description rendering block:
```tsx
{event.description && (
    <p className="text-xs truncate" style={{ color: 'var(--color-text-muted)' }}>{event.description}</p>
)}
```
Change to:
```tsx
{displaySubtitle && (
    <p className="text-xs truncate" style={{ color: 'var(--color-text-muted)' }}>{displaySubtitle}</p>
)}
```

- [ ] **Step 2: Update Dashboard today-events display**

In `Dashboard.tsx`, in both `incomeToday.map` and `expenseToday.map` blocks, find the span that shows the event name. For incomeToday (line ~91):
```tsx
<span className="truncate">{e.categoryName}</span>
```
Change to:
```tsx
<span className="truncate">{e.description || e.categoryName || 'Без названия'}</span>
```

Do the same for expenseToday (the part that shows `e.categoryName`, not the FUND_TRANSFER branch):
```tsx
{e.type === 'FUND_TRANSFER'
    ? `↪ ${e.targetFundName ?? 'Копилка'}`
    : e.categoryName}
```
Change to:
```tsx
{e.type === 'FUND_TRANSFER'
    ? `↪ ${e.targetFundName ?? 'Копилка'}`
    : e.description || e.categoryName || 'Без названия'}
```

- [ ] **Step 3: Update WishlistSection.tsx display**

In `WishlistSection.tsx`, find the item display block (~line 74–83):
```tsx
<div className="font-medium text-sm">{item.categoryName}</div>
{item.description && (
    <div className="text-xs truncate" style={{ color: 'var(--color-text-muted)' }}>
        {item.description}
    </div>
)}
```
Change to (name primary, category secondary):
```tsx
<div className="font-medium text-sm">
    {item.description || item.categoryName || 'Без названия'}
</div>
{item.description && (
    <div className="text-xs truncate" style={{ color: 'var(--color-text-muted)' }}>
        {item.categoryName}
    </div>
)}
```

- [ ] **Step 4: Verify display hierarchy in dev server**

Create a test event: set category + transaction name. Verify in Budget that name shows large, category shows small below. Create another event with no name. Verify only category shows.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/pages/Budget.tsx frontend/src/pages/Dashboard.tsx frontend/src/components/WishlistSection.tsx
git commit -m "feat: display transaction name as primary label, category as secondary"
```

---

### Task 4: Dashboard — show transaction names under progress bars

**Files:**
- Modify: `frontend/src/pages/Dashboard.tsx`

**Change:** Fetch all events for current month. Under each progress bar category, list (up to 5) transaction names (events with `description` set).

- [ ] **Step 1: Add monthEvents state and fetch**

In `Dashboard.tsx`, add state:
```tsx
const [monthEvents, setMonthEvents] = useState<FinancialEvent[]>([]);
```

Change the `useEffect` Promise.all to also fetch monthly events:
```tsx
useEffect(() => {
    const today = new Date();
    const monthStart = new Date(today.getFullYear(), today.getMonth(), 1).toISOString().slice(0, 10);
    const monthEnd = new Date(today.getFullYear(), today.getMonth() + 1, 0).toISOString().slice(0, 10);

    Promise.all([
        fetchDashboard(),
        fetchAnalyticsReport(),
        fetchEvents(todayStr, todayStr),
        fetchEvents(monthStart, monthEnd),
    ])
        .then(([dash, rep, evts, mEvts]) => {
            setData(dash);
            setAnalytics(rep);
            setTodayEvents(evts);
            setMonthEvents(mEvts);
        })
        .catch(e => setError(e.message));
}, []);
```

- [ ] **Step 2: Render transaction names under each progress bar**

In the progress bars section, after the `<div className="h-2 rounded-full ...">` progress bar element, add (still inside `progressBars.map(bar => ...)`):

```tsx
{/* Transaction names under this category */}
{(() => {
    const names = monthEvents
        .filter(e => e.categoryName === bar.categoryName && e.description)
        .map(e => e.description as string)
        .filter((v, i, arr) => arr.indexOf(v) === i); // deduplicate
    if (names.length === 0) return null;
    const shown = names.slice(0, 5);
    const extra = names.length - shown.length;
    return (
        <p className="text-xs mt-0.5 truncate" style={{ color: 'var(--color-text-muted)' }}>
            {shown.join(' · ')}{extra > 0 ? ` · и ещё ${extra}` : ''}
        </p>
    );
})()}
```

- [ ] **Step 3: Verify in dev server**

Create events with names under one category. Go to Dashboard → "ПЛАН / ФАКТ ЗА МЕСЯЦ" — names should appear as small text under the category bar.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/pages/Dashboard.tsx
git commit -m "feat: show transaction names under plan-fact progress bars on Dashboard"
```

---

## Chunk 3: Goals Page Projections

### Task 5: Show projected pocket balance at end of month and end of plans

**Files:**
- Modify: `frontend/src/pages/Funds.tsx`

- [ ] **Step 1: Add fetchEvents import and projection state**

In `Funds.tsx`, add `fetchEvents` to the import:
```tsx
import { fetchFunds, createFund, updateFund, deleteFund, transferToFund, fetchEvents } from '../api';
```
Add `FinancialEvent` to the type import:
```tsx
import type { FundsOverview, TargetFund, FinancialEvent } from '../types/api';
```

Add projection state in the `Funds` component:
```tsx
const [projections, setProjections] = useState<{
    endOfMonth: number;
    endOfPlans: number | null;
    lastPlanDate: string | null;
} | null>(null);
```

- [ ] **Step 2: Compute projections when data loads**

Add a `useEffect` that runs when `data` changes:
```tsx
useEffect(() => {
    if (!data) return;
    const today = new Date();
    const todayStr = today.toISOString().slice(0, 10);
    const monthEnd = new Date(today.getFullYear(), today.getMonth() + 1, 0).toISOString().slice(0, 10);
    const farFuture = new Date(today.getTime() + 730 * 24 * 60 * 60 * 1000).toISOString().slice(0, 10);

    const projectBalance = (base: number, evts: FinancialEvent[]) =>
        evts
            .filter(e => e.status === 'PLANNED')
            .reduce((bal, e) => {
                const amt = e.plannedAmount ?? 0;
                return e.type === 'INCOME' ? bal + amt : bal - amt;
            }, base);

    Promise.all([
        fetchEvents(todayStr, monthEnd),
        fetchEvents(todayStr, farFuture),
    ]).then(([monthEvts, allFutureEvts]) => {
        const endOfMonth = projectBalance(data.pocketBalance, monthEvts);

        const futurePlanned = allFutureEvts.filter(e => e.status === 'PLANNED');
        let endOfPlans: number | null = null;
        let lastPlanDate: string | null = null;
        if (futurePlanned.length > 0) {
            endOfPlans = projectBalance(data.pocketBalance, allFutureEvts);
            lastPlanDate = futurePlanned.map(e => e.date).sort().at(-1) ?? null;
        }
        setProjections({ endOfMonth, endOfPlans, lastPlanDate });
    });
}, [data]);
```

- [ ] **Step 3: Update the pocket card to show two projection rows**

Add a local formatter for short dates (if not present already):
```tsx
const fmtDate = (s: string) =>
    new Date(s + 'T00:00:00').toLocaleDateString('ru-RU', { day: 'numeric', month: 'short', year: 'numeric' });
```

Find the pocket card div (~line 304) and replace its inner content with:
```tsx
<div className="flex items-center gap-4 flex-1 min-w-0">
    <Wallet size={32} color="white" className="shrink-0" />
    <div className="min-w-0">
        <p className="text-sm text-white/70">В кармашке</p>
        <p className="text-3xl font-bold text-white">
            {new Intl.NumberFormat('ru-RU', { style: 'currency', currency: 'RUB', minimumFractionDigits: 0 })
                .format(data.pocketBalance)}
        </p>
        {projections && (
            <div className="mt-2 space-y-0.5 text-sm text-white/80">
                <p>На конец месяца:{' '}
                    <b>{new Intl.NumberFormat('ru-RU', { style: 'currency', currency: 'RUB', minimumFractionDigits: 0 }).format(projections.endOfMonth)}</b>
                </p>
                {projections.endOfPlans != null && projections.lastPlanDate && (
                    <p>На конец планов:{' '}
                        <b>{new Intl.NumberFormat('ru-RU', { style: 'currency', currency: 'RUB', minimumFractionDigits: 0 }).format(projections.endOfPlans)}</b>
                        <span className="text-white/60"> · до {fmtDate(projections.lastPlanDate)}</span>
                    </p>
                )}
            </div>
        )}
    </div>
</div>
```

Note: remove the existing `Wallet` and balance paragraph from the original card structure since we're replacing the inner content.

- [ ] **Step 4: Verify in dev server**

Go to Funds/Цели page. The pocket card should show current balance + "На конец месяца: X ₽" + "На конец планов: X ₽ · до DD MMM YYYY". If no future events exist, the second line is hidden.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/pages/Funds.tsx
git commit -m "feat: show projected pocket balance at end-of-month and end-of-plans on Goals page"
```

---

## Chunk 4: Analytics Fixes

### Task 6: Fix Analytics — delta sign, column spacing, remove IncomeGapSection

**Files:**
- Modify: `frontend/src/pages/Analytics.tsx`

- [ ] **Step 1: Remove IncomeGapSection from render**

In `Analytics.tsx`, in the `!loading && preset === '1m'` block (~line 88–95), remove the `<IncomeGapSection ... />` line:
```tsx
// Delete this line:
<IncomeGapSection gap={analytics.incomeGap} />
```
Keep `PlanFactSection` and `MandatoryBurnSection`. The `IncomeGapSection` function definition can stay (or be removed for cleanliness; removing it requires also removing the import of nothing — it's defined in the same file so just remove the function if desired).

- [ ] **Step 2: Add isIncome prop to PlanFactGroup**

Change `PlanFactGroup` signature from:
```tsx
function PlanFactGroup({ label, rows, totalPlanned, totalFact, mt }: {
    label: string;
    rows: AnalyticsReport['planFact']['categories'];
    totalPlanned: number;
    totalFact: number;
    mt?: boolean;
})
```
To:
```tsx
function PlanFactGroup({ label, rows, totalPlanned, totalFact, mt, isIncome }: {
    label: string;
    rows: AnalyticsReport['planFact']['categories'];
    totalPlanned: number;
    totalFact: number;
    mt?: boolean;
    isIncome: boolean;
})
```

- [ ] **Step 3: Update callers of PlanFactGroup to pass isIncome**

In `PlanFactSection`, change:
```tsx
<PlanFactGroup label="Доходы" rows={incomeRows}
    totalPlanned={planFact.totalPlannedIncome}
    totalFact={planFact.totalFactIncome} />

<PlanFactGroup label="Расходы" rows={expenseRows}
    totalPlanned={planFact.totalPlannedExpense}
    totalFact={planFact.totalFactExpense}
    mt={incomeRows.length > 0} />
```
To:
```tsx
<PlanFactGroup label="Доходы" rows={incomeRows}
    totalPlanned={planFact.totalPlannedIncome}
    totalFact={planFact.totalFactIncome}
    isIncome={true} />

<PlanFactGroup label="Расходы" rows={expenseRows}
    totalPlanned={planFact.totalPlannedExpense}
    totalFact={planFact.totalFactExpense}
    mt={incomeRows.length > 0}
    isIncome={false} />
```

- [ ] **Step 4: Fix delta sign logic in PlanFactGroup**

In `PlanFactGroup`, change the `totalDelta` and row delta rendering:

Replace:
```tsx
const totalDelta = totalFact - totalPlanned;
```
With:
```tsx
// Positive = good: for income fact>plan is good; for expenses plan>fact is good
const totalDelta = isIncome ? totalFact - totalPlanned : totalPlanned - totalFact;
```

Replace row delta cell:
```tsx
<td className="py-1.5 text-right font-medium"
    style={{ color: deltaColor(row.delta) }}>
    {row.delta >= 0 ? '+' : ''}{fmt(row.delta)}
</td>
```
With:
```tsx
{(() => {
    // Backend returns delta = fact - plan. For expenses, invert so positive = saved (good).
    const displayDelta = isIncome ? row.delta : -row.delta;
    return (
        <td className="py-1.5 text-right font-medium"
            style={{ color: deltaColor(displayDelta) }}>
            {displayDelta >= 0 ? '+' : ''}{fmt(Math.abs(displayDelta))}
        </td>
    );
})()}
```

Replace total delta cell:
```tsx
<td className="py-1.5 text-right"
    style={{ color: deltaColor(totalDelta) }}>
    {totalDelta >= 0 ? '+' : ''}{fmt(totalDelta)}
</td>
```
With:
```tsx
<td className="py-1.5 text-right"
    style={{ color: deltaColor(totalDelta) }}>
    {totalDelta >= 0 ? '+' : ''}{fmt(Math.abs(totalDelta))}
</td>
```

- [ ] **Step 5: Fix multi-month table column spacing**

In `AnalyticsRow`, change data cell padding from `px-3` to `px-4`:
```tsx
// Change all instances of:
className="text-right px-3 py-2 ..."
// To:
className="text-right px-4 py-2 ..."
```
Also change the header `<th>` cells padding: `px-3 py-2` → `px-4 py-2`.

- [ ] **Step 6: Verify in dev server**

Go to Analytics. For 1-month view:
- "ДОХОДЫ" block below plan-fact is gone
- Delta for expense rows: if fact > plan, shows red negative (overspent). If fact < plan, shows green positive (saved).
- Delta for income rows: if fact > plan, shows green positive. If fact < plan, shows red negative.

For 3m/6m/12m: columns have more breathing room between numbers.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/pages/Analytics.tsx
git commit -m "fix: analytics delta sign (expenses inverted), remove duplicate income block, increase column spacing"
```

---

## Chunk 5: Budget Visual Improvements

### Task 7: Fix income/expense header and add timeline layout within weeks

**Files:**
- Modify: `frontend/src/pages/Budget.tsx`

- [ ] **Step 1: Conditionally show fact in income/expense header**

Find the "Компактный итог месяца" section (~line 106–124). Change the expenses row to hide "факт" when it's 0:

```tsx
{/* Компактный итог месяца */}
{!loading && (
    <div className="rounded-2xl px-5 py-3 text-sm space-y-1"
        style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}>
        <div className="flex justify-between">
            <span style={{ color: 'var(--color-text-muted)' }}>Доходы</span>
            <span>
                {totalFactIncome > 0 && (
                    <span style={{ color: 'var(--color-success)' }}>факт {fmt(totalFactIncome)} / </span>
                )}
                <span style={{ color: 'var(--color-text-muted)' }}>план {fmt(totalPlannedIncome)}</span>
            </span>
        </div>
        <div className="flex justify-between">
            <span style={{ color: 'var(--color-text-muted)' }}>Расходы</span>
            <span>
                {totalFactExpense > 0 && (
                    <span>факт {fmt(totalFactExpense)} / </span>
                )}
                <span style={{ color: 'var(--color-text-muted)' }}>план {fmt(totalPlannedExpense)}</span>
            </span>
        </div>
    </div>
)}
```

- [ ] **Step 2: Add day-label helper function**

Before the `Budget` component function, add:
```tsx
const DAY_NAMES = ['Вс', 'Пн', 'Вт', 'Ср', 'Чт', 'Пт', 'Сб'];

function getDayLabel(dateStr: string): { dow: string; dayNum: number } {
    const d = new Date(dateStr + 'T00:00:00');
    return { dow: DAY_NAMES[d.getDay()], dayNum: d.getDate() };
}
```

- [ ] **Step 3: Replace flat week event list with timeline layout**

Find the `{isOpen && (...)` block inside `weeks.map(week => ...)`. Replace the inner content with a day-grouped timeline:

```tsx
{isOpen && (
    <div>
        {weekEvents.length === 0 ? (
            <p className="px-5 py-3 text-sm" style={{ color: 'var(--color-text-muted)' }}>Нет событий</p>
        ) : (() => {
            // Group events by date
            const byDay = weekEvents.reduce<Record<string, FinancialEvent[]>>((acc, e) => {
                (acc[e.date] ??= []).push(e);
                return acc;
            }, {});
            const sortedDays = Object.keys(byDay).sort();

            return sortedDays.map((day, dayIdx) => {
                const { dow, dayNum } = getDayLabel(day);
                const dayEvts = byDay[day];
                return (
                    <div
                        key={day}
                        style={{
                            display: 'grid',
                            gridTemplateColumns: '48px 1fr',
                            borderTop: dayIdx > 0 ? '1px solid var(--color-border)' : undefined,
                        }}
                    >
                        {/* Left: date label */}
                        <div className="flex flex-col items-center justify-start pt-3 pb-2 select-none"
                            style={{ color: 'var(--color-text-muted)', fontSize: '11px', lineHeight: 1.3 }}>
                            <span>{dow}</span>
                            <span className="font-semibold text-sm mt-0.5"
                                style={{ color: 'var(--color-text)' }}>{dayNum}</span>
                        </div>
                        {/* Right: events */}
                        <div className="divide-y" style={{ borderColor: 'var(--color-border)' }}>
                            {dayEvts.map((event: FinancialEvent) => {
                                const delta = event.factAmount != null && event.plannedAmount != null
                                    ? event.factAmount - event.plannedAmount : null;
                                const isIncome = event.type === 'INCOME';
                                const isFundTransfer = event.type === 'FUND_TRANSFER';
                                const isExecuted = event.status === 'EXECUTED';
                                const displayName = isFundTransfer
                                    ? `↪ ${event.targetFundName ?? 'Копилка'}`
                                    : event.description || event.categoryName || 'Без названия';
                                const displaySubtitle = !isFundTransfer && event.description
                                    ? event.categoryName
                                    : null;
                                const amountColor = isIncome
                                    ? 'var(--color-success)'
                                    : isFundTransfer
                                        ? 'hsl(var(--primary))'
                                        : isExecuted ? 'var(--color-text-muted)' : 'var(--color-text)';
                                const isLowPlanned = event.priority === 'LOW' && event.status === 'PLANNED';
                                return (
                                    <div key={event.id}
                                        onClick={() => setSelectedEvent(event)}
                                        className={`pr-5 py-3 flex items-center justify-between gap-3 cursor-pointer hover:bg-white/5 transition-colors${isLowPlanned ? ' opacity-60' : ''}`}>
                                        <div className="flex-1 min-w-0">
                                            <div className="flex items-center gap-2">
                                                <span className="font-medium text-sm truncate">{displayName}</span>
                                                <PriorityButton
                                                    priority={event.priority}
                                                    onCycle={() => cycleEventPriority(event.id).then(load)}
                                                />
                                                {isExecuted && (
                                                    <Badge variant="outline" className="text-xs border-green-600/60 text-green-500 px-1.5 py-0">✓</Badge>
                                                )}
                                            </div>
                                            {displaySubtitle && (
                                                <p className="text-xs truncate" style={{ color: 'var(--color-text-muted)' }}>{displaySubtitle}</p>
                                            )}
                                        </div>
                                        <div className="text-right shrink-0 space-y-0.5">
                                            <div className="text-sm font-semibold" style={{ color: amountColor }}>
                                                {isIncome ? '+' : '-'}{fmt(event.plannedAmount)}
                                            </div>
                                            {event.factAmount != null && (
                                                <div className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
                                                    факт: {fmt(event.factAmount)}
                                                    {delta != null && (
                                                        <span style={{ color: delta > 0 ? 'var(--color-danger)' : 'var(--color-success)', marginLeft: 4 }}>
                                                            {delta > 0 ? '+' : ''}{fmt(delta)}
                                                        </span>
                                                    )}
                                                </div>
                                            )}
                                        </div>
                                    </div>
                                );
                            })}
                        </div>
                    </div>
                );
            });
        })()}
    </div>
)}
```

Note: the `divide-y` class on the outer list in the original code is now on the right column per-day div. The left date label spans all events for that day naturally because the grid row contains the date cell and the event stack in one grid row.

- [ ] **Step 4: Verify in dev server**

Go to Budget page. Open a week with events on multiple days. Verify:
- Days are separated with thin border
- Left column shows day-of-week abbreviation + day number
- Events for the same day stack on the right
- Days with no events are not shown
- Expenses header shows "план X ₽" only when fact = 0

- [ ] **Step 5: Commit**

```bash
git add frontend/src/pages/Budget.tsx
git commit -m "feat: budget timeline layout (day-grouped within week) + conditional expense fact display"
```

---

## Summary

| Task | File(s) | Change |
|---|---|---|
| 1 | Dashboard.tsx, Analytics.tsx | `min-w-0`/`truncate`/`table-fixed` to prevent overflow |
| 2 | Fab.tsx | Rename field to "Название", add priority selector |
| 3 | Budget.tsx, Dashboard.tsx, WishlistSection.tsx | Name-primary display hierarchy |
| 4 | Dashboard.tsx | Monthly events fetch + transaction names under bars |
| 5 | Funds.tsx | Projected pocket balance (end-of-month, end-of-plans) |
| 6 | Analytics.tsx | Delta sign fix, remove IncomeGapSection, column spacing |
| 7 | Budget.tsx | Conditional expense fact + timeline layout |
