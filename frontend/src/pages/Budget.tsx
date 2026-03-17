import { useEffect, useState, useCallback } from 'react';
import { fetchEvents, cycleEventPriority } from '../api';
import type { FinancialEvent } from '../types/api';
import EditEventSheet from '../components/EditEventSheet';
import PriorityButton from '../components/PriorityButton';
import { Badge } from '../components/ui/badge';
import { ScrollArea } from '../components/ui/scroll-area';

const fmt = (n: number | null) =>
    n != null
        ? new Intl.NumberFormat('ru-RU', { minimumFractionDigits: 0 }).format(n) + ' ₽'
        : '—';

function startOfWeek(date: Date): Date {
    const d = new Date(date);
    const day = d.getDay();
    const diff = day === 0 ? -6 : 1 - day;
    d.setDate(d.getDate() + diff);
    d.setHours(0, 0, 0, 0);
    return d;
}

function addDays(date: Date, days: number): Date {
    const d = new Date(date);
    d.setDate(d.getDate() + days);
    return d;
}

function formatDateYMD(date: Date): string {
    const y = date.getFullYear();
    const m = String(date.getMonth() + 1).padStart(2, '0');
    const d = String(date.getDate()).padStart(2, '0');
    return `${y}-${m}-${d}`;
}

type Week = { label: string; start: string; end: string };

function buildWeeks(year: number, month: number): Week[] {
    const firstDay = new Date(year, month, 1);
    const lastDay = new Date(year, month + 1, 0);
    const weeks: Week[] = [];
    let weekStart = startOfWeek(firstDay);
    let weekNum = 1;
    while (weekStart <= lastDay) {
        const weekEnd = addDays(weekStart, 6);
        weeks.push({
            label: `Неделя ${weekNum++}`,
            start: formatDateYMD(new Date(Math.max(weekStart.getTime(), firstDay.getTime()))),
            end: formatDateYMD(new Date(Math.min(weekEnd.getTime(), lastDay.getTime()))),
        });
        weekStart = addDays(weekStart, 7);
    }
    return weeks;
}

const DAY_NAMES = ['Вс', 'Пн', 'Вт', 'Ср', 'Чт', 'Пт', 'Сб'];

function getDayLabel(dateStr: string): { dow: string; dayNum: number } {
    const d = new Date(dateStr + 'T00:00:00');
    return { dow: DAY_NAMES[d.getDay()], dayNum: d.getDate() };
}

