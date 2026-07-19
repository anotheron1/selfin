import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ChevronDown, TrendingUp } from 'lucide-react';
import {
    postPocketSandbox, convertWishlistItem, setEventWishlistStatus, setFundWishlistStatus,
    createWishlistItem,
} from '../api';
import type {
    SandboxItem, SandboxRef, SandboxRequest, SandboxResponse, SandboxTryOn,
} from '../types/api';
import { fmtRub } from '../lib/format';
import {
    defaultTryOn, lastDayOfMonth, realizationScope, sameRef,
} from '../lib/sandboxMath';
import {
    emptyState, loadSandbox, saveSandbox, forgetRef, type SandboxState,
} from '../lib/sandboxStorage';
import SandboxChart from '../components/sandbox/SandboxChart';
import SandboxItemRow from '../components/sandbox/SandboxItemRow';
import AdhocRow from '../components/sandbox/AdhocRow';
import CapitalWhatIf from '../components/sandbox/CapitalWhatIf';

const SCOPES: { key: string | undefined; label: string }[] = [
    { key: undefined, label: 'До дохода' },
    { key: 'SECOND_INCOME', label: '2-й доход' },
    { key: 'MONTHS:3', label: '3 мес' },
    { key: 'MONTHS:6', label: '6 мес' },
];

/** true если item реально сидит в baseline и им управляют «обратным» тумблером. */
function isInPlan(item: SandboxItem): boolean {
    return item.wishlistStatus === 'FIXED' && item.inBaseline;
}

