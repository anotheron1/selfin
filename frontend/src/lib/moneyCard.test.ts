import { describe, expect, it } from 'vitest';
import type { Account, BalanceCheckpoint, CapitalItem, Category } from '../types/api';
import {
    ANSWERS, answerLabel, canMakeMain, cardFormProblem, cardHistory, cardView, cardWriteFailure, createDto, creditCheckItems,
    creditDebtLine as creditDebtLine0, deleteCardFailure, diffLine as diffLine0, historyChart, makeMainFailure,
    pointNote as pointNote0, savingsSince as savingsSince0, spendCategories, updateDto, WORDS,
} from './moneyCard';

// Суммы — с неразрывным пробелом (fmtNum): в ожиданиях — обычный.
const sp = (s: string | null) => s && s.replace(/ /g, ' ');
const diffLine = (...a: Parameters<typeof diffLine0>) => sp(diffLine0(...a));
const pointNote = (...a: Parameters<typeof pointNote0>) => sp(pointNote0(...a));
const savingsSince = (...a: Parameters<typeof savingsSince0>) => sp(savingsSince0(...a));
const creditDebtLine = (...a: Parameters<typeof creditDebtLine0>) => sp(creditDebtLine0(...a));

const acc = (over: Partial<Account>): Account => ({
    id: 'a', name: 'Карта', kind: 'DEBIT', trackBalance: true, purposeCategoryId: null, purposeCategoryName: null,
    creditLimit: null, availableFloor: null, isDefault: false, sortOrder: 0, balance: null, balanceDate: null, debt: null,
    allocatedThisMonth: null, floorSuggestion: null, ...over,
});
const cp = (id: string, date: string, amount: number, computedBalance: number | null, accountId = 'a'): BalanceCheckpoint => ({
    id, date, amount, accountId, accountName: '', createdAt: `${date}T10:00:00Z`, computedBalance,
    drift: computedBalance == null ? null : amount - computedBalance,
});
const err = (status: number) => Object.assign(new Error('API error'), { status });

describe('вид листа (п. 12)', () => {
    it('основная, «на всё», кредитка, накопительная, для трат', () => {
        expect(cardView(acc({ isDefault: true }))).toBe('main');
        expect(cardView(acc({ kind: 'CASH', trackBalance: true }))).toBe('together');
        expect(cardView(acc({ kind: 'CREDIT' }))).toBe('credit');
        expect(cardView(acc({ kind: 'DEPOSIT' }))).toBe('savings');
        expect(cardView(acc({ kind: 'CASH', trackBalance: false }))).toBe('spending');
        expect(cardView(acc({ kind: 'DEBIT', trackBalance: false }))).toBe('spending');
    });

    it('ответ строкой листа: у основной — «Основная», у второй «на всё» — свой', () => {
        expect(answerLabel(acc({ isDefault: true }))).toBe('Основная');
        expect(answerLabel(acc({}))).toBe('На всё, вместе с основной');
        expect(answerLabel(acc({ kind: 'CREDIT' }))).toBe('Кредитка');
        expect(answerLabel(acc({ kind: 'DEPOSIT' }))).toBe('Накопительная');
        expect(answerLabel(acc({ trackBalance: false }))).toBe('Для отдельных трат');
    });

    it('ответов три — «на всё» и «основной» нет (п. 4, п. 10)', () => {
        expect(ANSWERS.map(a => a.value)).toEqual(['SPEND', 'SAVINGS', 'CREDIT']);
        expect(ANSWERS.every(a => a.hint.length > 0)).toBe(true);
    });

    it('«Сделать основной» — у карты «на всё» с обновлением; у кредитки, накопительной, для трат и без обновления — нет', () => {
        expect(canMakeMain(acc({ balanceDate: '2026-11-01' }))).toBe(true);
        expect(canMakeMain(acc({ kind: 'CASH', balanceDate: '2026-11-01' }))).toBe(true);
        expect(canMakeMain(acc({ balanceDate: null }))).toBe(false);
        expect(canMakeMain(acc({ isDefault: true, balanceDate: '2026-11-01' }))).toBe(false);
        expect(canMakeMain(acc({ kind: 'CREDIT', balanceDate: '2026-11-01' }))).toBe(false);
        expect(canMakeMain(acc({ kind: 'DEPOSIT', balanceDate: '2026-11-01' }))).toBe(false);
        expect(canMakeMain(acc({ trackBalance: false, balanceDate: '2026-11-01' }))).toBe(false);
    });
});

