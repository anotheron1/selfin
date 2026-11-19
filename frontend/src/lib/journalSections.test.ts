import { describe, expect, it } from 'vitest';
import type { FinancialEvent } from '../types/api';
import {
    afterMonth, expectationGroups, expectationsHeader, isExpectationPlan, journalDate, journalView, mainListEvents,
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

describe('ожидания месяца (срез 3, п. 10; доски Zh2-J8, S3-X1, S3-X4, S3-X5)', () => {
    // Ноябрь 2026: 1-е — воскресенье; сегодня — чт 12 нояб., неделя 9–15.
    const TODAY = '2026-11-12';
    const sp = (s: string) => s.replace(/ /g, ' ');
    const groups = (events: FinancialEvent[], today = TODAY) => expectationGroups(events, 2026, 10, today);
    const weekly = ['2026-11-02', '2026-11-09', '2026-11-16', '2026-11-23', '2026-11-30']
        .map((date, i) => ev({ id: `w${i}`, date, categoryName: 'Продукты' }));

    it('одна группа на категорию; группы — по первой дате; бронь и хотелка не ожидания', () => {
        const g = groups([
            ev({ id: 'f28', date: '2026-11-28' }),
            ev({ id: 'cafe', date: '2026-11-12', categoryId: 'cafe', categoryName: 'Кафе', plannedAmount: 3000 }),
            ev({ id: 'f7', date: '2026-11-07' }),
            ev({ id: 'bron', date: '2026-11-05', priority: 'HIGH' }),
            ev({ id: 'wish', date: '2026-11-05', priority: 'LOW' }),
        ]);
        expect(g.map((x) => x.title)).toEqual(['Еда / Продукты', 'Кафе']);
        expect(g[0].preset).toEqual({ type: 'EXPENSE', categoryId: 'food' });
    });

    it('неделей: «8 000 ₽ · неделя», «каждый понедельник»; неделя с сегодня — «Эта неделя», прошедшая — «прошла»', () => {
        const [g] = groups(weekly);
        expect(sp(g.caption)).toBe('8 000 ₽ · неделя');
        expect(sp(g.subtitle)).toBe('ожидание · каждый понедельник по 8 000 ₽');
        expect(g.weeks.map((w) => [sp(w.label), w.current, w.past])).toEqual([
            ['2–8 нояб., прошла', false, true],
            ['Эта неделя, 9–15 нояб.', true, false],
            ['16–22 нояб.', false, false],
            ['23–29 нояб.', false, false],
            ['30 нояб.', false, false],
        ]);
        expect(g.weeks[g.open].current).toBe(true);
    });

    it('разовое — одной строкой с датой; «около 15 000 ₽ · 20 нояб., пт»', () => {
        const [g] = groups([ev({ id: 'car', date: '2026-11-20', categoryName: 'Ремонт машины', plannedAmount: 15000 })]);
        expect(g.single).toBe(true);
        expect(sp(g.caption)).toBe('≈ 15 000 ₽');
        expect(sp(g.subtitle)).toBe('ожидание · около 15 000 ₽ · 20 нояб., пт');
        expect(g.weeks.map((w) => sp(w.label))).toEqual(['20 нояб., пт']);
        expect(g.footnote).toContain('раньше 20-го');
    });

    it('строки с разными датами и суммами — «≈ итог» и «около … в ноябре»; в неделе с равными суммами — «N ×»', () => {
        const [g] = groups([
            ev({ id: 'a', date: '2026-11-12', plannedAmount: 500 }), ev({ id: 'b', date: '2026-11-13', plannedAmount: 500 }),
            ev({ id: 'c', date: '2026-11-16', plannedAmount: 500 }), ev({ id: 'd', date: '2026-11-17', plannedAmount: 700 }),
        ]);
        expect(sp(g.caption)).toBe('≈ 2 200 ₽');
        expect(sp(g.subtitle)).toBe('ожидание · около 2 200 ₽ в ноябре');
        expect(g.weeks.map((w) => [w.planned, w.each, w.lines.length])).toEqual([[1000, 500, 2], [1200, null, 2]]);
    });

    it('«записано X» — сумма записей к строкам недели (linkedFactsAmount) и сами записи; «осталось» не считается', () => {
        const [g] = groups([
            ...weekly.map((w) => (w.id === 'w1' ? { ...w, linkedFactsCount: 2, linkedFactsAmount: 2440 } : w)),
            fact({ id: 'q', date: '2026-11-11', parentEventId: 'w1', factAmount: 1200, createdAt: '2026-11-11T10:00:00' }),
            fact({ id: 'p', date: '2026-11-12', parentEventId: 'w1', factAmount: 1240, description: 'Пятёрочка', createdAt: '2026-11-12T10:00:00' }),
            fact({ id: 'x', date: '2026-11-11', factAmount: 300 }),
            fact({ id: 'y', date: '2026-11-03', parentEventId: 'w0', factAmount: 500 }),
        ]);
        const week = g.weeks[1];
        expect(week.recorded).toBe(2440);
        expect(week.records.map((r) => [r.name, r.when, r.amount])).toEqual([
            ['Пятёрочка', 'сегодня', 1240], ['Еда / Продукты', 'вчера', 1200],
        ]);
        expect(JSON.stringify(g)).not.toMatch(/remain|осталось|over|превыш/i);
    });


    it('неделя, что кончается сегодня, — эта, а не прошедшая', () => {
        const [g] = groups(weekly, '2026-11-15');
        expect(g.weeks.map((w) => [sp(w.label), w.current, w.past]).slice(0, 2)).toEqual([
            ['2–8 нояб., прошла', false, true],
            ['Эта неделя, 9–15 нояб.', true, false],
        ]);
    });

    it('строки через 7 дней, но с разными суммами, — не «неделей»', () => {
        const [g] = groups([ev({ id: 'a', date: '2026-11-02' }), ev({ id: 'b', date: '2026-11-09', plannedAmount: 9000 })]);
        expect(sp(g.caption)).toBe('≈ 17 000 ₽');
    });

    it('у разового название — заметка, а не подзаголовок', () => {
        const [g] = groups([ev({ id: 'car', date: '2026-11-20', plannedAmount: 15000, description: 'Стоматолог' })]);
        expect(g.notes.map(sp)).toEqual(['пт 20 — 15 000 ₽ «Стоматолог»']);
        expect(g.subtitle).not.toContain('«');
    });
    it('запись следующего месяца к строке 30-го — в листе; планы следующего месяца ожиданиями не становятся (ревью Codex)', () => {
        // Запись 1 дек. встаёт к строке 30 нояб. (шесть дней назад, ExpectationMatch) — журнал ноября её не читает.
        const after = [
            fact({ id: 'dec1', date: '2026-12-01', parentEventId: 'w4', factAmount: 900, createdAt: '2026-12-01T10:00:00' }),
            ev({ id: 'decPlan', date: '2026-12-07', categoryId: 'cafe', categoryName: 'Кафе' }),
        ];
        const g = expectationGroups(weekly, 2026, 10, TODAY, after);
        expect(g.map((x) => x.title)).toEqual(['Продукты']);
        expect(g[0].weeks[4].records.map((r) => r.id)).toEqual(['dec1']);
    });

    it('дочитать после месяца — шесть дней: строка 30-го собирает записи до 6-го', () => {
        expect(afterMonth(2026, 10)).toEqual({ start: '2026-12-01', end: '2026-12-06' });
        expect(afterMonth(2026, 11)).toEqual({ start: '2027-01-01', end: '2027-01-06' });
    });

    it('погашенная строка — та же строка с «записано», не пропадает', () => {
        const [g] = groups([ev({ id: 'a', date: '2026-11-03', plannedAmount: 8000, linkedFactsCount: 3, linkedFactsAmount: 8650 })]);
        expect(g.weeks[0].recorded).toBe(8650);
        expect(g.weeks[0].planned).toBe(8000);
    });

    it('месяц без сегодня: открыта ближайшая впереди, в прошлом месяце — последняя', () => {
        expect(groups(weekly, '2026-10-20')[0].open).toBe(0);
        expect(groups(weekly, '2026-12-20')[0].open).toBe(4);
    });

    it('заметки строк — с датой и суммой', () => {
        const [g] = groups([
            ev({ id: 'a', date: '2026-11-29', plannedAmount: 2000, description: 'Закупка к празднику' }),
            ev({ id: 'b', date: '2026-11-12' }),
        ]);
        expect(g.notes.map(sp)).toEqual(['вс 29 — 2 000 ₽ «Закупка к празднику»']);
    });

    it('одно название у всех строк серии — в подзаголовке один раз, без заметок у каждой строки', () => {
        const [g] = groups(weekly.map((w) => ({ ...w, description: 'Бензин' })));
        expect(g.notes).toEqual([]);
        expect(sp(g.subtitle)).toBe('ожидание · «Бензин» · каждый понедельник по 8 000 ₽');
    });

    it('доходное ожидание — «+» и «Поступления»', () => {
        const [g] = groups([ev({ id: 'i', type: 'INCOME', date: '2026-11-20', categoryName: 'Подработка', plannedAmount: 20000 })]);
        expect(sp(g.caption)).toBe('≈ +20 000 ₽');
        expect(g.footnote.startsWith('Поступления')).toBe(true);
    });
});

describe('заголовок «Ожидания месяца» (п. 10; ошибка journalSections.ts:89)', () => {
    const sp = (s: string | null) => s?.replace(/ /g, ' ') ?? null;

    it('строки и сумма по одному набору: доходная строка в счёте — и в сумме «придёт»', () => {
        // Было: «2 строки · 8 000 ₽» — доход в счёте строк, а сумма только расходов.
        expect(sp(expectationsHeader([
            ev({ plannedAmount: 8000 }), ev({ type: 'INCOME', categoryId: 'side', plannedAmount: 20000 }),
        ]))).toBe('2 строки · уйдёт 8 000 ₽ · придёт 20 000 ₽');
    });

    it('только расходы — «N строк · сумма»; брони и хотелки не считаются', () => {
        expect(sp(expectationsHeader([
            ev({ plannedAmount: 8000 }), ev({ plannedAmount: 3000 }), ev({ priority: 'HIGH', plannedAmount: 45000 }),
        ]))).toBe('2 строки · 11 000 ₽');
    });

    it('ожиданий нет — заголовка с числами нет', () => {
        expect(expectationsHeader([ev({ priority: 'HIGH' })])).toBeNull();
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
            // Снятие из копилки — перевод с минусом (TargetFundService.transferToPocket; ревью Codex на #232).
            fact({ id: 'g', date: '2026-11-09', type: 'FUND_TRANSFER', targetFundName: 'Машина', factAmount: -400, categoryName: 'Переводы в копилки', createdAt: '2026-11-09T00:30:00' }),
        ]);
        expect(j.weeks[0].days[0].records.map((r) => [r.name, r.sub, r.fact])).toEqual([
            ['Пятёрочка', 'Продукты · к ожиданию', true],
            ['Продукты', 'к ожиданию', true],
            ['Досуг и поездки', 'к хотелке', true],
            ['Отпуск', 'Накопление · в копилку', true],
            ['Досуг и поездки', 'к плану «Отпуск»', true],
            ['Машина', 'Накопление · из копилки', true],
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

describe('журнал по дням — края недели и брони', () => {
    const at = (id: string, date: string, over: Partial<FinancialEvent> = {}) =>
        fact({ id, date, createdAt: `${date}T10:00:00`, priority: 'LOW', categoryName: 'Транспорт', ...over });
    const plan = (id: string, date: string, over: Partial<FinancialEvent> = {}) =>
        ev({ id, date, createdAt: '2026-11-01T09:00:00', priority: 'HIGH', categoryName: 'Жильё и быт', ...over });

    it('сегодня — воскресенье: неделя ещё текущая', () => {
        const j = journalView([at('a', '2026-11-15')], 2026, 10, '2026-11-15');
        expect(j.weeks[0]).toMatchObject({ label: '9–15 нояб.', current: true, open: true });
    });

    it('сегодня — понедельник: строка впереди этой недели — «Эта неделя»', () => {
        const j = journalView([plan('p', '2026-11-12')], 2026, 10, '2026-11-09');
        expect(j.ahead!.groups.map((g) => g.label)).toEqual(['Эта неделя, 9–15 нояб.']);
    });

    it('бронь оплачена двумя записями — у каждой «часть брони»', () => {
        const j = journalView([
            plan('rent', '2026-11-10', { plannedAmount: 25000, linkedFactsCount: 2, linkedFactsAmount: 25000, status: 'EXECUTED' }),
            fact({ id: 'f1', date: '2026-11-10', priority: 'HIGH', parentEventId: 'rent', createdAt: '2026-11-10T09:00:00' }),
            fact({ id: 'f2', date: '2026-11-10', priority: 'HIGH', parentEventId: 'rent', createdAt: '2026-11-10T10:00:00' }),
        ], 2026, 10, '2026-11-12');
        expect(j.weeks[0].days[0].records.map((r) => r.sub)).toEqual(['часть брони', 'часть брони']);
    });

    it('счёт свёрнутой недели — все её строки, и планы тоже', () => {
        const j = journalView([plan('p', '2026-11-03'), at('a', '2026-11-04')], 2026, 10, '2026-11-12');
        expect(j.weeks[1]).toMatchObject({ label: '2–8 нояб.', count: 2 });
    });

    it('план в прошлом дне — без даты: она в заголовке дня; «вчера» — словом', () => {
        const j = journalView([plan('p', '2026-11-10'), plan('y', '2026-11-11')], 2026, 10, '2026-11-12');
        expect(j.weeks[0].days.map((d) => d.plans[0].sub)).toEqual(['вчера · бронь', 'бронь']);
    });
});
