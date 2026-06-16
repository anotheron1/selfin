import { useEffect, useMemo, useState } from 'react';
import { useWishlistSimulation } from '../components/wishlist/useWishlistSimulation';
import WishlistThresholdsHeader from '../components/wishlist/WishlistThresholdsHeader';
import WishlistImpactChart from '../components/wishlist/WishlistImpactChart';
import WishlistItemList, { type ItemPersistPatch } from '../components/wishlist/WishlistItemList';
import FixWishlistDialog, { type ConvertTarget } from '../components/wishlist/FixWishlistDialog';
import DeleteWishlistDialog from '../components/wishlist/DeleteWishlistDialog';
import { type RecomputeRequest } from '../components/wishlist/WishlistItemCard';
import {
    composeTimeline, riskZones, scaleDelta,
    type ActiveItem, type BaselinePoint, type RiskLevel,
} from '../components/wishlist/wishlistUtils';
import {
    recomputeWishlistItem, convertWishlistItem,
    setEventWishlistStatus, setFundWishlistStatus,
    updateEvent, updateFund, deleteEvent, deleteFund,
} from '../api';
import type { MonthDelta, WishlistItem, WishlistStatus, WishlistThresholds } from '../types/api';
import { Button } from '../components/ui/button';

/** Эффективная delta item'а с учётом override (recomputed > scaled amount > базовая). */
function effectiveDelta(item: WishlistItem, override: { amount?: number; delta?: MonthDelta[] } | undefined): MonthDelta[] {
    if (override?.delta != null) return override.delta;
    if (override?.amount != null) return scaleDelta(item.delta, item.amount, override.amount);
    return item.delta;
}

/** Худшая зона риска по вектору (для solo-бейджа). */
function worstZone(zones: RiskLevel[]): RiskLevel | undefined {
    if (zones.length === 0) return undefined;
    const rank = { green: 0, yellow: 1, red: 2 };
    return zones.reduce((acc, z) => (rank[z] >= rank[acc] ? z : acc), 'green' as RiskLevel);
}

