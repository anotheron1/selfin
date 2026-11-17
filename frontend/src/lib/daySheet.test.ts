import { describe, expect, it } from 'vitest';
import { buildDaySheet } from './daySheet';
import type { PocketResponse, UpcomingItem } from '../types/api';

const sp = (s: string) => s.replace(/\s/g, ' ');

const it_ = (over: Partial<UpcomingItem>): UpcomingItem => ({
    id: 'x', date: '2026-11-15', categoryName: 'Связь и подписки', amount: 800, description: 'Связь',
    overdue: false, wishlist: false, priority: 'HIGH', type: 'EXPENSE', ...over,
});

const pocket = {
    trajectory: [
        { date: '2026-11-14', balance: 21_100, income: 0, expense: 0, balanceWithForecast: null, items: [] },
        {
            date: '2026-11-15', balance: 19_900, income: 90_000, expense: 1_200, balanceWithForecast: null, items: [
                it_({ id: 'a' }),
                it_({ id: 'b', categoryName: 'Продукты', description: null, amount: 400, priority: 'MEDIUM' }),
                it_({ id: 'c', categoryName: 'Доходы', description: 'Зарплата', amount: 90_000, type: 'INCOME' }),
                it_({ id: 'd', categoryName: 'Жильё и быт', description: 'Аренда', amount: 25_000, overdue: true, date: '2026-11-10' }),
            ],
        },
        { date: '2026-11-16', balance: 19_900, income: 0, expense: 0, balanceWithForecast: null, items: [] },
    ],
} as unknown as PocketResponse;

describe('buildDaySheet — лист дня', () => {
    it('заголовок — день недели и дата; сводка — сколько строк и что к вечеру', () => {
        const v = buildDaySheet(pocket, '2026-11-15')!;
        expect(v.title).toBe('Воскресенье, 15 нояб.');
        expect(sp(v.summary)).toBe('4 строки · к вечеру останется 19 900 ₽');
    });

    it('строки: своё имя и категория с видом; без своего имени — категория; доход с плюсом, ожидание с «≈»', () => {
        const rows = buildDaySheet(pocket, '2026-11-15')!.rows.map(r => [r.kind, r.name, r.sub, sp(r.amount)]);
        expect(rows).toEqual([
            ['bron', 'Связь', 'Связь и подписки · бронь', '-800 ₽'],
            ['ozh', 'Продукты', 'ожидание', '≈ -400 ₽'],
            ['bron', 'Зарплата', 'Доходы · бронь', '+90 000 ₽'],
            ['bron', 'Аренда', 'Жильё и быт · бронь с 10 нояб.', '-25 000 ₽'],
        ]);
    });

    it('‹ › — соседние дни графика; у краёв — нет', () => {
        expect(buildDaySheet(pocket, '2026-11-15')).toMatchObject({ prev: '2026-11-14', next: '2026-11-16' });
        expect(buildDaySheet(pocket, '2026-11-14')).toMatchObject({ prev: null, next: '2026-11-15' });
        expect(buildDaySheet(pocket, '2026-11-16')?.next).toBeNull();
    });

    it('пустой день — «Строк нет»; дня нет на графике — листа нет', () => {
        expect(sp(buildDaySheet(pocket, '2026-11-16')!.summary)).toBe('Строк нет · к вечеру останется 19 900 ₽');
        expect(buildDaySheet(pocket, '2026-12-01')).toBeNull();
    });
});
