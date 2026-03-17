# Savings Strategy Planner — Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Upgrade `SavingsStrategySection` to simulate fund completion (freed-up cash), enforce a shared 50%-of-income cap across all sliders with proportional rebalancing, and add an "overtime" checkbox that relaxes the cap to 100% with a second income line on the chart.

**Architecture:** All changes are pure frontend — no backend modifications. Business logic is extracted to a new `savingsStrategyUtils.ts` file containing four pure functions that are unit-tested in isolation. The React component imports these utilities and wires them to state.

**Tech Stack:** React 18, TypeScript, Recharts, Vitest (needs install)

**Spec:** `docs/superpowers/specs/2026-03-17-savings-strategy-planner-design.md`

---

## File Map

| Action | Path | Responsibility |
|--------|------|----------------|
| Create | `frontend/src/components/funds/savingsStrategyUtils.ts` | Pure functions: `rebalancePercents`, `scalePercentsToFit`, `maxPercent`, `buildChartData`, `calcPMT`, `fmtYearMonth`, types |
| Create | `frontend/src/components/funds/__tests__/savingsStrategyUtils.test.ts` | Unit tests for all pure functions |
| Modify | `frontend/vite.config.ts` | Add `test` block for Vitest |
| Modify | `frontend/package.json` | Add `vitest` dev dependency + `test` script |
| Modify | `frontend/src/components/funds/SavingsStrategySection.tsx` | Import utils, add `allowOvertime` state, fix slider logic, update chart, add markers |

---

## Chunk 1: Utility Functions + Tests

### Task 1: Install Vitest and configure

**Files:**
- Modify: `frontend/package.json`
- Modify: `frontend/vite.config.ts`

- [ ] **Step 1.1: Install vitest**

```bash
cd frontend && npm install --save-dev vitest
```

Expected output: `added N packages`

- [ ] **Step 1.2: Add test script to `frontend/package.json`**

In the `"scripts"` section, add:
```json
"test": "vitest run",
"test:watch": "vitest"
```

- [ ] **Step 1.3: Add test config to `frontend/vite.config.ts`**

Replace the file content with:
```ts
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import path from "path"

export default defineConfig({
  plugins: [react()],
  server: {
    port: process.env.PORT ? parseInt(process.env.PORT) : 5173,
    strictPort: false,
  },
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./src"),
    },
  },
  test: {
    environment: 'node',
    globals: true,
  },
})
```

- [ ] **Step 1.4: Verify vitest runs (no test files yet)**

```bash
cd frontend && npm test
```

Expected: `No test files found` or 0 tests, exit 0.

---

### Task 2: Create utils file with helpers and types

**Files:**
- Create: `frontend/src/components/funds/savingsStrategyUtils.ts`

- [ ] **Step 2.1: Create the file with helpers and types**

Create `frontend/src/components/funds/savingsStrategyUtils.ts`:

