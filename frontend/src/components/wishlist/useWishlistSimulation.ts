import { useCallback, useEffect, useMemo, useState } from 'react';
import { fetchWishlistSimulation } from '../../api';
import type { MonthDelta, WishlistSimulationDto } from '../../types/api';
import {
    composeTimeline,
    riskZones,
    scaleDelta,
    type ActiveItem,
    type BaselinePoint,
    type RiskLevel,
} from './wishlistUtils';

/** Локальный override одного item'а: позиции слайдеров + (опционально) пересчитанная delta. */
export interface ItemOverride {
    amount?: number;
    targetDate?: string;
    delta?: MonthDelta[];
}

export interface WishlistSimulationActions {
    /** Включить/выключить влияние item'а на симуляцию. */
    toggleItem: (id: string) => void;
    /** Слайдер суммы: масштабирует delta локально через scaleDelta (без сети). */
    setAmountOverride: (id: string, amount: number) => void;
    /** Слайдер даты: сохраняет дату; фактический пересчёт delta приходит через applyRecomputedDelta. */
    setDateOverride: (id: string, date: string) => void;
    /** Карточка пересчитала delta на бэке (смена параметров) и проталкивает её обратно. */
    applyRecomputedDelta: (id: string, delta: MonthDelta[]) => void;
}

export interface UseWishlistSimulationResult {
    data: WishlistSimulationDto | null;
    isLoading: boolean;
    error: string | null;
    refetch: () => void;
    activeMap: Record<string, boolean>;
    overrideMap: Record<string, ItemOverride>;
    composed: BaselinePoint[];
    zones: RiskLevel[];
    futureMonths: string[];
    actions: WishlistSimulationActions;
}

/**
 * Загружает симуляцию /wishlist и держит локальное UI-состояние (active/override) для
 * мгновенного отклика слайдеров. Сеть НЕ дёргается при перетаскивании слайдера — только
 * на mount/refetch; пересчёт параметров делает карточка и проталкивает delta через
 * {@link WishlistSimulationActions.applyRecomputedDelta}.
 *
 * <p>Соглашение об индексах (load-bearing): {@code monthIndex = 0} ⟷ {@code current+1}.
 * Симуляционная поверхность — только FUTURE-сегмент baseline; массив index i ⟷ monthIndex i.
 */
export function useWishlistSimulation(horizonMonths = 36): UseWishlistSimulationResult {
    const [data, setData] = useState<WishlistSimulationDto | null>(null);
    const [isLoading, setIsLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [tick, setTick] = useState(0);

    const [activeMap, setActiveMap] = useState<Record<string, boolean>>({});
    const [overrideMap, setOverrideMap] = useState<Record<string, ItemOverride>>({});

    const refetch = useCallback(() => setTick(t => t + 1), []);

    useEffect(() => {
        let cancelled = false;
        setIsLoading(true);
        setError(null);
        fetchWishlistSimulation(horizonMonths)
            .then(d => {
                if (cancelled) return;
                setData(d);
                // Сидируем active: OPEN+FIXED влияют по умолчанию, DISMISSED — нет.
                const seed: Record<string, boolean> = {};
                for (const item of d.items) {
                    seed[item.id] = item.status === 'OPEN' || item.status === 'FIXED';
                }
                setActiveMap(seed);
                setOverrideMap({});
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
    }, [tick, horizonMonths]);

    const toggleItem = useCallback((id: string) => {
        setActiveMap(prev => ({ ...prev, [id]: !prev[id] }));
    }, []);

    const setAmountOverride = useCallback((id: string, amount: number) => {
        setOverrideMap(prev => ({ ...prev, [id]: { ...prev[id], amount } }));
    }, []);

    const setDateOverride = useCallback((id: string, date: string) => {
        setOverrideMap(prev => ({ ...prev, [id]: { ...prev[id], targetDate: date } }));
    }, []);

    const applyRecomputedDelta = useCallback((id: string, delta: MonthDelta[]) => {
        setOverrideMap(prev => ({ ...prev, [id]: { ...prev[id], delta } }));
    }, []);

    const actions = useMemo<WishlistSimulationActions>(
        () => ({ toggleItem, setAmountOverride, setDateOverride, applyRecomputedDelta }),
        [toggleItem, setAmountOverride, setDateOverride, applyRecomputedDelta],
    );

    // FUTURE-сегмент baseline → BaselinePoint[] (balance → account, index i ⟷ monthIndex i).
    const futurePoints = useMemo(() => {
        if (!data) return [];
        return data.baseline.points.filter(p => p.phase === 'FUTURE');
    }, [data]);

    const futureBaseline = useMemo<BaselinePoint[]>(
        () => futurePoints.map(p => ({ account: p.balance, capital: p.capital })),
        [futurePoints],
    );

    const futureMonths = useMemo<string[]>(
        () => futurePoints.map(p => p.yearMonth),
        [futurePoints],
    );

    // Активные items с учётом override: recomputed delta > scaled amount > исходная delta.
    const activeItems = useMemo<ActiveItem[]>(() => {
        if (!data) return [];
        return data.items.map(item => {
            const ov = overrideMap[item.id];
            let delta = item.delta;
            if (ov?.delta != null) {
                delta = ov.delta;
            } else if (ov?.amount != null) {
                delta = scaleDelta(item.delta, item.amount, ov.amount);
            }
            return { active: !!activeMap[item.id], delta };
        });
    }, [data, activeMap, overrideMap]);

    const composed = useMemo<BaselinePoint[]>(
        () => composeTimeline(futureBaseline, activeItems),
        [futureBaseline, activeItems],
    );

    const zones = useMemo<RiskLevel[]>(() => {
        if (!data) return [];
        return riskZones(composed, data.thresholds, data.constraints.monthlyExpensesAvg);
    }, [composed, data]);

    return {
        data,
        isLoading,
        error,
        refetch,
        activeMap,
        overrideMap,
        composed,
        zones,
        futureMonths,
        actions,
    };
}
