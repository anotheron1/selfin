import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';

/**
 * Сторож по исходнику (Р1, ANO-23): остаток по месяцам на «Стратегии» и в «Что с капиталом» —
 * одно число из ядра, и называется оно одним словом — «остаток» (правило 13, решение 24.09).
 * Компонентных тестов во фронте нет, поэтому слово проверяется в исходнике.
 *
 * Спека: docs/superpowers/specs/2026-10-04-strategy-from-core-design.md
 */
const read = (path: string) => readFileSync(new URL(path, import.meta.url), 'utf8');

describe('остаток по месяцам называется «остаток» (Р1)', () => {
    it('легенда «Стратегии»', () => {
        const src = read('./CashflowChartCard.tsx');
        expect(src).toContain("label: 'Остаток'");
        expect(src).not.toContain("label: 'Баланс'");
    });

    it('подсказка месяца: остаток на конец месяца, текущий месяц — не «сейчас»', () => {
        const src = read('./MonthTooltip.tsx');
        expect(src).toContain('Остаток на конец месяца');
        expect(src).not.toContain('>Баланс<');
        expect(src).not.toContain('(сейчас)');
    });

    it('подсказка графика «Что с капиталом»', () => {
        const src = read('../wishlist/WishlistImpactChart.tsx');
        expect(src).toContain('Остаток: {fmtRub(row.account)}');
        expect(src).not.toContain('Счёт:');
    });
});
