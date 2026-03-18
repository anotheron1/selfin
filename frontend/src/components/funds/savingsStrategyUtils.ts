import type { FundPlannerMonth, PurchaseType, TargetFund } from '../../types/api';

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
    const shortMonth = date.toLocaleDateString('ru-RU', { month: 'short' }).replace(/\./g, '');
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
    'Факт расходы'?: number;
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
 *
 * Precondition: current values should sum to at most `cap` before calling.
 * Use scalePercentsToFit() first when the cap decreases.
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
 * - CREDIT funds / null-target funds: returns globalCap.
 * - SAVINGS funds with targetAmount: returns min(globalCap, ceil(targetAmount/avgIncome*100)).
 *   When fundMax < globalCap, this prevents the slider from going above single-month payoff.
 *   When fundMax >= globalCap, the function just returns globalCap.
 */
export function maxPercent(
    targetAmount: number | null | undefined,
    purchaseType: PurchaseType,
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
 * Runs the savings simulation over the provided months array.
 *
 * Rules:
 * - SAVINGS funds: contribute monthly = income * percent / 100, capped at remaining target.
 *   When accumulated >= targetAmount the fund completes and contributions stop.
 *   Funds with targetAmount = null contribute forever (freed-up cash never appears).
 * - CREDIT funds: skipped — PMT is informational only, shown on the fund card.
 * - Returns chart data + labels for completed funds (for ReferenceLine markers).
 */
export function buildChartData(
    months: FundPlannerMonth[],
    funds: TargetFund[],
    fundPercents: Record<string, number>,
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
            // CREDIT funds: PMT is informational only (shown on the fund card), not added to the chart line
            if (f.purchaseType === 'CREDIT') continue;

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

        // For the current month (idx=0): add actual amounts already incurred so that
        // each line shows the projected month-end total (fact-to-date + remaining plan).
        const expenseOffset = idx === 0 ? Math.round(m.factExpenses ?? 0) : 0;
        const incomeOffset  = idx === 0 ? Math.round(m.factIncome  ?? 0) : 0;

        const point: ChartPoint = {
            label: fmtYearMonth(m.yearMonth),
            'Доход': Math.round(m.plannedIncome + incomeOffset),
            'Обяз. расходы': Math.round(m.mandatoryExpenses + expenseOffset),
            'Все расходы': Math.round(m.allPlannedExpenses + expenseOffset),
            'Расходы + копилки': Math.round(m.allPlannedExpenses + totalContribution + expenseOffset),
        };

        if (idx === 0 && m.factExpenses != null && m.factExpenses > 0) {
            point['Факт расходы'] = Math.round(m.factExpenses);
        }

        return point;
    });

    return { chartData, completionLabels };
}

// ─── Planner stats ────────────────────────────────────────────────────────────

export type MinPoint = { value: number; label: string };

export type PlannerStats = {
    /** Average planned income across active months (plannedIncome > 0) */
    avgIncome: number;
    /** Average (income − mandatory expenses) across active months */
    avgAfterMandatory: number;
    /** Average (income − all planned expenses) across active months */
    avgAfterAll: number;
    /** Worst month for (income − mandatory). null if equals avg (suppress display). */
    minAfterMandatory: MinPoint | null;
    /** Worst month for (income − all). null if equals avg (suppress display). */
    minAfterAll: MinPoint | null;
};

/**
 * Computes summary statistics for the planner summary bar.
 * Only months with plannedIncome > 0 are included (active horizon).
 * If min equals avg, minAfterX is returned as null (no parenthetical shown).
 */
export function calcPlannerStats(months: FundPlannerMonth[]): PlannerStats {
    const active = months.filter(m => m.plannedIncome > 0);

    if (active.length === 0) {
        return { avgIncome: 0, avgAfterMandatory: 0, avgAfterAll: 0, minAfterMandatory: null, minAfterAll: null };
    }

    const sum = (arr: number[]) => arr.reduce((s, v) => s + v, 0);
    const avg = (arr: number[]) => Math.round(sum(arr) / arr.length);

    const incomes = active.map(m => m.plannedIncome);
    const afterMandatory = active.map(m => m.plannedIncome - m.mandatoryExpenses);
    const afterAll = active.map(m => m.plannedIncome - m.allPlannedExpenses);

    const avgIncome = avg(incomes);
    const avgAfterMandatory = avg(afterMandatory);
    const avgAfterAll = avg(afterAll);

    const findMin = (values: number[]): MinPoint | null => {
        const minVal = Math.round(Math.min(...values));
        const avgVal = Math.round(sum(values) / values.length);
        if (minVal === avgVal) return null; // suppress display
        const idx = values.findIndex(v => Math.round(v) === minVal);
        return { value: minVal, label: fmtYearMonth(active[idx].yearMonth) };
    };

    return {
        avgIncome,
        avgAfterMandatory,
        avgAfterAll,
        minAfterMandatory: findMin(afterMandatory),
        minAfterAll: findMin(afterAll),
    };
}
