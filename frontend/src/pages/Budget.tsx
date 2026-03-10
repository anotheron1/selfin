import { useEffect, useState, useCallback } from 'react';
import { fetchEvents, fetchDashboard } from '../api';
import type { CashGapAlert, FinancialEvent } from '../types/api';
import EditEventSheet from '../components/EditEventSheet';
import { AlertTriangle } from 'lucide-react';
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
    return date.toISOString().slice(0, 10);
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

export default function Budget() {
    const now = new Date();
    const [year, setYear] = useState(now.getFullYear());
    const [month, setMonth] = useState(now.getMonth());
    const [events, setEvents] = useState<FinancialEvent[]>([]);
    const [loading, setLoading] = useState(true);
    const [openWeeks, setOpenWeeks] = useState<Record<string, boolean>>({});
    const [selectedEvent, setSelectedEvent] = useState<FinancialEvent | null>(null);
    const [cashGap, setCashGap] = useState<CashGapAlert | null>(null);

    const load = useCallback(() => {
        const start = formatDateYMD(new Date(year, month, 1));
        const end = formatDateYMD(new Date(year, month + 1, 0));
        setLoading(true);
        const dateParam = formatDateYMD(new Date(year, month, 15));
        Promise.all([
            fetchEvents(start, end),
            fetchDashboard(dateParam),
        ]).then(([evts, dash]) => {
            setEvents(evts);
            setCashGap(dash.cashGapAlert);
        }).finally(() => setLoading(false));
    }, [year, month]);

    useEffect(() => { load(); }, [load]);

    const weeks = buildWeeks(year, month);
    const monthLabel = new Date(year, month).toLocaleDateString('ru-RU', { month: 'long', year: 'numeric' });

    return (
        <>
            <ScrollArea className="h-[calc(100dvh-var(--nav-height))]">
            <div className="px-4 py-6 space-y-4">
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

                {loading && <p className="text-center text-sm animate-pulse" style={{ color: 'var(--color-text-muted)' }}>Загрузка...</p>}

                {/* Баннер кассового разрыва */}
                {!loading && cashGap && (
                    <div className="flex items-start gap-3 rounded-2xl px-4 py-3"
                        style={{ background: 'rgba(239,68,68,0.12)', border: '1px solid rgba(239,68,68,0.35)' }}>
                        <AlertTriangle size={18} style={{ color: 'var(--color-danger)', flexShrink: 0, marginTop: 2 }} />
                        <div>
                            <p className="text-sm font-semibold" style={{ color: 'var(--color-danger)' }}>Кассовый разрыв!</p>
                            <p className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
                                {new Date(cashGap.gapDate).toLocaleDateString('ru-RU', { day: 'numeric', month: 'long' })}
                                {' — '}
                                не хватит{' '}
                                <span style={{ color: 'var(--color-danger)' }}>
                                    {new Intl.NumberFormat('ru-RU').format(Math.abs(cashGap.gapAmount))} ₽
                                </span>
                            </p>
                        </div>
                    </div>
                )}

                {!loading && weeks.map(week => {
                    const weekEvents = events.filter((e: FinancialEvent) => e.date >= week.start && e.date <= week.end);
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
                                <div className="divide-y" style={{ borderColor: 'var(--color-border)' }}>
                                    {weekEvents.length === 0 ? (
                                        <p className="px-5 py-3 text-sm" style={{ color: 'var(--color-text-muted)' }}>Нет событий</p>
                                    ) : weekEvents.map((event: FinancialEvent) => {
                                        const delta = event.factAmount != null && event.plannedAmount != null
                                            ? event.factAmount - event.plannedAmount : null;
                                        const isIncome = event.type === 'INCOME';
                                        const isFundTransfer = event.type === 'FUND_TRANSFER';
                                        const isExecuted = event.status === 'EXECUTED';
                                        const displayName = isFundTransfer
                                            ? `↪ ${event.targetFundName ?? 'Копилка'}`
                                            : event.categoryName;
                                        const amountColor = isIncome
                                            ? 'var(--color-success)'
                                            : isFundTransfer
                                                ? 'hsl(var(--primary))'
                                                : isExecuted ? 'var(--color-text-muted)' : 'var(--color-text)';
                                        return (
                                            <div key={event.id}
                                                onClick={() => setSelectedEvent(event)}
                                                className="px-5 py-3 flex items-center justify-between gap-3 cursor-pointer hover:bg-white/5 transition-colors">
                                                <div className="flex-1 min-w-0">
                                                    <div className="flex items-center gap-2">
                                                        <span className="font-medium text-sm truncate">{displayName}</span>
                                                        {event.mandatory && (
                                                            <Badge variant="outline" className="text-xs border-destructive/60 text-destructive px-1.5 py-0">обяз</Badge>
                                                        )}
                                                        {isExecuted && (
                                                            <Badge variant="outline" className="text-xs border-green-600/60 text-green-500 px-1.5 py-0">✓</Badge>
                                                        )}
                                                    </div>
                                                    {event.description && (
                                                        <p className="text-xs truncate" style={{ color: 'var(--color-text-muted)' }}>{event.description}</p>
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
