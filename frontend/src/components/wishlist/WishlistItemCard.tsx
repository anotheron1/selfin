import { useEffect, useRef, useState } from 'react';
import { Pencil, Trash2 } from 'lucide-react';
import { Button } from '../ui/button';
import { Input } from '../ui/input';
import { Badge } from '../ui/badge';
import WishlistRiskBadge from './WishlistRiskBadge';
import type { RiskLevel } from './wishlistUtils';
import type { WishlistItem, WishlistStatus } from '../../types/api';
import { fmtRub, fmtYearMonthFull } from '../strategy/strategyChartUtils';
import { calcPMT } from '../funds/savingsStrategyUtils';

/** Параметры пересчёта delta на бэке (смена суммы/даты/кредитных параметров). */
export interface RecomputeRequest {
    kind: WishlistItem['kind'];
    amount: number;
    targetDate: string;
    rate?: number;
    termMonths?: number;
}

interface Props {
    item: WishlistItem;
    active: boolean;
    amountOverride?: number;
    dateOverride?: string;
    soloRisk: RiskLevel | undefined;
    /** Верхняя граница слайдера суммы (из constraints). */
    amountMax: number;
    /** Текущий месяц YYYY-MM — база для маппинга дата↔смещение. */
    currentMonth: string;
    onToggleActive: () => void;
    /** Живое изменение суммы (слайдер) — масштабирует delta локально. */
    onAmountChange: (amount: number) => void;
    /** Живое изменение даты (слайдер). */
    onDateChange: (date: string) => void;
    /** Параметры изменились — родитель пересчитывает delta на бэке. */
    onParamsRecompute: (req: RecomputeRequest) => void;
    /** Отпустили слайдер/изменили параметры — персист через updateEvent/updateFund. */
    onPersist: (patch: { amount: number; targetDate: string; rate?: number; termMonths?: number }) => void;
    onFix: () => void;
    onDelete: () => void;
    onStatusChange: (status: WishlistStatus) => void;
}

const MIN_OFFSET = 1;
const MAX_OFFSET = 36;
/** Дебаунс сетевого персиста: стрелки клавиатуры на слайдере шлют onKeyUp на каждый шаг — коалесцируем в один PUT. */
const PERSIST_DEBOUNCE_MS = 500;

/** "YYYY-MM" + N месяцев → "YYYY-MM". */
function addMonths(ym: string, n: number): string {
    const [y, m] = ym.split('-').map(Number);
    const idx = (y * 12 + (m - 1)) + n;
    const ny = Math.floor(idx / 12);
    const nm = (idx % 12) + 1;
    return `${ny}-${String(nm).padStart(2, '0')}`;
}

/** Целочисленное смещение в месяцах от currentMonth до месяца targetDate (clamp 1..36). */
function offsetOf(targetDate: string, currentMonth: string): number {
    const tym = targetDate.slice(0, 7);
    const [ty, tm] = tym.split('-').map(Number);
    const [cy, cm] = currentMonth.split('-').map(Number);
    const diff = (ty - cy) * 12 + (tm - cm);
    return Math.min(MAX_OFFSET, Math.max(MIN_OFFSET, diff));
}

/**
 * Карточка одного item'а: чекбокс активности + название + риск-бейдж + кнопка Fix,
 * два слайдера (сумма / дата), для кредита — раскрываемые параметры (ставка/срок),
 * строка с PMT/взносом. Живые изменения идут через onAmount/onDateChange (масштаб delta),
 * персист — через onPersist при отпускании слайдера.
 */
