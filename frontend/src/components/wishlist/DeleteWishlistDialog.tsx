import { useEffect, useState } from 'react';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter } from '../ui/dialog';
import { Button } from '../ui/button';
import FundMoneyQuestion from '../FundMoneyQuestion';
import type { FundMoney, WishlistItem } from '../../types/api';

interface Props {
    open: boolean;
    item: WishlistItem;
    /** Идёт удаление: кнопки заняты, закрыть нельзя (ANO-141). */
    busy: boolean;
    /** Отказ последнего удаления словами экрана; `null` — отказа не было. */
    error: string | null;
    /** Сервер отказал удалить копилку с деньгами — спросить, что с ними (ANO-198). */
    asksMoney: boolean;
    onClose: () => void;
    /** Удалить; `money` — ответ на вопрос «что с деньгами», уходит удаляемым копилкам. */
    onConfirm: (alsoDeleteArtifact: boolean, money?: FundMoney) => void;
}

/**
 * Подтверждение удаления item'а. Если item уже сконвертирован (convertedTo != null),
 * предлагает чекбоксом удалить также созданный артефакт (план/копилку).
 * Отказ удаления остаётся в диалоге строкой; копилка с деньгами — вопросом, что с ними (ANO-198):
 * ответ повторяет удаление с той же галочкой.
 */
export default function DeleteWishlistDialog({ open, item, busy, error, asksMoney, onClose, onConfirm }: Props) {
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
                {asksMoney ? (
                    <div className="space-y-2 py-2">
                        <FundMoneyQuestion
                            busy={busy}
                            onAnswer={money => onConfirm(alsoArtifact, money)}
                            onKeep={onClose} />
                        {error && (
                            <p className="text-sm" style={{ color: 'var(--color-warning)' }}>{error}</p>
                        )}
                    </div>
                ) : (
                    <>
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
                    </>
                )}
            </DialogContent>
        </Dialog>
    );
}
