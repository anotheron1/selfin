import { Trash2 } from 'lucide-react';
import { Badge } from '../ui/badge';
import type { WishlistItem, WishlistStatus } from '../../types/api';
import { fmtRub, fmtYearMonthFull } from '../strategy/strategyChartUtils';

interface Props {
    item: WishlistItem;
    onDelete: () => void;
    onStatusChange: (status: WishlistStatus) => void;
    /** Отказ последней смены статуса словами экрана (ANO-141); нет — отказа не было. */
    statusError?: string | null;
}

/**
 * Отложенная хотелка в разделе «Отложено» (ANO-107). В расчёт она не входит — сервер отдаёт её
 * с пустой дельтой, — поэтому ни галочки «учитывать», ни ползунков, ни фиксации: одно действие —
 * вернуть в обсуждение. Удалить — как у остальных.
 */
export default function DismissedItemCard({ item, onDelete, onStatusChange, statusError }: Props) {
    return (
        <div
            className="rounded-xl p-4 space-y-2"
            style={{ background: 'var(--color-bg)', border: '1px solid var(--color-border)' }}>
            <div className="flex items-center gap-2">
                <span className="font-medium text-sm flex-1 truncate">{item.name}</span>
                {item.kind === 'CREDIT' && (
                    <Badge variant="outline" className="border-orange-500/60 text-orange-400 bg-orange-500/10">Кредит</Badge>
                )}
                <button
                    onClick={onDelete}
                    className="transition-colors p-1"
                    style={{ color: 'var(--color-text-muted)' }}
                    title="Удалить">
                    <Trash2 size={15} />
                </button>
            </div>
            <p className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
                {fmtRub(Math.round(item.amount))}
                {item.targetDate ? ` · ${fmtYearMonthFull(item.targetDate.slice(0, 7))}` : ' · срок не задан'}
            </p>
            {/* ANO-141: отказ смены статуса — здесь, у кнопки, которую нажали. */}
            {statusError && (
                <p className="text-xs text-right" style={{ color: 'var(--color-warning)' }}>{statusError}</p>
            )}
            <div className="flex justify-end">
                <button
                    onClick={() => onStatusChange('OPEN')}
                    className="text-xs underline"
                    style={{ color: 'var(--color-text-muted)' }}>
                    Вернуть в обсуждение
                </button>
            </div>
        </div>
    );
}
