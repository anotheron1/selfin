import { useEffect, useState } from 'react';
import { fetchMultiMonthReport, fetchAnalyticsReport } from '../api';
import type { AnalyticsReport, MultiMonthReport, MultiMonthRow } from '../types/api';
import { ScrollArea } from '../components/ui/scroll-area';
import { Button } from '../components/ui/button';

type Preset = '1m' | '3m' | '6m' | '12m';

const fmt = (n: number) =>
    new Intl.NumberFormat('ru-RU', { style: 'currency', currency: 'RUB', minimumFractionDigits: 0 }).format(n);

const fmtAmt = (n: number | null) => n != null ? fmt(n) : '—';

const fmtTable = (n: number | null) =>
    n != null
        ? new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 0 }).format(n) + ' ₽'
        : '—';

const fmtDay = (dateStr: string) =>
    new Date(dateStr).toLocaleDateString('ru-RU', { day: 'numeric', month: 'short' });

function deltaColor(delta: number): string {
    return delta >= 0 ? 'var(--color-success)' : 'var(--color-danger)';
}

function getDateRange(preset: '3m' | '6m' | '12m'): { startDate: string; endDate: string } {
    const today = new Date();
    const endMonth = new Date(today.getFullYear(), today.getMonth(), 1);
    const months = preset === '3m' ? 3 : preset === '6m' ? 6 : 12;
    const startMonth = new Date(endMonth);
    startMonth.setMonth(startMonth.getMonth() - months + 1);
    const startDate = startMonth.toISOString().slice(0, 7) + '-01';
    const endDate = new Date(endMonth.getFullYear(), endMonth.getMonth() + 1, 0)
        .toISOString()
        .slice(0, 10);
    return { startDate, endDate };
}

export default function Analytics() {
    const [preset, setPreset] = useState<Preset>('1m');
    const [report, setReport] = useState<MultiMonthReport | null>(null);
    const [analytics, setAnalytics] = useState<AnalyticsReport | null>(null);
    const [loading, setLoading] = useState(true);

    useEffect(() => {
        setLoading(true);
        if (preset === '1m') {
            fetchAnalyticsReport()
                .then(setAnalytics)
                .finally(() => setLoading(false));
        } else {
            const { startDate, endDate } = getDateRange(preset);
            fetchMultiMonthReport(startDate, endDate)
                .then(setReport)
                .finally(() => setLoading(false));
        }
    }, [preset]);

    const presetLabels: Record<Preset, string> = {
        '1m': 'Месяц',
        '3m': '3 мес',
        '6m': '6 мес',
        '12m': '12 мес',
    };

    return (
        <div className="flex flex-col h-[calc(100dvh-var(--nav-height))]">
            {/* Period selector */}
            <div className="flex gap-2 px-4 pt-4 pb-2 shrink-0">
                {(['1m', '3m', '6m', '12m'] as const).map(p => (
                    <Button
                        key={p}
                        size="sm"
                        variant={preset === p ? 'default' : 'secondary'}
                        onClick={() => setPreset(p)}
                    >
                        {presetLabels[p]}
                    </Button>
                ))}
            </div>

            {loading && (
                <p className="text-center py-10 text-sm text-muted-foreground animate-pulse">Загрузка...</p>
            )}

            {/* Текущий месяц: детальные секции */}
            {!loading && preset === '1m' && analytics && (
                <ScrollArea className="flex-1">
                    <div className="px-4 pb-6 space-y-5">
                        <PlanFactSection planFact={analytics.planFact} />
                        <MandatoryBurnSection burn={analytics.mandatoryBurn} />
                        <IncomeGapSection gap={analytics.incomeGap} />
                    </div>
                </ScrollArea>
            )}

            {!loading && preset === '1m' && analytics && analytics.planFact.categories.length === 0 && (
                <p className="text-center py-10 text-sm" style={{ color: 'var(--color-text-muted)' }}>
                    Нет данных за текущий месяц.
                </p>
            )}

            {/* Многомесячная таблица */}
            {!loading && preset !== '1m' && report && (
                <ScrollArea className="flex-1">
                    <div className="overflow-x-auto">
                        <table className="w-full text-sm border-collapse min-w-max">
                            <thead>
                                <tr className="border-b border-border">
                                    <th className="sticky left-0 z-10 bg-background text-left px-4 py-2 font-medium text-muted-foreground min-w-[160px]">
                                        Статья
                                    </th>
                                    {report.months.map(m => (
                                        <th key={m} className="text-right px-3 py-2 font-medium text-muted-foreground min-w-[100px]">
                                            {new Date(m + '-01').toLocaleDateString('ru-RU', { month: 'short', year: '2-digit' })}
                                        </th>
                                    ))}
                                </tr>
                            </thead>
                            <tbody>
                                {report.rows.map((row, i) => (
                                    <AnalyticsRow key={i} row={row} months={report.months} />
                                ))}
                            </tbody>
                        </table>
                    </div>
                </ScrollArea>
            )}
        </div>
    );
}

