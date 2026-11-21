import { describe, expect, it } from 'vitest';
import type { FinancialEvent } from '../types/api';
import { foundByMonth, foundHeader, NO_SEARCH, periodRange, searchParams, searchReady } from './journalSearch';

const ev = (over: Partial<FinancialEvent>): FinancialEvent => ({
    id: 'e', date: '2026-11-12', categoryId: 'c', categoryName: 'Продукты', type: 'EXPENSE', plannedAmount: null,
    factAmount: 1240, status: 'EXECUTED', priority: 'MEDIUM', description: 'Пятёрочка', rawInput: null,
    createdAt: '2026-11-12T09:00:00', eventKind: 'FACT', parentEventId: null, linkedFactsCount: 0,
    linkedFactsAmount: null, parentPlanDescription: null, ...over,
});

describe('поиск по журналу', () => {
    it('ищет, когда есть слово, число, категория или вид; один период и «Только записи» — нет', () => {
        expect(searchReady(NO_SEARCH)).toBe(false);
        expect(searchReady({ ...NO_SEARCH, q: '  ' })).toBe(false);
        expect(searchReady({ ...NO_SEARCH, period: 'month', factsOnly: false })).toBe(false);
        expect(searchReady({ ...NO_SEARCH, q: 'пятёр' })).toBe(true);
        expect(searchReady({ ...NO_SEARCH, categoryId: 'c' })).toBe(true);
        expect(searchReady({ ...NO_SEARCH, priority: 'LOW' })).toBe(true);
    });

    it('период — от сегодня: этот месяц, прошлый через границу года, этот год; «за всё время» — без дат', () => {
        expect(periodRange('all', '2026-01-15')).toEqual({});
        expect(periodRange('month', '2026-02-15')).toEqual({ from: '2026-02-01', to: '2026-02-28' });
        expect(periodRange('prev', '2026-01-15')).toEqual({ from: '2025-12-01', to: '2025-12-31' });
        expect(periodRange('year', '2026-11-21')).toEqual({ from: '2026-01-01', to: '2026-12-31' });
    });

    it('в запрос — только заданное: слово без краевых пробелов, даты периода, категория, вид, «Только записи»', () => {
        expect(searchParams({ ...NO_SEARCH, q: ' пятёр ' }, '2026-11-21').toString())
            .toBe(new URLSearchParams({ q: 'пятёр', factsOnly: 'true' }).toString());
        expect(Object.fromEntries(searchParams(
            { q: '', period: 'month', categoryId: 'c', priority: 'HIGH', factsOnly: false }, '2026-11-21')))
            .toEqual({ from: '2026-11-01', to: '2026-11-30', categoryId: 'c', priority: 'HIGH' });
    });

    it('«Нашлось 46 записей · 52 300 ₽ за всё время»; доход — с плюсом; с планами — «строк» и «записано»', () => {
        const rows = [ev({})];
        expect(foundHeader({ rows, total: 46, recorded: -52300 }, NO_SEARCH))
            .toMatchObject({ found: '46 записей · 52 300 ₽', tail: 'за всё время' });
        expect(foundHeader({ rows, total: 1, recorded: 80000 }, { ...NO_SEARCH, period: 'month' }))
            .toEqual({ found: '1 запись · +80 000 ₽', tail: 'за этот месяц', more: null });
        expect(foundHeader({ rows, total: 3, recorded: -500 }, { ...NO_SEARCH, factsOnly: false }).found)
            .toBe('3 строки · записано 500 ₽');
        // Только планы — записанного нет, суммы нет.
        expect(foundHeader({ rows, total: 2, recorded: 0 }, { ...NO_SEARCH, factsOnly: false }).found).toBe('2 строки');
    });

    it('ничего — «Ничего не нашлось»; больше, чем пришло, — «Показаны 200 свежих»', () => {
        expect(foundHeader({ rows: [], total: 0, recorded: 0 }, NO_SEARCH).found).toBeNull();
        const rows = Array.from({ length: 200 }, () => ev({}));
        expect(foundHeader({ rows, total: 340, recorded: -1 }, NO_SEARCH).more).toBe('Показаны 200 свежих');
    });

    it('найденное по месяцам в порядке сервера: «Ноябрь 2026 · 2», строка — имя, дата и категория, сумма со знаком', () => {
        const months = foundByMonth([
            ev({ id: 'a' }),
            ev({ id: 'b', date: '2026-11-05', description: null, factAmount: 980 }),
            ev({ id: 'c', date: '2025-10-29', type: 'INCOME', description: 'Зарплата', categoryName: 'Зарплата', factAmount: 50000 }),
            ev({ id: 'd', date: '2025-10-01', type: 'FUND_TRANSFER', targetFundName: 'Отпуск', factAmount: -3000 }),
            ev({ id: 'e', date: '2025-10-01', eventKind: 'PLAN', factAmount: null, plannedAmount: 25000, description: 'Аренда', categoryName: 'Жильё и быт' }),
        ]);
        expect(months.map(m => m.label)).toEqual(['Ноябрь 2026 · 2', 'Октябрь 2025 · 3']);
        expect(months[0].rows.map(r => [r.name, r.sub, r.amount])).toEqual([
            ['Пятёрочка', '12 нояб. · Продукты', '1 240 ₽'],
            ['Продукты', '5 нояб.', '980 ₽'],
        ]);
        expect(months[1].rows.map(r => [r.name, r.sub, r.amount])).toEqual([
            ['Зарплата', '29 окт. · Зарплата', '+50 000 ₽'],
            ['Отпуск', '1 окт. · Накопление', '+3 000 ₽'],
            ['Аренда', '1 окт. · Жильё и быт', '25 000 ₽'],
        ]);
    });
});
