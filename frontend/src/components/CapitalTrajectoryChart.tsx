import { useEffect, useState } from 'react';
import { LineChart, Line, XAxis, YAxis, Tooltip, ResponsiveContainer } from 'recharts';
import { fetchCapitalTrajectory } from '../api';
import type { CapitalTrajectory } from '../types/api';
import { Button } from './ui/button';

type Range = '6m' | '1y' | '3y' | 'all';
const labels: Record<Range, string> = { '6m': '6 мес.', '1y': '1 год', '3y': '3 года', 'all': 'Всё' };

const fmt = (n: number) =>
    new Intl.NumberFormat('ru-RU', { style: 'currency', currency: 'RUB', minimumFractionDigits: 0 }).format(n);

const fmtCompact = (n: number) => {
    if (Math.abs(n) >= 1_000_000) return (n / 1_000_000).toFixed(1) + 'М';
    if (Math.abs(n) >= 1_000) return Math.round(n / 1000) + 'к';
    return String(n);
};

interface Props {
    refreshSignal: number;
}

export default function CapitalTrajectoryChart({ refreshSignal }: Props) {
    const [range, setRange] = useState<Range>('1y');
    const [trajectory, setTrajectory] = useState<CapitalTrajectory | null>(null);

    useEffect(() => {
        const today = new Date();
        let from: string | undefined;
        if (range === '6m') from = isoMinusMonths(today, 6);
        else if (range === '1y') from = isoMinusMonths(today, 12);
        else if (range === '3y') from = isoMinusMonths(today, 36);
        // all: undefined → backend выберет earliest

        fetchCapitalTrajectory(from).then(setTrajectory).catch(console.error);
    }, [range, refreshSignal]);

    if (!trajectory) return null;
    // Считаем дельту от предыдущей точки — нужно в tooltip.
    const data = trajectory.points.map((p, idx, arr) => ({
        ...p,
        dateLabel: shortDate(p.date),
        deltaFromPrev: idx === 0 ? null : p.capital - arr[idx - 1].capital,
    }));

    return (
        <div className="rounded-lg p-4"
             style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}>
            <div className="flex items-center justify-between mb-3">
                <h3 className="text-sm font-medium">Динамика капитала</h3>
                <div className="flex gap-1">
                    {(Object.keys(labels) as Range[]).map(r => (
                        <Button key={r} size="sm" variant={range === r ? 'default' : 'ghost'} onClick={() => setRange(r)}>
                            {labels[r]}
                        </Button>
                    ))}
                </div>
            </div>

            {data.length <= 1 ? (
                <div className="py-8 text-center space-y-3">
                    <svg width="80" height="40" className="inline-block" viewBox="0 0 80 40">
                        <circle cx="70" cy="20" r="4" fill="var(--color-accent, #6c63ff)" />
                    </svg>
                    <div className="text-sm" style={{ color: 'var(--color-text-muted)' }}>
                        История появится по мере переоценок. Хочешь увидеть прошлое — добавь оценку задним числом из Sheet любого актива.
                    </div>
                </div>
            ) : (
                <ResponsiveContainer width="100%" height={220}>
                    <LineChart data={data}>
                        <XAxis dataKey="dateLabel" tick={{ fontSize: 11, fill: 'var(--color-text-muted)' }} />
                        <YAxis tickFormatter={fmtCompact} tick={{ fontSize: 11, fill: 'var(--color-text-muted)' }} />
                        <Tooltip content={<CustomTooltip />} />
                        <Line type="monotone" dataKey="capital" stroke="var(--color-accent, #6c63ff)" strokeWidth={2} dot={{ r: 3 }} />
                    </LineChart>
                </ResponsiveContainer>
            )}
        </div>
    );
}

interface TooltipProps {
    active?: boolean;
    payload?: Array<{ payload: { date: string; capital: number; liquid: number; assets: number; liabilities: number; deltaFromPrev: number | null } }>;
}

function CustomTooltip({ active, payload }: TooltipProps) {
    if (!active || !payload?.length) return null;
    const p = payload[0].payload;
    const deltaSign = p.deltaFromPrev != null && p.deltaFromPrev >= 0 ? '+' : '';
    return (
        <div className="rounded p-2 text-xs" style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}>
            <div className="font-medium">{shortDate(p.date)}</div>
            <div>{fmt(p.capital)}</div>
            <div className="opacity-70 mt-1">cash {fmtCompact(p.liquid)} + активы {fmtCompact(p.assets)} − долги {fmtCompact(p.liabilities)}</div>
            {p.deltaFromPrev != null && (
                <div className="mt-1" style={{ color: p.deltaFromPrev >= 0 ? 'var(--color-success, #7ec699)' : 'var(--color-danger, #e88a8a)' }}>
                    {deltaSign}{fmtCompact(p.deltaFromPrev)} от прошлой точки
                </div>
            )}
        </div>
    );
}

function shortDate(iso: string): string {
    return new Date(iso).toLocaleDateString('ru-RU', { day: 'numeric', month: 'short', year: '2-digit' });
}

function isoMinusMonths(d: Date, months: number): string {
    const r = new Date(d);
    r.setMonth(r.getMonth() - months);
    return r.toISOString().slice(0, 10);
}
