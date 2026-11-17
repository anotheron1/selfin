import { useEffect, useState } from 'react';
import { Bus, Coffee, ShoppingCart } from 'lucide-react';
import { Button, Field, KindMark } from '@/ds';
import { createPlanWithFact, fetchCategories, fetchEvents } from '../../api';
import { AttemptKeys } from '../../lib/attemptKey';
import { parseAmountExpression } from '../../lib/amountExpression';
import { fmtDayShort } from '../../lib/format';
import { termIncome, USUAL_CATEGORIES, usualDtos, type UsualName } from '../../lib/usualSpending';
import type { Category, PocketResponse } from '../../types/api';

const ICON: Record<UsualName, typeof Bus> = { Продукты: ShoppingCart, Транспорт: Bus, 'Кафе и доставка': Coffee };

/**
 * «Сколько обычно уходит до …?» — по ожиданию на продукты, транспорт, кафе и доставку (спека среза 2, пп. 10, 11).
 * Одна форма для плашки «Обычных трат в плане нет» на «Сегодня» и вопроса 4 первого входа. Пишет при «Готово»;
 * число перечитывает экран: удачная запись шлёт `selfin:changed`.
 */
export function UsualSpendingForm({ data, onDone, onSkip, doneLabel = 'Готово' }: {
    data: PocketResponse;
    onDone: () => void;
    onSkip?: () => void;
    doneLabel?: string;
}) {
    const income = termIncome(data);
    const until = income?.date ?? data.horizon.endDate;
    const [raw, setRaw] = useState<Record<UsualName, string>>({ Продукты: '', Транспорт: '', 'Кафе и доставка': '' });
    const [categories, setCategories] = useState<Category[] | null>(null);
    const [monthly, setMonthly] = useState<boolean | null>(income?.id ? null : false);
    const [attempts] = useState(() => new AttemptKeys());
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        fetchCategories().then(setCategories).catch(() => setError('Не загрузились категории — попробуй ещё раз'));
        if (income?.id) {
            fetchEvents(income.date, income.date)
                .then(es => setMonthly(es.find(e => e.id === income.id)?.recurringFrequency === 'MONTHLY'))
                .catch(() => setMonthly(false));
        }
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    const amounts = Object.fromEntries(USUAL_CATEGORIES.map(n => [n, parseAmountExpression(raw[n]).value ?? 0])) as Record<UsualName, number>;
    const any = USUAL_CATEGORIES.some(n => amounts[n] > 0);

    const submit = async (e: React.FormEvent) => {
        e.preventDefault();
        if (!categories || monthly == null || busy) return;
        setBusy(true);
        setError(null);
        try {
            const dtos = usualDtos(amounts, categories, { date: until, monthly }, data.trajectory[0]?.date ?? until);
            for (const dto of dtos) await createPlanWithFact(attempts, dto);
            onDone();
        } catch (err) {
            console.error(err);
            setError('Не записалось — попробуй ещё раз');
        } finally {
            setBusy(false);
        }
    };

    return (
        <form onSubmit={submit} className="flex flex-col gap-3 font-sf text-sf-ink">
            <div className="flex flex-col gap-1">
                <h2 className="text-[22px] font-medium leading-tight">Сколько обычно уходит до {fmtDayShort(until)}?</h2>
                <p className="text-sf-control text-sf-muted">На продукты, транспорт, кафе — примерно. Без этого число выше, чем будет на деле.</p>
            </div>
            <div className="flex flex-col">
                {USUAL_CATEGORIES.map(n => {
                    const Icon = ICON[n];
                    return (
                        <div key={n} className="flex min-h-[60px] items-center gap-3 border-b border-sf-rule">
                            <KindMark kind="ozh" icon={<Icon strokeWidth={1.7} />} />
                            <span className="flex-1 text-sf-button font-normal">{n}</span>
                            <Field className="w-[132px]" inputMode="decimal" placeholder="0" suffix="₽" aria-label={`${n}, ₽`}
                                value={raw[n]} onChange={e => setRaw(r => ({ ...r, [n]: e.target.value }))} />
                        </div>
                    );
                })}
            </div>
            {error && <p role="alert" className="text-sf-label text-sf-muted">{error}</p>}
            <div className="flex gap-2">
                <Button type="submit" disabled={!any || busy || !categories || monthly == null} className="flex-1">{doneLabel}</Button>
                {onSkip && <Button type="button" variant="outline" onClick={onSkip}>Пропустить</Button>}
            </div>
        </form>
    );
}
