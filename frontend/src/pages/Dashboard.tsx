import { useEffect, useState } from 'react';
import { fetchDashboard, fetchAnalyticsReport, fetchEvents } from '../api';
import type { AnalyticsReport, DashboardData, FinancialEvent } from '../types/api';
import { AlertTriangle, TrendingDown, TrendingUp } from 'lucide-react';
import { Badge } from '../components/ui/badge';

const fmt = (n: number) =>
    new Intl.NumberFormat('ru-RU', { style: 'currency', currency: 'RUB', minimumFractionDigits: 0 }).format(n);

const fmtAmt = (n: number | null) => n != null ? fmt(n) : '—';

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

const fmtDay = (dateStr: string) =>
    new Date(dateStr).toLocaleDateString('ru-RU', { day: 'numeric', month: 'short' });

/**
 * Строка прогноза в зарплатном горизонте: иконка тренда + метка + сумма.
 * @param label  текст метки («До зп (28 мар)» / «После зп (28 мар)»)
 * @param value  прогнозная сумма (null — не отображается)
 * @param dimmed уменьшенная яркость для «вспомогательных» строк (стартовый капитал)
 */
function SalaryRow({ label, value, dimmed = false }: {
    label: string;
    value: number | null | undefined;
    dimmed?: boolean;
}) {
    if (value == null) return null;
    const positive = value >= 0;
    return (
        <div className="flex items-center justify-center gap-2 text-sm"
            style={{ color: dimmed ? 'var(--color-text-muted)' : 'var(--color-text-muted)', opacity: dimmed ? 0.65 : 1 }}>
            {positive ? <TrendingUp size={14} /> : <TrendingDown size={14} />}
            <span>
                {label}:{' '}
                <b style={{ color: positive ? 'var(--color-success)' : 'var(--color-danger)' }}>
                    {fmt(value)}
                </b>
            </span>
        </div>
    );
}

export default function Dashboard() {
    const _today = new Date();
    const todayStr = `${_today.getFullYear()}-${String(_today.getMonth() + 1).padStart(2, '0')}-${String(_today.getDate()).padStart(2, '0')}`;

    const [data, setData] = useState<DashboardData | null>(null);
    const [analytics, setAnalytics] = useState<AnalyticsReport | null>(null);
    const [todayEvents, setTodayEvents] = useState<FinancialEvent[]>([]);
    const [monthEvents, setMonthEvents] = useState<FinancialEvent[]>([]);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        const today = new Date();
        const ld = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
        const monthStart = ld(new Date(today.getFullYear(), today.getMonth(), 1));
        const monthEnd = ld(new Date(today.getFullYear(), today.getMonth() + 1, 0));

        Promise.all([
            fetchDashboard(),
            fetchAnalyticsReport(),
            fetchEvents(todayStr, todayStr),
            fetchEvents(monthStart, monthEnd),
        ])
            .then(([dash, rep, evts, mEvts]) => {
                setData(dash);
                setAnalytics(rep);
                setTodayEvents(evts);
                setMonthEvents(mEvts);
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
        <div className="overflow-y-auto overflow-x-hidden scrollbar-none" style={{ height: 'calc(100dvh - var(--nav-height))' }}>
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
                    {/* Зарплатные горизонты: до/после/до-второй или fallback конец месяца */}
                    {data.nextSalaryDate ? (
                        <div className="space-y-1 pt-1">
                            {/* Строка 1: низшая точка перед первой зп */}
                            <SalaryRow
                                label={`До зп (${fmtDay(data.nextSalaryDate)})`}
                                value={data.balanceBeforeNextSalary}
                            />
                            {/* Строка 2: стартовый капитал после первой зп */}
                            <SalaryRow
                                label={`После зп (${fmtDay(data.nextSalaryDate)})`}
                                value={data.balanceAfterNextSalary}
                                dimmed
                            />
                            {/* Строка 3: низшая точка перед второй зп (если есть) */}
                            {data.secondSalaryDate && (
                                <SalaryRow
                                    label={`До зп (${fmtDay(data.secondSalaryDate)})`}
                                    value={data.balanceBeforeSecondSalary}
                                />
                            )}
                        </div>
                    ) : (
                        <div className="flex items-center justify-center gap-2 text-sm"
                            style={{ color: 'var(--color-text-muted)' }}>
                            {data.endOfMonthForecast >= 0 ? <TrendingUp size={14} /> : <TrendingDown size={14} />}
                            <span>Прогноз конец месяца:{' '}
                                <b style={{ color: data.endOfMonthForecast >= 0 ? 'var(--color-success)' : 'var(--color-danger)' }}>
                                    {fmt(data.endOfMonthForecast)}
                                </b>
                            </span>
                        </div>
                    )}
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
                        const state = getBarState(bar.plannedLimit, bar.currentFact);
                        return (
                            <div key={bar.categoryName}>
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
                                    <span className="shrink-0" style={{ color: 'var(--color-text-muted)' }}>
                                        {fmt(bar.plannedLimit)} / {fmt(bar.currentFact)}
                                    </span>
                                </div>
                                <div className="h-2 rounded-full" style={{ background: 'var(--color-surface-2)' }}>
                                    <div className="h-2 rounded-full transition-all"
                                        style={{ width: state.barWidth, background: state.barColor }} />
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

            {/* Кассовый календарь */}
            {analytics && (
                <CashFlowSection cashFlow={analytics.cashFlow} />
            )}
        </div>
        </div>
    );
}

// ─── Кассовый календарь (только будущие дни) ─────────────────────────────────

function CashFlowSection({ cashFlow }: { cashFlow: AnalyticsReport['cashFlow'] }) {
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
