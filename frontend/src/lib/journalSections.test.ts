import { describe, expect, it } from 'vitest';
import type { FinancialEvent } from '../types/api';
import {
    expectationCards, expectationsSummary, isExpectationPlan, journalDate, journalView, mainListEvents,
} from './journalSections';

const ev = (over: Partial<FinancialEvent> = {}): FinancialEvent => ({
    id: 'e1', date: '2026-09-07', categoryId: 'food', categoryName: 'Еда / Продукты', type: 'EXPENSE',
    plannedAmount: 8000, factAmount: null, status: 'PLANNED', priority: 'MEDIUM', description: null,
    rawInput: null, createdAt: '2026-09-01T10:00:00', eventKind: 'PLAN', parentEventId: null,
    linkedFactsCount: 0, linkedFactsAmount: null, parentPlanDescription: null, ...over,
});
const fact = (over: Partial<FinancialEvent> = {}) =>
    ev({ eventKind: 'FACT', plannedAmount: null, factAmount: 7350, ...over });

describe('что куда (ANO-176)', () => {
    it('ожидание уходит из недель; бронь, хотелка и любой факт остаются', () => {
        const events = [
            ev({ id: 'exp', priority: 'MEDIUM' }),
            ev({ id: 'bron', priority: 'HIGH' }),
            ev({ id: 'wish', priority: 'LOW' }),
            fact({ id: 'factOfExp', priority: 'MEDIUM', parentEventId: 'exp' }),
        ];
        expect(mainListEvents(events).map((e) => e.id)).toEqual(['bron', 'wish', 'factOfExp']);
        expect(events.filter(isExpectationPlan).map((e) => e.id)).toEqual(['exp']);
    });

    it('правило по характеру, а не по типу: ожидаемый доход — тоже ожидание', () => {
        expect(isExpectationPlan(ev({ type: 'INCOME', priority: 'MEDIUM' }))).toBe(true);
        expect(isExpectationPlan(ev({ type: 'INCOME', priority: 'HIGH' }))).toBe(false);
    });
});

describe('карточки ожиданий', () => {
    it('одна карточка на категорию; строки по дате; карточки — по первой дате', () => {
        const cards = expectationCards([
            ev({ id: 'f28', date: '2026-09-28' }),
            ev({ id: 'cafe12', date: '2026-09-12', categoryId: 'cafe', categoryName: 'Кафе', plannedAmount: 3000 }),
            ev({ id: 'f7', date: '2026-09-07' }),
            ev({ id: 'bron', date: '2026-09-05', priority: 'HIGH' }),
        ]);
        expect(cards.map((c) => c.title)).toEqual(['Еда / Продукты', 'Кафе']);
        expect(cards[0].lines.map((l) => l.id)).toEqual(['f7', 'f28']);
        expect(cards[0].lines.map((l) => l.label)).toEqual(['пн 7', 'пн 28']);
    });

    it('равные суммы — «сколько × сколько»; разные — только итог', () => {
        const [equal, mixed] = expectationCards([
            ev({ id: 'a', date: '2026-09-07' }), ev({ id: 'b', date: '2026-09-14' }),
            ev({ id: 'c', date: '2026-09-12', categoryId: 'cafe', categoryName: 'Кафе', plannedAmount: 3000 }),
            ev({ id: 'd', date: '2026-09-26', categoryId: 'cafe', categoryName: 'Кафе', plannedAmount: 4500 }),
        ]);
        expect(equal).toMatchObject({ count: 2, each: 8000, total: 16000 });
        expect(mixed).toMatchObject({ count: 2, each: null, total: 7500 });
    });

    it('«✓» — на строку записан факт; ничего не считается «осталось»', () => {
        const [card] = expectationCards([
            ev({ id: 'a', date: '2026-09-07', linkedFactsCount: 1, linkedFactsAmount: 7350 }),
            ev({ id: 'b', date: '2026-09-14' }),
        ]);
        expect(card.lines.map((l) => l.recorded)).toEqual([true, false]);
        expect(Object.keys(card)).not.toContain('remaining');
    });

    it('описание строки — примечание с её датой', () => {
        const [card] = expectationCards([
            ev({ id: 'a', date: '2026-09-26', description: 'День рождения друга' }),
            ev({ id: 'b', date: '2026-09-12' }),
        ]);
        expect(card.notes).toEqual(['сб 26 — «День рождения друга»']);
    });

    it('сводка месяца — сколько строк и на какую сумму', () => {
        expect(expectationsSummary([
            ev({ plannedAmount: 8000 }), ev({ plannedAmount: 3000 }), ev({ priority: 'HIGH', plannedAmount: 45000 }),
        ])).toEqual({ count: 2, total: 11000 });
    });

    it('в сумму сводки примерный доход не складывается с расходами', () => {
        expect(expectationsSummary([
            ev({ plannedAmount: 8000 }), ev({ type: 'INCOME', categoryId: 'side', plannedAmount: 20000 }),
        ])).toEqual({ count: 2, total: 8000 });
    });
});

