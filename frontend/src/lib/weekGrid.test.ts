import { describe, expect, it } from 'vitest';
import type { FinancialEvent } from '../types/api';
import { expectationGroups } from './journalSections';
import { weekGrid } from './weekGrid';

const ev = (over: Partial<FinancialEvent> = {}): FinancialEvent => ({
    id: 'e1', date: '2026-11-09', categoryId: 'food', categoryName: 'Продукты', type: 'EXPENSE',
    plannedAmount: 8000, factAmount: null, status: 'PLANNED', priority: 'MEDIUM', description: null,
    rawInput: null, createdAt: '2026-11-01T10:00:00', eventKind: 'PLAN', parentEventId: null,
    linkedFactsCount: 0, linkedFactsAmount: null, parentPlanDescription: null, ...over,
});

const grid = (events: FinancialEvent[], year: number, month: number, today: string) =>
    weekGrid(expectationGroups(events, year, month, today), year, month, today);

describe('сетка недель — 1440 (срез 3, п. 11; доска S3-W1d)', () => {
    // Ноябрь 2026: 1-е — воскресенье, 30-е — понедельник; сегодня — чт 12 нояб.
    const TODAY = '2026-11-12';
    const weekly = ['2026-11-02', '2026-11-09', '2026-11-16', '2026-11-23', '2026-11-30']
        .map((date, i) => ev({ id: `w${i}`, date }));

    it('недели с понедельника; неделя через границу месяца — «30–6», в ней строка 30-го; пустой край — не столбец', () => {
        const g = grid(weekly, 2026, 10, TODAY);
        expect(g.weeks.map((w) => w.label)).toEqual(['2–8', '9–15', '16–22', '23–29', '30–6']);
        expect(g.weeks.map((w) => w.current)).toEqual([false, true, false, false, false]);
        expect(g.rows[0].cells[4]?.lines.map((e) => e.id)).toEqual(['w4']);
    });

    it('месяц с 1-м в среду: неполная первая неделя — столбец, только если в ней есть строка', () => {
        // Апрель 2026: 1-е — среда.
        const empty = grid([ev({ id: 'a', date: '2026-04-08' })], 2026, 3, '2026-04-08');
        expect(empty.weeks.map((w) => w.label)).toEqual(['6–12', '13–19', '20–26']);
        const early = grid([ev({ id: 'a', date: '2026-04-02' })], 2026, 3, '2026-04-08');
        expect(early.weeks.map((w) => w.label)).toEqual(['30–5', '6–12', '13–19', '20–26']);
        expect(early.rows[0].cells[0]?.lines.map((e) => e.id)).toEqual(['a']);
    });

    it('разовое — одна клетка в своей неделе', () => {
        const g = grid([...weekly, ev({ id: 'car', date: '2026-11-20', categoryId: 'car', categoryName: 'Ремонт машины', plannedAmount: 15000 })],
            2026, 10, TODAY);
        expect(g.rows[1].cells.map((c) => (c ? c.look : null))).toEqual([null, null, 'dash', null, null]);
    });

    it('клетка: заливка с записанным, пунктир с суммой у строки впереди, прошедшая без записей — пустая', () => {
        const g = grid([
            ev({ id: 'past', date: '2026-11-02', linkedFactsCount: 3, linkedFactsAmount: 8650 }),
            ev({ id: 'now', date: '2026-11-09', linkedFactsCount: 3, linkedFactsAmount: 4540 }),
            ev({ id: 'next', date: '2026-11-16' }),
            ev({ id: 'cafe', date: '2026-11-03', categoryId: 'cafe', categoryName: 'Кафе', plannedAmount: 2000 }),
        ], 2026, 10, TODAY);
        expect(g.rows[0].cells.slice(0, 3).map((c) => [c?.look, c?.value])).toEqual([['fill', 8650], ['fill', 4540], ['dash', 8000]]);
        expect(g.rows[1].cells[0]).toMatchObject({ look: 'blank' });
    });

    it('нажата по умолчанию — клетка текущей недели первого ожидания; нет её — первая клетка первого', () => {
        expect(grid(weekly, 2026, 10, TODAY).selected).toEqual({ row: 0, col: 1 });
        expect(grid([ev({ id: 'a', date: '2026-11-20' })], 2026, 10, TODAY).selected).toEqual({ row: 0, col: 2 });
        expect(grid([], 2026, 10, TODAY).selected).toBeNull();
    });
});
