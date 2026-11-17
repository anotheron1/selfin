import type { PocketResponse, Priority, UpcomingItem } from '../types/api';
import { fmtDayShort, fmtRub } from './format';
import { ruPlural } from './plural';
import { PRIORITY_DOT_CONFIG } from './priority';

/**
 * Лист дня (ANO-98; спека среза 2, п. 8; доски S2-D3, S2-D-1440): заголовок — день недели и дата,
 * «N строк · к вечеру останется …», строки дня из `trajectory[].items`, соседние дни графика для ‹ ›.
 * У ожиданий — «≈»: сумма примерная. Ожидания по дням не размазываются: в листе то, что датировано этим днём.
 */
export type DayKind = 'bron' | 'ozh' | 'hot';

export interface DayRow {
    key: string;
    kind: DayKind;
    type: UpcomingItem['type'];
    name: string;
    sub: string;
    amount: string;
}

export interface DayView {
    date: string;
    title: string;
    summary: string;
    rows: DayRow[];
    prev: string | null;
    next: string | null;
}

const KIND: Record<Priority, DayKind> = { HIGH: 'bron', MEDIUM: 'ozh', LOW: 'hot' };

function weekdayTitle(iso: string): string {
    const [y, m, d] = iso.split('-').map(Number);
    const wd = new Date(y, m - 1, d).toLocaleDateString('ru-RU', { weekday: 'long' });
    return `${wd[0].toUpperCase()}${wd.slice(1)}, ${fmtDayShort(iso)}`;
}

function rowOf(u: UpcomingItem, i: number): DayRow {
    const word = PRIORITY_DOT_CONFIG[u.priority].name.toLowerCase();
    const own = u.description?.trim();
    const name = own || u.categoryName || '—';
    const kind = u.overdue ? `${word} с ${fmtDayShort(u.date)}` : word;
    const sign = u.type === 'INCOME' ? `+${fmtRub(u.amount)}` : fmtRub(-u.amount);
    return {
        key: u.id ?? `${u.description}-${i}`,
        kind: KIND[u.priority],
        type: u.type,
        name,
        sub: own && u.categoryName ? `${u.categoryName} · ${kind}` : kind,
        amount: u.priority === 'MEDIUM' ? `≈ ${sign}` : sign,
    };
}

export function buildDaySheet(p: PocketResponse, date: string): DayView | null {
    const i = p.trajectory.findIndex(t => t.date === date);
    if (i < 0) return null;
    const point = p.trajectory[i];
    const n = point.items.length;
    const lines = n === 0 ? 'Строк нет' : `${n} ${ruPlural(n, ['строка', 'строки', 'строк'])}`;
    return {
        date,
        title: weekdayTitle(date),
        summary: `${lines} · к вечеру останется ${fmtRub(point.balance)}`,
        rows: point.items.map(rowOf),
        prev: p.trajectory[i - 1]?.date ?? null,
        next: p.trajectory[i + 1]?.date ?? null,
    };
}
