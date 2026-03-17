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
 *
 * Precondition: current values should sum to at most `cap` before calling.
 * Use scalePercentsToFit() first when the cap decreases (e.g. overtime toggle-off).
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
