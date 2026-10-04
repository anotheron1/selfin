import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useWishlistSimulation } from '../wishlist/useWishlistSimulation';
import WishlistThresholdsHeader from '../wishlist/WishlistThresholdsHeader';
import WishlistImpactChart from '../wishlist/WishlistImpactChart';
import WishlistItemList from '../wishlist/WishlistItemList';
import FixWishlistDialog, { type ConvertTarget } from '../wishlist/FixWishlistDialog';
import DeleteWishlistDialog from '../wishlist/DeleteWishlistDialog';
import DismissWishlistDialog from '../wishlist/DismissWishlistDialog';
import { type RecomputeRequest } from '../wishlist/WishlistItemCard';
import {
    composeTimeline, riskZones, effectiveDelta, fixPatch, trialParams, dismissQuestion,
    type ActiveItem, type BaselinePoint, type RiskLevel,
} from '../wishlist/wishlistUtils';
import {
    recomputeWishlistItem, convertWishlistItem, fetchPocketSettings,
    setEventWishlistStatus, setFundWishlistStatus,
    setEventWishlistParams, setFundWishlistParams, deleteEvent, deleteFund,
} from '../../api';
import type { FundMoney, WishlistItem, WishlistStatus, WishlistThresholds } from '../../types/api';
import { asksWhereMoney, attempt, deleteTogether, dismissFailure, type Deletable } from '../../lib/writeFailure';
import { LatestRequests } from '../../lib/latestRequests';
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
    /** Номера пересчётов по строкам: опоздавший ответ не применяется (ANO-105). */
    const recomputes = useRef(new LatestRequests()).current;

    // Локальные пороги — позволяют пересчитывать зоны риска мгновенно (хук читает data.thresholds).
    const [thresholds, setThresholds] = useState<WishlistThresholds | null>(null);
    useEffect(() => {
        if (data) setThresholds(data.thresholds);
    }, [data]);
    // Р5 (ANO-93): подушка одна — НЗ кармашка; жёлтый месяц — остаток ниже него.
    // Ревью Codex на #134: не пришедший НЗ — не ноль. С нулём остаток ниже настоящего НЗ стал бы
    // зелёным, а шапка сказала бы «НЗ не задан» — предупреждение пропало бы молча. Пока НЗ нет,
    // блок грузится; отказ ручки — ошибка с «Повторить». Пришедший раньше НЗ при сбое
    // перечитывания остаётся: это настоящее число, а не догадка.
    const [nzLoaded, setNzLoaded] = useState<number | null>(null);
    const [nzError, setNzError] = useState<string | null>(null);
    const loadNz = useCallback(() => {
        setNzError(null);
        fetchPocketSettings()
            .then(s => setNzLoaded(s.bufferAmount))
            .catch((e: Error) => setNzError(e.message));
    }, []);
    useEffect(loadNz, [data, loadNz]);
    /** НЗ для расчёта зон; на экран зоны попадают только с пришедшим НЗ. */
    const nz = nzLoaded ?? 0;

    const [fixItem, setFixItem] = useState<WishlistItem | null>(null);
    const [deleteItem, setDeleteItem] = useState<WishlistItem | null>(null);
    // ANO-141: отказ записи виден там, где нажали. Раньше диалог закрывался до запроса,
    // а отказ глотался перечитыванием списка — снаружи это было «ничего не произошло».
    const [fixBusy, setFixBusy] = useState(false);
    const [fixError, setFixError] = useState<string | null>(null);
    const [deleteBusy, setDeleteBusy] = useState(false);
    const [deleteError, setDeleteError] = useState<string | null>(null);
    /** ANO-198: сервер отказал удалить копилку с деньгами — диалог спрашивает, что с ними. */
    const [deleteAsksMoney, setDeleteAsksMoney] = useState(false);
    const [statusErrors, setStatusErrors] = useState<Record<string, string>>({});
    // ANO-210: «Отложить» у хотелки с созданным планом или копилкой спрашивает, как удаление.
    const [dismissItem, setDismissItem] = useState<WishlistItem | null>(null);
    const [dismissBusy, setDismissBusy] = useState(false);
    const [dismissError, setDismissError] = useState<string | null>(null);

    const effThresholds = thresholds ?? data?.thresholds ?? { capitalThresholdRub: null };

    // FUTURE-baseline (для solo-симуляции каждого item'а).
    const futureBaseline = useMemo<BaselinePoint[]>(() => {
        if (!data) return [];
        return data.baseline.points.filter(p => p.phase === 'FUTURE').map(p => ({ account: p.balance, capital: p.capital }));
    }, [data]);

    // Зоны риска для графика — по эффективным порогам.
    const zones = useMemo<RiskLevel[]>(
        () => riskZones(composed, effThresholds, nz),
        [composed, effThresholds, nz],
    );

    // Solo-риск каждого item'а: симулируем ТОЛЬКО его поверх baseline.
    const soloRiskMap = useMemo<Record<string, RiskLevel | undefined>>(() => {
        if (!data) return {};
        const map: Record<string, RiskLevel | undefined> = {};
        for (const item of data.items) {
            const delta = effectiveDelta(item, overrideMap[item.id]);
            const solo: ActiveItem[] = [{ active: true, delta }];
            const composedSolo = composeTimeline(futureBaseline, solo);
            map[item.id] = worstZone(riskZones(composedSolo, effThresholds, nz));
        }
        return map;
    }, [data, overrideMap, futureBaseline, effThresholds, nz]);

    const account = useMemo(() => composed.map(p => p.account), [composed]);
    const capital = useMemo(() => composed.map(p => p.capital), [composed]);

    const maxFor = (item: WishlistItem) =>
        item.kind === 'CREDIT' ? (data?.constraints.maxCreditAmount ?? 0) : (data?.constraints.maxWishlistAmount ?? 0);

    // --- Callbacks ---

    const handleParamsRecompute = (item: WishlistItem, req: RecomputeRequest) => {
        // ANO-139: подкрученные ставка/срок запоминаются как примерка. Раньше карточка
        // писала их в базу прямо по blur — здесь они только доезжают до fixPatch.
        actions.setCreditOverride(item.id, req.rate, req.termMonths);
        // ANO-105: пересчётов по строке бывает несколько подряд — применяется только последний.
        // Сумма запроса едет с дельтой: подкрученная после масштабирует от неё.
        const n = recomputes.start(item.id);
        recomputeWishlistItem({
            kind: req.kind, amount: req.amount, targetDate: req.targetDate,
            rate: req.rate, termMonths: req.termMonths,
        })
            .then(resp => {
                if (recomputes.isLatest(item.id, n)) {
                    actions.applyRecomputedDelta(item.id, resp.delta, req.amount, resp.monthlyContribution);
                }
            })
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
        // ANO-162: только параметры примерки, отдельной записью. Полная перезапись (PUT) отвязывала
        // копилку от счёта и стирала исходный текст хотелки, а хотелку без срока не писала вовсе.
        const write = trialParams(item, fixPatch(item, overrideMap[item.id]), explicitDate);
        return write.kind === 'event'
            ? setEventWishlistParams(item.id, write.body)
            : setFundWishlistParams(item.id, write.body);
    };

    /** @param deleteArtifact удалить и созданный план или копилку — одной записью со статусом (ANO-210) */
    const changeStatus = (item: WishlistItem, status: WishlistStatus, deleteArtifact = false): Promise<unknown> =>
        (item.kind === 'WISHLIST' ? setEventWishlistStatus : setFundWishlistStatus)(item.id, status, deleteArtifact);

    /**
     * «Отложить» и «Вернуть в обсуждение»: отказ — строкой на карточке, до следующего нажатия.
     *
     * <p>На отказе список не перечитывается (ревью Codex, #108): перечитывание уводит блок в
     * загрузку, а без связи — в ошибку загрузки, и карточка со строкой отказа пропадает. Статус
     * не сменился — перечитывать нечего; повтор той же смены безопасен.
     */
    const handleStatusChange = async (item: WishlistItem, status: WishlistStatus) => {
        // ANO-210: у хотелки с созданным «Отложить» оставило бы созданное в расчёте молча —
        // сначала вопрос, запись — из диалога.
        if (status === 'DISMISSED' && dismissQuestion(item)) {
            setDismissError(null);
            setDismissItem(item);
            return;
        }
        setStatusErrors(prev => {
            const next = { ...prev };
            delete next[item.id];
            return next;
        });
        const failure = await attempt(() => changeStatus(item, status));
        if (failure) {
            setStatusErrors(prev => ({ ...prev, [item.id]: failure }));
            return;
        }
        refetch();
    };

    const openFix = (item: WishlistItem) => {
        setFixError(null);
        setFixItem(item);
    };

    /**
     * Диалог закрывается только на успехе; отказ остаётся в нём строкой.
     *
     * <p>На отказе список не перечитывается, пока диалог открыт: перечитывание сбрасывает
     * подкрученное (`overrideMap`), и повтор записал бы в хотелку прежние числа вместо тех, на
     * которые человек смотрел. Перечитывание — при закрытии ({@link closeFix}): запись примерки
     * могла пройти до отказа конверсии.
     */
    const handleFixConfirm = async (target: ConvertTarget, createRecurringPayments: boolean,
                                    planDate?: string) => {
        if (!fixItem) return;
        const item = fixItem;
        setFixBusy(true);
        setFixError(null);
        // Сначала запись подкрученного, только потом конверсия: иначе план создастся
        // на прежнюю сумму. Если запись не прошла — не конвертируем: план на устаревшем
        // числе хуже, чем несработавшая кнопка.
        const failure = await attempt(() => persistTrial(item, planDate)
            .then(() => convertWishlistItem(item.id,
                { sourceKind: item.kind, target, createRecurringPayments, planDate })));
        setFixBusy(false);
        if (failure) {
            setFixError(failure);
            return;
        }
        setFixItem(null);
        refetch();
    };

    const handleFixWithoutConversion = async () => {
        if (!fixItem) return;
        const item = fixItem;
        setFixBusy(true);
        setFixError(null);
        const failure = await attempt(() => persistTrial(item).then(() => changeStatus(item, 'FIXED')));
        setFixBusy(false);
        if (failure) {
            setFixError(failure);
            return;
        }
        setFixItem(null);
        refetch();
    };

    /**
     * Закрыть без записи. После отказа — перечитать: примерка могла записаться до отказа
     * конверсии. Без попытки — не перечитывать, иначе простое «Отмена» сбросило бы подкрученное.
     */
    const closeFix = () => {
        setFixItem(null);
        if (fixError) refetch();
    };

    /**
     * «Отложить» из диалога (ANO-210): с галочкой созданное удаляется вместе со сменой статуса —
     * одной записью на сервере. Диалог закрывается только на успехе; отказ остаётся в нём строкой.
     * На отказе список не перечитывается: сервер откатил всё, перечитывать нечего.
     */
    const handleDismissConfirm = async (alsoArtifact: boolean) => {
        if (!dismissItem?.convertedTo) return;
        const item = dismissItem;
        const kind = dismissItem.convertedTo.kind;
        setDismissBusy(true);
        setDismissError(null);
        const failure = await attempt(() => changeStatus(item, 'DISMISSED', alsoArtifact),
            err => dismissFailure(err, kind));
        setDismissBusy(false);
        if (failure) {
            setDismissError(failure);
            return;
        }
        setDismissItem(null);
        refetch();
    };

    /** Закрыть без записи. После отказа — перечитать: ответ мог потеряться уже после записи. */
    const closeDismiss = () => {
        setDismissItem(null);
        if (dismissError) refetch();
    };

    const openDelete = (item: WishlistItem) => {
        setDeleteError(null);
        setDeleteAsksMoney(false);
        setDeleteItem(item);
    };

    /**
     * @param money ответ на вопрос «что с деньгами» (ANO-198) — уходит каждой удаляемой копилке; нет —
     *              первая попытка: копилка с деньгами ответит 409, и диалог спросит
     */
    const handleDeleteConfirm = async (alsoArtifact: boolean, money?: FundMoney) => {
        if (!deleteItem) return;
        const item = deleteItem;
        setDeleteBusy(true);
        setDeleteError(null);
        const remove = (kind: Deletable, id: string) => ({
            kind, run: () => (kind === 'EVENT' ? deleteEvent(id) : deleteFund(id, money)),
        });
        const parts = [remove(item.kind === 'WISHLIST' ? 'EVENT' : 'FUND', item.id)];
        if (alsoArtifact && item.convertedTo) parts.push(remove(item.convertedTo.kind, item.convertedTo.id));
        const failure = await deleteTogether(parts);
        setDeleteBusy(false);
        if (!money && asksWhereMoney(failure)) {
            setDeleteAsksMoney(true);
            return;
        }
        if (failure) {
            setDeleteError(failure);
            return;
        }
        setDeleteItem(null);
        refetch();
    };

    /**
     * Закрыть без удаления. После отказа или вопроса «что с деньгами» — перечитать: одна из двух
     * частей могла удалиться, хотелка — да, копилка с деньгами — нет.
     */
    const closeDelete = () => {
        setDeleteItem(null);
        if (deleteError || deleteAsksMoney) refetch();
    };

    const currentMonth = data?.baseline.currentMonth ?? '';
    const hasBaseline = !!data && data.baseline.points.length > 0;
    /** Данные есть, а НЗ ещё не пришёл: зоны без него не рисуем (ревью Codex на #134). */
    const waitsNz = !!data && !isLoading && !error && hasBaseline && nzLoaded == null;
    /** Что уйдёт в запись при фиксации — подкрученное поверх записанного (ANO-139). */
    const fixView = fixItem && fixPatch(fixItem, overrideMap[fixItem.id]);

    return (
        <div className="space-y-3">
            <p className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
                Приблизительно, по месяцам: влияние включённого набора на счёт, капитал и долги.
            </p>

            {(isLoading || (waitsNz && !nzError)) && (
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

            {waitsNz && nzError && (
                <div className="rounded-lg p-4 text-center"
                     style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}>
                    <p className="text-sm mb-3">Не удалось загрузить НЗ — без него жёлтые месяцы не посчитать</p>
                    <p className="text-xs mb-3" style={{ color: 'var(--color-text-muted)' }}>{nzError}</p>
                    <Button onClick={loadNz} size="sm">Повторить</Button>
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

            {data && !isLoading && !error && hasBaseline && nzLoaded != null && (
                <>
                    {/* key меняется один раз на переходе pending→loaded порогов сервера: форсирует
                        корректный одноразовый (ре)монтаж шапки с серверными значениями, даже если
                        будущий рефактор отрендерит её раньше загрузки. Zero-risk: ремонт только до
                        того, как пользователь успеет печатать (см. seed-once контракт в шапке). */}
                    <WishlistThresholdsHeader
                        key={`thr-${data?.thresholds ? 'loaded' : 'pending'}`}
                        value={effThresholds}
                        nz={nz}
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
                        onFix={openFix}
                        onDelete={openDelete}
                        onStatusChange={handleStatusChange}
                        statusErrors={statusErrors}
                        onCreated={refetch}
                    />
                </>
            )}

            {fixItem && fixView && (
                <FixWishlistDialog
                    open={!!fixItem}
                    /* ANO-139: диалог обязан показывать подкрученное — человек фиксирует
                       то, на что смотрел, а не то, что записано. ANO-141: ставка и срок —
                       тоже подкрученные: по ним решается пункт «Кредит», а сервер читает
                       копилку уже после persistTrial. */
                    item={{
                        ...fixItem,
                        amount: fixView.amount,
                        targetDate: fixView.targetDate ?? null,
                        rate: fixView.rate ?? null,
                        termMonths: fixView.termMonths ?? null,
                    }}
                    busy={fixBusy}
                    error={fixError}
                    onClose={closeFix}
                    onConfirm={handleFixConfirm}
                    onFixWithoutConversion={handleFixWithoutConversion}
                />
            )}
            {deleteItem && (
                <DeleteWishlistDialog
                    open={!!deleteItem}
                    item={deleteItem}
                    busy={deleteBusy}
                    error={deleteError}
                    asksMoney={deleteAsksMoney}
                    onClose={closeDelete}
                    onConfirm={handleDeleteConfirm}
                />
            )}
            {dismissItem && dismissQuestion(dismissItem) && (
                <DismissWishlistDialog
                    open={!!dismissItem}
                    item={dismissItem}
                    question={dismissQuestion(dismissItem)!}
                    busy={dismissBusy}
                    error={dismissError}
                    onClose={closeDismiss}
                    onConfirm={handleDismissConfirm}
                />
            )}
        </div>
    );
}