```ts
import type { FundPlannerMonth, TargetFund } from '../../types/api';

// ─── Helpers ─────────────────────────────────────────────────────────────────

/** Annuity PMT formula: P × r × (1+r)^n / ((1+r)^n − 1) */
export function calcPMT(principal: number, annualRate: number, termMonths: number): number {
    const r = annualRate / 100 / 12;
    if (r === 0) return principal / termMonths;
    const pow = Math.pow(1 + r, termMonths);
    return (principal * r * pow) / (pow - 1);
}

/** Formats "2026-03" → "мар 26" */
export function fmtYearMonth(ym: string): string {
    const [year, month] = ym.split('-');
    const date = new Date(Number(year), Number(month) - 1, 1);
    const shortMonth = date.toLocaleDateString('ru-RU', { month: 'short' }).replace('.', '');
    const shortYear = String(year).slice(2);
    return `${shortMonth} ${shortYear}`;
}

// ─── Chart types ─────────────────────────────────────────────────────────────

export type ChartPoint = {
    label: string;
    'Доход': number;
    'Обяз. расходы': number;
    'Все расходы': number;
    'Расходы + копилки': number;
    'Доход + подработки'?: number;
};

export type BuildResult = {
    chartData: ChartPoint[];
    completionLabels: { label: string; name: string }[];
};

// ─── Pure business-logic functions ───────────────────────────────────────────

/**
 * Adjusts all fund percentages so that:
 * 1. The target fund gets `newValue` (clamped to `cap`).
 * 2. Total across all funds never exceeds `cap`.
 * 3. Other funds are reduced proportionally if needed.
 */
export function rebalancePercents(
    fundId: string,
    newValue: number,
    current: Record<string, number>,
    cap: number,
): Record<string, number> {
    const others = Object.entries(current).filter(([id]) => id !== fundId);
    const otherSum = others.reduce((s, [, v]) => s + v, 0);
    const proposed = Math.min(newValue, cap);      // hard ceiling
    const available = cap - proposed;

    if (otherSum <= available) {
        // Fits without rebalancing
        return { ...current, [fundId]: proposed };
    }

    // Scale down other funds proportionally
    const excess = otherSum - available;
    const next: Record<string, number> = { ...current, [fundId]: proposed };
    for (const [id, v] of others) {
        next[id] = Math.max(0, v - (v / otherSum) * excess);
    }
    return next;
}

/**
 * Scales ALL fund percentages down so their total fits within `cap`.
 * Used when the overtime checkbox is toggled off (cap drops from 100 to 50).
 * Proportions are preserved. Returns unchanged object if already within cap.
 */
export function scalePercentsToFit(
    current: Record<string, number>,
    cap: number,
): Record<string, number> {
    const total = Object.values(current).reduce((s, v) => s + v, 0);
    if (total <= cap) return current;
    const next: Record<string, number> = {};
    for (const [id, v] of Object.entries(current)) {
        next[id] = v * cap / total;
    }
    return next;
}

/**
 * Returns the maximum meaningful slider % for a fund.
 * For SAVINGS funds: capped at the % needed to pay off the fund in one month.
 * For CREDIT funds or null-target funds: returns globalCap unchanged.
 */
export function maxPercent(
    targetAmount: number | null | undefined,
    purchaseType: string,
    avgIncome: number,
    globalCap: number,
): number {
    if (purchaseType === 'CREDIT' || targetAmount == null || avgIncome === 0) {
        return globalCap;
    }
    const fundMax = Math.ceil((targetAmount / avgIncome) * 100);
    return Math.min(globalCap, fundMax);
}

/**
 * Runs the savings simulation over all 36 months.
 *
 * Rules:
 * - SAVINGS funds: contribute monthly = income * percent / 100, capped at remaining target.
 *   When accumulated >= targetAmount the fund completes and contributions stop.
 *   Funds with targetAmount = null contribute forever (freed-up cash never appears).
 * - CREDIT funds: fixed annuity payment each month for creditTermMonths.
 * - Returns chart data + labels for completed funds (for ReferenceLine markers).
 */
export function buildChartData(
    months: FundPlannerMonth[],
    funds: TargetFund[],
    fundPercents: Record<string, number>,
    allowOvertime: boolean,
): BuildResult {
    // Initialize simulation state for SAVINGS funds
    const fundState: Record<string, { accumulated: number; complete: boolean }> = {};
    for (const f of funds) {
        if (f.purchaseType !== 'CREDIT') {
            fundState[f.id] = { accumulated: 0, complete: false };
        }
    }

    const completionLabels: { label: string; name: string }[] = [];

    const chartData = months.map((m, idx) => {
        let totalContribution = 0;

        for (const f of funds) {
            if (f.purchaseType === 'CREDIT') {
                if (
                    f.creditRate != null &&
                    f.creditTermMonths != null &&
                    f.targetAmount != null &&
                    idx < f.creditTermMonths
                ) {
                    totalContribution += calcPMT(f.targetAmount, f.creditRate, f.creditTermMonths);
                }
                continue;
            }

            const state = fundState[f.id];
            if (!state || state.complete) continue;

            const monthly = m.plannedIncome * (fundPercents[f.id] ?? 0) / 100;
            const remaining = f.targetAmount != null
                ? f.targetAmount - state.accumulated
                : Infinity;
            const contribution = Math.min(monthly, remaining);
            state.accumulated += contribution;
            totalContribution += contribution;

            if (f.targetAmount != null && state.accumulated >= f.targetAmount) {
                state.complete = true;
                // Push marker exactly at the month of completion
                completionLabels.push({ label: fmtYearMonth(m.yearMonth), name: f.name });
            }
        }

        const point: ChartPoint = {
            label: fmtYearMonth(m.yearMonth),
            'Доход': Math.round(m.plannedIncome),
            'Обяз. расходы': Math.round(m.mandatoryExpenses),
            'Все расходы': Math.round(m.allPlannedExpenses),
            'Расходы + копилки': Math.round(m.allPlannedExpenses + totalContribution),
        };

        if (allowOvertime) {
            point['Доход + подработки'] = Math.round(m.plannedIncome * 1.5);
        }

        return point;
    });

    return { chartData, completionLabels };
}
```

