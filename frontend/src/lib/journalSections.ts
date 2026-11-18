import type { FinancialEvent } from '../types/api';
import { PRIORITY_DOT_CONFIG } from './priority';
import { byRecencyDesc, compareByRecency } from './eventOrder';
import { fmtDayShort } from './format';

/**
 * Журнал на два списка (ANO-176): что идёт в недели, а что — в карточки ожиданий.
 *
 * Это правило, а не вид. На дизайн-сессии журнал будут перерисовывать — раскладка уйдёт,
 * правило останется, поэтому оно живёт здесь, а не в разметке страницы.
 *
 * Спека: docs/superpowers/specs/2026-09-24-journal-two-lists-design.md
 */

/** Плановая строка-ожидание: сумма примерная, тратится по ходу. Правило по характеру, а не по типу. */
export const isExpectationPlan = (e: FinancialEvent): boolean =>
    e.eventKind === 'PLAN' && e.priority === 'MEDIUM';

/** Основной список: брони, хотелки и все факты — факт всегда точен, в том числе факт ожидания. */
export const mainListEvents = (events: FinancialEvent[]): FinancialEvent[] =>
    events.filter((e) => !isExpectationPlan(e));

const DOW = ['вс', 'пн', 'вт', 'ср', 'чт', 'пт', 'сб'];

/** «пн 7». ISO-дата разбирается руками: new Date('yyyy-mm-dd') читает её как UTC и вечером сдвигает день. */
export function dayLabel(iso: string): string {
    const [y, m, d] = iso.split('-').map(Number);
    return `${DOW[new Date(y, m - 1, d).getDay()]} ${d}`;
}

/**
 * `?date=` журнала — «Открыть день в журнале» с листа дня (срез 2, п. 8): месяц и год из даты, прокрутка к дню.
 * Не дата или несуществующий день — null: журнал открывается как обычно, на текущем месяце.
 */
export function journalDate(param: string | null): { iso: string; year: number; month: number } | null {
    if (!param || !/^\d{4}-\d{2}-\d{2}$/.test(param)) return null;
    const [y, m, d] = param.split('-').map(Number);
    const dt = new Date(y, m - 1, d);
    if (dt.getFullYear() !== y || dt.getMonth() !== m - 1 || dt.getDate() !== d) return null;
    return { iso: param, year: y, month: m - 1 };
}

export interface ExpectationLine {
    id: string;
    label: string;
    /** На строку уже записан факт — «✓» у даты. Сколько записано, не показывается: канон, граница по запрету 1. */
    recorded: boolean;
    event: FinancialEvent;
}

export interface ExpectationCard {
    key: string;
    title: string;
    count: number;
    /** Сумма одной строки, если у всех строк она одинакова, — «4 × 8 000 ₽»; иначе null и показывается итог. */
    each: number | null;
    total: number;
    income: boolean;
    lines: ExpectationLine[];
    /** Описания строк с их датой — «сб 26 — «День рождения друга»». */
    notes: string[];
}

const datedExpectations = (events: FinancialEvent[]) => events
    .filter((e) => isExpectationPlan(e) && e.date != null)
    .sort((a, b) => (a.date! < b.date! ? -1 : a.date! > b.date! ? 1 : 0));

/** Одна карточка на категорию; строки — по дате; карточки — по дате первой строки. */
export function expectationCards(events: FinancialEvent[]): ExpectationCard[] {
    const groups = new Map<string, FinancialEvent[]>();
    for (const e of datedExpectations(events)) {
        const key = e.type === 'FUND_TRANSFER' ? `fund:${e.targetFundId ?? ''}` : e.categoryId;
        groups.set(key, [...(groups.get(key) ?? []), e]);
    }
    return [...groups].map(([key, rows]) => {
        const amounts = rows.map((e) => e.plannedAmount ?? 0);
        const first = rows[0];
        return {
            key,
            title: first.type === 'FUND_TRANSFER' ? (first.targetFundName ?? first.categoryName) : first.categoryName,
            count: rows.length,
            each: amounts.every((a) => a === amounts[0]) ? amounts[0] : null,
            total: amounts.reduce((s, a) => s + a, 0),
            income: first.type === 'INCOME',
            lines: rows.map((e) => ({
                id: e.id,
                label: dayLabel(e.date!),
                recorded: e.linkedFactsCount > 0 || e.status === 'EXECUTED' || e.factAmount != null,
                event: e,
            })),
            notes: rows.filter((e) => e.description).map((e) => `${dayLabel(e.date!)} — «${e.description}»`),
        };
    });
}

/**
 * «14 строк · 53 100 ₽» — для заголовка колонки и свёрнутого блока. Сумма — только того, что
 * уходит: примерный доход, сложенный с расходами, дал бы число, которое ничего не значит.
 */