describe('«Добавить карту» (п. 13): запрос', () => {
    it('для трат — ответ и категория, без остатка и кредитных полей', () => {
        expect(createDto({ name: ' Бензин ', purpose: 'SPEND', categoryId: 'c', balance: '500', limit: '1', floor: '1' }))
            .toEqual({ name: 'Бензин', purpose: 'SPEND', purposeCategoryId: 'c' });
    });
    it('накопительная — остаток, без категории', () => {
        expect(createDto({ name: 'Вклад', purpose: 'SAVINGS', categoryId: 'c', balance: '60 000', limit: '', floor: '' }))
            .toEqual({ name: 'Вклад', purpose: 'SAVINGS', balance: 60000 });
    });
    it('кредитка — лимит, доступно и «возвращаю до»; пустое не уходит', () => {
        expect(createDto({ name: 'Кредитка', purpose: 'CREDIT', categoryId: null, balance: '85000', limit: '100000', floor: '90000' }))
            .toEqual({ name: 'Кредитка', purpose: 'CREDIT', creditLimit: 100000, availableFloor: 90000, balance: 85000 });
        expect(createDto({ name: 'Кредитка', purpose: 'CREDIT', categoryId: null, balance: '', limit: '100000', floor: '' }))
            .toEqual({ name: 'Кредитка', purpose: 'CREDIT', creditLimit: 100000 });
    });
});

describe('правка карты — по осям', () => {
    it('без нового ответа оси прежние', () => {
        const a = acc({ kind: 'CASH', trackBalance: true });
        expect(updateDto(a, { name: 'Кошелёк', purpose: null, categoryId: null, limit: '', floor: '' }))
            .toEqual({ name: 'Кошелёк', kind: 'CASH', trackBalance: true, purposeCategoryId: null, creditLimit: null, availableFloor: null });
    });
    it('новый ответ — как при заведении; наличные остаются наличными', () => {
        expect(updateDto(acc({ kind: 'CASH' }), { name: 'Н', purpose: 'SPEND', categoryId: 'c', limit: '', floor: '' }))
            .toMatchObject({ kind: 'CASH', trackBalance: false, purposeCategoryId: 'c' });
        expect(updateDto(acc({ kind: 'DEPOSIT' }), { name: 'Н', purpose: 'SPEND', categoryId: 'c', limit: '', floor: '' }))
            .toMatchObject({ kind: 'DEBIT', trackBalance: false });
        expect(updateDto(acc({}), { name: 'Н', purpose: 'SAVINGS', categoryId: 'c', limit: '', floor: '' }))
            .toMatchObject({ kind: 'DEPOSIT', trackBalance: true, purposeCategoryId: null });
        expect(updateDto(acc({}), { name: 'Н', purpose: 'CREDIT', categoryId: null, limit: '100000', floor: '90000' }))
            .toMatchObject({ kind: 'CREDIT', trackBalance: true, creditLimit: 100000, availableFloor: 90000 });
    });
    it('кредитные поля — только у кредитки', () => {
        expect(updateDto(acc({ kind: 'CREDIT' }), { name: 'Н', purpose: 'SAVINGS', categoryId: null, limit: '100000', floor: '1' }))
            .toMatchObject({ creditLimit: null, availableFloor: null });
    });
});

