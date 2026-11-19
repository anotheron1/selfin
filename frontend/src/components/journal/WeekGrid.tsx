import type { ReactNode } from 'react';
import { Button } from '@/ds';
import { cn } from '@/lib/utils';
import type { FinancialEvent } from '../../types/api';
import type { ExpectationGroup } from '../../lib/journalSections';
import type { WeekGrid as Grid } from '../../lib/weekGrid';
import { ExpectationMark, WeekBlock } from './ExpectationSheet';

const short = (n: number) => Math.round(n).toLocaleString('ru-RU').replace(/\s/g, ' ');

/**
 * Сетка недель на 1440 (срез 3, п. 11; доска S3-W1d), колонка 480: строки — ожидания, столбцы — недели с понедельника,
 * текущая выделена. Клетка: пунктир с суммой — строка впереди, заливка с числом — сколько к ней записано. Нажатая клетка —
 * её записи под сеткой и подсветка этих записей в списке. Цвета превышения нет — граница по запрету 1.
 */
export default function WeekGrid({ grid, selected, onSelect, onRecord, onEdit, hint }: {
    grid: Grid;
    selected: { row: number; col: number } | null;
    onSelect: (row: number, col: number) => void;
    onRecord: (g: ExpectationGroup) => void;
    /** «Изменить ожидание» — строка нажатой клетки (EditEventSheet до PR 5). */
    onEdit: (line: FinancialEvent) => void;
    /** Пузырь первого журнала у кружка первого ожидания (S3-N1). */
    hint?: ReactNode;
}) {
    const cols = `minmax(0,1fr) repeat(${grid.weeks.length}, 52px)`;
    const sel = selected && grid.rows[selected.row]?.cells[selected.col];
    const group = selected && grid.rows[selected.row]?.group;
    return (
        <div>
            <div className="grid items-end gap-x-1 pb-1.5 text-center text-sf-caption text-sf-muted" style={{ gridTemplateColumns: cols }}>
                <span />
                {grid.weeks.map((w) => (
                    <span key={w.start} className={cn(w.current && 'font-semibold text-sf-accent')}>
                        {w.current && <span className="block">эта</span>}{w.label}
                    </span>
                ))}
            </div>
            {grid.rows.map((r, row) => (
                <div key={r.group.key} className={cn('relative grid items-center gap-x-1 border-b border-sf-rule py-1.5', row === 0 && 'z-10')}
                    style={{ gridTemplateColumns: cols }}>
                    <div className="flex min-w-0 items-center gap-2.5">
                        <button type="button" aria-label={`Записать: ${r.group.title}`} onClick={() => onRecord(r.group)} className="rounded-sf-pill">
                            <ExpectationMark group={r.group} size="s" plus />
                        </button>
                        <span className="min-w-0">
                            <span className="block truncate text-sf-note font-semibold">{r.group.title}</span>
                            <span className="block truncate text-sf-caption text-sf-muted">{r.group.caption}</span>
                        </span>
                    </div>
                    {r.cells.map((c, col) => (c
                        ? (
                            <button key={grid.weeks[col].start} type="button" onClick={() => onSelect(row, col)}
                                aria-pressed={selected?.row === row && selected.col === col}
                                aria-label={`${r.group.title}, ${c.range}`}
                                className={cn('flex h-11 items-center justify-center rounded-[10px] text-sf-caption tabular-nums transition-colors',
                                    c.look === 'fill' ? 'bg-sf-plate font-semibold' : 'border border-dashed border-sf-rule text-sf-muted',
                                    selected?.row === row && selected.col === col && 'ring-[1.5px] ring-sf-accent')}>
                                {c.look !== 'blank' && short(c.value)}
                            </button>
                        )
                        : <span key={grid.weeks[col].start} />))}
                    {row === 0 && hint}
                </div>
            ))}
            {sel && group && (
                <div className="mt-3 flex flex-col gap-3 rounded-sf-plate bg-sf-plate pb-3">
                    <WeekBlock title={`${group.title} · ${sel.range}`} week={sel} income={group.income}
                        note={sel.records.length > 0 ? 'эти записи подсвечены в списке' : undefined} />
                    <div className="grid grid-cols-2 gap-2.5 px-4">
                        <Button variant="soft" size="m" onClick={() => onEdit(sel.lines[0])}>Изменить ожидание</Button>
                        <Button size="m" onClick={() => onRecord(group)}>Записать</Button>
                    </div>
                </div>
            )}
            <p className="mt-3 px-1 text-sf-label text-sf-muted">
                Пунктир — строка впереди, заливка — к ней уже записано. Ничего не краснеет: ожидание — прогноз.
            </p>
        </div>
    );
}
