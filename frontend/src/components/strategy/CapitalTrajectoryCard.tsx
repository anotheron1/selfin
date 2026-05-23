import { useState } from 'react';
import type { StrategyTimelineDto } from '../../types/api';
import ChartLegendToggles from './ChartLegendToggles';
import StrategyCapitalChart from './StrategyCapitalChart';

interface Props {
    timeline: StrategyTimelineDto;
}

export default function CapitalTrajectoryCard({ timeline }: Props) {
    const [showAssets, setShowAssets] = useState(false);
    const [showLiabilities, setShowLiabilities] = useState(false);

    return (
        <div className="rounded-lg p-3.5"
             style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}>
            <ChartLegendToggles
                title="Капитал"
                toggles={[
                    { id: 'capital', label: 'Чистый', color: '#22c55e', shape: 'dot', active: true },
                    { id: 'assets', label: 'Активы', color: 'rgba(255,255,255,0.5)', shape: 'dot',
                      active: showAssets, onToggle: () => setShowAssets(v => !v) },
                    { id: 'liabilities', label: 'Обязательства', color: 'rgba(255,255,255,0.5)', shape: 'dash',
                      active: showLiabilities, onToggle: () => setShowLiabilities(v => !v) },
                ]}
            />
            <StrategyCapitalChart
                points={timeline.points}
                currentMonth={timeline.currentMonth}
                showAssets={showAssets}
                showLiabilities={showLiabilities}
            />
        </div>
    );
}
