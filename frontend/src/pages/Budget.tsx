import { useEffect, useLayoutEffect, useState, useCallback, useReducer, useRef } from 'react';
import { useSearchParams } from 'react-router-dom';
import { fetchEvents, fetchEventMonths, cycleEventPriority, createLinkedFact, fetchCheckpoints, fetchAccounts, fetchCategories } from '../api';
import { CHANGED } from '../api/client';
import type { Category, FinancialEvent, HintsSettings, ScopeEnum } from '../types/api';
import { afterMonth, expectationGroups, expectationsHeader, journalDate, journalView, recordTo, type ExpectationGroup } from '../lib/journalSections';
import { weekGrid } from '../lib/weekGrid';
import {
    firstJournal, JOURNAL_PAID_HINT, JOURNAL_PLUS_HINT, journalCircleHint, loadHints, saveHints, type HintId,
} from '../lib/hints';
import { anchorDateOf, quickClose, type QuickClose } from '../lib/quickClose';
import { QuickCloseGuard } from '../lib/quickCloseGuard';
import { latestAllowedIso, todayIso } from '../lib/factDate';
import { PRIORITY_DOT_CONFIG } from '../lib/priority';
import { eventName } from '../lib/eventCard';
import { type JournalOpen, readJournalPosition, saveJournalPosition, sessionStore } from '../lib/screenSync';
import PlanFactSheet from '../components/PlanFactSheet';
import QuickAddModal from '../components/Fab';
import UndoToast from '../components/UndoToast';
import { ApiError } from '../api/client';
import EventCard, { type Written } from '../components/journal/EventCard';
import { DayHint } from '../components/today/HintBubble';
import ExpectationCircles from '../components/journal/ExpectationCircles';
import ExpectationSheet from '../components/journal/ExpectationSheet';
import JournalList from '../components/journal/JournalList';
import MonthPicker from '../components/journal/MonthPicker';
import WeekGrid from '../components/journal/WeekGrid';
import { SearchChips, SearchField, SearchResults, useJournalSearch } from '../components/journal/JournalSearch';
import { NO_SEARCH } from '../lib/journalSearch';
import { IconButton } from '@/ds';
import { cn } from '@/lib/utils';
import { Search } from 'lucide-react';

