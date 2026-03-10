import { useEffect, useState } from 'react';
import { fetchDashboard, fetchAnalyticsReport, fetchEvents } from '../api';
import type { AnalyticsReport, DashboardData, FinancialEvent } from '../types/api';
import { AlertTriangle, TrendingDown, TrendingUp } from 'lucide-react';
import { Badge } from '../components/ui/badge';
import { ScrollArea } from '../components/ui/scroll-area';

const fmt = (n: number) =>
    new Intl.NumberFormat('ru-RU', { style: 'currency', currency: 'RUB', minimumFractionDigits: 0 }).format(n);

const fmtAmt = (n: number | null) => n != null ? fmt(n) : '—';

const fmtDay = (dateStr: string) =>
    new Date(dateStr).toLocaleDateString('ru-RU', { day: 'numeric', month: 'short' });

/** Цвет дельты: зелёный если ≥ 0, красный если < 0. */
function deltaColor(delta: number): string {
    return delta >= 0 ? 'var(--color-success)' : 'var(--color-danger)';
}

export default function Dashboard() {
    const todayStr = new Date().toISOString().slice(0, 10);

    const [data, setData] = useState<DashboardData | null>(null);
    const [analytics, setAnalytics] = useState<AnalyticsReport | null>(null);
    const [todayEvents, setTodayEvents] = useState<FinancialEvent[]>([]);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        Promise.all([fetchDashboard(), fetchAnalyticsReport(), fetchEvents(todayStr, todayStr)])
            .then(([dash, rep, evts]) => {
                setData(dash);
                setAnalytics(rep);
                setTodayEvents(evts);
            })
            .catch(e => setError(e.message));
    }, []);

    if (error) return (
        <div className="p-6 text-center" style={{ color: 'var(--color-danger)' }}>Ошибка: {error}</div>
    );

    if (!data) return (
        <div className="p-6 text-center animate-pulse" style={{ color: 'var(--color-text-muted)' }}>Загрузка...</div>
    );

    const balancePositive = data.currentBalance >= 0;

    // Прогноз конца дня: currentBalance + плановые суммы ещё не исполненных событий сегодня
    const unexecutedToday = todayEvents.filter(e => e.status === 'PLANNED');
    const endOfDayForecast = unexecutedToday.reduce((bal, e) => {
        const amt = e.plannedAmount ?? 0;
        return e.type === 'INCOME' ? bal + amt : bal - amt;
    }, data.currentBalance);
    const hasUnexecutedToday = unexecutedToday.length > 0;

    const incomeToday = todayEvents.filter(e => e.type === 'INCOME');
    const expenseToday = todayEvents.filter(e => e.type === 'EXPENSE' || e.type === 'FUND_TRANSFER');

    return (
        <ScrollArea className="h-[calc(100dvh-var(--nav-height))]">
        <div className="pl-4 pr-5 py-6 space-y-5">
            {/* Hero: Текущий баланс + события сегодня */}
            <div className="rounded-2xl p-5 space-y-3"
                style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}>

                {/* Главное число */}
                <div className="text-center space-y-1">
                    <p className="text-sm" style={{ color: 'var(--color-text-muted)' }}>Текущий баланс</p>
                    <p className="text-5xl font-bold tracking-tight"
                        style={{ color: balancePositive ? 'var(--color-success)' : 'var(--color-danger)' }}>
                        {fmt(data.currentBalance)}
                    </p>
                    <div className="flex items-center justify-center gap-2 text-sm"
                        style={{ color: 'var(--color-text-muted)' }}>
                        {data.endOfMonthForecast >= 0 ? <TrendingUp size={14} /> : <TrendingDown size={14} />}
                        <span>Прогноз конец месяца:{' '}
                            <b style={{ color: data.endOfMonthForecast >= 0 ? 'var(--color-success)' : 'var(--color-danger)' }}>
                                {fmt(data.endOfMonthForecast)}
                            </b>
                        </span>
                    </div>
                </div>

                {/* События сегодня */}
                {todayEvents.length > 0 && (
                    <div className="pt-2 border-t space-y-2" style={{ borderColor: 'var(--color-border)' }}>
                        <p className="text-xs font-semibold uppercase" style={{ color: 'var(--color-text-muted)' }}>
                            Сегодня
                        </p>

                        {/* Доходы */}
                        {incomeToday.map(e => (
                            <div key={e.id} className="flex items-center justify-between gap-2 text-sm">
                                <div className="flex items-center gap-2 min-w-0">
                                    <span className="truncate">{e.categoryName}</span>
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
                                            : e.categoryName}
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
                        {hasUnexecutedToday && (
                            <div className="flex justify-between items-center pt-2 border-t text-sm font-semibold"
                                style={{ borderColor: 'var(--color-border)' }}>
                                <span style={{ color: 'var(--color-text-muted)' }}>Прогноз конца дня</span>
                                <span style={{ color: endOfDayForecast >= 0 ? 'var(--color-success)' : 'var(--color-danger)' }}>
                                    {fmt(endOfDayForecast)}
                                </span>
                            </div>
                        )}
                    </div>
                )}
            </div>

            {/* Алерт кассового разрыва */}
            {data.cashGapAlert && (
                <div className="rounded-xl p-4 flex gap-3 items-start"
                    style={{ background: 'rgba(239,68,68,0.12)', border: '1px solid var(--color-danger)' }}>
                    <AlertTriangle size={18} style={{ color: 'var(--color-danger)', flexShrink: 0, marginTop: 2 }} />
                    <div>
                        <p className="font-semibold text-sm" style={{ color: 'var(--color-danger)' }}>Кассовый разрыв!</p>
                        <p className="text-sm" style={{ color: 'var(--color-text)' }}>
                            {new Date(data.cashGapAlert.gapDate).toLocaleDateString('ru-RU', { day: 'numeric', month: 'long' })} — ожидается дефицит{' '}
                            <b>{fmt(data.cashGapAlert.gapAmount)}</b>
                        </p>
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
                        const pct = Math.min(bar.percentage, 100);
                        const color = pct < 70 ? 'var(--color-success)' : pct < 90 ? 'var(--color-warning)' : 'var(--color-danger)';
                        return (
                            <div key={bar.categoryName}>
                                <div className="flex justify-between text-sm mb-1">
                                    <span>{bar.categoryName}</span>
                                    <span style={{ color: 'var(--color-text-muted)' }}>
                                        {fmt(bar.currentFact)} / {fmt(bar.plannedLimit)}
                                    </span>
                                </div>
                                <div className="h-2 rounded-full" style={{ background: 'var(--color-surface-2)' }}>
                                    <div className="h-2 rounded-full transition-all"
                                        style={{ width: `${pct}%`, background: color }} />
                                </div>
                            </div>
                        );
                    })}
                </div>
            )}

            {data.progressBars.length === 0 && !data.cashGapAlert && todayEvents.length === 0 && (
                <div className="text-center py-8 text-sm" style={{ color: 'var(--color-text-muted)' }}>
                    Нет событий за текущий месяц. Добавь первую трату кнопкой «+»!
                </div>
            )}

            {/* ── Аналитика ─────────────────────────────── */}
            {analytics && (
                <>
                    {/* Секция 1: Кассовый календарь (только будущие дни) */}
                    <CashFlowSection cashFlow={analytics.cashFlow} />

                    {/* Секция 2: Отчёт План-Факт */}
                    <PlanFactSection planFact={analytics.planFact} />

                    {/* Секция 3: Burn rate обязательных трат */}
                    <MandatoryBurnSection burn={analytics.mandatoryBurn} />

                    {/* Секция 4: Дефицит доходов */}
                    <IncomeGapSection gap={analytics.incomeGap} />
                </>
            )}
        </div>
        </ScrollArea>
    );
}

