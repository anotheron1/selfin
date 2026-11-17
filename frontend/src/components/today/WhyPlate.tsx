import { useState } from 'react';
import { ChevronDown, ChevronUp, Pencil } from 'lucide-react';
import { Button, plateVariants } from '@/ds';
import { cn } from '@/lib/utils';
import { BALANCE_ROW, buildBreakdownView, CREDIT_ROW, OVERDUE_ROW, type BreakdownRow } from '../../lib/breakdownRows';
import { fmtDayShort, fmtRub } from '../../lib/format';
import { pocketResult, pocketState } from '../../lib/gapMode';
import { ruPlural } from '../../lib/plural';
import { quickClose, type QuickClose } from '../../lib/quickClose';
import type { FinancialEvent, PocketResponse, UpcomingItem } from '../../types/api';

/** Имена строк на «Сегодня» — с досок Zh2-390x и Zh2-1440-7t; «Цели» до среза 6 говорят по-старому. */
const RENAME: Record<string, string> = { [BALANCE_ROW]: 'На картах', [CREDIT_ROW]: 'Вернуть на кредитку' };

type MarkKind = 'both' | 'go' | 'free' | 'short' | null;
const MARK: Record<Exclude<MarkKind, null>, string> = {
    both: 'linear-gradient(90deg, var(--sf-bar-go) 50%, var(--sf-accent) 50%)',
    go: 'var(--sf-bar-go)',
    free: 'var(--sf-accent)',
    short: 'var(--sf-kind-ozh)',
};

/** Метка строки повторяет кусок полосы (решение 35): остаток и доход делятся на оба куска, вычеты — «уйдёт». */
function markOf(row: BreakdownRow, ok: boolean): MarkKind {
    if (row.kind === 'result') return ok ? 'free' : 'short';
    if (row.kind !== 'item') return null;
    return row.label === BALANCE_ROW || row.amount.startsWith('+') ? 'both' : 'go';
}

/** Бронь из «осталось потратить» глазами кружка журнала: у просроченной брони фактов нет по построению движка. */
const asPlan = (u: UpcomingItem): FinancialEvent => ({
    id: u.id, date: u.date, eventKind: 'PLAN', plannedAmount: u.amount, linkedFactsAmount: 0, linkedFactsCount: 0,
    factAmount: null, status: 'PLANNED', priority: u.priority, type: u.type,
} as FinancialEvent);

const monthName = (iso: string) => {
    const [y, m] = iso.split('-').map(Number);
    return new Date(y, m - 1, 1).toLocaleDateString('ru-RU', { month: 'long' });
};

function byMonth(items: UpcomingItem[]): { month: string; items: UpcomingItem[] }[] {
    const groups: { key: string; month: string; items: UpcomingItem[] }[] = [];
    for (const u of [...items].sort((a, b) => a.date.localeCompare(b.date))) {
        const key = u.date.slice(0, 7);
        if (groups[groups.length - 1]?.key !== key) groups.push({ key, month: monthName(u.date), items: [] });
        groups[groups.length - 1].items.push(u);
    }
    return groups;
}

/** «18 строк с 31 авг. по 10 нояб. — оплата не отмечена» (доска S2-O-B). */
function overdueNote(items: UpcomingItem[]): string {
    const dates = items.map(u => u.date).sort();
    const n = `${items.length} ${ruPlural(items.length, ['строка', 'строки', 'строк'])}`;
    const span = dates[0] === dates[dates.length - 1]
        ? `, ${fmtDayShort(dates[0])}` : ` с ${fmtDayShort(dates[0])} по ${fmtDayShort(dates[dates.length - 1])}`;
    return `${n}${span} — оплата не отмечена`;
}

export interface OverdueMarking {
    /** Кружок занят — эта бронь уже закрывается (`QuickCloseGuard.held`). */
    busy: (id: string) => boolean;
    onMark: (item: UpcomingItem, decision: QuickClose) => void;
}

/**
 * «Из чего число» (спека среза 2, пп. 4, 5): плитка с полосой «уйдёт по плану | свободно», по нажатию — строки
 * `buildBreakdownView`, ниже «Кроме этого», последней — НЗ ✎. Строка броней с прошедшей датой раскрывается
 * списком по месяцам с кружком «Отметить оплату» (решение 49, S2-O-B). На 1440 раскрыто сразу, «Кроме этого» —
 * своей колонкой справа (Zh2-1440-7t).
 */
