import { useEffect, useState } from 'react';
import { fetchMultiMonthReport } from '../api';
import type { MultiMonthReport, MultiMonthRow } from '../types/api';
import { ScrollArea } from '../components/ui/scroll-area';
import { Button } from '../components/ui/button';

const fmt = (n: number | null) =>
    n != null
        ? new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 0 }).format(n) + ' ₽'
        : '—';

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
    const [preset, setPreset] = useState<'3m' | '6m' | '12m'>('3m');
    const [report, setReport] = useState<MultiMonthReport | null>(null);
    const [loading, setLoading] = useState(true);

    useEffect(() => {
        const { startDate, endDate } = getDateRange(preset);
        setLoading(true);
        fetchMultiMonthReport(startDate, endDate)
            .then(setReport)
            .finally(() => setLoading(false));
    }, [preset]);

    return (
        <div className="flex flex-col h-[calc(100dvh-var(--nav-height))]">
            {/* Period selector */}
            <div className="flex gap-2 px-4 pt-4 pb-2 shrink-0">
                {(['3m', '6m', '12m'] as const).map(p => (
                    <Button
                        key={p}
                        size="sm"
                        variant={preset === p ? 'default' : 'secondary'}
                        onClick={() => setPreset(p)}
                    >
                        {p === '3m' ? '3 мес' : p === '6m' ? '6 мес' : '12 мес'}
                    </Button>
                ))}
            </div>

            {loading && (
                <p className="text-center py-10 text-sm text-muted-foreground animate-pulse">Загрузка...</p>
            )}

            {!loading && report && (
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
                                <div className={`font-medium ${actualColor}`}>{fmt(v.actual)}</div>
                                <div className="text-xs text-muted-foreground">{fmt(v.planned)}</div>
                            </div>
                        ) : (
                            <div className="text-muted-foreground">{fmt(v.planned)}</div>
                        )}
                    </td>
                );
            })}
        </tr>
    );
}
