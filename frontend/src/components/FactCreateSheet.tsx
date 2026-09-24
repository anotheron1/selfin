import { useEffect, useState } from 'react';
import { Sheet, SheetContent, SheetHeader, SheetTitle } from './ui/sheet';
import { Input } from './ui/input';
import { AmountInput, amountValue, amountRawInput } from './ui/amount-input';
import { Button } from './ui/button';
import { createLinkedFact } from '../api';
import { PRIORITY_DOT_CONFIG, PRIORITY_FIELD_LABEL, PRIORITY_ORDER, priorityTitle } from '../lib/priority';
import { canRecordFact, todayIso } from '../lib/factDate';
import type { FactCreateDto, Priority } from '../types/api';

interface Props {
    planId: string;
    planDescription: string;
    planPriority: Priority;
    open: boolean;
    onClose: () => void;
    onCreated: () => void;
    /** Строка под заголовком — например, почему здесь нужна дата (ANO-176). */
    hint?: string;
    /** Сумма, подставленная заранее, — остаток брони, которую закрывают (ANO-176). */
    defaultAmount?: number;
    /** Дата пустая, пока её не выберут: после сверки остатка её нельзя угадать (ANO-176). */
    requireDate?: boolean;
    /** Правка самого плана — у строк, которые открываются не из недель, а из карточек ожиданий. */
    onEditPlan?: () => void;
    editPlanLabel?: string;
}

export default function FactCreateSheet({
    planId, planDescription, planPriority, open, onClose, onCreated,
    hint, defaultAmount, requireDate, onEditPlan, editPlanLabel = 'изменить план',
}: Props) {
    // ANO-155: местная дата, не UTC — toISOString() ночью уводил умолчание на день назад.
    const today = todayIso(new Date());
    const initialDate = requireDate ? '' : today;
    const initialAmount = defaultAmount != null ? String(defaultAmount) : '';
    const [date, setDate] = useState(initialDate);
    const [amount, setAmount] = useState(initialAmount);
    const [description, setDescription] = useState('');
    const [priority, setPriority] = useState<Priority>(planPriority);
    const [loading, setLoading] = useState(false);

    // Synchronise priority with the selected plan whenever the sheet opens
    useEffect(() => {
        if (open) setPriority(planPriority);
    }, [open, planPriority]);

    // Reset all fields to fresh state whenever the sheet opens (e.g. for a different plan)
    function handleOpenChange(isOpen: boolean) {
        if (!isOpen) {
            onClose();
        } else {
            // Ревью #45: сброс обязан класть ту же дату, что и умолчание, — иначе
            // повторное открытие ставило UTC-дату мимо границы `max`, которая местная.
            setDate(requireDate ? '' : todayIso(new Date()));
            setAmount(initialAmount);
            setDescription('');
            setPriority(planPriority);
        }
    }

    async function handleSubmit(e: React.FormEvent) {
        e.preventDefault();
        // ANO-33: сумма может быть выражением, поэтому parseFloat нельзя —
        // на «450+abc» он молча вернул бы 450. null = вводу верить нельзя.
        const value = amountValue(amount);
        if (value == null) return;
        setLoading(true);
        try {
            const dto: FactCreateDto = {
                date,
                factAmount: value,
                description: description || undefined,
                priority,
                rawInput: amountRawInput(amount),
            };
            await createLinkedFact(planId, dto);
            onCreated();
            onClose();
        } catch (err) {
            console.error(err);
        } finally {
            setLoading(false);
        }
    }

    return (
        <Sheet open={open} onOpenChange={handleOpenChange}>
            <SheetContent side="bottom" className="max-w-2xl mx-auto rounded-t-2xl">
                <SheetHeader>
                    <SheetTitle>Записать факт</SheetTitle>
                    <p className="text-sm" style={{ color: 'var(--color-text-muted)' }}>{planDescription}</p>
                    {hint && <p className="text-sm">{hint}</p>}
                    {onEditPlan && (
                        <button type="button" onClick={onEditPlan} className="self-start text-xs"
                            style={{ color: 'hsl(var(--primary))' }}>
                            {editPlanLabel}
                        </button>
                    )}
                </SheetHeader>
                <form onSubmit={handleSubmit} className="space-y-3 mt-4">
                    <div>
                        <label className="text-xs text-muted-foreground block mb-1">Дата</label>
                        {/* ANO-155: факт значит «деньги ушли» — позже сегодня они уйти не могли. */}
                        <Input type="date" value={date} max={today}
                               onChange={e => setDate(e.target.value)} required />
                    </div>
                    <div>
                        <label className="text-xs text-muted-foreground block mb-1">Фактическая сумма, ₽</label>
                        <AmountInput
                            placeholder="0 или 450+1230+890"
                            value={amount}
                            onChange={setAmount}
                            required
                        />
                    </div>
                    <div>
                        <label className="text-xs text-muted-foreground block mb-1">{PRIORITY_FIELD_LABEL}</label>
                        <div className="flex items-center gap-3 pt-1">
                            {PRIORITY_ORDER.map(p => (
                                <button
                                    key={p}
                                    type="button"
                                    title={priorityTitle(p)}
                                    onClick={() => setPriority(p)}
                                    style={{
                                        display: 'flex',
                                        alignItems: 'center',
                                        gap: 6,
                                        opacity: priority === p ? 1 : 0.35,
                                        cursor: 'pointer',
                                        background: 'none',
                                        border: 'none',
                                        padding: 0,
                                        transition: 'opacity 0.15s',
                                    }}
                                >
                                    <span style={{
                                        display: 'inline-block',
                                        width: 10,
                                        height: 10,
                                        borderRadius: '50%',
                                        backgroundColor: PRIORITY_DOT_CONFIG[p].color,
                                    }} />
                                    <span className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
                                        {PRIORITY_DOT_CONFIG[p].name}
                                    </span>
                                </button>
                            ))}
                        </div>
                    </div>
                    <div>
                        <label className="text-xs text-muted-foreground block mb-1">Комментарий</label>
                        <Input
                            placeholder="Необязательно..."
                            value={description}
                            onChange={e => setDescription(e.target.value)}
                        />
                    </div>
                    <Button type="submit" className="w-full"
                            disabled={loading || !canRecordFact(date, today)}>
                        {loading ? 'Сохраняю...' : 'Сохранить факт'}
                    </Button>
                </form>
            </SheetContent>
        </Sheet>
    );
}
