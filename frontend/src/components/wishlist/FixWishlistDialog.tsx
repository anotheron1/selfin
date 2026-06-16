import { useEffect, useState } from 'react';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter } from '../ui/dialog';
import { Button } from '../ui/button';
import type { WishlistItem, WishlistKind } from '../../types/api';

export type ConvertTarget = 'PLAN_EVENT' | 'FUND' | 'FUND_WITH_CREDIT';

interface Props {
    open: boolean;
    item: WishlistItem;
    onClose: () => void;
    /** Зафиксировать с конверсией в выбранный артефакт. */
    onConfirm: (target: ConvertTarget, createRecurringPayments: boolean) => void;
    /** Зафиксировать без конверсии (статус FIXED, артефакт не создаётся). */
    onFixWithoutConversion: () => void;
}

/** Дефолтная цель конверсии по типу item'а. */
function defaultTarget(kind: WishlistKind): ConvertTarget {
    switch (kind) {
        case 'WISHLIST': return 'PLAN_EVENT';
        case 'SAVINGS': return 'FUND';
        case 'CREDIT': return 'FUND_WITH_CREDIT';
    }
}

const TARGET_LABEL: Record<ConvertTarget, string> = {
    PLAN_EVENT: 'Плановое событие',
    FUND: 'Копилка',
    FUND_WITH_CREDIT: 'Кредит (копилка + график платежей)',
};

/**
 * Диалог фиксации хотелки: выбор цели конверсии (radio, дефолт по kind),
 * для FUND_WITH_CREDIT — чекбокс «создать платёжный график», и отдельное
 * действие «Зафиксировать без конверсии».
 */
export default function FixWishlistDialog({ open, item, onClose, onConfirm, onFixWithoutConversion }: Props) {
    const [target, setTarget] = useState<ConvertTarget>(defaultTarget(item.kind));
    const [createRecurring, setCreateRecurring] = useState(true);

    // Сброс на дефолт при открытии/смене item'а.
    useEffect(() => {
        if (open) {
            setTarget(defaultTarget(item.kind));
            setCreateRecurring(true);
        }
    }, [open, item.id, item.kind]);

    const targets: ConvertTarget[] = ['PLAN_EVENT', 'FUND', 'FUND_WITH_CREDIT'];

    return (
        <Dialog open={open} onOpenChange={o => !o && onClose()}>
            <DialogContent>
                <DialogHeader>
                    <DialogTitle>Зафиксировать: {item.name}</DialogTitle>
                </DialogHeader>
                <div className="space-y-2 py-2">
                    <p className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
                        Во что превратить решение:
                    </p>
                    {targets.map(t => (
                        <label key={t} className="flex items-center gap-2 text-sm">
                            <input
                                type="radio"
                                checked={target === t}
                                onChange={() => setTarget(t)}
                            />
                            {TARGET_LABEL[t]}
                        </label>
                    ))}
                    {target === 'FUND_WITH_CREDIT' && (
                        <label className="flex items-center gap-2 text-sm pl-6">
                            <input
                                type="checkbox"
                                checked={createRecurring}
                                onChange={e => setCreateRecurring(e.target.checked)}
                            />
                            Создать платёжный график (recurring)
                        </label>
                    )}
                </div>
                <DialogFooter className="flex-col sm:flex-row gap-2">
                    <Button variant="ghost" onClick={onFixWithoutConversion}>
                        Зафиксировать без конверсии
                    </Button>
                    <Button onClick={() => onConfirm(target, createRecurring)}>
                        Зафиксировать
                    </Button>
                </DialogFooter>
            </DialogContent>
        </Dialog>
    );
}