export function expectationsSummary(events: FinancialEvent[]): { count: number; total: number } {
    const rows = datedExpectations(events);
    const outflow = rows.filter((e) => e.type !== 'INCOME');
    return { count: rows.length, total: outflow.reduce((s, e) => s + (e.plannedAmount ?? 0), 0) };
}

// ── Журнал по дням (срез 3, п. 6; доски Zh2-J8, Zh2-J8b, S3-F7) ───────────────────────────────────────

/** Строка журнала — что нарисовать; вид — у страницы. */
export interface JournalRow {
    event: FinancialEvent;
    /** Запись — кружок залит; план — контур. */
    fact: boolean;
    kind: 'bron' | 'ozh' | 'hot';
    name: string;
    /** «Продукты · к ожиданию», «сегодня · бронь · оплачено 10 000». */
    sub: string;
    /** У плана — остаток: оплаченное несут его записи. */
    amount: number | null;
    income: boolean;
    /** План-хотелка приглушён (Zh2-J8b). */
    muted: boolean;
    /** Пара для подсветки наведением: план и его записи (ANO-42). */
    group: string;
}

export interface JournalDay { iso: string; title: string; plans: JournalRow[]; records: JournalRow[] }

export interface JournalWeek {
    label: string;
    start: string;
    end: string;
    /** Неделя с сегодня — без заголовка, всегда открыта. */
    current: boolean;
    /** Открыта сразу: текущая, а в месяце без неё — свежая. */
    open: boolean;
    count: number;
    days: JournalDay[];
}

export interface JournalAhead {
    title: string;
    count: number;
    /** «Связь 15-го, Зарплата 25-го» — строка свёрнутого «Впереди». */
    summary: string;
    groups: { label: string; rows: JournalRow[] }[];
}

export interface JournalView { ahead: JournalAhead | null; weeks: JournalWeek[] }

