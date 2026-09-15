import { useEffect, useState } from 'react';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter } from '../ui/dialog';
import { Button } from '../ui/button';
import { Input } from '../ui/input';
import type { WishlistItem, WishlistKind } from '../../types/api';
import { canConfirmConversion, type ConvertTarget } from './wishlistUtils';

export type { ConvertTarget };

interface Props {
    open: boolean;
    item: WishlistItem;
    onClose: () => void;
    /** Зафиксировать с конверсией в выбранный артефакт. */
    onConfirm: (target: ConvertTarget, createRecurringPayments: boolean, planDate?: string) => void;
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
    // ANO-138: срок плана. У хотелки «когда-нибудь» его нет — спрашиваем здесь,
    // выдумывать дату нельзя (ANO-29).
    const [planDate, setPlanDate] = useState(item.targetDate ?? '');
    // Граница та же, что у серверного requireFutureDate: строго завтра и дальше.
    const t = new Date();
    const todayIso = `${t.getFullYear()}-${String(t.getMonth() + 1).padStart(2, '0')}-${String(t.getDate()).padStart(2, '0')}`;
    const minDate = new Date(t.getTime() + 24 * 60 * 60 * 1000);
    const minIso = `${minDate.getFullYear()}-${String(minDate.getMonth() + 1).padStart(2, '0')}-${String(minDate.getDate()).padStart(2, '0')}`;

    // Сброс на дефолт при открытии/смене item'а.
    useEffect(() => {
        if (open) {
            setTarget(defaultTarget(item.kind));
            setCreateRecurring(true);
            setPlanDate(item.targetDate ?? '');
        }
    }, [open, item.id, item.kind, item.targetDate]);

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
                    {target === 'PLAN_EVENT' && (
                        <div className="pl-6 space-y-1 pt-1">
                            <Input
                                type="date"
                                value={planDate}
                                min={minIso}
                                onChange={e => setPlanDate(e.target.value)}
                            />
                            <p className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
                                Когда планируешь потратить
                            </p>
                        </div>
                    )}
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
                    <Button
                        disabled={!canConfirmConversion(target, planDate, todayIso)}
                        onClick={() => onConfirm(target, createRecurring, planDate || undefined)}
                    >
                        Зафиксировать
                    </Button>
                </DialogFooter>
            </DialogContent>
        </Dialog>
    );
}
