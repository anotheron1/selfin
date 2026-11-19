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

    it('расход и перевод на ноль — минус, доход на ноль — плюс: правка факта разрешает ноль (ревью #105)', () => {
        // -0 < 0 ложно: без явной ветки ноль расхода выходил «+0 ₽», как приход.
        expect(factAmountText('EXPENSE', 0, rub)).toBe('-0 ₽');
        expect(factAmountText('FUND_TRANSFER', 0, rub)).toBe('-0 ₽');
        expect(factAmountText('INCOME', 0, rub)).toBe('+0 ₽');
    });

    it('суммы нет — знака нет: прочерк форматтера', () => {
        expect(factAmountText('EXPENSE', null, rub)).toBe('—');
    });
});

/**
 * Сторож по исходнику: оба места, где на экране сумма факта, берут знак у правила. Тестов компонентов во фронте
 * нет, а вернуть знак по типу строки — правка в одну строку, и тест правила её не заметит. Третье место — блок
 * «Сегодня» Дашборда — ушло в «Журнал» вместе с Дашбордом (срез 2, решение 28): на «Сегодня» фактов нет.
 */
describe('знак факта на экране — только от правила (ANO-184)', () => {
    const budget = readFileSync(new URL('../pages/Budget.tsx', import.meta.url), 'utf8');

    it('журнал, строка: знак — от правила, у ушедшего его нет (доска Zh2-J8); «Последнего факта» нет (решение 51)', () => {
        const list = readFileSync(new URL('../components/journal/JournalList.tsx', import.meta.url), 'utf8');
        expect(list).toContain('const s = signedFact(r.event.type, r.amount);');
        expect(list).not.toMatch(/r\.income \? '\+'/);
        expect(budget).not.toContain('lastFact');
    });
});
