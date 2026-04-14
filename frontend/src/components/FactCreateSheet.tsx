import { useState } from 'react';
import { Sheet, SheetContent, SheetHeader, SheetTitle } from './ui/sheet';
import { Input } from './ui/input';
import { Button } from './ui/button';
import { createLinkedFact } from '../api';
import { PRIORITY_DOT_CONFIG, PRIORITY_ORDER } from '../lib/priority';
import type { FactCreateDto, Priority } from '../types/api';

interface Props {
    planId: string;
    planDescription: string;
    planPriority: Priority;
    open: boolean;
    onClose: () => void;
    onCreated: () => void;
}

export default function FactCreateSheet({ planId, planDescription, planPriority, open, onClose, onCreated }: Props) {
    const today = new Date().toISOString().slice(0, 10);
    const [date, setDate] = useState(today);
    const [amount, setAmount] = useState('');
    const [description, setDescription] = useState('');
    const [priority, setPriority] = useState<Priority>(planPriority);
    const [loading, setLoading] = useState(false);

    // Reset all fields to fresh state whenever the sheet opens (e.g. for a different plan)
    function handleOpenChange(isOpen: boolean) {
        if (!isOpen) {
            onClose();
        } else {
            setDate(new Date().toISOString().slice(0, 10));
            setAmount('');
            setDescription('');
            setPriority(planPriority);
        }
    }

    async function handleSubmit(e: React.FormEvent) {
        e.preventDefault();
        if (!amount) return;
        setLoading(true);
        try {
            const dto: FactCreateDto = {
                date,
                factAmount: parseFloat(amount),
                description: description || undefined,
                priority,
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
        <Sheet open={open} onOpenChange={handleOpenChange}>
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
                        <label className="text-xs text-muted-foreground block mb-1">Необходимость</label>
                        <div className="flex items-center gap-3 pt-1">
                            {PRIORITY_ORDER.map(p => (
                                <button
                                    key={p}
                                    type="button"
                                    title={PRIORITY_DOT_CONFIG[p].title}
                                    onClick={() => setPriority(p)}
                                    style={{
                                        display: 'flex',
                                        alignItems: 'center',
                                        gap: 6,
                                        opacity: priority === p ? 1 : 0.35,
                                        cursor: 'pointer',
                                        background: 'none',
                                        border: 'none',
                                        padding: 0,
                                        transition: 'opacity 0.15s',
                                    }}
                                >
                                    <span style={{
                                        display: 'inline-block',
                                        width: 10,
                                        height: 10,
                                        borderRadius: '50%',
                                        backgroundColor: PRIORITY_DOT_CONFIG[p].color,
                                    }} />
                                    <span className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
                                        {PRIORITY_DOT_CONFIG[p].title}
                                    </span>
                                </button>
                            ))}
                        </div>
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
