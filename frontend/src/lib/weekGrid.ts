import type { ExpectationGroup, ExpectationWeek } from './journalSections';

/**
 * Сетка недель ожиданий на 1440 (срез 3, п. 11; доска S3-W1d): строки — ожидания месяца, столбцы — недели с понедельника.
 * Клетка — строки ожидания, чья дата в этой неделе: пунктир и сумма — строка впереди без записей; заливка и число —
 * сколько к ней записано; прошедшая без записей — пустая (прошедшее ожидание не держится, упрёка нет — правило 12).
 */

export interface GridWeek { start: string; end: string; label: string; current: boolean }

export interface GridCell extends ExpectationWeek {
    look: 'dash' | 'fill' | 'blank';
    value: number;
}

export interface WeekGrid {
    weeks: GridWeek[];
    rows: { group: ExpectationGroup; cells: (GridCell | null)[] }[];
    /** Нажатая по умолчанию: текущая неделя первого ожидания, нет её — первая клетка первого. */
    selected: { row: number; col: number } | null;
}

const iso = (d: Date) =>
    `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
const parse = (s: string) => { const [y, m, d] = s.split('-').map(Number); return new Date(y, m - 1, d); };
const shift = (s: string, days: number) => { const d = parse(s); d.setDate(d.getDate() + days); return iso(d); };
const mondayOf = (s: string) => shift(s, -((parse(s).getDay() + 6) % 7));

export function weekGrid(groups: ExpectationGroup[], year: number, month: number, today: string): WeekGrid {
    const first = iso(new Date(year, month, 1));
    const last = iso(new Date(year, month + 1, 0));
    const all: GridWeek[] = [];
    for (let monday = mondayOf(first); monday <= last; monday = shift(monday, 7)) {
        const end = shift(monday, 6);
        all.push({ start: monday, end, label: `${parse(monday).getDate()}–${parse(end).getDate()}`, current: monday <= today && today <= end });
    }
    // Неделя на краю месяца, где ни у одного ожидания нет строки, — не столбец: ширина колонки 480.
    const used = (w: GridWeek) => groups.some((g) => g.weeks.some((x) => mondayOf(x.start) === w.start));
    const weeks = all.filter((w) => (first <= w.start && w.end <= last) || used(w));

    const rows = groups.map((group) => ({
        group,
        cells: weeks.map((w): GridCell | null => {
            const week = group.weeks.find((x) => mondayOf(x.start) === w.start);
            if (!week) return null;
            const look = week.recorded > 0 ? 'fill' : week.past ? 'blank' : 'dash';
            return { ...week, look, value: look === 'fill' ? week.recorded : week.planned };
        }),
    }));
    const now = weeks.findIndex((w) => w.current);
    const col = rows.length === 0 ? -1 : now !== -1 && rows[0].cells[now] ? now : rows[0].cells.findIndex((c) => c != null);
    return { weeks, rows, selected: col === -1 ? null : { row: 0, col } };
}
