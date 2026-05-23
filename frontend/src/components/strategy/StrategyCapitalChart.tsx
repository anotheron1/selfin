import { LineChart, Line, XAxis, YAxis, Tooltip, ReferenceLine, ResponsiveContainer } from 'recharts';
import type { StrategyTimelinePointDto } from '../../types/api';
import { toChartPoints, fmtCompact, fmtYearMonthLabel } from './strategyChartUtils';
import MonthTooltip from './MonthTooltip';

interface Props {
    points: StrategyTimelinePointDto[];
    currentMonth: string;
    showAssets: boolean;
    showLiabilities: boolean;
}

export default function StrategyCapitalChart({ points, currentMonth, showAssets, showLiabilities }: Props) {
    const data = toChartPoints(points);
    const currentLabel = fmtYearMonthLabel(currentMonth);

    return (
        <ResponsiveContainer width="100%" height={150}>
            <LineChart data={data} margin={{ top: 8, right: 12, left: 0, bottom: 0 }} syncId="strategyTimeline">
                <XAxis dataKey="label" tick={{ fontSize: 11 }} tickLine={false} axisLine={false} />
                <YAxis tickFormatter={fmtCompact} tick={{ fontSize: 11 }} tickLine={false} axisLine={false} width={40} />
                <Tooltip content={(props: any) => <MonthTooltip {...props} currentMonth={currentMonth} />} />
                <ReferenceLine x={currentLabel} stroke="#fbbf24" strokeDasharray="3 3" />

                <Line type="monotone" dataKey="capital" stroke="#22c55e" strokeWidth={2} dot={false} isAnimationActive={false} />
                {showAssets && (
                    <Line type="monotone" dataKey="assets" stroke="rgba(255,255,255,0.3)" strokeWidth={1} dot={false} isAnimationActive={false} />
                )}
                {showLiabilities && (
                    <Line type="monotone" dataKey="liabilities" stroke="rgba(255,255,255,0.3)" strokeWidth={1} strokeDasharray="2 2" dot={false} isAnimationActive={false} />
                )}
            </LineChart>
        </ResponsiveContainer>
    );
}
