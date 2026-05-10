import { useState } from 'react';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter } from './ui/dialog';
import { Button } from './ui/button';
import type { ScopeEnum } from '../types/api';

interface Props {
    open: boolean;
    onClose: () => void;
    onConfirm: (scope: ScopeEnum) => void;
}

export default function DeleteRecurringDialog({ open, onClose, onConfirm }: Props) {
    const [scope, setScope] = useState<ScopeEnum>('FOLLOWING');

    return (
        <Dialog open={open} onOpenChange={(o) => !o && onClose()}>
            <DialogContent>
                <DialogHeader>
                    <DialogTitle>Удалить повторяющееся событие?</DialogTitle>
                </DialogHeader>
                <div className="space-y-2 py-2">
                    <label className="flex items-center gap-2 text-sm">
                        <input type="radio" checked={scope === 'THIS'} onChange={() => setScope('THIS')} />
                        Только это
                    </label>
                    <label className="flex items-center gap-2 text-sm">
                        <input type="radio" checked={scope === 'FOLLOWING'} onChange={() => setScope('FOLLOWING')} />
                        Это и все следующие
                    </label>
                    <label className="flex items-center gap-2 text-sm">
                        <input type="radio" checked={scope === 'ALL'} onChange={() => setScope('ALL')} />
                        Все (правило будет удалено)
                    </label>
                    <p className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
                        Исполненные события прошлого не удаляются.
                    </p>
                </div>
                <DialogFooter>
                    <Button variant="ghost" onClick={onClose}>Отмена</Button>
                    <Button onClick={() => onConfirm(scope)} variant="destructive">Удалить</Button>
                </DialogFooter>
            </DialogContent>
        </Dialog>
    );
}
