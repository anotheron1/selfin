import { describe, expect, it } from 'vitest';
import { fmtRub as fmtC } from './format';
import { buildPocketPhrase } from './pocketPhrase';
import type { PocketResponse } from '../types/api';

/** Минимальный PocketResponse: горизонт NEXT_INCOME до 15.07, буфер 0, breakdown не важен для фразы. */
function make(overrides: Partial<PocketResponse> = {}): PocketResponse {
    return {
        pocket: 36000,
        currentBalance: 80000,
        buffer: 0,
        checkpointDate: '2026-07-01',
        horizon: { type: 'NEXT_INCOME', endDate: '2026-07-15', label: 'до дохода 15.07', fallback: false },
        minPoint: { date: '2026-07-12', balance: 36000, drivenBy: 'Страховка' },
        breakdown: [],
        trajectory: [
            { date: '2026-07-10', balance: 60000, income: 0, expense: 20000, balanceWithForecast: null },
            { date: '2026-07-12', balance: 36000, income: 0, expense: 24000, balanceWithForecast: null },
            { date: '2026-07-15', balance: 129000, income: 93000, expense: 0, balanceWithForecast: null },
        ],
        wishlistCandidates: [],
        upcoming: [],
        // Второе и третье числа кармашка (ANO-9 §4.2–§4.3) на фразу не влияют: фраза
        // отвечает про основное число, а эти два — оговорки к нему.
        pocketAfterCreditRestore: null,
        pocketWithDeposits: null,
        // ANO-80: прогноз на фразу тоже не влияет — она про основное число.
        pocketWithForecast: null,
        // ANO-185: на фразу не влияет — строка об ожиданиях стоит отдельно.
        planHasExpectations: true,
        minPointWithForecast: null,
        ...overrides,
    };
}

/** Первая фраза справки кармашка — то, что значит число (ANO-99). */
const MEANING = 'так, чтобы хватило на всё, что уже стоит в плане.';

