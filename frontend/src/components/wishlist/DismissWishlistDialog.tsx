import { useEffect, useState } from 'react';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter } from '../ui/dialog';
import { Button } from '../ui/button';
import type { WishlistItem } from '../../types/api';
import type { DismissQuestion } from './wishlistUtils';

interface Props {
    open: boolean;
    item: WishlistItem;
    /** Строки вопроса — `dismissQuestion(item)`: что останется в расчёте и как предложить удалить. */
    question: DismissQuestion;
    /** Идёт запись: кнопки заняты, закрыть нельзя. */
    busy: boolean;
    /** Отказ последней записи словами экрана; `null` — отказа не было. */
    error: string | null;
    onClose: () => void;
    onConfirm: (alsoArtifact: boolean) => void;
}

/**
 * «Отложить» у хотелки, из которой создан план или копилка (ANO-210). Устроен как удаление хотелки
 * (`DeleteWishlistDialog`): созданное остаётся в расчёте, а удалить его — явный выбор, галочка
 * выключена при каждом открытии. Отказ остаётся в диалоге строкой.
 */
export default function DismissWishlistDialog({ open, item, question, busy, error, onClose, onConfirm }: Props) {
    const [alsoArtifact, setAlsoArtifact] = useState(false);

    useEffect(() => {
        if (open) setAlsoArtifact(false);
    }, [open, item.id]);

    return (
        <Dialog open={open} onOpenChange={o => !o && !busy && onClose()}>
            <DialogContent>
                <DialogHeader>
                    <DialogTitle>Отложить «{item.name}»?</DialogTitle>
                </DialogHeader>
                <div className="space-y-2 py-2">
                    <p className="text-sm">{question.stays}</p>
                    <label className="flex items-center gap-2 text-sm">
                        <input
                            type="checkbox"
                            checked={alsoArtifact}
                            onChange={e => setAlsoArtifact(e.target.checked)}
                        />
                        {question.also}
                    </label>
                    {error && (
                        <p className="text-sm" style={{ color: 'var(--color-warning)' }}>{error}</p>
                    )}
                </div>
                <DialogFooter>
                    <Button variant="ghost" disabled={busy} onClick={onClose}>Отмена</Button>
                    <Button disabled={busy} onClick={() => onConfirm(alsoArtifact)}>
                        Отложить
                    </Button>
                </DialogFooter>
            </DialogContent>
        </Dialog>
    );
}
