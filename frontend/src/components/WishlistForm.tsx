import { useState } from 'react';
import type { WishlistCreateDto } from '../types/api';

interface WishlistFormProps {
    initialValues?: Partial<WishlistCreateDto & { date?: string }>;
    onSubmit: (dto: WishlistCreateDto) => Promise<void>;
    onCancel: () => void;
}

export default function WishlistForm({ initialValues = {}, onSubmit, onCancel }: WishlistFormProps) {
    const [description, setDescription] = useState(initialValues.description ?? '');
    const [plannedAmount, setPlannedAmount] = useState(
        initialValues.plannedAmount != null ? String(initialValues.plannedAmount) : '');
    const [url, setUrl] = useState(initialValues.url ?? '');
    const [submitting, setSubmitting] = useState(false);
    const [error, setError] = useState<string | null>(null);

    async function handleSubmit(e: React.FormEvent) {
        e.preventDefault();
        if (!description.trim()) return;
        setSubmitting(true);
        setError(null);
        try {
            await onSubmit({
                description: description.trim(),
                plannedAmount: plannedAmount ? Number(plannedAmount) : null,
                url: url.trim() || null,
            });
        } catch {
            setError('Не удалось сохранить. Попробуйте ещё раз.');
        } finally {
            setSubmitting(false);
        }
    }

    const inputStyle = {
        background: 'var(--color-surface)',
        border: '1px solid var(--color-border)',
        color: 'var(--color-text)',
    };

    return (
        <form
            onSubmit={handleSubmit}
            className="flex flex-col gap-1.5 p-2 rounded"
            style={{ background: 'var(--color-bg)', border: '1px solid var(--color-border)' }}
        >
            <input type="text" required placeholder="Название *" value={description}
                onChange={e => setDescription(e.target.value)}
                className="rounded px-2 py-1 text-sm w-full outline-none" style={inputStyle} />
            <input type="number" min="0" step="0.01" placeholder="Цена (необязательно)"
                value={plannedAmount} onChange={e => setPlannedAmount(e.target.value)}
                className="rounded px-2 py-1 text-sm w-full outline-none" style={inputStyle} />
            <input type="url" placeholder="Ссылка (необязательно)" value={url}
                onChange={e => setUrl(e.target.value)}
                className="rounded px-2 py-1 text-sm w-full outline-none" style={inputStyle} />
            {error && <p className="text-xs" style={{ color: '#ef4444' }}>{error}</p>}
            <div className="flex gap-2 justify-end">
                <button type="button" onClick={onCancel}
                    className="text-xs px-2 py-1 rounded"
                    style={{ color: 'var(--color-text-muted)' }}>Отмена</button>
                <button type="submit" disabled={submitting}
                    className="text-xs px-3 py-1 rounded"
                    style={{ background: 'var(--color-primary)', color: '#fff' }}>
                    {submitting ? '...' : 'Сохранить'}
                </button>
            </div>
        </form>
    );
}
