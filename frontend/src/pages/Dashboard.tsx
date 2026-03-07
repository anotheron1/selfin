import { useEffect, useState } from 'react';
import { fetchDashboard } from '../api';
import type { DashboardData } from '../types/api';
import { AlertTriangle, TrendingDown, TrendingUp } from 'lucide-react';

const fmt = (n: number) =>
    new Intl.NumberFormat('ru-RU', { style: 'currency', currency: 'RUB', minimumFractionDigits: 0 }).format(n);

export default function Dashboard() {
    const [data, setData] = useState<DashboardData | null>(null);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        fetchDashboard()
            .then(setData)
            .catch(e => setError(e.message));
    }, []);

    if (error) return (
        <div className="p-6 text-center" style={{ color: 'var(--color-danger)' }}>Ошибка: {error}</div>
    );

    if (!data) return (
        <div className="p-6 text-center animate-pulse" style={{ color: 'var(--color-text-muted)' }}>Загрузка...</div>
    );

    const balancePositive = data.currentBalance >= 0;

    return (
        <div className="px-4 py-6 space-y-5">
            {/* Hero: Текущий баланс */}
            <div className="rounded-2xl p-6 text-center space-y-2"
                style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}>
                <p className="text-sm" style={{ color: 'var(--color-text-muted)' }}>Баланс на сегодня</p>
                <p className="text-5xl font-bold tracking-tight"
                    style={{ color: balancePositive ? 'var(--color-success)' : 'var(--color-danger)' }}>
                    {fmt(data.currentBalance)}
                </p>
                <div className="flex items-center justify-center gap-2 text-sm"
                    style={{ color: 'var(--color-text-muted)' }}>
                    {data.endOfMonthForecast >= 0 ? <TrendingUp size={14} /> : <TrendingDown size={14} />}
                    <span>Прогноз на конец месяца: <b style={{ color: data.endOfMonthForecast >= 0 ? 'var(--color-success)' : 'var(--color-danger)' }}>{fmt(data.endOfMonthForecast)}</b></span>
                </div>
            </div>

            {/* Алерт кассового разрыва */}
            {data.cashGapAlert && (
                <div className="rounded-xl p-4 flex gap-3 items-start"
                    style={{ background: 'rgba(239,68,68,0.12)', border: '1px solid var(--color-danger)' }}>
                    <AlertTriangle size={18} style={{ color: 'var(--color-danger)', flexShrink: 0, marginTop: 2 }} />
                    <div>
                        <p className="font-semibold text-sm" style={{ color: 'var(--color-danger)' }}>Кассовый разрыв!</p>
                        <p className="text-sm" style={{ color: 'var(--color-text)' }}>
                            {new Date(data.cashGapAlert.gapDate).toLocaleDateString('ru-RU', { day: 'numeric', month: 'long' })} — ожидается дефицит{' '}
                            <b>{fmt(data.cashGapAlert.gapAmount)}</b>
                        </p>
                    </div>
                </div>
            )}

            {/* Прогресс-бары план/факт */}
            {data.progressBars.length > 0 && (
                <div className="rounded-2xl p-5 space-y-4"
                    style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}>
                    <h3 className="font-semibold text-sm" style={{ color: 'var(--color-text-muted)' }}>
                        ПЛАН / ФАКТ ЗА МЕСЯЦ
                    </h3>
                    {data.progressBars.map(bar => {
                        const pct = Math.min(bar.percentage, 100);
                        const color = pct < 70 ? 'var(--color-success)' : pct < 90 ? 'var(--color-warning)' : 'var(--color-danger)';
                        return (
                            <div key={bar.categoryName}>
                                <div className="flex justify-between text-sm mb-1">
                                    <span>{bar.categoryName}</span>
                                    <span style={{ color: 'var(--color-text-muted)' }}>
                                        {fmt(bar.currentFact)} / {fmt(bar.plannedLimit)}
                                    </span>
                                </div>
                                <div className="h-2 rounded-full" style={{ background: 'var(--color-surface-2)' }}>
                                    <div className="h-2 rounded-full transition-all"
                                        style={{ width: `${pct}%`, background: color }} />
                                </div>
                            </div>
                        );
                    })}
                </div>
            )}

            {data.progressBars.length === 0 && !data.cashGapAlert && (
                <div className="text-center py-8 text-sm" style={{ color: 'var(--color-text-muted)' }}>
                    Нет событий за текущий месяц. Добавь первую трату кнопкой «+»!
                </div>
            )}
        </div>
    );
}
