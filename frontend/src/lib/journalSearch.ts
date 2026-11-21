import type { FinancialEvent, Priority, SearchResult } from '../types/api';
import { fmtDayShort } from './format';
import { ruPlural } from './plural';
import { signedFact } from './factSign';

/**
 * Поиск по журналу (срез 3, п. 9, ANO-126; доски S3-S1, S3-W6): что спросить у сервера и как сказать о найденном.
 * Что подходит под поиск, решает сервер (`GET /events/search`, EventSearch); здесь — чипы, заголовок и месяцы.
 */

export type Period = 'all' | 'month' | 'prev' | 'year';

/** Чип периода: подпись в выборе и хвост заголовка «Нашлось … за всё время». */
export const PERIODS: Record<Period, { label: string; tail: string }> = {
    all: { label: 'За всё время', tail: 'за всё время' },
    month: { label: 'Этот месяц', tail: 'за этот месяц' },
    prev: { label: 'Прошлый месяц', tail: 'за прошлый месяц' },
    year: { label: 'Этот год', tail: 'за этот год' },
};

export interface SearchCriteria {
    q: string;
    period: Period;
    categoryId: string | null;
    priority: Priority | null;
    /** «Только записи» — включён, как на досках. */
    factsOnly: boolean;
}

export const NO_SEARCH: SearchCriteria = { q: '', period: 'all', categoryId: null, priority: null, factsOnly: true };

/** Искать есть что: слово, число, категория или вид. Один период или «Только записи» — это просто журнал. */
export const searchReady = (c: SearchCriteria): boolean =>
    c.q.trim() !== '' || c.categoryId != null || c.priority != null;

const iso = (d: Date) =>
    `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;

/** Даты периода от сегодня; «За всё время» — без дат. */
export function periodRange(p: Period, today: string): { from?: string; to?: string } {
    const [y, m] = today.split('-').map(Number);
    if (p === 'month') return { from: iso(new Date(y, m - 1, 1)), to: iso(new Date(y, m, 0)) };
    if (p === 'prev') return { from: iso(new Date(y, m - 2, 1)), to: iso(new Date(y, m - 1, 0)) };
    if (p === 'year') return { from: `${y}-01-01`, to: `${y}-12-31` };
    return {};
}

/** Параметры запроса: пустое не уходит. */
export function searchParams(c: SearchCriteria, today: string): URLSearchParams {
    const p = new URLSearchParams();
    if (c.q.trim()) p.set('q', c.q.trim());
    const { from, to } = periodRange(c.period, today);
    if (from) p.set('from', from);
    if (to) p.set('to', to);
    if (c.categoryId) p.set('categoryId', c.categoryId);
    if (c.priority) p.set('priority', c.priority);
    if (c.factsOnly) p.set('factsOnly', 'true');
    return p;
}

/** Что искать сейчас — строкой запроса; пусто — не искать: поиск закрыт или искать нечего. */
export const searchKey = (c: SearchCriteria, open: boolean, today: string): string =>
    open && searchReady(c) ? searchParams(c, today).toString() : '';

const rub = (n: number) => `${Math.round(n).toLocaleString('ru-RU').replace(/\s/g, ' ')} ₽`;

/** Сумма со знаком, как в журнале: «+» у пришедшего, у ушедшего знака нет. */
const signed = (toFree: number) => `${toFree > 0 ? '+' : ''}${rub(Math.abs(toFree))}`;

/**
 * «Нашлось **46 записей · 52 300 ₽** за всё время»: `found` — выделенная часть, null — ничего не нашлось. С планами —
 * «строк» и «записано X»: сумма — только записанного. Нашлось больше, чем пришло, — «Показаны 200 свежих».
 */
export function foundHeader(r: SearchResult, c: SearchCriteria): { found: string | null; tail: string; more: string | null } {
    const tail = PERIODS[c.period].tail;
    if (r.total === 0) return { found: null, tail, more: null };
    const count = `${r.total} ${ruPlural(r.total, c.factsOnly ? ['запись', 'записи', 'записей'] : ['строка', 'строки', 'строк'])}`;
    const sum = Math.round(r.recorded) === 0 ? '' : ` · ${c.factsOnly ? '' : 'записано '}${signed(r.recorded)}`;
    const more = r.total > r.rows.length ? `Показаны ${r.rows.length} свежих` : null;
    return { found: `${count}${sum}`, tail, more };
}

const MONTHS = ['Январь', 'Февраль', 'Март', 'Апрель', 'Май', 'Июнь', 'Июль', 'Август', 'Сентябрь', 'Октябрь', 'Ноябрь', 'Декабрь'];

/** Строка найденного: имя, «12 нояб. · Продукты», сумма со знаком. */
export interface FoundRow {
    event: FinancialEvent;
    name: string;
    sub: string;
    amount: string;
}

/** Найденное по месяцам, свежий первым: «Ноябрь 2026 · 4». Порядок строк — с сервера. */
export function foundByMonth(rows: FinancialEvent[]): { label: string; rows: FoundRow[] }[] {
    const out: { key: string; label: string; rows: FoundRow[] }[] = [];
    for (const e of rows) {
        const key = e.date!.slice(0, 7);
        let g = out[out.length - 1];
        if (!g || g.key !== key) {
            const [y, m] = key.split('-').map(Number);
            g = { key, label: `${MONTHS[m - 1]} ${y}`, rows: [] };
            out.push(g);
        }
        g.rows.push(foundRow(e));
    }
    return out.map(g => ({ label: `${g.label} · ${g.rows.length}`, rows: g.rows }));
}

function foundRow(e: FinancialEvent): FoundRow {
    const transfer = e.type === 'FUND_TRANSFER';
    const name = transfer ? (e.targetFundName ?? 'Копилка') : (e.description || e.categoryName);
    const category = transfer ? 'Накопление' : e.description ? e.categoryName : null;
    const value = e.factAmount ?? e.plannedAmount;
    const s = value == null ? null : signedFact(e.type, value);
    return {
        event: e, name,
        sub: [fmtDayShort(e.date!), category].filter(Boolean).join(' · '),
        amount: s == null ? '—' : `${s.sign === '+' ? '+' : ''}${rub(s.amount)}`,
    };
}
