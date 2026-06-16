import type { MonthDelta } from '../../types/api';

export interface BaselinePoint { account: number; capital: number; }
export interface ActiveItem { active: boolean; delta: MonthDelta[]; }

/**
 * Накладывает delta всех активных items на baseline. Delta трактуется как поток (flow),
 * применяемый начиная с monthIndex и накапливающийся вперёд — зеркалит backend applyDeltas.
 */
export function composeTimeline(baseline: BaselinePoint[], items: ActiveItem[]): BaselinePoint[] {
    const accountCum = new Array(baseline.length).fill(0);
    const capitalCum = new Array(baseline.length).fill(0);
    for (const item of items) {
        if (!item.active) continue;
        for (const d of item.delta) {
            for (let i = d.monthIndex; i < baseline.length; i++) {
                accountCum[i] += d.accountDelta;
                capitalCum[i] += d.capitalDelta;
            }
        }
    }
    return baseline.map((p, i) => ({
        account: p.account + accountCum[i],
        capital: p.capital + capitalCum[i],
    }));
}

/** Линейно масштабирует delta по отношению override/base (для слайдера суммы хотелки). */
export function scaleDelta(delta: MonthDelta[], baseAmount: number, override: number): MonthDelta[] {
    if (baseAmount === 0) return delta;
    const k = override / baseAmount;
    return delta.map(d => ({
        ...d,
        accountDelta: d.accountDelta * k,
        capitalDelta: d.capitalDelta * k,
        fundDelta: d.fundDelta != null ? d.fundDelta * k : d.fundDelta,
        liabilityDelta: d.liabilityDelta != null ? d.liabilityDelta * k : d.liabilityDelta,
    }));
}

export type RiskLevel = 'green' | 'yellow' | 'red';

export function riskZones(
    points: BaselinePoint[],
    thresholds: { capitalThresholdRub: number | null; cashBufferMonths: number },
    monthlyExpensesAvg: number,
): RiskLevel[] {
    const buffer = monthlyExpensesAvg * thresholds.cashBufferMonths;
    return points.map(p => {
        const accountRisk: RiskLevel =
            p.account < 0 ? 'red' : p.account < buffer ? 'yellow' : 'green';
        let capitalRisk: RiskLevel = 'green';
        if (thresholds.capitalThresholdRub != null) {
            const t = thresholds.capitalThresholdRub;
            capitalRisk = p.capital < t ? 'red' : p.capital < t * 1.1 ? 'yellow' : 'green';
        }
        return worse(accountRisk, capitalRisk);
    });
}

function worse(a: RiskLevel, b: RiskLevel): RiskLevel {
    const rank = { green: 0, yellow: 1, red: 2 };
    return rank[a] >= rank[b] ? a : b;
}
