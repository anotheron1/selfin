import { useState } from 'react';
import { Plus, ChevronDown } from 'lucide-react';
import type { CapitalItem, CapitalItemKind } from '../types/api';
import { Button } from './ui/button';

const fmt = (n: number) =>
    new Intl.NumberFormat('ru-RU', { style: 'currency', currency: 'RUB', minimumFractionDigits: 0 }).format(n);

const fmtCompact = (n: number) => {
    if (n >= 1_000_000) return (n / 1_000_000).toFixed(2).replace(/\.?0+$/, '') + 'М ₽';
    if (n >= 1_000) return Math.round(n / 1000) + 'к ₽';
    return fmt(n);
};

interface Props {
    kind: CapitalItemKind;
    items: CapitalItem[];
    onItemClick: (item: CapitalItem) => void;
    onCreate: () => void;
}

export default function CapitalItemList({ kind, items, onItemClick, onCreate }: Props) {
    const active = items.filter(i => !i.isArchived);
    const archived = items.filter(i => i.isArchived);
    const total = active.reduce((s, i) => s + i.currentValue, 0);
    const isAsset = kind === 'ASSET';
    const accent = isAsset ? 'var(--color-success, #7ec699)' : 'var(--color-danger, #e88a8a)';
    const heading = isAsset ? 'АКТИВЫ' : 'ОБЯЗАТЕЛЬСТВА';
    const addLabel = isAsset ? '+ Добавить актив' : '+ Добавить обязательство';

    const [archivedOpen, setArchivedOpen] = useState(false);

    return (
        <div className="rounded-lg p-4"
             style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}>
            <div className="text-xs font-semibold tracking-wide mb-3" style={{ color: accent }}>
                {heading} · {fmtCompact(total)}
            </div>

            <ul className="space-y-1">
                {active.length === 0 && (
                    <li className="text-sm py-2" style={{ color: 'var(--color-text-muted)' }}>
                        Пока пусто
                    </li>
                )}
                {active.map(item => (
                    <li key={item.id}>
                        <button
                            type="button"
                            onClick={() => onItemClick(item)}
                            className="w-full flex justify-between items-center py-2 px-2 rounded hover:bg-white/5 transition text-left">
                            <span className="truncate">{item.name}</span>
                            <span className="font-medium ml-2">{fmt(item.currentValue)}</span>
                        </button>
                    </li>
                ))}
            </ul>

            <Button variant="ghost" size="sm" onClick={onCreate} className="mt-2 w-full justify-start gap-2">
                <Plus size={16} /> {addLabel}
            </Button>

            {archived.length > 0 && (
                <div className="mt-3 border-t pt-2" style={{ borderColor: 'var(--color-border)' }}>
                    <button
                        type="button"
                        onClick={() => setArchivedOpen(o => !o)}
                        className="w-full flex items-center gap-1 text-xs"
                        style={{ color: 'var(--color-text-muted)' }}>
                        <ChevronDown size={14} className={archivedOpen ? '' : '-rotate-90'} />
                        Архивные ({archived.length})
                    </button>
                    {archivedOpen && (
                        <ul className="mt-1 space-y-1">
                            {archived.map(item => (
                                <li key={item.id}>
                                    <button
                                        type="button"
                                        onClick={() => onItemClick(item)}
                                        className="w-full flex justify-between items-center py-1.5 px-2 rounded hover:bg-white/5 text-left text-sm opacity-70">
                                        <span className="truncate">{item.name}</span>
                                        <span>—</span>
                                    </button>
                                </li>
                            ))}
                        </ul>
                    )}
                </div>
            )}
        </div>
    );
}
