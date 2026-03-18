import { useEffect, useState, useCallback } from 'react';
import { ExternalLink, Pencil, Plus } from 'lucide-react';
import { fetchWishlist, deleteEvent, updateEvent, createWishlistItem } from '../api';
import type { FinancialEvent, WishlistCreateDto } from '../types/api';
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
    const [showAddForm, setShowAddForm] = useState(false);
    const [addForm, setAddForm] = useState<{ description: string; plannedAmount: string; url: string }>({
        description: '',
        plannedAmount: '',
        url: '',
    });
    const [submitting, setSubmitting] = useState(false);
    const [addError, setAddError] = useState<string | null>(null);
    const [editingId, setEditingId] = useState<string | null>(null);
    const [editForm, setEditForm] = useState<{ description: string; plannedAmount: string; url: string; date: string }>({
        description: '',
        plannedAmount: '',
        url: '',
        date: '',
    });
    const [editSubmitting, setEditSubmitting] = useState(false);
    const [editError, setEditError] = useState<string | null>(null);
    const [isOpen, setIsOpen] = useState(true);

    const load = useCallback(() => {
        setLoading(true);
        fetchWishlist()
            .then(data => setItems(data.filter(e => e.status === 'PLANNED')))
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

    async function handleAddSubmit(e: React.FormEvent) {
        e.preventDefault();
        if (!addForm.description.trim()) return;
        setSubmitting(true);
        setAddError(null);
        try {
            const dto: WishlistCreateDto = {
                description: addForm.description.trim(),
                plannedAmount: addForm.plannedAmount ? Number(addForm.plannedAmount) : null,
                url: addForm.url.trim() || null,
            };
            await createWishlistItem(dto);
            setAddForm({ description: '', plannedAmount: '', url: '' });
            setShowAddForm(false);
            await load();
        } catch {
            setAddError('Не удалось добавить. Попробуйте ещё раз.');
        } finally {
            setSubmitting(false);
        }
    }

    const handleEditOpen = (item: FinancialEvent) => {
        setEditingId(item.id);
        setEditForm({
            description: item.description ?? '',
            plannedAmount: item.plannedAmount != null ? String(item.plannedAmount) : '',
            url: item.url ?? '',
            date: item.date ?? '',
        });
        setEditError(null);
        // Close reschedule panel if open for this item
        if (reschedulingId === item.id) {
            setReschedulingId(null);
            setNewDate('');
        }
    };

    const handleEditCancel = () => {
        setEditingId(null);
        setEditError(null);
    };

    async function handleEditSubmit(e: React.FormEvent, item: FinancialEvent) {
        e.preventDefault();
        if (!editForm.description.trim()) return;
        setEditSubmitting(true);
        setEditError(null);
        try {
            // date is @NotNull on backend — fall back to today if cleared
            const today = new Date().toISOString().slice(0, 10);
            await updateEvent(item.id, {
                date: editForm.date || today,
                categoryId: item.categoryId,
                type: item.type,
                plannedAmount: editForm.plannedAmount ? parseFloat(editForm.plannedAmount) : undefined,
                priority: item.priority,
                description: editForm.description.trim() || undefined,
            });
            setEditingId(null);
            await load();
        } catch {
            setEditError('Не удалось сохранить. Попробуйте ещё раз.');
        } finally {
            setEditSubmitting(false);
        }
    }

    if (loading) return (
        <div className="text-sm text-center animate-pulse py-4" style={{ color: 'var(--color-text-muted)' }}>
            Загрузка хотелок...
        </div>
    );

    return (
        <div className="rounded-2xl overflow-hidden"
            style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}>
            <div className="flex items-center justify-between px-5 py-3">
                <button
                    onClick={() => setIsOpen(v => !v)}
                    className="flex items-center gap-2 font-semibold text-sm flex-1 text-left">
                    <span>Хотелки</span>
                    <span style={{ color: 'var(--color-text-muted)', fontSize: '12px' }}>{isOpen ? '▲' : '▼'}</span>
                </button>
                {isOpen && (
                    <button
                        onClick={() => setShowAddForm(v => !v)}
                        className="w-6 h-6 rounded flex items-center justify-center transition-colors"
                        style={{ color: 'var(--color-text-muted)' }}
                        title="Добавить хотелку"
                    >
                        <Plus size={14} />
                    </button>
                )}
            </div>
        {isOpen && <div className="px-5 pb-4 space-y-3">
            {showAddForm && (
                <form
                    onSubmit={handleAddSubmit}
                    className="mb-3 flex flex-col gap-1.5 p-2 rounded"
                    style={{ background: 'var(--color-bg)', border: '1px solid var(--color-border)' }}
                >
                    <input
                        type="text"
                        required
                        placeholder="Название *"
                        value={addForm.description}
                        onChange={e => setAddForm(v => ({ ...v, description: e.target.value }))}
                        className="rounded px-2 py-1 text-sm w-full outline-none"
                        style={{
                            background: 'var(--color-surface)',
                            border: '1px solid var(--color-border)',
                            color: 'var(--color-text)',
                        }}
                    />
                    <input
                        type="number"
                        min="0"
                        step="0.01"
                        placeholder="Цена (необязательно)"
                        value={addForm.plannedAmount}
                        onChange={e => setAddForm(v => ({ ...v, plannedAmount: e.target.value }))}
                        className="rounded px-2 py-1 text-sm w-full outline-none"
                        style={{
                            background: 'var(--color-surface)',
                            border: '1px solid var(--color-border)',
                            color: 'var(--color-text)',
                        }}
                    />
                    <input
                        type="url"
                        placeholder="Ссылка (необязательно)"
                        value={addForm.url}
                        onChange={e => setAddForm(v => ({ ...v, url: e.target.value }))}
                        className="rounded px-2 py-1 text-sm w-full outline-none"
                        style={{
                            background: 'var(--color-surface)',
                            border: '1px solid var(--color-border)',
                            color: 'var(--color-text)',
                        }}
                    />
                    {addError && (
                        <p className="text-xs" style={{ color: '#ef4444' }}>{addError}</p>
                    )}
                    <div className="flex gap-2 justify-end">
                        <button
                            type="button"
                            onClick={() => setShowAddForm(false)}
                            className="text-xs px-2 py-1 rounded"
                            style={{ color: 'var(--color-text-muted)' }}
                        >
                            Отмена
                        </button>
                        <button
                            type="submit"
                            disabled={submitting}
                            className="text-xs px-3 py-1 rounded"
                            style={{ background: 'var(--color-primary)', color: '#fff' }}
                        >
                            {submitting ? '...' : 'Добавить'}
                        </button>
                    </div>
                </form>
            )}
            {items.length === 0 ? (
                <div className="py-2 text-sm" style={{ color: 'var(--color-text-muted)' }}>
                    Нереализованных хотелок нет
                </div>
            ) : (
                <div className="divide-y" style={{ borderColor: 'var(--color-border)' }}>
                    {items.map((item, idx) => (
                        <div key={item.id}
                            className="px-5 py-3 space-y-2"
                            style={idx < items.length - 1 ? { borderBottom: '1px solid var(--color-border)' } : undefined}>
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
                                            <a
                                                href={item.url}
                                                target="_blank"
                                                rel="noreferrer"
                                                className="ml-1 inline-flex items-center"
                                                style={{ color: 'var(--color-text-muted)' }}
                                                title={item.url}
                                                onClick={e => e.stopPropagation()}
                                            >
                                                <ExternalLink size={11} />
                                            </a>
                                        )}
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
                                        className="h-7 w-7 p-0"
                                        style={{ color: 'var(--color-text-muted)' }}
                                        title="Редактировать"
                                        onClick={() => handleEditOpen(item)}>
                                        <Pencil size={13} />
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
                            {editingId === item.id && (
                                <form
                                    onSubmit={e => handleEditSubmit(e, item)}
                                    className="flex flex-col gap-1.5 p-2 rounded"
                                    style={{ background: 'var(--color-bg)', border: '1px solid var(--color-border)' }}
                                >
                                    <input
                                        type="text"
                                        required
                                        placeholder="Название *"
                                        value={editForm.description}
                                        onChange={e => setEditForm(v => ({ ...v, description: e.target.value }))}
                                        className="rounded px-2 py-1 text-sm w-full outline-none"
                                        style={{
                                            background: 'var(--color-surface)',
                                            border: '1px solid var(--color-border)',
                                            color: 'var(--color-text)',
                                        }}
                                    />
                                    <input
                                        type="number"
                                        min="0"
                                        step="0.01"
                                        placeholder="Цена (необязательно)"
                                        value={editForm.plannedAmount}
                                        onChange={e => setEditForm(v => ({ ...v, plannedAmount: e.target.value }))}
                                        className="rounded px-2 py-1 text-sm w-full outline-none"
                                        style={{
                                            background: 'var(--color-surface)',
                                            border: '1px solid var(--color-border)',
                                            color: 'var(--color-text)',
                                        }}
                                    />
                                    <input
                                        type="url"
                                        placeholder="Ссылка (необязательно)"
                                        value={editForm.url}
                                        onChange={e => setEditForm(v => ({ ...v, url: e.target.value }))}
                                        className="rounded px-2 py-1 text-sm w-full outline-none"
                                        style={{
                                            background: 'var(--color-surface)',
                                            border: '1px solid var(--color-border)',
                                            color: 'var(--color-text)',
                                        }}
                                    />
                                    <input
                                        type="date"
                                        placeholder="Дата (необязательно)"
                                        value={editForm.date}
                                        onChange={e => setEditForm(v => ({ ...v, date: e.target.value }))}
                                        className="rounded px-2 py-1 text-sm w-full outline-none"
                                        style={{
                                            background: 'var(--color-surface)',
                                            border: '1px solid var(--color-border)',
                                            color: 'var(--color-text)',
                                        }}
                                    />
                                    {editError && (
                                        <p className="text-xs" style={{ color: '#ef4444' }}>{editError}</p>
                                    )}
                                    <div className="flex gap-2 justify-end">
                                        <button
                                            type="button"
                                            onClick={handleEditCancel}
                                            className="text-xs px-2 py-1 rounded"
                                            style={{ color: 'var(--color-text-muted)' }}
                                        >
                                            Отмена
                                        </button>
                                        <button
                                            type="submit"
                                            disabled={editSubmitting}
                                            className="text-xs px-3 py-1 rounded"
                                            style={{ background: 'var(--color-primary)', color: '#fff' }}
                                        >
                                            {editSubmitting ? '...' : 'Сохранить'}
                                        </button>
                                    </div>
                                </form>
                            )}
                        </div>
                    ))}
                </div>
            )}
        </div>}
        </div>
    );
}
