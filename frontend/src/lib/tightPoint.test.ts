import { describe, expect, it } from 'vitest';
import { watchdogView } from './tightPoint';
import type { PocketResponse } from '../types/api';

const sp = (s: string) => s.replace(/\s/g, ' ');

/** Срок до 25 нояб.; сторож до 2-го дохода — 25 дек. */
const pocket = (over: Partial<PocketResponse> = {}): PocketResponse => ({
    pocket: 17_000, buffer: 0, horizon: { type: 'NEXT_INCOME', endDate: '2026-11-25', label: '', fallback: false },
    minPoint: { date: '2026-11-24', balance: 17_000, drivenBy: null }, minPointWithForecast: null, ...over,
} as unknown as PocketResponse);
const watchdog = (min: { date: string; balance: number; drivenBy?: string | null }, soft: { date: string; balance: number } | null = null) =>
    ({ minPoint: { drivenBy: null, ...min }, minPointWithForecast: soft ? { ...soft, drivenBy: null } : null } as unknown as PocketResponse);

describe('watchdogView — сторож разрыва и впритык', () => {
    it('разрыв по плану за краем графика: плашка, виновник, «после дохода», глубже с обычными, кнопка 2-го дохода', () => {
        const v = watchdogView(watchdog({ date: '2026-12-24', balance: -13_800, drivenBy: 'Кредитка' }, { date: '2026-12-24', balance: -17_700 }), pocket());
        expect(v?.kind).toBe('plate');
        if (v?.kind !== 'plate') return;
        expect(v.tone).toBe('plan');
        expect(v.title).toBe('Кассовый разрыв — 24 дек.');
        expect(sp(v.body)).toBe('По плану не хватит 13 800 ₽, в этот день — «Кредитка». Это после дохода 25 нояб., за краем графика.');
        expect(sp(v.small!)).toBe('С обычными тратами — глубже: 17 700 ₽.');
        expect(v.showSecond).toBe(true);
    });

    it('впритык в пределах графика — точка на дне с подписью, без плашки', () => {
        const v = watchdogView(watchdog({ date: '2026-12-10', balance: 2_300 }, { date: '2026-11-24', balance: -1_100 }), pocket());
        expect(v?.kind).toBe('dot');
        if (v?.kind !== 'dot') return;
        expect(v.date).toBe('2026-11-24');
        expect(v.lines.map(sp)).toEqual(['с обычными тратами, 24 нояб.', 'может не хватить около 1 100 ₽']);
    });

    it('впритык за краем графика — плашка того же вида со словами впритык и кнопкой 2-го дохода', () => {
        const v = watchdogView(watchdog({ date: '2026-12-10', balance: 2_300 }, { date: '2026-12-20', balance: -1_100 }), pocket());
        expect(v).toMatchObject({ kind: 'plate', tone: 'tight', title: 'Впритык — 20 дек.', showSecond: true });
        if (v?.kind === 'plate') expect(sp(v.body)).toBe('По плану хватает. С обычными тратами может не хватить около 1 100 ₽. Это после дохода 25 нояб., за краем графика.');
    });

    it('тот же разрыв, что называет число, — молчание', () => {
        const p = pocket({ pocket: -1_600, minPoint: { date: '2026-11-24', balance: -1_600, drivenBy: null } });
        expect(watchdogView(watchdog({ date: '2026-11-24', balance: -1_600 }), p)).toBeNull();
    });

    it('сторож молчит или не загрузился — ничего', () => {
        expect(watchdogView(watchdog({ date: '2026-12-10', balance: 2_300 }), pocket())).toBeNull();
        expect(watchdogView(null, pocket())).toBeNull();
    });
});
