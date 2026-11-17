import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { renderToStaticMarkup } from 'react-dom/server';
import { StartSky } from './Start';
import type { PocketResponse, UpcomingItem } from '../types/api';

const item = (date: string, type: 'INCOME' | 'EXPENSE', amount: number, description: string): UpcomingItem => ({
    id: description, date, categoryName: null, amount, description, overdue: false, wishlist: false, priority: 'HIGH', type,
});

/** 17–25 нояб.: остаток 48 800; `pays` — расходы по дням; `salary` — доход 25-го, им кончается срок. */
function pocket({ pays = {}, salary = false }: { pays?: Record<string, [string, number][]>; salary?: boolean }): PocketResponse {
    let balance = 48_800;
    const trajectory = Array.from({ length: 9 }, (_, i) => {
        const date = `2026-11-${17 + i}`;
        const out = (pays[date] ?? []).map(([n, a]) => item(date, 'EXPENSE', a, n));
        const inc = salary && date === '2026-11-25' ? [item(date, 'INCOME', 80_000, 'Зарплата')] : [];
        const expense = out.reduce((s, u) => s + u.amount, 0);
        const income = inc.reduce((s, u) => s + u.amount, 0);
        balance += income - expense;
        return { date, balance, income, expense, balanceWithForecast: null, items: [...inc, ...out] };
    });
    return {
        pocket: 23_000, currentBalance: 48_800, buffer: 0, checkpointDate: '2026-11-17',
        horizon: { type: 'NEXT_INCOME', endDate: '2026-11-25', label: '', fallback: !salary },
        minPoint: { date: '2026-11-24', balance: 23_000, drivenBy: null },
        trajectory, upcoming: [], breakdown: [],
    } as unknown as PocketResponse;
}

const sky = (p: PocketResponse, step: number) =>
    renderToStaticMarkup(<StartSky data={p} step={step} onBack={() => {}} />).replace(/ /g, ' ');

describe('Первый вход — небо (доски S2-W2a, S2-W2d, S2-W-1440)', () => {
    it('точки шагов: текущий — длинный, пройденные — акцентом', () => {
        const html = sky(pocket({}), 3);
        expect(html).toContain('aria-label="Вопрос 3 из 4"');
        expect(html.match(/data-step="done"/g)).toHaveLength(2 * 2);   // 390 и 1440
        expect(html.match(/data-step="current"/g)).toHaveLength(2);
        expect(html.match(/data-step="ahead"/g)).toHaveLength(2);
    });

    it('«Назад» — со второго вопроса', () => {
        expect(sky(pocket({}), 1)).not.toContain('aria-label="Назад"');
        expect(sky(pocket({}), 2)).toContain('aria-label="Назад"');
    });

    it('до вопроса 2 — солнце пунктиром и «пока без плана»', () => {
        const html = sky(pocket({}), 1);
        expect(html).toContain('stroke-dasharray="3 5"');
        expect(html).toContain('Свободно — пока без плана');
    });

    it('доход есть — солнце настоящее, «Свободно до» дня дохода', () => {
        const html = sky(pocket({ salary: true }), 3);
        expect(html).not.toContain('stroke-dasharray="3 5"');
        expect(html).toContain('Свободно до 25 нояб.');
        expect(html).toContain('Зарплата · 25 нояб.');
    });

    it('платежи — флажки на обрывах, на флажке только сумма дня', () => {
        const html = sky(pocket({ salary: true, pays: { '2026-11-19': [['Аренда', 25_000]], '2026-11-21': [['Связь', 500], ['Интернет', 300]] } }), 3);
        expect(html.match(/data-flag="2026-11-19"/g)).toHaveLength(2);
        expect(html.match(/data-flag="2026-11-21"/g)).toHaveLength(2);
        expect(html).toContain('-25 000');
        expect(html).toContain('-800');
        expect(html).not.toMatch(/Аренда|Связь|Интернет/);
    });

    it('без платежей — без флажков', () => {
        expect(sky(pocket({ salary: true }), 3)).not.toContain('data-flag');
    });
});

/** Сторож по исходнику: открытие первого входа — сеть, статический рендер его не видит. */
describe('Первый вход — когда открывается (п. 11)', () => {
    const read = (p: string) => readFileSync(new URL(p, import.meta.url), 'utf8');

    it('«Сегодня» уводит на /start по needsFirstEntry', () => {
        expect(read('./Today.tsx')).toMatch(/needsFirstEntry\(p\)\) \{ navigate\('\/start'/);
    });

    it('человек с данными на /start не остаётся — уходит на «Сегодня»', () => {
        expect(read('./Start.tsx')).toMatch(/if \(needsFirstEntry\(p\)\) setData\(p\); else navigate\('\/', \{ replace: true \}\)/);
    });

    it('/start — без навигации, как вход и согласие', () => {
        expect(read('../App.tsx')).toMatch(/STANDALONE[^}]*'\/start': \(\) => <Start \/>/);
    });
});
