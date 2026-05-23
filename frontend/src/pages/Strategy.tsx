import { useStrategyTimeline } from '../components/strategy/useStrategyTimeline';
import CashflowChartCard from '../components/strategy/CashflowChartCard';
import CapitalTrajectoryCard from '../components/strategy/CapitalTrajectoryCard';
import { Button } from '../components/ui/button';
import { fmtYearMonthFull } from '../components/strategy/strategyChartUtils';

export default function Strategy() {
    const { data: timeline, isLoading, error, refetch } = useStrategyTimeline();

    return (
        <div className="px-3 pt-3 pb-6 space-y-3">
            <div>
                <h1 className="text-xl font-semibold">Стратегия</h1>
                {timeline && (
                    <p className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
                        Финансовая траектория: {fmtYearMonthFull(timeline.firstActivityMonth)} → +3 года вперёд
                    </p>
                )}
            </div>

            {isLoading && (
                <>
                    <div className="rounded-lg h-[280px] animate-pulse"
                         style={{ background: 'var(--color-surface)' }} />
                    <div className="rounded-lg h-[180px] animate-pulse"
                         style={{ background: 'var(--color-surface)' }} />
                </>
            )}

            {error && (
                <div className="rounded-lg p-4 text-center"
                     style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}>
                    <p className="text-sm mb-3">Не удалось загрузить стратегию</p>
                    <p className="text-xs mb-3" style={{ color: 'var(--color-text-muted)' }}>{error}</p>
                    <Button onClick={refetch} size="sm">Повторить</Button>
                </div>
            )}

            {timeline && !isLoading && !error && timeline.points.length === 0 && (
                <div className="rounded-lg p-6 text-center"
                     style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}>
                    <p className="text-sm">Здесь появится финансовая траектория, когда вы начнёте записывать события и снимки баланса.</p>
                </div>
            )}

            {timeline && !isLoading && !error && timeline.points.length > 0 && (
                <>
                    <CashflowChartCard timeline={timeline} />
                    <CapitalTrajectoryCard timeline={timeline} />
                </>
            )}
        </div>
    );
}
