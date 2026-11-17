import { describe, expect, it } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { Landscape } from './Landscape';
import { Cells } from './Cells';
import type { PocketResponse, UpcomingItem } from '../../types/api';

const salary: UpcomingItem = {
    id: 'inc', date: '2026-11-15', categoryName: 'Доходы', amount: 90_000, description: 'Зарплата',
    overdue: false, wishlist: false, priority: 'HIGH', type: 'INCOME',
};

/** 12–15 нояб., срок кончается зарплатой 15-го. */
function pocket(balances: number[], buffer = 0): PocketResponse {
    const trajectory = balances.map((balance, i) => {
        const date = `2026-11-${12 + i}`;
        const inc = date === '2026-11-15';
        return { date, balance, income: inc ? 90_000 : 0, expense: 0, balanceWithForecast: null, items: inc ? [salary] : [] };
    });
    const min = Math.min(...balances);
    return {
        pocket: 0, currentBalance: balances[0], buffer, checkpointDate: '2026-11-12',
        horizon: { type: 'NEXT_INCOME', endDate: '2026-11-15', label: '', fallback: false },
        minPoint: { date: trajectory[balances.indexOf(min)].date, balance: min, drivenBy: null },
        trajectory, upcoming: [], breakdown: [],
    } as unknown as PocketResponse;
}

describe('Landscape', () => {
    it('склон, солнце с именем дохода, без воды и тумана, когда ниже нуля и ниже НЗ не уходит', () => {
        const html = renderToStaticMarkup(<Landscape data={pocket([48_800, 30_000, 17_000, 107_000])} size="narrow" />);
        expect(html).toContain('fill="var(--sf-slope)"');
        expect(html).toContain('Зарплата · 15 нояб.');
        expect(html).not.toContain('data-layer="water"');
        expect(html).not.toContain('data-layer="mist"');
        expect(html).toContain('data-layer="low"');
    });

    it('ниже нуля — вода; задан НЗ — туман', () => {
        const html = renderToStaticMarkup(<Landscape data={pocket([48_800, -20_000, 5_000, 95_000], 10_000)} size="wide" />);
        expect(html).toContain('data-layer="water"');
        expect(html).toContain('data-layer="mist"');
    });

    it('первый вход до вопроса о доходе: солнце пунктиром, без подписи дохода', () => {
        const html = renderToStaticMarkup(<Landscape data={pocket([48_800, 30_000, 17_000, 107_000])} size="narrow" dashedSun />);
        expect(html).toContain('stroke-dasharray="3 5"');
        expect(html).not.toContain('Зарплата · 15 нояб.');
    });
});

describe('Cells', () => {
    it('клетка на каждый день срока, «сегодня» и самый низкий отмечены, ниже нуля — вода', () => {
        const html = renderToStaticMarkup(<Cells data={pocket([48_800, -20_000, 5_000, 95_000])} size="narrow" />);
        expect(html.match(/data-level=/g)).toHaveLength(4);
        expect(html).toContain('data-level="water"');
        expect(html).toContain('data-mark="today"');
        expect(html).toContain('data-mark="low"');
    });
});
