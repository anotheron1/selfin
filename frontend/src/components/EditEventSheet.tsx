import { useState, useEffect } from 'react';
import { updateEvent, fetchCategories, patchEventFact } from '../api';
import type { Category, FinancialEvent, FinancialEventCreateDto, EventType, Priority } from '../types/api';
import { Sheet, SheetContent, SheetHeader, SheetTitle } from './ui/sheet';
import { Input } from './ui/input';
import { Button } from './ui/button';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from './ui/select';

interface EditEventSheetProps {
    event: FinancialEvent;
    onClose: () => void;
    onSuccess: () => void;
}

export default function EditEventSheet({ event, onClose, onSuccess }: EditEventSheetProps) {
    const [factAmount, setFactAmount] = useState<string>(
        event.factAmount != null ? String(event.factAmount) : ''
    );
    const [description, setDescription] = useState(event.description ?? '');
    const [plannedAmount, setPlannedAmount] = useState<string>(
        event.plannedAmount != null ? String(event.plannedAmount) : ''
    );
    const [date, setDate] = useState(event.date ?? '');
    const [categoryId, setCategoryId] = useState(event.categoryId ?? '');
    const [type, setType] = useState<EventType>(event.type);
    const [priority, setPriority] = useState<Priority>(event.priority ?? 'MEDIUM');
    const [categories, setCategories] = useState<Category[]>([]);
    const [loading, setLoading] = useState(false);

    useEffect(() => {
        fetchCategories().then(setCategories).catch(console.error);
    }, []);

    const handleSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        setLoading(true);
        try {
            if (event.eventKind === 'FACT') {
                // FACT records are updated via PATCH /events/{id}/fact
                await patchEventFact(event.id, factAmount !== '' ? Number(factAmount) : undefined, description || undefined);
            } else {
                const dto: FinancialEventCreateDto = {
                    date: date || (event.date ?? ''),
                    categoryId: categoryId || undefined,
                    type,
                    plannedAmount: plannedAmount !== '' ? Number(plannedAmount) : undefined,
                    priority,
                    mandatory: event.mandatory,
                    description: description || undefined,
                    rawInput: event.rawInput ?? undefined,
                };
                await updateEvent(event.id, dto);
            }
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

    return (
        <Sheet open onOpenChange={(open) => !open && onClose()}>
            <SheetContent side="bottom" className="max-w-2xl mx-auto rounded-t-2xl">
                <SheetHeader>
                    <SheetTitle>{event.eventKind === 'FACT' ? 'Редактировать факт' : 'Редактировать план'}</SheetTitle>
                </SheetHeader>

                <form onSubmit={handleSubmit} className="space-y-3 mt-4">
                    <div>
                        <label className="text-xs text-muted-foreground block mb-1">
                            Название
                        </label>
                        <Input
                            placeholder="Название события..."
                            value={description}
                            onChange={e => setDescription(e.target.value)}
                        />
                    </div>
                    {event.eventKind !== 'FACT' && (
                        <div>
                            <label className="text-xs text-muted-foreground block mb-1">
                                Плановая сумма, ₽
                            </label>
                            <Input
                                type="number"
                                placeholder="0"
                                value={plannedAmount}
                                onChange={e => setPlannedAmount(e.target.value)}
                            />
                        </div>
                    )}
                    {event.eventKind === 'FACT' && (
                        <div>
                            <label className="text-xs text-muted-foreground block mb-1">
                                Фактическая сумма, ₽
                            </label>
                            <Input
                                type="number"
                                placeholder={`Факт: ${event.factAmount ?? '—'}`}
                                value={factAmount}
                                onChange={e => setFactAmount(e.target.value)}
                            />
                        </div>
                    )}
                    <div>
                        <label className="text-xs text-muted-foreground block mb-1">
                            Дата
                        </label>
                        <Input
                            type="date"
                            value={date}
                            onChange={e => setDate(e.target.value)}
                        />
                    </div>
                    {event.eventKind !== 'FACT' && (
                        <div>
                            <label className="text-xs text-muted-foreground block mb-1">
                                Категория
                            </label>
                            <Select value={categoryId} onValueChange={setCategoryId}>
                                <SelectTrigger>
                                    <SelectValue placeholder="Выберите категорию" />
                                </SelectTrigger>
                                <SelectContent>
                                    {categories.map(cat => (
                                        <SelectItem key={cat.id} value={cat.id}>
                                            {cat.name}
                                        </SelectItem>
                                    ))}
                                </SelectContent>
                            </Select>
                        </div>
                    )}
                    {event.eventKind !== 'FACT' && (
                        <div>
                            <label className="text-xs text-muted-foreground block mb-1">
                                Тип
                            </label>
                            <Select value={type} onValueChange={(v) => setType(v as EventType)}>
                                <SelectTrigger>
                                    <SelectValue />
                                </SelectTrigger>
                                <SelectContent>
                                    <SelectItem value="INCOME">Доход</SelectItem>
                                    <SelectItem value="EXPENSE">Расход</SelectItem>
                                </SelectContent>
                            </Select>
                        </div>
                    )}
                    {event.eventKind !== 'FACT' && (
                        <div>
                            <label className="text-xs text-muted-foreground block mb-1">
                                Приоритет
                            </label>
                            <Select value={priority} onValueChange={(v) => setPriority(v as Priority)}>
                                <SelectTrigger>
                                    <SelectValue />
                                </SelectTrigger>
                                <SelectContent>
                                    <SelectItem value="HIGH">Высокий</SelectItem>
                                    <SelectItem value="MEDIUM">Средний</SelectItem>
                                    <SelectItem value="LOW">Низкий</SelectItem>
                                </SelectContent>
                            </Select>
                        </div>
                    )}
                    <div className="flex gap-2 pt-1">
                        <Button
                            type="button"
                            variant="destructive"
                            className="flex-1 bg-destructive/10 text-destructive hover:bg-destructive/20"
                            onClick={handleDelete}
                            disabled={loading}
                        >
                            Удалить
                        </Button>
                        <Button
                            type="submit"
                            className="flex-1"
                            disabled={loading}
                        >
                            {loading ? 'Сохраняем...' : 'Сохранить'}
                        </Button>
                    </div>
                </form>
            </SheetContent>
        </Sheet>
    );
}