const KIND = { HIGH: 'bron', MEDIUM: 'ozh', LOW: 'hot' } as const;
const IN_MONTH = ['январе', 'феврале', 'марте', 'апреле', 'мае', 'июне', 'июле', 'августе', 'сентябре', 'октябре', 'ноябре', 'декабре'];
const rub = (n: number) => Math.round(n).toLocaleString('ru-RU').replace(/\s/g, '\u00a0');
const iso = (d: Date) =>
    `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
const parse = (s: string) => { const [y, m, d] = s.split('-').map(Number); return new Date(y, m - 1, d); };
const shift = (s: string, days: number) => { const d = parse(s); d.setDate(d.getDate() + days); return iso(d); };
const dow = (s: string) => DOW[parse(s).getDay()];
const cap = (s: string) => s[0].toUpperCase() + s.slice(1);

/** «сегодня», «вчера», «15 нояб., вс». */
function dateWord(date: string, today: string): string {
    if (date === today) return 'сегодня';
    if (date === shift(today, -1)) return 'вчера';
    return `${fmtDayShort(date)}, ${dow(date)}`;
}

/** Недели месяца с понедельника, обрезанные краями месяца. */
function monthWeeks(year: number, month: number): { start: string; end: string }[] {
    const first = iso(new Date(year, month, 1));
    const last = iso(new Date(year, month + 1, 0));
    const weeks = [];
    let monday = shift(first, -((parse(first).getDay() + 6) % 7));
    while (monday <= last) {
        const sunday = shift(monday, 6);
        weeks.push({ start: monday < first ? first : monday, end: sunday > last ? last : sunday });
        monday = shift(monday, 7);
    }
    return weeks;
}

/** «2–8 нояб.», «1 нояб.». */
const weekLabel = (w: { start: string; end: string }) =>
    w.start === w.end ? fmtDayShort(w.end) : `${parse(w.start).getDate()}–${fmtDayShort(w.end)}`;

const remainderOf = (p: FinancialEvent) => (p.plannedAmount ?? 0) - (p.linkedFactsAmount ?? 0);

/** План закрыт записями — его несут они; план не рисуется. */
const settledByFacts = (p: FinancialEvent) => p.linkedFactsCount > 0 && remainderOf(p) <= 0;
/** План закрыт старым путём, суммой в самом плане (ANO-25), — записей нет, он сам стоит записью. */
const settledInPlace = (p: FinancialEvent) =>
    p.linkedFactsCount === 0 && (p.status === 'EXECUTED' || p.factAmount != null);

/** Связь записи с планом словами: «к ожиданию», «по брони», «часть брони», «к хотелке». */
function linkWord(f: FinancialEvent, byId: Map<string, FinancialEvent>): string | null {
    if (!f.parentEventId) return null;
    const plan = byId.get(f.parentEventId);
    if (!plan) return f.parentPlanDescription ? `к плану «${f.parentPlanDescription}»` : null;
    if (plan.priority === 'MEDIUM') return 'к ожиданию';
    if (plan.priority === 'LOW') return 'к хотелке';
    return plan.linkedFactsCount === 1 && remainderOf(plan) <= 0 ? 'по брони' : 'часть брони';
}

const nameOf = (e: FinancialEvent) =>
    e.type === 'FUND_TRANSFER' ? (e.targetFundName ?? 'Копилка') : (e.description || e.categoryName);

function recordRow(e: FinancialEvent, byId: Map<string, FinancialEvent>): JournalRow {
    const sub = e.type === 'FUND_TRANSFER'
        ? 'Накопление · в копилку'
        : [e.description ? e.categoryName : null, e.eventKind === 'FACT' ? linkWord(e, byId) : null].filter(Boolean).join(' · ');
    return {
        event: e, fact: true, kind: KIND[e.priority], name: nameOf(e), sub,
        amount: e.factAmount ?? e.plannedAmount, income: e.type === 'INCOME', muted: false,
        group: e.parentEventId ?? e.id,
    };
}

function planRow(e: FinancialEvent, today: string): JournalRow {
    const income = e.type === 'INCOME';
    const paid = e.linkedFactsAmount ?? 0;
    const sub = [
        dateWord(e.date!, today),
        e.type === 'FUND_TRANSFER' ? 'в копилку' : PRIORITY_DOT_CONFIG[e.priority].name.toLowerCase(),
        paid > 0 ? `${income ? 'получено' : 'оплачено'} ${rub(paid)}` : null,
    ].filter(Boolean).join(' · ');
    return {
        event: e, fact: false, kind: KIND[e.priority], name: nameOf(e), sub,
        amount: e.plannedAmount == null ? null : remainderOf(e), income, muted: e.priority === 'LOW', group: e.id,
    };
}

/**
 * Журнал месяца по дням (срез 3, п. 6): будущее — свёрнутым «Впереди»; прошлое и сегодня — неделями, свежая первой,
 * в дне план блоком «по плану» над записями. Ожидания — не здесь: у них свой блок.
 */
export function journalView(events: FinancialEvent[], year: number, month: number, today: string): JournalView {
    const byId = new Map(events.filter((e) => e.eventKind === 'PLAN').map((e) => [e.id, e] as const));
    const rows = mainListEvents(events)
        .filter((e) => e.date != null && !(e.eventKind === 'PLAN' && settledByFacts(e)))
        .map((e) => (e.eventKind === 'FACT' || settledInPlace(e) ? recordRow(e, byId) : planRow(e, today)));

    const weeks = monthWeeks(year, month);
    const weekOf = (date: string) => weeks.find((w) => w.start <= date && date <= w.end)!;

    const future = rows.filter((r) => r.event.date! > today).sort((a, b) => compareByRecency(a.event, b.event));
    const groups: JournalAhead['groups'] = [];
    for (const r of future) {
        const w = weekOf(r.event.date!);
        const label = w.start <= today ? `Эта неделя, ${weekLabel(w)}` : weekLabel(w);
        if (groups.at(-1)?.label !== label) groups.push({ label, rows: [] });
        groups.at(-1)!.rows.push(r);
    }
    const ahead = future.length === 0 ? null : {
        title: `Впереди в ${IN_MONTH[month]}`,
        count: future.length,
        summary: future.map((r) => `${r.name} ${parse(r.event.date!).getDate()}-го`).join(', '),
        groups,
    };

    const past = rows.filter((r) => r.event.date! <= today);
    const shown = weeks.filter((w) => w.start <= today).reverse();
    const hasCurrent = shown.some((w) => w.end >= today);
    return {
        ahead,
        weeks: shown.map((w, i) => {
            const inWeek = past.filter((r) => w.start <= r.event.date! && r.event.date! <= w.end);
            const dates = [...new Set(inWeek.map((r) => r.event.date!))].sort().reverse();
            const current = w.end >= today;
            return {
                label: weekLabel(w), start: w.start, end: w.end, current, open: current || (!hasCurrent && i === 0), count: inWeek.length,
                days: dates.map((date) => {
                    const word = dateWord(date, today);
                    const title = date === today || date === shift(today, -1)
                        ? `${cap(word)}, ${fmtDayShort(date)}, ${dow(date)}`
                        : `${cap(dow(date))}, ${fmtDayShort(date)}`;
                    const ofDay = inWeek.filter((r) => r.event.date === date);
                    return {
                        iso: date, title,
                        plans: ofDay.filter((r) => !r.fact).sort((a, b) => compareByRecency(a.event, b.event)),
                        records: ofDay.filter((r) => r.fact).sort((a, b) => byRecencyDesc(a.event, b.event)),
                    };
                }),
            };
        }),
    };
}