describe('отказы словами экрана (опись 31–32)', () => {
    it('«возвращаю до» выше лимита — до отправки; равно — можно', () => {
        expect(cardFormProblem({ purpose: 'CREDIT', limit: '100000', floor: '120000' })).toBe(WORDS.floorAboveLimit);
        expect(cardFormProblem({ purpose: 'CREDIT', limit: '100000', floor: '100000' })).toBeNull();
        expect(cardFormProblem({ purpose: 'CREDIT', limit: '', floor: '150000' })).toBeNull();
        expect(cardFormProblem({ purpose: 'CREDIT', limit: '100000', floor: '' })).toBeNull();
        expect(cardFormProblem({ purpose: 'SAVINGS', limit: '100000', floor: '120000' })).toBeNull();
    });
    it('смена через кредитку с остатками — 400 своими словами; прочее — общее', () => {
        expect(cardWriteFailure(err(400), acc({ kind: 'CREDIT' }), 'DEBIT')).toBe(WORDS.kindAcrossCredit);
        expect(cardWriteFailure(err(400), acc({}), 'CREDIT')).toBe(WORDS.kindAcrossCredit);
        expect(cardWriteFailure(err(400), acc({}), 'DEPOSIT')).not.toBe(WORDS.kindAcrossCredit);
        expect(cardWriteFailure(err(500), acc({ kind: 'CREDIT' }), 'DEBIT')).not.toBe(WORDS.kindAcrossCredit);
    });
    it('удаление: 409 — копилка на карте', () => {
        expect(deleteCardFailure(err(409))).toBe(WORDS.deleteHoldsGoal);
        expect(deleteCardFailure(err(500))).not.toBe(WORDS.deleteHoldsGoal);
    });
    it('«Сделать основной»: 400 — сначала обновить остатки (решение 65)', () => {
        expect(makeMainFailure(err(400))).toBe(WORDS.updateFirst);
        expect(makeMainFailure(err(500))).not.toBe(WORDS.updateFirst);
    });
    it('старых слов нет', () => {
        const all = JSON.stringify(WORDS) + JSON.stringify(ANSWERS);
        for (const w of ['приёмник', 'якор', 'планк', 'Природа', 'Зона ответственности', 'Слежу']) expect(all).not.toContain(w);
    });
});

describe('история обновлений (п. 12)', () => {
    const list = [cp('2', '2026-11-12', 48800, 48500), cp('x', '2026-11-01', 1, null, 'b'), cp('1', '2026-10-14', 51000, null)];

    it('только этой карты, по дате', () => {
        expect(cardHistory(list, 'a').map(c => c.id)).toEqual(['1', '2']);
    });

    it('разница словами; рост накопительной — «обычно это проценты»', () => {
        expect(diffLine(cp('1', '2026-10-14', 51000, null), false)).toBe('первое обновление');
        expect(diffLine(cp('2', '2026-11-12', 48800, 48500), false)).toBe('в банке на 300 ₽ больше, чем по записям');
        expect(diffLine(cp('2', '2026-11-12', 48800, 50000), false)).toBe('в банке на 1 200 ₽ меньше, чем по записям');
        expect(diffLine(cp('2', '2026-11-12', 48800, 48800), false)).toBe('сошлось с записями');
        expect(diffLine(cp('2', '2026-11-03', 22000, 21820), true)).toBe('в банке на 180 ₽ больше, чем по записям, — обычно это проценты');
        expect(diffLine(cp('2', '2026-11-03', 22000, 22100), true)).toBe('в банке на 100 ₽ меньше, чем по записям');
    });

    it('нажатая точка: дата, сумма, «по записям было»', () => {
        expect(pointNote(cp('2', '2026-11-12', 48800, 48500), false))
            .toBe('По записям было 48 500 ₽: в банке на 300 ₽ больше.');
        expect(pointNote(cp('1', '2026-10-14', 51000, null), false)).toBe('Первое обновление.');
        expect(pointNote(cp('2', '2026-11-12', 48800, 48800), false)).toBe('По записям было 48 800 ₽: сошлось.');
    });

    it('график: точки — обновления; пунктир идёт до «по записям» следующей точки и до сегодня', () => {
        const c = historyChart(cardHistory(list, 'a'), { date: '2026-11-22', value: 47000 }, 300, 100);
        expect(c.dots.map(d => d.id)).toEqual(['1', '2']);
        // 1 → по записям 48 500 к 12 нояб. → 48 800 из банка → 47 000 сегодня
        expect(c.path.split('L')).toHaveLength(4);
        expect(c.dots[0].x).toBeLessThan(c.dots[1].x);
        expect(c.dots[0].y).toBeLessThan(c.dots[1].y); // 51 000 выше 48 800
        expect(c.labels.map(l => l.text)).toEqual(['14 окт.', '22 нояб.']);
    });

    it('график без «сегодня» — пунктир кончается последней точкой', () => {
        const c = historyChart(cardHistory(list, 'a'), null, 300, 100);
        expect(c.path.split('L')).toHaveLength(3);
        expect(c.labels.map(l => l.text)).toEqual(['14 окт.', '12 нояб.']);
    });
});

