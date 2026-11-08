import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import {
    monthlyFor, monthsFor, refKey, sameRef, realizationScope, defaultTryOn, tryOnDate, movedTryOnNote,
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

// lastDayOfMonth / stretchTargetDate уехали на сервер вместе с ANO-34 §1
// (SandboxLayoutStretchTargetDateTest хранит ровно эти случаи).

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

/**
 * ANO-236. Хотелка с прошедшей датой не примерялась: строка слала дату как есть, сервер отвечал 400 «tryOn.date
 * обязательна и строго в будущем», и примерка сбрасывалась. Теперь — на 1-е число следующего месяца, с пометкой,
 * как карточка «Что с капиталом» подставляет срок хотелке без срока (ANO-29). Правило 5: ответ на устаревших данных.
 */
describe('примерка с прошедшей датой (ANO-236)', () => {
    const today = '2026-10-27';

    it('прошедшая дата и сегодняшняя — на 1-е число следующего месяца: сервер ждёт строго будущую', () => {
        expect(tryOnDate('2026-09-01', today)).toBe('2026-11-01');
        expect(tryOnDate('2026-10-27', today)).toBe('2026-11-01');
        expect(tryOnDate('2026-11-15', '2026-12-10')).toBe('2027-01-01');
    });

    it('будущая дата — как есть', () => {
        expect(tryOnDate('2026-10-28', today)).toBe('2026-10-28');
        expect(tryOnDate('2027-09-06', today)).toBe('2027-09-06');
    });

    it('пометка — о дате, а не о человеке; у будущей даты её нет', () => {
        expect(movedTryOnNote('2026-09-01', today)).toBe('Дата прошла — примеряю на 1 ноября');
        expect(movedTryOnNote('2026-10-27', today)).toBe('Дата сегодня — примеряю на 1 ноября');
        expect(movedTryOnNote('2026-10-28', today)).toBeNull();
    });

    it('страница шлёт подставленную дату только у хотелок; строка показывает пометку и не фиксирует молча (сторож)', () => {
        const page = readFileSync(new URL('../pages/Wishlist.tsx', import.meta.url), 'utf8');
        expect(page).toMatch(/state\.enabled\.filter\(t => t\.date\)\.map\(t => \(\{ \.\.\.t, date: tryOnDate\(t\.date, today\) \}\)\)/);
        expect(page).toMatch(/const today = serverTodayIso\(new Date\(\)\);/);
        expect(page).toMatch(/today=\{today\}/);
        const row = readFileSync(new URL('../components/sandbox/SandboxItemRow.tsx', import.meta.url), 'utf8');
        expect(row).toMatch(/const moved = date \? movedTryOnNote\(date, today\) : null;/);
        expect(row).toMatch(/\{moved && enabled && !locked && \(/);
        expect(row).toMatch(/disabled=\{needsDate \|\| moved != null\}/);
    });

    it('срок «до реализации» — из тех же подставленных дат, что уходят в запрос (ревью Codex на #211)', () => {
        // По исходным датам срок с прошедшей хотелкой уходил в прошлое, а с ещё одной на 15.11 кончался раньше
        // 1 декабря — и подставленный отток в расчёт не попадал.
        const page = readFileSync(new URL('../pages/Wishlist.tsx', import.meta.url), 'utf8');
        expect(page).toMatch(/\.\.\.state\.enabled\.map\(t => \(t\.date \? tryOnDate\(t\.date, today\) : t\.date\)\)/);
        expect(realizationScope(['2026-09-01', '2026-11-15'].map(d => tryOnDate(d, '2026-11-08')))).toBe('DATE:2026-12-01');
    });
});
