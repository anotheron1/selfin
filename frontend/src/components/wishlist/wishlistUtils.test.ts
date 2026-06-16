import { describe, it, expect } from 'vitest';
import { composeTimeline, scaleDelta } from './wishlistUtils';
import type { MonthDelta } from '../../types/api';

const baseline = [
    { account: 100000, capital: 500000 },
    { account: 90000, capital: 500000 },
    { account: 80000, capital: 500000 },
]; // index 0 = current+1

describe('composeTimeline', () => {
    it('returns baseline when no active items', () => {
        const out = composeTimeline(baseline, []);
        expect(out).toEqual(baseline);
    });

    it('applies a single delta cumulatively from its month', () => {
        const delta: MonthDelta[] = [{ monthIndex: 1, accountDelta: -50000, capitalDelta: -50000 }];
        const out = composeTimeline(baseline, [{ active: true, delta }]);
        expect(out[0].account).toBe(100000);          // before
        expect(out[1].account).toBe(40000);           // 90000 - 50000
        expect(out[2].account).toBe(30000);           // 80000 - 50000 (cumulative)
    });

    it('excludes disabled items', () => {
        const delta: MonthDelta[] = [{ monthIndex: 0, accountDelta: -50000, capitalDelta: 0 }];
        const out = composeTimeline(baseline, [{ active: false, delta }]);
        expect(out).toEqual(baseline);
    });

    it('sums multiple items in the same month', () => {
        const a: MonthDelta[] = [{ monthIndex: 0, accountDelta: -10000, capitalDelta: 0 }];
        const b: MonthDelta[] = [{ monthIndex: 0, accountDelta: -20000, capitalDelta: 0 }];
        const out = composeTimeline(baseline, [{ active: true, delta: a }, { active: true, delta: b }]);
        expect(out[0].account).toBe(70000);   // 100000 - 30000
    });
});

describe('scaleDelta', () => {
    it('scales linearly by amount ratio', () => {
        const delta: MonthDelta[] = [{ monthIndex: 0, accountDelta: -100000, capitalDelta: -100000 }];
        const scaled = scaleDelta(delta, 100000, 150000);  // baseAmount=100k, override=150k
        expect(scaled[0].accountDelta).toBe(-150000);
        expect(scaled[0].capitalDelta).toBe(-150000);
    });
});
