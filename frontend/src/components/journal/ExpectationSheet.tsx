import { useEffect, useRef, useState } from 'react';
import { X } from 'lucide-react';
import { Button, Plate } from '@/ds';
import { cn } from '@/lib/utils';
import type { FinancialEvent } from '../../types/api';
import type { ExpectationGroup, ExpectationWeek } from '../../lib/journalSections';
import { iconOf } from './JournalList';

const rub = (n: number) => `${Math.round(n).toLocaleString('ru-RU').replace(/\s/g, ' ')} ₽`;

/** «8 000 ₽», «7 × 500 ₽»; у дохода — «+». */
export const weekAmount = (w: ExpectationWeek, income: boolean) => {
    const sign = income ? '+' : '';
    return w.each != null ? `${w.lines.length} × ${sign}${rub(w.each)}` : `${sign}${rub(w.planned)}`;
};

/**
 * Открытая неделя ожидания — плашка: что ждали, сколько записано и записи к строкам (S3-X4, S3-X5, блок под сеткой
 * S3-W1d). «Записано» — без «осталось», без цвета и полосы: граница по запрету 1.
 */
export function WeekBlock({ title, week, income, note }: { title: string; week: ExpectationWeek; income: boolean; note?: string }) {
    return (
        <Plate>
            <div className="flex items-baseline justify-between gap-3">
                <span className="text-sf-body font-semibold">{title}</span>
                <span className="shrink-0 whitespace-nowrap text-sf-body tabular-nums">{weekAmount(week, income)}</span>
            </div>
            {week.recorded > 0 && (
                <p className="mt-1.5 text-sf-label">
                    записано <span className="font-semibold tabular-nums">{rub(week.recorded)}</span>
                    {note && <span className="text-sf-muted"> · {note}</span>}
                </p>
            )}
            {week.records.length > 0 && (
                <ul className="mt-2 flex flex-col gap-1">
                    {week.records.map((r) => (
                        <li key={r.id} className="flex items-baseline justify-between gap-3 text-sf-label">
                            <span className="min-w-0 truncate">{r.name}<span className="text-sf-muted"> · {r.when}</span></span>
                            <span className="shrink-0 tabular-nums">{rub(r.amount)}</span>
                        </li>
                    ))}
                </ul>
            )}
        </Plate>
    );
}

/** Значок ожидания в кружке цвета «ожидание»; с «+» — кнопка записи. */
export function ExpectationMark({ group, size, plus }: { group: ExpectationGroup; size: 'l' | 's'; plus?: boolean }) {
    const Icon = iconOf(group.first);
    return (
        <span className={cn('relative inline-flex shrink-0 items-center justify-center rounded-sf-pill bg-sf-kind-ozh-bg text-sf-kind-ozh',
            size === 'l' ? 'size-[62px] [&>svg]:size-7' : 'size-9 [&>svg]:size-[18px]')}>
            <Icon strokeWidth={1.6} />
            {plus && (
                <span aria-hidden="true" className={cn('absolute -bottom-0.5 -right-0.5 flex items-center justify-center rounded-sf-pill bg-sf-accent font-semibold leading-none text-sf-on-accent ring-2 ring-sf-page',
                    size === 'l' ? 'size-[22px] text-[16px]' : 'size-4 text-[12px]')}>+</span>
            )}
        </span>
    );
}

/**
 * Лист ожидания на телефоне (срез 3, п. 10; доски S3-X1, S3-X4, S3-X5): строки неделями — открытая плашкой с записями,
 * остальные строкой, прошедшие приглушены; заметки строк; пояснение; «Изменить ожидание» и «Записать».
 * Нажатие на неделю открывает её. Esc и фон закрывают.
 */
export default function ExpectationSheet({ group, onClose, onEdit, onRecord }: {
    group: ExpectationGroup;
    onClose: () => void;
    /** «Изменить ожидание» — строка открытой недели (EditEventSheet до PR 5). */
    onEdit: (line: FinancialEvent) => void;
    onRecord: () => void;
}) {
    const [open, setOpen] = useState(group.open);
    const ref = useRef<HTMLDivElement>(null);
    useEffect(() => { ref.current?.focus(); }, []);
    const week = group.weeks[open];

    return (
        <div className="fixed inset-0 z-50 lg:hidden">
            <div aria-hidden="true" className="absolute inset-0 bg-black/30" onClick={onClose} />
            <div ref={ref} role="dialog" aria-label={group.title} tabIndex={-1}
                onKeyDown={(e) => { if (e.key === 'Escape') onClose(); }}
                className="absolute inset-x-0 bottom-0 flex max-h-[85dvh] flex-col rounded-t-[28px] bg-sf-page px-5 pb-4 pt-1.5 font-sf text-sf-ink shadow-sf-raised outline-none">
                <button type="button" aria-label="Свернуть" onClick={onClose} className="mt-0.5 flex h-6 w-16 items-center justify-center self-center">
                    <span className="h-1 w-9 rounded-sm" style={{ background: 'var(--sf-bar-go)' }} />
                </button>
                <button type="button" aria-label="Закрыть" onClick={onClose}
                    className="absolute right-3 top-3 flex h-9 w-9 items-center justify-center text-sf-ink"><X size={18} /></button>
                <div className="flex items-center gap-3.5 pb-3 pr-8 pt-1">
                    <ExpectationMark group={group} size="l" />
                    <div className="min-w-0">
                        <div className="truncate text-sf-button font-semibold">{group.title}</div>
                        <div className="text-sf-label text-sf-muted">{group.subtitle}</div>
                    </div>
                </div>
                <div className="min-h-0 flex-1 overflow-y-auto">
                    {group.weeks.map((w, i) => (i === open
                        ? <WeekBlock key={w.start} title={w.label} week={w} income={group.income} />
                        : (
                            <button key={w.start} type="button" onClick={() => setOpen(i)}
                                className={cn('flex w-full items-baseline justify-between gap-3 border-b border-sf-rule px-1 py-3.5 text-left text-sf-body',
                                    w.past && 'text-sf-muted')}>
                                <span>{w.label}</span>
                                <span className="shrink-0 whitespace-nowrap tabular-nums">
                                    {weekAmount(w, group.income)}{w.recorded > 0 && ` · записано ${rub(w.recorded)}`}
                                </span>
                            </button>
                        )))}
                    {group.notes.map((n) => <p key={n} className="mt-2.5 px-1 text-sf-label text-sf-muted">{n}</p>)}
                    <p className="mt-3 px-1 text-sf-label text-sf-muted">{group.footnote}</p>
                </div>
                <div className="mt-3 grid grid-cols-2 gap-2.5">
                    <Button variant="soft" onClick={() => onEdit(week?.lines[0] ?? group.first)}>Изменить ожидание</Button>
                    <Button onClick={onRecord}>Записать</Button>
                </div>
            </div>
        </div>
    );
}