describe('строки листа', () => {
    it('накопительная: обновление и переводы после него', () => {
        const last = cp('2', '2026-11-03', 22000, 21820);
        expect(savingsSince(last, 30000, '2026-11-22')).toBe('Обновлён 3 нояб. на 22 000 ₽, после — отложено 8 000 ₽.');
        expect(savingsSince(last, 19000, '2026-11-22')).toBe('Обновлён 3 нояб. на 22 000 ₽, после — снято 3 000 ₽.');
        expect(savingsSince(last, 22000, '2026-11-22')).toBe('Обновлён 3 нояб. на 22 000 ₽.');
    });

    it('кредитка: «Обновлена» — без двойной точки после сокращения месяца', () => {
        expect(WORDS.creditSince('12 нояб.', '8 000 ₽')).toMatch(/^Обновлена 12 нояб\. Долг — 8 000 ₽ до лимита\. /);
        expect(WORDS.creditSince('сегодня', '8 000 ₽')).toMatch(/^Обновлена сегодня\. Долг/);
    });

    it('кредитка: долг в «Свободно» не входит — только если он есть', () => {
        expect(creditDebtLine('100000', '85000')).toBe(
            'Долг 15 000 ₽ в «Свободно» не входит — он виден на «Сегодня» строкой «если вернуть на кредитку».');
        expect(creditDebtLine('100000', '100000')).toBeNull();
        expect(creditDebtLine('', '85000')).toBeNull();
        expect(creditDebtLine('100000', '')).toBeNull();
    });

    it('категории «Для каких трат» — расходные, без «Накопления»', () => {
        const cats = [
            { id: '1', name: 'Бензин', type: 'EXPENSE' }, { id: '2', name: 'Зарплата', type: 'INCOME' },
            { id: '3', name: 'Накопление', type: 'EXPENSE', systemKind: 'FUND_TRANSFER' },
        ] as Category[];
        expect(spendCategories(cats).map(c => c.id)).toEqual(['1']);
    });
});

describe('«Не та же ли это кредитка?» (п. 14)', () => {
    const debt = (name: string, isArchived = false, kind: CapitalItem['kind'] = 'LIABILITY'): CapitalItem => ({
        id: name, kind, name, description: null, createdAt: '', currentValue: 15000, lastValuedAt: '2026-09-01', isArchived,
    });

    it('только живые ручные долги; похожие на имя карты — первыми', () => {
        const items = [debt('Ипотека'), debt('Кредитка Т-Банка'), debt('Старый', true), debt('Машина', false, 'ASSET')];
        expect(creditCheckItems(items, 'Т-Банк кредитка').map(i => i.id)).toEqual(['Кредитка Т-Банка', 'Ипотека']);
    });

    it('живого долга нет — вопроса нет', () => {
        expect(creditCheckItems([debt('Старый', true)], 'Кредитка')).toEqual([]);
    });
});