export default function WishlistItemCard(props: Props) {
    const {
        item, active, amountOverride, dateOverride, soloRisk, amountMax, currentMonth,
        onToggleActive, onAmountChange, onDateChange, onParamsRecompute, onPersist,
        onFix, onDelete, onStatusChange,
    } = props;

    const amount = amountOverride ?? item.amount;
    const targetDate = dateOverride ?? item.targetDate;
    const offset = offsetOf(targetDate, currentMonth);

    const isCredit = item.kind === 'CREDIT';
    const [showCreditParams, setShowCreditParams] = useState(false);
    const [rate, setRate] = useState(item.rate != null ? String(item.rate) : '');
    const [term, setTerm] = useState(item.termMonths != null ? String(item.termMonths) : '');

    // Пересинхронизировать кредитные поля, если item обновился извне (refetch).
    useEffect(() => {
        setRate(item.rate != null ? String(item.rate) : '');
        setTerm(item.termMonths != null ? String(item.termMonths) : '');
    }, [item.rate, item.termMonths]);

    const sliderMax = Math.max(amountMax, item.amount, 1);

    const buildRecomputeReq = (over: Partial<RecomputeRequest> = {}): RecomputeRequest => ({
        kind: item.kind,
        amount,
        targetDate,
        rate: rate ? Number(rate) : undefined,
        termMonths: term ? Number(term) : undefined,
        ...over,
    });

    // Дебаунс ТОЛЬКО сетевого персиста. Локальное состояние (слайдер + override) обновляется
    // синхронно в onChange — здесь дебаунсим только PUT, чтобы серии нажатий стрелок схлопывались.
    const persistTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
    useEffect(() => () => {
        if (persistTimer.current) clearTimeout(persistTimer.current);
    }, []);

    const persist = (over: { amount?: number; targetDate?: string } = {}) => {
        // Значения резолвим в момент вызова (актуальный closure), отправляем по трейлинг-таймеру.
        const patch = {
            amount: over.amount ?? amount,
            targetDate: over.targetDate ?? targetDate,
            rate: isCredit && rate ? Number(rate) : undefined,
            termMonths: isCredit && term ? Number(term) : undefined,
        };
        if (persistTimer.current) clearTimeout(persistTimer.current);
        persistTimer.current = setTimeout(() => onPersist(patch), PERSIST_DEBOUNCE_MS);
    };

    // PMT/contribution строка: для кредита локально считаем PMT (мгновенный отклик),
    // иначе показываем месячный взнос копилки из item.
    let pmtLine: React.ReactNode = null;
    if (isCredit) {
        const r = rate ? Number(rate) : null;
        const t = term ? Number(term) : null;
        if (r != null && t != null && t > 0 && amount > 0) {
            pmtLine = (
                <span className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
                    Платёж ≈ {fmtRub(Math.round(calcPMT(amount, r, t)))}/мес
                </span>
            );
        } else {
            pmtLine = (
                <span className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
                    Укажите ставку и срок
                </span>
            );
        }
    } else if (item.kind === 'SAVINGS' && item.monthlyContribution != null) {
        pmtLine = (
            <span className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
                Взнос ≈ {fmtRub(Math.round(item.monthlyContribution))}/мес
            </span>
        );
    }

    return (
        <div
            className="rounded-xl p-4 space-y-3"
            style={{ background: 'var(--color-bg)', border: '1px solid var(--color-border)' }}>
            {/* Header: checkbox + name + badge + actions */}
            <div className="flex items-center gap-2">
                <input
                    type="checkbox"
                    checked={active}
                    onChange={onToggleActive}
                    title="Учитывать в симуляции"
                />
                <span className="font-medium text-sm flex-1 truncate">{item.name}</span>
                <WishlistRiskBadge level={active ? soloRisk : undefined} />
                {isCredit && <Badge variant="outline" className="border-orange-500/60 text-orange-400 bg-orange-500/10">Кредит</Badge>}
                <Button size="sm" variant="secondary" className="h-7" onClick={onFix}>
                    <Pencil size={13} className="mr-1" /> Зафиксировать
                </Button>
                <button
                    onClick={onDelete}
                    className="transition-colors p-1"
                    style={{ color: 'var(--color-text-muted)' }}
                    title="Удалить">
                    <Trash2 size={15} />
                </button>
            </div>

            {/* Amount slider */}
            <div className="space-y-1">
                <div className="flex items-center justify-between text-xs">
                    <span style={{ color: 'var(--color-text-muted)' }}>Сумма</span>
                    <span className="font-medium">{fmtRub(Math.round(amount))}</span>
                </div>
                <input
                    type="range"
                    min={0}
                    max={sliderMax}
                    step={Math.max(1, Math.round(sliderMax / 100))}
                    value={amount}
                    onChange={e => onAmountChange(Number(e.target.value))}
                    onMouseUp={() => persist()}
                    onTouchEnd={() => persist()}
                    onKeyUp={() => persist()}
                    className="w-full h-2 rounded-full cursor-pointer accent-[hsl(var(--primary))]"
                />
            </div>

            {/* Date slider (month offset 1..36) */}
            <div className="space-y-1">
                <div className="flex items-center justify-between text-xs">
                    <span style={{ color: 'var(--color-text-muted)' }}>Когда</span>
                    <span className="font-medium">
                        {fmtYearMonthFull(targetDate.slice(0, 7))} (через {offset} мес)
                    </span>
                </div>
                <input
                    type="range"
                    min={MIN_OFFSET}
                    max={MAX_OFFSET}
                    step={1}
                    value={offset}
                    onChange={e => {
                        const next = `${addMonths(currentMonth, Number(e.target.value))}-01`;
                        onDateChange(next);
                    }}
                    onMouseUp={() => persist()}
                    onTouchEnd={() => persist()}
                    onKeyUp={() => persist()}
                    className="w-full h-2 rounded-full cursor-pointer accent-[hsl(var(--primary))]"
                />
            </div>

            {/* PMT / contribution */}
            <div className="flex items-center justify-between">
                {pmtLine}
                {isCredit && (
                    <button
                        onClick={() => setShowCreditParams(s => !s)}
                        className="text-xs underline"
                        style={{ color: 'var(--color-text-muted)' }}>
                        {showCreditParams ? 'Скрыть параметры' : 'Параметры кредита'}
                    </button>
                )}
            </div>

            {/* CREDIT params */}
            {isCredit && showCreditParams && (
                <div className="flex gap-2">
                    <div className="flex-1">
                        <label className="text-xs" style={{ color: 'var(--color-text-muted)' }}>Ставка, %</label>
                        <Input
                            type="number"
                            min="0.01"
                            step="0.01"
                            value={rate}
                            onChange={e => setRate(e.target.value)}
                            onBlur={() => {
                                onParamsRecompute(buildRecomputeReq({ rate: rate ? Number(rate) : undefined }));
                                persist();
                            }}
                            className="h-8 text-sm"
                        />
                    </div>
                    <div className="flex-1">
                        <label className="text-xs" style={{ color: 'var(--color-text-muted)' }}>Срок, мес.</label>
                        <Input
                            type="number"
                            min="1"
                            step="1"
                            value={term}
                            onChange={e => setTerm(e.target.value)}
                            onBlur={() => {
                                onParamsRecompute(buildRecomputeReq({ termMonths: term ? Number(term) : undefined }));
                                persist();
                            }}
                            className="h-8 text-sm"
                        />
                    </div>
                </div>
            )}

            {/* Status affordance: dismiss / restore */}
            <div className="flex justify-end">
                {item.status === 'DISMISSED' ? (
                    <button
                        onClick={() => onStatusChange('OPEN')}
                        className="text-xs underline"
                        style={{ color: 'var(--color-text-muted)' }}>
                        Вернуть в обсуждение
                    </button>
                ) : (
                    <button
                        onClick={() => onStatusChange('DISMISSED')}
                        className="text-xs underline"
                        style={{ color: 'var(--color-text-muted)' }}>
                        Отклонить
                    </button>
                )}
            </div>
        </div>
    );
}
