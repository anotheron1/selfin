import { describe, expect, it } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { MovePlate, WatchdogPlate } from './GapPlate';
import { TightDot } from './Landscape';
import { buildLandscape } from '../../lib/landscape';
import type { GapMode } from '../../lib/gapMode';
import type { PocketResponse, UpcomingItem } from '../../types/api';

const sp = (s: string) => s.replace(/\s/g, ' ');
const RED = /danger|destructive|sf-error|#EF4444|rgba\(239,\s*68/i;

const plan = {
    kind: 'plate', tone: 'plan', title: 'Кассовый разрыв — 24 дек.',
    body: 'По плану не хватит 13 800 ₽, в этот день — «Кредитка». Это после дохода 25 нояб., за краем графика.',
    small: 'С обычными тратами — глубже: 17 700 ₽.', showSecond: true,
} as const;

const item = (id: string, date: string, amount: number, wishlist = false): UpcomingItem =>
    ({ id, date, categoryName: 'Дом', amount, description: null, overdue: false, wishlist, priority: 'MEDIUM', type: 'EXPENSE' });

const gap = {
    horizonLabel: '', headline: '', lead: '', shortfall: 13_800, subline: null, moveHeader: '',
    movable: [
        { item: item('r1', '2026-12-05', 40_000), label: 'Ремонт', outcome: 'разрыва не будет', text: '' },
        { item: item('w1', '2026-12-19', 12_000, true), label: 'Подарки', outcome: 'не хватит 5 500 ₽', text: '' },
    ],
} as GapMode;

describe('WatchdogPlate — Zh2-G7a', () => {
    it('песочная плашка, без красного и «!»; виновник, глубже с обычными, кнопка 2-го дохода', () => {
        const html = renderToStaticMarkup(<WatchdogPlate view={plan} onShowSecond={() => {}} />);
        expect(html).toContain('bg-sf-plate');
        expect(html).not.toMatch(RED);
        expect(html).not.toContain('!');
        expect(html).toContain('«Кредитка»');
        expect(html).toContain('С обычными тратами — глубже');
        expect(html).toContain('Показать до 2-го дохода');
    });

    it('в пределах графика кнопки 2-го дохода нет', () => {
        const html = renderToStaticMarkup(<WatchdogPlate view={{ ...plan, showSecond: false }} onShowSecond={() => {}} />);
        expect(html).not.toContain('Показать до 2-го дохода');
    });
});

describe('MovePlate — Zh2-G7b', () => {
    it('заголовок со сроком, брони не двигаются, у каждой строки «Сдвинуть», внизу «Обновить»', () => {
        const html = sp(renderToStaticMarkup(<MovePlate gap={gap} horizonEnd="2026-12-25" onReanchor={() => {}} />));
        expect(html).toContain('Можно сдвинуть на после 25 дек.');
        expect(html).toContain('Брони не двигаются — их дата известна заранее.');
        expect(html).toContain('Ремонт <span class="text-sf-muted">· 5 дек. · 40 000 ₽</span>');
        expect(html).toContain('→ разрыва не будет');
        expect([...html.matchAll(/aria-label="Сдвинуть: ([^"]+)"/g)].map(m => m[1])).toEqual([
            'Ремонт на после 25 дек.', 'Подарки на после 25 дек.',
        ]);
        expect(html).toContain('>Обновить<');
        expect(html).not.toMatch(RED);
    });
});

describe('TightDot — S2-T3, S2-T2', () => {
    const p = {
        buffer: 0, horizon: { type: 'NEXT_INCOME', endDate: '2026-11-14', label: '', fallback: false },
        minPoint: { date: '2026-11-13', balance: 2_300, drivenBy: null },
        trajectory: [
            { date: '2026-11-12', balance: 10_000, income: 0, expense: 0, balanceWithForecast: 9_000, items: [] },
            { date: '2026-11-13', balance: 2_300, income: 0, expense: 0, balanceWithForecast: -1_100, items: [] },
            { date: '2026-11-14', balance: 5_000, income: 0, expense: 0, balanceWithForecast: 1_000, items: [] },
        ],
    } as unknown as PocketResponse;
    const m = buildLandscape(p, 'narrow');
    const lines: [string, string] = ['с обычными тратами, 13 нояб.', 'может не хватить около 1 100 ₽'];

    it('по умолчанию — только точка на тёмном слое, без подписи', () => {
        const html = renderToStaticMarkup(<svg><TightDot m={m} date="2026-11-13" lines={lines} open={false} onToggle={() => {}} /></svg>);
        expect(html).toContain(`cy="${m.points[1].yUsual}"`);
        expect(html).toContain('aria-expanded="false"');
        expect(html).not.toContain('может не хватить около 1 100 ₽</text>');
    });

    it('нажали — подпись в две строки', () => {
        const html = renderToStaticMarkup(<svg><TightDot m={m} date="2026-11-13" lines={lines} open onToggle={() => {}} /></svg>);
        expect(html).toContain('aria-expanded="true"');
        expect(html).toContain('>с обычными тратами, 13 нояб.</text>');
        expect(html).toContain('>может не хватить около 1 100 ₽</text>');
    });

    it('дня нет на графике — ничего', () => {
        expect(renderToStaticMarkup(<svg><TightDot m={m} date="2026-12-20" lines={lines} open onToggle={() => {}} /></svg>)).toBe('<svg></svg>');
    });
});
