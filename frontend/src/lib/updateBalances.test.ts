import { describe, expect, it } from 'vitest';
import type { BalanceBatchResult, BalanceSheetCard } from '../types/api';
import * as U from './updateBalances';
import { batchOf, lastCreditId, rowName } from './updateBalances';

/** Строки листа «Обновить остатки» (срез 4, п. 16; доски S4-U1, S4-U4). Неразрывные пробелы сумм — обычными. */
const sp = <A extends unknown[]>(f: (...a: A) => string | null) => (...a: A) => f(...a)?.replace(/ /g, ' ') ?? null;
const rowCaption = sp(U.rowCaption), rowNote = sp(U.rowNote), onCreditLine = sp(U.onCreditLine);
const freeLine = sp(U.freeLine), savedText = sp(U.savedText);

const TODAY = '2026-11-23';
const card = (over: Partial<BalanceSheetCard> = {}): BalanceSheetCard => ({
    accountId: 'main', name: 'Основная карта', kind: 'DEBIT', isDefault: true,
    byRecords: 48_800, updatedOn: '2026-11-12', transfersSinceUpdate: null, ...over,
});
const savings = card({ accountId: 'dep', name: 'Отпуск', kind: 'DEPOSIT', isDefault: false, byRecords: 30_000, transfersSinceUpdate: 2_000 });
const credit = card({ accountId: 'cr', name: 'Кредитка', kind: 'CREDIT', isDefault: false, byRecords: 85_000 });
const together = card({ accountId: 'tg', name: 'Вторая', isDefault: false, byRecords: 10_000, transfersSinceUpdate: 0 });
const fresh = card({ accountId: 'new', name: 'Новая', isDefault: false, kind: 'DEPOSIT', byRecords: null, updatedOn: null });

describe('строка карты', () => {
    it('основная и накопительная — «по записям»: их число двигают записи и переводы', () => {
        expect(rowCaption(card(), TODAY)).toBe('по записям 48 800');
        expect(rowCaption(savings, TODAY)).toBe('по записям 30 000');
        // Переводов не было — всё равно «по записям»: рост вклада — проценты (Т9).
        expect(rowCaption({ ...savings, transfersSinceUpdate: 0 }, TODAY)).toBe('по записям 30 000');
    });
    it('кредитка и вторая «на всё» — дата обновления: записи их не двигают (как лист карты, PR 4)', () => {
        expect(rowCaption(credit, TODAY)).toBe('обновлён 12 нояб.');
        expect(rowCaption(together, TODAY)).toBe('обновлён 12 нояб.');
    });
    it('вторая «на всё» с переводами в свою копилку — «по записям»', () => {
        expect(rowCaption({ ...together, transfersSinceUpdate: 500 }, TODAY)).toBe('по записям 10 000');
    });
    it('без обновлений — «первое обновление»', () => {
        expect(rowCaption(fresh, TODAY)).toBe('первое обновление');
    });
    it('у кредитки поле — доступное', () => {
        expect(rowName(credit)).toBe('Кредитка, доступно');
        expect(rowName(card())).toBe('Основная карта');
    });
});

