import { useState, useEffect } from 'react';
import { Plus } from 'lucide-react';
import { createEvent, fetchCategories } from '../api';
import type { Category, FinancialEventCreateDto } from '../types/api';
import { Sheet, SheetContent, SheetHeader, SheetTitle } from './ui/sheet';
import { Input } from './ui/input';
import { Button } from './ui/button';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from './ui/select';

/**
 * Модальная форма быстрого добавления транзакции (bottom sheet).
 * Загружает категории внутри себя при открытии — всегда актуальный список.
 * Фильтрует категории по выбранному типу (INCOME/EXPENSE).
 * При успешном сохранении вызывает `onSuccess` для обновления данных родителя.
 *
 * @param onClose   вызывается при закрытии модала
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
        fetchCategories().then(setCategories).finally(() => setCatLoading(false));
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
        <Sheet open onOpenChange={open => !open && onClose()}>
            <SheetContent side="bottom" className="max-w-2xl mx-auto rounded-t-2xl">
                <SheetHeader>
                    <SheetTitle>Быстрый ввод</SheetTitle>
                </SheetHeader>
                <form onSubmit={handleSubmit} className="flex flex-col gap-3 mt-4">
                    {/* Тип: Расход / Доход */}
                    <div className="flex gap-2">
                        <Button
                            type="button"
                            className="flex-1"
                            variant={form.type === 'EXPENSE' ? 'destructive' : 'secondary'}
                            onClick={() => handleTypeChange('EXPENSE')}
                        >
                            Расход
                        </Button>
                        <Button
                            type="button"
                            className="flex-1"
                            variant={form.type === 'INCOME' ? 'default' : 'secondary'}
                            style={form.type === 'INCOME' ? { background: 'var(--color-success)' } : {}}
                            onClick={() => handleTypeChange('INCOME')}
                        >
                            Доход
                        </Button>
                    </div>

                    {/* Категория — отфильтрована по типу */}
                    <Select
                        value={form.categoryId || ''}
                        onValueChange={val => setForm(f => ({ ...f, categoryId: val }))}
                        disabled={catLoading}
                    >
                        <SelectTrigger>
                            <SelectValue placeholder={catLoading ? 'Загрузка...' : `Выбери категорию (${filteredCategories.length})`} />
                        </SelectTrigger>
                        <SelectContent>
                            {filteredCategories.map(c => (
                                <SelectItem key={c.id} value={c.id}>{c.name}</SelectItem>
                            ))}
                        </SelectContent>
                    </Select>

                    {/* Плановая сумма */}
                    <Input
                        type="number"
                        placeholder="Сумма (план), ₽"
                        value={form.plannedAmount ?? ''}
                        onChange={e => setForm(f => ({ ...f, plannedAmount: Number(e.target.value) }))}
                    />

                    {/* Фактическая сумма — заполняется, если событие уже произошло */}
                    <Input
                        type="number"
                        placeholder="Сумма (факт, если уже произошло), ₽"
                        value={form.factAmount ?? ''}
                        onChange={e => setForm(f => ({
                            ...f,
                            factAmount: e.target.value ? Number(e.target.value) : undefined,
                        }))}
                    />

                    {/* Дата */}
                    <Input
                        type="date"
                        value={form.date || ''}
                        onChange={e => setForm(f => ({ ...f, date: e.target.value }))}
                    />

                    {/* Комментарий */}
                    <Input
                        placeholder="Комментарий (необязательно)"
                        value={form.description ?? ''}
                        onChange={e => setForm(f => ({ ...f, description: e.target.value, rawInput: e.target.value }))}
                    />

                    <Button
                        type="submit"
                        className="w-full"
                        disabled={loading || !form.categoryId}
                    >
                        {loading ? 'Сохраняем...' : 'Сохранить'}
                    </Button>
                </form>
            </SheetContent>
        </Sheet>
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