export default function Wishlist() {
    const navigate = useNavigate();
    const [scope, setScope] = useState<string | undefined>(undefined);
    const [state, setState] = useState<SandboxState>(() => loadSandbox());
    const [resp, setResp] = useState<SandboxResponse | null>(null);
    const [error, setError] = useState<string | null>(null);
    const [showCapital, setShowCapital] = useState(false);
    const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);

    useEffect(() => { saveSandbox(state); }, [state]);

    // Скоуп «до реализации»: max дата включённых датированных элементов (§7).
    const realization = useMemo(() => {
        const dates = [
            ...state.enabled.map(t => t.date),
            ...state.adhoc.map(t => t.date),
        ];
        return realizationScope(dates);
    }, [state.enabled, state.adhoc]);

    const effectiveScope = scope === '__REALIZATION__' ? (realization ?? undefined) : scope;

    // Собираем запрос: tryOn = enabled + adhoc; exclude = excluded.
    const request = useMemo<SandboxRequest>(() => ({
        scope: effectiveScope,
        tryOn: [...state.enabled, ...state.adhoc].filter(t => t.date), // без даты не шлём
        exclude: state.excluded,
    }), [effectiveScope, state]);

    const load = useCallback(() => {
        postPocketSandbox(request)
            .then(r => { setResp(r); setError(null); })
            .catch((e: Error) => setError(e.message));
    }, [request]);

    // Debounce 200 мс на любое изменение (тумблер/правка/скоуп).
    useEffect(() => {
        if (debounceRef.current) clearTimeout(debounceRef.current);
        debounceRef.current = setTimeout(load, 200);
        return () => { if (debounceRef.current) clearTimeout(debounceRef.current); };
    }, [load]);

    const items = resp?.items ?? [];

    // ── управление состоянием одного item ───────────────────────────────────

    const enabledParams = (ref: SandboxRef): SandboxTryOn | null =>
        state.enabled.find(t => sameRef(t.ref, ref)) ?? null;

    const isToggled = (item: SandboxItem): boolean => {
        if (isInPlan(item)) return !state.excluded.some(e => sameRef(e, item.ref)); // «обратный» тумблер
        return state.enabled.some(t => sameRef(t.ref, item.ref));
    };

    const toggle = (item: SandboxItem, next: boolean) => {
        setState(s => {
            if (isInPlan(item)) {
                // Обратный тумблер: off → exclude (примерка отказа); on → вернуть как есть
                return next
                    ? { ...s, excluded: s.excluded.filter(e => !sameRef(e, item.ref)),
                        enabled: s.enabled.filter(t => !sameRef(t.ref, item.ref)) }
                    : { ...s, excluded: [...s.excluded.filter(e => !sameRef(e, item.ref)), item.ref] };
            }
            // OPEN / FIXED-не-в-baseline: обычный tryOn
            return next
                ? { ...s, enabled: [...s.enabled.filter(t => !sameRef(t.ref, item.ref)), defaultTryOn(item)] }
                : { ...s, enabled: s.enabled.filter(t => !sameRef(t.ref, item.ref)) };
        });
    };

    const changeParams = (item: SandboxItem, next: SandboxTryOn) => {
        setState(s => {
            const enabled = [...s.enabled.filter(t => !sameRef(t.ref, item.ref)), next];
            // Покрутить FIXED-в-плане = exclude + tryOn парой (§7)
            const excluded = isInPlan(item)
                ? [...s.excluded.filter(e => !sameRef(e, item.ref)), item.ref]
                : s.excluded;
            return { ...s, enabled, excluded };
        });
    };

    // ── фиксация / отложить ─────────────────────────────────────────────────

    const fix = (item: SandboxItem) => {
        const p = enabledParams(item.ref);
        const stretch = p?.stretchMonths ?? item.stretchMonthsDefault ?? 0;
        const date = p?.date ?? item.date ?? undefined;
        if (item.kind === 'WISHLIST') {
            if (stretch >= 1 && date) {
                // Растянутая хотелка → копилка с датой цели = месяц последнего взноса (§8)
                convertWishlistItem(item.ref.id, {
                    sourceKind: 'WISHLIST', target: 'FUND', fundTargetDate: lastDayOfMonth(date),
                }).then(() => afterFix(item.ref)).catch(() => afterFix(item.ref));
            } else {
                setEventWishlistStatus(item.ref.id, 'FIXED')
                    .then(() => afterFix(item.ref)).catch(() => afterFix(item.ref));
            }
        } else {
            // Копилка/кредит уже FIXED — фиксировать нечего, просто убираем из черновика
            afterFix(item.ref);
        }
    };

    const dismiss = (item: SandboxItem) => {
        const call = item.kind === 'WISHLIST' ? setEventWishlistStatus : setFundWishlistStatus;
        call(item.ref.id, 'DISMISSED').then(() => afterFix(item.ref)).catch(() => afterFix(item.ref));
    };

    const afterFix = (ref: SandboxRef) => {
        setState(s => forgetRef(s, ref));
        load();
    };

    // ── ad-hoc ──────────────────────────────────────────────────────────────

    const addAdhoc = (amount: number, date: string) =>
        setState(s => ({ ...s, adhoc: [...s.adhoc, { ref: null, amount, date }] }));
    const removeAdhoc = (index: number) =>
        setState(s => ({ ...s, adhoc: s.adhoc.filter((_, i) => i !== index) }));
    // Хотелка создаётся как OPEN без даты (WishlistCreateDto даты не несёт);
    // дату юзер проставит в строке, если решит примерять её всерьёз.
    const saveAdhocAsWishlist = (amount: number, _date: string) =>
        createWishlistItem({ description: 'Из примерки', plannedAmount: amount })
            .then(load).catch(load);

    // ── рендер ──────────────────────────────────────────────────────────────

    const baseline = resp?.baseline;
    const fitted = resp?.fitted;
    const diff = baseline && fitted ? fitted.pocket - baseline.pocket : 0;

    return (
        <div className="px-3 pt-3 pb-6 space-y-3">
            <div className="flex items-baseline justify-between gap-2">
                <div>
                    <h1 className="text-xl font-semibold">Примерка</h1>
                    <p className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
                        Двигайте хотелки — смотрите удар по кармашку, ничего не сохраняя
                    </p>
                </div>
                <button onClick={() => navigate('/')}
                    className="text-xs px-2.5 py-1 rounded-lg shrink-0"
                    style={{ border: '1px solid var(--color-border)', color: 'var(--color-text-muted)' }}>
                    На дашборд
                </button>
            </div>

            {error && (
                <div className="rounded-lg p-4 text-sm text-center"
                    style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}>
                    Ошибка примерки: {error}
                </div>
            )}

            {/* Шапка: два числа + разница + скоупы */}
            {baseline && fitted && (
                <div className="rounded-2xl p-4"
                    style={{ background: 'linear-gradient(135deg, var(--color-accent) 0%, #9f8cff 100%)' }}>
                    <div className="flex items-end justify-between gap-3">
                        <div>
                            <p className="text-xs text-white/70">В кармашке сейчас</p>
                            <p className="text-2xl font-bold text-white">{fmtRub(baseline.pocket)}</p>
                        </div>
                        <div className="text-right">
                            <p className="text-xs text-white/70">С примеркой</p>
                            <p className="text-2xl font-bold text-white">{fmtRub(fitted.pocket)}</p>
                        </div>
                    </div>
                    {diff !== 0 && (
                        <p className="text-sm text-white/90 mt-1 text-right font-medium">
                            {diff < 0 ? '' : '+'}{fmtRub(diff)}
                        </p>
                    )}
                    <div className="flex gap-1.5 mt-3 flex-wrap">
                        {SCOPES.map(sc => (
                            <button key={sc.label} onClick={() => setScope(sc.key)}
                                className={`text-xs px-2.5 py-1 rounded-full transition-colors ${
                                    scope === sc.key ? 'bg-white/90 text-black font-semibold'
                                        : 'bg-white/15 text-white/80 hover:bg-white/25'}`}>
                                {sc.label}
                            </button>
                        ))}
                        {realization && (
                            <button onClick={() => setScope('__REALIZATION__')}
                                className={`text-xs px-2.5 py-1 rounded-full transition-colors ${
                                    scope === '__REALIZATION__' ? 'bg-white/90 text-black font-semibold'
                                        : 'bg-white/15 text-white/80 hover:bg-white/25'}`}>
                                До реализации
                            </button>
                        )}
                    </div>
                </div>
            )}

            {baseline && fitted && <SandboxChart baseline={baseline} fitted={fitted} />}

            {/* Список хотелок/копилок */}
            <div className="space-y-2">
                {items.map(item => (
                    <SandboxItemRow
                        key={`${item.ref.type}:${item.ref.id}`}
                        item={item}
                        enabled={isToggled(item)}
                        params={enabledParams(item.ref)}
                        onToggle={next => toggle(item, next)}
                        onParamsChange={next => changeParams(item, next)}
                        onFix={() => fix(item)}
                        onDismiss={() => dismiss(item)}
                    />
                ))}
                {items.length === 0 && !error && (
                    <p className="text-xs text-center py-4" style={{ color: 'var(--color-text-muted)' }}>
                        Хотелок пока нет — добавьте ad-hoc примерку ниже.
                    </p>
                )}
            </div>

            <AdhocRow
                items={state.adhoc}
                onAdd={addAdhoc}
                onRemove={removeAdhoc}
                onSaveAsWishlist={saveAdhocAsWishlist}
            />

            {/* Что с капиталом — вторичный месячный вид, ленивый */}
            <div className="rounded-2xl overflow-hidden"
                style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}>
                <button onClick={() => setShowCapital(v => !v)}
                    className="w-full flex items-center justify-between px-4 py-3 text-sm font-medium">
                    <span className="flex items-center gap-2">
                        <TrendingUp size={15} style={{ color: 'var(--color-primary)' }} />
                        Что с капиталом
                    </span>
                    <ChevronDown size={16}
                        style={{ transform: showCapital ? 'rotate(180deg)' : 'none', transition: 'transform 0.2s' }} />
                </button>
                {showCapital && (
                    <div className="px-3 pb-3">
                        <CapitalWhatIf />
                    </div>
                )}
            </div>
        </div>
    );
}
