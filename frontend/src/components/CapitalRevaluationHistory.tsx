import { useState, useEffect } from 'react';
import { Pencil, Trash2 } from 'lucide-react';
import { fetchCapitalHistory, updateCapitalRevaluation, deleteCapitalRevaluation } from '../api';
import type { CapitalRevaluation } from '../types/api';
import { Button } from './ui/button';
import { Input } from './ui/input';

const fmt = (n: number) =>
    new Intl.NumberFormat('ru-RU', { style: 'currency', currency: 'RUB', minimumFractionDigits: 0 }).format(n);

const fmtDate = (iso: string) =>
    new Date(iso).toLocaleDateString('ru-RU', { day: 'numeric', month: 'short', year: 'numeric' });

interface Props {
    itemId: string;
    refreshSignal: number;
    onChanged: () => void;
}

export default function CapitalRevaluationHistory({ itemId, refreshSignal, onChanged }: Props) {
    const [history, setHistory] = useState<CapitalRevaluation[]>([]);
    const [editingId, setEditingId] = useState<string | null>(null);
    const [editValue, setEditValue] = useState('');
    const [editDate, setEditDate] = useState('');

    useEffect(() => {
        fetchCapitalHistory(itemId).then(setHistory).catch(console.error);
    }, [itemId, refreshSignal]);

    const startEdit = (r: CapitalRevaluation) => {
        setEditingId(r.id);
        setEditValue(String(r.value));
        setEditDate(r.valuedAt);
    };

    const saveEdit = async () => {
        if (!editingId) return;
        await updateCapitalRevaluation(editingId, {
            value: Number(editValue),
            valuedAt: editDate,
        });
        setEditingId(null);
        onChanged();
    };

    const remove = async (id: string) => {
        if (!confirm('Удалить эту запись из истории?')) return;
        await deleteCapitalRevaluation(id);
        onChanged();
    };

    if (history.length === 0) {
        return <div className="text-xs py-2" style={{ color: 'var(--color-text-muted)' }}>История пуста</div>;
    }

    return (
        <ul className="space-y-1">
            {history.map(r => (
                <li key={r.id} className="text-sm py-2 px-2 rounded hover:bg-white/5">
                    {editingId === r.id ? (
                        <div className="flex gap-1 items-center">
                            <Input value={editValue} onChange={e => setEditValue(e.target.value)} type="number" className="w-32 h-7" />
                            <Input value={editDate} onChange={e => setEditDate(e.target.value)} type="date" className="w-36 h-7" />
                            <Button size="sm" onClick={saveEdit}>OK</Button>
                            <Button size="sm" variant="ghost" onClick={() => setEditingId(null)}>×</Button>
                        </div>
                    ) : (
                        <div className="flex items-center justify-between">
                            <div className="flex flex-col">
                                <span>{fmtDate(r.valuedAt)} · {fmt(r.value)}</span>
                                {r.note && <span className="text-xs" style={{ color: 'var(--color-text-muted)' }}>{r.note}</span>}
                            </div>
                            <div className="flex gap-1">
                                <Button size="sm" variant="ghost" onClick={() => startEdit(r)}><Pencil size={14} /></Button>
                                <Button size="sm" variant="ghost" onClick={() => remove(r.id)}><Trash2 size={14} /></Button>
                            </div>
                        </div>
                    )}
                </li>
            ))}
        </ul>
    );
}
