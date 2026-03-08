import { useState, useEffect } from 'react';
import { Plus, X } from 'lucide-react';
import { createEvent, fetchCategories } from '../api';
import type { Category, FinancialEventCreateDto } from '../types/api';

/**
 * Модальная форма быстрого добавления транзакции (bottom sheet).
 * Загружает категории внутри себя при открытии — всегда актуальный список.
 * Фильтрует категории по выбранному типу (INCOME/EXPENSE).
 * При успешном сохранении вызывает `onSuccess` для обновления данных родителя.
 *
 * @param onClose   вызывается при закрытии модала (крестик или успех)
 * @param onSuccess вызывается после успешного создания события
 */
function QuickAddModal({ onClose, onSuccess }: { onClose: () => void; onSuccess: () => void }) {
    const [form, setForm] = useState<Partial<FinancialEventCreateDto>>({
        date: new Date().toISOString().slice(0, 10),
        type: 'EXPENSE',
        mandatory: false,
    });
    const [loading, setLoading] = useState(false);
    const [categories, setCategories] = useState<Category[]>([]);
    const [catLoading, setCatLoading] = useState(true);

    // Загружаем категории при открытии модала — всегда свежий список
    useEffect(() => {
        setCatLoading(true);
        fetchCategories()
            .then(setCategories)
            .finally(() => setCatLoading(false));
    }, []);

    // При смене типа — сбрасываем выбранную категорию
    const handleTypeChange = (type: 'EXPENSE' | 'INCOME') => {
        setForm(f => ({ ...f, type, categoryId: undefined }));
    };

    // Фильтруем категории по выбранному типу
    const filteredCategories = categories.filter(c => c.type === form.type);

    const handleSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        if (!form.categoryId || !form.date || !form.type) return;
        setLoading(true);
        try {
            await createEvent(form as FinancialEventCreateDto);
            onSuccess();
            onClose();
        } catch (err) {
            console.error('Ошибка создания транзакции:', err);
        } finally {
            setLoading(false);
        }
    };

    return (
        <div className="fixed inset-0 z-[100] flex items-end justify-center"
            style={{ background: 'rgba(0,0,0,0.6)', backdropFilter: 'blur(4px)' }}>
            <div className="w-full max-w-2xl rounded-t-2xl p-6"
                style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}>
                <div className="flex items-center justify-between mb-4">
                    <h2 className="text-lg font-semibold">Быстрый ввод</h2>
                    <button onClick={onClose} className="text-muted-400 hover:text-white">
                        <X size={20} />
                    </button>
                </div>
                <form onSubmit={handleSubmit} className="flex flex-col gap-3">
                    {/* Выбор типа */}
                    <div className="flex gap-2">
                        <button type="button"
                            onClick={() => handleTypeChange('EXPENSE')}
                            className="flex-1 py-2 rounded-lg text-sm font-medium transition-colors"
                            style={{
                                background: form.type === 'EXPENSE' ? 'var(--color-danger)' : 'var(--color-surface-2)',
                                color: form.type === 'EXPENSE' ? '#fff' : 'var(--color-text-muted)',
                            }}>Расход</button>
                        <button type="button"
                            onClick={() => handleTypeChange('INCOME')}
                            className="flex-1 py-2 rounded-lg text-sm font-medium transition-colors"
                            style={{
                                background: form.type === 'INCOME' ? 'var(--color-success)' : 'var(--color-surface-2)',
                                color: form.type === 'INCOME' ? '#fff' : 'var(--color-text-muted)',
                            }}>Доход</button>
                    </div>

                    {/* Категория — отфильтрована по типу */}
                    <select
                        required
                        value={form.categoryId || ''}
                        onChange={e => setForm(f => ({ ...f, categoryId: e.target.value }))}
                        className="w-full rounded-lg px-3 py-2 text-sm"
                        style={{ background: 'var(--color-surface-2)', border: '1px solid var(--color-border)', color: 'var(--color-text)' }}>
                        <option value="">
                            {catLoading ? 'Загрузка...' : `Выбери категорию (${filteredCategories.length})`}
                        </option>
                        {filteredCategories.map(c => (
                            <option key={c.id} value={c.id}>{c.name}</option>
                        ))}
                    </select>

                    {/* Плановая сумма */}
                    <input
                        type="number"
                        placeholder="Сумма (план), ₽"
                        value={form.plannedAmount ?? ''}
                        onChange={e => setForm(f => ({ ...f, plannedAmount: Number(e.target.value) }))}
                        className="w-full rounded-lg px-3 py-2 text-sm"
                        style={{ background: 'var(--color-surface-2)', border: '1px solid var(--color-border)', color: 'var(--color-text)' }}
                    />

                    {/* Дата */}
                    <input
                        type="date"
                        value={form.date || ''}
                        onChange={e => setForm(f => ({ ...f, date: e.target.value }))}
                        className="w-full rounded-lg px-3 py-2 text-sm"
                        style={{ background: 'var(--color-surface-2)', border: '1px solid var(--color-border)', color: 'var(--color-text)' }}
                    />

                    {/* Комментарий */}
                    <input
                        type="text"
                        placeholder="Комментарий (необязательно)"
                        value={form.description ?? ''}
                        onChange={e => setForm(f => ({ ...f, description: e.target.value, rawInput: e.target.value }))}
                        className="w-full rounded-lg px-3 py-2 text-sm"
                        style={{ background: 'var(--color-surface-2)', border: '1px solid var(--color-border)', color: 'var(--color-text)' }}
                    />

                    <button
                        type="submit"
                        disabled={loading || !form.categoryId}
                        className="w-full py-3 rounded-lg font-semibold text-white transition-opacity"
                        style={{ background: 'var(--color-accent)', opacity: (loading || !form.categoryId) ? 0.6 : 1 }}>
                        {loading ? 'Сохраняем...' : 'Сохранить'}
                    </button>
                </form>
            </div>
        </div>
    );
}

interface FabProps {
    /** Колбэк, вызываемый после успешного добавления транзакции для обновления страницы. */
    onSuccess: () => void;
}

/**
 * Floating Action Button (FAB) — фиксированная кнопка быстрого добавления транзакции.
 * Отображается поверх всего контента, позиционируется над нижней навигацией.
 * По клику открывает `QuickAddModal` в виде bottom sheet.
 */
export default function Fab({ onSuccess }: FabProps) {
    const [open, setOpen] = useState(false);

    return (
        <>
            <button
                onClick={() => setOpen(true)}
                className="fixed z-50 rounded-full shadow-lg flex items-center justify-center transition-transform hover:scale-105 active:scale-95"
                style={{
                    bottom: 'calc(var(--nav-height) + 16px)',
                    right: '20px',
                    width: '56px',
                    height: '56px',
                    background: 'var(--color-accent)',
                    boxShadow: '0 0 24px var(--color-accent-glow)',
                }}>
                <Plus size={26} color="white" />
            </button>
            {open && (
                <QuickAddModal
                    onClose={() => setOpen(false)}
                    onSuccess={onSuccess}
                />
            )}
        </>
    );
}
