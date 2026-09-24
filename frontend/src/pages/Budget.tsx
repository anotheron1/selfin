import { useEffect, useState, useCallback, useReducer } from 'react';
import { ChevronRight, Repeat } from 'lucide-react';
import { fetchEvents, cycleEventPriority, createLinkedFact, fetchCheckpoints, fetchAccounts } from '../api';
import type { FinancialEvent } from '../types/api';
import { favourableDelta, deltaColor } from '../lib/planFact';
import { compareByRecency, byRecencyDesc } from '../lib/eventOrder';
import { dayLabel, expectationCards, expectationsSummary, factLinkLabel, mainListEvents } from '../lib/journalSections';
import { anchorDateOf, quickClose, type QuickClose } from '../lib/quickClose';
import { QuickCloseGuard } from '../lib/quickCloseGuard';
import { canRecordFact, todayIso } from '../lib/factDate';
import { ruPlural } from '../lib/plural';
import { PRIORITY_DOT_CONFIG } from '../lib/priority';
import EditEventSheet from '../components/EditEventSheet';
import FactCreateSheet from '../components/FactCreateSheet';
import PriorityButton from '../components/PriorityButton';
import QuickCloseButton from '../components/journal/QuickCloseButton';
import ExpectationCards from '../components/journal/ExpectationCards';
import { ScrollArea } from '../components/ui/scroll-area';

const fmt = (n: number | null) =>
    n != null
        ? new Intl.NumberFormat('ru-RU', { minimumFractionDigits: 0 }).format(n) + ' ₽'
        : '—';

function createPlanFactHandlers() {
    const handleMouseEnter = (groupId: string) => {
        document.querySelectorAll(`[data-group="${groupId}"]`)
            .forEach(el => el.classList.add('pf-hovered'));
    };
    const handleMouseLeave = (groupId: string) => {
        document.querySelectorAll(`[data-group="${groupId}"]`)
            .forEach(el => el.classList.remove('pf-hovered'));
    };
    return { handleMouseEnter, handleMouseLeave };
}

const pfHandlers = createPlanFactHandlers();

function recurringTooltip(e: FinancialEvent): string {
    if (e.recurringFrequency === 'MONTHLY' && e.recurringDayOfMonth != null) {
        return `Повторяется ежемесячно ${e.recurringDayOfMonth}-го числа`;
    }
    if (e.recurringFrequency === 'YEARLY' && e.recurringDayOfMonth != null && e.recurringMonthOfYear != null) {
        const months = ['января','февраля','марта','апреля','мая','июня','июля','августа','сентября','октября','ноября','декабря'];
        return `Повторяется ${e.recurringDayOfMonth} ${months[e.recurringMonthOfYear - 1]} каждого года`;
    }
    return 'Повторяющееся событие';
}

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

function getDisplayName(event: FinancialEvent): string {
    if (event.type === 'FUND_TRANSFER') return event.targetFundName ?? 'Копилка';
    return event.description || event.rawInput || event.categoryName || '';
}

/** «23 сент.» */
const shortDate = (iso: string) =>
    new Date(iso + 'T00:00:00').toLocaleDateString('ru-RU', { day: 'numeric', month: 'short' });

const NO_CIRCLE: QuickClose = { kind: 'none' };

/** Что сделает касание кружка — для подсказки и экранного чтеца. */
function quickCloseLabel(d: QuickClose, income: boolean): string {
    if (d.kind === 'tap') return `Отметить ${income ? 'получение' : 'оплату'}: ${fmt(d.amount)} · ${shortDate(d.date)}`;
    if (d.kind === 'askDate') return 'Записать факт: после даты плана была сверка остатка — понадобится дата';
    return '';
}

