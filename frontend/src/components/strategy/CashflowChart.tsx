import {
    ComposedChart, Line, Bar, Area, XAxis, YAxis, Tooltip, ReferenceLine, ResponsiveContainer, Cell,
} from 'recharts';
import type { StrategyTimelinePointDto } from '../../types/api';
import { toChartPoints, fmtCompact, fmtYearMonthLabel } from './strategyChartUtils';
import MonthTooltip from './MonthTooltip';

interface Props {
    points: StrategyTimelinePointDto[];
    currentMonth: string;
    showFan: boolean;
    showNettoBars: boolean;
    showConfirmed: boolean;
}

export default function CashflowChart({ points, currentMonth, showFan, showNettoBars, showConfirmed }: Props) {
    const data = toChartPoints(points);
    const currentLabel = fmtYearMonthLabel(currentMonth);   // должен совпадать с dataKey="label"

    return (
        <ResponsiveContainer width="100%" height={240}>
            <ComposedChart data={data} margin={{ top: 8, right: 12, left: 0, bottom: 0 }} syncId="strategyTimeline">
                <XAxis
                    dataKey="label"
                    tick={{ fontSize: 11 }}
                    tickLine={false}
                    axisLine={false}
                />
                <YAxis
                    tickFormatter={fmtCompact}
                    tick={{ fontSize: 11 }}
                    tickLine={false}
                    axisLine={false}
                    width={40}
                />
                {/* Render-prop форма: Recharts передаёт active/payload/label, мы добавляем currentMonth */}
                <Tooltip content={(props: any) => <MonthTooltip {...props} currentMonth={currentMonth} />} />
                <ReferenceLine x={currentLabel} stroke="#fbbf24" strokeDasharray="3 3" />

                {showFan && (
                    <Area
                        type="monotone"
                        dataKey="balanceRange"
                        stroke="none"
                        fill="rgba(108, 99, 255, 0.12)"
                        isAnimationActive={false}
                    />
                )}

                {showNettoBars && (
                    <Bar dataKey="nettoFlow" maxBarSize={20}>
                        {data.map((d, i) => (
                            <Cell key={i} fill={d.nettoFlow >= 0 ? 'rgba(34, 197, 94, 0.4)' : 'rgba(239, 68, 68, 0.4)'} />
                        ))}
                    </Bar>
                )}

                {showConfirmed && (
                    <Line
                        type="monotone"
                        dataKey="balanceConfirmed"
                        stroke="#9da9b8"
                        strokeWidth={1.5}
                        strokeDasharray="4 3"
                        dot={false}
                        isAnimationActive={false}
                    />
                )}

                <Line
                    type="monotone"
                    dataKey="balance"
                    stroke="#6c63ff"
                    strokeWidth={2}
                    dot={false}
                    isAnimationActive={false}
                />
            </ComposedChart>
        </ResponsiveContainer>
    );
}
