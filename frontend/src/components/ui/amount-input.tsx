import { useId } from 'react';
import { parseAmountExpression } from '../../lib/amountExpression';
import { cn } from '../../lib/utils';

/**
 * Поле денежной суммы с калькулятором (ANO-33): принимает и «2570», и «450+1230+890».
 *
 * Почему не `type="number"`: в числовое поле выражение физически не ввести —
 * при badInput браузер отдаёт пустую строку, и подключить парсер невозможно.
 * Отсюда `type="text"` + `inputMode="decimal"` (числовая клавиатура на телефоне)
 * и своя валидация взамен утраченных нативных `required` / `min` / `step`.
 *
 * Компонент хранит СЫРОЙ текст: родитель отдаёт его в `rawInput`, чтобы потом было
 * видно, из чего сложилась сумма, а число получает через {@link parseAmountExpression}.
 */

export interface AmountInputProps {
    /** Сырой текст поля (не число!). */
    value: string;
    onChange: (raw: string) => void;
    placeholder?: string;
    required?: boolean;
    disabled?: boolean;
    autoFocus?: boolean;
    className?: string;
    style?: React.CSSProperties;
    'aria-label'?: string;
    /** Скрыть строку предпросмотра (для тесных мест вроде строки примерки). */
    hidePreview?: boolean;
}

const fmt = (n: number) =>
    new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 2 }).format(n);

export function AmountInput({
    value, onChange, placeholder, required, disabled, autoFocus,
    className, style, hidePreview, ...rest
}: AmountInputProps) {
    const hintId = useId();
    const parsed = parseAmountExpression(value);

    // Пока поле пустое, ошибку не показываем: пустота — не ошибка ввода.
    const showHint = !hidePreview && value.trim() !== '' && (
        parsed.isExpression || parsed.error === 'syntax' || parsed.error === 'nonPositive'
    );

    const hint = parsed.error === 'syntax'
        ? 'Не понимаю выражение'
        : parsed.error === 'nonPositive'
            ? 'Сумма должна быть больше нуля'
            : parsed.value != null && parsed.isExpression
                ? `= ${fmt(parsed.value)} ₽`
                : null;

    const isError = parsed.error === 'syntax' || parsed.error === 'nonPositive';

    return (
        <div>
            <input
                {...rest}
                type="text"
                inputMode="decimal"
                autoComplete="off"
                value={value}
                onChange={e => onChange(e.target.value)}
                placeholder={placeholder}
                required={required}
                disabled={disabled}
                autoFocus={autoFocus}
                aria-invalid={isError || undefined}
                aria-describedby={showHint ? hintId : undefined}
                className={cn(
                    'flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm',
                    'ring-offset-background placeholder:text-muted-foreground',
                    'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2',
                    'disabled:cursor-not-allowed disabled:opacity-50',
                    className,
                )}
                style={style}
            />
            {showHint && hint && (
                <p
                    id={hintId}
                    className="text-xs mt-1"
                    style={{ color: isError ? 'var(--color-danger)' : 'var(--color-text-muted)' }}
                >
                    {hint}
                </p>
            )}
        </div>
    );
}

/**
 * Число для отправки на бэк, или null если вводу верить нельзя.
 * Родитель обязан звать именно это вместо `Number(value)`: на «450+1230»
 * `Number` вернёт NaN, а `parseFloat` — молча 450.
 */
export function amountValue(raw: string): number | null {
    return parseAmountExpression(raw).value;
}

/** Что класть в `rawInput`: только осмысленное выражение, а не обычное число. */
export function amountRawInput(raw: string): string | undefined {
    const parsed = parseAmountExpression(raw);
    return parsed.isExpression && parsed.value != null ? raw.trim() : undefined;
}

export default AmountInput;
