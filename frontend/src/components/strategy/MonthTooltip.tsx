import { useNavigate } from 'react-router-dom';
import type { StrategyTimelinePointDto } from '../../types/api';
import { fmtYearMonthFull, fmtRub, monthsBetween } from './strategyChartUtils';
import { Repeat } from 'lucide-react';

interface Props {
    active?: boolean;
    payload?: Array<{ payload: StrategyTimelinePointDto }>;
    currentMonth?: string;
}

export default function MonthTooltip({ active, payload, currentMonth }: Props) {
    const navigate = useNavigate();
    if (!active || !payload || payload.length === 0) return null;

    const p = payload[0].payload;
    const isFuture = p.phase === 'FUTURE';
    const isCurrent = p.phase === 'CURRENT';

    const monthLabel = fmtYearMonthFull(p.yearMonth);
    const subtitle = isFuture && currentMonth
        ? ` (через ${monthsBetween(currentMonth, p.yearMonth)} мес)`
        : isCurrent ? ' (сейчас)' : '';

    const goToBudget = () => navigate(`/budget?month=${p.yearMonth}`);

    return (
        <div
            className="rounded-lg p-3 max-w-xs text-xs"
            style={{
                background: 'var(--color-surface)',
                border: '1px solid var(--color-border)',
                boxShadow: '0 4px 16px rgba(0,0,0,0.4)',
            }}
        >
            <div className="text-[11px] uppercase tracking-wide mb-1.5" style={{ color: 'var(--color-text-muted)' }}>
                {monthLabel}{subtitle}
            </div>

            <div className="flex justify-between items-baseline mb-1">
                <span style={{ color: 'var(--color-text-muted)' }}>Баланс</span>
                <span className="font-semibold text-[14px]">{fmtRub(p.balance)}</span>
            </div>
            {isFuture && p.balanceLow !== null && p.balanceHigh !== null && p.balanceLow !== p.balanceHigh && (
                <div className="text-[10px] mb-2" style={{ color: 'var(--color-text-muted)' }}>
                    Диапазон: {fmtRub(p.balanceLow)} – {fmtRub(p.balanceHigh)}
                </div>
            )}

            {p.breakdown && (
                <>
                    {p.breakdown.incomeItems.length > 0 && (
                        <div className="rounded p-2 mb-1.5" style={{ background: 'rgba(34, 197, 94, 0.08)' }}>
                            <div className="text-[10px] uppercase mb-1" style={{ color: 'var(--color-text-muted)' }}>
                                Доход +{fmtRub(p.income)}
                            </div>
                            {p.breakdown.incomeItems.map(it => (
                                <div key={it.category} className="flex justify-between items-center">
                                    <span>{it.isRecurring && <Repeat size={10} className="inline mr-1" />}{it.category}</span>
                                    <span>+{fmtRub(it.amount)}</span>
                                </div>
                            ))}
                        </div>
                    )}
                    {p.breakdown.expenseItems.length > 0 && (
                        <div className="rounded p-2" style={{ background: 'rgba(239, 68, 68, 0.08)' }}>
                            <div className="text-[10px] uppercase mb-1" style={{ color: 'var(--color-text-muted)' }}>
                                Расход −{fmtRub(p.expense)}
                            </div>
                            {p.breakdown.expenseItems.map(it => (
                                <div key={it.category} className="flex justify-between items-center">
                                    <span>
                                        {it.isRecurring && <Repeat size={10} className="inline mr-1" />}
                                        {it.category}
                                        {it.isPredicted && <span style={{ color: 'var(--color-text-muted)' }}> (прогноз)</span>}
                                    </span>
                                    <span>{it.isPredicted ? '~' : ''}{fmtRub(it.amount)}</span>
                                </div>
                            ))}
                        </div>
                    )}
                </>
            )}

            <button
                type="button"
                onClick={goToBudget}
                className="mt-2 text-[11px] hover:underline"
                style={{ color: 'var(--color-primary)' }}
            >
                Открыть Budget этого месяца →
            </button>
        </div>
    );
}
