import { describe, expect, it } from 'vitest';
import { fmtRub } from './format';
import {
    buildDayDetails,
    buildLinePoints,
    buildMinAnnotation,
    computeDomain,
    pickTicks,
    showBufferZone,
    showDangerZone,
    type TrajPoint,
} from './trajectoryChart';

const pt = (date: string, balance: number, income = 0, expense = 0): TrajPoint =>
    ({ date, balance, income, expense });

describe('computeDomain', () => {
    it('всегда включает ноль снизу', () => {
        expect(computeDomain([100, 500])).toEqual({ min: 0, max: 500 });
    });
    it('всегда включает ноль сверху: вся траектория под водой', () => {
        expect(computeDomain([-5000, -100])).toEqual({ min: -5000, max: 0 });
    });
    it('смешанная траектория — крайние значения', () => {
        expect(computeDomain([-2000, 3000])).toEqual({ min: -2000, max: 3000 });
    });
    it('вырожденный случай (все нули) не даёт нулевую высоту домена', () => {
        const d = computeDomain([0, 0]);
        expect(d.max).toBeGreaterThan(d.min);
    });
});

describe('зоны появляются только когда должны', () => {
    it('красная зона разрыва — только при отрицательном минимуме домена', () => {
        expect(showDangerZone({ min: -1, max: 100 })).toBe(true);
        expect(showDangerZone({ min: 0, max: 100 })).toBe(false);
    });
    it('янтарная зона буфера — только при buffer > 0', () => {
        expect(showBufferZone(5000)).toBe(true);
        expect(showBufferZone(0)).toBe(false);
    });
});

describe('pickTicks', () => {
    const traj: TrajPoint[] = [
        pt('2026-07-10', 50000),
        pt('2026-07-11', 48000),
        pt('2026-07-12', 26000),
        pt('2026-07-13', 24000),
        pt('2026-07-14', 22000),
        pt('2026-07-15', 20000),
        pt('2026-07-16', 18000),
        pt('2026-07-17', 15000),
        pt('2026-07-18', 6800),
        pt('2026-07-19', 6800),
        pt('2026-07-20', 51800, 45000),
        pt('2026-07-21', 50000),
        pt('2026-07-22', 48000),
        pt('2026-07-23', 47000),
        pt('2026-07-24', 45000),
        pt('2026-07-25', 80000, 35000),
    ];

    it('всегда содержит сегодня, конец горизонта и день минимума', () => {
        const ticks = pickTicks(traj, '2026-07-18');
        expect(ticks).toContain(0);
        expect(ticks).toContain(15);
        expect(ticks).toContain(8);
    });
    it('дни доходов попадают, если не липнут к уже выбранным', () => {
        expect(pickTicks(traj, '2026-07-18')).toContain(10);
    });
    it('день дохода вплотную к минимуму отбрасывается (коллизия)', () => {
        const t = traj.map((p, i) => (i === 9 ? pt(p.date, p.balance, 1000) : p));
        const ticks = pickTicks(t, '2026-07-18');
        expect(ticks).not.toContain(9);
    });
    it('однодневная траектория — единственный тик', () => {
        expect(pickTicks([pt('2026-07-10', 100)], '2026-07-10')).toEqual([0]);
    });
    it('результат отсортирован и без дублей', () => {
        const ticks = pickTicks(traj, '2026-07-10'); // минимум = день 0
        expect(ticks).toEqual([...new Set(ticks)].sort((a, b) => a - b));
    });
});

describe('buildLinePoints / makeScales', () => {
    it('маппит точки в координаты polyline', () => {
        // 3 точки, w=100, padX=0, top=0, floor=100, домен 0..100: x = 0/50/100, y инвертирован
        const points = buildLinePoints([0, 50, 100], 100, 0, 0, 100, { min: 0, max: 100 });
        expect(points).toBe('0,100 50,50 100,0');
    });
    it('одна точка не делит на ноль', () => {
        expect(buildLinePoints([42], 100, 0, 0, 100, { min: 0, max: 100 })).toBe('50,58');
    });
    it('асимметричные поля top/floor учитываются', () => {
        // top=30, floor=190: 0 → y=190, 100 → y=30
        expect(buildLinePoints([0, 100], 100, 10, 30, 190, { min: 0, max: 100 }))
            .toBe('10,190 90,30');
    });
});

describe('buildMinAnnotation', () => {
    it('с виновником', () => {
        expect(buildMinAnnotation({ date: '2026-07-18', balance: 6800, drivenBy: 'Страховка' }))
            .toBe(`мин 18.07 · ${fmtRub(6800)} · Страховка`);
    });
    it('без виновника — хвост опущен', () => {
        expect(buildMinAnnotation({ date: '2026-07-18', balance: 6800, drivenBy: null }))
            .toBe(`мин 18.07 · ${fmtRub(6800)}`);
    });
    it('длинный виновник обрезается, чтобы не вылезать за viewBox', () => {
        const long = 'Страховка автомобиля КАСКО продление на год';
        const result = buildMinAnnotation({ date: '2026-07-18', balance: 6800, drivenBy: long });
        expect(result.endsWith('…')).toBe(true);
        expect(result.length).toBeLessThan(`мин 18.07 · ${fmtRub(6800)} · `.length + 26);
    });
});

describe('buildDayDetails', () => {
    it('день с расходом', () => {
        expect(buildDayDetails(pt('2026-07-18', 6800, 0, 8300), false))
            .toBe(`18.07 · остаток ${fmtRub(6800)} · −${fmtRub(8300)}`);
    });
    it('день с доходом и расходом', () => {
        expect(buildDayDetails(pt('2026-07-20', 51800, 45000, 1200), false))
            .toBe(`20.07 · остаток ${fmtRub(51800)} · +${fmtRub(45000)} · −${fmtRub(1200)}`);
    });
    it('день 0 подписан «сегодня», нулевые потоки опущены', () => {
        expect(buildDayDetails(pt('2026-07-10', 50000), true))
            .toBe(`сегодня · остаток ${fmtRub(50000)}`);
    });
});