---

### Task 3: Write and run tests for `rebalancePercents`

**Files:**
- Create: `frontend/src/components/funds/__tests__/savingsStrategyUtils.test.ts`

- [ ] **Step 3.1: Write failing tests**

Create `frontend/src/components/funds/__tests__/savingsStrategyUtils.test.ts`:

```ts
import { describe, it, expect } from 'vitest';
import {
    rebalancePercents,
    scalePercentsToFit,
    maxPercent,
    buildChartData,
    calcPMT,
} from '../savingsStrategyUtils';
import type { FundPlannerMonth } from '../../../types/api';
import type { TargetFund } from '../../../types/api';

// ─── rebalancePercents ────────────────────────────────────────────────────────

describe('rebalancePercents', () => {
    it('sets value when total stays within cap', () => {
        const result = rebalancePercents('a', 10, { a: 0, b: 5 }, 50);
        expect(result['a']).toBe(10);
        expect(result['b']).toBe(5);
    });

    it('clamps proposed to cap', () => {
        const result = rebalancePercents('a', 60, { a: 0, b: 0 }, 50);
        expect(result['a']).toBe(50);
    });

    it('reduces others proportionally when cap exceeded', () => {
        // a=0 b=30 c=20, set a=20 → cap=50
        // proposed=20, available=30, otherSum=50 (b+c), excess=otherSum-available=20
        // formula uses otherSum (50), not cap (50) — they coincide here, but it's otherSum
        // b gets: 30 - (30/otherSum=50) * 20 = 30 - 12 = 18
        // c gets: 20 - (20/otherSum=50) * 20 = 20 - 8 = 12
        const result = rebalancePercents('a', 20, { a: 0, b: 30, c: 20 }, 50);
        expect(result['a']).toBe(20);
        expect(result['b']).toBeCloseTo(18, 5);
        expect(result['c']).toBeCloseTo(12, 5);
    });

    it('reduces other fund exactly to remaining available space', () => {
        // a=0 b=10, set a=45 → cap=50, available=5, otherSum=10, excess=5
        // b: 10 - (10/10)*5 = 5  (does not go to 0)
        const result = rebalancePercents('a', 45, { a: 0, b: 10 }, 50);
        expect(result['a']).toBe(45);
        expect(result['b']).toBeCloseTo(5);
        expect(result['a'] + result['b']).toBeCloseTo(50);
    });

    it('all others at 0 — proposed is clamped to cap', () => {
        const result = rebalancePercents('a', 80, { a: 0, b: 0, c: 0 }, 50);
        expect(result['a']).toBe(50);
        expect(result['b']).toBe(0);
        expect(result['c']).toBe(0);
    });
});
```

- [ ] **Step 3.2: Run tests — expect PASS (utils already written)**

```bash
cd frontend && npm test src/components/funds/__tests__/savingsStrategyUtils.test.ts
```

Expected: all `rebalancePercents` tests PASS.

---

### Task 4: Add and run tests for `scalePercentsToFit`

- [ ] **Step 4.1: Append tests to the test file**

Add after the `rebalancePercents` describe block:

