import {
    ComposedChart, Line, XAxis, YAxis, Tooltip, ReferenceArea, ReferenceLine, ResponsiveContainer,
} from 'recharts';
import type { RiskLevel } from './wishlistUtils';
import { fmtCompact, fmtRub, fmtYearMonthLabel, fmtYearMonthFull } from '../strategy/strategyChartUtils';

interface Props {
    /** Подписи месяцев в формате YYYY-MM (future-сегмент). */
    months: string[];
    /** Баланс счёта по месяцам (composed). */
    account: number[];
    /** Капитал по месяцам (composed). */
    capital: number[];
    /** Уровень риска для фоновой заливки каждого месяца. */
    zones: RiskLevel[];
    /** Порог капитала; null — линия не рисуется. */
    capitalThreshold: number | null;
}

const ZONE_FILL: Record<RiskLevel, string> = {
    green: '#10b981',
    yellow: '#f59e0b',
    red: '#ef4444',
};

interface Row {
    label: string;
    ym: string;
    account: number;
    capital: number;
}

/** Минимальный tooltip: месяц + счёт + капитал. */
function ChartTooltip({ active, payload }: any) {
    if (!active || !payload || payload.length === 0) return null;
    const row: Row = payload[0].payload;
    return (
        <div
            className="rounded-lg px-3 py-2 text-xs"
            style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}>
            <div className="font-medium mb-1">{fmtYearMonthFull(row.ym)}</div>
            <div style={{ color: '#6c63ff' }}>Счёт: {fmtRub(row.account)}</div>
            <div style={{ color: '#22c55e' }}>Капитал: {fmtRub(row.capital)}</div>
        </div>
    );
}

/**
 * График влияния хотелок: баланс счёта (левая ось) + капитал (правая ось) на горизонте,
 * с фоновой заливкой каждого месяца по уровню риска и горизонтальной линией порога капитала.
 */
export default function WishlistImpactChart({ months, account, capital, zones, capitalThreshold }: Props) {
    const data: Row[] = months.map((ym, i) => ({
        label: fmtYearMonthLabel(ym),
        ym,
        account: account[i] ?? 0,
        capital: capital[i] ?? 0,
    }));

    return (
        <ResponsiveContainer width="100%" height={280}>
            <ComposedChart data={data} margin={{ top: 8, right: 8, left: 0, bottom: 0 }}>
                {/* Фоновая заливка по зонам риска — по одной полосе на месяц, ~10% непрозрачности. */}
                {data.map((row, i) => {
                    const zone = zones[i];
                    if (!zone) return null;
                    return (
                        <ReferenceArea
                            key={`zone-${i}`}
                            x1={row.label}
                            x2={row.label}
                            yAxisId="account"
                            fill={ZONE_FILL[zone]}
                            fillOpacity={0.1}
                            stroke="none"
                            ifOverflow="extendDomain"
                        />
                    );
                })}

                <XAxis
                    dataKey="label"
                    tick={{ fontSize: 11 }}
                    tickLine={false}
                    axisLine={false}
                />
                <YAxis
                    yAxisId="account"
                    tickFormatter={fmtCompact}
                    tick={{ fontSize: 11 }}
                    tickLine={false}
                    axisLine={false}
                    width={40}
                />
                <YAxis
                    yAxisId="capital"
                    orientation="right"
                    tickFormatter={fmtCompact}
                    tick={{ fontSize: 11 }}
                    tickLine={false}
                    axisLine={false}
                    width={40}
                />

                <Tooltip content={<ChartTooltip />} />

                {capitalThreshold != null && (
                    <ReferenceLine
                        y={capitalThreshold}
                        yAxisId="capital"
                        stroke="#ef4444"
                        strokeDasharray="4 3"
                    />
                )}

                <Line
                    yAxisId="account"
                    type="monotone"
                    dataKey="account"
                    stroke="#6c63ff"
                    strokeWidth={2}
                    dot={false}
                    isAnimationActive={false}
                />
                <Line
                    yAxisId="capital"
                    type="monotone"
                    dataKey="capital"
                    stroke="#22c55e"
                    strokeWidth={2}
                    strokeDasharray="5 4"
                    dot={false}
                    isAnimationActive={false}
                />
            </ComposedChart>
        </ResponsiveContainer>
    );
}