// ─── Секция 1: Кассовый календарь (только будущие дни) ──────────────────────

function CashFlowSection({ cashFlow }: { cashFlow: AnalyticsReport['cashFlow'] }) {
    // Показываем только будущие дни (не сегодня, не прошлое)
    const futureDays = cashFlow.filter(d => d.isFuture);
    if (futureDays.length === 0) return null;

    return (
        <div className="rounded-2xl p-5"
            style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}>
            <h3 className="font-semibold text-sm mb-3" style={{ color: 'var(--color-text-muted)' }}>
                КАССОВЫЙ КАЛЕНДАРЬ
            </h3>
            <div className="flex gap-2 overflow-x-auto pb-2" style={{ scrollbarWidth: 'thin' }}>
                {futureDays.map(day => {
                    const balanceColor = day.runningBalance < 0 ? 'var(--color-danger)' : 'var(--color-success)';
                    return (
                        <div key={day.date}
                            className="flex-none rounded-xl p-2 text-center"
                            style={{
                                minWidth: '64px',
                                background: day.isGap
                                    ? 'rgba(239,68,68,0.12)'
                                    : 'var(--color-surface-2)',
                                border: day.isGap
                                    ? '1px solid var(--color-danger)'
                                    : '1px solid var(--color-border)',
                            }}>
                            <p className="text-xs mb-1" style={{ color: 'var(--color-text-muted)' }}>
                                {fmtDay(day.date)}
                            </p>
                            <p className="text-xs font-bold" style={{ color: balanceColor }}>
                                {fmt(day.runningBalance)}
                            </p>
                            {(day.dailyIncome > 0 || day.dailyExpense > 0) && (
                                <p className="mt-1" style={{ color: 'var(--color-text-muted)', fontSize: '10px' }}>
                                    {day.dailyIncome > 0 && <span style={{ color: 'var(--color-success)' }}>+{fmt(day.dailyIncome)}</span>}
                                    {day.dailyExpense > 0 && <span style={{ color: 'var(--color-danger)' }}>{' '}−{fmt(day.dailyExpense)}</span>}
                                </p>
                            )}
                        </div>
                    );
                })}
            </div>
        </div>
    );
}

