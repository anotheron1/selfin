import { useCallback, useEffect, useReducer, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ChevronRight, CreditCard, LayoutGrid, Mountain, RefreshCw, ShoppingBag } from 'lucide-react';
import { Button, Choices, Money, Plate, Tile } from '@/ds';
import { cn } from '@/lib/utils';
import { createLinkedFact, fetchMe, fetchPocket } from '../api';
import { CHANGED } from '../api/client';
import { profileLink } from '../components/frame/AppFrame';
import { Cells } from '../components/today/Cells';
import { DaySheet } from '../components/today/DaySheet';
import { MovePlate, WatchdogPlate } from '../components/today/GapPlate';
import { Landscape, TightDot } from '../components/today/Landscape';
import { UsualSpendingForm } from '../components/today/UsualSpendingForm';
import { WhyPlate } from '../components/today/WhyPlate';
import NzSheet from '../components/pocket/NzSheet';
import ReanchorSheet from '../components/pocket/ReanchorSheet';
import { Dialog, DialogContent, DialogTitle } from '../components/ui/dialog';
import { serverTodayIso } from '../lib/factDate';
import { fmtDayShort } from '../lib/format';
import { buildGapMode } from '../lib/gapMode';
import { buildLandscape, type LandscapeModel } from '../lib/landscape';
import { noExpectationsNote } from '../lib/noExpectationsNote';
import type { QuickClose } from '../lib/quickClose';
import { QuickCloseGuard } from '../lib/quickCloseGuard';
import { buildBreakHint } from '../lib/reanchor';
import { watchdogView } from '../lib/tightPoint';
import { buildWatchdogAlert, withoutSameGap } from '../lib/watchdogAlert';
import { buildTodayTiles, cardsTileLabel } from '../lib/todayTiles';
import type { PocketResponse, UpcomingItem } from '../types/api';

/** Периоды — прежние (опись № 18); вид календаря следует за периодом, значки меняют его вручную. */
const PERIODS = [
    { value: 'NEXT', label: 'До дохода', scope: undefined },
    { value: 'SECOND_INCOME', label: '2-й доход', scope: 'SECOND_INCOME' },
    { value: 'MONTHS:3', label: '3 мес', scope: 'MONTHS:3' },
    { value: 'MONTHS:6', label: '6 мес', scope: 'MONTHS:6' },
] as const;
type Period = (typeof PERIODS)[number]['value'];
type View = 'land' | 'cells';

const num = (n: number) => Math.round(n).toLocaleString('ru-RU');
const CELL_SCALE = ['var(--sf-cell-1)', 'var(--sf-cell-2)', 'var(--sf-cell-3)', 'var(--sf-cell-4)', 'var(--sf-cell-5)'];

/** Кольцо самого низкого остатка — тот же знак, что на склоне. */
const LowRing = () => (
    <svg viewBox="0 0 18 18" aria-hidden="true"><circle cx="9" cy="9" r="5" fill="var(--sf-ring)" stroke="var(--sf-ring-rim)" strokeWidth="2" /></svg>
);

function Legend({ data, m, view }: { data: PocketResponse; m: LandscapeModel; view: View }) {
    const sw = (bg: string, extra = '') => <span aria-hidden="true" className={cn('h-3 w-3 rounded-[3px]', extra)} style={{ background: bg }} />;
    const item = (key: string, mark: React.ReactNode, text: string) => <span key={key} className="flex items-center gap-1.5">{mark}{text}</span>;
    const items = view === 'cells'
        ? [
            <span key="scale" className="flex items-center gap-1.5">меньше{CELL_SCALE.map(c => <span key={c}>{sw(c)}</span>)}больше</span>,
            item('today', sw('transparent', 'border-[1.8px] border-sf-ink'), 'сегодня'),
            m.water && item('water', sw('var(--sf-water)'), 'ниже нуля — вода'),
        ]
        : [
            item('slope', sw('var(--sf-slope)'), 'остаток'),
            m.usualPath && item('usual', sw('var(--sf-usual)'), 'с обычными тратами'),
            data.buffer > 0 && item('mist', sw('var(--sf-mist)', 'border border-dashed border-sf-muted'), 'ниже НЗ — туман'),
            m.water && item('water', sw('linear-gradient(180deg,var(--sf-water),var(--sf-water-deep))'), 'ниже нуля — вода'),
            (m.sun || m.smallSuns.length > 0) && item('sun', <span aria-hidden="true" className="h-2.5 w-2.5 rounded-full" style={{ background: 'var(--sf-orb)' }} />, 'доход'),
            m.hazeX != null && item('haze', sw('var(--sf-chip)'), 'после срока — дымка'),
        ];
    return <div className="mt-2.5 flex flex-wrap items-center gap-x-4 gap-y-1.5 text-sf-label text-sf-muted">{items}</div>;
}

