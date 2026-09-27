import { useEffect, useState } from 'react';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter } from '../ui/dialog';
import { Button } from '../ui/button';
import type { WishlistItem } from '../../types/api';

interface Props {
    open: boolean;
    item: WishlistItem;
    /** Идёт удаление: кнопки заняты, закрыть нельзя (ANO-141). */
    busy: boolean;
    /** Отказ последнего удаления словами экрана; `null` — отказа не было. */
    error: string | null;
    onClose: () => void;
    onConfirm: (alsoDeleteArtifact: boolean) => void;
}

/**
 * Подтверждение удаления item'а. Если item уже сконвертирован (convertedTo != null),
 * предлагает чекбоксом удалить также созданный артефакт (план/копилку).
 * Отказ удаления остаётся в диалоге строкой.
 */
export default function DeleteWishlistDialog({ open, item, busy, error, onClose, onConfirm }: Props) {
    const [alsoArtifact, setAlsoArtifact] = useState(false);
    const hasArtifact = item.convertedTo != null;

    useEffect(() => {
        if (open) setAlsoArtifact(false);
    }, [open, item.id]);

    return (
        <Dialog open={open} onOpenChange={o => !o && !busy && onClose()}>
            <DialogContent>
                <DialogHeader>
                    <DialogTitle>Удалить «{item.name}»?</DialogTitle>
                </DialogHeader>
                <div className="space-y-2 py-2">
                    {hasArtifact && (
                        <label className="flex items-center gap-2 text-sm">
                            <input
                                type="checkbox"
                                checked={alsoArtifact}
                                onChange={e => setAlsoArtifact(e.target.checked)}
                            />
                            Удалить также созданный план/копилку
                        </label>
                    )}
                    <p className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
                        Действие необратимо.
                    </p>
                    {error && (
                        <p className="text-sm" style={{ color: 'var(--color-warning)' }}>{error}</p>
                    )}
                </div>
                <DialogFooter>
                    <Button variant="ghost" disabled={busy} onClick={onClose}>Отмена</Button>
                    <Button variant="destructive" disabled={busy} onClick={() => onConfirm(alsoArtifact)}>
                        Удалить
                    </Button>
                </DialogFooter>
            </DialogContent>
        </Dialog>
    );
}
