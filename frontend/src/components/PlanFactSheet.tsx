import { useEffect, useState } from 'react';
import { Button, Choices, Field, KindMark, Plate, chipVariants } from '@/ds';
import { cn } from '@/lib/utils';
import { createLinkedFact, fetchPocket } from '../api';
import type { FinancialEvent } from '../types/api';
import { amountRawInput, amountValue } from './ui/amount-input';
import { AmountField, DateChips } from './formParts';
import FormSheet from './FormSheet';
import { iconOf } from './journal/JournalList';
import { AttemptKeys } from '../lib/attemptKey';
import { canRecordFact, latestAllowedIso } from '../lib/factDate';
import { fmtDayShort, fmtRub } from '../lib/format';
import { fundMovementMessage } from '../lib/fundMovement';
import { askDateChoices, askDateNote, closePlanOf, ownEffect, planRest, restQuestion, type RestChoice } from '../lib/ownFact';

const KIND = { HIGH: 'bron', MEDIUM: 'ozh', LOW: 'hot' } as const;
const TO = { HIGH: 'К брони', MEDIUM: 'К ожиданию', LOW: 'К хотелке' } as const;
const TITLE = { HIGH: 'брони', MEDIUM: 'ожиданию', LOW: 'хотелке' } as const;

/**
 * Запись к плану (срез 3 п. 3; доски S3-F6, S3-F9, S3-F9b, S3-F6b; на 1440 — окном): «своё» у брони и у планового
 * дохода, «Оплачено» брони раньше сверки, «Записать» ожидания, которое к строке встаёт только так (перевод в копилку,
 * архивная категория, категория-бронь — lib/journalSections recordTo). Категория и вид — от плана (решение 51).
 */
