import { useCallback, useEffect, useState } from 'react';
import { fetchDashboard, fetchEvents, fetchPocket } from '../api';
import type { DashboardData, DailyForecastPoint, FinancialEvent, PocketResponse } from '../types/api';
import { AlertTriangle } from 'lucide-react';
import { Badge } from '../components/ui/badge';
import PocketCard from '../components/PocketCard';
import PocketTrajectoryChart from '../components/pocket/PocketTrajectoryChart';
import { buildWatchdogAlert } from '../lib/watchdogAlert';
import { fmtRub } from '../lib/format';

const fmt = fmtRub;

const fmtAmt = (n: number | null) => n != null ? fmt(n) : '—';

/** «14 июля» из ISO-строки БЕЗ UTC-парсинга (new Date('YYYY-MM-DD') сдвигает день в западных TZ). */
const fmtLocalDate = (iso: string) => {
    const [y, m, d] = iso.split('-').map(Number);
    return new Date(y, m - 1, d).toLocaleDateString('ru-RU', { day: 'numeric', month: 'long' });
};

interface BarState {
    barColor: string;
    barWidth: string;
    badge: string;
    badgeColor: string;
}

function getBarState(planned: number, fact: number): BarState {
    if (planned === 0 && fact > 0) {
        return { barColor: 'transparent', barWidth: '0%', badge: 'Не запланировано', badgeColor: '#f59e0b' };
    }
    if (planned > 0 && fact === 0) {
        return { barColor: 'transparent', barWidth: '0%', badge: 'Не выполнено', badgeColor: '#6b7280' };
    }
    if (planned > 0 && fact > planned * 1.05) {
        return { barColor: '#ef4444', barWidth: '100%', badge: 'Перерасход', badgeColor: '#ef4444' };
    }
    if (planned > 0 && fact >= planned * 0.95) {
        const pct = Math.min(Math.round((fact / planned) * 100), 100);
        return { barColor: '#22c55e', barWidth: `${pct}%`, badge: 'Выполнено', badgeColor: '#22c55e' };
    }
    if (planned > 0 && fact > 0) {
        const pct = Math.round((fact / planned) * 100);
        return { barColor: '#60a5fa', barWidth: `${pct}%`, badge: 'В процессе', badgeColor: '#60a5fa' };
    }
    // plan=0, fact=0 — show empty bar, no badge
    return { barColor: '#6b7280', barWidth: '0%', badge: '', badgeColor: '#6b7280' };
}

function getBarMax(plannedLimit: number, projection: number | null): number {
    if (!projection || projection <= plannedLimit) return plannedLimit;
    return Math.max(plannedLimit * 1.25, projection * 1.1);
}

interface SparklineProps {
    history: DailyForecastPoint[];
    plannedLimit: number;
    projectionAmount: number;
    daysInMonth: number;
}

