import { useEffect, useState } from 'react';
import {
    ResponsiveContainer,
    LineChart,
    Line,
    XAxis,
    YAxis,
    Tooltip,
    Legend,
    ReferenceLine,
    Label,
} from 'recharts';
import { cn } from '@/lib/utils';
import { fetchPlannerData, updateFund } from '../../api/index';
import type { TargetFund, FundPlannerData, FundPlannerMonth } from '../../types/api';
import { Badge } from '../ui/badge';
import { Button } from '../ui/button';
import { Input } from '../ui/input';
import {
    calcPMT,
    fmtYearMonth,
    buildChartData,
    rebalancePercents,
    maxPercent,
} from './savingsStrategyUtils';

// ─── Helpers ──────────────────────────────────────────────────────────────────

const fmtRub = (n: number) =>
    new Intl.NumberFormat('ru-RU', { style: 'currency', currency: 'RUB', minimumFractionDigits: 0 }).format(n);

function avgFirstMonths(months: FundPlannerMonth[], count: number): number {
    const slice = months.slice(0, count);
    if (slice.length === 0) return 0;
    return slice.reduce((sum, m) => sum + m.plannedIncome, 0) / slice.length;
}

// ─── Props ────────────────────────────────────────────────────────────────────

interface Props {
    funds: TargetFund[];
    onFundUpdated: () => void;
}

// ─── Credit params sub-component ─────────────────────────────────────────────

interface CreditParamsProps {
    fund: TargetFund;
    monthlyContribution: number;
    onSaved: () => void;
}

function CreditParams({ fund, monthlyContribution, onSaved }: CreditParamsProps) {
    const [rate, setRate] = useState(fund.creditRate != null ? String(fund.creditRate) : '');
    const [term, setTerm] = useState(fund.creditTermMonths != null ? String(fund.creditTermMonths) : '');
    const [saving, setSaving] = useState(false);
    const [error, setError] = useState<string | null>(null);

    const handleSave = async () => {
        setSaving(true);
        setError(null);
        try {
            await updateFund(fund.id, {
                name: fund.name,
                creditRate: rate ? Number(rate) : undefined,
                creditTermMonths: term ? Number(term) : undefined,
            });
            onSaved();
        } catch {
            setError('Ошибка сохранения');
        } finally {
            setSaving(false);
        }
    };

    const rateNum = rate ? Number(rate) : null;
    const termNum = term ? Number(term) : null;
    const principal = fund.targetAmount;

    let indicator: React.ReactNode = null;
    if (rateNum != null && termNum != null && principal != null && principal > 0) {
        const pmt = calcPMT(principal, rateNum, termNum);
        const canAfford = monthlyContribution >= pmt;
        indicator = canAfford ? (
            <span className="text-xs font-medium text-green-500">потяну</span>
        ) : (
            <span className="text-xs font-medium text-red-500">
                не хватает {fmtRub(pmt - monthlyContribution)}
            </span>
        );
    } else if (!rateNum || !termNum) {
        indicator = (
            <span className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
                Укажите параметры кредита
            </span>
        );
    }

    return (
        <div className="space-y-2">
            <div className="flex items-center gap-2">
                <div className="flex-1">
                    <label className="text-xs" style={{ color: 'var(--color-text-muted)' }}>Ставка, %</label>
                    <Input
                        type="number"
                        min="0.01"
                        max="99.99"
                        step="0.01"
                        placeholder="напр. 18.5"
                        value={rate}
                        onChange={e => setRate(e.target.value)}
                        className="h-8 text-sm"
                    />
                </div>
                <div className="flex-1">
                    <label className="text-xs" style={{ color: 'var(--color-text-muted)' }}>Срок, мес.</label>
                    <Input
                        type="number"
                        min="1"
                        max="360"
                        step="1"
                        placeholder="напр. 36"
                        value={term}
                        onChange={e => setTerm(e.target.value)}
                        className="h-8 text-sm"
                    />
                </div>
                <div className="self-end">
                    <Button
                        size="sm"
                        onClick={handleSave}
                        disabled={saving}
                        className="h-8">
                        {saving ? '...' : 'Сохранить'}
                    </Button>
                </div>
            </div>
            {error && <p className="text-xs text-destructive mt-1">{error}</p>}
            {indicator && <div>{indicator}</div>}
        </div>
    );
}

// ─── Main component ───────────────────────────────────────────────────────────

