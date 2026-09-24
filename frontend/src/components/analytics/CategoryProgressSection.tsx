import { useEffect, useState } from 'react';
import { fetchDashboard, fetchEvents } from '../../api';
import type { DashboardData, DailyForecastPoint, FinancialEvent } from '../../types/api';
import { fmtRub } from '../../lib/format';
import { PLAN_FACT_PALETTE, barView } from '../../lib/planFactBars';

const fmt = fmtRub;

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
                <line x1={0} y1={planY} x2={W} y2={planY}
                    stroke={PLAN_FACT_PALETTE.plan} strokeWidth={1} strokeDasharray="4 3" />
                <polyline points={factPoints} fill="none" stroke={PLAN_FACT_PALETTE.fact} strokeWidth={2} strokeLinejoin="round" />
                <polyline points={projPoints} fill="none" stroke={PLAN_FACT_PALETTE.forecast} strokeWidth={1.5}
                    strokeDasharray="3 3" strokeLinejoin="round" />
                <line x1={todayX} y1={y(today.cumulativeFact)} x2={futureEndX} y2={futureEndY}
                    stroke="#8a8aa0" strokeWidth={1.5} strokeDasharray="4 4" opacity={0.8} />
                <line x1={todayX} y1={0} x2={todayX} y2={H}
                    stroke="rgba(108,99,255,0.4)" strokeWidth={1} strokeDasharray="2 2" />
            </svg>
            <div className="flex justify-between text-[10px] text-muted-foreground mt-1">
                <span>1</span><span>{daysInMonth}</span>
            </div>
            <div className="flex gap-3 mt-1.5 flex-wrap">
                {[
                    { color: PLAN_FACT_PALETTE.fact, label: 'факт', dashed: false },
                    { color: PLAN_FACT_PALETTE.plan, label: 'план', dashed: true },
                    { color: PLAN_FACT_PALETTE.forecast, label: 'прогноз по дням', dashed: true },
                    { color: '#8a8aa0', label: 'прогноз вперёд', dashed: true },
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

/**
 * «По категориям за месяц» (ANO-119) — план, факт и прогноз конца месяца полосками.
 *
 * <p>Переехало сюда с дашборда: там это был отчёт отклонений в состоянии по умолчанию,
 * то есть прямое нарушение запрета 2. На «Аналитику» заходят намеренно, и раздел
 * раскрывается действием — это и есть «по явному запросу».
 *
 * <p>Оценки при переезде сняты: ни подписей-приговоров, ни красной заливки, ни жёлтой
 * пометки за незаведённый план (правила 12 и 5). Осталось то, ради чего блок и нужен
 * на разборе: сколько план, сколько факт, куда идёт дело к концу месяца.
 *
 * <p>ANO-172: при переезде факт залили несуществующей CSS-переменной, и полоса не рисовалась
 * вовсе. Теперь цвет каждой метки говорит, что это за метка, и не меняется от того, как она
 * стоит относительно плана, — правило и палитра в lib/planFactBars.
 *
 * <p>Данные грузятся при монтировании, а монтируется компонент только раскрытым —
 * свёрнутый раздел не стоит ни одного запроса.
 */
export default function CategoryProgressSection() {
    const [data, setData] = useState<DashboardData | null>(null);
    const [monthEvents, setMonthEvents] = useState<FinancialEvent[]>([]);

    useEffect(() => {
        const today = new Date();
        const ld = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
        const monthStart = ld(new Date(today.getFullYear(), today.getMonth(), 1));
        const monthEnd = ld(new Date(today.getFullYear(), today.getMonth() + 1, 0));
        fetchDashboard().then(setData).catch(() => setData({ progressBars: [] }));
        fetchEvents(monthStart, monthEnd).then(setMonthEvents).catch(() => setMonthEvents([]));
    }, []);

    if (!data || data.progressBars.length === 0) return null;

    const daysInMonth = new Date(new Date().getFullYear(), new Date().getMonth() + 1, 0).getDate();

    return (
        <div className="rounded-2xl p-5 space-y-4"
            style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}>
            <h3 className="font-semibold text-sm" style={{ color: 'var(--color-text-muted)' }}>
                ПО КАТЕГОРИЯМ ЗА МЕСЯЦ
            </h3>
            {data.progressBars.map(bar => {
                const view = barView(bar);
                return (
                    <div key={bar.categoryName} className="group relative">
                        <div className="flex justify-between gap-2 text-sm mb-1 min-w-0">
                            <span className="truncate">{bar.categoryName}</span>
                            {/* Числа — в цветах своих меток: сразу видно, какая засечка чья. */}
                            <span className="shrink-0 text-xs text-muted-foreground">
                                <span style={{ color: view.colors.fact }}>{fmt(bar.currentFact)}</span>
                                {' / '}
                                <span style={{ color: view.colors.plan }}>{fmt(bar.plannedLimit)}</span>
                                {bar.forecastEnabled && bar.projectionAmount != null && (
                                    <>
                                        {' / '}
                                        <span style={{ color: view.colors.forecast }}>~{fmt(bar.projectionAmount)}</span>
                                    </>
                                )}
                            </span>
                        </div>
                        <div className="relative h-2 rounded-full" style={{ background: PLAN_FACT_PALETTE.track }}>
                            <div className="h-2 rounded-full transition-all"
                                style={{ width: `${view.factPct}%`, background: view.colors.fact }} />
                            <div className="absolute top-[-4px] h-[calc(100%+8px)] w-0.5 rounded-sm z-10"
                                style={{ left: `${view.planPct}%`, background: view.colors.plan }} />
                            {view.forecastPct != null && (
                                <>
                                    <div className="absolute top-[-5px] h-[calc(100%+10px)] w-0.5 rounded-sm z-20"
                                        style={{ left: `${view.forecastPct}%`, background: view.colors.forecast }} />
                                    <div className="absolute top-[-6px] w-2 h-2 rounded-full z-30"
                                        style={{
                                            left: `calc(${view.forecastPct}% - 3px)`,
                                            background: view.colors.forecast,
                                            boxShadow: '0 0 0 2px var(--color-surface)',
                                        }} />
                                </>
                            )}
                        </div>
                        {(() => {
                            const names = monthEvents
                                .filter(e => e.categoryName === bar.categoryName && e.description)
                                .map(e => e.description as string)
                                .filter((v, i, arr) => arr.indexOf(v) === i);
                            if (names.length === 0) return null;
                            const shown = names.slice(0, 5);
                            const extra = names.length - shown.length;
                            return (
                                <p className="text-xs mt-0.5 truncate" style={{ color: 'var(--color-text-muted)' }}>
                                    {shown.join(' · ')}{extra > 0 ? ` · и ещё ${extra}` : ''}
                                </p>
                            );
                        })()}
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
    );
}
