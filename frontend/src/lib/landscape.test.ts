import { describe, expect, it } from 'vitest';
import { buildLandscape, monotoneCurve, LAND_FRAMES } from './landscape';
import type { PocketResponse, UpcomingItem } from '../types/api';

type Point = PocketResponse['trajectory'][number];

const income = (date: string, amount: number, description: string | null, categoryName: string | null = null): UpcomingItem =>
    ({ id: 'i' + date, date, categoryName, amount, description, overdue: false, wishlist: false, priority: 'HIGH', type: 'INCOME' });

/** Дни с 12.11, остатки по порядку; доход — на дне, где он указан. */
function pocket(balances: number[], opts: {
    horizonEnd?: string; buffer?: number; forecast?: (number | null)[]; incomes?: Record<string, UpcomingItem>;
    minDate?: string; minBalance?: number;
} = {}): PocketResponse {
    const trajectory: Point[] = balances.map((balance, i) => {
        const date = `2026-11-${String(12 + i).padStart(2, '0')}`;
        const inc = opts.incomes?.[date];
        return {
            date, balance, income: inc ? inc.amount : 0, expense: 0,
            balanceWithForecast: opts.forecast ? opts.forecast[i] ?? null : null, items: inc ? [inc] : [],
        };
    });
    const minBalance = opts.minBalance ?? Math.min(...balances);
    return {
        pocket: 0, currentBalance: balances[0], buffer: opts.buffer ?? 0, checkpointDate: '2026-11-12',
        horizon: { type: 'NEXT_INCOME', endDate: opts.horizonEnd ?? trajectory[trajectory.length - 1].date, label: '', fallback: false },
        minPoint: { date: opts.minDate ?? trajectory[balances.indexOf(minBalance)].date, balance: minBalance, drivenBy: null },
        minPointWithForecast: null, breakdown: [], trajectory, wishlistCandidates: [], upcoming: [],
        pocketAfterCreditRestore: null, pocketWithDeposits: null, pocketWithForecast: null, planHasExpectations: true,
    } as unknown as PocketResponse;
}

const N = LAND_FRAMES.narrow;
const W = LAND_FRAMES.wide;

describe('buildLandscape — шкала от нуля', () => {
    it('ниже нуля нет — воды нет, ноль на своём месте, склон над ним', () => {
        const m = buildLandscape(pocket([48_800, 30_000, 17_000]), 'narrow');
        expect(m.water).toBe(false);
        expect(m.zeroY).toBe(N.zeroBase);
        expect(m.points.every(p => p.y <= m.zeroY)).toBe(true);
        expect(m.slopePath.startsWith(`M0,${N.h} L0,`)).toBe(true);
    });

    it('ниже нуля — вода, самая глубокая точка у нижней кромки', () => {
        const m = buildLandscape(pocket([48_800, -287_012, 10_000]), 'narrow');
        expect(m.water).toBe(true);
        expect(m.zeroY).toBeLessThan(N.zeroBase);
        expect(m.points[1].y).toBeCloseTo(N.floorY, 5);
        expect(m.points[0].y).toBeCloseTo(N.topY, 5);
    });

    it('НЗ задан — туман от уровня НЗ до нуля; НЗ нет — тумана нет', () => {
        const withNz = buildLandscape(pocket([48_800, 17_000], { buffer: 10_000 }), 'wide');
        expect(withNz.mistY).not.toBeNull();
        expect(withNz.mistY!).toBeLessThan(withNz.zeroY);
        expect(withNz.mistY!).toBeCloseTo(withNz.yOf(10_000), 5);
        expect(buildLandscape(pocket([48_800, 17_000]), 'wide').mistY).toBeNull();
    });

    it('тёмный слой — только когда у точек есть остаток с обычными тратами', () => {
        expect(buildLandscape(pocket([48_800, 17_000]), 'narrow').usualPath).toBeNull();
        const m = buildLandscape(pocket([48_800, 17_000], { forecast: [48_800, 15_900] }), 'narrow');
        expect(m.usualPath).not.toBeNull();
    });
});

