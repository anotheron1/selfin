import { describe, expect, it } from 'vitest';
import { termIncome, usualCategoryId, usualChoices, usualDtos } from './usualSpending';
import type { PocketResponse } from '../types/api';

const cats = [
    { id: 'food', name: 'Продукты', type: 'EXPENSE' as const },
    { id: 'bus', name: 'Транспорт', type: 'EXPENSE' as const },
    { id: 'cafe', name: 'Кафе и доставка', type: 'EXPENSE' as const },
];

describe('usualDtos — обычные траты до дохода', () => {
    it('по ожиданию на категорию с суммой, датой — день перед доходом, повтор месяцем тем же днём', () => {
        const dtos = usualDtos({ Продукты: 6_000, Транспорт: 0, 'Кафе и доставка': 2_000 }, cats,
            { date: '2026-11-25', monthly: true }, '2026-11-12');
        expect(dtos).toEqual([
            { date: '2026-11-24', categoryId: 'food', type: 'EXPENSE', plannedAmount: 6_000, priority: 'MEDIUM',
                recurring: { frequency: 'MONTHLY', dayOfMonth: 24, startDate: '2026-11-24' } },
            { date: '2026-11-24', categoryId: 'cafe', type: 'EXPENSE', plannedAmount: 2_000, priority: 'MEDIUM',
                recurring: { frequency: 'MONTHLY', dayOfMonth: 24, startDate: '2026-11-24' } },
        ]);
    });

    it('доход не повторяется — и ожидание без повтора', () => {
        const [dto] = usualDtos({ Продукты: 6_000 }, cats, { date: '2026-11-25', monthly: false }, '2026-11-12');
        expect(dto.recurring).toBeNull();
    });

    it('доход 1-го: день перед ним — конец прошлого месяца, повтор 31-го', () => {
        const [dto] = usualDtos({ Продукты: 6_000 }, cats, { date: '2026-12-01', monthly: true }, '2026-11-12');
        expect(dto.date).toBe('2026-11-30');
        expect(dto.recurring).toMatchObject({ dayOfMonth: 31 });
    });

    it('доход сегодня — дата не в прошлом, а сегодня', () => {
        const [dto] = usualDtos({ Продукты: 6_000 }, cats, { date: '2026-11-12', monthly: false }, '2026-11-12');
        expect(dto.date).toBe('2026-11-12');
    });

    it('категории нет — отказ, а не трата без категории', () => {
        expect(() => usualDtos({ Транспорт: 500 }, cats.slice(0, 1), { date: '2026-11-25', monthly: false }, '2026-11-12')).toThrow('Транспорт');
    });

    it('своя категория вместо отсутствующей: «Транспорт» пишется в выбранное «Авто», «Продукты» — в свою', () => {
        const own = [cats[0], { id: 'auto', name: 'Авто', type: 'EXPENSE' as const }];
        const dtos = usualDtos({ Продукты: 6_000, Транспорт: 500 }, own, { date: '2026-11-25', monthly: false }, '2026-11-12',
            { Транспорт: 'auto' });
        expect(dtos.map(d => d.categoryId)).toEqual(['food', 'auto']);
    });
});

describe('usualCategoryId — категория строки', () => {
    const own = [{ id: 'food', name: 'Продукты', type: 'EXPENSE' as const }, { id: 'auto', name: 'Авто', type: 'EXPENSE' as const },
        { id: 'inc', name: 'Транспорт', type: 'INCOME' as const }];

    it('есть категория с именем строки — она, без выбора', () => {
        expect(usualCategoryId('Продукты', own)).toBe('food');
    });

    it('такой нет (доходная с тем же именем не в счёт) — null, строка просит выбрать', () => {
        expect(usualCategoryId('Транспорт', own)).toBeNull();
    });

    it('выбирать — из своих расходных, без системных', () => {
        const list = [{ id: 'auto', name: 'Авто', type: 'EXPENSE' as const, isSystem: false },
            { id: 'goal', name: 'Цели', type: 'EXPENSE' as const, isSystem: true },
            { id: 'sal', name: 'Зарплата', type: 'INCOME' as const, isSystem: false }];
        expect(usualChoices(list).map(c => c.id)).toEqual(['auto']);
    });

    it('выбор человека важнее имени', () => {
        expect(usualCategoryId('Продукты', own, { Продукты: 'auto' })).toBe('auto');
        expect(usualCategoryId('Транспорт', own, { Транспорт: 'auto' })).toBe('auto');
    });
});

describe('termIncome — доход, которым кончается срок', () => {
    const p = (income: number, items: unknown[]) => ({
        horizon: { endDate: '2026-11-25' },
        trajectory: [{ date: '2026-11-25', income, items }],
    } as unknown as PocketResponse);

    it('есть доход в последний день срока — его дата и id', () => {
        expect(termIncome(p(90_000, [{ id: 'sal', type: 'INCOME' }]))).toEqual({ date: '2026-11-25', id: 'sal' });
    });

    it('срок кончается не доходом — null', () => {
        expect(termIncome(p(0, []))).toBeNull();
    });
});
