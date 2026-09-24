import { describe, expect, it } from 'vitest';
import type { FinancialEvent } from '../types/api';
import { anchorDateOf, quickClose } from './quickClose';

const TODAY = '2026-09-23';
const ANCHOR = '2026-09-13';

const plan = (over: Partial<FinancialEvent> = {}): FinancialEvent => ({
    id: 'p', date: '2026-09-15', categoryId: 'net', categoryName: 'Связь', type: 'EXPENSE',
    plannedAmount: 800, factAmount: null, status: 'PLANNED', priority: 'HIGH', description: 'Интернет',
    rawInput: null, createdAt: '2026-09-01T10:00:00', eventKind: 'PLAN', parentEventId: null,
    linkedFactsCount: 0, linkedFactsAmount: null, parentPlanDescription: null, ...over,
});

describe('кружок закрытия брони (ANO-176)', () => {
    it('дата наступила, сверка была раньше — касание пишет факт на всю бронь датой плана', () => {
        expect(quickClose(plan(), TODAY, ANCHOR)).toEqual({ kind: 'tap', amount: 800, date: '2026-09-15' });
    });

    it('частичный факт — касание дописывает остаток, а не сумму плана', () => {
        expect(quickClose(plan({ plannedAmount: 45000, linkedFactsCount: 1, linkedFactsAmount: 20000 }), TODAY, ANCHOR))
            .toEqual({ kind: 'tap', amount: 25000, date: '2026-09-15' });
    });

    it('дата раньше сверки — касание просит дату: платёж мог пройти и до сверки, и после', () => {
        expect(quickClose(plan({ date: '2026-09-03' }), TODAY, ANCHOR))
            .toEqual({ kind: 'askDate', amount: 800, anchorDate: ANCHOR });
    });

    it('день сверки — одно касание: факт, записанный сейчас, посчитается (AnchorWindow)', () => {
        expect(quickClose(plan({ date: ANCHOR }), TODAY, ANCHOR).kind).toBe('tap');
    });

    it('сверок нет — одно касание', () => {
        expect(quickClose(plan({ date: '2026-09-03' }), TODAY, null).kind).toBe('tap');
    });

    it('сегодняшняя дата — одно касание', () => {
        expect(quickClose(plan({ date: TODAY }), TODAY, ANCHOR)).toEqual({ kind: 'tap', amount: 800, date: TODAY });
    });

    it('дата впереди — кружка нет', () => {
        expect(quickClose(plan({ date: '2026-09-25' }), TODAY, ANCHOR).kind).toBe('none');
    });

    it('не бронь, факт, перевод в копилку, нет даты или суммы — кружка нет', () => {
        expect(quickClose(plan({ priority: 'MEDIUM' }), TODAY, ANCHOR).kind).toBe('none');
        expect(quickClose(plan({ priority: 'LOW' }), TODAY, ANCHOR).kind).toBe('none');
        expect(quickClose(plan({ eventKind: 'FACT' }), TODAY, ANCHOR).kind).toBe('none');
        expect(quickClose(plan({ type: 'FUND_TRANSFER' }), TODAY, ANCHOR).kind).toBe('none');
        expect(quickClose(plan({ date: null }), TODAY, ANCHOR).kind).toBe('none');
        expect(quickClose(plan({ plannedAmount: null }), TODAY, ANCHOR).kind).toBe('none');
    });

    it('покрыто фактами, исполнено или старый факт прямо в плане — галочка, у любого характера', () => {
        expect(quickClose(plan({ linkedFactsCount: 1, linkedFactsAmount: 800 }), TODAY, ANCHOR).kind).toBe('done');
        expect(quickClose(plan({ status: 'EXECUTED' }), TODAY, ANCHOR).kind).toBe('done');
        expect(quickClose(plan({ factAmount: 800 }), TODAY, ANCHOR).kind).toBe('done');
        expect(quickClose(plan({ priority: 'LOW', status: 'EXECUTED' }), TODAY, ANCHOR).kind).toBe('done');
        expect(quickClose(plan({ type: 'FUND_TRANSFER', status: 'EXECUTED' }), TODAY, ANCHOR).kind).toBe('done');
    });
});

describe('какая сверка — последняя основного счёта не позже сегодня', () => {
    const accounts = [{ id: 'card', isDefault: true }, { id: 'deposit', isDefault: false }];

    it('основной счёт, а не последняя сверка по всей таблице', () => {
        const checkpoints = [
            { accountId: 'card', date: '2026-09-13' },
            { accountId: 'card', date: '2026-08-20' },
            { accountId: 'deposit', date: '2026-09-20' },
        ];
        expect(anchorDateOf(checkpoints, accounts, TODAY)).toBe('2026-09-13');
    });

    it('сверка позже сегодня не считается', () => {
        expect(anchorDateOf([{ accountId: 'card', date: '2026-09-30' }, { accountId: 'card', date: '2026-09-01' }],
            accounts, TODAY)).toBe('2026-09-01');
    });

    it('нет основного счёта или его сверок — сверки нет', () => {
        expect(anchorDateOf([{ accountId: 'deposit', date: '2026-09-01' }], accounts, TODAY)).toBeNull();
        expect(anchorDateOf([{ accountId: 'card', date: '2026-09-01' }], [], TODAY)).toBeNull();
    });
});