```ts
// ─── scalePercentsToFit ───────────────────────────────────────────────────────

describe('scalePercentsToFit', () => {
    it('returns unchanged object when already within cap', () => {
        const current = { a: 20, b: 15 };
        expect(scalePercentsToFit(current, 50)).toEqual(current);
    });

    it('scales down proportionally when over cap', () => {
        // a=60 b=40 → total=100, cap=50
        // a → 60*50/100=30, b → 40*50/100=20
        const result = scalePercentsToFit({ a: 60, b: 40 }, 50);
        expect(result['a']).toBeCloseTo(30);
        expect(result['b']).toBeCloseTo(20);
    });

    it('total after scaling equals cap exactly', () => {
        const result = scalePercentsToFit({ a: 33, b: 33, c: 34 }, 50);
        const total = Object.values(result).reduce((s, v) => s + v, 0);
        expect(total).toBeCloseTo(50);
    });
});
```

- [ ] **Step 4.2: Run tests**

```bash
cd frontend && npm test src/components/funds/__tests__/savingsStrategyUtils.test.ts
```

Expected: all tests PASS.

---

### Task 5: Add and run tests for `maxPercent`

- [ ] **Step 5.1: Append tests**

```ts
// ─── maxPercent ───────────────────────────────────────────────────────────────

describe('maxPercent', () => {
    it('returns globalCap for CREDIT funds', () => {
        expect(maxPercent(500000, 'CREDIT', 150000, 50)).toBe(50);
    });

    it('returns globalCap when targetAmount is null', () => {
        expect(maxPercent(null, 'SAVINGS', 150000, 50)).toBe(50);
    });

    it('returns globalCap when avgIncome is 0', () => {
        expect(maxPercent(80000, 'SAVINGS', 0, 50)).toBe(50);
    });

    it('returns ceil(targetAmount/income*100) when less than globalCap', () => {
        // 30000/150000*100 = 20 → ceil(20) = 20
        expect(maxPercent(30000, 'SAVINGS', 150000, 50)).toBe(20);
    });

    it('is capped by globalCap if fundMax exceeds it', () => {
        // 200000/150000*100 = 133.3 → ceil = 134, but cap = 50
        expect(maxPercent(200000, 'SAVINGS', 150000, 50)).toBe(50);
    });
});
```

- [ ] **Step 5.2: Run tests**

```bash
cd frontend && npm test src/components/funds/__tests__/savingsStrategyUtils.test.ts
```

Expected: all tests PASS.

---

### Task 6: Add and run tests for `buildChartData`

- [ ] **Step 6.1: Append tests**