describe('buildLandscape — срок, солнце, дымка', () => {
    const salary = income('2026-11-16', 80_000, 'Зарплата');

    it('дни срока — от левого поля до правого; день дохода и хвост — за правым полем, под дымкой', () => {
        const m = buildLandscape(pocket([48_800, 30_000, 25_000, 17_000, 97_000, 96_000], {
            horizonEnd: '2026-11-16', incomes: { '2026-11-16': salary },
        }), 'wide');
        expect(m.points[0].x).toBe(W.x0);
        expect(m.points[3].x).toBe(W.x1);
        expect(m.points[4].x).toBeGreaterThan(W.x1);
        expect(m.points[5].x).toBeLessThanOrEqual(W.w);
        expect(m.hazeX).toBe(W.x1);
    });

    it('солнце — у дохода конца срока, подпись — имя и дата (ANO-96)', () => {
        const m = buildLandscape(pocket([48_800, 17_000, 97_000], {
            horizonEnd: '2026-11-14', incomes: { '2026-11-14': income('2026-11-14', 80_000, 'Зарплата') },
        }), 'narrow');
        expect(m.sun).not.toBeNull();
        expect(m.sun!.caption).toBe('Зарплата · 14 нояб.');
    });

    it('без описания солнце называет категорию', () => {
        const m = buildLandscape(pocket([48_800, 17_000, 97_000], {
            horizonEnd: '2026-11-14', incomes: { '2026-11-14': income('2026-11-14', 80_000, null, 'Подработка') },
        }), 'narrow');
        expect(m.sun!.caption).toBe('Подработка · 14 нояб.');
    });

    it('срок кончается не доходом — солнца нет', () => {
        expect(buildLandscape(pocket([48_800, 17_000, 16_000]), 'narrow').sun).toBeNull();
    });

    it('мелкий доход раньше срока — маленькое солнце без подписи', () => {
        const m = buildLandscape(pocket([48_800, 53_800, 40_000, 97_000], {
            horizonEnd: '2026-11-15',
            incomes: { '2026-11-13': income('2026-11-13', 5_000, 'Возврат долга'), '2026-11-15': income('2026-11-15', 80_000, 'Зарплата') },
        }), 'narrow');
        expect(m.smallSuns).toHaveLength(1);
        expect(m.smallSuns[0].x).toBe(m.points[1].x);
        expect(m.sun!.caption).toBe('Зарплата · 15 нояб.');
    });
});

describe('buildLandscape — точки и даты', () => {
    it('«сегодня» — первая точка, самый низкий остаток — у его даты', () => {
        const m = buildLandscape(pocket([48_800, 30_000, 17_000, 20_000]), 'wide');
        expect(m.today).toMatchObject({ x: W.x0, y: m.points[0].y });
        expect(m.low).toMatchObject({ x: m.points[2].x, y: m.yOf(17_000) });
    });

    it('даты: «сегодня» слева, последний день срока — справа, не больше, чем влезает', () => {
        const days = Array.from({ length: 13 }, (_, i) => 48_800 - i * 2_000);
        const narrow = buildLandscape(pocket(days), 'narrow');
        expect(narrow.labels[0]).toEqual({ x: N.x0, text: 'сегодня', anchor: 'start' });
        expect(narrow.labels[narrow.labels.length - 1]).toEqual({ x: N.x1, text: '24 нояб.', anchor: 'end' });
        expect(narrow.labels.length).toBeLessThanOrEqual(N.maxLabels);
        expect(buildLandscape(pocket(days), 'wide').labels.length).toBeLessThanOrEqual(W.maxLabels);
    });
});

describe('monotoneCurve', () => {
    it('обрыв не даёт горбов: опорные точки не выходят за соседние значения', () => {
        const d = monotoneCurve([[0, 10], [10, 10], [20, 50], [30, 50]]);
        const ys = [...d.matchAll(/C[\d.,\s-]+/g)].flatMap(m => m[0].slice(1).trim().split(/\s+/).map(p => Number(p.split(',')[1])));
        expect(Math.min(...ys)).toBeGreaterThanOrEqual(10);
        expect(Math.max(...ys)).toBeLessThanOrEqual(50);
    });
});
