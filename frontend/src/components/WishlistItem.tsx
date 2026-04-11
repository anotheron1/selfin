import { useState } from 'react';
import { ExternalLink, Pencil } from 'lucide-react';
import type { FinancialEvent, WishlistCreateDto } from '../types/api';
import { Button } from './ui/button';
import { Input } from './ui/input';
import { updateEvent, deleteEvent } from '../api';
import WishlistForm from './WishlistForm';

interface WishlistItemProps {
    item: FinancialEvent;
    onReload: () => void;
}

const fmt = (n: number | null) =>
    n != null
        ? new Intl.NumberFormat('ru-RU', { minimumFractionDigits: 0 }).format(n) + ' ₽'
        : '—';

function formatPeriod(dateStr: string): string {
    const d = new Date(dateStr + 'T00:00:00');
    return d.toLocaleDateString('ru-RU', { month: 'long', year: 'numeric' });
}

export default function WishlistItem({ item, onReload }: WishlistItemProps) {
    const [rescheduling, setRescheduling] = useState(false);
    const [newDate, setNewDate] = useState('');
    const [editing, setEditing] = useState(false);

    async function handleDelete() {
        await deleteEvent(item.id);
        onReload();
    }

    async function handleReschedule() {
        if (!newDate) return;
        await updateEvent(item.id, {
            date: newDate,
            categoryId: item.categoryId,
            type: item.type,
            plannedAmount: item.plannedAmount ?? undefined,
            priority: item.priority,
            description: item.description ?? undefined,
        });
        setRescheduling(false);
        setNewDate('');
        onReload();
    }

    async function handleEditSubmit(dto: WishlistCreateDto) {
        const today = new Date().toISOString().slice(0, 10);
        await updateEvent(item.id, {
            date: item.date || today,
            categoryId: item.categoryId,
            type: item.type,
            plannedAmount: dto.plannedAmount ?? undefined,
            priority: item.priority,
            description: dto.description,
        });
        setEditing(false);
        onReload();
    }

    return (
        <div className="px-5 py-3 space-y-2" style={{ borderBottom: '1px solid var(--color-border)' }}>
            <div className="flex items-start justify-between gap-3">
                <div className="flex-1 min-w-0">
                    <div className="font-medium text-sm">
                        {item.description || item.categoryName || 'Без названия'}
                    </div>
                    {item.description && (
                        <div className="text-xs truncate" style={{ color: 'var(--color-text-muted)' }}>
                            {item.categoryName}
                        </div>
                    )}
                    <div className="text-xs mt-0.5 flex items-center" style={{ color: 'var(--color-text-muted)' }}>
                        план {fmt(item.plannedAmount)}{item.date ? ` · ${formatPeriod(item.date)}` : ''}
                        {item.url && (
                            <a href={item.url} target="_blank" rel="noreferrer"
                                className="ml-1 inline-flex items-center"
                                style={{ color: 'var(--color-text-muted)' }}
                                onClick={e => e.stopPropagation()}>
                                <ExternalLink size={11} />
                            </a>
                        )}
                    </div>
                </div>
                <div className="flex items-center gap-1.5 shrink-0">
                    <Button size="sm" variant="outline" className="text-xs h-7 px-2"
                        onClick={() => { setRescheduling(v => !v); setNewDate(''); }}>
                        Запланировать
                    </Button>
                    <Button size="sm" variant="ghost" className="h-7 w-7 p-0"
                        style={{ color: 'var(--color-text-muted)' }}
                        title="Редактировать"
                        onClick={() => setEditing(v => !v)}>
                        <Pencil size={13} />
                    </Button>
                    <Button size="sm" variant="ghost" className="text-xs h-7 w-7 p-0"
                        style={{ color: 'var(--color-text-muted)' }}
                        onClick={handleDelete}>
                        ×
                    </Button>
                </div>
            </div>
            {rescheduling && (
                <div className="flex gap-2 items-center">
                    <Input type="date" value={newDate} onChange={e => setNewDate(e.target.value)}
                        className="h-7 text-xs flex-1" />
                    <Button size="sm" className="h-7 text-xs px-3" disabled={!newDate}
                        onClick={handleReschedule}>ОК</Button>
                </div>
            )}
            {editing && (
                <WishlistForm
                    initialValues={{
                        description: item.description ?? '',
                        plannedAmount: item.plannedAmount ?? undefined,
                        url: item.url ?? '',
                    }}
                    onSubmit={handleEditSubmit}
                    onCancel={() => setEditing(false)}
                />
            )}
        </div>
    );
}
