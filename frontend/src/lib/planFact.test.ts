import { describe, it, expect } from 'vitest';
import { favourableDelta, favourableFromDelta, deltaColor, deltaSign } from './planFact';

const GREEN = 'var(--color-success)';
const RED = 'var(--color-danger)';

describe('planFact: знак «в пользу пользователя» (ANO-32)', () => {
    it('доход: больше плана — хорошо, меньше — плохо', () => {
        // Симптом тикета: зарплата выше плановой красилась красным
        expect(deltaColor(favourableDelta('INCOME', 100_000, 120_000))).toBe(GREEN);
        expect(deltaColor(favourableDelta('INCOME', 100_000, 80_000))).toBe(RED);
    });

    it('расход: меньше плана — хорошо, больше — плохо', () => {
        expect(deltaColor(favourableDelta('EXPENSE', 10_000, 8_000))).toBe(GREEN);
        expect(deltaColor(favourableDelta('EXPENSE', 10_000, 12_000))).toBe(RED);
    });

    it('ровно по плану — хорошо для обоих типов', () => {
        expect(deltaColor(favourableDelta('INCOME', 100, 100))).toBe(GREEN);
        expect(deltaColor(favourableDelta('EXPENSE', 100, 100))).toBe(GREEN);
    });

    it('нет плана или нет факта — не красим тревожным цветом', () => {
        expect(favourableDelta('EXPENSE', null, 5_000)).toBe(0);
        expect(favourableDelta('EXPENSE', 5_000, null)).toBe(0);
        expect(deltaColor(favourableDelta('EXPENSE', null, 5_000))).toBe(GREEN);
    });

    it('favourableFromDelta переворачивает только расход', () => {
        // бэк отдаёт delta = факт − план
        expect(favourableFromDelta('INCOME', 2_000)).toBe(2_000);
        expect(favourableFromDelta('EXPENSE', 2_000)).toBe(-2_000);
        expect(favourableFromDelta('EXPENSE', -2_000)).toBe(2_000);
    });

    it('deltaSign не теряет минус', () => {
        expect(deltaSign(500)).toBe('+');
        expect(deltaSign(0)).toBe('+');
        expect(deltaSign(-500)).toBe('−');
    });
});