describe('разница словами', () => {
    it('основная: в банке больше или меньше, чем по записям', () => {
        expect(rowNote(card(), '48100')).toBe('В банке на 700 ₽ меньше, чем по записям.');
        expect(rowNote(card(), '50000')).toBe('В банке на 1 200 ₽ больше, чем по записям.');
        expect(rowNote(card(), '48800')).toBe('Сошлось с записями.');
    });
    it('накопительная: рост — «обычно это проценты», убыль — без них', () => {
        expect(rowNote(savings, '30240')).toBe('В банке на 240 ₽ больше, чем по записям, — обычно это проценты.');
        expect(rowNote(savings, '29000')).toBe('В банке на 1 000 ₽ меньше, чем по записям.');
    });
    it('основная с ростом — без процентов', () => {
        expect(rowNote(card(), '50000')).not.toContain('проценты');
    });
    it('кредитка и вторая «на всё» — с прошлым разом', () => {
        expect(rowNote(credit, '80500')).toBe('На 4 500 ₽ меньше, чем в прошлый раз.');
        expect(rowNote(together, '10500')).toBe('На 500 ₽ больше, чем в прошлый раз.');
        expect(rowNote(credit, '85000')).toBe('Как в прошлый раз.');
    });
    it('копейки — без округления', () => {
        expect(rowNote(card(), '48800,5')).toBe('В банке на 0,50 ₽ больше, чем по записям.');
    });
    it('пустое поле и первое обновление — строки нет', () => {
        expect(rowNote(card(), '')).toBeNull();
        expect(rowNote(card(), '  ')).toBeNull();
        expect(rowNote(fresh, '60000')).toBeNull();
    });
    it('нечитаемое и минус — сказать до отправки, с именем поля', () => {
        expect(rowNote(credit, 'много')).toBe('Не понял сумму в «Кредитка, доступно» — нужно число, можно 0.');
        expect(rowNote(card(), '-5')).toBe('Не понял сумму в «Основная карта» — нужно число, можно 0.');
    });
});

describe('запрос — только заполненные поля', () => {
    const cards = [card(), savings, credit];

    it('пустые пропущены, выражение посчитано, ноль — сумма', () => {
        expect(batchOf(cards, { main: '48100', dep: '', cr: '0' })).toEqual({
            entries: [{ accountId: 'main', amount: 48_100 }, { accountId: 'cr', amount: 0 }],
        });
        expect(batchOf(cards, { dep: '30000+240' })).toEqual({ entries: [{ accountId: 'dep', amount: 30_240 }] });
    });
    it('ничего не введено — пакет пустой', () => {
        expect(batchOf(cards, {})).toEqual({ entries: [] });
    });
    it('есть нечитаемое — пакета нет: молча ушло бы без этой карты', () => {
        expect(batchOf(cards, { main: '48100', cr: 'много' })).toBeNull();
    });
    it('порядок — как в листе, а не как вводили', () => {
        expect(batchOf(cards, { cr: '1', main: '2' })!.entries.map(e => e.accountId)).toEqual(['main', 'cr']);
    });
});

describe('под кредитками и «Свободно станет…»', () => {
    it('«в кредит записано» — под последней кредиткой', () => {
        expect(onCreditLine({ since: '2026-11-12', amount: 4_500 }, TODAY)).toBe('с 12 нояб. в кредит записано 4 500 ₽');
        expect(lastCreditId([card(), credit, { ...credit, accountId: 'cr2' }, savings])).toBe('cr2');
        expect(lastCreditId([card(), savings])).toBeNull();
    });
    it('«Свободно» станет — по предпросмотру', () => {
        const r: BalanceBatchResult = { freeBefore: 17_000, freeAfter: 16_300, cards: [] };
        expect(freeLine(r)).toBe('«Свободно» станет 16 300 ₽ вместо 17 000 ₽.');
        expect(freeLine({ ...r, freeAfter: 17_000 })).toBe('«Свободно» не изменится.');
    });
    it('срок назван, как в шапке: на «Сегодня» может быть выбран другой период', () => {
        const r: BalanceBatchResult = { freeBefore: -283_712, freeAfter: 49_800, cards: [] };
        expect(freeLine(r, '2026-12-05')).toBe('«Свободно до 5 дек.» станет 49 800 ₽ вместо −283 712 ₽.');
        expect(freeLine({ ...r, freeAfter: -283_712 }, '2026-12-05')).toBe('«Свободно до 5 дек.» не изменится.');
    });
});