function ForecastSparkline({ history, plannedLimit, projectionAmount, daysInMonth }: SparklineProps) {
    if (history.length === 0) return null;

    const W = 180, H = 65;
    const PAD = 5;
    const xRange = W - PAD * 2;
    const yRange = H - PAD * 2;

    const maxValue = Math.max(plannedLimit, projectionAmount, ...history.map(p => p.projectedTotal)) * 1.05;

    const x = (day: number) => PAD + ((day - 1) / (daysInMonth - 1)) * xRange;
    const y = (val: number) => H - PAD - (val / maxValue) * yRange;

    const today = history[history.length - 1];
    const todayX = x(today.day);

    const factPoints = history.map(p => `${x(p.day)},${y(p.cumulativeFact)}`).join(' ');
    const projPoints = history.map(p => `${x(p.day)},${y(p.projectedTotal)}`).join(' ');
    const futureEndX = x(daysInMonth);
    const futureEndY = y(projectionAmount);
    const planY = y(plannedLimit);

    return (
        <div className="bg-[#0e0e1e] border border-[#2a2a3a] rounded-lg p-3 mt-2 w-[220px]">
            <div className="text-[10px] text-muted-foreground mb-1">Динамика месяца</div>
            <svg width={W} height={H} viewBox={`0 0 ${W} ${H}`}>
                {/* Plan line */}
                <line x1={0} y1={planY} x2={W} y2={planY}
                    stroke="rgba(255,255,255,0.2)" strokeWidth={1} strokeDasharray="4 3" />
                {/* Fact line */}
                <polyline points={factPoints} fill="none" stroke="#6c63ff" strokeWidth={2} strokeLinejoin="round" />
                {/* Projection history line */}
                <polyline points={projPoints} fill="none" stroke="#ffaa44" strokeWidth={1.5}
                    strokeDasharray="3 3" strokeLinejoin="round" />
                {/* Future projection */}
                <line x1={todayX} y1={y(today.cumulativeFact)} x2={futureEndX} y2={futureEndY}
                    stroke="#ff5a5a" strokeWidth={1.5} strokeDasharray="4 4" opacity={0.8} />
                {/* Today marker */}
                <line x1={todayX} y1={0} x2={todayX} y2={H}
                    stroke="rgba(108,99,255,0.4)" strokeWidth={1} strokeDasharray="2 2" />
            </svg>
            <div className="flex justify-between text-[10px] text-muted-foreground mt-1">
                <span>1</span><span>{daysInMonth}</span>
            </div>
            <div className="flex gap-3 mt-1.5 flex-wrap">
                {[
                    { color: '#6c63ff', label: 'факт', dashed: false },
                    { color: '#ffaa44', label: 'прогноз по дням', dashed: true },
                    { color: '#ff5a5a', label: 'прогноз вперёд', dashed: true },
                ].map(({ color, label, dashed }) => (
                    <div key={label} className="flex items-center gap-1">
                        <div className="w-3.5 h-0.5 rounded" style={{
                            background: dashed
                                ? `repeating-linear-gradient(90deg,${color} 0,${color} 3px,transparent 3px,transparent 6px)`
                                : color
                        }} />
                        <span className="text-[10px] text-muted-foreground">{label}</span>
                    </div>
                ))}
            </div>
        </div>
    );
}

