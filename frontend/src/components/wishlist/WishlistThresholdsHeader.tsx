import { useEffect, useRef, useState } from 'react';
import { updateWishlistSettings } from '../../api';
import type { WishlistThresholds } from '../../types/api';
import { Input } from '../ui/input';
import { fmtRub } from '../strategy/strategyChartUtils';

interface Props {
    value: WishlistThresholds;
    monthlyExpensesAvg: number;
    /** Сообщает родителю новые пороги (для пересчёта зон риска). */
    onChange: (next: WishlistThresholds) => void;
}

const DEBOUNCE_MS = 800;

/**
 * Шапка страницы /wishlist: два порога — минимальный капитал (nullable) и буфер счёта в месяцах.
 * PUT на бэк дебаунсится на 800 мс; родитель получает изменения сразу (через onChange) для
 * мгновенного пересчёта зон риска. Внизу — производная строка с абсолютной суммой буфера.
 */
export default function WishlistThresholdsHeader({ value, monthlyExpensesAvg, onChange }: Props) {
    // Локальные строковые поля для свободного ввода (в т.ч. пустого).
    const [capitalStr, setCapitalStr] = useState(
        value.capitalThresholdRub != null ? String(value.capitalThresholdRub) : '',
    );
    const [bufferStr, setBufferStr] = useState(String(value.cashBufferMonths));

    const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
    const [saveError, setSaveError] = useState<string | null>(null);

    // Дебаунс-PUT при каждом изменении локальных полей.
    useEffect(() => {
        if (timer.current) clearTimeout(timer.current);
        timer.current = setTimeout(() => {
            const capital = capitalStr.trim() === '' ? null : Number(capitalStr);
            const buffer = bufferStr.trim() === '' ? 0 : Number(bufferStr);
            if (capital != null && Number.isNaN(capital)) return;
            if (Number.isNaN(buffer)) return;
            const next: WishlistThresholds = {
                capitalThresholdRub: capital,
                cashBufferMonths: buffer,
            };
            onChange(next);
            setSaveError(null);
            updateWishlistSettings(next).catch(e => {
                setSaveError(e instanceof Error ? e.message : 'Не удалось сохранить настройки');
            });
        }, DEBOUNCE_MS);
        return () => {
            if (timer.current) clearTimeout(timer.current);
        };
    }, [capitalStr, bufferStr]); // eslint-disable-line react-hooks/exhaustive-deps

    const bufferNum = bufferStr.trim() === '' ? 0 : Number(bufferStr);
    const bufferRub = monthlyExpensesAvg * (Number.isNaN(bufferNum) ? 0 : bufferNum);

    return (
        <div
            className="rounded-2xl px-4 py-3 space-y-2"
            style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}>
            <div className="flex flex-wrap gap-3">
                <div className="flex-1 min-w-[140px]">
                    <label className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
                        Мин. капитал, ₽
                    </label>
                    <Input
                        type="number"
                        min="0"
                        step="1000"
                        placeholder="выкл."
                        value={capitalStr}
                        onChange={e => setCapitalStr(e.target.value)}
                        className="h-8 text-sm"
                    />
                </div>
                <div className="flex-1 min-w-[140px]">
                    <label className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
                        Буфер счёта, мес.
                    </label>
                    <Input
                        type="number"
                        min="0"
                        step="0.5"
                        placeholder="1"
                        value={bufferStr}
                        onChange={e => setBufferStr(e.target.value)}
                        className="h-8 text-sm"
                    />
                </div>
            </div>
            <p className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
                ≈ не давать счёту опуститься ниже {fmtRub(Math.round(bufferRub))}
            </p>
            {saveError && <p className="text-xs text-destructive">{saveError}</p>}
        </div>
    );
}