// ─── Секция 2: Отчёт План-Факт ──────────────────────────────────────────────

function PlanFactSection({ planFact }: { planFact: AnalyticsReport['planFact'] }) {
    const incomeRows = planFact.categories.filter(c => c.type === 'INCOME');
    const expenseRows = planFact.categories.filter(c => c.type === 'EXPENSE');

    return (
        <div className="rounded-2xl p-5"
            style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}>
            <h3 className="font-semibold text-sm mb-3" style={{ color: 'var(--color-text-muted)' }}>
                ОТЧЁТ ПЛАН-ФАКТ
            </h3>

            {incomeRows.length > 0 && (
                <PlanFactGroup label="Доходы" rows={incomeRows}
                    totalPlanned={planFact.totalPlannedIncome}
                    totalFact={planFact.totalFactIncome} />
            )}

            {expenseRows.length > 0 && (
                <PlanFactGroup label="Расходы" rows={expenseRows}
                    totalPlanned={planFact.totalPlannedExpense}
                    totalFact={planFact.totalFactExpense}
                    mt={incomeRows.length > 0} />
            )}

            {planFact.categories.length === 0 && (
                <p className="text-sm text-center py-2" style={{ color: 'var(--color-text-muted)' }}>Нет данных</p>
            )}
        </div>
    );
}

function PlanFactGroup({ label, rows, totalPlanned, totalFact, mt }: {
    label: string;
    rows: AnalyticsReport['planFact']['categories'];
    totalPlanned: number;
    totalFact: number;
    mt?: boolean;
}) {
    const totalDelta = totalFact - totalPlanned;
    return (
        <div className={mt ? 'mt-4' : ''}>
            <p className="text-xs font-semibold mb-2" style={{ color: 'var(--color-text-muted)' }}>
                {label.toUpperCase()}
            </p>
            <table className="w-full text-sm">
                <thead>
                    <tr style={{ color: 'var(--color-text-muted)', fontSize: '11px' }}>
                        <th className="text-left pb-1 font-normal">Категория</th>
                        <th className="text-right pb-1 font-normal">План</th>
                        <th className="text-right pb-1 font-normal">Факт</th>
                        <th className="text-right pb-1 font-normal">Δ</th>
                    </tr>
                </thead>
                <tbody>
                    {rows.map(row => (
                        <tr key={row.categoryName}
                            style={{ borderTop: '1px solid var(--color-border)' }}>
                            <td className="py-1.5">{row.categoryName}</td>
                            <td className="py-1.5 text-right" style={{ color: 'var(--color-text-muted)' }}>{fmt(row.planned)}</td>
                            <td className="py-1.5 text-right">{fmt(row.fact)}</td>
                            <td className="py-1.5 text-right font-medium"
                                style={{ color: deltaColor(row.delta) }}>
                                {row.delta >= 0 ? '+' : ''}{fmt(row.delta)}
                            </td>
                        </tr>
                    ))}
                    {/* Итого */}
                    <tr style={{ borderTop: '2px solid var(--color-border)', fontWeight: 600 }}>
                        <td className="py-1.5">Итого</td>
                        <td className="py-1.5 text-right" style={{ color: 'var(--color-text-muted)' }}>{fmt(totalPlanned)}</td>
                        <td className="py-1.5 text-right">{fmt(totalFact)}</td>
                        <td className="py-1.5 text-right"
                            style={{ color: deltaColor(totalDelta) }}>
                            {totalDelta >= 0 ? '+' : ''}{fmt(totalDelta)}
                        </td>
                    </tr>
                </tbody>
            </table>
        </div>
    );
}

