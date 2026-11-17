import { useEffect, useRef } from 'react';
import { Link } from 'react-router-dom';
import { ChevronLeft, ChevronRight, PiggyBank, Receipt, Sun, X } from 'lucide-react';
import { KindMark } from '@/ds';
import { cn } from '@/lib/utils';
import { buildDaySheet, type DayRow } from '../../lib/daySheet';
import type { PocketResponse } from '../../types/api';

const ICON = { INCOME: Sun, EXPENSE: Receipt, FUND_TRANSFER: PiggyBank } as const;

function Row({ r, wide }: { r: DayRow; wide: boolean }) {
    const Icon = ICON[r.type] ?? Receipt;
    return (
        <div className={cn('flex items-center gap-3 border-b border-sf-rule', wide ? 'min-h-[48px]' : 'min-h-[56px]')}>
            <KindMark kind={r.kind} icon={<Icon strokeWidth={1.7} />} />
            <span className="flex min-w-0 flex-1 flex-col">
                <span className={cn('truncate', wide ? 'text-sf-control' : 'text-sf-body')}>{r.name}</span>
                <span className="truncate text-sf-label text-sf-muted">{r.sub}</span>
            </span>
            <span className={cn('shrink-0 whitespace-nowrap font-medium tabular-nums', wide ? 'text-sf-control' : 'text-sf-body')}>{r.amount}</span>
        </div>
    );
}

/**
 * Лист дня (S2-D3, S2-D-1440): до 1024 px — снизу, высота постоянная; от 1024 — карточка у выбранного дня.
 * Список листается внутри, «Открыть день в журнале» прижата к низу; ‹ › — соседние дни графика; Esc закрывает.
 */
export function DaySheet({ data, date, size, xPct = 50, onDate, onClose }: {
    data: PocketResponse;
    date: string;
    size: 'narrow' | 'wide';
    /** Где день на пейзаже, в процентах ширины, — карточка 1440 встаёт рядом. */
    xPct?: number;
    onDate: (date: string) => void;
    onClose: () => void;
}) {
    const ref = useRef<HTMLDivElement>(null);
    useEffect(() => { ref.current?.focus(); }, []);
    const v = buildDaySheet(data, date);
    if (!v) return null;
    const wide = size === 'wide';
    const place = !wide ? undefined
        : xPct > 60 ? { right: `calc(${100 - xPct}% + 14px)` } : { left: `calc(${xPct}% + 14px)` };

    const nav = (label: string, to: string | null, Icon: typeof ChevronLeft, edge: string) => (
        <button type="button" aria-label={label} disabled={!to} onClick={() => to && onDate(to)}
            className={cn('flex h-11 w-11 shrink-0 items-center justify-center disabled:opacity-30', edge)}>
            <Icon size={wide ? 18 : 20} />
        </button>
    );

    return (
        <div ref={ref} role="dialog" aria-label={v.title} tabIndex={-1}
            onKeyDown={e => { if (e.key === 'Escape') onClose(); }}
            style={place}
            className={cn('flex flex-col bg-sf-page font-sf text-sf-ink outline-none',
                wide ? 'absolute top-2.5 z-10 h-[326px] w-[340px] rounded-[18px] px-4 pb-2 pt-1.5 shadow-sf-raised'
                    : 'fixed inset-x-0 bottom-0 z-50 h-[392px] rounded-t-[28px] px-5 pb-3 pt-1.5 shadow-sf-raised lg:hidden')}>
            {!wide && (
                <button type="button" aria-label="Свернуть" onClick={onClose} className="mt-0.5 flex h-6 w-16 items-center justify-center self-center">
                    <span className="h-1 w-9 rounded-sm" style={{ background: 'var(--sf-bar-go)' }} />
                </button>
            )}
            <div className="flex shrink-0 items-center gap-1">
                {nav('Предыдущий день', v.prev, ChevronLeft, wide ? '-ml-2.5' : '-ml-3')}
                <span className="flex min-w-0 flex-1 flex-col items-center text-center">
                    <span className={cn('font-semibold', wide ? 'text-sf-body' : 'text-sf-button')}>{v.title}</span>
                    <span className="text-sf-label text-sf-muted">{v.summary}</span>
                </span>
                {nav('Следующий день', v.next, ChevronRight, wide ? '' : '-mr-3')}
                {wide && (
                    <button type="button" aria-label="Закрыть" onClick={onClose}
                        className="-mr-2 flex h-11 w-8 shrink-0 items-center justify-center text-sf-muted"><X size={14} /></button>
                )}
            </div>
            <div className="relative mt-1.5 min-h-0 flex-1">
                <div tabIndex={0} aria-label="Что в этот день" className="flex h-full flex-col overflow-y-auto">
                    {v.rows.map(r => <Row key={r.key} r={r} wide={wide} />)}
                </div>
                <span aria-hidden="true" className="pointer-events-none absolute inset-x-0 bottom-0 h-8 bg-gradient-to-b from-transparent to-[var(--sf-page)]" />
            </div>
            <Link to={`/budget?date=${date}`}
                className={cn('mt-1 flex shrink-0 items-center justify-between border-t border-sf-rule font-semibold text-sf-accent',
                    wide ? 'h-11 text-sf-control' : 'h-12 text-sf-body')}>
                <span>Открыть день в журнале</span><ChevronRight size={wide ? 16 : 18} />
            </Link>
        </div>
    );
}
