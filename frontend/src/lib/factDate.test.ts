import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
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

describe('формы факта берут «сегодня» одним способом (ревью #45)', () => {
    // В быстром добавлении умолчание даты считалось по UTC, а граница «не позже сегодня» —
    // по местной дате. Западнее Гринвича после полуночи по UTC форма объявляла будущим
    // собственное умолчание и прятала поле факта, пока дату не поправят руками. Сторож
    // читает исходник: поведенческого теста на это нет — компонентных тестов в проекте нет.
    const forms = ['../components/Fab.tsx', '../components/FactCreateSheet.tsx'];

    it.each(forms)('%s не берёт дату из toISOString()', (file) => {
        const src = readFileSync(new URL(file, import.meta.url), 'utf8');
        expect(src).not.toMatch(/toISOString\(\)\s*\.slice/);
        expect(src).toContain('todayIso(');
    });
});
