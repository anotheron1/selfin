import type { FinancialEvent } from '../types/api';
import { PRIORITY_DOT_CONFIG } from './priority';
import { byRecencyDesc, compareByRecency } from './eventOrder';
import { fmtDayShort } from './format';
import { ruPlural } from './plural';

/**
 * Журнал на два списка (ANO-176): что идёт в дни, а что — в ожидания месяца (кружки, лист, сетка недель).
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

/** Перевод с минусом — снятие из копилки (TargetFundService.transferToPocket), а не накопление в неё. */
const fundWay = (amount: number | null) => ((amount ?? 0) < 0 ? 'из копилки' : 'в копилку');

function recordRow(e: FinancialEvent, byId: Map<string, FinancialEvent>): JournalRow {
    const sub = e.type === 'FUND_TRANSFER'
        ? `Накопление · ${fundWay(e.factAmount ?? e.plannedAmount)}`
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
    // В дне дата уже в заголовке: у плана — только «сегодня» и «вчера», как на доске; иначе на 390 за край уходит
    // «оплачено 3 000». Во «Впереди» дата нужна.
    const word = dateWord(e.date!, today);
    const sub = [
        e.date! > today || e.date === today || e.date === shift(today, -1) ? word : null,
        e.type === 'FUND_TRANSFER' ? fundWay(e.plannedAmount) : PRIORITY_DOT_CONFIG[e.priority].name.toLowerCase(),
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

// ── Ожидания месяца (срез 3, п. 10; доски Zh2-J8, S3-X1, S3-X4, S3-X5) ─────────────────────────────────

/** Запись к строке ожидания — строка листа: «Пятёрочка · сегодня 1 240 ₽». */
export interface ExpectationRecord { id: string; name: string; when: string; amount: number; event: FinancialEvent }

/** Неделя ожидания: строки, чья дата в этой неделе, и записи к ним. */
export interface ExpectationWeek {
    start: string;
    end: string;
    /** «Эта неделя, 9–15 нояб.», «16–22 нояб.», «2–8 нояб., прошла»; у разового — дата строки «20 нояб., пт». */
    label: string;
    /** «9–15 нояб.», у разового — «20 нояб., пт»: заголовок блока под сеткой. */
    range: string;
    current: boolean;
    /** Прошла — приглушена: прошедшее ожидание в число не входит. */
    past: boolean;
    lines: FinancialEvent[];
    planned: number;
    /** Сумма одной строки, если строк больше одной и суммы равны, — «7 × 500 ₽». */
    each: number | null;
    /**
     * Сколько записано к строкам недели (`linkedFactsAmount`). «Осталось», цвета и полосы нет — граница по запрету 1;
     * «записано» выбрал владелец (ANO-227 п. 3, решение 51).
     */
    recorded: number;
    records: ExpectationRecord[];
}

/** Ожидание одной категории (у перевода — копилки): кружок, лист и строка сетки. */
export interface ExpectationGroup {
    key: string;
    title: string;
    income: boolean;
    /** Форма «+» с категорией ожидания — кружок с «+» и «Записать». */
    preset: { type: FinancialEvent['type']; categoryId?: string; targetFundId?: string };
    /** Первая строка — значок кружка и «Изменить ожидание» без открытой недели. */
    first: FinancialEvent;
    total: number;
    /** Разовое — одна строка в месяце. */
    single: boolean;
    /** Подпись под кружком: «≈ 15 000 ₽», «8 000 ₽ · неделя». */
    caption: string;
    /** «ожидание · около 15 000 ₽ в ноябре», «… · каждый понедельник по 8 000 ₽», «… · 20 нояб., пт». */
    subtitle: string;
    weeks: ExpectationWeek[];
    /** Неделя, открытая в листе сразу: текущая, иначе ближайшая впереди, иначе последняя. */
    open: number;
    /** Заметки строк: «вс 29 — 2 000 ₽ «Закупка к празднику»». */
    notes: string[];
    /** Пояснение под листом — справка «Как устроен журнал» на месте (решение 8). */
    footnote: string;
}

const EVERY = ['каждое воскресенье', 'каждый понедельник', 'каждый вторник', 'каждую среду', 'каждый четверг', 'каждую пятницу', 'каждую субботу'];

const datedExpectations = (events: FinancialEvent[]) => events
    .filter((e) => isExpectationPlan(e) && e.date != null)
    .sort((a, b) => (a.date! < b.date! ? -1 : a.date! > b.date! ? 1 : 0));

const sumOf = (rows: FinancialEvent[]) => rows.reduce((s, e) => s + (e.plannedAmount ?? 0), 0);

/** Строки через 7 дней одной суммой — ожидание «неделей» (S3-X4). */
const isWeekly = (rows: FinancialEvent[]) => rows.length > 1
    && rows.every((e, i) => e.plannedAmount === rows[0].plannedAmount && (i === 0 || e.date === shift(rows[i - 1].date!, 7)));

/**
 * Дни после месяца, чьи записи могут встать к его строкам: правило «своей недели» (ExpectationMatch, шесть дней назад)
 * ставит запись 1 дек. к строке 30 нояб. Журнал читает их отдельно — только для ожиданий (ревью Codex на #233).
 */
export function afterMonth(year: number, month: number): { start: string; end: string } {
    const start = iso(new Date(year, month + 1, 1));
    return { start, end: shift(start, 5) };
}

/**
 * Ожидания месяца по категориям; группы — по дате первой строки. `after` — чтение `afterMonth`: из него берутся только
 * записи к строкам месяца, планы следующего месяца ожиданиями этого не становятся.
 */
export function expectationGroups(
    events: FinancialEvent[], year: number, month: number, today: string, after: FinancialEvent[] = [],
): ExpectationGroup[] {
    const groups = new Map<string, FinancialEvent[]>();
    for (const e of datedExpectations(events)) {
        const key = e.type === 'FUND_TRANSFER' ? `fund:${e.targetFundId ?? ''}` : e.categoryId;
        groups.set(key, [...(groups.get(key) ?? []), e]);
    }
    const factsOf = (ids: Set<string>) => [...events, ...after]
        .filter((f) => f.eventKind === 'FACT' && f.parentEventId != null && ids.has(f.parentEventId))
        .sort(byRecencyDesc);

    return [...groups].map(([key, rows]) => {
        const first = rows[0];
        const income = first.type === 'INCOME';
        const fund = first.type === 'FUND_TRANSFER';
        const title = fund ? (first.targetFundName ?? first.categoryName) : first.categoryName;
        const total = sumOf(rows);
        const single = rows.length === 1;
        const weekly = isWeekly(rows);
        // Одно название у всех строк серии — имя ожидания, а не заметка: в подзаголовок один раз, не пять заметок.
        const common = !single && rows.every((e) => e.description && e.description === first.description) ? first.description : null;
        const money = (n: number) => `${income ? '+' : ''}${rub(n)} ₽`;
        const dated = (d: string) => `${fmtDayShort(d)}, ${dow(d)}`;

        const weeks = monthWeeks(year, month).flatMap((w): ExpectationWeek[] => {
            const lines = rows.filter((e) => w.start <= e.date! && e.date! <= w.end);
            if (lines.length === 0) return [];
            const current = w.start <= today && today <= w.end;
            const past = w.end < today;
            const amounts = lines.map((e) => e.plannedAmount ?? 0);
            const range = single ? dated(first.date!) : weekLabel(w);
            return [{
                start: w.start, end: w.end, current, past, lines, range,
                label: single ? range : current ? `Эта неделя, ${range}` : past ? `${range}, прошла` : range,
                planned: sumOf(lines),
                each: lines.length > 1 && amounts.every((a) => a === amounts[0]) ? amounts[0] : null,
                recorded: lines.reduce((s, e) => s + (e.linkedFactsAmount ?? 0), 0),
                records: factsOf(new Set(lines.map((e) => e.id))).map((f) => ({
                    id: f.id, name: nameOf(f), when: dateWord(f.date!, today), amount: f.factAmount ?? 0, event: f,
                })),
            }];
        });
        const ahead = weeks.findIndex((w) => !w.past);
        const who = income ? 'Поступления' : 'Траты';
        const prognosis = 'Вышло больше или меньше — ничего не краснеет: ожидание — это прогноз.';
        return {
            key, title, income, first, total, single, weeks,
            preset: fund ? { type: first.type, targetFundId: first.targetFundId } : { type: first.type, categoryId: first.categoryId },
            caption: weekly ? `${money(first.plannedAmount ?? 0)} · неделя` : `≈ ${money(total)}`,
            subtitle: `ожидание · ${common ? `«${common}» · ` : ''}${single ? `около ${money(total)} · ${dated(first.date!)}`
                : weekly ? `${EVERY[parse(first.date!).getDay()]} по ${money(first.plannedAmount ?? 0)}`
                    : `около ${money(total)} в ${IN_MONTH[month]}`}`,
            open: ahead === -1 ? weeks.length - 1 : ahead,
            notes: common ? [] : rows.filter((e) => e.description)
                .map((e) => `${dayLabel(e.date!)} — ${money(e.plannedAmount ?? 0)} «${e.description}»`),
            // Перевод к ожиданию сам не встаёт: правило привязки — у записей расхода и дохода (ExpectationMatch).
            footnote: fund ? prognosis
                : single ? `${who} категории «${title}» встают к этой строке, даже если пришлись раньше ${parse(first.date!).getDate()}-го. ${prognosis}`
                    : `${who} категории «${title}» сами встают к ожиданию своей недели. Прошедшая неделя в число не входит — это был прогноз, а не долг.`,
        };
    });
}

/**
 * Куда пишет «Записать» и кружок с «+». Трата и доход — форма «+» с категорией: сервер ставит запись к строке своей
 * недели (ExpectationMatch). Перевод в копилку мимо этого правила — запись к строке (`createLinkedFact`), по умолчанию
 * к первой строке открытой недели (ревью Codex на #233).
 */
export const recordTo = (g: ExpectationGroup, line: FinancialEvent = g.weeks[g.open]?.lines[0] ?? g.first):
    { planId: string } | { preset: ExpectationGroup['preset'] } =>
    g.preset.type === 'FUND_TRANSFER' ? { planId: line.id } : { preset: g.preset };

/**
 * «14 строк · 53 100 ₽» — заголовок «Ожидания месяца». Счёт и сумма — по одному набору строк: есть доходные —
 * «уйдёт X · придёт Y» (ANO-227; было: доход в счёте строк, а сумма только расходов). Ожиданий нет — null.
 */
export function expectationsHeader(events: FinancialEvent[]): string | null {
    const rows = datedExpectations(events);
    if (rows.length === 0) return null;
    const out = sumOf(rows.filter((e) => e.type !== 'INCOME'));
    const inc = rows.filter((e) => e.type === 'INCOME');
    const count = `${rows.length} ${ruPlural(rows.length, ['строка', 'строки', 'строк'])}`;
    return inc.length === 0
        ? `${count} · ${rub(out)} ₽`
        : `${count} · уйдёт ${rub(out)} ₽ · придёт ${rub(sumOf(inc))} ₽`;
}
