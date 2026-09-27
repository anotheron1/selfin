import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { factAmountText, signedFact } from './factSign';

const rub = (n: number | null) => (n == null ? '—' : `${n} ₽`);

describe('знак суммы факта — по направлению денег (ANO-184)', () => {
    it('расход — минус: деньги ушли из свободных', () => {
        expect(signedFact('EXPENSE', 500)).toEqual({ sign: '-', amount: 500 });
    });

    it('доход — плюс, и в «Последнем факте» тоже: на стенде доход 184 ₽ был «-184 ₽»', () => {
        expect(factAmountText('INCOME', 184, rub)).toBe('+184 ₽');
    });

    it('перевод в копилку — минус', () => {
        expect(factAmountText('FUND_TRANSFER', 1000, rub)).toBe('-1000 ₽');
    });

    it('возврат из копилки — плюс, без второго минуса: на стенде было «--100 ₽»', () => {
        expect(factAmountText('FUND_TRANSFER', -100, rub)).toBe('+100 ₽');
    });

    it('суммы нет — знака нет: прочерк форматтера', () => {
        expect(factAmountText('EXPENSE', null, rub)).toBe('—');
    });
});

/**
 * Сторож по исходнику: три места берут знак факта у правила. Тестов компонентов во фронте нет,
 * а вернуть знак по типу строки — правка в одну строку, и тест правила её не заметит.
 */
describe('знак факта на экране — только от правила (ANO-184)', () => {
    const budget = readFileSync(new URL('../pages/Budget.tsx', import.meta.url), 'utf8');
    const dashboard = readFileSync(new URL('../pages/Dashboard.tsx', import.meta.url), 'utf8');

    it('журнал, строка факта', () => {
        expect(budget).toContain('factAmountText(event.type, event.factAmount, fmt)');
        expect(budget).not.toContain("{isIncome ? '+' : '-'}{fmt(event.factAmount)}");
    });

    it('журнал, «Последний факт»', () => {
        expect(budget).toContain('factAmountText(lastFact.type, lastFact.factAmount, fmt)');
        expect(budget).not.toContain('-{fmt(lastFact.factAmount)}');
    });

    it('дашборд, «Сегодня»: и доходы, и расходы с переводами', () => {
        const calls = dashboard.match(/factAmountText\(e\.type, e\.factAmount \?\? e\.plannedAmount, fmtAmt\)/g) ?? [];
        expect(calls).toHaveLength(2);
        expect(dashboard).not.toMatch(/[-+]\{fmtAmt\(e\.factAmount \?\? e\.plannedAmount\)\}/);
    });
});