export default function Dashboard({ refreshSignal }: { refreshSignal?: number }) {
    const _today = new Date();
    const todayStr = `${_today.getFullYear()}-${String(_today.getMonth() + 1).padStart(2, '0')}-${String(_today.getDate()).padStart(2, '0')}`;

    const [data, setData] = useState<DashboardData | null>(null);
    const [pocket, setPocket] = useState<PocketResponse | null>(null);
    // Сторожевой скоуп (ANO-14 §5): алерт разрыва всегда смотрит до 2-го дохода,
    // независимо от скоупа PocketCard. Ошибка сторожа не роняет страницу и НЕ стирает
    // ранее показанный алерт (транзиентный сбой не должен молча снять предупреждение).
    const [watchdog, setWatchdog] = useState<PocketResponse | null>(null);
    const [watchdogFailed, setWatchdogFailed] = useState(false);
    const [todayEvents, setTodayEvents] = useState<FinancialEvent[]>([]);
    const [monthEvents, setMonthEvents] = useState<FinancialEvent[]>([]);
    const [error, setError] = useState<string | null>(null);

    const loadAll = useCallback(() => {
        const today = new Date();
        const ld = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
        const monthStart = ld(new Date(today.getFullYear(), today.getMonth(), 1));
        const monthEnd = ld(new Date(today.getFullYear(), today.getMonth() + 1, 0));

        Promise.all([
            fetchDashboard(),
            fetchEvents(todayStr, todayStr),
            fetchEvents(monthStart, monthEnd),
        ])
            .then(([dash, evts, mEvts]) => {
                setData(dash);
                setTodayEvents(evts);
                setMonthEvents(mEvts);
            })
            .catch(e => setError(e.message));

        fetchPocket('SECOND_INCOME')
            .then(wd => { setWatchdog(wd); setWatchdogFailed(false); })
            .catch(() => setWatchdogFailed(true)); // прежний watchdog-стейт сохраняем
    }, [todayStr]);

    useEffect(() => { loadAll(); }, [loadAll]);

    // Фоновое обновление при добавлении через FAB
    useEffect(() => {
        if (refreshSignal) loadAll();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [refreshSignal]);

    if (error) return (
        <div className="p-6 text-center" style={{ color: 'var(--color-danger)' }}>Ошибка: {error}</div>
    );

    if (!data) return (
        <div className="p-6 text-center animate-pulse" style={{ color: 'var(--color-text-muted)' }}>Загрузка...</div>
    );

    // Прогноз конца дня: остаток кармашка + плановые суммы ещё не исполненных событий сегодня
    const unexecutedToday = todayEvents.filter(e => e.status === 'PLANNED');
    const endOfDayForecast = unexecutedToday.reduce((bal, e) => {
        const amt = e.plannedAmount ?? 0;
        return e.type === 'INCOME' ? bal + amt : bal - amt;
    }, pocket?.currentBalance ?? 0);
    const hasUnexecutedToday = unexecutedToday.length > 0;

    const incomeToday = todayEvents.filter(e => e.type === 'INCOME');
    const expenseToday = todayEvents.filter(e => e.type === 'EXPENSE' || e.type === 'FUND_TRANSFER');

    const watchdogAlert = buildWatchdogAlert(watchdog, pocket?.horizon.endDate ?? null);

    return (
        <div className="overflow-y-auto overflow-x-hidden scrollbar-none" style={{ height: 'calc(100dvh - var(--nav-height))' }}>
        <div className="pl-4 pr-5 py-6 space-y-5">
            {/* Hero: кармашек — единый ответ из GET /pocket (ANO-12/13).
                Старые «Текущий баланс» + зарплатные горизонты DashboardService удалены:
                расчёт расходился с кармашком (см. флаг в ANO-12). */}
            <PocketCard onData={setPocket} refreshSignal={refreshSignal} onReanchor={loadAll} />

            {/* События сегодня */}
            {todayEvents.length > 0 && (
                <div className="rounded-2xl p-5 space-y-3"
                    style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}>
                    <div className="space-y-2">
                        <p className="text-xs font-semibold uppercase" style={{ color: 'var(--color-text-muted)' }}>
                            Сегодня
                        </p>

                        {/* Доходы */}
                        {incomeToday.map(e => (
                            <div key={e.id} className="flex items-center justify-between gap-2 text-sm">
                                <div className="flex items-center gap-2 min-w-0">
                                    <span className="truncate">{e.description || e.categoryName || 'Без названия'}</span>
                                    {e.mandatory && (
                                        <Badge variant="outline" className="text-xs border-destructive/60 text-destructive px-1.5 py-0 shrink-0">обяз</Badge>
                                    )}
                                    {e.status === 'EXECUTED' && (
                                        <span className="text-xs shrink-0" style={{ color: 'var(--color-success)' }}>✓</span>
                                    )}
                                </div>
                                <span className="font-medium shrink-0" style={{ color: 'var(--color-success)' }}>
                                    +{fmtAmt(e.factAmount ?? e.plannedAmount)}
                                </span>
                            </div>
                        ))}

                        {/* Расходы и переводы в копилку */}
                        {expenseToday.map(e => (
                            <div key={e.id} className="flex items-center justify-between gap-2 text-sm">
                                <div className="flex items-center gap-2 min-w-0">
                                    <span className="truncate">
                                        {e.type === 'FUND_TRANSFER'
                                            ? `↪ ${e.targetFundName ?? 'Копилка'}`
                                            : e.description || e.categoryName || 'Без названия'}
                                    </span>
                                    {e.mandatory && (
                                        <Badge variant="outline" className="text-xs border-destructive/60 text-destructive px-1.5 py-0 shrink-0">обяз</Badge>
                                    )}
                                    {e.status === 'EXECUTED' && (
                                        <span className="text-xs shrink-0" style={{ color: 'var(--color-success)' }}>✓</span>
                                    )}
                                </div>
                                <span className="font-medium shrink-0" style={{ color: 'var(--color-text-muted)' }}>
                                    -{fmtAmt(e.factAmount ?? e.plannedAmount)}
                                </span>
                            </div>
                        ))}

                        {/* Прогноз конца дня — только если есть неисполненные */}
                        {hasUnexecutedToday && pocket && (
                            <div className="flex justify-between items-center pt-2 border-t text-sm font-semibold"
                                style={{ borderColor: 'var(--color-border)' }}>
                                <span style={{ color: 'var(--color-text-muted)' }}>Прогноз конца дня</span>
                                <span style={{ color: endOfDayForecast >= 0 ? 'var(--color-success)' : 'var(--color-danger)' }}>
                                    {fmt(endOfDayForecast)}
                                </span>
                            </div>
                        )}
                    </div>
                </div>
            )}

            {/* Алерт кассового разрыва — из minPoint сторожевого скоупа SECOND_INCOME (ANO-14 §5) */}
            {watchdogAlert && (
                <div className="rounded-xl p-4 flex gap-3 items-start"
                    style={{ background: 'rgba(239,68,68,0.12)', border: '1px solid var(--color-danger)' }}>
                    <AlertTriangle size={18} style={{ color: 'var(--color-danger)', flexShrink: 0, marginTop: 2 }} />
                    <div>
                        <p className="font-semibold text-sm" style={{ color: 'var(--color-danger)' }}>Кассовый разрыв!</p>
                        <p className="text-sm" style={{ color: 'var(--color-text)' }}>
                            {fmtLocalDate(watchdogAlert.date)} — ожидается дефицит{' '}
                            <b>{fmt(watchdogAlert.deficit)}</b>
                            {watchdogAlert.drivenBy && <> («{watchdogAlert.drivenBy}»)</>}
                        </p>
                        {watchdogAlert.beyondChart && (
                            <p className="text-xs mt-1" style={{ color: 'var(--color-text-muted)' }}>
                                Разрыв за пределами графика — переключись на «2-й доход»
                            </p>
                        )}
                    </div>
                </div>
            )}

            {/* Прогресс-бары план/факт */}
            {data.progressBars.length > 0 && (
                <div className="rounded-2xl p-5 space-y-4"
                    style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}>
                    <h3 className="font-semibold text-sm" style={{ color: 'var(--color-text-muted)' }}>
                        ПЛАН / ФАКТ ЗА МЕСЯЦ
                    </h3>
                    {data.progressBars.map(bar => {
                        const state = getBarState(bar.plannedLimit, bar.currentFact);
                        const barMax = getBarMax(bar.plannedLimit, bar.projectionAmount);
                        const factPct = barMax > 0 ? (bar.currentFact / barMax) * 100 : 0;
                        const planMarkerPct = barMax > 0 ? (bar.plannedLimit / barMax) * 100 : 100;
                        const needlePct = bar.forecastEnabled && bar.projectionAmount && barMax > 0
                            ? (bar.projectionAmount / barMax) * 100 : null;
                        const isOverspend = bar.projectionAmount != null && bar.projectionAmount > bar.plannedLimit;
                        const daysInMonth = new Date(new Date().getFullYear(), new Date().getMonth() + 1, 0).getDate();
                        return (
                            <div key={bar.categoryName} className="group relative">
                                <div className="flex justify-between gap-2 text-sm mb-1 min-w-0">
                                    <div className="flex items-center gap-1.5 min-w-0">
                                        <span className="truncate">{bar.categoryName}</span>
                                        {state.badge && (
                                            <span
                                                className="shrink-0 text-xs px-1.5 py-0 rounded-full"
                                                style={{
                                                    color: state.badgeColor,
                                                    background: `${state.badgeColor}22`,
                                                    fontSize: '10px',
                                                    lineHeight: '18px',
                                                    whiteSpace: 'nowrap',
                                                }}
                                            >
                                                {state.badge}
                                            </span>
                                        )}
                                    </div>
                                    <span className="shrink-0 text-xs text-muted-foreground">
                                        {fmt(bar.currentFact)} / {fmt(bar.plannedLimit)}
                                        {bar.forecastEnabled && bar.projectionAmount != null && (
                                            <span className={`ml-1 font-semibold ${isOverspend ? 'text-destructive' : 'text-green-400'}`}>
                                                / ~{fmt(bar.projectionAmount)}
                                            </span>
                                        )}
                                    </span>
                                </div>
                                <div className="relative h-2 rounded-full" style={{ background: 'var(--color-surface-2)' }}>
                                    {/* Fact fill — uses dynamic barMax */}
                                    <div className="h-2 rounded-full transition-all"
                                        style={{ width: `${Math.min(factPct, 100)}%`, background: state.barColor }} />
                                    {/* Plan marker — thin white vertical line */}
                                    <div
                                        className="absolute top-[-3px] h-[calc(100%+6px)] w-0.5 bg-white/20 rounded-sm z-10"
                                        style={{ left: `${planMarkerPct}%` }}
                                    />
                                    {/* Needle — projection end-of-month marker */}
                                    {needlePct != null && (
                                        <>
                                            <div
                                                className={`absolute top-[-5px] h-[calc(100%+10px)] w-0.5 rounded-sm z-20 ${isOverspend ? 'bg-destructive' : 'bg-green-400'}`}
                                                style={{ left: `${Math.min(needlePct, 98)}%` }}
                                            />
                                            <div
                                                className={`absolute top-[-3px] w-2 h-2 rounded-full border-2 border-background z-30 ${isOverspend ? 'bg-destructive' : 'bg-green-400'}`}
                                                style={{ left: `calc(${Math.min(needlePct, 98)}% - 4px)` }}
                                            />
                                        </>
                                    )}
                                </div>
                                {/* Transaction names under this category */}
                                {(() => {
                                    const names = monthEvents
                                        .filter(e => e.categoryName === bar.categoryName && e.description)
                                        .map(e => e.description as string)
                                        .filter((v, i, arr) => arr.indexOf(v) === i); // deduplicate
                                    if (names.length === 0) return null;
                                    const shown = names.slice(0, 5);
                                    const extra = names.length - shown.length;
                                    return (
                                        <p className="text-xs mt-0.5 truncate" style={{ color: 'var(--color-text-muted)' }}>
                                            {shown.join(' · ')}{extra > 0 ? ` · и ещё ${extra}` : ''}
                                        </p>
                                    );
                                })()}
                                {/* Hover sparkline tooltip */}
                                {bar.forecastEnabled && bar.history.length > 0 && (
                                    <div className="absolute right-0 top-6 hidden group-hover:block z-50">
                                        <ForecastSparkline
                                            history={bar.history}
                                            plannedLimit={bar.plannedLimit}
                                            projectionAmount={bar.projectionAmount ?? bar.plannedLimit}
                                            daysInMonth={daysInMonth}
                                        />
                                    </div>
                                )}
                            </div>
                        );
                    })}
                </div>
            )}

            {/* Сторож недоступен и данных нет — честная пометка вместо тишины */}
            {watchdogFailed && !watchdog && (
                <p className="text-xs text-center" style={{ color: 'var(--color-text-muted)' }}>
                    Не удалось проверить кассовый разрыв — обнови страницу
                </p>
            )}

            {data.progressBars.length === 0 && !watchdogAlert && todayEvents.length === 0 && (
                <div className="text-center py-8 text-sm" style={{ color: 'var(--color-text-muted)' }}>
                    Нет событий за текущий месяц. Добавь первую трату кнопкой «+»!
                </div>
            )}

            {/* Кассовый календарь — близнец кармашка: та же trajectory из GET /pocket,
                нарисованная графиком; горизонт = скоуп PocketCard (ANO-6, ANO-14) */}
            {pocket && (
                <PocketTrajectoryChart data={pocket} />
            )}
        </div>
        </div>
    );
}
