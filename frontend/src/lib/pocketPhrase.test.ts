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
        horizon: { type: 'NEXT_INCOME', endDate: '2026-07-15', label: 'до дохода 15.07', fallback: false },
        minPoint: { date: '2026-07-12', balance: 36000, drivenBy: 'Страховка' },
        breakdown: [],
        trajectory: [
            { date: '2026-07-10', balance: 60000, income: 0, expense: 20000 },
            { date: '2026-07-12', balance: 36000, income: 0, expense: 24000 },
            { date: '2026-07-15', balance: 129000, income: 93000, expense: 0 },
        ],
        wishlistCandidates: [],
        ...overrides,
    };
}

describe('buildPocketPhrase', () => {
    it('провал в середине: число + узкий день с виновником + после дохода', () => {
        expect(buildPocketPhrase(make())).toBe(
            `Свободно ${fmtC(36000)} до дохода 15.07. Самый узкий день — 12.07: на счёте останется ${fmtC(36000)} («Страховка»). После дохода → ${fmtC(129000)}.`,
        );
    });

    it('буфер > 0: min = pocket + buffer, буфер упомянут', () => {
        const p = make({
            pocket: 36000,
            buffer: 5000,
            minPoint: { date: '2026-07-12', balance: 41000, drivenBy: 'Страховка' },
        });
        expect(buildPocketPhrase(p)).toBe(
            `Свободно ${fmtC(36000)} до дохода 15.07. Самый узкий день — 12.07: на счёте останется ${fmtC(41000)} («Страховка»). Буфер ${fmtC(5000)} уже отложен. После дохода → ${fmtC(129000)}.`,
        );
    });

    it('минимум в день 0: траектория не опускается ниже сегодняшнего (расходы могут быть!)', () => {
        // Фикстура консистентна движку: день 0 — низшая точка, дальше доход перекрывает трату
        const p = make({
            pocket: 60000,
            minPoint: { date: '2026-07-10', balance: 60000, drivenBy: null },
            trajectory: [
                { date: '2026-07-10', balance: 60000, income: 0, expense: 5000 },
                { date: '2026-07-12', balance: 153000, income: 100000, expense: 7000 },
                { date: '2026-07-15', balance: 129000, income: 0, expense: 24000 },
            ],
        });
        expect(buildPocketPhrase(p)).toBe(
            `Свободно ${fmtC(60000)} до дохода 15.07. Минимум — уже сегодня: дальше по плану не ниже. После дохода → ${fmtC(129000)}.`,
        );
    });

    it('дефицит с выходом в плюс', () => {
        const p = make({
            pocket: -49094,
            minPoint: { date: '2026-07-13', balance: -49094, drivenBy: 'Детсад' },
            trajectory: [
                { date: '2026-07-10', balance: -17994, income: 0, expense: 85400 },
                { date: '2026-07-13', balance: -49094, income: 0, expense: 11300 },
                { date: '2026-07-15', balance: 25906, income: 75000, expense: 0 },
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
                { date: '2026-07-10', balance: -17994, income: 0, expense: 85400 },
                { date: '2026-07-13', balance: -49094, income: 0, expense: 11300 },
                { date: '2026-07-15', balance: -9094, income: 40000, expense: 0 },
            ],
        });
        expect(buildPocketPhrase(p)).toBe(
            `Свободных денег нет: к 13.07 по плану не хватит ${fmtC(49094)}. Даже доход 15.07 не выведет в плюс — план требует правки.`,
        );
    });

    it('буфер прогрызен: минимум положительный, но ниже буфера', () => {
        const p = make({
            pocket: -2000,
            buffer: 5000,
            minPoint: { date: '2026-07-12', balance: 3000, drivenBy: null },
        });
        expect(buildPocketPhrase(p)).toBe(
            `Впритык: в узкий день 12.07 на счёте останется ${fmtC(3000)} — меньше буфера ${fmtC(5000)}. После дохода → ${fmtC(129000)}.`,
        );
    });

    it('фолбэк-горизонт: без «после дохода», с пометкой про доходы', () => {
        const p = make({
            pocket: 20000,
            horizon: { type: 'NEXT_INCOME', endDate: '2026-08-09', label: '30 дней вперёд (нет плановых доходов)', fallback: true },
            minPoint: { date: '2026-07-12', balance: 20000, drivenBy: 'Аренда' },
        });
        expect(buildPocketPhrase(p)).toBe(
            `Свободно ${fmtC(20000)} на 30 дней вперёд (плановых доходов нет). Самый узкий день — 12.07: на счёте останется ${fmtC(20000)} («Аренда»).`,
        );
    });

    it('скоуп SECOND_INCOME: label дословно + «после дохода» (конец горизонта = день 2-го дохода)', () => {
        const p = make({
            horizon: { type: 'SECOND_INCOME', endDate: '2026-07-25', label: 'до 2-го дохода 25.07', fallback: false },
            trajectory: [
                { date: '2026-07-10', balance: 60000, income: 0, expense: 20000 },
                { date: '2026-07-12', balance: 36000, income: 0, expense: 24000 },
                { date: '2026-07-25', balance: 129000, income: 93000, expense: 0 },
            ],
        });
        expect(buildPocketPhrase(p)).toBe(
            `Свободно ${fmtC(36000)} до 2-го дохода 25.07. Самый узкий день — 12.07: на счёте останется ${fmtC(36000)} («Страховка»). После дохода → ${fmtC(129000)}.`,
        );
    });

    it('SECOND_INCOME-фолбэк без доходов вовсе: грамматика с «на», как у NEXT_INCOME-фолбэка', () => {
        const p = make({
            pocket: 20000,
            horizon: { type: 'SECOND_INCOME', endDate: '2026-08-09', label: '30 дней вперёд (нет плановых доходов)', fallback: true },
            minPoint: { date: '2026-07-12', balance: 20000, drivenBy: null },
        });
        expect(buildPocketPhrase(p)).toBe(
            `Свободно ${fmtC(20000)} на 30 дней вперёд (плановых доходов нет). Самый узкий день — 12.07: на счёте останется ${fmtC(20000)}.`,
        );
    });

    it('SECOND_INCOME-фолбэк: label дословно, БЕЗ утверждения «плановых доходов нет»', () => {
        const p = make({
            horizon: { type: 'SECOND_INCOME', endDate: '2026-08-29', label: 'до 29.08 (второй доход не найден)', fallback: true },
        });
        expect(buildPocketPhrase(p)).toBe(
            `Свободно ${fmtC(36000)} до 29.08 (второй доход не найден). Самый узкий день — 12.07: на счёте останется ${fmtC(36000)} («Страховка»).`,
        );
    });

    it('скоуп MONTHS: горизонт из label, без «после дохода»', () => {
        const p = make({
            horizon: { type: 'MONTHS', endDate: '2026-10-10', label: '3 мес (до 10.10)', fallback: false },
        });
        expect(buildPocketPhrase(p)).toBe(
            `Свободно ${fmtC(36000)} 3 мес (до 10.10). Самый узкий день — 12.07: на счёте останется ${fmtC(36000)} («Страховка»).`,
        );
    });
});