// ─── Секция 3: Burn rate обязательных трат ───────────────────────────────────

function MandatoryBurnSection({ burn }: { burn: AnalyticsReport['mandatoryBurn'] }) {
    if (burn.totalPlanned === 0 && burn.totalFact === 0) return null;

    const totalPct = burn.totalPlanned > 0
        ? Math.min(Math.round((burn.totalFact / burn.totalPlanned) * 100), 100)
        : 0;
    const totalColor = totalPct < 70 ? 'var(--color-success)' : totalPct < 90 ? 'var(--color-warning)' : 'var(--color-danger)';

    return (
        <div className="rounded-2xl p-5"
            style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}>
            <h3 className="font-semibold text-sm mb-3" style={{ color: 'var(--color-text-muted)' }}>
                ОБЯЗАТЕЛЬНЫЕ ТРАТЫ
            </h3>

            {/* Общий прогресс-бар */}
            <div className="mb-1 flex justify-between text-sm">
                <span>Всего</span>
                <span style={{ color: 'var(--color-text-muted)' }}>{fmt(burn.totalFact)} / {fmt(burn.totalPlanned)}</span>
            </div>
            <div className="h-3 rounded-full mb-4" style={{ background: 'var(--color-surface-2)' }}>
                <div className="h-3 rounded-full transition-all"
                    style={{ width: `${totalPct}%`, background: totalColor }} />
            </div>

            {/* По неделям */}
            <div className="space-y-2">
                {burn.byWeek.map(week => {
                    const wPct = week.planned > 0
                        ? Math.min(Math.round((week.fact / week.planned) * 100), 100)
                        : 0;
                    const wColor = wPct < 70 ? 'var(--color-success)' : wPct < 90 ? 'var(--color-warning)' : 'var(--color-danger)';
                    return (
                        <div key={week.weekNumber}>
                            <div className="flex justify-between text-xs mb-1"
                                style={{ color: 'var(--color-text-muted)' }}>
                                <span>Неделя {week.weekNumber}: {fmtDay(week.weekStart)} – {fmtDay(week.weekEnd)}</span>
                                <span>{fmt(week.fact)} / {fmt(week.planned)}</span>
                            </div>
                            <div className="h-1.5 rounded-full" style={{ background: 'var(--color-surface-2)' }}>
                                <div className="h-1.5 rounded-full transition-all"
                                    style={{ width: `${wPct}%`, background: wColor }} />
                            </div>
                        </div>
                    );
                })}
            </div>
        </div>
    );
}

// ─── Секция 4: Дефицит доходов ───────────────────────────────────────────────

function IncomeGapSection({ gap }: { gap: AnalyticsReport['incomeGap'] }) {
    if (gap.plannedIncome === 0 && gap.factIncome === 0) return null;

    const pct = gap.plannedIncome > 0
        ? Math.min(Math.round((gap.factIncome / gap.plannedIncome) * 100), 100)
        : 0;
    const barColor = pct >= 100 ? 'var(--color-success)' : pct >= 70 ? 'var(--color-warning)' : 'var(--color-danger)';

    return (
        <div className="rounded-2xl p-5"
            style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}>
            <h3 className="font-semibold text-sm mb-3" style={{ color: 'var(--color-text-muted)' }}>
                ДОХОДЫ
            </h3>
            <div className="flex justify-between text-sm mb-1">
                <span>Факт / План</span>
                <span style={{ color: 'var(--color-text-muted)' }}>{fmt(gap.factIncome)} / {fmt(gap.plannedIncome)}</span>
            </div>
            <div className="h-3 rounded-full mb-3" style={{ background: 'var(--color-surface-2)' }}>
                <div className="h-3 rounded-full transition-all"
                    style={{ width: `${pct}%`, background: barColor }} />
            </div>
            <div className="flex justify-between text-sm">
                <span style={{ color: 'var(--color-text-muted)' }}>Дельта</span>
                <span className="font-semibold" style={{ color: deltaColor(gap.delta) }}>
                    {gap.delta >= 0 ? '+' : ''}{fmt(gap.delta)}
                </span>
            </div>
            {gap.delta < 0 && (
                <p className="text-xs mt-2" style={{ color: 'var(--color-text-muted)' }}>
                    Недобор доходов — {Math.abs(pct - 100)}% от плана ещё не получено
                </p>
            )}
        </div>
    );
}