export function WhyPlate({ data, size, onNz, onReanchor, overdue, aside, initialOpen, initialOverdueOpen = false }: {
    data: PocketResponse;
    size: 'narrow' | 'wide';
    onNz: () => void;
    onReanchor: () => void;
    overdue: OverdueMarking;
    /** На 1440 — над «Кроме этого» в правой колонке, а без неё — вместо неё (плашка «Обычных трат в плане нет», п. 10). */
    aside?: React.ReactNode;
    initialOpen?: boolean;
    initialOverdueOpen?: boolean;
}) {
    const [open, setOpen] = useState(initialOpen ?? size === 'wide');
    const [overdueOpen, setOverdueOpen] = useState(initialOverdueOpen);
    const view = buildBreakdownView(data);
    const ok = pocketState(data) === 'ok';
    const result = pocketResult(data);
    const free = Math.max(data.pocket, 0);
    const go = Math.max(data.currentBalance - free, 0);
    const today = data.trajectory[0]?.date ?? '';
    const overdueItems = data.upcoming.filter(u => u.overdue && u.id != null);

    const caveats: BreakdownRow[] = [...view.caveats];
    if (data.pocketWithDeposits != null) {
        // Имени вклада в ответе /pocket нет — пояснение словами справки, а не «Отпуск» с доски.
        caveats.push({ kind: 'caveat', label: 'Если распечатать вклад', amount: fmtRub(data.pocketWithDeposits),
            note: 'столько будет свободно, если снять вклад' });
    }
    const wide = size === 'wide';
    const text = wide ? 'text-sf-body' : 'text-sf-control';

    const rowView = (row: BreakdownRow, key: string) => {
        const mark = markOf(row, ok);
        const isOverdue = row.label === OVERDUE_ROW && overdueItems.length > 0;
        const label = RENAME[row.label] ?? row.label;
        return (
            <div key={key} className={cn('flex gap-2', wide ? 'py-2' : 'py-[7px]')} data-row={label}>
                <span aria-hidden="true" className="mt-[5px] h-2.5 w-2.5 shrink-0 rounded-[3px]"
                    style={mark ? { background: MARK[mark] } : undefined} data-mark={mark ?? undefined} />
                <div className="flex min-w-0 flex-1 flex-col">
                    <div className={cn('flex items-baseline justify-between gap-3', text, row.kind === 'result' && 'font-semibold')}>
                        {isOverdue
                            ? <button type="button" aria-expanded={overdueOpen} onClick={() => setOverdueOpen(v => !v)}
                                className="-my-2 flex min-h-[44px] items-center gap-1 text-left">
                                {label}{overdueOpen ? <ChevronUp size={14} /> : <ChevronDown size={14} />}
                            </button>
                            : <span>{label}</span>}
                        <span className="whitespace-nowrap tabular-nums">{row.amount}</span>
                    </div>
                    {(isOverdue ? overdueNote(overdueItems) : row.note) &&
                        <span className="text-sf-label text-sf-muted">{isOverdue ? overdueNote(overdueItems) : row.note}</span>}
                    {isOverdue && overdueOpen && (
                        <div className="mt-1 flex flex-col" data-list="overdue">
                            {byMonth(overdueItems).map(g => (
                                <div key={g.month} className="flex flex-col">
                                    <span className="mt-2 text-sf-caption font-semibold text-sf-muted">{g.month}</span>
                                    {g.items.map(u => {
                                        const decision = quickClose(asPlan(u), today, data.checkpointDate);
                                        const name = `${fmtRub(u.amount)} · ${fmtDayShort(u.date)}`;
                                        return (
                                            <div key={u.id} className="flex min-h-[40px] items-center gap-2.5 border-b border-sf-rule">
                                                <span className="w-[58px] shrink-0 text-sf-label text-sf-muted">{fmtDayShort(u.date)}</span>
                                                <span className={cn('min-w-0 flex-1 truncate', text)}>
                                                    {u.description?.trim() || u.categoryName}
                                                </span>
                                                <span className={cn('whitespace-nowrap tabular-nums', text)}>{fmtRub(u.amount)}</span>
                                                {decision.kind === 'tap' || decision.kind === 'askDate'
                                                    ? <button type="button" aria-label={`Отметить оплату: ${name}`}
                                                        disabled={overdue.busy(u.id!)} onClick={() => overdue.onMark(u, decision)}
                                                        className="-my-1 -mr-2 flex h-11 w-11 shrink-0 items-center justify-center rounded-full disabled:opacity-40">
                                                        <span className="h-7 w-7 rounded-full border-2 border-sf-accent" />
                                                    </button>
                                                    : <span className="w-9 shrink-0" aria-hidden="true" />}
                                            </div>
                                        );
                                    })}
                                </div>
                            ))}
                            <div className="mt-3 flex items-center gap-2.5 rounded-sf-box bg-sf-page px-3 py-2.5">
                                <span className="flex-1 text-sf-note">
                                    Всё это уже оплачено? Обнови остаток — брони до сверки снимутся разом.
                                </span>
                                <Button variant="soft" size="s" onClick={onReanchor}>Обновить</Button>
                            </div>
                        </div>
                    )}
                </div>
            </div>
        );
    };

    const nzRow = (
        <button type="button" onClick={onNz} aria-label="Задать НЗ"
            className="mt-1 flex min-h-[44px] items-center gap-1.5 border-t border-sf-rule text-sf-note text-sf-muted">
            {data.buffer > 0 ? `НЗ ${fmtRub(data.buffer)}` : 'НЗ не задан'}
            <Pencil size={12} />
        </button>
    );

    const caveatRows = caveats.map((r, i) => rowView(r, `k${i}`));
    const head = (
        <button type="button" aria-expanded={open} onClick={() => setOpen(v => !v)} className="flex flex-col text-left">
            <span className={cn('flex h-7 w-full items-center justify-between font-semibold', wide ? 'text-sf-button' : 'text-sf-body')}>
                <span>Из чего число</span>{open ? <ChevronUp size={18} /> : <ChevronDown size={18} />}
            </span>
            <span aria-hidden="true" className={cn('flex h-3.5 w-full gap-0.5 overflow-hidden rounded-[7px]', wide ? 'mt-2' : 'mt-1.5')}>
                {go > 0 && <span style={{ flex: `${go} 1 0`, background: 'var(--sf-bar-go)' }} data-bar="go" />}
                {free > 0 && <span style={{ flex: `${free} 1 0`, background: 'var(--sf-accent)' }} data-bar="free" />}
            </span>
            {/* Свёрнуто — куски полосы названы числами (S2-R1, S2-P1); раскрыто — то же говорят строки. */}
            {!open && (
                <span className="mt-2 flex w-full justify-between gap-2 text-sf-label text-sf-muted">
                    <span>уйдёт по плану {fmtRub(go)}</span>
                    <span className="font-medium text-sf-ink">{result.label[0].toLowerCase()}{result.label.slice(1)} {fmtRub(result.amount)}</span>
                </span>
            )}
        </button>
    );
    const body = open && (
        <div className="mt-2.5 flex flex-col">
            {view.calc.map((r, i) => rowView(r, `c${i}`))}
            <div className="my-1.5 h-px bg-sf-rule" />
            {rowView(view.result, 'r')}
            {!wide && caveats.length > 0 && <>
                <span className="mt-2.5 pb-1 text-sf-label font-semibold text-sf-muted">Кроме этого — в число не входит</span>
                {caveatRows}
            </>}
            {nzRow}
        </div>
    );

    if (!wide) return <div className={cn(plateVariants(), 'flex flex-col pb-2.5 pt-3')}>{head}{body}</div>;
    return (
        <div className="grid grid-cols-2 items-start gap-6">
            <div className={cn(plateVariants(), 'flex flex-col rounded-[24px] px-[22px] pb-3 pt-4')}>{head}{body}</div>
            <div className="flex flex-col gap-6">
                {aside}
                {caveats.length > 0 && (
                    <div className="flex flex-col rounded-[24px] border border-sf-rule px-[22px] pb-3 pt-4">
                        <span className="flex h-7 items-center text-sf-button font-semibold">Кроме этого — в число не входит</span>
                        <div className="mt-1.5 flex flex-col">{caveatRows}</div>
                    </div>
                )}
            </div>
        </div>
    );
}