/**
 * «Сегодня» (срез 2; доски Zh2-390, Zh2-1440-7t и состояния S2-*, Zh2-G7*): число на небе, плитки, пейзаж или клетки,
 * календарь с периодами, «Из чего число». Данные — прежние: `GET /pocket` выбранного периода и сторож до 2-го дохода.
 * Справки, кнопки примерки, списков плана и записей здесь нет: строки впереди — в «Журнале», примерка — в «Целях»
 * (сторож — today.test.tsx).
 */
export default function Today({ refreshSignal }: { refreshSignal?: number }) {
    const navigate = useNavigate();
    const [period, setPeriod] = useState<Period>('NEXT');
    const [manualView, setManualView] = useState<View | null>(null);
    const [data, setData] = useState<PocketResponse | null>(null);
    const [loadFailed, setLoadFailed] = useState(false);
    // Сторож (ANO-14 §5) смотрит до 2-го дохода при любом периоде; сбой не стирает показанное предупреждение.
    const [watchdog, setWatchdog] = useState<PocketResponse | null>(null);
    const [watchdogFailed, setWatchdogFailed] = useState(false);
    const [lastVisit, setLastVisit] = useState<string | null>(null);
    const [day, setDay] = useState<string | null>(null);
    const [tightOpen, setTightOpen] = useState(false);
    const [sheet, setSheet] = useState<'reanchor' | 'nz' | 'usual' | null>(null);
    const [guard] = useState(() => new QuickCloseGuard());
    const [, guardChanged] = useReducer((n: number) => n + 1, 0);
    const [markFailed, setMarkFailed] = useState(false);

    const scope = PERIODS.find(p => p.value === period)!.scope;
    const load = useCallback(() => {
        const read = guard.readStarted();
        fetchPocket(scope)
            .then(p => { setData(p); setLoadFailed(false); guard.readSucceeded(read); guardChanged(); })
            .catch(() => { setLoadFailed(true); guard.readFailed(read); guardChanged(); });
        fetchPocket('SECOND_INCOME')
            .then(w => { setWatchdog(w); setWatchdogFailed(false); })
            .catch(() => setWatchdogFailed(true));
    }, [scope, guard]);

    useEffect(() => { load(); }, [load]);
    useEffect(() => { if (refreshSignal) load(); }, [refreshSignal, load]);
    // Срез 1: удачная запись любым экраном и листом шлёт CHANGED — число, пейзаж и сторож перечитываются.
    useEffect(() => {
        window.addEventListener(CHANGED, load);
        return () => window.removeEventListener(CHANGED, load);
    }, [load]);
    useEffect(() => { fetchMe().then(me => setLastVisit(me.lastVisit)).catch(() => setLastVisit(null)); }, []);
    // ANO-102 (опись № 16): вернулся из фона в другой день по календарю сервера — перечитать визит и число.
    const shownOn = useRef(serverTodayIso(new Date()));
    useEffect(() => {
        const resume = () => {
            const today = serverTodayIso(new Date());
            if (document.visibilityState !== 'visible' || today === shownOn.current) return;
            shownOn.current = today;
            fetchMe().then(me => setLastVisit(me.lastVisit)).catch(() => setLastVisit(null));
            load();
        };
        document.addEventListener('visibilitychange', resume);
        return () => document.removeEventListener('visibilitychange', resume);
    }, [load]);

    const choosePeriod = (v: string) => { setPeriod(v as Period); setManualView(null); setDay(null); setTightOpen(false); };

    // Кружок «Отметить оплату» у прошедших броней — правило и занятость журнала (quickClose, QuickCloseGuard).
    const markPaid = (u: UpcomingItem, d: QuickClose) => {
        if (d.kind === 'askDate') { navigate(`/budget?date=${u.date}`); return; }
        if (d.kind !== 'tap' || u.id == null || !guard.begin(u.id)) return;
        const id = u.id;
        setMarkFailed(false);
        guardChanged();
        createLinkedFact(guard.attempts, id, { date: d.date, factAmount: d.amount }).then(
            () => { guard.wrote(id); guardChanged(); load(); },
            err => { console.error(err); guard.failed(id); setMarkFailed(true); guardChanged(); },
        );
    };

    const profile = profileLink('plain', '-mr-2.5 lg:hidden');
    if (!data) {
        return (
            <div className="bg-[linear-gradient(180deg,var(--sf-sky-top)_0%,var(--sf-sky-bottom)_100%)] pb-10">
                <div className="mx-auto flex h-11 max-w-[1180px] items-center justify-between px-sf-gutter">
                    <span className="text-sf-control text-sf-muted">{loadFailed ? 'Не удалось загрузить свободные деньги' : 'Загрузка…'}</span>
                    {profile}
                </div>
                {loadFailed && <div className="mx-auto max-w-[1180px] px-sf-gutter"><Button size="s" onClick={load}>Повторить</Button></div>}
            </div>
        );
    }

    const gap = buildGapMode(data);
    const watch = watchdogView(watchdog, data);
    // «Впритык» на плитке — только когда день на графике: за краем срок «как обычно» не задет.
    const alert = withoutSameGap(buildWatchdogAlert(watchdog, data.horizon.endDate), data);
    const tiles = buildTodayTiles(data, alert && !alert.beyondChart ? alert : null);
    const view: View = manualView ?? (period.startsWith('MONTHS') ? 'cells' : 'land');
    const wideModel = buildLandscape(data, 'wide');
    const narrowModel = buildLandscape(data, 'narrow');
    const breakHint = buildBreakHint(lastVisit, data.checkpointDate, serverTodayIso(new Date()));
    const noUsual = noExpectationsNote(data) != null;
    const selected = day && data.trajectory.some(t => t.date === day) ? day : null;
    const dayX = selected ? (wideModel.points.find(p => p.date === selected)!.x / wideModel.w) * 100 : 50;
    const overlay = (m: LandscapeModel) => watch?.kind === 'dot'
        ? <TightDot m={m} date={watch.date} lines={watch.lines} open={tightOpen} onToggle={() => setTightOpen(v => !v)} />
        : null;
    const drivenBy = gap && data.minPoint.drivenBy ? `В этот день — «${data.minPoint.drivenBy}». ` : '';
    const noUsualPlate = noUsual && (
        <Plate className="flex gap-3" data-plate="no-usual">
            <span className="mt-0.5 flex shrink-0 text-sf-kind-ozh"><ShoppingBag size={22} strokeWidth={1.7} aria-hidden="true" /></span>
            <div className="flex flex-1 flex-col gap-1">
                <span className="text-sf-body font-semibold">Обычных трат в плане нет</span>
                <span className="text-sf-note">Число выше, чем будет на деле: продукты, транспорт, кафе ещё не вычтены.</span>
                <Button variant="soft" size="s" className="mt-1 self-start" onClick={() => setSheet('usual')}>Добавить</Button>
            </div>
        </Plate>
    );
    const why = (size: 'narrow' | 'wide') => (
        <WhyPlate data={data} size={size} onNz={() => setSheet('nz')} onReanchor={() => setSheet('reanchor')}
            overdue={{ busy: id => guard.held(id), onMark: markPaid }} aside={size === 'wide' ? noUsualPlate : undefined} />
    );

    return (
        <div>
            <section className="relative bg-[linear-gradient(180deg,var(--sf-sky-top)_0%,var(--sf-sky-mid)_52%,var(--sf-sky-bottom)_100%)]">
                <div className="mx-auto flex max-w-[1180px] flex-col px-sf-gutter lg:pt-1">
                    <div className="flex h-11 items-center justify-between">
                        <span className="text-sf-control text-sf-muted lg:text-sf-body">
                            {gap ? gap.lead : `Свободно до ${fmtDayShort(data.horizon.endDate)}`}
                        </span>
                        {profile}
                    </div>
                    <Money value={num(gap ? gap.shortfall : data.pocket)} className="lg:text-[104px]" />
                    {gap && <p className="mt-2.5 text-sf-note text-sf-muted">{drivenBy}{gap.subline}</p>}
                    <div className="mt-3.5 flex gap-2 lg:mt-5 lg:max-w-[660px] lg:gap-2.5">
                        {tiles.map(t => t.key === 'cards'
                            ? <Tile key={t.key} className="flex-1" icon={<CreditCard strokeWidth={1.7} />} value={t.value}
                                caption={<><RefreshCw strokeWidth={2.4} />{t.caption}</>} onClick={() => setSheet('reanchor')}
                                aria-label={cardsTileLabel(data)} />
                            : <Tile key={t.key} className={cn('flex-1', t.tight && 'bg-sf-plate')}
                                icon={t.key === 'usual' ? <ShoppingBag strokeWidth={1.7} /> : <LowRing />}
                                value={<><span className="sr-only">{t.sr} </span>{t.value}</>} caption={t.caption} />)}
                    </div>
                    {breakHint && (
                        <button type="button" onClick={() => setSheet('reanchor')}
                            className="mt-2.5 flex min-h-[44px] items-center gap-2.5 rounded-sf-field bg-sf-plate px-3 py-2 text-left text-sf-note lg:max-w-[660px]">
                            <RefreshCw size={16} className="shrink-0 text-sf-kind-ozh" aria-hidden="true" />
                            <span className="flex-1">{breakHint}</span>
                            <ChevronRight size={16} aria-hidden="true" />
                        </button>
                    )}
                </div>
                {view === 'land'
                    ? <>
                        <div className="lg:hidden">
                            <Landscape data={data} size="narrow" selectedDate={selected} onDay={setDay} overlay={overlay} />
                        </div>
                        <div className="relative hidden lg:block">
                            <Landscape data={data} size="wide" selectedDate={selected} onDay={setDay} overlay={overlay} />
                            {selected && <DaySheet data={data} date={selected} size="wide" xPct={dayX} onDate={setDay} onClose={() => setDay(null)} />}
                        </div>
                    </>
                    : <div className="mx-auto max-w-[1180px] px-sf-gutter pb-10 pt-4">
                        <div className="lg:hidden"><Cells data={data} size="narrow" onDay={setDay} /></div>
                        <div className="relative hidden lg:block">
                            <Cells data={data} size="wide" onDay={setDay} />
                            {selected && <DaySheet data={data} date={selected} size="wide" xPct={50} onDate={setDay} onClose={() => setDay(null)} />}
                        </div>
                    </div>}
            </section>

            <section className="relative -mt-6 rounded-t-[28px] bg-sf-page lg:-mt-7 lg:rounded-t-[32px]">
                <div className="mx-auto flex max-w-[1180px] flex-col px-sf-gutter pb-6 pt-4 lg:pt-6">
                    {watch?.kind === 'plate' && (
                        <div className="mb-3.5"><WatchdogPlate view={watch} onShowSecond={() => choosePeriod('SECOND_INCOME')} /></div>
                    )}
                    {gap && gap.movable.length > 0 && (
                        <div className="mb-3.5"><MovePlate gap={gap} horizonEnd={data.horizon.endDate} onReanchor={() => setSheet('reanchor')} /></div>
                    )}
                    {noUsual && <div className="mb-3.5 lg:hidden">{noUsualPlate}</div>}
                    <div className="flex flex-col gap-2.5 lg:flex-row lg:items-center lg:gap-7">
                        <div className="flex min-h-[44px] items-center justify-between gap-3 lg:contents">
                            <div className="flex min-w-0 flex-col">
                                <span className="text-[16px] font-semibold leading-tight lg:text-[17px]">Кассовый календарь</span>
                                <span className="text-sf-label text-sf-muted">остаток по дням</span>
                            </div>
                            <Choices name="calendar-view" label="Вид календаря" value={view} onChange={v => setManualView(v as View)}
                                className="w-[92px] shrink-0 lg:order-last lg:ml-auto"
                                options={[
                                    { value: 'land', label: <><Mountain size={20} strokeWidth={1.7} aria-hidden="true" /><span className="sr-only">Пейзажем</span></> },
                                    { value: 'cells', label: <><LayoutGrid size={20} strokeWidth={1.7} aria-hidden="true" /><span className="sr-only">Клетками по дням</span></> },
                                ]} />
                        </div>
                        <Choices name="period" label="Период" look="chip" size="control" value={period} onChange={choosePeriod}
                            options={PERIODS.map(p => ({ value: p.value, label: p.label }))} />
                    </div>
                    <Legend data={data} m={narrowModel} view={view} />
                    <span className="mt-0.5 text-sf-label text-sf-muted">
                        {watch?.kind === 'dot' && view === 'land'
                            ? 'Нажми на точку — где может не хватить. На день — что в нём.'
                            : 'Нажми на день — что в этот день придёт и уйдёт.'}
                    </span>
                    <div className="mt-3 lg:mt-[18px]">
                        <div className="lg:hidden">{why('narrow')}</div>
                        <div className="hidden lg:block">{why('wide')}</div>
                    </div>
                    {markFailed && <p role="alert" className="mt-2 text-sf-label text-sf-muted">Оплата не отметилась — попробуй ещё раз</p>}
                    {watchdogFailed && !watchdog && (
                        <p className="mt-3 text-center text-sf-label text-sf-muted">Не удалось проверить кассовый разрыв — обнови страницу</p>
                    )}
                </div>
            </section>

            {selected && <DaySheet data={data} date={selected} size="narrow" onDate={setDay} onClose={() => setDay(null)} />}
            <ReanchorSheet open={sheet === 'reanchor'} onOpenChange={o => setSheet(o ? 'reanchor' : null)}
                currentBalance={data.currentBalance} checkpointDate={data.checkpointDate} onSuccess={load} />
            <NzSheet open={sheet === 'nz'} onOpenChange={o => setSheet(o ? 'nz' : null)} buffer={data.buffer} onSuccess={load} />
            <Dialog open={sheet === 'usual'} onOpenChange={o => setSheet(o ? 'usual' : null)}>
                <DialogContent>
                    <DialogTitle className="sr-only">Обычные траты</DialogTitle>
                    {sheet === 'usual' && <UsualSpendingForm data={data} onDone={() => { setSheet(null); load(); }} />}
                </DialogContent>
            </Dialog>
        </div>
    );
}
