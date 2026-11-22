import type { Account, CapitalItem, CapitalSummary, CapitalTrajectory, TargetFund } from '../types/api';
import { fmtAmount, fmtDayShort, fmtNum } from './format';

/**
 * Что куда на «Деньгах» (срез 4, пп. 8–11; доски S4-W1, S4-W2, S4-M4). Вид — components/money/MoneyView.tsx.
 */

export interface MoneyGroups {
    primary: Account | null;
    /** Кредитки — парой с основной. */
    credits: Account[];
    /** Вторая карта «на всё» — дебетовая или наличные с остатком, не основная (п. 10): в «Основной», под ней. */
    together: Account[];
    /** Для отдельных трат — без остатка; наличные первыми. */
    spending: Account[];
    savings: Account[];
    assets: CapitalItem[];
    debts: CapitalItem[];
    /** Проданное и погашенное — «Архивные · N». */
    archived: CapitalItem[];
}

export function moneyGroups(accounts: Account[], items: CapitalItem[]): MoneyGroups {
    const rest = accounts.filter(a => !a.isDefault);
    const live = items.filter(i => !i.isArchived);
    return {
        primary: accounts.find(a => a.isDefault) ?? null,
        credits: rest.filter(a => a.kind === 'CREDIT'),
        together: rest.filter(a => (a.kind === 'DEBIT' || a.kind === 'CASH') && a.trackBalance),
        spending: rest.filter(a => (a.kind === 'DEBIT' || a.kind === 'CASH') && !a.trackBalance)
            .sort((x, y) => Number(y.kind === 'CASH') - Number(x.kind === 'CASH')),
        savings: rest.filter(a => a.kind === 'DEPOSIT'),
        assets: live.filter(i => i.kind === 'ASSET'),
        debts: live.filter(i => i.kind === 'LIABILITY'),
        archived: items.filter(i => i.isArchived),
    };
}

export interface AccountLine {
    /** Строки под названием. */
    sub: string[];
    /** Сумма справа; null — остатка ещё нет. */
    value: string | null;
    /** Подпись под суммой: «доступно», «записано в ноябре». */
    caption: string | null;
    /** Значок обновления перед датой — у основной (S4-W1). */
    updated: boolean;
}

const FIRST = 'первое обновление';

/** Строка карты в группе. */
export function accountLine(a: Account, funds: TargetFund[], today: string): AccountLine {
    const updated = a.balanceDate ? `обновлён ${fmtDayShort(a.balanceDate)}` : FIRST;
    const value = a.balance == null ? null : fmtAmount(a.balance);
    if (a.kind === 'CREDIT') {
        if (a.balance == null) return { sub: [FIRST], value: null, caption: null, updated: false };
        const sub = [];
        if (a.debt != null && a.creditLimit != null) sub.push(`долг ${fmtNum(a.debt)} из ${fmtNum(a.creditLimit)}`);
        if (a.availableFloor != null) sub.push(`возвращаю до ${fmtNum(a.availableFloor)}`);
        return { sub: sub.length ? sub : [updated], value, caption: 'доступно', updated: false };
    }
    if (!a.trackBalance) {
        return {
            sub: [a.purposeCategoryName ? `${a.purposeCategoryName} · остаток не нужен` : 'остаток не нужен'],
            value: fmtAmount(a.allocatedThisMonth ?? 0), caption: `записано ${monthIn(today)}`, updated: false,
        };
    }
    if (a.kind === 'DEPOSIT') {
        const names = funds.filter(f => f.accountId === a.id).map(f => `«${f.name}»`);
        const pot = names.length === 0 ? [] : [`${names.length === 1 ? 'копилка' : 'копилки'} ${names.join(', ')}`];
        return { sub: [[...pot, updated].join(' · ')], value, caption: null, updated: false };
    }
    if (!a.isDefault) return { sub: [`считается вместе с основной · ${updated}`], value, caption: null, updated: false };
    return { sub: [updated], value, caption: null, updated: a.balanceDate != null };
}

/** «есть · должен» под капиталом: есть — деньги и имущество, должен — все долги, с кредитками. */
export function capitalHead(s: Pick<CapitalSummary, 'liquid' | 'assetsTotal' | 'liabilitiesTotal'>) {
    return { has: s.liquid + s.assetsTotal, owes: s.liabilitiesTotal };
}

const MONTHS_IN = ['январе', 'феврале', 'марте', 'апреле', 'мае', 'июне', 'июле', 'августе', 'сентябре', 'октябре', 'ноябре', 'декабре'];

/** «в ноябре» из ISO-даты. */
export const monthIn = (iso: string): string => `в ${MONTHS_IN[Number(iso.slice(5, 7)) - 1]}`;

// ---------- График ----------

export type Range = 'quarter' | '6m' | 'year' | '3y' | 'all';

export const RANGES: { value: Range; label: string }[] = [
    { value: 'quarter', label: 'Квартал' }, { value: '6m', label: '6 мес' }, { value: 'year', label: 'Год' },
    { value: '3y', label: '3 года' }, { value: 'all', label: 'Всё' },
];

const MONTHS_BACK: Record<Exclude<Range, 'all'>, number> = { quarter: 3, '6m': 6, year: 12, '3y': 36 };

/** ISO-дата на `months` месяцев раньше — без часового пояса. */
function monthsBefore(iso: string, months: number): string {
    const [y, m, d] = iso.split('-').map(Number);
    const total = y * 12 + (m - 1) - months;
    const [ty, tm] = [Math.floor(total / 12), total % 12 + 1];
    // 31 мая минус квартал — 28 февраля, а не «31 февраля»: сервер ставит точки на настоящие концы месяцев.
    const day = Math.min(d, new Date(ty, tm, 0).getDate());
    return `${ty}-${String(tm).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
}

/** По умолчанию «Всё», пока первой точке меньше года, потом «Год» (ANO-84, п. 1). */
export function defaultRange(first: string | null, today: string): Range {
    return first != null && first <= monthsBefore(today, 12) ? 'year' : 'all';
}

type Point = CapitalTrajectory['points'][number];

/** Точки периода: с начала периода по сегодня. */
export function rangePoints(points: Point[], range: Range, today: string): Point[] {
    if (range === 'all') return points;
    const from = monthsBefore(today, MONTHS_BACK[range]);
    return points.filter(p => p.date >= from);
}

/** Изменение у графика — последняя точка минус первая; одной точки мало. */
export function rangeChange(points: Point[]): { amount: number; from: string } | null {
    if (points.length < 2) return null;
    return { amount: points[points.length - 1].capital - points[0].capital, from: points[0].date };
}