describe('после «Сохранить» (S4-U4)', () => {
    const cards = [card(), savings, credit];

    it('«Свободно» вместо прежнего, разница основной, рост вклада — проценты; меньше — вспоминать траты не нужно (п. 16)', () => {
        expect(savedText({
            freeBefore: 17_000, freeAfter: 16_300,
            cards: [{ accountId: 'main', difference: -700 }, { accountId: 'dep', difference: 240 }],
        }, cards)).toBe('Остатки обновлены. «Свободно» — 16 300 ₽ вместо 17 000: в банке на 700 ₽ меньше, чем по записям.'
            + ' Вспоминать траты не нужно. «Отпуск»: +240 ₽ сверх переводов — обычно это проценты.');
    });
    it('основная больше — «больше», без «вспоминать»', () => {
        expect(savedText({ freeBefore: 1_000, freeAfter: 2_200, cards: [{ accountId: 'main', difference: 1_200 }] }, cards))
            .toBe('Остатки обновлены. «Свободно» — 2 200 ₽ вместо 1 000: в банке на 1 200 ₽ больше, чем по записям.');
    });
    it('«Свободно» сдвинулось не на разницу основной — разница отдельной фразой, а не объяснением (ревью #244)', () => {
        // Стенд: основная +1 200, а «Свободно» +333 512 — сервер отпустил прошедшие брони (решение 65).
        expect(savedText({ freeBefore: -283_712, freeAfter: 49_800, cards: [{ accountId: 'main', difference: 1_200 }] }, cards))
            .toBe('Остатки обновлены. «Свободно» — 49 800 ₽ вместо −283 712. Основная карта: в банке на 1 200 ₽ больше, чем по записям.');
        expect(savedText({ freeBefore: 17_000, freeAfter: 16_000, cards: [{ accountId: 'main', difference: -700 }] }, cards))
            .toBe('Остатки обновлены. «Свободно» — 16 000 ₽ вместо 17 000. Основная карта: в банке на 700 ₽ меньше, чем по записям.'
                + ' Вспоминать траты не нужно.');
        expect(savedText({ freeBefore: 17_000, freeAfter: 17_000, cards: [{ accountId: 'main', difference: 500 }] }, cards))
            .toBe('Остатки обновлены. «Свободно» не изменилось. Основная карта: в банке на 500 ₽ больше, чем по записям.');
    });
    it('совпадение — до копейки', () => {
        expect(savedText({ freeBefore: 0.1, freeAfter: 0.3, cards: [{ accountId: 'main', difference: 0.2 }] }, cards))
            .toBe('Остатки обновлены. «Свободно» — 0,30 ₽ вместо 0,10: в банке на 0,20 ₽ больше, чем по записям.');
    });
    it('«Свободно» прежнее — так и сказано; убыль вклада и кредитка — без строки', () => {
        expect(savedText({
            freeBefore: 17_000, freeAfter: 17_000,
            cards: [{ accountId: 'dep', difference: -100 }, { accountId: 'cr', difference: -4_500 }],
        }, cards)).toBe('Остатки обновлены. «Свободно» не изменилось.');
    });
    it('«Свободно» сдвинулось без разницы основной — только числа', () => {
        expect(savedText({
            freeBefore: 100, freeAfter: 300,
            cards: [{ accountId: 'main', difference: 0 }, { accountId: 'cr', difference: 200 }],
        }, cards)).toBe('Остатки обновлены. «Свободно» — 300 ₽ вместо 100.');
        expect(savedText({ freeBefore: 100, freeAfter: 300, cards: [{ accountId: 'main', difference: null }] }, cards))
            .toBe('Остатки обновлены. «Свободно» — 300 ₽ вместо 100.');
    });
    it('срок назван, как в шапке', () => {
        expect(savedText({ freeBefore: -283_712, freeAfter: 49_800, cards: [{ accountId: 'main', difference: 1_200 }] }, cards, '2026-12-05'))
            .toBe('Остатки обновлены. «Свободно до 5 дек.» — 49 800 ₽ вместо −283 712. Основная карта: в банке на 1 200 ₽ больше, чем по записям.');
        expect(savedText({ freeBefore: 17_000, freeAfter: 17_000, cards: [] }, cards, '2026-12-05'))
            .toBe('Остатки обновлены. «Свободно до 5 дек.» не изменилось.');
    });
});
