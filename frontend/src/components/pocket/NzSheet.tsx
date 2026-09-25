import { Dialog, DialogContent, DialogHeader, DialogTitle } from '../ui/dialog';
import NzForm from './NzForm';

/**
 * Шторка НЗ с карточки кармашка (ANO-92): открывается из «почему столько», как шторка остатка —
 * со строки «на счёте». Форма монтируется заново при каждом открытии и берёт свежий НЗ.
 */
export default function NzSheet({ open, onOpenChange, buffer, onSuccess }: {
    open: boolean;
    onOpenChange: (v: boolean) => void;
    /** НЗ из ответа кармашка; 0 — не задан. */
    buffer: number;
    onSuccess: () => void;
}) {
    return (
        <Dialog open={open} onOpenChange={onOpenChange}>
            <DialogContent>
                <DialogHeader>
                    <DialogTitle>НЗ — неприкосновенный запас</DialogTitle>
                </DialogHeader>
                {open && (
                    <NzForm
                        autoFocus
                        initial={buffer}
                        onSaved={() => { onOpenChange(false); onSuccess(); }}
                    />
                )}
            </DialogContent>
        </Dialog>
    );
}