describe('buildPocketPhrase', () => {
    it('ANO-99: при НЗ 0 число не повторяется — фраза говорит, что оно и есть самый низкий остаток', () => {
        // Раньше: «Свободно 36 000 … Самый узкий день — 12.07: на счёте останется 36 000 …» —
        // то же число дважды, и третий раз крупно над фразой.
        expect(buildPocketPhrase(make())).toBe(
            `Столько можно потратить до дохода 15.07 ${MEANING} Это самый низкий остаток по плану — 12.07 («Страховка»). После дохода станет ${fmtC(129000)}.`,
        );
    });

    it('НЗ > 0: минимум — другое число, его и НЗ называем', () => {
        const p = make({
            pocket: 36000,
            buffer: 5000,
            minPoint: { date: '2026-07-12', balance: 41000, drivenBy: 'Страховка' },
        });
        expect(buildPocketPhrase(p)).toBe(
            `Столько можно потратить до дохода 15.07 ${MEANING} Самый низкий остаток по плану — ${fmtC(41000)} 12.07 («Страховка»); НЗ ${fmtC(5000)} из него не тратится. После дохода станет ${fmtC(129000)}.`,
        );
    });

    it('минимум в день 0: траектория не опускается ниже сегодняшнего (расходы могут быть!)', () => {
        // Фикстура консистентна движку: день 0 — низшая точка, дальше доход перекрывает трату
        const p = make({
            pocket: 60000,
            minPoint: { date: '2026-07-10', balance: 60000, drivenBy: null },
            trajectory: [
                { date: '2026-07-10', balance: 60000, income: 0, expense: 5000, balanceWithForecast: null },
                { date: '2026-07-12', balance: 153000, income: 100000, expense: 7000, balanceWithForecast: null },
                { date: '2026-07-15', balance: 129000, income: 0, expense: 24000, balanceWithForecast: null },
            ],
        });
        expect(buildPocketPhrase(p)).toBe(
            `Столько можно потратить до дохода 15.07 ${MEANING} Ниже сегодняшнего остаток по плану не опустится. После дохода станет ${fmtC(129000)}.`,
        );
    });

    it('минимум в день 0 при НЗ: НЗ из остатка не тратится', () => {
        const p = make({
            pocket: 55000,
            buffer: 5000,
            minPoint: { date: '2026-07-10', balance: 60000, drivenBy: null },
            trajectory: [
                { date: '2026-07-10', balance: 60000, income: 0, expense: 5000, balanceWithForecast: null },
                { date: '2026-07-15', balance: 129000, income: 69000, expense: 0, balanceWithForecast: null },
            ],
        });
        expect(buildPocketPhrase(p)).toBe(
            `Столько можно потратить до дохода 15.07 ${MEANING} Ниже сегодняшнего остаток по плану не опустится; НЗ ${fmtC(5000)} из него не тратится. После дохода станет ${fmtC(129000)}.`,
        );
    });

    it('дефицит с выходом в плюс', () => {
        const p = make({
            pocket: -49094,
            minPoint: { date: '2026-07-13', balance: -49094, drivenBy: 'Детсад' },
            trajectory: [
                { date: '2026-07-10', balance: -17994, income: 0, expense: 85400, balanceWithForecast: null },
                { date: '2026-07-13', balance: -49094, income: 0, expense: 11300, balanceWithForecast: null },
                { date: '2026-07-15', balance: 25906, income: 75000, expense: 0, balanceWithForecast: null },
            ],
        });
        expect(buildPocketPhrase(p)).toBe(
            `Свободных денег нет: к 13.07 по плану не хватит ${fmtC(49094)} («Детсад»). Доход 15.07 выведет в ${fmtC(25906)}.`,
        );
    });

    it('дефицит без выхода в плюс', () => {
        const p = make({
            pocket: -49094,
            minPoint: { date: '2026-07-13', balance: -49094, drivenBy: null },
            trajectory: [
                { date: '2026-07-10', balance: -17994, income: 0, expense: 85400, balanceWithForecast: null },
                { date: '2026-07-13', balance: -49094, income: 0, expense: 11300, balanceWithForecast: null },
                { date: '2026-07-15', balance: -9094, income: 40000, expense: 0, balanceWithForecast: null },
            ],
        });
        expect(buildPocketPhrase(p)).toBe(
            `Свободных денег нет: к 13.07 по плану не хватит ${fmtC(49094)}. Даже доход 15.07 не выведет в плюс — план требует правки.`,
        );
    });

    it('НЗ задет: минимум положительный, но ниже НЗ — «самый низкий остаток», а не «узкий день»', () => {
        const p = make({
            pocket: -2000,
            buffer: 5000,
            minPoint: { date: '2026-07-12', balance: 3000, drivenBy: null },
        });
        expect(buildPocketPhrase(p)).toBe(
            `Впритык: самый низкий остаток по плану — ${fmtC(3000)} 12.07, меньше НЗ ${fmtC(5000)}. После дохода станет ${fmtC(129000)}.`,
        );
    });

    it('фолбэк-горизонт: без «после дохода», с пометкой про доходы', () => {
        const p = make({
            pocket: 20000,
            horizon: { type: 'NEXT_INCOME', endDate: '2026-08-09', label: '30 дней вперёд (нет плановых доходов)', fallback: true },
            minPoint: { date: '2026-07-12', balance: 20000, drivenBy: 'Аренда' },
        });
        expect(buildPocketPhrase(p)).toBe(
            `Столько можно потратить на 30 дней вперёд (плановых доходов нет) ${MEANING} Это самый низкий остаток по плану — 12.07 («Аренда»).`,
        );
    });

    it('скоуп SECOND_INCOME: label дословно + «после дохода» (конец горизонта = день 2-го дохода)', () => {
        const p = make({
            horizon: { type: 'SECOND_INCOME', endDate: '2026-07-25', label: 'до 2-го дохода 25.07', fallback: false },
            trajectory: [
                { date: '2026-07-10', balance: 60000, income: 0, expense: 20000, balanceWithForecast: null },
                { date: '2026-07-12', balance: 36000, income: 0, expense: 24000, balanceWithForecast: null },
                { date: '2026-07-25', balance: 129000, income: 93000, expense: 0, balanceWithForecast: null },
            ],
        });
        expect(buildPocketPhrase(p)).toBe(
            `Столько можно потратить до 2-го дохода 25.07 ${MEANING} Это самый низкий остаток по плану — 12.07 («Страховка»). После дохода станет ${fmtC(129000)}.`,
        );
    });

    it('SECOND_INCOME-фолбэк без доходов вовсе: грамматика с «на», как у NEXT_INCOME-фолбэка', () => {
        const p = make({
            pocket: 20000,
            horizon: { type: 'SECOND_INCOME', endDate: '2026-08-09', label: '30 дней вперёд (нет плановых доходов)', fallback: true },
            minPoint: { date: '2026-07-12', balance: 20000, drivenBy: null },
        });
        expect(buildPocketPhrase(p)).toBe(
            `Столько можно потратить на 30 дней вперёд (плановых доходов нет) ${MEANING} Это самый низкий остаток по плану — 12.07.`,
        );
    });

    it('SECOND_INCOME-фолбэк: label дословно, БЕЗ утверждения «плановых доходов нет»', () => {
        const p = make({
            horizon: { type: 'SECOND_INCOME', endDate: '2026-08-29', label: 'до 29.08 (второй доход не найден)', fallback: true },
        });
        expect(buildPocketPhrase(p)).toBe(
            `Столько можно потратить до 29.08 (второй доход не найден) ${MEANING} Это самый низкий остаток по плану — 12.07 («Страховка»).`,
        );
    });

    it('информационный хвост за горизонтом: «после дохода» = точка дня дохода, не конец траектории', () => {
        const p = make({
            trajectory: [
                { date: '2026-07-10', balance: 60000, income: 0, expense: 20000, balanceWithForecast: null },
                { date: '2026-07-12', balance: 36000, income: 0, expense: 24000, balanceWithForecast: null },
                { date: '2026-07-15', balance: 129000, income: 93000, expense: 0, balanceWithForecast: null },
                { date: '2026-07-16', balance: 104000, income: 0, expense: 25000, balanceWithForecast: null },
                { date: '2026-07-17', balance: 104000, income: 0, expense: 0, balanceWithForecast: null },
            ],
        });
        expect(buildPocketPhrase(p)).toBe(
            `Столько можно потратить до дохода 15.07 ${MEANING} Это самый низкий остаток по плану — 12.07 («Страховка»). После дохода станет ${fmtC(129000)}.`,
        );
    });

    it('скоуп MONTHS: «за N мес», без «после дохода»', () => {
        const p = make({
            horizon: { type: 'MONTHS', endDate: '2026-10-10', label: '3 мес (до 10.10)', fallback: false },
        });
        expect(buildPocketPhrase(p)).toBe(
            `Столько можно потратить за 3 мес (до 10.10) ${MEANING} Это самый низкий остаток по плану — 12.07 («Страховка»).`,
        );
    });

    it('скоуп DATE: подпись дословно, без «за»', () => {
        const p = make({
            horizon: { type: 'DATE', endDate: '2026-10-31', label: 'до 31.10.2026', fallback: false },
        });
        expect(buildPocketPhrase(p)).toBe(
            `Столько можно потратить до 31.10.2026 ${MEANING} Это самый низкий остаток по плану — 12.07 («Страховка»).`,
        );
    });
});
