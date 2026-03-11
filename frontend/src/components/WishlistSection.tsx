import { useEffect, useState, useCallback } from 'react';
import { fetchWishlist, deleteEvent, updateEvent } from '../api';
import type { FinancialEvent } from '../types/api';
import { Button } from './ui/button';
import { Input } from './ui/input';

const fmt = (n: number | null) =>
    n != null
        ? new Intl.NumberFormat('ru-RU', { minimumFractionDigits: 0 }).format(n) + ' ₽'
        : '—';

function formatPeriod(dateStr: string): string {
    const d = new Date(dateStr + 'T00:00:00');
    return d.toLocaleDateString('ru-RU', { month: 'long', year: 'numeric' });
}

export default function WishlistSection() {
    const [items, setItems] = useState<FinancialEvent[]>([]);
    const [loading, setLoading] = useState(true);
    const [reschedulingId, setReschedulingId] = useState<string | null>(null);
    const [newDate, setNewDate] = useState('');

    const load = useCallback(() => {
        setLoading(true);
        fetchWishlist()
            .then(setItems)
            .finally(() => setLoading(false));
    }, []);

    useEffect(() => { load(); }, [load]);

    const handleDelete = async (id: string) => {
        await deleteEvent(id);
        load();
    };

    const handleReschedule = async (event: FinancialEvent) => {
        if (!newDate) return;
        await updateEvent(event.id, {
            date: newDate,
            categoryId: event.categoryId,
            type: event.type,
            plannedAmount: event.plannedAmount ?? undefined,
            priority: event.priority,
            description: event.description ?? undefined,
        });
        setReschedulingId(null);
        setNewDate('');
        load();
    };

    if (loading) return (
        <div className="text-sm text-center animate-pulse py-4" style={{ color: 'var(--color-text-muted)' }}>
            Загрузка хотелок...
        </div>
    );

    return (
        <div className="space-y-3">
            <h2 className="font-semibold">Хотелки</h2>
            {items.length === 0 ? (
                <div className="rounded-2xl px-5 py-4 text-sm"
                    style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)', color: 'var(--color-text-muted)' }}>
                    Нереализованных хотелок нет
                </div>
            ) : (
                <div className="rounded-2xl overflow-hidden"
                    style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}>
                    {items.map((item, idx) => (
                        <div key={item.id}
                            className="px-5 py-3 space-y-2"
                            style={idx < items.length - 1 ? { borderBottom: '1px solid var(--color-border)' } : undefined}>
                            <div className="flex items-start justify-between gap-3">
                                <div className="flex-1 min-w-0">
                                    <div className="font-medium text-sm">{item.categoryName}</div>
                                    {item.description && (
                                        <div className="text-xs truncate" style={{ color: 'var(--color-text-muted)' }}>
                                            {item.description}
                                        </div>
                                    )}
                                    <div className="text-xs mt-0.5" style={{ color: 'var(--color-text-muted)' }}>
                                        план {fmt(item.plannedAmount)} · {formatPeriod(item.date)}
                                    </div>
                                </div>
                                <div className="flex items-center gap-1.5 shrink-0">
                                    <Button
                                        size="sm"
                                        variant="outline"
                                        className="text-xs h-7 px-2"
                                        onClick={() => {
                                            setReschedulingId(reschedulingId === item.id ? null : item.id);
                                            setNewDate('');
                                        }}>
                                        Запланировать
                                    </Button>
                                    <Button
                                        size="sm"
                                        variant="ghost"
                                        className="text-xs h-7 w-7 p-0"
                                        style={{ color: 'var(--color-text-muted)' }}
                                        onClick={() => handleDelete(item.id)}>
                                        ×
                                    </Button>
                                </div>
                            </div>
                            {reschedulingId === item.id && (
                                <div className="flex gap-2 items-center">
                                    <Input
                                        type="date"
                                        value={newDate}
                                        onChange={e => setNewDate(e.target.value)}
                                        className="h-7 text-xs flex-1"
                                    />
                                    <Button
                                        size="sm"
                                        className="h-7 text-xs px-3"
                                        disabled={!newDate}
                                        onClick={() => handleReschedule(item)}>
                                        ОК
                                    </Button>
                                </div>
                            )}
                        </div>
                    ))}
                </div>
            )}
        </div>
    );
}