```ts
// ─── buildChartData ───────────────────────────────────────────────────────────

const makeMonth = (yearMonth: string, income = 100000, mandatory = 40000, allExpenses = 60000): FundPlannerMonth => ({
    yearMonth,
    plannedIncome: income,
    mandatoryExpenses: mandatory,
    allPlannedExpenses: allExpenses,
});

const makeSavingsFund = (id: string, name: string, targetAmount: number | null): TargetFund => ({
    id,
    name,
    targetAmount,
    currentBalance: 0,
    status: 'FUNDING',
    priority: 100,
    targetDate: null,
    estimatedCompletionDate: null,
    purchaseType: 'SAVINGS',
    creditRate: null,
    creditTermMonths: null,
});

const makeCreditFund = (id: string, name: string, amount: number, rate: number, term: number): TargetFund => ({
    id,
    name,
    targetAmount: amount,
    currentBalance: 0,
    status: 'FUNDING',
    priority: 100,
    targetDate: null,
    estimatedCompletionDate: null,
    purchaseType: 'CREDIT',
    creditRate: rate,
    creditTermMonths: term,
});

describe('buildChartData', () => {
    it('with 0% for all funds, копилки line equals all expenses', () => {
        const months = [makeMonth('2026-03'), makeMonth('2026-04')];
        const funds = [makeSavingsFund('a', 'A', 50000)];
        const { chartData } = buildChartData(months, funds, { a: 0 }, false);
        expect(chartData[0]['Расходы + копилки']).toBe(60000);
        expect(chartData[1]['Расходы + копилки']).toBe(60000);
    });

    it('savings fund contributes monthly and stops at targetAmount', () => {
        // fund target=20000, income=100000, 10% → 10000/мес → done in 2 months
        const months = [
            makeMonth('2026-03'),
            makeMonth('2026-04'),
            makeMonth('2026-05'),
        ];
        const funds = [makeSavingsFund('a', 'Покраска', 20000)];
        const { chartData, completionLabels } = buildChartData(months, funds, { a: 10 }, false);

        // month 1: 10000 contribution
        expect(chartData[0]['Расходы + копилки']).toBe(70000);
        // month 2: 10000 contribution (fund completes)
        expect(chartData[1]['Расходы + копилки']).toBe(70000);
        // month 3: fund complete, 0 contribution
        expect(chartData[2]['Расходы + копилки']).toBe(60000);
        // completion label at month 2
        expect(completionLabels).toHaveLength(1);
        expect(completionLabels[0].name).toBe('Покраска');
    });

    it('does not over-contribute in the completion month (partial contribution)', () => {
        // fund target=15000, income=100000, 10% → 10000/мес
        // month 1: +10000 = 10000
        // month 2: need 5000 more, contribute 5000 (not 10000)
        const months = [makeMonth('2026-03'), makeMonth('2026-04'), makeMonth('2026-05')];
        const funds = [makeSavingsFund('a', 'Watch', 15000)];
        const { chartData } = buildChartData(months, funds, { a: 10 }, false);
        // month 2: allExpenses(60000) + 5000 partial = 65000
        expect(chartData[1]['Расходы + копилки']).toBe(65000);
        // month 3: complete, 0
        expect(chartData[2]['Расходы + копилки']).toBe(60000);
    });

    it('null-target fund contributes forever', () => {
        const months = [makeMonth('2026-03'), makeMonth('2026-04'), makeMonth('2026-05')];
        const funds = [makeSavingsFund('a', 'Карман', null)];
        const { chartData, completionLabels } = buildChartData(months, funds, { a: 10 }, false);
        expect(chartData[0]['Расходы + копилки']).toBe(70000);
        expect(chartData[2]['Расходы + копилки']).toBe(70000);
        expect(completionLabels).toHaveLength(0);
    });

    it('credit fund contributes fixed PMT for term months then stops', () => {
        // term = 2 months, so contributes in idx 0 and 1, not idx 2
        const months = [makeMonth('2026-03'), makeMonth('2026-04'), makeMonth('2026-05')];
        const creditFund = makeCreditFund('b', 'Машина', 120000, 0, 2); // 0% rate, term=2
        // PMT at 0% = 120000/2 = 60000
        const { chartData } = buildChartData(months, [creditFund], { b: 0 }, false);
        expect(chartData[0]['Расходы + копилки']).toBe(60000 + 60000); // allExp + PMT
        expect(chartData[1]['Расходы + копилки']).toBe(60000 + 60000);
        expect(chartData[2]['Расходы + копилки']).toBe(60000); // term over
    });

    it('overtime line absent when allowOvertime=false', () => {
        const months = [makeMonth('2026-03')];
        const { chartData } = buildChartData(months, [], {}, false);
        expect(chartData[0]['Доход + подработки']).toBeUndefined();
    });

    it('overtime line present and equals 1.5× income when allowOvertime=true', () => {
        const months = [makeMonth('2026-03', 100000)];
        const { chartData } = buildChartData(months, [], {}, true);
        expect(chartData[0]['Доход + подработки']).toBe(150000);
    });

    it('calcPMT: 0% rate returns principal/term', () => {
        expect(calcPMT(120000, 0, 12)).toBeCloseTo(10000);
    });

    it('credit fund with null parameters contributes 0 to chart', () => {
        // Spec section 6: "CREDIT-фонд без параметров — не добавляет нагрузку на график"
        const months = [makeMonth('2026-03')];
        const incompleteCreditFund: TargetFund = {
            ...makeCreditFund('c', 'Null-credit', 500000, 18, 60),
            creditRate: null,       // params not yet set
            creditTermMonths: null,
        };
        const { chartData } = buildChartData(months, [incompleteCreditFund], { c: 0 }, false);
        expect(chartData[0]['Расходы + копилки']).toBe(60000); // allExpenses only, no PMT added
    });
});
```

- [ ] **Step 6.2: Run all tests**

```bash
cd frontend && npm test src/components/funds/__tests__/savingsStrategyUtils.test.ts
```

Expected: all tests PASS.

- [ ] **Step 6.3: Commit Chunk 1**