export default function Budget() {
    const now = new Date();
    const [year, setYear] = useState(now.getFullYear());
    const [month, setMonth] = useState(now.getMonth());
    const [events, setEvents] = useState<FinancialEvent[]>([]);
    const [loading, setLoading] = useState(true);
    const [openWeeks, setOpenWeeks] = useState<Record<string, boolean>>({});
    const [selectedEvent, setSelectedEvent] = useState<FinancialEvent | null>(null);

    const load = useCallback(() => {
        const start = formatDateYMD(new Date(year, month, 1));
        const end = formatDateYMD(new Date(year, month + 1, 0));
        setLoading(true);
        fetchEvents(start, end)
            .then(setEvents)
            .finally(() => setLoading(false));
    }, [year, month]);

    useEffect(() => { load(); }, [load]);

    const weeks = buildWeeks(year, month);
    const monthLabel = new Date(year, month).toLocaleDateString('ru-RU', { month: 'long', year: 'numeric' });

    // Компактный итог месяца
    const totalPlannedIncome = events
        .filter(e => e.type === 'INCOME')
        .reduce((s, e) => s + (e.plannedAmount ?? 0), 0);
    const totalFactIncome = events
        .filter(e => e.type === 'INCOME' && e.status === 'EXECUTED')
        .reduce((s, e) => s + (e.factAmount ?? e.plannedAmount ?? 0), 0);
    const totalPlannedExpense = events
        .filter(e => e.type === 'EXPENSE')
        .reduce((s, e) => s + (e.plannedAmount ?? 0), 0);
    const totalFactExpense = events
        .filter(e => e.type === 'EXPENSE' && e.status === 'EXECUTED')
        .reduce((s, e) => s + (e.factAmount ?? e.plannedAmount ?? 0), 0);

    return (
        <>
            <ScrollArea className="h-[calc(100dvh-var(--nav-height))]">
            <div className="pl-4 pr-5 py-6 space-y-4">
                {/* Навигация по месяцу */}
                <div className="flex items-center justify-between mb-2">
                    <button
                        onClick={() => { if (month === 0) { setMonth(11); setYear((y: number) => y - 1); } else setMonth((m: number) => m - 1); }}
                        className="text-lg px-3 py-1 rounded-lg" style={{ color: 'var(--color-accent)' }}>‹</button>
                    <h2 className="font-semibold capitalize">{monthLabel}</h2>
                    <button
                        onClick={() => { if (month === 11) { setMonth(0); setYear((y: number) => y + 1); } else setMonth((m: number) => m + 1); }}
                        className="text-lg px-3 py-1 rounded-lg" style={{ color: 'var(--color-accent)' }}>›</button>
                </div>

                {/* Компактный итог месяца */}
                {!loading && (
                    <div className="rounded-2xl px-5 py-3 text-sm space-y-1"
                        style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}>
                        <div className="flex justify-between">
                            <span style={{ color: 'var(--color-text-muted)' }}>Доходы</span>
                            <span>
                                {totalFactIncome > 0 && (
                                    <span style={{ color: 'var(--color-success)' }}>факт {fmt(totalFactIncome)} / </span>
                                )}
                                <span style={{ color: 'var(--color-text-muted)' }}>план {fmt(totalPlannedIncome)}</span>
                            </span>
                        </div>
                        <div className="flex justify-between">
                            <span style={{ color: 'var(--color-text-muted)' }}>Расходы</span>
                            <span>
                                {totalFactExpense > 0 && (
                                    <span>факт {fmt(totalFactExpense)} / </span>
                                )}
                                <span style={{ color: 'var(--color-text-muted)' }}>план {fmt(totalPlannedExpense)}</span>
                            </span>
                        </div>
                    </div>
                )}

                {loading && <p className="text-center text-sm animate-pulse" style={{ color: 'var(--color-text-muted)' }}>Загрузка...</p>}

                {!loading && weeks.map(week => {
                    const weekEvents = events.filter((e: FinancialEvent) => e.date != null && e.date >= week.start && e.date <= week.end);
                    const isOpen = openWeeks[week.label] !== false;
                    return (
                        <div key={week.label} className="rounded-2xl overflow-hidden"
                            style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}>
                            <button
                                onClick={() => setOpenWeeks((s: Record<string, boolean>) => ({ ...s, [week.label]: !isOpen }))}
                                className="w-full flex items-center justify-between px-5 py-3 text-sm font-semibold">
                                <span>{week.label}</span>
                                <span style={{ color: 'var(--color-text-muted)' }}>
                                    {weekEvents.length} событий {isOpen ? '▲' : '▼'}
                                </span>
                            </button>
                            {isOpen && (
                                <div>
                                    {weekEvents.length === 0 ? (
                                        <p className="px-5 py-3 text-sm" style={{ color: 'var(--color-text-muted)' }}>Нет событий</p>
                                    ) : (() => {
                                        // Group events by date
                                        const byDay = weekEvents.reduce<Record<string, FinancialEvent[]>>((acc, e) => {
                                            (acc[e.date!] ??= []).push(e);
                                            return acc;
                                        }, {});
                                        const sortedDays = Object.keys(byDay).sort();

                                        return sortedDays.map((day, dayIdx) => {
                                            const { dow, dayNum } = getDayLabel(day);
                                            const dayEvts = byDay[day];
                                            return (
                                                <div
                                                    key={day}
                                                    style={{
                                                        display: 'grid',
                                                        gridTemplateColumns: '48px 1fr',
                                                        borderTop: dayIdx > 0 ? '1px solid var(--color-border)' : undefined,
                                                    }}
                                                >
                                                    {/* Left: date label */}
                                                    <div className="flex flex-col items-center justify-start pt-3 pb-2 select-none"
                                                        style={{ color: 'var(--color-text-muted)', fontSize: '11px', lineHeight: 1.3 }}>
                                                        <span>{dow}</span>
                                                        <span className="font-semibold text-sm mt-0.5"
                                                            style={{ color: 'var(--color-text)' }}>{dayNum}</span>
                                                    </div>
                                                    {/* Right: events */}
                                                    <div className="divide-y" style={{ borderColor: 'var(--color-border)' }}>
                                                        {dayEvts.map((event: FinancialEvent) => {
                                                            const delta = event.factAmount != null && event.plannedAmount != null
                                                                ? event.factAmount - event.plannedAmount : null;
                                                            const isIncome = event.type === 'INCOME';
                                                            const isFundTransfer = event.type === 'FUND_TRANSFER';
                                                            const isExecuted = event.status === 'EXECUTED';
                                                            const displayName = isFundTransfer
                                                                ? `↪ ${event.targetFundName ?? 'Копилка'}`
                                                                : event.description || event.categoryName || 'Без названия';
                                                            const displaySubtitle = !isFundTransfer && event.description
                                                                ? event.categoryName
                                                                : null;
                                                            const amountColor = isIncome
                                                                ? 'var(--color-success)'
                                                                : isFundTransfer
                                                                    ? 'hsl(var(--primary))'
                                                                    : isExecuted ? 'var(--color-text-muted)' : 'var(--color-text)';
                                                            const isLowPlanned = event.priority === 'LOW' && event.status === 'PLANNED';
                                                            return (
                                                                <div key={event.id}
                                                                    onClick={() => setSelectedEvent(event)}
                                                                    className={`pr-5 py-3 flex items-center justify-between gap-3 cursor-pointer hover:bg-white/5 transition-colors${isLowPlanned ? ' opacity-60' : ''}`}>
                                                                    <div className="flex-1 min-w-0">
                                                                        <div className="flex items-center gap-2">
                                                                            <span className="font-medium text-sm truncate">{displayName}</span>
                                                                            <PriorityButton
                                                                                priority={event.priority}
                                                                                onCycle={() => cycleEventPriority(event.id).then(load)}
                                                                            />
                                                                            {isExecuted && (
                                                                                <Badge variant="outline" className="text-xs border-green-600/60 text-green-500 px-1.5 py-0">✓</Badge>
                                                                            )}
                                                                        </div>
                                                                        {displaySubtitle && (
                                                                            <p className="text-xs truncate" style={{ color: 'var(--color-text-muted)' }}>{displaySubtitle}</p>
                                                                        )}
                                                                    </div>
                                                                    <div className="text-right shrink-0 space-y-0.5">
                                                                        <div className="text-sm font-semibold" style={{ color: amountColor }}>
                                                                            {isIncome ? '+' : '-'}{fmt(event.plannedAmount)}
                                                                        </div>
                                                                        {event.factAmount != null && (
                                                                            <div className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
                                                                                факт: {fmt(event.factAmount)}
                                                                                {delta != null && (
                                                                                    <span style={{ color: delta > 0 ? 'var(--color-danger)' : 'var(--color-success)', marginLeft: 4 }}>
                                                                                        {delta > 0 ? '+' : ''}{fmt(delta)}
                                                                                    </span>
                                                                                )}
                                                                            </div>
                                                                        )}
                                                                    </div>
                                                                </div>
                                                            );
                                                        })}
                                                    </div>
                                                </div>
                                            );
                                        });
                                    })()}
                                </div>
                            )}
                        </div>
                    );
                })}
            </div>
            </ScrollArea>
            {selectedEvent && (
                <EditEventSheet
                    event={selectedEvent}
                    onClose={() => setSelectedEvent(null)}
                    onSuccess={() => { setSelectedEvent(null); load(); }}
                />
            )}
        </>
    );
}
