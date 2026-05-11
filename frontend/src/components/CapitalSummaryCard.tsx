import { useState } from 'react';
import { TrendingUp, TrendingDown } from 'lucide-react';
import type { CapitalSummary } from '../types/api';
import { Button } from './ui/button';

const fmt = (n: number) =>
    new Intl.NumberFormat('ru-RU', { style: 'currency', currency: 'RUB', minimumFractionDigits: 0 }).format(n);

const fmtDelta = (n: number) => {
    const sign = n >= 0 ? '+' : '';
    return `${sign}${fmt(n)}`;
};

const fmtPercent = (delta: number, base: number) => {
    if (base === 0) return '';
    const pct = (delta / base) * 100;
    const sign = pct >= 0 ? '+' : '';
    return `(${sign}${pct.toFixed(1)}%)`;
};

type Period = 'month' | 'quarter' | 'year';
const periodLabels: Record<Period, string> = {
    month: 'Месяц',
    quarter: 'Квартал',
    year: 'Год',
};

interface Props {
    summary: CapitalSummary;
}

export default function CapitalSummaryCard({ summary }: Props) {
    const [period, setPeriod] = useState<Period>('month');
    const delta = summary.deltas[period];
    const baseForPercent = summary.total - delta;

    return (
        <div className="rounded-lg p-5"
             style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}>
            <div className="text-xs uppercase tracking-wide" style={{ color: 'var(--color-text-muted)' }}>
                Капитал на сегодня
            </div>
            <div className="text-3xl font-semibold mt-1">{fmt(summary.total)}</div>

            <div className="flex gap-2 mt-3">
                {(Object.keys(periodLabels) as Period[]).map(p => (
                    <Button
                        key={p}
                        size="sm"
                        variant={period === p ? 'default' : 'outline'}
                        onClick={() => setPeriod(p)}>
                        {periodLabels[p]}
                    </Button>
                ))}
            </div>

            <div className="mt-2 flex items-center gap-1 text-sm"
                 style={{ color: delta >= 0 ? 'var(--color-success, #7ec699)' : 'var(--color-danger, #e88a8a)' }}>
                {delta >= 0 ? <TrendingUp size={16} /> : <TrendingDown size={16} />}
                {fmtDelta(delta)} {fmtPercent(delta, baseForPercent)}
            </div>
        </div>
    );
}
