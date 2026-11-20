import { useRef } from 'react';
import { Choices, Field, chipVariants } from '@/ds';
import { cn } from '@/lib/utils';
import { parseAmountExpression } from '../lib/amountExpression';
import { fmtDayShort } from '../lib/format';

/** Детали формы «+» и формы записи к плану: сумма одним полем и даты чипами (Zh2-F8a, Zh2-F8b, S3-F6). */

const fmt = (n: number) => new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 2 }).format(n);

/**
 * Сумма — одно поле, крупным светлым; выражение «450+1230» — как раньше (ANO-33): под полем «= 1 680 ₽» или
 * почему не понять. Хранит сырой текст: число — amountValue, выражение для rawInput — amountRawInput.
 */
export function AmountField({ value, onChange, note, autoFocus }: {
    value: string; onChange: (raw: string) => void;
    /** Строка под полем: «по плану — 25 000 ₽» (S3-F6). */
    note?: string;
    autoFocus?: boolean;
}) {
    const parsed = parseAmountExpression(value);
    const error = value.trim() !== '' && (parsed.error === 'syntax' || parsed.error === 'nonPositive');
    const hint = value.trim() === '' ? null
        : parsed.error === 'syntax' ? 'Не понимаю выражение'
        : parsed.error === 'nonPositive' ? 'Сумма должна быть больше нуля'
        : parsed.isExpression && parsed.value != null ? `= ${fmt(parsed.value)} ₽` : null;
    return (
        <div className="flex flex-col gap-1">
            <Field look="amount" aria-label="Сумма" inputMode="decimal" autoComplete="off" placeholder="0"
                value={value} onChange={(e) => onChange(e.target.value)} invalid={error} suffix="₽" autoFocus={autoFocus} />
            {(hint || note) && (
                <span className={error ? 'pl-1 text-sf-label text-sf-error' : 'pl-1 text-sf-label text-sf-muted'}>
                    {hint ?? note}
                </span>
            )}
        </div>
    );
}

const shift = (iso: string, days: number) => {
    const [y, m, d] = iso.split('-').map(Number);
    const t = new Date(y, m - 1, d + days);
    return `${t.getFullYear()}-${String(t.getMonth() + 1).padStart(2, '0')}-${String(t.getDate()).padStart(2, '0')}`;
};

/** «19 нояб., чт» */
const otherLabel = (iso: string) => {
    const [y, m, d] = iso.split('-').map(Number);
    return `${fmtDayShort(iso)}, ${new Date(y, m - 1, d).toLocaleDateString('ru-RU', { weekday: 'short' })}`;
};

/**
 * Дата чипами: «Сегодня», «Вчера», «Другая дата» — она открывает календарь и потом показывает выбранное
 * (Zh2-F8b). Справа — значок повтора, если его даёт форма.
 */
export function DateChips({ value, today, onChange, max, children }: {
    value: string; today: string; onChange: (iso: string) => void;
    /** Позже нельзя: у записи к плану дата — не позже сегодня (ANO-155). */
    max?: string;
    children?: React.ReactNode;
}) {
    const picker = useRef<HTMLInputElement>(null);
    const yesterday = shift(today, -1);
    const other = value !== today && value !== yesterday && value !== '';
    const open = () => {
        const el = picker.current;
        if (!el) return;
        try { el.showPicker(); } catch { el.focus(); }
    };
    return (
        <div className="relative flex flex-wrap items-center gap-1.5">
            <Choices name="date" label="Дата" look="chip" value={value} className="contents"
                options={[{ value: today, label: 'Сегодня' }, { value: yesterday, label: 'Вчера' }]} onChange={onChange} />
            {/* Не radio: выбранную другую дату нажимают ещё раз — снова календарь. */}
            <button type="button" aria-pressed={other} onClick={open}
                className={cn(chipVariants(), other && 'bg-sf-accent font-medium text-sf-on-accent hover:bg-sf-accent-hover active:bg-sf-accent-press')}>
                {other ? otherLabel(value) : 'Другая дата'}
            </button>
            <input ref={picker} type="date" tabIndex={-1} aria-hidden="true" value={value} max={max}
                onChange={(e) => { if (e.target.value) onChange(e.target.value); }}
                className="pointer-events-none absolute bottom-0 left-0 h-0 w-0 opacity-0" />
            {children}
        </div>
    );
}
