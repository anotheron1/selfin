import { useState } from 'react';
import { Sheet, SheetContent, SheetHeader, SheetTitle } from './ui/sheet';
import { Input } from './ui/input';
import { Button } from './ui/button';
import { createLinkedFact } from '../api';
import type { FactCreateDto } from '../types/api';

interface Props {
    planId: string;
    planDescription: string;
    open: boolean;
    onClose: () => void;
    onCreated: () => void;
}

export default function FactCreateSheet({ planId, planDescription, open, onClose, onCreated }: Props) {
    const today = new Date().toISOString().slice(0, 10);
    const [date, setDate] = useState(today);
    const [amount, setAmount] = useState('');
    const [description, setDescription] = useState('');
    const [loading, setLoading] = useState(false);

    async function handleSubmit(e: React.FormEvent) {
        e.preventDefault();
        if (!amount) return;
        setLoading(true);
        try {
            const dto: FactCreateDto = {
                date,
                factAmount: parseFloat(amount),
                description: description || undefined,
            };
            await createLinkedFact(planId, dto);
            onCreated();
            onClose();
        } catch (err) {
            console.error(err);
        } finally {
            setLoading(false);
        }
    }

    return (
        <Sheet open={open} onOpenChange={(open) => !open && onClose()}>
            <SheetContent side="bottom" className="max-w-2xl mx-auto rounded-t-2xl">
                <SheetHeader>
                    <SheetTitle>Записать факт</SheetTitle>
                    <p className="text-sm" style={{ color: 'var(--color-text-muted)' }}>{planDescription}</p>
                </SheetHeader>
                <form onSubmit={handleSubmit} className="space-y-3 mt-4">
                    <div>
                        <label className="text-xs text-muted-foreground block mb-1">Дата</label>
                        <Input type="date" value={date} onChange={e => setDate(e.target.value)} required />
                    </div>
                    <div>
                        <label className="text-xs text-muted-foreground block mb-1">Фактическая сумма, ₽</label>
                        <Input
                            type="number"
                            min="0.01"
                            step="0.01"
                            placeholder="0"
                            value={amount}
                            onChange={e => setAmount(e.target.value)}
                            required
                        />
                    </div>
                    <div>
                        <label className="text-xs text-muted-foreground block mb-1">Комментарий</label>
                        <Input
                            placeholder="Необязательно..."
                            value={description}
                            onChange={e => setDescription(e.target.value)}
                        />
                    </div>
                    <Button type="submit" className="w-full" disabled={loading}>
                        {loading ? 'Сохраняю...' : 'Сохранить факт'}
                    </Button>
                </form>
            </SheetContent>
        </Sheet>
    );
}
