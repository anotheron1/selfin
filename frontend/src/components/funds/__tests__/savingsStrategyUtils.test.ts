import { describe, it, expect } from 'vitest';
import {
    rebalancePercents,
    scalePercentsToFit,
    maxPercent,
    buildChartData,
    calcPMT,
    calcPlannerStats,
} from '../savingsStrategyUtils';
import type { FundPlannerMonth, TargetFund } from '../../../types/api';

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

// ─── buildChartData ───────────────────────────────────────────────────────────

const makeMonth = (yearMonth: string, income = 100000, mandatory = 40000, allExpenses = 60000, factExpenses: number | null = null): FundPlannerMonth => ({
    yearMonth,
    plannedIncome: income,
    mandatoryExpenses: mandatory,
    allPlannedExpenses: allExpenses,
    factExpenses,
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
        const { chartData } = buildChartData(months, funds, { a: 0 });
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
        const { chartData, completionLabels } = buildChartData(months, funds, { a: 10 });

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
        const { chartData } = buildChartData(months, funds, { a: 10 });
        // month 2: allExpenses(60000) + 5000 partial = 65000
        expect(chartData[1]['Расходы + копилки']).toBe(65000);
        // month 3: complete, 0
        expect(chartData[2]['Расходы + копилки']).toBe(60000);
    });

    it('null-target fund contributes forever', () => {
        const months = [makeMonth('2026-03'), makeMonth('2026-04'), makeMonth('2026-05')];
        const funds = [makeSavingsFund('a', 'Карман', null)];
        const { chartData, completionLabels } = buildChartData(months, funds, { a: 10 });
        expect(chartData[0]['Расходы + копилки']).toBe(70000);
        expect(chartData[2]['Расходы + копилки']).toBe(70000);
        expect(completionLabels).toHaveLength(0);
    });

    it('credit fund does NOT contribute to chart line — PMT is informational only', () => {
        const months = [makeMonth('2026-03'), makeMonth('2026-04'), makeMonth('2026-05')];
        const creditFund = makeCreditFund('b', 'Машина', 120000, 0, 2);
        const { chartData } = buildChartData(months, [creditFund], { b: 0 });
        // CREDIT не добавляет в chart — линия = allExpenses всегда
        expect(chartData[0]['Расходы + копилки']).toBe(60000);
        expect(chartData[1]['Расходы + копилки']).toBe(60000);
        expect(chartData[2]['Расходы + копилки']).toBe(60000);
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
        const { chartData } = buildChartData(months, [incompleteCreditFund], { c: 0 });
        expect(chartData[0]['Расходы + копилки']).toBe(60000); // allExpenses only, no PMT added
    });

    it('populates Факт расходы for month 0 when factExpenses is set', () => {
        const months = [
            makeMonth('2026-01', 100000, 30000, 60000, 50000), // factExpenses = 50000
            makeMonth('2026-02', 100000, 30000, 60000, null),
            makeMonth('2026-03', 100000, 30000, 60000, null),
        ];
        const funds: TargetFund[] = [];
        const percents: Record<string, number> = {};
        const { chartData } = buildChartData(months, funds, percents);
        expect(chartData[0]['Факт расходы']).toBe(50000);
        expect(chartData[1]['Факт расходы']).toBeUndefined();
    });
});

// ─── calcPlannerStats ─────────────────────────────────────────────────────────

describe('calcPlannerStats', () => {
    const makeMonth = (
        plannedIncome: number,
        mandatoryExpenses: number,
        allPlannedExpenses: number,
        yearMonth = '2026-03',
    ): FundPlannerMonth => ({
        yearMonth,
        plannedIncome,
        mandatoryExpenses,
        allPlannedExpenses,
        factExpenses: null,
    });

    it('returns zeros when no active months', () => {
        const stats = calcPlannerStats([]);
        expect(stats.avgIncome).toBe(0);
        expect(stats.avgAfterMandatory).toBe(0);
        expect(stats.avgAfterAll).toBe(0);
        expect(stats.minAfterMandatory).toBe(null);
        expect(stats.minAfterAll).toBe(null);
    });

    it('excludes months with zero income from calculations', () => {
        const months = [
            makeMonth(100000, 30000, 50000, '2026-03'),
            makeMonth(0, 0, 0, '2026-04'),           // zero — excluded
            makeMonth(80000, 20000, 40000, '2026-05'),
        ];
        const stats = calcPlannerStats(months);
        // avgIncome: (100000 + 80000) / 2 = 90000
        expect(stats.avgIncome).toBe(90000);
        // avgAfterAll: ((100000-50000) + (80000-40000)) / 2 = (50000+40000)/2 = 45000
        expect(stats.avgAfterAll).toBe(45000);
    });

    it('computes avgAfterMandatory correctly', () => {
        const months = [
            makeMonth(100000, 60000, 80000, '2026-03'),
            makeMonth(100000, 40000, 70000, '2026-04'),
        ];
        const stats = calcPlannerStats(months);
        // afterMandatory: [40000, 60000] → avg 50000
        expect(stats.avgAfterMandatory).toBe(50000);
    });

    it('finds minimum month for afterAll', () => {
        const months = [
            makeMonth(100000, 30000, 50000, '2026-03'),
            makeMonth(100000, 30000, 90000, '2026-08'), // worst
        ];
        const stats = calcPlannerStats(months);
        expect(stats.minAfterAll).not.toBeNull();
        expect(stats.minAfterAll!.value).toBe(10000);     // 100000 - 90000
        expect(stats.minAfterAll!.label).toBe('авг 26');
    });

    it('returns null min when min equals avg', () => {
        const months = [makeMonth(100000, 30000, 50000, '2026-03')];
        const stats = calcPlannerStats(months);
        // min === avg → return null to suppress display
        expect(stats.minAfterAll).toBeNull();
        expect(stats.minAfterMandatory).toBeNull();
    });

    it('handles negative remainder (expenses exceed income)', () => {
        const months = [
            makeMonth(50000, 60000, 70000, '2026-03'),
        ];
        const stats = calcPlannerStats(months);
        expect(stats.avgAfterMandatory).toBe(-10000);
        expect(stats.avgAfterAll).toBe(-20000);
    });
});
