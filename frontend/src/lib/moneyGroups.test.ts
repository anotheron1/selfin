import { describe, expect, it } from 'vitest';
import {
    accountLine, capitalHead, defaultRange, moneyGroups, monthIn, rangeChange, rangePoints,
} from './moneyGroups';
import { fmtAmount, fmtSigned } from './format';
import type { Account, CapitalItem, CapitalSummary, CapitalTrajectory, TargetFund } from '../types/api';

/** Срез 4, пп. 8–11: группы «Денег», шапка капитала и период графика. */

const acc = (over: Partial<Account>): Account => ({
    id: over.name ?? 'a', name: 'Карта', kind: 'DEBIT', trackBalance: true, purposeCategoryId: null,
    purposeCategoryName: null, creditLimit: null, availableFloor: null, isDefault: false, sortOrder: 0,
    balance: null, balanceDate: null, debt: null, allocatedThisMonth: null, floorSuggestion: null, ...over,
});
const item = (name: string, kind: 'ASSET' | 'LIABILITY', isArchived = false) =>
    ({ id: name, kind, name, currentValue: 1, isArchived }) as unknown as CapitalItem;
/** Неразрывные пробелы сумм — обычными: ожидания читаются глазами. */
const plain = <T,>(x: T): T => JSON.parse(JSON.stringify(x).replace(/\u00a0/g, ' '));
const fund = (name: string, accountId: string | null) => ({ id: name, name, accountId }) as unknown as TargetFund;

const main = acc({ name: 'Основная карта', isDefault: true, balance: 48800, balanceDate: '2026-11-12' });
const credit = acc({ name: 'Кредитка', kind: 'CREDIT', balance: 85000, creditLimit: 100000, debt: 15000, availableFloor: 90000 });
const cash = acc({ name: 'Наличные', kind: 'CASH', trackBalance: false, purposeCategoryName: 'Наличные', allocatedThisMonth: 3000 });
const petrol = acc({ name: 'Карта для бензина', trackBalance: false, purposeCategoryName: 'Бензин', allocatedThisMonth: 5000 });
const deposit = acc({ name: 'Вклад', kind: 'DEPOSIT', balance: 60000, balanceDate: '2026-11-01' });
const second = acc({ name: 'Вторая', balance: 1000, balanceDate: '2026-11-10' });
const oldCash = acc({ name: 'Кошелёк', kind: 'CASH', balance: 500, balanceDate: '2026-11-10' });

describe('группы «Денег» (п. 9)', () => {
    const g = moneyGroups([petrol, deposit, credit, cash, main], [
        item('Машина', 'ASSET'), item('Кредит на машину', 'LIABILITY'), item('Дача', 'ASSET', true),
    ]);

    it('основная и кредитки парой', () => {
        expect(g.primary?.name).toBe('Основная карта');
        expect(g.credits.map(a => a.name)).toEqual(['Кредитка']);
    });

    it('для отдельных трат — карты без остатка, наличные первыми', () => {
        expect(g.spending.map(a => a.name)).toEqual(['Наличные', 'Карта для бензина']);
    });

    it('накопительные — вклады', () => {
        expect(g.savings.map(a => a.name)).toEqual(['Вклад']);
    });

    it('имущество, долги и архивные — отдельно', () => {
        expect(g.assets.map(i => i.name)).toEqual(['Машина']);
        expect(g.debts.map(i => i.name)).toEqual(['Кредит на машину']);
        expect(g.archived.map(i => i.name)).toEqual(['Дача']);
    });

    it('вторая карта «на всё» — дебетовая или наличные с остатком, не основная, — в «Основной» (п. 10)', () => {
        const t = moneyGroups([main, second, oldCash, cash], []);
        expect(t.together.map(a => a.name)).toEqual(['Вторая', 'Кошелёк']);
        expect(t.spending.map(a => a.name)).toEqual(['Наличные']);
    });

    it('новый человек — основная и наличные, остальное пусто (п. 11)', () => {
        const t = moneyGroups([cash, main], []);
        expect([t.primary?.name, t.credits.length, t.together.length, t.spending.length, t.savings.length])
            .toEqual(['Основная карта', 0, 0, 1, 0]);
        expect(t.assets.length + t.debts.length + t.archived.length).toBe(0);
    });
});

