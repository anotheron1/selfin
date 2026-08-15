import { useEffect, useState } from 'react';
import { AmountInput, amountValue } from '../ui/amount-input';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '../ui/dialog';
import { createCheckpoint, fetchAccounts } from '../../api';
import type { Account } from '../../types/api';
import { buildDriftPreview, buildMirrorLabel, checkpointAgeDays } from '../../lib/reanchor';

/**
 * Жест ре-якоря (ANO-15 §3): одно поле суммы, дата всегда «сегодня»
 * (задним числом — через Settings). Зеркало дрейфа — из lib/reanchor.
 *
 * <p>Ре-якорь — единственное место ежедневного цикла, где счёт вообще виден (ANO-9 §5.1):
 * здесь ты и правда выбираешь, чей остаток вводишь. Пока счёт один, селектора нет вовсе —
 * не «спрятан за дефолтом», а буквально отсутствует.
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
    const [accounts, setAccounts] = useState<Account[]>([]);
    const [accountId, setAccountId] = useState('');

    useEffect(() => {
        if (!open) return;
        setAmount('');
        setError(null);
        setAccountId('');
        fetchAccounts().then(setAccounts).catch(() => setAccounts([]));
    }, [open]);

    const selected = accounts.find(a => a.id === accountId) ?? null;
    // Зеркало дрейфа сравнивает введённое с числом КАРМАШКА, то есть с суммой свободных
    // денег. Для другого счёта такое сравнение бессмысленно, поэтому его не показываем —
    // лучше промолчать, чем показать разницу двух разных величин и назвать её дрейфом.
    const mirrorApplies = selected == null || selected.isDefault;

    const t = new Date();
    const todayIso = `${t.getFullYear()}-${String(t.getMonth() + 1).padStart(2, '0')}-${String(t.getDate()).padStart(2, '0')}`;
    // ANO-33: остаток можно ввести выражением («50000+12000» с двух счетов)
    const entered = amount.trim() === '' ? null : amountValue(amount);
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
            await createCheckpoint({ date: todayIso, amount: entered, accountId: accountId || undefined });
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
                    {mirrorApplies && (
                        <p className="text-sm" style={{ color: 'var(--color-text-muted)' }}>
                            {buildMirrorLabel(checkpointDate != null, currentBalance)}
                        </p>
                    )}
                    {accounts.length > 1 && (
                        <select
                            value={accountId}
                            onChange={e => setAccountId(e.target.value)}
                            className="w-full rounded-lg px-3 py-2 text-sm"
                            style={{ background: 'var(--color-surface-2)', border: '1px solid var(--color-border)', color: 'var(--color-text)' }}>
                            <option value="">Счёт по умолчанию</option>
                            {accounts.map(a => (
                                <option key={a.id} value={a.id}>
                                    {a.name}{a.kind === 'CREDIT' ? ' — доступный остаток' : ''}
                                </option>
                            ))}
                        </select>
                    )}
                    <AmountInput
                        autoFocus
                        value={amount}
                        onChange={setAmount}
                        placeholder="Остаток из банка, ₽"
                        className="w-full rounded-lg px-3 py-2 text-sm h-auto border-0"
                        style={{ background: 'var(--color-surface-2)', border: '1px solid var(--color-border)', color: 'var(--color-text)' }}
                    />
                    {mirrorApplies && driftLine && (
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
