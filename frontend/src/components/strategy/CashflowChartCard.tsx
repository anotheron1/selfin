import { useState } from 'react';
import type { StrategyTimelineDto } from '../../types/api';
import ChartLegendToggles from './ChartLegendToggles';
import CashflowChart from './CashflowChart';

interface Props {
    timeline: StrategyTimelineDto;
}

export default function CashflowChartCard({ timeline }: Props) {
    const [showFan, setShowFan] = useState(true);
    const [showNettoBars, setShowNettoBars] = useState(true);
    const [showConfirmed, setShowConfirmed] = useState(false);

    return (
        <div className="rounded-lg p-3.5"
             style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}>
            <ChartLegendToggles
                title="Денежный поток"
                toggles={[
                    { id: 'balance', label: 'Баланс', color: '#6c63ff', shape: 'dot', active: true },
                    { id: 'fan', label: 'Диапазон', color: '#6c63ff', shape: 'dot',
                      active: showFan && timeline.fanEnabled,
                      onToggle: timeline.fanEnabled ? () => setShowFan(v => !v) : undefined },
                    { id: 'netto', label: 'Нетто-потоки', color: '#22c55e', shape: 'bar',
                      active: showNettoBars, onToggle: () => setShowNettoBars(v => !v) },
                    { id: 'confirmed', label: 'Только подтверждённое', color: '#9da9b8', shape: 'dash',
                      active: showConfirmed, onToggle: () => setShowConfirmed(v => !v) },
                ]}
            />
            {!timeline.fanEnabled && (
                <div className="text-[11px] mb-2 px-2 py-1 rounded inline-block"
                     style={{ background: 'rgba(251, 191, 36, 0.1)', color: 'var(--color-text-muted)' }}>
                    Прогноз пока недоступен — нужно ≥3 месяца истории по 3+ категориям
                </div>
            )}
            <CashflowChart
                points={timeline.points}
                currentMonth={timeline.currentMonth}
                showFan={showFan && timeline.fanEnabled}
                showNettoBars={showNettoBars}
                showConfirmed={showConfirmed}
            />
        </div>
    );
}
