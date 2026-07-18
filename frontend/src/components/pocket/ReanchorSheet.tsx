import { useEffect, useState } from 'react';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '../ui/dialog';
import { createCheckpoint } from '../../api';
import { buildDriftPreview, buildMirrorLabel, checkpointAgeDays } from '../../lib/reanchor';

/**
 * Жест ре-якоря (ANO-15 §3): одно поле суммы, дата всегда «сегодня»
 * (задним числом — через Settings). Зеркало дрейфа — из lib/reanchor.
 */
export default function ReanchorSheet({ open, onOpenChange, currentBalance, checkpointDate, onSuccess }: {
    open: boolean;
    onOpenChange: (v: boolean) => void;
    currentBalance: number;
    checkpointDate: string | null;
    onSuccess: () => void;
}) {
    const [amount, setAmount] = useState('');
    const [saving, setSaving] = useState(false);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        if (open) { setAmount(''); setError(null); }
    }, [open]);

    const t = new Date();
    const todayIso = `${t.getFullYear()}-${String(t.getMonth() + 1).padStart(2, '0')}-${String(t.getDate()).padStart(2, '0')}`;
    const entered = amount.trim() === '' ? null : Number(amount);
    const ageDays = checkpointAgeDays(checkpointDate, todayIso);
    const driftLine = buildDriftPreview(entered, currentBalance, ageDays);
    const driftColor = driftLine == null ? undefined
        : driftLine.startsWith('дрейф −') ? 'var(--color-danger)'
        : driftLine.startsWith('дрейф +') ? 'var(--color-success)'
        : 'var(--color-text-muted)';

    const submit = async (e: React.FormEvent) => {
        e.preventDefault();
        if (entered == null || Number.isNaN(entered) || entered < 0) return;
        setSaving(true);
        setError(null);
        try {
            await createCheckpoint({ date: todayIso, amount: entered });
            onOpenChange(false);
            onSuccess();
        } catch (err) {
            setError((err as Error).message);
        } finally {
            setSaving(false);
        }
    };

    return (
        <Dialog open={open} onOpenChange={onOpenChange}>
            <DialogContent>
                <DialogHeader>
                    <DialogTitle>Обновить остаток</DialogTitle>
                </DialogHeader>
                <form onSubmit={submit} className="space-y-3">
                    <p className="text-sm" style={{ color: 'var(--color-text-muted)' }}>
                        {buildMirrorLabel(checkpointDate != null, currentBalance)}
                    </p>
                    <input
                        type="number"
                        inputMode="decimal"
                        autoFocus
                        min="0"
                        step="0.01"
                        value={amount}
                        onChange={e => setAmount(e.target.value)}
                        placeholder="Остаток из банка, ₽"
                        className="w-full rounded-lg px-3 py-2 text-sm"
                        style={{ background: 'var(--color-surface-2)', border: '1px solid var(--color-border)', color: 'var(--color-text)' }}
                    />
                    {driftLine && (
                        <p className="text-sm" style={{ color: driftColor }}>{driftLine}</p>
                    )}
                    {error && (
                        <p className="text-sm" style={{ color: 'var(--color-danger)' }}>Ошибка: {error}</p>
                    )}
                    <p className="text-[11px]" style={{ color: 'var(--color-text-muted)' }}>
                        Якорится на сегодня: операции этого дня уже внутри числа из банка.
                        Задним числом — через Настройки.
                    </p>
                    <button type="submit"
                        disabled={saving || entered == null || Number.isNaN(entered) || entered < 0}
                        className="w-full rounded-lg px-3 py-2 text-sm font-semibold disabled:opacity-50"
                        style={{ background: 'var(--color-accent)', color: '#fff' }}>
                        {saving ? 'Сохраняю…' : 'Заякорить'}
                    </button>
                </form>
            </DialogContent>
        </Dialog>
    );
}
