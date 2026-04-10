import { useEffect, useState, useCallback } from 'react';
import { Plus } from 'lucide-react';
import { fetchWishlist, createWishlistItem } from '../api';
import type { FinancialEvent, WishlistCreateDto } from '../types/api';
import WishlistItem from './WishlistItem';
import WishlistForm from './WishlistForm';

export default function WishlistSection() {
    const [items, setItems] = useState<FinancialEvent[]>([]);
    const [loading, setLoading] = useState(true);
    const [showAddForm, setShowAddForm] = useState(false);
    const [isOpen, setIsOpen] = useState(true);

    const load = useCallback(() => {
        setLoading(true);
        fetchWishlist()
            .then(setItems)
            .finally(() => setLoading(false));
    }, []);

    useEffect(() => { load(); }, [load]);

    async function handleAdd(dto: WishlistCreateDto) {
        await createWishlistItem(dto);
        setShowAddForm(false);
        await load();
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
                <button onClick={() => setIsOpen(v => !v)}
                    className="flex items-center gap-2 font-semibold text-sm flex-1 text-left">
                    <span>Хотелки</span>
                    <span style={{ color: 'var(--color-text-muted)', fontSize: '12px' }}>
                        {isOpen ? '▲' : '▼'}
                    </span>
                </button>
                {isOpen && (
                    <button onClick={() => setShowAddForm(v => !v)}
                        className="w-6 h-6 rounded flex items-center justify-center transition-colors"
                        style={{ color: 'var(--color-text-muted)' }} title="Добавить хотелку">
                        <Plus size={14} />
                    </button>
                )}
            </div>
            {isOpen && (
                <div className="px-5 pb-4 space-y-3">
                    {showAddForm && (
                        <WishlistForm
                            onSubmit={handleAdd}
                            onCancel={() => setShowAddForm(false)}
                        />
                    )}
                    {items.length === 0 ? (
                        <div className="py-2 text-sm" style={{ color: 'var(--color-text-muted)' }}>
                            Нереализованных хотелок нет
                        </div>
                    ) : (
                        <div className="divide-y" style={{ borderColor: 'var(--color-border)' }}>
                            {items.map(item => (
                                <WishlistItem key={item.id} item={item} onReload={load} />
                            ))}
                        </div>
                    )}
                </div>
            )}
        </div>
    );
}
