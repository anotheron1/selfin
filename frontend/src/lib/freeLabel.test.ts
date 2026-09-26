import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { FREE_LABEL, pocketResult } from './gapMode';
import { buildBreakdownView } from './breakdownRows';
import type { PocketResponse } from '../types/api';

/**
 * Одно число — одно имя (ANO-76). Итог расшифровки назвал число «Свободно» (ANO-77), а карточка
 * над тем же числом осталась «В кармашке» — и этого никто не заметил. Поэтому имя живёт в одной
 * константе, а здесь проверяется, что карточка, расшифровка и шапка примерки берут его оттуда.
 *
 * <p>Имя «Свободно» — только когда денег хватает. При нехватке и задетом НЗ итог называется по
 * состоянию, как в расшифровке (ANO-77): шапка примерки писала «Свободно сейчас −25 612 ₽» — ревью
 * Codex на #80. Поэтому итог словами — одна функция на расшифровку и шапку.
 *
 * Спека: docs/superpowers/specs/2026-09-26-free-money-word-design.md
 */

const source = (path: string) => readFileSync(fileURLToPath(new URL(path, import.meta.url)), 'utf8');

/** Обычный день: денег хватает, НЗ не задан. */
const ok: PocketResponse = {
    pocket: 12000,
    currentBalance: 30000,
    buffer: 0,
    checkpointDate: '2026-09-20',
    horizon: { type: 'NEXT_INCOME', endDate: '2026-10-05', label: 'до дохода 05.10', fallback: false },
    minPoint: { date: '2026-10-04', balance: 12000, drivenBy: null },
    minPointWithForecast: null,
    breakdown: [
        { type: 'STARTING_BALANCE', label: 'На счёте', amount: 30000, details: [] },
        { type: 'PLANNED_EXPENSES', label: 'Плановые расходы', amount: -18000, details: [] },
        { type: 'TRAJECTORY_MIN', label: 'Самый низкий остаток', amount: 12000, details: [] },
        { type: 'POCKET', label: 'Свободно', amount: 12000, details: [] },
    ],
    trajectory: [
        { date: '2026-09-26', balance: 30000, income: 0, expense: 0, balanceWithForecast: null },
        { date: '2026-10-04', balance: 12000, income: 0, expense: 18000, balanceWithForecast: null },
        { date: '2026-10-05', balance: 87000, income: 75000, expense: 0, balanceWithForecast: null },
    ],
    wishlistCandidates: [],
    upcoming: [],
    pocketAfterCreditRestore: null,
    pocketWithDeposits: null,
    pocketWithForecast: null,
    planHasExpectations: true,
};

describe('имя главного числа — одно на всех экранах (ANO-76)', () => {
    it('имя — слово людей: «Свободно», не «кармашек»', () => {
        expect(FREE_LABEL).toBe('Свободно');
    });

    it('итог расшифровки в обычном режиме назван тем же именем, что карточка', () => {
        expect(buildBreakdownView(ok).result.label).toBe(FREE_LABEL);
    });

    it('карточка в обычном режиме подписана этим именем, а не своей строкой', () => {
        expect(source('../components/PocketCard.tsx')).toContain('gap ? gap.horizonLabel : FREE_LABEL');
    });

    it('итог словами по состоянию: «Свободно» — только когда денег хватает, сумма всегда без минуса', () => {
        expect(pocketResult(ok)).toEqual({ label: FREE_LABEL, amount: 12000 });
        const gap = { ...ok, pocket: -25612, minPoint: { date: '2026-10-12', balance: -25612, drivenBy: null } };
        expect(pocketResult(gap)).toEqual({ label: 'Не хватает', amount: 25612 });
        const nz = { ...ok, pocket: -3000, buffer: 5000, minPoint: { date: '2026-09-26', balance: 2000, drivenBy: null } };
        expect(pocketResult(nz)).toEqual({ label: 'Придётся взять из НЗ', amount: 3000 });
    });

    it('шапка примерки называет оба числа по состоянию — той же функцией, что итог расшифровки', () => {
        const page = source('../pages/Wishlist.tsx');
        expect(page).toContain('pocketResult(baseline)');
        expect(page).toContain('pocketResult(fitted)');
        expect(page, 'своя подпись «Свободно» противоречит числу при нехватке').not.toContain('{FREE_LABEL} сейчас');
        expect(source('./breakdownRows.ts'), 'итог расшифровки — той же функцией').toContain('pocketResult(p)');
    });
});