```bash
cd frontend && git add package.json vite.config.ts src/components/funds/savingsStrategyUtils.ts src/components/funds/__tests__/savingsStrategyUtils.test.ts && git commit -m "feat: extract savings strategy utils with full test coverage"
```

---

## Chunk 2: Update SavingsStrategySection Component

### Task 7: Replace helpers and chart data logic in the component

**Files:**
- Modify: `frontend/src/components/funds/SavingsStrategySection.tsx`

The component currently defines `calcPMT` and `fmtYearMonth` locally and computes chart data inline. We remove the local definitions and switch to the utils.

- [ ] **Step 7.1: Remove local helper definitions and import from utils**

In `SavingsStrategySection.tsx`, remove the `calcPMT` and `fmtYearMonth` function bodies (lines that define them). Then update the imports at the top of the file:

```ts
import {
    calcPMT,
    fmtYearMonth,
    buildChartData,
    rebalancePercents,
    scalePercentsToFit,
    maxPercent,
    type BuildResult,
} from './savingsStrategyUtils';
```

Remove the existing `import { calcPMT, fmtYearMonth }` references if they exist as local functions.

- [ ] **Step 7.2: Add `allowOvertime` state**

In the `SavingsStrategySection` component body, after the existing state declarations, add:

```ts
const [allowOvertime, setAllowOvertime] = useState(false);
```

- [ ] **Step 7.3: Add computed variables block**

After the `activeFunds` line, replace the existing `plannedIncome` and `totalPercent`/`totalMonthly` computations with the full computed block:

```ts
const avgIncome = plannerData ? avgFirstMonths(plannerData.months, 3) : 0;
const totalPercent = Object.values(fundPercents).reduce((s, v) => s + v, 0);
const totalMonthly = Math.round(avgIncome * totalPercent / 100);
const cap = allowOvertime ? 100 : 50;
```

- [ ] **Step 7.3a: Rename remaining `plannedIncome` references to `avgIncome`**

After introducing `avgIncome` in Step 7.3, the old variable name `plannedIncome` still appears in two places in the component. Find and rename both:

1. In the fund card loop — the `monthly` calculation:
   ```ts
   // Before:
   const monthly = Math.round(plannedIncome * percent / 100);
   // After:
   const monthly = Math.round(avgIncome * percent / 100);
   ```

2. In the summary bar — the income display span:
   ```tsx
   // Before:
   ~{fmtRub(Math.round(plannedIncome))}/мес
   // After:
   ~{fmtRub(Math.round(avgIncome))}/мес
   ```

Without this rename the component will not compile (TypeScript error: `plannedIncome` is not defined).

- [ ] **Step 7.4: Replace inline chart data computation with `buildChartData`**

Find the existing `const chartData = plannerData ? plannerData.months.map(...)` block and replace it with:

```ts
const { chartData, completionLabels } = plannerData
    ? buildChartData(plannerData.months, activeFunds, fundPercents, allowOvertime)
    : { chartData: [], completionLabels: [] };
```

- [ ] **Step 7.5: Verify the app still renders (manual check)**

```bash
cd frontend && npm run dev
```

Open http://localhost:5173, navigate to Funds page, expand "Планировщик копилок". The section should render without errors. The chart should look the same as before (graph simulation now stops when funds complete).

---

### Task 8: Fix slider onChange and max

- [ ] **Step 8.1: Update slider `onChange` handler**

Find the `onChange` handler of the `<input type="range">` in the fund card loop. Replace:

```ts
onChange={e =>
    setFundPercents(prev => ({
        ...prev,
        [fund.id]: Number(e.target.value),
    }))
}
```

With:

```ts
onChange={e =>
    setFundPercents(prev =>
        rebalancePercents(fund.id, Number(e.target.value), prev, cap)
    )
}
```

- [ ] **Step 8.2: Update slider `max` attribute**

Find `max={50}` on the range input. Replace with:

```ts
max={maxPercent(fund.targetAmount, fund.purchaseType, avgIncome, cap)}
```

- [ ] **Step 8.3: Manual test — proportional rebalancing**

Run dev server. In the planner:
1. Set one fund to 40% — confirm it works.
2. Set another fund to 40% — the first fund should automatically decrease to 10%.
3. Confirm total never exceeds 50%.

