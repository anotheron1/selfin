import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { CARD_DEBT_ROW, MONEY_ROW, computedRow, sectionTotal } from './capitalRows';
import type { CapitalItem, CapitalSummary } from '../types/api';

/**
 * Р7 (ANO-217): «Капитал на сегодня» складывается из строк экрана. Стенд 04.10: из «Активов 5.49М» и
 * «Обязательств 2.92М» выходило 2 573 957 при капитале 2 668 177 — не было денег 209 900 и долгов по
 * картам 115 680.
 */
const item = (kind: 'ASSET' | 'LIABILITY', currentValue: number, isArchived = false) =>
    ({ id: String(Math.random()), kind, name: '', currentValue, isArchived }) as unknown as CapitalItem;

const summary = {
    total: 2668177, liquid: 209900, assetsTotal: 5490000, liabilitiesTotal: 3031723, cardDebts: 115680,
    items: [item('ASSET', 5000000), item('ASSET', 490000), item('LIABILITY', 2916043), item('ASSET', 1, true)],
    deltas: { month: 0, quarter: 0, year: 0 },
} as unknown as CapitalSummary;

describe('строки капитала (Р7)', () => {
    it('деньги — в «Активах», долги по картам — в «Обязательствах»', () => {
        expect(computedRow('ASSET', summary)).toEqual({ name: MONEY_ROW, value: 209900 });
        expect(computedRow('LIABILITY', summary)).toEqual({ name: CARD_DEBT_ROW, value: 115680 });
    });
    it('нулевая строка не показывается', () => {
        expect(computedRow('LIABILITY', { ...summary, cardDebts: 0 })).toBeNull();
    });
    it('экран берёт строки и итог раздела отсюда (сторож по исходнику)', () => {
        const read = (p: string) => readFileSync(new URL(p, import.meta.url), 'utf8');
        const page = read('../pages/Capital.tsx');
        expect(page).toContain("computedRow('ASSET', summary)");
        expect(page).toContain("computedRow('LIABILITY', summary)");
        expect(read('../components/CapitalItemList.tsx')).toContain('sectionTotal(active, computed)');
    });
    it('«Активы» минус «Обязательства» — ровно «Капитал на сегодня»', () => {
        const items = summary.items;
        const assets = sectionTotal(items.filter(i => i.kind === 'ASSET'), computedRow('ASSET', summary));
        const liabilities = sectionTotal(items.filter(i => i.kind === 'LIABILITY'), computedRow('LIABILITY', summary));
        expect(assets - liabilities).toBe(summary.total);
    });
});