export default function SavingsStrategySection({ funds, onFundUpdated }: Props) {
    const [isOpen, setIsOpen] = useState(false);
    const [plannerData, setPlannerData] = useState<FundPlannerData | null>(null);
    const [fundPercents, setFundPercents] = useState<Record<string, number>>({});

    // Initialize percents for each fund (0 by default)
    const fundIdKey = funds.map(f => f.id).join(',');

    useEffect(() => {
        setFundPercents(prev => {
            const next: Record<string, number> = {};
            for (const f of funds) {
                next[f.id] = prev[f.id] ?? 0;
            }
            return next;
        });
    }, [fundIdKey]); // eslint-disable-line react-hooks/exhaustive-deps

    // Load planner data when section is first opened
    useEffect(() => {
        if (isOpen && plannerData === null) {
            fetchPlannerData().then(setPlannerData).catch(() => {/* ignore */});
        }
    }, [isOpen, plannerData]);

    const avgIncome = plannerData ? avgFirstMonths(plannerData.months, 3) : 0;
    const avgMandatory = plannerData
        ? Math.round(plannerData.months.slice(0, 3).reduce((s, m) => s + (m.mandatoryExpenses ?? 0), 0) / Math.min(3, plannerData.months.length))
        : 0;
    const avgAll = plannerData
        ? Math.round(plannerData.months.slice(0, 3).reduce((s, m) => s + (m.allPlannedExpenses ?? 0), 0) / Math.min(3, plannerData.months.length))
        : 0;
    const remainingAfterMandatory = Math.max(0, avgIncome - avgMandatory);
    const remainingAfterAll = Math.max(0, avgIncome - avgAll);
    const totalPercent = Object.values(fundPercents).reduce((s, v) => s + v, 0);
    const totalMonthly = Math.round(avgIncome * totalPercent / 100);
    const cap = avgIncome > 0
        ? Math.max(1, Math.round((remainingAfterAll / avgIncome) * 100))
        : 50;

    // Chart data
    const { chartData, completionLabels } = plannerData
        ? buildChartData(plannerData.months, funds, fundPercents)
        : { chartData: [], completionLabels: [] };

    const tickFormatter = (v: number) => Math.abs(v) >= 1000 ? Math.round(v / 1000) + 'к' : String(v);

    return (
        <div
            className="rounded-2xl overflow-hidden"
            style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}>
            {/* Header / toggle */}
            <button
                onClick={() => setIsOpen(o => !o)}
                className="w-full flex items-center justify-between px-5 py-4 text-left"
                aria-expanded={isOpen}>
                <span className="font-semibold">Планировщик копилок</span>
                <span
                    className={cn('transition-transform duration-200 text-lg leading-none', isOpen && 'rotate-180')}
                    style={{ color: 'var(--color-text-muted)' }}>
                    ▾
                </span>
            </button>

            {isOpen && (
                <div className="px-5 pb-5 space-y-4">
                    {/* Summary bar */}
                    {plannerData && (
                        <div
                            className="flex flex-wrap gap-x-4 gap-y-1 text-sm rounded-xl px-4 py-2.5"
                            style={{ background: 'var(--color-bg)', border: '1px solid var(--color-border)' }}>
                            <span style={{ color: 'var(--color-text-muted)' }}>
                                Плановый доход:{' '}
                                <span className="font-medium" style={{ color: 'var(--color-text)' }}>
                                    ~{avgIncome.toLocaleString()} ₽/мес
                                </span>
                            </span>
                            <span style={{ color: 'var(--color-text-muted)' }}>|</span>
                            <span style={{ color: 'var(--color-text-muted)' }}>
                                Остаток (обяз.):{' '}
                                <span className="font-medium" style={{ color: 'var(--color-text)' }}>
                                    ~{remainingAfterMandatory.toLocaleString()} ₽/мес
                                </span>
                            </span>
                            <span style={{ color: 'var(--color-text-muted)' }}>|</span>
                            <span style={{ color: 'var(--color-text-muted)' }}>
                                Остаток (все расходы):{' '}
                                <span className="font-medium" style={{ color: 'var(--color-text)' }}>
                                    ~{remainingAfterAll.toLocaleString()} ₽/мес
                                </span>
                            </span>
                            <span style={{ color: 'var(--color-text-muted)' }}>|</span>
                            <span style={{ color: 'var(--color-text-muted)' }}>
                                Распределено:{' '}
                                <span className="font-medium" style={{ color: totalPercent > cap ? '#f97316' : 'var(--color-text)' }}>
                                    {fmtRub(totalMonthly)}/мес ({Math.round(totalPercent)}% свободных)
                                </span>
                            </span>
                        </div>
                    )}

                    {/* No planner data yet */}
                    {!plannerData && (
                        <div
                            className="text-sm text-center py-4 animate-pulse"
                            style={{ color: 'var(--color-text-muted)' }}>
                            Загрузка данных...
                        </div>
                    )}

                    {/* Fund cards */}
                    {funds.length === 0 && (
                        <p className="text-sm text-center py-2" style={{ color: 'var(--color-text-muted)' }}>
                            Нет активных копилок
                        </p>
                    )}

                    {funds.map(fund => {
                        const percent = fundPercents[fund.id] ?? 0;
                        const monthly = Math.round(avgIncome * percent / 100);
                        const isCredit = fund.purchaseType === 'CREDIT';

                        let projection: React.ReactNode = null;
                        if (!isCredit) {
                            if (fund.targetAmount != null && monthly > 0) {
                                const months = Math.ceil(fund.targetAmount / monthly);
                                projection = (
                                    <span className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
                                        {fmtRub(monthly)}/мес → достигну через {months} мес
                                    </span>
                                );
                            } else if (fund.targetAmount != null && monthly === 0) {
                                projection = (
                                    <span className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
                                        {fmtRub(monthly)}/мес
                                    </span>
                                );
                            } else {
                                projection = (
                                    <span className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
                                        Цель не задана
                                    </span>
                                );
                            }
                        }

                        return (
                            <div
                                key={fund.id}
                                className="rounded-xl p-4 space-y-3"
                                style={{ background: 'var(--color-bg)', border: '1px solid var(--color-border)' }}>
                                {/* Name + badge */}
                                <div className="flex items-center gap-2">
                                    <span className="font-medium text-sm">{fund.name}</span>
                                    {isCredit ? (
                                        <Badge
                                            className="border-orange-500/60 text-orange-400 bg-orange-500/10"
                                            variant="outline">
                                            Кредит
                                        </Badge>
                                    ) : (
                                        <Badge variant="secondary">Накопление</Badge>
                                    )}
                                </div>

                                {/* Slider */}
                                <div className="space-y-1">
                                    <div className="flex items-center justify-between text-xs">
                                        <span style={{ color: 'var(--color-text-muted)' }}>% дохода</span>
                                        <span className="font-medium">
                                            {percent}% = {fmtRub(monthly)}/мес
                                        </span>
                                    </div>
                                    <input
                                        type="range"
                                        min={0}
                                        max={maxPercent(fund.targetAmount, fund.purchaseType, avgIncome, cap)}
                                        step={1}
                                        value={percent}
                                        onChange={e =>
                                            setFundPercents(prev =>
                                                rebalancePercents(fund.id, Number(e.target.value), prev, cap)
                                            )
                                        }
                                        className="w-full h-2 rounded-full cursor-pointer accent-[hsl(var(--primary))]"
                                    />
                                </div>

                                {/* Projection or credit params */}
                                {!isCredit && projection}
                                {isCredit && (
                                    <CreditParams
                                        key={`${fund.id}-${fund.creditRate ?? 'null'}-${fund.creditTermMonths ?? 'null'}`}
                                        fund={fund}
                                        monthlyContribution={monthly}
                                        onSaved={onFundUpdated}
                                    />
                                )}
                            </div>
                        );
                    })}

                    {/* Chart */}
                    {plannerData && plannerData.months.length > 0 && (
                        <div className="pt-2">
                            <ResponsiveContainer width="100%" height={300}>
                                <LineChart data={chartData} margin={{ top: 4, right: 8, left: 0, bottom: 0 }}>
                                    <XAxis
                                        dataKey="label"
                                        tick={{ fontSize: 11 }}
                                        tickLine={false}
                                        axisLine={false}
                                    />
                                    <YAxis
                                        tickFormatter={tickFormatter}
                                        tick={{ fontSize: 11 }}
                                        tickLine={false}
                                        axisLine={false}
                                        width={40}
                                    />
                                    <Tooltip
                                        formatter={(value, name) => [typeof value === 'number' ? fmtRub(value) : String(value), name]}
                                        contentStyle={{
                                            background: 'var(--color-surface)',
                                            border: '1px solid var(--color-border)',
                                            borderRadius: 8,
                                            fontSize: 12,
                                        }}
                                    />
                                    <Legend wrapperStyle={{ fontSize: 12 }} />
                                    <Line
                                        type="monotone"
                                        dataKey="Доход"
                                        stroke="#22c55e"
                                        dot={false}
                                        strokeWidth={2}
                                    />
                                    <Line
                                        type="monotone"
                                        dataKey="Обяз. расходы"
                                        stroke="#f97316"
                                        dot={false}
                                        strokeWidth={2}
                                        strokeDasharray="5 5"
                                    />
                                    <Line
                                        type="monotone"
                                        dataKey="Все расходы"
                                        stroke="#ef4444"
                                        dot={false}
                                        strokeWidth={2}
                                        strokeDasharray="5 5"
                                    />
                                    <Line
                                        type="monotone"
                                        dataKey="Расходы + копилки"
                                        stroke="hsl(var(--primary))"
                                        dot={false}
                                        strokeWidth={2}
                                    />
                                    {completionLabels.map(({ label, name }) => (
                                        <ReferenceLine
                                            key={`${label}-${name}`}
                                            x={label}
                                            stroke="var(--color-border)"
                                            strokeDasharray="4 4">
                                            <Label
                                                value={name}
                                                position="insideTopRight"
                                                style={{ fontSize: 10, fill: 'var(--color-text-muted)' }}
                                            />
                                        </ReferenceLine>
                                    ))}
                                </LineChart>
                            </ResponsiveContainer>
                        </div>
                    )}
                </div>
            )}
        </div>
    );
}