// ─── Multi-month table row ────────────────────────────────────────────────────

function AnalyticsRow({ row, months }: { row: MultiMonthRow; months: string[] }) {
    const isTotal = row.type !== 'CATEGORY';
    const isBalance = row.type === 'BALANCE';
    const isIncome = row.type === 'TOTAL_INCOME' || row.categoryType === 'INCOME';

    const valueMap = new Map(row.values.map(v => [v.month, v]));

    return (
        <tr className={`border-b border-border/50 ${isTotal ? 'bg-card' : 'hover:bg-card/50'}`}>
            <td className={`sticky left-0 z-10 px-4 py-2 ${isBalance ? 'bg-secondary font-bold' : isTotal ? 'bg-card font-semibold' : 'bg-background'}`}>
                {!isTotal && <span className="text-muted-foreground mr-1">└</span>}
                {row.label}
            </td>
            {months.map(m => {
                const v = valueMap.get(m);
                if (!v) return <td key={m} className="text-right px-3 py-2 text-muted-foreground">—</td>;

                const isNegativeBalance = isBalance && v.actual != null && v.actual < 0;
                const actualColor = isBalance
                    ? (v.actual != null && v.actual >= 0 ? 'text-green-500' : 'text-destructive')
                    : isIncome ? 'text-green-500' : 'text-foreground';

                return (
                    <td key={m} className={`text-right px-3 py-2 ${isNegativeBalance ? 'bg-destructive/10' : ''}`}>
                        {v.actual != null ? (
                            <div>
                                <div className={`font-medium ${actualColor}`}>{fmtTable(v.actual)}</div>
                                <div className="text-xs text-muted-foreground">{fmtTable(v.planned)}</div>
                            </div>
                        ) : (
                            <div className="text-muted-foreground">{fmtTable(v.planned)}</div>
                        )}
                    </td>
                );
            })}
        </tr>
    );
}

// ─── Секция: Отчёт План-Факт ─────────────────────────────────────────────────

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
            <table className="w-full text-sm table-fixed">
                <thead>
                    <tr style={{ color: 'var(--color-text-muted)', fontSize: '11px' }}>
                        <th className="text-left pb-1 font-normal w-2/5">Категория</th>
                        <th className="text-right pb-1 font-normal">План</th>
                        <th className="text-right pb-1 font-normal">Факт</th>
                        <th className="text-right pb-1 font-normal">Δ</th>
                    </tr>
                </thead>
                <tbody>
                    {rows.map(row => (
                        <tr key={row.categoryName}
                            style={{ borderTop: '1px solid var(--color-border)' }}>
                            <td className="py-1.5 max-w-0">
                                <span className="block truncate">{row.categoryName}</span>
                            </td>
                            <td className="py-1.5 text-right" style={{ color: 'var(--color-text-muted)' }}>{fmt(row.planned)}</td>
                            <td className="py-1.5 text-right">{fmt(row.fact)}</td>
                            <td className="py-1.5 text-right font-medium"
                                style={{ color: deltaColor(row.delta) }}>
                                {row.delta >= 0 ? '+' : ''}{fmt(row.delta)}
                            </td>
                        </tr>
                    ))}
                    <tr style={{ borderTop: '2px solid var(--color-border)', fontWeight: 600 }}>
                        <td className="py-1.5 max-w-0">Итого</td>
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

// ─── Секция: Burn rate обязательных трат ─────────────────────────────────────

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

            <div className="mb-1 flex justify-between text-sm">
                <span>Всего</span>
                <span style={{ color: 'var(--color-text-muted)' }}>{fmt(burn.totalFact)} / {fmt(burn.totalPlanned)}</span>
            </div>
            <div className="h-3 rounded-full mb-4" style={{ background: 'var(--color-surface-2)' }}>
                <div className="h-3 rounded-full transition-all"
                    style={{ width: `${totalPct}%`, background: totalColor }} />
            </div>

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

// ─── Секция: Дефицит доходов ──────────────────────────────────────────────────

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
                <span style={{ color: 'var(--color-text-muted)' }}>{fmtAmt(gap.factIncome)} / {fmtAmt(gap.plannedIncome)}</span>
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
