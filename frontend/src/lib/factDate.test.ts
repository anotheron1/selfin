import { describe, it, expect } from 'vitest';
import { canRecordFact, todayIso } from './factDate';

describe('canRecordFact (ANO-155)', () => {
    const TODAY = '2026-09-15';

    it('вчерашним числом записать можно', () => {
        expect(canRecordFact('2026-09-14', TODAY)).toBe(true);
    });

    it('сегодняшним можно: деньги уйти уже могли', () => {
        expect(canRecordFact(TODAY, TODAY)).toBe(true);
    });

    it('завтрашним нельзя: в будущем деньги уйти не могли', () => {
        expect(canRecordFact('2026-09-16', TODAY)).toBe(false);
    });

    it('пустую дату записать нельзя', () => {
        expect(canRecordFact('', TODAY)).toBe(false);
    });

    it('сравнение календарное, а не строковое по длине', () => {
        // 2026-09-09 < 2026-09-15 — лексикография ISO совпадает с календарём
        expect(canRecordFact('2026-09-09', TODAY)).toBe(true);
        expect(canRecordFact('2026-10-01', TODAY)).toBe(false);
    });
});

describe('todayIso (ANO-155)', () => {
    it('берёт местную дату, а не UTC', () => {
        // 31 декабря 23:00 по местному: UTC-версия дала бы 1 января.
        expect(todayIso(new Date(2026, 11, 31, 23, 0, 0))).toBe('2026-12-31');
    });

    it('дополняет месяц и день нулями', () => {
        expect(todayIso(new Date(2026, 0, 5))).toBe('2026-01-05');
    });
});
