import type { StrategyTimelinePointDto } from '../../types/api';

/** "2026-03" → "мар 26" */
export function fmtYearMonthLabel(ym: string): string {
    const [year, month] = ym.split('-');
    const d = new Date(Number(year), Number(month) - 1, 1);
    const shortMonth = d.toLocaleDateString('ru-RU', { month: 'short' }).replace(/\./g, '');
    return `${shortMonth} ${String(year).slice(2)}`;
}

/** Месячное имя + год для tooltip ("Сентябрь 2026") */
export function fmtYearMonthFull(ym: string): string {
    const [year, month] = ym.split('-');
    const d = new Date(Number(year), Number(month) - 1, 1);
    const fullMonth = d.toLocaleDateString('ru-RU', { month: 'long' });
    return `${fullMonth.charAt(0).toUpperCase()}${fullMonth.slice(1)} ${year}`;
}

/** N месяцев между from и to (>=0). Для подписи "через N мес" в tooltip */
export function monthsBetween(fromYm: string, toYm: string): number {
    const [fy, fm] = fromYm.split('-').map(Number);
    const [ty, tm] = toYm.split('-').map(Number);
    return Math.max(0, (ty - fy) * 12 + (tm - fm));
}

/** Маппинг DTO в chart-friendly формат: добавляем балансReange для Recharts Area */
export interface ChartPoint extends StrategyTimelinePointDto {
    label: string;
    balanceRange: [number, number] | null;
}

export function toChartPoints(points: StrategyTimelinePointDto[]): ChartPoint[] {
    return points.map(p => ({
        ...p,
        label: fmtYearMonthLabel(p.yearMonth),
        balanceRange: (p.balanceLow !== null && p.balanceHigh !== null && p.balanceLow !== p.balanceHigh)
            ? [p.balanceLow, p.balanceHigh]
            : null,
    }));
}

/** Currency formatting */
export const fmtRub = (n: number) =>
    new Intl.NumberFormat('ru-RU', { style: 'currency', currency: 'RUB', minimumFractionDigits: 0 }).format(n);

/** Compact Y-axis ticks (350к, 5М) */
export const fmtCompact = (n: number) => {
    if (Math.abs(n) >= 1_000_000) return (n / 1_000_000).toFixed(1).replace(/\.0$/, '') + 'М';
    if (Math.abs(n) >= 1_000) return Math.round(n / 1000) + 'к';
    return String(n);
};