describe('журнал на дату — ?date= (срез 2, п. 8)', () => {
    it('дата — месяц и год журнала, месяц с нуля', () => {
        expect(journalDate('2026-11-15')).toEqual({ iso: '2026-11-15', year: 2026, month: 10 });
        expect(journalDate('2027-01-01')).toEqual({ iso: '2027-01-01', year: 2027, month: 0 });
    });

    it('не дата и несуществующий день — журнал как обычно', () => {
        for (const bad of [null, '', '15.11.2026', '2026-11', '2026-02-30', '2026-13-01', '2026-11-15T00:00', '2026-11-5']) {
            expect(journalDate(bad)).toBeNull();
        }
    });
});

// ── Журнал по дням (срез 3, п. 6; доски Zh2-J8, Zh2-J8b, S3-F7) ────────────────────────────────────────

describe('журнал по дням (срез 3, п. 6)', () => {
    // Ноябрь 2026: 1-е — воскресенье; сегодня — чт 12 нояб., неделя 9–15.
    const TODAY = '2026-11-12';
    const sp = (s: string) => s.replace(/ /g, ' ');
    const at = (id: string, date: string, over: Partial<FinancialEvent> = {}) =>
        fact({ id, date, createdAt: `${date}T10:00:00`, priority: 'LOW', categoryName: 'Транспорт', ...over });
    const plan = (id: string, date: string, over: Partial<FinancialEvent> = {}) =>
        ev({ id, date, createdAt: '2026-11-01T09:00:00', priority: 'HIGH', categoryName: 'Жильё и быт', ...over });
    const view = (events: FinancialEvent[], today = TODAY, year = 2026, month = 10) =>
        journalView(events, year, month, today);

    it('недели — свежая первой; неделя с сегодня — без заголовка и открыта; прошлые свёрнуты «2–8 нояб.»', () => {
        const j = view([at('a', '2026-11-03'), at('b', '2026-11-05'), at('c', '2026-11-10'), at('d', '2026-11-12')]);
        expect(j.weeks.map((w) => [w.label, w.current, w.open, w.count])).toEqual([
            ['9–15 нояб.', true, true, 2],
            ['2–8 нояб.', false, false, 2],
            ['1 нояб.', false, false, 0],
        ]);
    });

    it('дни — свежий первым; «Сегодня», «Вчера», дальше день недели', () => {
        const j = view([at('a', '2026-11-09'), at('b', '2026-11-11'), at('c', '2026-11-12')]);
        expect(j.weeks[0].days.map((d) => d.title)).toEqual(['Сегодня, 12 нояб., чт', 'Вчера, 11 нояб., ср', 'Пн, 9 нояб.']);
    });

    it('в дне — план блоком над записями; записи — свежая первой', () => {
        const j = view([
            at('early', '2026-11-12', { createdAt: '2026-11-12T08:00:00' }),
            plan('rent', '2026-11-12'),
            at('late', '2026-11-12', { createdAt: '2026-11-12T20:00:00' }),
        ]);
        const [day] = j.weeks[0].days;
        expect(day.plans.map((r) => r.event.id)).toEqual(['rent']);
        expect(day.records.map((r) => r.event.id)).toEqual(['late', 'early']);
    });

    it('ожидания — не в днях: их место — блок ожиданий', () => {
        const j = view([ev({ id: 'exp', date: '2026-11-09', priority: 'MEDIUM' }), at('a', '2026-11-09')]);
        expect(j.weeks[0].days[0].plans).toEqual([]);
        expect(j.weeks[0].days[0].records.map((r) => r.event.id)).toEqual(['a']);
    });

    it('оплаченная бронь уходит — её несут записи «по брони»; оплаченная частью — остаток и «оплачено»', () => {
        const j = view([
            plan('net', '2026-11-11', { plannedAmount: 600, linkedFactsCount: 1, linkedFactsAmount: 600, status: 'EXECUTED' }),
            fact({ id: 'netFact', date: '2026-11-11', priority: 'HIGH', parentEventId: 'net', description: 'Интернет', categoryName: 'Связь и подписки', factAmount: 600 }),
            plan('rent', '2026-11-12', { plannedAmount: 25000, linkedFactsCount: 1, linkedFactsAmount: 10000 }),
            fact({ id: 'rentFact', date: '2026-11-12', priority: 'HIGH', parentEventId: 'rent', description: 'Аренда', categoryName: 'Жильё и быт', factAmount: 10000 }),
        ]);
        const [today, yesterday] = j.weeks[0].days;
        expect(today.plans.map((r) => [r.event.id, r.amount, sp(r.sub)])).toEqual([['rent', 15000, 'сегодня · бронь · оплачено 10 000']]);
        expect(today.records.map((r) => r.sub)).toEqual(['Жильё и быт · часть брони']);
        expect(yesterday.plans).toEqual([]);
        expect(yesterday.records.map((r) => [r.name, r.sub])).toEqual([['Интернет', 'Связь и подписки · по брони']]);
    });

    it('план, закрытый старым путём — суммой в самом плане (ANO-25), — стоит записью', () => {
        const j = view([plan('old', '2026-11-10', { plannedAmount: 900, factAmount: 900, status: 'EXECUTED' })]);
        const [row] = j.weeks[0].days[0].records;
        expect([row.event.id, row.fact, row.amount]).toEqual(['old', true, 900]);
    });

    it('«Впереди в ноябре» — строки после сегодня по неделям, «Эта неделя» у текущей', () => {
        const j = view([
            plan('salary', '2026-11-25', { description: 'Зарплата', type: 'INCOME', categoryName: 'Зарплата', plannedAmount: 80000 }),
            plan('phone', '2026-11-15', { description: 'Связь' }),
            plan('wish', '2026-11-28', { description: 'Наушники', priority: 'LOW' }),
            plan('rent', '2026-11-12'),
        ]);
        expect(j.ahead).toMatchObject({ title: 'Впереди в ноябре', count: 3, summary: 'Связь 15-го, Зарплата 25-го, Наушники 28-го' });
        expect(j.ahead!.groups.map((g) => [g.label, g.rows.map((r) => r.event.id)])).toEqual([
            ['Эта неделя, 9–15 нояб.', ['phone']],
            ['23–29 нояб.', ['salary', 'wish']],
        ]);
        expect(j.ahead!.groups[0].rows[0].sub).toBe('15 нояб., вс · бронь');
        expect(j.ahead!.groups[1].rows.map((r) => [r.income, r.muted])).toEqual([[true, false], [false, true]]);
        expect(view([plan('rent', '2026-11-12')]).ahead).toBeNull();
    });

    it('строка записи: имя и категория, связь словами — к ожиданию, к хотелке, перевод в копилку', () => {
        const exp = ev({ id: 'exp', date: '2026-11-09', categoryName: 'Продукты' });
        const wish = plan('wish', '2026-11-09', { priority: 'LOW', description: 'Кино' });
        const j = view([
            exp, wish,
            fact({ id: 'a', date: '2026-11-09', parentEventId: 'exp', description: 'Пятёрочка', categoryName: 'Продукты', createdAt: '2026-11-09T05:00:00' }),
            fact({ id: 'b', date: '2026-11-09', parentEventId: 'exp', categoryName: 'Продукты', createdAt: '2026-11-09T04:00:00' }),
            fact({ id: 'c', date: '2026-11-09', parentEventId: 'wish', priority: 'LOW', categoryName: 'Досуг и поездки', createdAt: '2026-11-09T03:00:00' }),
            fact({ id: 'd', date: '2026-11-09', type: 'FUND_TRANSFER', targetFundName: 'Отпуск', categoryName: 'Переводы в копилки', createdAt: '2026-11-09T02:00:00' }),
            fact({ id: 'e', date: '2026-11-09', parentEventId: 'august', parentPlanDescription: 'Отпуск', categoryName: 'Досуг и поездки', createdAt: '2026-11-09T01:00:00' }),
        ]);
        expect(j.weeks[0].days[0].records.map((r) => [r.name, r.sub, r.fact])).toEqual([
            ['Пятёрочка', 'Продукты · к ожиданию', true],
            ['Продукты', 'к ожиданию', true],
            ['Досуг и поездки', 'к хотелке', true],
            ['Отпуск', 'Накопление · в копилку', true],
            ['Досуг и поездки', 'к плану «Отпуск»', true],
        ]);
    });

    it('вид строки — цветом кружка; подсветка пары — план и его записи', () => {
        const j = view([plan('rent', '2026-11-12', { linkedFactsCount: 1, linkedFactsAmount: 1 }),
            fact({ id: 'f', date: '2026-11-12', priority: 'HIGH', parentEventId: 'rent' }), at('w', '2026-11-12')]);
        const [day] = j.weeks[0].days;
        expect(day.plans.map((r) => [r.kind, r.fact, r.group])).toEqual([['bron', false, 'rent']]);
        expect(day.records.map((r) => [r.kind, r.group])).toEqual([['hot', 'w'], ['bron', 'rent']]);
    });

    it('прошлый месяц: открыта свежая неделя, остальные свёрнуты; будущий — только «Впереди»', () => {
        const past = view([at('a', '2026-10-05'), at('b', '2026-10-28')], TODAY, 2026, 9);
        expect(past.weeks.map((w) => [w.label, w.current, w.open])).toEqual([
            ['26–31 окт.', false, true], ['19–25 окт.', false, false], ['12–18 окт.', false, false],
            ['5–11 окт.', false, false], ['1–4 окт.', false, false],
        ]);
        const future = view([plan('x', '2026-12-03')], TODAY, 2026, 11);
        expect(future.weeks).toEqual([]);
        expect(future.ahead).toMatchObject({ title: 'Впереди в декабре', count: 1 });
    });
});
