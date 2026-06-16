import { useState } from 'react';
import { Plus } from 'lucide-react';
import WishlistItemCard, { type RecomputeRequest } from './WishlistItemCard';
import AddWishlistDialog from './AddWishlistDialog';
import type { RiskLevel } from './wishlistUtils';
import type { ItemOverride } from './useWishlistSimulation';
import type { WishlistItem, WishlistStatus } from '../../types/api';

export interface ItemPersistPatch {
    amount: number;
    targetDate: string;
    rate?: number;
    termMonths?: number;
}

interface Props {
    items: WishlistItem[];
    activeMap: Record<string, boolean>;
    overrideMap: Record<string, ItemOverride>;
    soloRiskMap: Record<string, RiskLevel | undefined>;
    currentMonth: string;
    /** Верхняя граница слайдера суммы для item'а (по kind: maxWishlistAmount / maxCreditAmount). */
    maxFor: (item: WishlistItem) => number;
    onToggleActive: (id: string) => void;
    onAmountChange: (id: string, amount: number) => void;
    onDateChange: (id: string, date: string) => void;
    onParamsRecompute: (item: WishlistItem, req: RecomputeRequest) => void;
    onPersist: (item: WishlistItem, patch: ItemPersistPatch) => void;
    onFix: (item: WishlistItem) => void;
    onDelete: (item: WishlistItem) => void;
    onStatusChange: (item: WishlistItem, status: WishlistStatus) => void;
    /** После создания нового item'а — refetch родителя. */
    onCreated: () => void;
}

/** Рендерит один item как карточку, прокидывая per-item state и колбэки. */
function renderCard(item: WishlistItem, p: Props) {
    return (
        <WishlistItemCard
            key={item.id}
            item={item}
            active={!!p.activeMap[item.id]}
            amountOverride={p.overrideMap[item.id]?.amount}
            dateOverride={p.overrideMap[item.id]?.targetDate}
            soloRisk={p.soloRiskMap[item.id]}
            amountMax={p.maxFor(item)}
            currentMonth={p.currentMonth}
            onToggleActive={() => p.onToggleActive(item.id)}
            onAmountChange={a => p.onAmountChange(item.id, a)}
            onDateChange={d => p.onDateChange(item.id, d)}
            onParamsRecompute={req => p.onParamsRecompute(item, req)}
            onPersist={patch => p.onPersist(item, patch)}
            onFix={() => p.onFix(item)}
            onDelete={() => p.onDelete(item)}
            onStatusChange={s => p.onStatusChange(item, s)}
        />
    );
}

interface CollapsibleProps {
    title: string;
    count: number;
    children: React.ReactNode;
}

/** Свёрнутая по умолчанию секция с счётчиком (FIXED / DISMISSED). */
function CollapsibleSection({ title, count, children }: CollapsibleProps) {
    const [open, setOpen] = useState(false);
    return (
        <div className="space-y-2">
            <button
                onClick={() => setOpen(o => !o)}
                className="w-full flex items-center justify-between text-left text-sm font-medium py-1"
                aria-expanded={open}>
                <span>{title} ({count})</span>
                <span className={open ? 'rotate-180 transition-transform' : 'transition-transform'}
                      style={{ color: 'var(--color-text-muted)' }}>▾</span>
            </button>
            {open && <div className="space-y-3">{children}</div>}
        </div>
    );
}

/**
 * Список item'ов в трёх секциях: OPEN (всегда развёрнута), FIXED и DISMISSED
 * (свёрнуты, со счётчиком). Внизу — кнопка «+ Добавить хотелку».
 */
export default function WishlistItemList(props: Props) {
    const [addOpen, setAddOpen] = useState(false);

    const open = props.items.filter(i => i.status === 'OPEN');
    const fixed = props.items.filter(i => i.status === 'FIXED');
    const dismissed = props.items.filter(i => i.status === 'DISMISSED');

    return (
        <div className="space-y-4">
            {/* OPEN — всегда развёрнута */}
            <div className="space-y-3">
                {open.length === 0 ? (
                    <p className="text-sm text-center py-2" style={{ color: 'var(--color-text-muted)' }}>
                        Нет хотелок на обсуждении
                    </p>
                ) : (
                    open.map(item => renderCard(item, props))
                )}
            </div>

            {fixed.length > 0 && (
                <CollapsibleSection title="Зафиксировано" count={fixed.length}>
                    {fixed.map(item => renderCard(item, props))}
                </CollapsibleSection>
            )}

            {dismissed.length > 0 && (
                <CollapsibleSection title="Отклонено" count={dismissed.length}>
                    {dismissed.map(item => renderCard(item, props))}
                </CollapsibleSection>
            )}

            <button
                onClick={() => setAddOpen(true)}
                className="w-full flex items-center justify-center gap-1.5 rounded-xl py-3 text-sm font-medium transition-colors"
                style={{ border: '1px dashed var(--color-border)', color: 'var(--color-text-muted)' }}>
                <Plus size={16} /> Добавить хотелку
            </button>

            <AddWishlistDialog
                open={addOpen}
                onClose={() => setAddOpen(false)}
                onCreated={props.onCreated}
            />
        </div>
    );
}
