import { buildCells } from '../../lib/cells';
import { fmtDayShort } from '../../lib/format';
import type { PocketResponse } from '../../types/api';

const FILL: Record<string, string> = {
    1: 'var(--sf-cell-1)', 2: 'var(--sf-cell-2)', 3: 'var(--sf-cell-3)', 4: 'var(--sf-cell-4)', 5: 'var(--sf-cell-5)',
    water: 'var(--sf-water)',
};
const WEEKDAYS = ['Пн', '', 'Ср', '', 'Пт', '', ''];

/** Клетки по дням на «3 мес» и «6 мес» (доска Zh2-390b; спека среза 2, п. 2). */
export function Cells({ data, size, onDay }: {
    data: PocketResponse;
    size: 'narrow' | 'wide';
    onDay?: (date: string) => void;
}) {
    const m = buildCells(data.trajectory, data.horizon.endDate, data.minPoint.date);
    const cell = size === 'wide' ? 22 : 19;
    const gap = 4;
    const left = 28;
    const top = 22;
    const w = left + m.weeks.length * (cell + gap);
    const h = top + 7 * (cell + gap);
    return (
        <svg viewBox={`0 0 ${w} ${h}`} className="block h-auto w-full max-w-full" role="img"
            aria-label={`Остаток по дням клетками: самый низкий — ${fmtDayShort(data.minPoint.date)}`}>
            {WEEKDAYS.map((d, r) => d && (
                <text key={r} x="0" y={top + r * (cell + gap) + cell * 0.72} fontSize="11" fill="var(--sf-muted)">{d}</text>
            ))}
            {m.weeks.map((wk, c) => {
                const x = left + c * (cell + gap);
                return (
                    <g key={c}>
                        {wk.month && <text x={x} y="12" fontSize="11" fill="var(--sf-muted)">{wk.month}</text>}
                        {wk.days.map((d, r) => {
                            if (!d) return null;
                            const y = top + r * (cell + gap);
                            return (
                                <g key={d.date} onClick={onDay ? () => onDay(d.date) : undefined}
                                    role={onDay ? 'button' : undefined} aria-label={fmtDayShort(d.date)}
                                    style={onDay ? { cursor: 'pointer' } : undefined}>
                                    <rect x={x} y={y} width={cell} height={cell} rx="5" fill={FILL[d.level]} data-level={d.level} />
                                    {d.today && <rect x={x - 2.5} y={y - 2.5} width={cell + 5} height={cell + 5} rx="7" fill="none"
                                        stroke="var(--sf-ink)" strokeWidth="1.8" data-mark="today" />}
                                    {d.low && <circle cx={x + cell / 2} cy={y + cell / 2} r="5" fill="var(--sf-ring)"
                                        stroke="var(--sf-ring-rim)" strokeWidth="2" data-mark="low" />}
                                </g>
                            );
                        })}
                    </g>
                );
            })}
        </svg>
    );
}