export default function Wishlist() {
    const sim = useWishlistSimulation();
    const { data, isLoading, error, refetch, activeMap, overrideMap, composed, futureMonths, actions } = sim;

    // Локальные пороги — позволяют пересчитывать зоны риска мгновенно (хук читает data.thresholds).
    const [thresholds, setThresholds] = useState<WishlistThresholds | null>(null);
    useEffect(() => {
        if (data) setThresholds(data.thresholds);
    }, [data]);

    const [fixItem, setFixItem] = useState<WishlistItem | null>(null);
    const [deleteItem, setDeleteItem] = useState<WishlistItem | null>(null);

    const monthlyExpensesAvg = data?.constraints.monthlyExpensesAvg ?? 0;
    const effThresholds = thresholds ?? data?.thresholds ?? { capitalThresholdRub: null, cashBufferMonths: 1 };

    // FUTURE-baseline (для solo-симуляции каждого item'а).
    const futureBaseline = useMemo<BaselinePoint[]>(() => {
        if (!data) return [];
        return data.baseline.points.filter(p => p.phase === 'FUTURE').map(p => ({ account: p.balance, capital: p.capital }));
    }, [data]);

    // Зоны риска для графика — по эффективным порогам.
    const zones = useMemo<RiskLevel[]>(
        () => riskZones(composed, effThresholds, monthlyExpensesAvg),
        [composed, effThresholds, monthlyExpensesAvg],
    );

    // Solo-риск каждого item'а: симулируем ТОЛЬКО его поверх baseline.
    const soloRiskMap = useMemo<Record<string, RiskLevel | undefined>>(() => {
        if (!data) return {};
        const map: Record<string, RiskLevel | undefined> = {};
        for (const item of data.items) {
            const delta = effectiveDelta(item, overrideMap[item.id]);
            const solo: ActiveItem[] = [{ active: true, delta }];
            const composedSolo = composeTimeline(futureBaseline, solo);
            map[item.id] = worstZone(riskZones(composedSolo, effThresholds, monthlyExpensesAvg));
        }
        return map;
    }, [data, overrideMap, futureBaseline, effThresholds, monthlyExpensesAvg]);

    const account = useMemo(() => composed.map(p => p.account), [composed]);
    const capital = useMemo(() => composed.map(p => p.capital), [composed]);

    const maxFor = (item: WishlistItem) =>
        item.kind === 'CREDIT' ? (data?.constraints.maxCreditAmount ?? 0) : (data?.constraints.maxWishlistAmount ?? 0);

    // --- Callbacks ---

    const handleParamsRecompute = (item: WishlistItem, req: RecomputeRequest) => {
        recomputeWishlistItem({
            kind: req.kind, amount: req.amount, targetDate: req.targetDate,
            rate: req.rate, termMonths: req.termMonths,
        })
            .then(resp => actions.applyRecomputedDelta(item.id, resp.delta))
            .catch(() => {/* старая delta остаётся; не блокируем UI */});
    };

    const handlePersist = (item: WishlistItem, patch: ItemPersistPatch) => {
        if (item.kind === 'WISHLIST') {
            updateEvent(item.id, {
                date: patch.targetDate,
                categoryId: item.categoryId ?? undefined,
                type: 'EXPENSE',
                priority: 'LOW',
                plannedAmount: patch.amount,
                description: item.name,
            }).catch(() => {/* визуальное состояние уже применено; refetch не насилуем */});
        } else {
            updateFund(item.id, {
                name: item.name,
                targetAmount: patch.amount,
                targetDate: patch.targetDate,
                purchaseType: item.kind === 'CREDIT' ? 'CREDIT' : 'SAVINGS',
                creditRate: patch.rate,
                creditTermMonths: patch.termMonths,
            }).catch(() => {/* idem */});
        }
    };

    const handleStatusChange = (item: WishlistItem, status: WishlistStatus) => {
        const call = item.kind === 'WISHLIST' ? setEventWishlistStatus : setFundWishlistStatus;
        call(item.id, status).then(refetch).catch(refetch);
    };

    const handleFixConfirm = (target: ConvertTarget, createRecurringPayments: boolean) => {
        if (!fixItem) return;
        const item = fixItem;
        setFixItem(null);
        convertWishlistItem(item.id, { sourceKind: item.kind, target, createRecurringPayments })
            .then(refetch)
            .catch(refetch);
    };

    const handleFixWithoutConversion = () => {
        if (!fixItem) return;
        const item = fixItem;
        setFixItem(null);
        handleStatusChange(item, 'FIXED');
    };

    const handleDeleteConfirm = (alsoArtifact: boolean) => {
        if (!deleteItem) return;
        const item = deleteItem;
        setDeleteItem(null);
        const deleteSource = item.kind === 'WISHLIST' ? deleteEvent(item.id) : deleteFund(item.id);
        const deleteArtifact = alsoArtifact && item.convertedTo
            ? (item.convertedTo.kind === 'EVENT' ? deleteEvent(item.convertedTo.id) : deleteFund(item.convertedTo.id))
            : Promise.resolve();
        Promise.allSettled([deleteSource, deleteArtifact]).then(refetch);
    };

    const currentMonth = data?.baseline.currentMonth ?? '';
    const hasBaseline = !!data && data.baseline.points.length > 0;

    return (
        <div className="px-3 pt-3 pb-6 space-y-3">
            <div>
                <h1 className="text-xl font-semibold">Хотелки</h1>
                <p className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
                    Большие решения: двигайте суммы и даты, смотрите влияние на счёт и капитал
                </p>
            </div>

            {isLoading && (
                <>
                    <div className="rounded-lg h-[88px] animate-pulse" style={{ background: 'var(--color-surface)' }} />
                    <div className="rounded-lg h-[280px] animate-pulse" style={{ background: 'var(--color-surface)' }} />
                    <div className="rounded-lg h-[160px] animate-pulse" style={{ background: 'var(--color-surface)' }} />
                </>
            )}

            {error && (
                <div className="rounded-lg p-4 text-center"
                     style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}>
                    <p className="text-sm mb-3">Не удалось загрузить страницу хотелок</p>
                    <p className="text-xs mb-3" style={{ color: 'var(--color-text-muted)' }}>{error}</p>
                    <Button onClick={refetch} size="sm">Повторить</Button>
                </div>
            )}

            {data && !isLoading && !error && !hasBaseline && (
                <div className="rounded-lg p-6 text-center"
                     style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}>
                    <p className="text-sm">
                        Здесь появится планирование хотелок, когда вы начнёте записывать события и снимки баланса.
                    </p>
                </div>
            )}

            {data && !isLoading && !error && hasBaseline && (
                <>
                    <WishlistThresholdsHeader
                        value={effThresholds}
                        monthlyExpensesAvg={monthlyExpensesAvg}
                        onChange={setThresholds}
                    />

                    <div className="rounded-2xl px-2 py-3"
                         style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}>
                        <WishlistImpactChart
                            months={futureMonths}
                            account={account}
                            capital={capital}
                            zones={zones}
                            capitalThreshold={effThresholds.capitalThresholdRub}
                        />
                    </div>

                    <WishlistItemList
                        items={data.items}
                        activeMap={activeMap}
                        overrideMap={overrideMap}
                        soloRiskMap={soloRiskMap}
                        currentMonth={currentMonth}
                        maxFor={maxFor}
                        onToggleActive={actions.toggleItem}
                        onAmountChange={actions.setAmountOverride}
                        onDateChange={actions.setDateOverride}
                        onParamsRecompute={handleParamsRecompute}
                        onPersist={handlePersist}
                        onFix={setFixItem}
                        onDelete={setDeleteItem}
                        onStatusChange={handleStatusChange}
                        onCreated={refetch}
                    />
                </>
            )}

            {fixItem && (
                <FixWishlistDialog
                    open={!!fixItem}
                    item={fixItem}
                    onClose={() => setFixItem(null)}
                    onConfirm={handleFixConfirm}
                    onFixWithoutConversion={handleFixWithoutConversion}
                />
            )}
            {deleteItem && (
                <DeleteWishlistDialog
                    open={!!deleteItem}
                    item={deleteItem}
                    onClose={() => setDeleteItem(null)}
                    onConfirm={handleDeleteConfirm}
                />
            )}
        </div>
    );
}
