import { describe, expect, it } from 'vitest';
import { buildCells } from './cells';

type P = { date: string; balance: number };
/** Дни подряд с даты, остатки по порядку. */
const days = (from: string, balances: number[]): P[] => balances.map((balance, i) => {
    const [y, m, d] = from.split('-').map(Number);
    const dt = new Date(y, m - 1, d + i);
    const iso = `${dt.getFullYear()}-${String(dt.getMonth() + 1).padStart(2, '0')}-${String(dt.getDate()).padStart(2, '0')}`;
    return { date: iso, balance };
});

describe('buildCells — клетки по дням (Zh2-390b)', () => {
    it('строки — дни недели с понедельника: среда встаёт в третью строку первой колонки', () => {
        const m = buildCells(days('2026-11-11', [10, 20, 30]), '2026-11-13', '2026-11-11');
        expect(m.weeks[0].days[0]).toBeNull();
        expect(m.weeks[0].days[1]).toBeNull();
        expect(m.weeks[0].days[2]?.date).toBe('2026-11-11');
    });

    it('больше остаток — темнее; ниже нуля — вода', () => {
        const m = buildCells(days('2026-11-09', [0, 50_000, 100_000, -5_000]), '2026-11-12', '2026-11-12');
        const [zero, half, full, below] = m.weeks[0].days;
        expect(zero?.level).toBe(1);
        expect(half?.level).toBe(3);
        expect(full?.level).toBe(5);
        expect(below?.level).toBe('water');
    });

    it('отмечены «сегодня» и день самого низкого остатка; хвост за сроком — без клеток', () => {
        const m = buildCells(days('2026-11-09', [30, 10, 20, 5]), '2026-11-11', '2026-11-10');
        const [mon, tue, wed] = m.weeks[0].days;
        expect(mon?.today).toBe(true);
        expect(tue?.low).toBe(true);
        expect(wed?.low).toBe(false);
        expect(m.weeks.flatMap(w => w.days).filter(c => c && c.date > '2026-11-11')).toHaveLength(0);
    });

    it('месяц подписан над колонкой, где он начинается', () => {
        const m = buildCells(days('2026-11-23', Array.from({ length: 14 }, () => 100)), '2026-12-06', '2026-11-23');
        expect(m.weeks[0].month).toBe('нояб.');
        expect(m.weeks[1].month).toBe('дек.');
    });
});
