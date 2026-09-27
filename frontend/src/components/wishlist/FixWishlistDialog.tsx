import { useEffect, useState } from 'react';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter } from '../ui/dialog';
import { Button } from '../ui/button';
import { Input } from '../ui/input';
import type { WishlistItem } from '../../types/api';
import { canConfirmConversion, conversionChoice, type ConvertTarget } from './wishlistUtils';

export type { ConvertTarget };

interface Props {
    open: boolean;
    /** Ставка и срок — те, что уйдут в запись (подкрученные): по ним решается пункт «Кредит». */
    item: WishlistItem;
    /** Идёт запись: кнопки заняты, закрыть нельзя (ANO-141). */
    busy: boolean;
    /** Отказ последней записи словами экрана; `null` — отказа не было. */
    error: string | null;
    onClose: () => void;
    /** Зафиксировать с конверсией в выбранный артефакт. */
    onConfirm: (target: ConvertTarget, createRecurringPayments: boolean, planDate?: string) => void;
    /** Зафиксировать без конверсии (статус FIXED, артефакт не создаётся). */
    onFixWithoutConversion: () => void;
}

const TARGET_LABEL: Record<ConvertTarget, string> = {
    PLAN_EVENT: 'Плановое событие',
    FUND: 'Копилка',
    FUND_WITH_CREDIT: 'Кредит (копилка + график платежей)',
};

/**
 * Диалог фиксации хотелки: выбор цели конверсии — только той, что примет сервер (ANO-141),
 * для FUND_WITH_CREDIT — чекбокс «создать платёжный график», и отдельное
 * действие «Зафиксировать без конверсии». Отказ записи остаётся в диалоге строкой.
 */
export default function FixWishlistDialog({ open, item, busy, error, onClose, onConfirm, onFixWithoutConversion }: Props) {
    const choice = conversionChoice(item.kind, item.rate, item.termMonths);
    const [target, setTarget] = useState<ConvertTarget>(choice.initial);
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
            setTarget(choice.initial);
            setCreateRecurring(true);
            setPlanDate(item.targetDate ?? '');
        }
    }, [open, item.id, item.kind, item.targetDate, choice.initial]);

    return (
        <Dialog open={open} onOpenChange={o => !o && !busy && onClose()}>
            <DialogContent>
                <DialogHeader>
                    <DialogTitle>Зафиксировать: {item.name}</DialogTitle>
                </DialogHeader>
                <div className="space-y-2 py-2">
                    <p className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
                        Во что превратить решение:
                    </p>
                    {choice.targets.map(t => (
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
                    {/* Спрятанный пункт без объяснения — стена (довод из Funds.tsx). */}
                    {choice.creditNeedsParams && (
                        <p className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
                            Для кредита нужны ставка и срок — они в «Параметрах кредита» на карточке.
                        </p>
                    )}
                    {error && (
                        <p className="text-sm" style={{ color: 'var(--color-warning)' }}>{error}</p>
                    )}
                </div>
                <DialogFooter className="flex-col sm:flex-row gap-2">
                    <Button variant="ghost" disabled={busy} onClick={onFixWithoutConversion}>
                        Зафиксировать без конверсии
                    </Button>
                    <Button
                        disabled={busy || !canConfirmConversion(target, planDate, todayIso)}
                        onClick={() => onConfirm(target, createRecurring, planDate || undefined)}
                    >
                        Зафиксировать
                    </Button>
                </DialogFooter>
            </DialogContent>
        </Dialog>
    );
}
