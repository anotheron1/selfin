import { useState } from 'react';
import { updateEvent } from '../api';
import type { FinancialEvent, FinancialEventCreateDto } from '../types/api';
import { Sheet, SheetContent, SheetHeader, SheetTitle, SheetDescription } from './ui/sheet';
import { Input } from './ui/input';
import { Button } from './ui/button';

interface EditEventSheetProps {
    event: FinancialEvent;
    scope?: 'THIS' | 'THIS_AND_FOLLOWING';
    onClose: () => void;
    onSuccess: () => void;
}

export default function EditEventSheet({ event, scope, onClose, onSuccess }: EditEventSheetProps) {
    const [factAmount, setFactAmount] = useState<string>(
        event.factAmount != null ? String(event.factAmount) : ''
    );
    const [description, setDescription] = useState(event.description ?? '');
    const [loading, setLoading] = useState(false);

    const handleSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        setLoading(true);
        try {
            const dto: FinancialEventCreateDto = {
                date: event.date,
                categoryId: event.categoryId,
                type: event.type,
                plannedAmount: event.plannedAmount ?? undefined,
                factAmount: factAmount !== '' ? Number(factAmount) : undefined,
                mandatory: event.mandatory,
                description: description || undefined,
                rawInput: event.rawInput ?? undefined,
            };
            await updateEvent(event.id, dto, scope ?? 'THIS');
            onSuccess();
            onClose();
        } catch (err) {
            console.error(err);
        } finally {
            setLoading(false);
        }
    };

    const handleDelete = async () => {
        if (!confirm('Удалить запись?')) return;
        const { deleteEvent } = await import('../api');
        setLoading(true);
        try {
            await deleteEvent(event.id, scope ?? 'THIS');
            onSuccess();
            onClose();
        } finally {
            setLoading(false);
        }
    };

    const fmt = (n: number | null) =>
        n != null ? new Intl.NumberFormat('ru-RU').format(n) + ' ₽' : '—';

    return (
        <Sheet open onOpenChange={(open) => !open && onClose()}>
            <SheetContent side="bottom" className="max-w-2xl mx-auto rounded-t-2xl">
                <SheetHeader>
                    <SheetTitle>{event.categoryName}</SheetTitle>
                    <SheetDescription className="text-xs">
                        {new Date(event.date).toLocaleDateString('ru-RU', { day: 'numeric', month: 'long' })}
                        {' · '}План: {fmt(event.plannedAmount)}
                    </SheetDescription>
                </SheetHeader>

                <form onSubmit={handleSubmit} className="space-y-3 mt-4">
                    <div>
                        <label className="text-xs text-muted-foreground block mb-1">
                            Фактическая сумма, ₽
                        </label>
                        <Input
                            type="number"
                            placeholder={`План: ${event.plannedAmount ?? '—'}`}
                            value={factAmount}
                            onChange={e => setFactAmount(e.target.value)}
                        />
                    </div>
                    <div>
                        <label className="text-xs text-muted-foreground block mb-1">
                            Комментарий
                        </label>
                        <Input
                            placeholder="Добавить заметку..."
                            value={description}
                            onChange={e => setDescription(e.target.value)}
                        />
                    </div>
                    <div className="flex gap-2 pt-1">
                        <Button
                            type="button"
                            variant="destructive"
                            className="flex-1 bg-destructive/10 text-destructive hover:bg-destructive/20"
                            onClick={handleDelete}
                            disabled={loading}
                        >
                            Удалить
                        </Button>
                        <Button
                            type="submit"
                            className="flex-1"
                            disabled={loading}
                        >
                            {loading ? 'Сохраняем...' : 'Сохранить факт'}
                        </Button>
                    </div>
                </form>
            </SheetContent>
        </Sheet>
    );
}
