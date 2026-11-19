import { useEffect, useLayoutEffect, useState, useCallback, useReducer, useRef } from 'react';
import { useSearchParams } from 'react-router-dom';
import { ChevronRight } from 'lucide-react';
import { fetchEvents, fetchEventMonths, cycleEventPriority, createLinkedFact, fetchCheckpoints, fetchAccounts } from '../api';
import { CHANGED } from '../api/client';
import type { FinancialEvent } from '../types/api';
import { dayLabel, expectationCards, expectationsSummary, journalDate, journalView } from '../lib/journalSections';
import { anchorDateOf, type QuickClose } from '../lib/quickClose';
import { QuickCloseGuard } from '../lib/quickCloseGuard';
import { latestAllowedIso, todayIso } from '../lib/factDate';
import { ruPlural } from '../lib/plural';
import { PRIORITY_DOT_CONFIG } from '../lib/priority';
import { type JournalOpen, readJournalPosition, saveJournalPosition, sessionStore } from '../lib/screenSync';
import EditEventSheet from '../components/EditEventSheet';
import FactCreateSheet from '../components/FactCreateSheet';
import ExpectationCards from '../components/journal/ExpectationCards';
import JournalList from '../components/journal/JournalList';
import MonthPicker from '../components/journal/MonthPicker';

const fmt = (n: number) => new Intl.NumberFormat('ru-RU', { minimumFractionDigits: 0 }).format(n) + ' ₽';

