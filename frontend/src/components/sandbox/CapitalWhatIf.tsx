import { useEffect, useMemo, useState } from 'react';
import { useWishlistSimulation } from '../wishlist/useWishlistSimulation';
import WishlistThresholdsHeader from '../wishlist/WishlistThresholdsHeader';
import WishlistImpactChart from '../wishlist/WishlistImpactChart';
import WishlistItemList from '../wishlist/WishlistItemList';
import FixWishlistDialog, { type ConvertTarget } from '../wishlist/FixWishlistDialog';
import DeleteWishlistDialog from '../wishlist/DeleteWishlistDialog';
import { type RecomputeRequest } from '../wishlist/WishlistItemCard';
import {
    composeTimeline, riskZones, effectiveDelta, fixPatch,
    type ActiveItem, type BaselinePoint, type RiskLevel,
} from '../wishlist/wishlistUtils';
import {
    recomputeWishlistItem, convertWishlistItem,
    setEventWishlistStatus, setFundWishlistStatus,
    updateEvent, updateFund, deleteEvent, deleteFund,
} from '../../api';
import type { WishlistItem, WishlistStatus, WishlistThresholds } from '../../types/api';
import { Button } from '../ui/button';

/** Худшая зона риска по вектору (для solo-бейджа). */
function worstZone(zones: RiskLevel[]): RiskLevel | undefined {
    if (zones.length === 0) return undefined;
    const rank = { green: 0, yellow: 1, red: 2 };
    return zones.reduce((acc, z) => (rank[z] >= rank[acc] ? z : acc), 'green' as RiskLevel);
}

/**
 * «Что с капиталом» (ANO-16 §7): месячная картинка счёта/капитала/долгов — реюз
 * прежнего экрана /wishlist целиком. Вторичный вид «приблизительно, по месяцам»;
 * дневная правда о кармашке живёт в песочнице выше. Ленивая загрузка по раскрытию.
 */
export default function CapitalWhatIf() {
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
        // ANO-139: подкрученные ставка/срок запоминаются как примерка. Раньше карточка
        // писала их в базу прямо по blur — здесь они только доезжают до fixPatch.
        actions.setCreditOverride(item.id, req.rate, req.termMonths);
        recomputeWishlistItem({
            kind: req.kind, amount: req.amount, targetDate: req.targetDate,
            rate: req.rate, termMonths: req.termMonths,
        })
            .then(resp => actions.applyRecomputedDelta(item.id, resp.delta))
            .catch(() => {/* старая delta остаётся; не блокируем UI */});
    };

    /**
     * Переносит подкрученные параметры в запись — единственная запись этого блока (ANO-139).
     *
     * <p>Возвращает промис намеренно: конверсия читает СОХРАНЁННУЮ сумму
     * (`convertFromEvent` берёт `src.getPlannedAmount()`), поэтому она обязана идти
     * строго после записи, иначе человек увидит план на прежнее число.
     *
     * @param explicitDate дата, названная в диалоге фиксации (только для PLAN_EVENT).
     *                     Она главнее подкрученной: это последний явный выбор человека,
     *                     и ровно она уйдёт в создаваемый план.
     */
    const persistTrial = (item: WishlistItem, explicitDate?: string): Promise<unknown> => {
        const patch = fixPatch(item, overrideMap[item.id]);
        const date = explicitDate ?? patch.targetDate;
        if (item.kind === 'WISHLIST') {
            // PUT /events требует дату. У хотелки без срока её нет, а выдумывать нельзя
            // (ANO-29): без даты не пишем вовсе.
            if (!date) return Promise.resolve();
            return updateEvent(item.id, {
                date,
                categoryId: item.categoryId ?? undefined,
                type: 'EXPENSE',
                priority: 'LOW',
                plannedAmount: patch.amount,
                description: item.name,
            });
        }
        return updateFund(item.id, {
            name: item.name,
            targetAmount: patch.amount,
            targetDate: date,
            purchaseType: item.kind === 'CREDIT' ? 'CREDIT' : 'SAVINGS',
            creditRate: patch.rate,
            creditTermMonths: patch.termMonths,
        });
    };

    const handleStatusChange = (item: WishlistItem, status: WishlistStatus) => {
        const call = item.kind === 'WISHLIST' ? setEventWishlistStatus : setFundWishlistStatus;
        call(item.id, status).then(refetch).catch(refetch);
    };

    const handleFixConfirm = (target: ConvertTarget, createRecurringPayments: boolean,
                              planDate?: string) => {
        if (!fixItem) return;
        const item = fixItem;
        setFixItem(null);
        // Сначала запись подкрученного, только потом конверсия: иначе план создастся
        // на прежнюю сумму. Если запись не прошла — не конвертируем: план на устаревшем
        // числе хуже, чем несработавшая кнопка.
        persistTrial(item, planDate)
            .then(() => convertWishlistItem(item.id,
                { sourceKind: item.kind, target, createRecurringPayments, planDate }))
            .then(refetch)
            .catch(refetch);
    };

    const handleFixWithoutConversion = () => {
        if (!fixItem) return;
        const item = fixItem;
        setFixItem(null);
        persistTrial(item)
            .then(() => handleStatusChange(item, 'FIXED'))
            .catch(refetch);
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
        <div className="space-y-3">
            <p className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
                Приблизительно, по месяцам: влияние включённого набора на счёт, капитал и долги.
            </p>

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
                    {/* key меняется один раз на переходе pending→loaded порогов сервера: форсирует
                        корректный одноразовый (ре)монтаж шапки с серверными значениями, даже если
                        будущий рефактор отрендерит её раньше загрузки. Zero-risk: ремонт только до
                        того, как пользователь успеет печатать (см. seed-once контракт в шапке). */}
                    <WishlistThresholdsHeader
                        key={`thr-${data?.thresholds ? 'loaded' : 'pending'}`}
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
                    /* ANO-139: диалог обязан показывать подкрученное — человек фиксирует
                       то, на что смотрел, а не то, что записано. */
                    item={{
                        ...fixItem,
                        amount: fixPatch(fixItem, overrideMap[fixItem.id]).amount,
                        targetDate: fixPatch(fixItem, overrideMap[fixItem.id]).targetDate ?? null,
                    }}
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