const iso = (d: Date) =>
    `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;

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
    const [loaded, setLoaded] = useState<{ key: string; events: FinancialEvent[]; after: FinancialEvent[] }>({ key: '', events: [], after: [] });
    const [fetching, setFetching] = useState(true);
    const events = loaded.key === shownKey ? loaded.events : [];
    const after = loaded.key === shownKey ? loaded.after : [];
    const loading = fetching || loaded.key !== shownKey;
    // Чтение месяца упало — сказать и дать повторить; чужой месяц по-прежнему не показывается (ревью Codex на #232).
    const [failedKey, setFailedKey] = useState<string | null>(null);
    const failed = !fetching && failedKey === shownKey;
    // Нажатие на строку — карточка; «Изменить» и строка карточки — та же форма «+», заполненная (п. 16).
    const [card, setCard] = useState<FinancialEvent | null>(null);
    const [editing, setEditing] = useState<{ event: FinancialEvent; scope: ScopeEnum } | null>(null);
    // «Ещё раз сегодня» из карточки — строка снизу с «Отменить» (S3-F3).
    const [written, setWritten] = useState<Written | null>(null);
    // Запись к плану (PlanFactSheet) открывается из «своё», из «Записать» ожидания и из «Оплачено» брони до сверки.
    const [factSheet, setFactSheet] = useState<{ planId: string; anchorDate?: string } | null>(null);
    // Ожидания (пп. 10–11): лист на телефоне, нажатая клетка сетки на 1440 — своя у месяца, форма «+» с категорией.
    const [sheetKey, setSheetKey] = useState<string | null>(null);
    const [cell, setCell] = useState<{ key: string; row: number; col: number } | null>(null);
    const [adding, setAdding] = useState<ExpectationGroup['preset'] | null>(null);
    // Категории, которые форма «+» запишет ожиданием: живые и не «брони». Ожидание из других — к строке (recordTo).
    // Не пришли — форма «+».
    const [viaForm, setViaForm] = useState<Set<string> | null>(null);
    // Категории — и для чипа «Категория» поиска, в порядке сервера (п. 14).
    const [categories, setCategories] = useState<Category[]>([]);
    useEffect(() => {
        fetchCategories().then(c => {
            setCategories(c);
            setViaForm(new Set(c.filter(x => x.priority !== 'HIGH').map(x => x.id)));
        }).catch(console.error);
    }, [refreshSignal]);
    // Поиск (п. 9, S3-S1, S3-W6): на телефоне открывается лупой, на 1440 — полем в строке месяца. Пока ищем, журнал
    // месяца спрятан, а не снят: крестик возвращает его с раскрытым и прокруткой, как были.
    const [searching, setSearching] = useState(false);
    const [criteria, setCriteria] = useState(NO_SEARCH);
    const found = useJournalSearch(criteria, searching, today);
    const searchRef = useRef<HTMLInputElement>(null);
    const scrollBefore = useRef(0);
    const searchingNow = useRef(false);
    searchingNow.current = searching;
    const openSearch = () => {
        if (searchingNow.current) return;
        scrollBefore.current = window.scrollY;
        setSearching(true);
    };
    const closeSearch = () => {
        setSearching(false);
        setCriteria(NO_SEARCH);
        searchRef.current?.blur();
        requestAnimationFrame(() => window.scrollTo(0, scrollBefore.current));
    };
    useEffect(() => { if (searching) searchRef.current?.focus(); }, [searching]);
    // Подсказки первого журнала (ANO-59) — ключ и выключатель те же, что у «Сегодня».
    const [hints, setHints] = useState<HintsSettings | null>(null);
    useEffect(() => { loadHints().then(setHints).catch(() => setHints(null)); }, []);
    const changeHints = (next: HintsSettings) => { setHints(next); saveHints(next); };
    // Последняя сверка основного счёта — граница «Оплачено» одним касанием (lib/quickClose).
    const [anchor, setAnchor] = useState<{ loaded: boolean; date: string | null }>({ loaded: false, date: null });
    // Ревью #56: у каждой брони своя занятость, и держится она до перечитанного журнала
    // (lib/quickCloseGuard). Одно значение на страницу освобождало бронь А касанием брони Б.
    const [guard] = useState(() => new QuickCloseGuard());
    const [, guardChanged] = useReducer((n: number) => n + 1, 0);
    const [closeFailedId, setCloseFailedId] = useState<string | null>(null);

    const load = useCallback((silent = false) => {
        const start = iso(new Date(year, month, 1));
        const end = iso(new Date(year, month + 1, 0));
        const key = `${year}-${month}`;
        if (!silent) setFetching(true);
        // Брони, записанные до начала этого чтения: его успех их освобождает.
        const releases = guard.readStarted();
        // Записи шести дней после месяца — только для ожиданий: они могут встать к строкам его конца (afterMonth).
        // Не прочлись — журнал всё равно показывается, без них.
        const tail = afterMonth(year, month);
        return Promise.all([fetchEvents(start, end), fetchEvents(tail.start, tail.end).catch(() => [] as FinancialEvent[])])
            .then(([data, next]) => {
                document.querySelectorAll('.pf-hovered').forEach(el => el.classList.remove('pf-hovered'));
                if (keyNow.current === key) setLoaded({ key, events: data, after: next });
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
        if (placed.current && !searchingNow.current) saveJournalPosition(sessionStore(), { year, month, scroll: window.scrollY, open: openNow.current });
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
        // Бронь раньше сверки: деньги ушли до неё или после — вопрос вместо поля даты (S3-F6b).
        if (d.kind === 'askDate') setFactSheet({ planId: plan.id, anchorDate: d.anchorDate });
    };

    // Фоновое обновление после записи через «+» и после возврата на вкладку (без сброса прокрутки)
    useEffect(() => {
        if (refreshSignal) load(true).catch(console.error);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [refreshSignal]);

    const view = journalView(events, year, month, today);
    const groups = expectationGroups(events, year, month, today, after);
    const header = expectationsHeader(events);
    const expTitle = `${PRIORITY_DOT_CONFIG.MEDIUM.plural} месяца`;
    const grid = weekGrid(groups, year, month, today);
    const selected = cell?.key === shownKey ? cell : grid.selected;
    const selectedCell = selected ? grid.rows[selected.row]?.cells[selected.col] : null;
    const highlight = new Set(selectedCell?.records.map(r => r.id) ?? []);
    const sheetGroup = groups.find(g => g.key === sheetKey) ?? null;
    // Пока сверка не пришла, граница — сегодня: всё прошлое спрашивает дату, а не пишет её наугад.
    const anchorDate = anchor.loaded ? anchor.date : today;

    // Первый журнал (п. 6, S3-N1): у «Оплачено | своё» — первая бронь расхода на этой неделе: она всегда раскрыта.
    const firstBron = view.weeks.find(w => w.current)?.days.flatMap(d => d.plans).find(r => r.kind === 'bron' && !r.income);
    const shown = loading ? [] : firstJournal({
        current: year === now.getFullYear() && month === now.getMonth(), events, circle: groups.length > 0, paid: firstBron != null,
    }, hints);
    const bubble = (id: HintId, text: string, arrow: 'up' | 'down', arrowClass: string, className: string) => (
        <DayHint id={id} shown={shown} settings={hints} onChange={changeHints}
            text={text} arrow={arrow} arrowClass={arrowClass} className={className} />
    );
    const circleHint = groups.length > 0 ? journalCircleHint(groups[0].title) : '';
    const record = (g: ExpectationGroup, line?: FinancialEvent) => {
        const to = recordTo(g, line, viaForm ?? undefined);
        void ('planId' in to ? setFactSheet({ planId: to.planId }) : setAdding(to.preset));
    };

    return (
        <>
            <div className="relative px-4 pb-6 font-sf text-sf-ink">
                {/* «+» на 1440 — «Записать» в полосе разделов над экраном: пузырь под ней, уголок вверх. */}
                {bubble('journal-plus', JOURNAL_PLUS_HINT, 'up', 'right-[84px]', 'hidden lg:block right-[calc(var(--sf-gutter)+20px)] -top-[118px] w-[270px]')}
                <div className="lg:grid lg:grid-cols-[minmax(0,1fr)_480px] lg:items-start lg:gap-12">
                <div className="min-w-0">
                    {/* Телефон: строка месяца и кружки ожиданий стоят, список листается под ними (ANO-227 п. 1). */}
                    <div ref={topRef} className="sticky top-0 z-30 -mx-4 bg-sf-page px-4 pb-2 pt-3 lg:static">
                        <div className="flex items-center gap-3">
                            <div className={cn(searching && 'hidden lg:block')}>
                                <MonthPicker year={year} month={month} loadMonths={fetchEventMonths}
                                    onChange={(y, m) => { setYear(y); setMonth(m); }} />
                            </div>
                            {!searching && (
                                <IconButton aria-label="Поиск по журналу" onClick={openSearch}
                                    className="ml-auto bg-sf-chip hover:bg-sf-chip-hover lg:hidden">
                                    <Search size={18} strokeWidth={1.8} />
                                </IconButton>
                            )}
                            <SearchField ref={searchRef} value={criteria.q} open={searching}
                                onChange={q => setCriteria(c => ({ ...c, q }))} onOpen={openSearch} onClose={closeSearch}
                                className={cn('flex-1 lg:max-w-[440px]', !searching && 'hidden lg:flex')} />
                        </div>
                        {searching && (
                            <div className="mt-3">
                                <SearchChips criteria={criteria} categories={categories} onChange={setCriteria} />
                            </div>
                        )}
                        {!searching && !loading && header && (
                            <div className="mt-3 lg:hidden">
                                <div className="mb-2.5 flex items-baseline justify-between gap-2">
                                    <span className="shrink-0 whitespace-nowrap text-sf-button font-semibold">{expTitle}</span>
                                    <span className="text-right text-sf-label text-sf-muted">{header}</span>
                                </div>
                                <ExpectationCircles groups={groups} onRecord={g => record(g)} onOpen={g => setSheetKey(g.key)}
                                    hint={bubble('journal-circle', circleHint, 'up', 'left-[38px]', 'left-0 top-[100px] w-[290px]')} />
                            </div>
                        )}
                    </div>

                    {searching && (
                        <SearchResults criteria={criteria} result={found.result} fresh={found.fresh} failed={found.failed}
                            onRetry={found.retry} onRow={setCard} />
                    )}
                    <div className={cn(searching && 'hidden')}>
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
                            highlight={highlight}
                            paidHint={firstBron && {
                                id: firstBron.event.id,
                                node: bubble('journal-paid', JOURNAL_PAID_HINT, 'up', 'right-12', 'right-0 top-full -mt-1 w-[240px]'),
                            }}
                            handlers={{
                                onRow: setCard,
                                onPaid,
                                onOwn: (plan) => setFactSheet({ planId: plan.id }),
                                onCycle: (plan) => { cycleEventPriority(plan.id).then(() => load(true)).catch(console.error); },
                                onRefresh: () => { load(true).catch(console.error); },
                            }} />}
                    </div>
                </div>

                {/* 1440: сетка недель колонкой 480 справа, листается сама (п. 11, решение 53). */}
                {!loading && (
                    <aside className="hidden min-w-0 flex-col gap-3 lg:sticky lg:top-0 lg:flex lg:max-h-dvh lg:overflow-y-auto lg:pb-6 lg:pt-3">
                        <div className="flex items-baseline justify-between gap-2 px-1 pt-1.5">
                            <span className="shrink-0 whitespace-nowrap text-sf-button font-semibold">{expTitle}</span>
                            {header && <span className="text-right text-sf-label text-sf-muted">{header}</span>}
                        </div>
                        {header
                            ? <WeekGrid grid={grid} selected={selected} onSelect={(row, col) => setCell({ key: shownKey, row, col })}
                                onRecord={record} onEdit={(line) => setEditing({ event: line, scope: 'FOLLOWING' })}
                                hint={bubble('journal-circle', circleHint, 'up', 'left-3', 'left-0 top-full mt-1 w-[290px]')} />
                            : <p className="px-1 text-sf-label text-sf-muted">В этом месяце ожиданий нет.</p>}
                    </aside>
                )}
                </div>
            </div>
            {card && (() => {
                // Строка — из перечитанного журнала: оплату в другой вкладке карточка видит, снятая строка карточку
                // закрывает (ревью Codex #236) — иначе «Оплачено» записало бы старый остаток второй раз.
                // Из поиска — строка любого месяца: ищется и в найденном, которое тоже перечитывается.
                const inMonth = events.find(e => e.id === card.id) ?? after.find(e => e.id === card.id);
                const live = inMonth ?? found.result?.rows.find(e => e.id === card.id);
                if (!live) return null;
                const pay = quickClose(live, allowedToday, anchorDate);
                // Строка только из найденного: месяц её не перечитывает, и бронь занята, пока поиск не перечитан после
                // записи — иначе после «Оплачено» карточка снова показала бы старый остаток (ревью Codex #237).
                const held = guard.held(live.id) || (!inMonth && !found.fresh);
                return (
                    <EventCard key={live.id} event={live} today={allowedToday} pay={pay} held={held}
                        onClose={() => setCard(null)}
                        onEdit={(scope) => { setCard(null); setEditing({ event: live, scope }); }}
                        onPaid={() => { if (pay) onPaid(live, pay); }}
                        onOwn={() => setFactSheet({ planId: live.id })}
                        onWritten={setWritten} />
                );
            })()}
            {editing && <QuickAddModal key={editing.event.id} edit={editing} onClose={() => setEditing(null)} onSuccess={() => {}} />}
            {written && (
                // 404 — запись уже снята: повтор после потерянного ответа — тоже отмена (ревью Codex #235).
                <UndoToast text={written.text} onDone={() => setWritten(null)}
                    onUndo={() => written.undo().catch(e => { if (!(e instanceof ApiError && e.status === 404)) throw e; })} />
            )}
            {/* «+» на телефоне — в капсуле снизу: пузырь над ней, уголок вниз. */}
            {bubble('journal-plus', JOURNAL_PLUS_HINT, 'down', 'left-[112px]', 'fixed bottom-[92px] left-1/2 z-40 -ml-[118px] w-[236px] lg:hidden')}
            {sheetGroup && (
                <ExpectationSheet key={sheetGroup.key} group={sheetGroup} onClose={() => setSheetKey(null)}
                    onEdit={line => { setSheetKey(null); setEditing({ event: line, scope: 'FOLLOWING' }); }}
                    onRecord={line => { setSheetKey(null); record(sheetGroup, line); }} />
            )}
            {adding && <QuickAddModal preset={adding} onClose={() => setAdding(null)} onSuccess={() => {}} />}
            {factSheet && (() => {
                // Строка ожидания может быть из шести дней после месяца (afterMonth) — ищется и там.
                const plan = events.find(e => e.id === factSheet.planId) ?? after.find(e => e.id === factSheet.planId)
                    ?? found.result?.rows.find(e => e.id === factSheet.planId);
                return plan && (
                    <PlanFactSheet key={factSheet.planId} plan={plan} name={eventName(plan)}
                        askDate={factSheet.anchorDate ? { anchorDate: factSheet.anchorDate } : undefined}
                        checkpointDate={anchor.loaded ? anchor.date : undefined}
                        onClose={() => setFactSheet(null)}
                        onCreated={() => { load(true).catch(console.error); setFactSheet(null); }} />
                );
            })()}
        </>
    );
}