export default function Budget({ refreshSignal }: { refreshSignal?: number }) {
    const now = new Date();
    const today = todayIso(now);
    const [year, setYear] = useState(now.getFullYear());
    const [month, setMonth] = useState(now.getMonth());
    const [events, setEvents] = useState<FinancialEvent[]>([]);
    const [loading, setLoading] = useState(true);
    const [openWeeks, setOpenWeeks] = useState<Record<string, boolean>>({});
    const [selectedEvent, setSelectedEvent] = useState<FinancialEvent | null>(null);
    // ANO-176: запись факта открывается из недель, из кружка брони до сверки и из карточек ожиданий.
    const [factSheet, setFactSheet] = useState<{
        planId: string; hint?: string; amount?: number; requireDate?: boolean; fromCard?: boolean;
    } | null>(null);
    // Последняя сверка основного счёта — граница кружка (lib/quickClose).
    const [anchor, setAnchor] = useState<{ loaded: boolean; date: string | null }>({ loaded: false, date: null });
    // Ревью #56: у каждой брони своя занятость, и держится она до перечитанного журнала
    // (lib/quickCloseGuard). Одно значение на страницу освобождало бронь А касанием брони Б.
    const [guard] = useState(() => new QuickCloseGuard());
    const [, guardChanged] = useReducer((n: number) => n + 1, 0);
    const [closeFailedId, setCloseFailedId] = useState<string | null>(null);
    const [expectationsOpen, setExpectationsOpen] = useState(false);

    const load = useCallback((silent = false) => {
        const start = formatDateYMD(new Date(year, month, 1));
        const end = formatDateYMD(new Date(year, month + 1, 0));
        if (!silent) setLoading(true);
        // Брони, записанные до начала этого чтения: его успех их освобождает.
        const releases = guard.readStarted();
        return fetchEvents(start, end)
            .then(data => {
                document.querySelectorAll('.pf-hovered').forEach(el => el.classList.remove('pf-hovered'));
                setEvents(data);
                guard.readSucceeded(releases);
                guardChanged();
            }, (err) => {
                guard.readFailed(releases);
                guardChanged();
                throw err;
            })
            .finally(() => setLoading(false));
    }, [year, month, guard]);

    useEffect(() => { load(); }, [load]);

    useEffect(() => {
        Promise.all([fetchCheckpoints(), fetchAccounts()])
            .then(([checkpoints, accounts]) =>
                setAnchor({ loaded: true, date: anchorDateOf(checkpoints, accounts, todayIso(new Date())) }))
            .catch(console.error);
    }, [refreshSignal]);

    // Запись факта не идемпотентна: кружок занят, пока журнал не перечитан после записи, —
    // иначе строка ещё показывает прежний остаток, и второе касание записало бы второй факт.
    const closePlan = (plan: FinancialEvent, amount: number, date: string) => {
        if (!guard.begin(plan.id)) return;
        setCloseFailedId(null);
        guardChanged();
        createLinkedFact(plan.id, { date, factAmount: amount })
            .then(
                () => { guard.wrote(plan.id); guardChanged(); return load(true); },
                (err) => { console.error(err); guard.failed(plan.id); setCloseFailedId(plan.id); guardChanged(); },
            )
            .catch(console.error);
    };

    // Фоновое обновление при добавлении через FAB (без сброса скролла)
    useEffect(() => {
        if (refreshSignal) load(true);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [refreshSignal]);

    const weeks = buildWeeks(year, month);
    const monthLabel = new Date(year, month).toLocaleDateString('ru-RU', { month: 'long', year: 'numeric' });

    // ANO-176: что куда решает lib/journalSections, здесь только раскладка.
    const mainEvents = mainListEvents(events);
    const plansById = new Map(events.filter(e => e.eventKind === 'PLAN').map(e => [e.id, e] as const));
    const cards = expectationCards(events);
    const expSummary = expectationsSummary(events);
    const expTitle = `${PRIORITY_DOT_CONFIG.MEDIUM.plural} месяца`;
    const expSummaryText = `${expSummary.count} ${ruPlural(expSummary.count, ['строка', 'строки', 'строк'])} · ${fmt(expSummary.total)}`;
    // Пока сверка не пришла, граница — сегодня: всё прошлое спрашивает дату, а не пишет её наугад.
    const anchorDate = anchor.loaded ? anchor.date : today;
    const openCardLine = (plan: FinancialEvent) => setFactSheet({ planId: plan.id, fromCard: true });

    const totalPlannedIncome = events.filter(e => e.type === 'INCOME' && e.eventKind === 'PLAN').reduce((s, e) => s + (e.plannedAmount ?? 0), 0);
    const totalFactIncome = events.filter(e => e.type === 'INCOME' && e.eventKind === 'FACT').reduce((s, e) => s + (e.factAmount ?? 0), 0);
    const totalPlannedExpense = events.filter(e => e.type === 'EXPENSE' && e.eventKind === 'PLAN').reduce((s, e) => s + (e.plannedAmount ?? 0), 0);
    const totalFactExpense = events.filter(e => e.type === 'EXPENSE' && e.eventKind === 'FACT').reduce((s, e) => s + (e.factAmount ?? 0), 0);
    const hasFactData = events.some(e => e.eventKind === 'FACT' && e.factAmount != null);
    // ANO-7: прежний компаратор `(b.date > a.date ? 1 : -1)` никогда не возвращал 0,
    // поэтому при нескольких фактах одного дня «последним» оказывался произвольный.
    const lastFact = events
        .filter(e => e.eventKind === 'FACT' && e.factAmount != null && e.date != null)
        .slice()
        .sort(byRecencyDesc)[0] ?? null;

    return (
        <>
            <ScrollArea className="h-[calc(100dvh-var(--nav-height))]">
            <div className="pl-4 pr-5 lg:px-8 py-6 space-y-4">
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

                {/* ANO-176: широкий экран — две колонки, основной список 3/5 и ожидания 2/5 */}
                <div className="lg:grid lg:grid-cols-[minmax(0,3fr)_minmax(0,2fr)] lg:gap-7 lg:items-start">
                <div className="space-y-4 min-w-0">

                {/* Сводка месяца */}
                {!loading && events.length > 0 && (
                    <div className="rounded-2xl px-5 py-3 space-y-1 text-sm"
                        style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}>
                        <div className="flex justify-between">
                            <span style={{ color: 'var(--color-text-muted)' }}>Доходы</span>
                            <span>
                                <span className="font-medium" style={{ color: 'var(--color-success)' }}>+{fmt(totalPlannedIncome)}</span>
                                <span style={{ color: 'var(--color-text-muted)' }}> план</span>
                                {hasFactData && totalFactIncome > 0 && (
                                    <span style={{ color: 'var(--color-text-muted)' }}>
                                        {' '}/ <span className="font-medium" style={{ color: 'var(--color-success)' }}>+{fmt(totalFactIncome)}</span> факт
                                    </span>
                                )}
                            </span>
                        </div>
                        <div className="flex justify-between">
                            <span style={{ color: 'var(--color-text-muted)' }}>Расходы</span>
                            <span>
                                <span className="font-medium">-{fmt(totalPlannedExpense)}</span>
                                <span style={{ color: 'var(--color-text-muted)' }}> план</span>
                                {hasFactData && totalFactExpense > 0 && (
                                    <span style={{ color: 'var(--color-text-muted)' }}>
                                        {' '}/ <span className="font-medium">-{fmt(totalFactExpense)}</span> факт
                                    </span>
                                )}
                            </span>
                        </div>
                        {lastFact && (
                            <div className="flex justify-between pt-1 mt-1" style={{ borderTop: '1px solid var(--color-border)' }}>
                                <span style={{ color: 'var(--color-text-muted)' }}>Последний факт</span>
                                <span style={{ color: 'var(--color-text-muted)' }}>
                                    {new Date(lastFact.date! + 'T00:00:00').toLocaleDateString('ru-RU', { day: 'numeric', month: 'short' })}
                                    {' · '}
                                    <span className="font-medium" style={{ color: 'var(--color-text)' }}>
                                        -{fmt(lastFact.factAmount)}
                                    </span>
                                </span>
                            </div>
                        )}
                    </div>
                )}

                {/* Телефон: ожидания свёрнутой строкой над неделями — треть узкого экрана не читается */}
                {!loading && expSummary.count > 0 && (
                    <div className="lg:hidden rounded-2xl overflow-hidden"
                        style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}>
                        <button type="button" aria-expanded={expectationsOpen}
                            onClick={() => setExpectationsOpen(o => !o)}
                            className="w-full min-h-[52px] flex items-center justify-between gap-2.5 px-4 py-3 text-left">
                            <span className="flex items-center gap-2">
                                <span className="w-2 h-2 rounded-full" style={{ background: PRIORITY_DOT_CONFIG.MEDIUM.color }} />
                                <span className="text-sm font-semibold">{expTitle}</span>
                            </span>
                            <span className="flex items-center gap-2 text-xs whitespace-nowrap" style={{ color: 'var(--color-text-muted)' }}>
                                {expSummaryText}
                                <ChevronRight size={14} className={`transition-transform${expectationsOpen ? ' rotate-90' : ''}`} />
                            </span>
                        </button>
                        {expectationsOpen && (
                            <div className="p-3" style={{ borderTop: '1px solid var(--color-border)' }}>
                                <ExpectationCards cards={cards} onLine={openCardLine} />
                            </div>
                        )}
                    </div>
                )}

                {loading && <p className="text-center text-sm animate-pulse" style={{ color: 'var(--color-text-muted)' }}>Загрузка...</p>}

                {!loading && weeks.map(week => {
                    const weekEvents = mainEvents.filter((e: FinancialEvent) => e.date != null && e.date >= week.start && e.date <= week.end);
                    const isOpen = openWeeks[week.label] !== false;
                    return (
                        <div key={week.label} className="rounded-2xl overflow-hidden"
                            style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}>
                            <button
                                onClick={() => setOpenWeeks((s: Record<string, boolean>) => ({ ...s, [week.label]: !isOpen }))}
                                className="w-full flex items-center justify-between px-5 py-3 text-sm font-semibold">
                                <span>{week.label}</span>
                                <span style={{ color: 'var(--color-text-muted)' }}>
                                    {weekEvents.length} {ruPlural(weekEvents.length, ['запись', 'записи', 'записей'])} {isOpen ? '▲' : '▼'}
                                </span>
                            </button>
                            {isOpen && (
                                <div>
                                    {weekEvents.length === 0 ? (
                                        <p className="px-5 py-3 text-sm" style={{ color: 'var(--color-text-muted)' }}>Нет записей</p>
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

                                            // Split by eventKind: PLANs first, then FACTs.
                                            // ANO-7: внутри дня порядок хронологический (момент ввода),
                                            // а не алфавитный — искать последнюю внесённую запись
                                            // по алфавиту невозможно. Связка план-факт (PLAN сверху,
                                            // FACT под пунктиром) — визуальный контракт, он сохранён.
                                            const planEvents = dayEvts
                                                .filter(e => e.eventKind === 'PLAN')
                                                .sort(compareByRecency);
                                            const factEvents = dayEvts
                                                .filter(e => e.eventKind === 'FACT')
                                                .sort((a, b) => {
                                                    const aLinked = a.parentEventId !== null ? 0 : 1;
                                                    const bLinked = b.parentEventId !== null ? 0 : 1;
                                                    if (aLinked !== bLinked) return aLinked - bLinked;
                                                    return compareByRecency(a, b);
                                                });

                                            return (
                                                <div
                                                    key={day}
                                                    style={{
                                                        display: 'grid',
                                                        // minmax(0, …): иначе столбец не уже самой длинной строки,
                                                        // и на телефоне страница уезжает вбок вместо многоточия.
                                                        gridTemplateColumns: '48px minmax(0, 1fr)',
                                                        borderTop: dayIdx > 0 ? '1px solid var(--color-border)' : undefined,
                                                    }}
                                                >
                                                    {/* Left: date label */}
                                                    <div className="flex flex-col items-center justify-start pt-3 pb-2 select-none"
                                                        style={{ color: 'var(--color-text-muted)', fontSize: '11px', lineHeight: 1.3 }}>
                                                        <span>{dow}</span>
                                                        <span className="font-semibold text-sm mt-0.5"
                                                            style={{ color: 'var(--color-text)' }}>{dayNum}</span>
                                                        {day === today && (
                                                            <span style={{ fontSize: '10px', color: 'hsl(var(--primary))' }}>сегодня</span>
                                                        )}
                                                    </div>
                                                    {/* Right: events */}
                                                    <div className="divide-y" style={{ borderColor: 'var(--color-border)' }}>
                                                        {[...planEvents,
                                                          ...(planEvents.length > 0 && factEvents.length > 0 ? ['__divider__'] : []),
                                                          ...factEvents
                                                        ].map((item, idx) => {
                                                            if (item === '__divider__') {
                                                                return (
                                                                    <div key={`div-${idx}`}
                                                                        style={{ borderTop: '1px dashed var(--color-border)', marginTop: -1 }} />
                                                                );
                                                            }
                                                            const event = item as FinancialEvent;
                                                            const isIncome = event.type === 'INCOME';
                                                            const isFundTransfer = event.type === 'FUND_TRANSFER';
                                                            const isExecuted = event.status === 'EXECUTED';
                                                            const isLowPlanned = event.priority === 'LOW' && event.status === 'PLANNED';
                                                            const isPlan = event.eventKind === 'PLAN';
                                                            const isFact = event.eventKind === 'FACT';
                                                            const groupId = isFact
                                                                ? (event.parentEventId ?? event.id)
                                                                : event.id;
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
                                                            const decision = isPlan ? quickClose(event, today, anchorDate) : NO_CIRCLE;
                                                            const linkLabel = isFact ? factLinkLabel(event, plansById) : null;
                                                            // Старый путь записи факта прямо в план (ANO-25) — тоже факт.
                                                            const planFact = event.linkedFactsAmount ?? event.factAmount;
                                                            return (
                                                                <div key={event.id}
                                                                    data-group={groupId}
                                                                    onClick={() => setSelectedEvent(event)}
                                                                    onMouseEnter={() => pfHandlers.handleMouseEnter(groupId)}
                                                                    onMouseLeave={() => pfHandlers.handleMouseLeave(groupId)}
                                                                    className={`pl-3 pr-1.5 py-2.5 flex items-center justify-between gap-2.5 cursor-pointer hover:bg-white/5 transition-colors${isPlan ? ' pf-is-plan' : ''}${isFact ? ' pf-is-fact' : ''}${isLowPlanned ? ' opacity-60' : ''}`}
                                                                    style={{ borderLeft: isPlan ? '3px solid rgba(255,255,255,0.12)' : '3px solid hsl(var(--primary))' }}>
                                                                    <div className="flex-1 min-w-0">
                                                                        <div className="flex items-center gap-2">
                                                                            <span className="font-medium text-sm truncate">{displayName}</span>
                                                                            <PriorityButton
                                                                                priority={event.priority}
                                                                                onCycle={isPlan ? () => cycleEventPriority(event.id).then(() => load(true)) : undefined}
                                                                            />
                                                                            {/* На телефоне место отдано названию: сумма факта и так под плановой суммой. */}
                                                                            {isPlan && event.linkedFactsCount > 0 && (
                                                                                <span className="hidden sm:inline text-xs whitespace-nowrap" style={{ color: 'var(--color-text-muted)' }}>
                                                                                    {event.linkedFactsCount} {ruPlural(event.linkedFactsCount, ['факт', 'факта', 'фактов'])}
                                                                                </span>
                                                                            )}
                                                                            {event.recurringRuleId && (
                                                                                <span title={recurringTooltip(event)}>
                                                                                    <Repeat size={12} style={{ color: 'var(--color-text-muted)' }} />
                                                                                </span>
                                                                            )}
                                                                        </div>
                                                                        {displaySubtitle && (
                                                                            <p className="text-xs truncate" style={{ color: 'var(--color-text-muted)' }}>{displaySubtitle}</p>
                                                                        )}
                                                                        {linkLabel && (
                                                                            <div className="text-xs break-words" style={{ color: 'var(--color-text-muted)' }}>
                                                                                {linkLabel}
                                                                            </div>
                                                                        )}
                                                                        {/* Где есть кружок, запись идёт через него; у будущей брони
                                                                            ссылка остаётся — заплатить раньше срока можно. */}
                                                                        {isPlan && decision.kind === 'none' && (
                                                                            <button
                                                                                onClick={(e) => { e.stopPropagation(); setFactSheet({ planId: event.id }); }}
                                                                                className="text-xs mt-1"
                                                                                style={{ color: 'hsl(var(--primary))' }}
                                                                            >
                                                                                + записать факт
                                                                            </button>
                                                                        )}
                                                                        {closeFailedId === event.id && (
                                                                            <p className="text-xs mt-1" style={{ color: 'var(--color-warning)' }}>
                                                                                Не записалось — попробуйте ещё раз
                                                                            </p>
                                                                        )}
                                                                        {guard.stale(event.id) && (
                                                                            <p className="text-xs mt-1" style={{ color: 'var(--color-warning)' }}>
                                                                                Факт записан, журнал не обновился —{' '}
                                                                                <button
                                                                                    onClick={(e) => { e.stopPropagation(); load(true).catch(console.error); }}
                                                                                    className="underline"
                                                                                >
                                                                                    обновить
                                                                                </button>
                                                                            </p>
                                                                        )}
                                                                    </div>
                                                                    <div className="text-right shrink-0 space-y-0.5">
                                                                        {isPlan ? (
                                                                            <div className="flex flex-col items-end gap-0.5">
                                                                                <span style={{
                                                                                    color: isIncome ? 'rgba(74,222,128,0.5)' : 'var(--color-text-muted)',
                                                                                    fontSize: '12px'
                                                                                }}>
                                                                                    {isIncome ? '+' : '-'}{fmt(event.plannedAmount)}
                                                                                </span>
                                                                                {planFact != null ? (
                                                                                    <span style={{
                                                                                        fontSize: '11px',
                                                                                        fontWeight: 600,
                                                                                        // ANO-32: у дохода знак смысла обратный — зарплата
                                                                                        // выше плана это хорошо, а не «перерасход»
                                                                                        color: deltaColor(favourableDelta(
                                                                                            isIncome ? 'INCOME' : 'EXPENSE',
                                                                                            event.plannedAmount,
                                                                                            planFact,
                                                                                        )),
                                                                                    }}>
                                                                                        факт {fmt(planFact)}
                                                                                    </span>
                                                                                ) : decision.kind !== 'done' && event.date != null && canRecordFact(event.date, today) ? (
                                                                                    // Будущей строке «нет факта» не говорит ничего: факта там быть не может.
                                                                                    <span style={{ fontSize: '11px', color: 'var(--color-text-muted)', fontStyle: 'italic' }}>
                                                                                        нет факта
                                                                                    </span>
                                                                                ) : null}
                                                                            </div>
                                                                        ) : (
                                                                            <div className="text-sm font-semibold" style={{ color: amountColor }}>
                                                                                {isIncome ? '+' : '-'}{fmt(event.factAmount)}
                                                                            </div>
                                                                        )}
                                                                    </div>
                                                                    <QuickCloseButton
                                                                        decision={decision}
                                                                        label={quickCloseLabel(decision, isIncome)}
                                                                        busy={guard.held(event.id)}
                                                                        onTap={() => {
                                                                            if (decision.kind === 'tap') closePlan(event, decision.amount, decision.date);
                                                                        }}
                                                                        onAskDate={() => {
                                                                            if (decision.kind !== 'askDate') return;
                                                                            setFactSheet({
                                                                                planId: event.id,
                                                                                amount: decision.amount,
                                                                                requireDate: true,
                                                                                hint: `Была сверка остатка ${shortDate(decision.anchorDate)} — когда ${isIncome ? 'пришли' : 'ушли'} деньги?`,
                                                                            });
                                                                        }}
                                                                    />
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

                {/* Широкий экран: ожидания колонкой справа, по карточке на категорию */}
                {!loading && (
                    <aside className="hidden lg:flex flex-col gap-2.5 min-w-0">
                        <div className="flex items-baseline justify-between gap-2 px-1 pt-1.5">
                            <span className="text-sm font-semibold">{expTitle}</span>
                            {expSummary.count > 0 && (
                                <span className="text-xs whitespace-nowrap" style={{ color: 'var(--color-text-muted)' }}>{expSummaryText}</span>
                            )}
                        </div>
                        {expSummary.count > 0
                            ? <ExpectationCards cards={cards} onLine={openCardLine} />
                            : <p className="text-xs px-1" style={{ color: 'var(--color-text-muted)' }}>В этом месяце ожиданий нет.</p>}
                    </aside>
                )}
                </div>
            </div>
            </ScrollArea>
            {selectedEvent && (
                <EditEventSheet
                    event={selectedEvent}
                    onClose={() => setSelectedEvent(null)}
                    onSuccess={() => { setSelectedEvent(null); load(true); }}
                />
            )}
            {factSheet && (() => {
                const plan = events.find(e => e.id === factSheet.planId);
                // У строки из карточки название — категория, поэтому к нему дата строки.
                const title = plan
                    ? getDisplayName(plan) + (factSheet.fromCard && plan.date ? ` · ${dayLabel(plan.date)}` : '')
                    : 'План';
                return (
                    <FactCreateSheet
                        key={factSheet.planId}
                        planId={factSheet.planId}
                        planDescription={title}
                        planPriority={plan?.priority ?? 'MEDIUM'}
                        open
                        hint={factSheet.hint}
                        defaultAmount={factSheet.amount}
                        requireDate={factSheet.requireDate}
                        onEditPlan={factSheet.fromCard && plan ? () => { setFactSheet(null); setSelectedEvent(plan); } : undefined}
                        editPlanLabel={`изменить ${PRIORITY_DOT_CONFIG.MEDIUM.name.toLowerCase()}`}
                        onClose={() => setFactSheet(null)}
                        onCreated={() => { load(true); setFactSheet(null); }}
                    />
                );
            })()}
        </>
    );
}
