import { useState } from 'react';
import { Plus, Save, Trash2 } from 'lucide-react';
import type { SandboxTryOn } from '../../types/api';
import { fmtRub } from '../../lib/format';

/**
 * Ad-hoc «а если трата» (ANO-16 §7): сумма + дата, живёт только в окне.
 * «Сохранить как хотелку» — отдельный жест (создаёт OPEN-событие через onSaveAsWishlist).
 */
export default function AdhocRow({
    items, onAdd, onRemove, onSaveAsWishlist,
}: {
    items: SandboxTryOn[];
    onAdd: (amount: number, date: string) => void;
    onRemove: (index: number) => void;
    onSaveAsWishlist: (amount: number, date: string) => void;
}) {
    const [amount, setAmount] = useState('');
    const [date, setDate] = useState('');

    const canAdd = Number(amount) > 0 && !!date;

    return (
        <div className="rounded-xl px-3 py-2.5"
            style={{ background: 'var(--color-surface)', border: '1px dashed var(--color-border)' }}>
            {items.map((t, i) => (
                <div key={i} className="flex items-center justify-between gap-2 mb-2 text-sm">
                    <span>«а если» {fmtRub(t.amount)} · {t.date.slice(8, 10)}.{t.date.slice(5, 7)}</span>
                    <div className="flex gap-1.5">
                        <button onClick={() => onSaveAsWishlist(t.amount, t.date)}
                            className="p-1.5 rounded-lg hover:bg-white/5"
                            style={{ color: 'var(--color-text-muted)' }}
                            aria-label="Сохранить как хотелку">
                            <Save size={14} />
                        </button>
                        <button onClick={() => onRemove(i)}
                            className="p-1.5 rounded-lg hover:bg-white/5"
                            style={{ color: 'var(--color-danger)' }}
                            aria-label="Убрать">
                            <Trash2 size={14} />
                        </button>
                    </div>
                </div>
            ))}
            <div className="flex items-center gap-2">
                <span className="text-xs shrink-0" style={{ color: 'var(--color-text-muted)' }}>
                    + а если трата
                </span>
                <input type="number" placeholder="сумма" value={amount}
                    onChange={e => setAmount(e.target.value)}
                    className="w-24 px-2 py-1 rounded text-right text-sm"
                    style={{ background: 'var(--color-bg)', border: '1px solid var(--color-border)' }} />
                <input type="date" value={date}
                    onChange={e => setDate(e.target.value)}
                    className="px-2 py-1 rounded text-sm"
                    style={{ background: 'var(--color-bg)', border: '1px solid var(--color-border)' }} />
                <button
                    disabled={!canAdd}
                    onClick={() => { onAdd(Number(amount), date); setAmount(''); setDate(''); }}
                    className="p-1.5 rounded-lg disabled:opacity-40"
                    style={{ background: 'var(--color-primary)', color: 'white' }}
                    aria-label="Добавить примерку">
                    <Plus size={15} />
                </button>
            </div>
        </div>
    );
}
