import { describe, it, expect } from 'vitest';
import {
    monthlyFor, monthsFor, refKey, sameRef, realizationScope, lastDayOfMonth, defaultTryOn,
} from './sandboxMath';
import type { SandboxItem } from '../types/api';

describe('sandboxMath: ползунок взнос ↔ месяцы', () => {
    it('monthlyFor делит сумму на месяцы, ≤1 → вся сумма', () => {
        expect(monthlyFor(80000, 5)).toBe(16000);
        expect(monthlyFor(8500, 1)).toBe(8500);
        expect(monthlyFor(8500, 0)).toBe(8500);
        expect(monthlyFor(100, 3)).toBe(33.33);
    });

    it('monthsFor обратно к monthlyFor, минимум 1', () => {
        expect(monthsFor(80000, 16000)).toBe(5);
        expect(monthsFor(8500, 8500)).toBe(1);
        expect(monthsFor(8500, 0)).toBe(1);
    });
});

describe('sandboxMath: refs', () => {
    it('refKey и sameRef', () => {
        expect(refKey({ type: 'FUND', id: 'x' })).toBe('FUND:x');
        expect(sameRef({ type: 'EVENT', id: 'a' }, { type: 'EVENT', id: 'a' })).toBe(true);
        expect(sameRef({ type: 'EVENT', id: 'a' }, { type: 'FUND', id: 'a' })).toBe(false);
        expect(sameRef(null, null)).toBe(true);
        expect(sameRef(null, { type: 'FUND', id: 'a' })).toBe(false);
    });
});

describe('sandboxMath: realizationScope (§7 «до реализации появляется/исчезает»)', () => {
    it('нет включённых датированных → null', () => {
        expect(realizationScope([])).toBeNull();
        expect(realizationScope([null, undefined])).toBeNull();
    });

    it('две датированные → DATE:максимальная', () => {
        expect(realizationScope(['2026-12-14', '2026-09-30'])).toBe('DATE:2026-12-14');
        expect(realizationScope([null, '2026-08-20'])).toBe('DATE:2026-08-20');
    });
});

describe('sandboxMath: lastDayOfMonth', () => {
    it('последний день месяца', () => {
        expect(lastDayOfMonth('2026-08-15')).toBe('2026-08-31');
        expect(lastDayOfMonth('2026-02-10')).toBe('2026-02-28');
        expect(lastDayOfMonth('2026-11-02')).toBe('2026-11-30');
    });
});

describe('sandboxMath: defaultTryOn', () => {
    it('копилка → дефолты из серверных полей', () => {
        const item: SandboxItem = {
            ref: { type: 'FUND', id: 'f' }, kind: 'SAVINGS', name: 'Горнолыжка',
            amount: 60000, date: '2026-12-14', stretchMonthsMax: 5, stretchMonthsDefault: 5,
            creditRate: null, creditTermMonths: null, wishlistStatus: 'FIXED', inBaseline: true,
        };
        const t = defaultTryOn(item);
        expect(t.ref).toEqual({ type: 'FUND', id: 'f' });
        expect(t.amount).toBe(60000);
        expect(t.date).toBe('2026-12-14');
        expect(t.stretchMonths).toBe(5);
    });

    it('хотелка без даты → date пустая, stretch 0', () => {
        const item: SandboxItem = {
            ref: { type: 'EVENT', id: 'e' }, kind: 'WISHLIST', name: 'Рюкзак',
            amount: 8500, date: null, stretchMonthsMax: null, stretchMonthsDefault: 0,
            creditRate: null, creditTermMonths: null, wishlistStatus: 'OPEN', inBaseline: false,
        };
        const t = defaultTryOn(item);
        expect(t.date).toBe('');
        expect(t.stretchMonths).toBe(0);
    });
});