describe('строка карты', () => {
    const funds = [fund('Отпуск', 'Вклад'), fund('Ремонт', null)];
    const line = (a: Account) => plain(accountLine(a, funds, '2026-11-22'));

    it('основная — «обновлён», без обновления — «первое обновление» (опись 24, 25)', () => {
        expect(line(main)).toEqual({ sub: ['обновлён 12 нояб.'], value: '48 800 ₽', caption: null, updated: true });
        expect(line({ ...main, balance: null, balanceDate: null }))
            .toEqual({ sub: ['первое обновление'], value: null, caption: null, updated: false });
    });

    it('кредитка — долг из лимита, «возвращаю до», доступно (опись 22, 23, 27)', () => {
        expect(line(credit)).toEqual({
            sub: ['долг 15 000 из 100 000', 'возвращаю до 90 000'], value: '85 000 ₽', caption: 'доступно', updated: false,
        });
    });

    it('карта для трат — категория и «записано в ноябре» (опись 26)', () => {
        expect(line(petrol)).toEqual({ sub: ['Бензин · остаток не нужен'], value: '5 000 ₽', caption: 'записано в ноябре', updated: false });
        expect(line({ ...petrol, purposeCategoryName: null, allocatedThisMonth: null }))
            .toEqual({ sub: ['остаток не нужен'], value: '0 ₽', caption: 'записано в ноябре', updated: false });
    });

    it('накопительный — его копилки и дата обновления', () => {
        expect(line(deposit)).toEqual({ sub: ['копилка «Отпуск» · обновлён 1 нояб.'], value: '60 000 ₽', caption: null, updated: false });
        expect(accountLine(deposit, [fund('Отпуск', 'Вклад'), fund('НЗ', 'Вклад')], '2026-11-22').sub)
            .toEqual(['копилки «Отпуск», «НЗ» · обновлён 1 нояб.']);
    });

    it('вторая «на всё» — «считается вместе с основной» (п. 10)', () => {
        expect(line(second).sub).toEqual(['считается вместе с основной']);
    });
});

describe('шапка капитала (п. 8)', () => {
    it('есть — деньги и имущество, должен — все долги', () => {
        const s = { total: 653800, liquid: 138800, assetsTotal: 850000, liabilitiesTotal: 335000 } as CapitalSummary;
        expect(capitalHead(s)).toEqual({ has: 988800, owes: 335000 });
    });
});

describe('суммы не округляются вверх (ANO-84, п. 2)', () => {
    it('копейки — если есть, двумя знаками', () => {
        expect(plain(fmtAmount(99999.99))).toBe('99 999,99 ₽');
        expect(plain(fmtAmount(99999.9))).toBe('99 999,90 ₽');
        expect(plain(fmtAmount(48800))).toBe('48 800 ₽');
    });
    it('знак — плюс или минус, ноль без знака', () => {
        expect(plain(fmtSigned(12400))).toBe('+12 400 ₽');
        expect(plain(fmtSigned(-320000.5))).toBe('−320 000,50 ₽');
        expect(plain(fmtSigned(0))).toBe('0 ₽');
    });
});

describe('месяц словами', () => {
    it('«в ноябре», «в мае»', () => {
        expect(monthIn('2026-11-22')).toBe('в ноябре');
        expect(monthIn('2026-05-01')).toBe('в мае');
    });
});

describe('график капитала (п. 8)', () => {
    const today = '2026-11-22';
    it('по умолчанию «Всё», пока первой точке меньше года, потом «Год»', () => {
        expect(defaultRange('2025-12-22', today)).toBe('all');   // 11 месяцев
        expect(defaultRange('2025-10-22', today)).toBe('year');  // 13 месяцев
        expect(defaultRange('2025-11-22', today)).toBe('year');  // ровно год
        expect(defaultRange(null, today)).toBe('all');
    });

    const pts = (dates: [string, number][]): CapitalTrajectory['points'] =>
        dates.map(([date, capital]) => ({ date, capital, liquid: 0, assets: 0, liabilities: 0 }));
    const all = pts([['2025-05-31', 500], ['2026-05-31', 612000], ['2026-08-31', 630000], ['2026-10-31', 640000], ['2026-11-22', 653800]]);

    it('период — точки с начала периода', () => {
        expect(rangePoints(all, 'all', today)).toHaveLength(5);
        expect(rangePoints(all, 'year', today).map(p => p.date)).toEqual(['2026-05-31', '2026-08-31', '2026-10-31', '2026-11-22']);
        expect(rangePoints(all, 'quarter', today).map(p => p.date)).toEqual(['2026-08-31', '2026-10-31', '2026-11-22']);
        expect(rangePoints(all, '6m', today).map(p => p.date)).toEqual(['2026-05-31', '2026-08-31', '2026-10-31', '2026-11-22']);
        expect(rangePoints(all, '3y', today)).toHaveLength(5);
    });

    it('изменение у графика — последняя точка минус первая', () => {
        expect(rangeChange(rangePoints(all, 'year', today))).toEqual({ amount: 41800, from: '2026-05-31' });
        expect(rangeChange(pts([['2026-11-22', 1]]))).toBeNull();
    });
});
