import { useState } from 'react';
import { X } from 'lucide-react';
import { updateEvent } from '../api';
import type { FinancialEvent, FinancialEventCreateDto } from '../types/api';

interface EditEventSheetProps {
    /** Событие для редактирования; его поля предзаполняют форму. */
    event: FinancialEvent;
    /** Вызывается при закрытии sheet (крестик, тап на оверлей, успех). */
    onClose: () => void;
    /** Вызывается после успешного сохранения или удаления для обновления списка. */
    onSuccess: () => void;
}

/**
 * Bottom sheet для редактирования финансового события.
 * Позволяет ввести фактическую сумму и комментарий, либо удалить событие.
 *
 * Форма использует полный PUT-запрос (все поля события), а не PATCH,
 * так как компонент имеет доступ к полному объекту события.
 * Для обновления только факта из других контекстов используйте `patchEventFact` из api/index.ts.
 */
export default function EditEventSheet({ event, onClose, onSuccess }: EditEventSheetProps) {
    const [factAmount, setFactAmount] = useState<string>(
        event.factAmount != null ? String(event.factAmount) : ''
    );
    const [description, setDescription] = useState(event.description ?? '');
    const [loading, setLoading] = useState(false);

    const handleSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        setLoading(true);
        try {
            const dto: FinancialEventCreateDto = {
                date: event.date,
                categoryId: event.categoryId,
                type: event.type,
                plannedAmount: event.plannedAmount ?? undefined,
                factAmount: factAmount !== '' ? Number(factAmount) : undefined,
                mandatory: event.mandatory,
                description: description || undefined,
                rawInput: event.rawInput ?? undefined,
            };
            await updateEvent(event.id, dto);
            onSuccess();
            onClose();
        } catch (err) {
            console.error(err);
        } finally {
            setLoading(false);
        }
    };

    const handleDelete = async () => {
        if (!confirm('Удалить запись?')) return;
        const { deleteEvent } = await import('../api');
        setLoading(true);
        try {
            await deleteEvent(event.id);
            onSuccess();
            onClose();
        } finally {
            setLoading(false);
        }
    };

    const fmt = (n: number | null) =>
        n != null ? new Intl.NumberFormat('ru-RU').format(n) + ' ₽' : '—';

    return (
        <div className="fixed inset-0 z-[100] flex items-end justify-center"
            style={{ background: 'rgba(0,0,0,0.6)', backdropFilter: 'blur(4px)' }}
            onClick={onClose}>
            <div
                className="w-full max-w-2xl rounded-t-2xl p-6 space-y-4"
                style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}
                onClick={e => e.stopPropagation()}>

                {/* Заголовок */}
                <div className="flex items-center justify-between">
                    <div>
                        <h2 className="font-semibold text-base">{event.categoryName}</h2>
                        <p className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
                            {new Date(event.date).toLocaleDateString('ru-RU', { day: 'numeric', month: 'long' })}
                            {' · '}План: {fmt(event.plannedAmount)}
                        </p>
                    </div>
                    <button onClick={onClose}><X size={20} style={{ color: 'var(--color-text-muted)' }} /></button>
                </div>

                {/* Форма */}
                <form onSubmit={handleSubmit} className="space-y-3">
                    <div>
                        <label className="text-xs block mb-1" style={{ color: 'var(--color-text-muted)' }}>
                            Фактическая сумма, ₽
                        </label>
                        <input
                            type="number"
                            placeholder={`План: ${event.plannedAmount ?? '—'}`}
                            value={factAmount}
                            onChange={e => setFactAmount(e.target.value)}
                            className="w-full rounded-lg px-3 py-2 text-sm"
                            style={{ background: 'var(--color-surface-2)', border: '1px solid var(--color-border)', color: 'var(--color-text)' }}
                        />
                    </div>
                    <div>
                        <label className="text-xs block mb-1" style={{ color: 'var(--color-text-muted)' }}>
                            Комментарий
                        </label>
                        <input
                            type="text"
                            placeholder="Добавить заметку..."
                            value={description}
                            onChange={e => setDescription(e.target.value)}
                            className="w-full rounded-lg px-3 py-2 text-sm"
                            style={{ background: 'var(--color-surface-2)', border: '1px solid var(--color-border)', color: 'var(--color-text)' }}
                        />
                    </div>
                    <div className="flex gap-2 pt-1">
                        <button
                            type="button"
                            onClick={handleDelete}
                            disabled={loading}
                            className="flex-1 py-2.5 rounded-lg text-sm font-medium"
                            style={{ background: 'rgba(239,68,68,0.12)', color: 'var(--color-danger)' }}>
                            Удалить
                        </button>
                        <button
                            type="submit"
                            disabled={loading}
                            className="flex-1 py-2.5 rounded-lg text-sm font-semibold text-white"
                            style={{ background: 'var(--color-accent)', opacity: loading ? 0.7 : 1 }}>
                            {loading ? 'Сохраняем...' : 'Сохранить факт'}
                        </button>
                    </div>
                </form>
            </div>
        </div>
    );
}
