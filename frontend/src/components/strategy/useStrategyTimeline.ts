import { useEffect, useState, useCallback } from 'react';
import { fetchStrategyTimeline } from '../../api';
import type { StrategyTimelineDto } from '../../types/api';

/**
 * Простой fetch-хук. React сам не вызывает повторный fetch на re-render,
 * пока deps не меняются — отдельный кэш не нужен. Refetch триггерится явно через
 * возвращаемый колбэк (например, после редактирования события в Budget).
 */
export function useStrategyTimeline(params?: {
    horizonMonths?: number;
    withBreakdown?: boolean;
}): {
    data: StrategyTimelineDto | null;
    isLoading: boolean;
    error: string | null;
    refetch: () => void;
} {
    const [data, setData] = useState<StrategyTimelineDto | null>(null);
    const [isLoading, setIsLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [tick, setTick] = useState(0);

    const refetch = useCallback(() => setTick(t => t + 1), []);

    useEffect(() => {
        let cancelled = false;
        setIsLoading(true);
        setError(null);
        fetchStrategyTimeline(params)
            .then(d => {
                if (cancelled) return;
                setData(d);
            })
            .catch(e => {
                if (cancelled) return;
                setError(e instanceof Error ? e.message : String(e));
            })
            .finally(() => {
                if (cancelled) return;
                setIsLoading(false);
            });
        return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [tick, params?.horizonMonths, params?.withBreakdown]);

    return { data, isLoading, error, refetch };
}
