import { useState } from 'react';
import { AmountInput, amountValue } from '../ui/amount-input';
import { updatePocketSettings } from '../../api';

const inputStyle = {
    background: 'var(--color-surface-2)',
    border: '1px solid var(--color-border)',
    color: 'var(--color-text)',
};

/**
 * Форма НЗ (ANO-92): одно поле суммы. Живёт в двух местах, как остаток: шторка с карточки
 * кармашка — поправить быстро, раздел «Настроек» — там его ищут.
 *
 * Пустое поле — НЗ нет: ноль поле суммы считает ошибкой («больше нуля»), поэтому НЗ убирают,
 * очищая поле, а не вводя 0.
 */
export default function NzForm({ initial, onSaved, autoFocus }: {
    /** Текущий НЗ; 0 — не задан. Поле засевается один раз, при монтировании. */
    initial: number;
    onSaved: (bufferAmount: number) => void;
    autoFocus?: boolean;
}) {
    const [amount, setAmount] = useState(initial > 0 ? String(initial) : '');
    const [saving, setSaving] = useState(false);
    const [error, setError] = useState<string | null>(null);

    const value = amount.trim() === '' ? 0 : amountValue(amount);

    const submit = async (e: React.FormEvent) => {
        e.preventDefault();
        if (value == null) return;
        setSaving(true);
        setError(null);
        try {
            await updatePocketSettings({ bufferAmount: value });
            onSaved(value);
        } catch (err) {
            setError((err as Error).message);
        } finally {
            setSaving(false);
        }
    };

    return (
        <form onSubmit={submit} className="space-y-3">
            <p className="text-sm" style={{ color: 'var(--color-text-muted)' }}>
                Сумма, которую кармашек не тратит: свободные деньги считаются так, чтобы на счёте
                оставалось не меньше. Чтобы убрать НЗ, очисти поле.
            </p>
            <AmountInput
                autoFocus={autoFocus}
                value={amount}
                onChange={setAmount}
                placeholder="НЗ, ₽"
                aria-label="НЗ, ₽"
                className="w-full rounded-lg px-3 py-2 text-sm h-auto border-0"
                style={inputStyle}
            />
            {error && (
                <p className="text-sm" style={{ color: 'var(--color-danger)' }}>Ошибка: {error}</p>
            )}
            <button type="submit"
                disabled={saving || value == null}
                className="w-full rounded-lg px-3 py-2 text-sm font-semibold disabled:opacity-50"
                style={{ background: 'var(--color-accent)', color: '#fff' }}>
                {saving ? 'Сохраняю…' : 'Сохранить'}
            </button>
        </form>
    );
}