const iso = (d: Date) =>
    `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;

function getDisplayName(event: FinancialEvent): string {
    if (event.type === 'FUND_TRANSFER') return event.targetFundName ?? 'Копилка';
    return event.description || event.rawInput || event.categoryName || '';
}

/** «23 сент.» */
const shortDate = (s: string) =>
    new Date(s + 'T00:00:00').toLocaleDateString('ru-RU', { day: 'numeric', month: 'short' });

/**
 * «Журнал» (срез 3, п. 6–8; доски Zh2-J8, Zh2-J8b, левая колонка S3-W1d). Дни — lib/journalSections, вид списка —
 * components/journal/JournalList. На телефоне строка месяца и ожидания стоят, список листается под ними; на 1440 две
 * колонки листаются каждая сама: список — страницей, ожидания — своей прокруткой (решение 53).
 */
export default function Budget({ refreshSignal }: { refreshSignal?: number }) {
    const now = new Date();
    // ANO-235: два «сегодня». Подпись дня — местная дата: человек живёт по своим часам. «Оплачено» и «своё» —
    // не позже даты сервера: факт местным «сегодня» восточнее Москвы ночью он отверг бы (@PastOrPresent).
    const today = todayIso(now);
    const allowedToday = latestAllowedIso(now);
    // «Открыть день в журнале» с листа дня (срез 2, п. 8): месяц этой даты и одна прокрутка к ней после чтения.
    const [params] = useSearchParams();
    const [target] = useState(() => journalDate(params.get('date')));
    // Ушёл из журнала и вернулся в этой вкладке — тот же месяц и та же прокрутка (п. 8, ANO-126).
    const [saved] = useState(() => (target ? null : readJournalPosition(sessionStore())));
    const placed = useRef(false);
    const topRef = useRef<HTMLDivElement>(null);
    const [year, setYear] = useState(target?.year ?? saved?.year ?? now.getFullYear());
    const [month, setMonth] = useState(target?.month ?? saved?.month ?? now.getMonth());
    // События — вместе с месяцем, к которому прочитаны: сразу после смены месяца в руках ещё прошлые, и вид по ним
    // ронял экран (строка вне недель месяца); поздний ответ прошлого месяца не перезаписывает новый.
    const shownKey = `${year}-${month}`;
    const keyNow = useRef(shownKey);
    keyNow.current = shownKey;
    const [loaded, setLoaded] = useState<{ key: string; events: FinancialEvent[] }>({ key: '', events: [] });
    const [fetching, setFetching] = useState(true);
    const events = loaded.key === shownKey ? loaded.events : [];
    const loading = fetching || loaded.key !== shownKey;
    // Чтение месяца упало — сказать и дать повторить; чужой месяц по-прежнему не показывается (ревью Codex на #232).
    const [failedKey, setFailedKey] = useState<string | null>(null);
    const failed = !fetching && failedKey === shownKey;
    const [selectedEvent, setSelectedEvent] = useState<FinancialEvent | null>(null);
    // ANO-176: запись к плану открывается из «своё», из «Оплачено» брони до сверки и из карточек ожиданий.
    const [factSheet, setFactSheet] = useState<{
        planId: string; hint?: string; amount?: number; requireDate?: boolean; fromCard?: boolean;
    } | null>(null);
    // Последняя сверка основного счёта — граница «Оплачено» одним касанием (lib/quickClose).
    const [anchor, setAnchor] = useState<{ loaded: boolean; date: string | null }>({ loaded: false, date: null });
    // Ревью #56: у каждой брони своя занятость, и держится она до перечитанного журнала
    // (lib/quickCloseGuard). Одно значение на страницу освобождало бронь А касанием брони Б.
    const [guard] = useState(() => new QuickCloseGuard());
    const [, guardChanged] = useReducer((n: number) => n + 1, 0);
    const [closeFailedId, setCloseFailedId] = useState<string | null>(null);
    const [expectationsOpen, setExpectationsOpen] = useState(false);

    const load = useCallback((silent = false) => {
        const start = iso(new Date(year, month, 1));
        const end = iso(new Date(year, month + 1, 0));
        const key = `${year}-${month}`;
        if (!silent) setFetching(true);
        // Брони, записанные до начала этого чтения: его успех их освобождает.
        const releases = guard.readStarted();
        return fetchEvents(start, end)
            .then(data => {
                document.querySelectorAll('.pf-hovered').forEach(el => el.classList.remove('pf-hovered'));
                if (keyNow.current === key) setLoaded({ key, events: data });
                guard.readSucceeded(releases);
                guardChanged();
            }, (err) => {
                if (keyNow.current === key) setFailedKey(key);
                guard.readFailed(releases);
                guardChanged();
                throw err;
            })
            .finally(() => setFetching(false));
    }, [year, month, guard]);

    useEffect(() => { load().catch(console.error); }, [load]);

    // Граница «Оплачено» одним касанием — последняя сверка основного счёта; сверка в другой вкладке её сдвигает.
    const loadAnchor = useCallback(() => {
        Promise.all([fetchCheckpoints(), fetchAccounts()])
            .then(([checkpoints, accounts]) =>
                setAnchor({ loaded: true, date: anchorDateOf(checkpoints, accounts, todayIso(new Date())) }))
            .catch(console.error);
    }, []);
    useEffect(() => { loadAnchor(); }, [loadAnchor, refreshSignal]);

    // Запись в этой вкладке и в другой (срез 3, п. 8: событие приходит и из BroadcastChannel) — журнал перечитывается,
    // и граница сверки с ним: иначе «Оплачено» записало бы датой плана мимо новой сверки (ревью Codex на #232).
    useEffect(() => {
        const reread = () => { load(true).catch(console.error); loadAnchor(); };
        window.addEventListener(CHANGED, reread);
        return () => window.removeEventListener(CHANGED, reread);
    }, [load, loadAnchor]);

    // Одна расстановка после первого чтения: к дню из ?date= или к запомненной прокрутке.
    useEffect(() => {
        if (loading || placed.current) return;
        placed.current = true;
        if (target) {
            // Будущий день — строка во «Впереди» (оно раскрыто, см. opened). Строк в день нет — день не нарисован:
            // будущий — к «Впереди», прошедший — к его неделе.
            const week = [...document.querySelectorAll<HTMLElement>('section[data-week-start]')]
                .find(w => w.dataset.weekStart! <= target.iso && target.iso <= w.dataset.weekEnd!);
            const el = document.getElementById(`day-${target.iso}`)
                ?? document.querySelector<HTMLElement>(`[data-date="${target.iso}"]`)
                ?? (target.iso > today ? document.querySelector<HTMLElement>('[data-ahead]') : week);
            if (el) {
                // На телефоне строка месяца липкая: цель встаёт под ней, а не за ней.
                const top = topRef.current;
                const cover = top && getComputedStyle(top).position === 'sticky' ? top.offsetHeight : 0;
                window.scrollTo(0, el.getBoundingClientRect().top + window.scrollY - cover);
            }
        } else if (saved) {
            window.scrollTo(0, saved.scroll);
        }
    }, [loading, target, saved, today]);

    // Позиция пишется на каждую прокрутку, смену месяца и раскрытие раздела; снимается до того, как страница сменится.
    // Раскрытое — вместе с ней: иначе журнал вернулся бы свёрнутым и прокрутка попала бы не туда (ревью Codex на #232).
    const openNow = useRef<JournalOpen | undefined>(undefined);
    const savePosition = useCallback(() => {
        if (placed.current) saveJournalPosition(sessionStore(), { year, month, scroll: window.scrollY, open: openNow.current });
    }, [year, month]);
    useLayoutEffect(() => {
        savePosition();
        window.addEventListener('scroll', savePosition, { passive: true });
        return () => window.removeEventListener('scroll', savePosition);
    }, [savePosition]);
    const onOpened = useCallback((o: JournalOpen) => { openNow.current = o; savePosition(); }, [savePosition]);

    // Повтор после сбоя безопасен — тот же ключ попытки (ANO-192). Но после записи кнопка занята,
    // пока журнал не перечитан: строка ещё показывает прежний остаток, и новое касание — уже новый факт.
    const closePlan = (plan: FinancialEvent, amount: number, date: string) => {
        if (!guard.begin(plan.id)) return;
        setCloseFailedId(null);
        guardChanged();
        createLinkedFact(guard.attempts, plan.id, { date, factAmount: amount })
            .then(
                () => { guard.wrote(plan.id); guardChanged(); return load(true); },
                (err) => { console.error(err); guard.failed(plan.id); setCloseFailedId(plan.id); guardChanged(); },
            )
            .catch(console.error);
    };

    const onPaid = (plan: FinancialEvent, d: QuickClose) => {
        if (d.kind === 'tap') closePlan(plan, d.amount, d.date);
        if (d.kind === 'askDate') {
            const income = plan.type === 'INCOME';
            setFactSheet({
                planId: plan.id, amount: d.amount, requireDate: true,
                hint: `Была сверка остатка ${shortDate(d.anchorDate)} — когда ${income ? 'пришли' : 'ушли'} деньги?`,
            });
        }
    };

    // Фоновое обновление после записи через «+» и после возврата на вкладку (без сброса прокрутки)
    useEffect(() => {
        if (refreshSignal) load(true).catch(console.error);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [refreshSignal]);

    const view = journalView(events, year, month, today);
    const cards = expectationCards(events);
    const expSummary = expectationsSummary(events);
    const expTitle = `${PRIORITY_DOT_CONFIG.MEDIUM.plural} месяца`;
    const expSummaryText = `${expSummary.count} ${ruPlural(expSummary.count, ['строка', 'строки', 'строк'])} · ${fmt(expSummary.total)}`;
    // Пока сверка не пришла, граница — сегодня: всё прошлое спрашивает дату, а не пишет её наугад.
    const anchorDate = anchor.loaded ? anchor.date : today;
    const openCardLine = (plan: FinancialEvent) => setFactSheet({ planId: plan.id, fromCard: true });

    return (
        <>
            <div className="px-4 pb-6 font-sf text-sf-ink">
                <div className="lg:grid lg:grid-cols-[minmax(0,1fr)_400px] lg:items-start lg:gap-12">
                <div className="min-w-0">
                    {/* Телефон: строка месяца и ожидания стоят, список листается под ними (ANO-227 п. 1). */}
                    <div ref={topRef} className="sticky top-0 z-30 -mx-4 bg-sf-page px-4 pb-2 pt-3 lg:static">
                        <MonthPicker year={year} month={month} loadMonths={fetchEventMonths}
                            onChange={(y, m) => { setYear(y); setMonth(m); }} />
                        {/* Ожидания — как были, до кружков PR 3: на телефоне свёрнутой строкой. */}
                        {!loading && expSummary.count > 0 && (
                            <div className="mt-2 overflow-hidden rounded-2xl lg:hidden"
                                style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}>
                                <button type="button" aria-expanded={expectationsOpen}
                                    onClick={() => setExpectationsOpen(o => !o)}
                                    className="flex min-h-[52px] w-full items-center justify-between gap-2.5 px-4 py-3 text-left">
                                    <span className="flex items-center gap-2">
                                        <span className="h-2 w-2 rounded-full" style={{ background: PRIORITY_DOT_CONFIG.MEDIUM.color }} />
                                        <span className="text-sm font-semibold">{expTitle}</span>
                                    </span>
                                    <span className="flex items-center gap-2 whitespace-nowrap text-xs" style={{ color: 'var(--color-text-muted)' }}>
                                        {expSummaryText}
                                        <ChevronRight size={14} className={`transition-transform${expectationsOpen ? ' rotate-90' : ''}`} />
                                    </span>
                                </button>
                                {expectationsOpen && (
                                    <div className="max-h-[50dvh] overflow-y-auto p-3" style={{ borderTop: '1px solid var(--color-border)' }}>
                                        <ExpectationCards cards={cards} onLine={openCardLine} />
                                    </div>
                                )}
                            </div>
                        )}
                    </div>

                    {loading
                        ? failed
                            ? (
                                <p className="py-6 text-center text-sf-note text-sf-muted">
                                    Журнал не загрузился —{' '}
                                    <button type="button" className="underline" onClick={() => { load().catch(console.error); }}>обновить</button>
                                </p>
                            )
                            : <p className="animate-pulse py-6 text-center text-sf-note text-sf-muted">Загрузка…</p>
                        : <JournalList key={`${year}-${month}`} view={view} allowedToday={allowedToday} anchorDate={anchorDate}
                            openDate={target?.iso} onOpened={onOpened}
                            opened={placed.current ? undefined
                                : saved && saved.year === year && saved.month === month ? saved.open
                                : target && target.year === year && target.month === month && target.iso > today ? { ahead: true, weeks: {} }
                                : undefined}
                            busy={(id) => guard.held(id)} failed={(id) => closeFailedId === id} stale={(id) => guard.stale(id)}
                            handlers={{
                                onRow: setSelectedEvent,
                                onPaid,
                                onOwn: (plan) => setFactSheet({ planId: plan.id }),
                                onCycle: (plan) => { cycleEventPriority(plan.id).then(() => load(true)).catch(console.error); },
                                onRefresh: () => { load(true).catch(console.error); },
                            }} />}
                </div>

                {/* 1440: ожидания колонкой справа, листаются сами (решение 53); вид — как был, до сетки PR 3. */}
                {!loading && (
                    <aside className="hidden min-w-0 flex-col gap-2.5 lg:sticky lg:top-0 lg:flex lg:max-h-dvh lg:overflow-y-auto lg:pt-3">
                        <div className="flex items-baseline justify-between gap-2 px-1 pt-1.5">
                            <span className="text-sf-body font-semibold">{expTitle}</span>
                            {expSummary.count > 0 && (
                                <span className="whitespace-nowrap text-sf-label text-sf-muted">{expSummaryText}</span>
                            )}
                        </div>
                        {expSummary.count > 0
                            ? <ExpectationCards cards={cards} onLine={openCardLine} />
                            : <p className="px-1 text-sf-label text-sf-muted">В этом месяце ожиданий нет.</p>}
                    </aside>
                )}
                </div>
            </div>
            {selectedEvent && (
                <EditEventSheet
                    event={selectedEvent}
                    onClose={() => setSelectedEvent(null)}
                    onSuccess={() => { setSelectedEvent(null); load(true).catch(console.error); }}
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
                        onCreated={() => { load(true).catch(console.error); setFactSheet(null); }}
                    />
                );
            })()}
        </>
    );
}