---

### Task 9: Update summary bar — cap indicator + overtime checkbox

- [ ] **Step 9.1: Update summary bar text**

Find the summary bar JSX block. Replace the "Итого откладываю" span:

```tsx
<span style={{ color: 'var(--color-text-muted)' }}>
    Распределено:{' '}
    <span className="font-medium" style={{ color: totalPercent > 50 && allowOvertime ? '#f97316' : 'var(--color-text)' }}>
        {Math.round(totalPercent)}% из {cap}% ({fmtRub(totalMonthly)}/мес)
    </span>
</span>
```

- [ ] **Step 9.2: Add overtime checkbox to summary bar**

After the "Распределено" span (still inside the summary bar div), add:

```tsx
<label className="flex items-center gap-1.5 cursor-pointer select-none">
    <input
        type="checkbox"
        checked={allowOvertime}
        onChange={e => {
            const next = e.target.checked;
            setAllowOvertime(next);
            if (!next) {
                setFundPercents(prev => scalePercentsToFit(prev, 50));
            }
        }}
        className="w-3.5 h-3.5 cursor-pointer"
    />
    <span className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
        Учитывать подработки
    </span>
</label>
```

- [ ] **Step 9.3: Manual test — overtime toggle**

1. Set sliders to 60% total (only possible after enabling overtime checkbox).
2. Uncheck "Учитывать подработки" — sliders should scale down proportionally to fit 50%.
3. Confirm the cap label switches between "из 50%" and "из 100%".

---

### Task 10: Add overtime line and completion markers to chart

- [ ] **Step 10.1: Import `ReferenceLine` and `Label` from recharts**

Find the recharts import line at the top. Add `ReferenceLine` and `Label`:

```ts
import {
    ResponsiveContainer,
    LineChart,
    Line,
    XAxis,
    YAxis,
    Tooltip,
    Legend,
    ReferenceLine,
    Label,
} from 'recharts';
```

- [ ] **Step 10.2: Add overtime line to `<LineChart>`**

Inside the `<LineChart>` JSX, after the last existing `<Line>`, add:

```tsx
{allowOvertime && (
    <Line
        type="monotone"
        dataKey="Доход + подработки"
        stroke="#6b7280"
        dot={false}
        strokeWidth={1.5}
        strokeDasharray="6 3"
    />
)}
```

- [ ] **Step 10.3: Add completion markers**

Inside `<LineChart>`, after the overtime line, add:

```tsx
{completionLabels.map(({ label, name }) => (
    <ReferenceLine
        key={label}
        x={label}
        stroke="var(--color-border)"
        strokeDasharray="4 4">
        <Label
            value={name}
            position="insideTopRight"
            style={{ fontSize: 10, fill: 'var(--color-text-muted)' }}
        />
    </ReferenceLine>
))}
```

- [ ] **Step 10.4: Manual test — chart features**

1. Set a SAVINGS fund at 10%, fund target = 200k, income ~100k → fund completes ~month 20. Verify vertical dashed line appears at that month.
2. Enable overtime checkbox — verify grey dashed line at 1.5× income appears.
3. Disable — verify line disappears.

---

### Task 11: Final commit

- [ ] **Step 11.1: Run all tests one last time**

```bash
cd frontend && npm test
```

Expected: all tests PASS.

- [ ] **Step 11.2: Commit Chunk 2**

```bash
cd frontend && git add src/components/funds/SavingsStrategySection.tsx && git commit -m "feat: savings planner — freed-up cash sim, 50% cap, overtime checkbox, completion markers"
```

---

## Summary of Changes

| File | What changed |
|------|-------------|
| `frontend/package.json` | Added vitest dev dep + test script |
| `frontend/vite.config.ts` | Added `test: { environment: 'node', globals: true }` |
| `frontend/src/components/funds/savingsStrategyUtils.ts` | **New** — 6 pure functions + types |
| `frontend/src/components/funds/__tests__/savingsStrategyUtils.test.ts` | **New** — 16 unit tests |
| `frontend/src/components/funds/SavingsStrategySection.tsx` | Removed local helpers, wired utils, added overtime checkbox + state, fixed slider logic, added markers |