export default function PlanFactSheet({ plan, name, askDate, checkpointDate, onClose, onCreated }: {
    plan: FinancialEvent;
    /** Как план назван в журнале. */
    name: string;
    /** Бронь раньше сверки (lib/quickClose): вместо поля даты — «До или после?». */
    askDate?: { anchorDate: string };
    /** Дата последней сверки: бронь не позже неё «Свободно» не держит — строки о нём нет. */
    checkpointDate?: string | null;
    onClose: () => void;
    onCreated: () => void;
}) {
    // ANO-155, ANO-235: дата записи — не позже сегодня, местной датой и не позже даты сервера.
    const today = latestAllowedIso(new Date());
    const rest = planRest(plan);
    const [amountRaw, setAmountRaw] = useState(rest > 0 ? String(rest) : '');
    const [date, setDate] = useState(askDate ? '' : today);
    const [description, setDescription] = useState('');
    const [choice, setChoice] = useState<RestChoice>('later');
    const [loading, setLoading] = useState(false);
    // ANO-169: отказ сервера виден — например, у плана перевода в копилку на счёте.
    const [error, setError] = useState<string | null>(null);
    // ANO-192, ревью #104: память попытки живёт, пока лист открыт; новое открытие — новый лист (key в журнале).
    const [attempts] = useState(() => new AttemptKeys());
    // Конец расчёта и траектория «Свободно»: без них строка о нём не обещает (бронь за концом расчёта, после минимума).
    const [pocket, setPocket] = useState<{ horizonEnd: string; trajectory: { date: string; balance: number }[] } | null>(null);
    useEffect(() => { fetchPocket().then(p => setPocket({ horizonEnd: p.horizon.endDate, trajectory: p.trajectory })).catch(() => {}); }, []);

    const amount = amountValue(amountRaw);
    const question = restQuestion(plan, amount);
    const effect = pocket ? ownEffect(plan, amount, choice, checkpointDate, date, pocket) : null;
    const priority = plan.priority ?? 'MEDIUM';
    const label = `Записать к ${TITLE[priority]} «${name}»`;
    const choices = askDate ? askDateChoices(plan, today) : [];
    const other = askDate != null && date !== '' && !choices.some(c => c.value === date);

    const submit = async () => {
        if (amount == null || !canRecordFact(date, today)) return;
        setLoading(true);
        setError(null);
        try {
            await createLinkedFact(attempts, plan.id, {
                date, factAmount: amount, description: description || undefined, rawInput: amountRawInput(amountRaw),
                closePlan: closePlanOf(plan, amount, choice),
            });
            onCreated();
            onClose();
        } catch (err) {
            console.error(err);
            setError(fundMovementMessage(err) ?? 'Не записалось — попробуйте ещё раз');
        } finally {
            setLoading(false);
        }
    };

    return (
        <FormSheet label={label} onClose={onClose}>
            <div className="flex items-center gap-3 px-1 pr-10">
                <KindMark kind={KIND[priority]} icon={(() => { const I = iconOf(plan); return <I strokeWidth={1.6} />; })()} className="size-10" />
                <div className="min-w-0">
                    <div className="truncate text-sf-body font-semibold">{TO[priority]} «{name}»</div>
                    <div className="text-sf-label text-sf-muted">
                        по плану {fmtRub(plan.plannedAmount ?? 0)}{plan.date ? ` · ${fmtDayShort(plan.date)}` : ''}{plan.categoryName ? ` · ${plan.categoryName}` : ''}
                    </div>
                </div>
            </div>
            <AmountField value={amountRaw} onChange={setAmountRaw} autoFocus
                note={`по плану — ${fmtRub(plan.plannedAmount ?? 0)}${plan.linkedFactsAmount ? `, записано ${fmtRub(plan.linkedFactsAmount)}` : ''}`} />
            {question && (
                <Plate className="flex flex-col gap-2">
                    <span className="text-sf-control">Остальные {fmtRub(question.rest)}</span>
                    <Choices name="rest" label="Остаток брони" look="chip" value={choice}
                        options={[{ value: 'later', label: question.options[0] }, { value: 'close', label: question.options[1] }]}
                        onChange={(v) => setChoice(v as RestChoice)} />
                </Plate>
            )}
            {askDate ? (
                <Plate className="flex flex-col gap-2.5">
                    <span className="text-sf-control">
                        {fmtDayShort(askDate.anchorDate)} ты обновлял остаток. Деньги {plan.type === 'INCOME' ? 'пришли' : 'ушли'} до этого или после?
                    </span>
                    <div className="flex flex-wrap gap-1.5">
                        <Choices name="when" label="Когда" look="chip" className="contents" value={date} options={askDateChoices(plan, today)} onChange={setDate} />
                        <label className={cn(chipVariants(), 'relative', other && 'bg-sf-accent font-medium text-sf-on-accent')}>
                            {other ? fmtDayShort(date) : 'Другая дата'}
                            <input type="date" aria-label="Другая дата" max={today} value={other ? date : ''}
                                onChange={(e) => { if (e.target.value) setDate(e.target.value); }}
                                className="absolute inset-0 cursor-pointer opacity-0" />
                        </label>
                    </div>
                </Plate>
            ) : (
                <DateChips value={date} today={today} onChange={setDate} max={today} />
            )}
            <Field aria-label="Название транзакции (необязательно)" placeholder="Название транзакции (необязательно)"
                value={description} onChange={(e) => setDescription(e.target.value)} />
            {askDate && amount != null && amount > 0 && <span className="px-1 text-sf-note text-sf-muted">{askDateNote(plan, amount)}</span>}
            {!askDate && effect && <span role="status" className="px-1 text-sf-note">{effect}</span>}
            {error && (
                <p className="px-1 text-sf-note text-sf-error">{error}</p>
            )}
            <Button disabled={loading || amount == null || amount <= 0 || !canRecordFact(date, today)} onClick={submit}>
                {loading ? 'Сохраняем…' : 'Записать'}
            </Button>
        </FormSheet>
    );
}
