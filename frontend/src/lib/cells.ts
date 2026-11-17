/**
 * Клетки по дням для «3 мес» и «6 мес» (доска Zh2-390b, «как в гитхабе»): колонка — неделя, строки — Пн…Вс.
 * Больше остаток — темнее, пять ступеней акцента (`--sf-cell-1…5`); ниже нуля — вода. Отмечены «сегодня» и день
 * самого низкого остатка. Клетки — только дни срока: хвост за ним информационный и в клетки не идёт.
 */
export interface Cell {
    date: string;
    level: 1 | 2 | 3 | 4 | 5 | 'water';
    today: boolean;
    low: boolean;
}

export interface CellsModel {
    /** Колонки-недели; `month` — короткое имя месяца, если он начинается в этой колонке. */
    weeks: { month: string | null; days: (Cell | null)[] }[];
}

const parse = (iso: string) => {
    const [y, m, d] = iso.split('-').map(Number);
    return new Date(y, m - 1, d);
};
const shortMonth = (iso: string) => parse(iso).toLocaleDateString('ru-RU', { month: 'short' });
/** Понедельник — 0. */
const weekday = (iso: string) => (parse(iso).getDay() + 6) % 7;

export function buildCells(points: { date: string; balance: number }[], horizonEnd: string, minDate: string): CellsModel {
    const term = points.filter(p => p.date <= horizonEnd);
    if (term.length === 0) return { weeks: [] };
    const max = Math.max(...term.map(p => p.balance), 1);
    const level = (b: number): Cell['level'] =>
        b < 0 ? 'water' : (Math.min(5, 1 + Math.floor((b / max) * 4 + 1e-9)) as Cell['level']);

    const weeks: CellsModel['weeks'] = [];
    let col: (Cell | null)[] = new Array(7).fill(null);
    let month: string | null = shortMonth(term[0].date);
    term.forEach((p, i) => {
        const wd = weekday(p.date);
        if (i > 0 && wd === 0) {
            weeks.push({ month, days: col });
            col = new Array(7).fill(null);
            month = null;
        }
        if (i > 0 && p.date.endsWith('-01')) month = shortMonth(p.date);
        col[wd] = { date: p.date, level: level(p.balance), today: i === 0, low: p.date === minDate };
    });
    weeks.push({ month, days: col });
    return { weeks };
}
