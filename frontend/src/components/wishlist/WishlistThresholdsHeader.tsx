import { useEffect, useRef, useState } from 'react';
import { updateWishlistSettings } from '../../api';
import type { WishlistThresholds } from '../../types/api';
import { AmountInput, amountValue } from '../ui/amount-input';
import { nzZoneText } from './wishlistUtils';

interface Props {
    value: WishlistThresholds;
    /** НЗ кармашка: жёлтые месяцы — остаток ниже него (Р5). */
    nz: number;
    /** Сообщает родителю новые пороги (для пересчёта зон риска). */
    onChange: (next: WishlistThresholds) => void;
}

const DEBOUNCE_MS = 800;

/**
 * Шапка «Что с капиталом»: порог «Мин. капитал» (nullable) и строка, что значит жёлтый. Порог
 * остатка — НЗ кармашка, второй подушки нет (Р5, ANO-93): поле «Подушка, мес.» ушло.
 * PUT на бэк дебаунсится на 800 мс; родитель получает изменения сразу (через onChange) для
 * мгновенного пересчёта зон риска.
 */
export default function WishlistThresholdsHeader({ value, nz, onChange }: Props) {
    // Контракт seed-once: локальные строковые поля СОЗНАТЕЛЬНО инициализируются из `value`
    // только при монтировании и НЕ ре-синхронизируются из `value` через useEffect. Причина:
    // правки текут наверх через onChange и возвращаются обратно как `value` — value-sync effect
    // затирал бы ввод пользователя в процессе печати. Родитель гарантирует, что шапка монтируется
    // лишь ПОСЛЕ загрузки порогов с сервера (и ремонтирует её через key на переходе pending→loaded),
    // поэтому стартовые значения здесь всегда серверные.
    const [capitalStr, setCapitalStr] = useState(
        value.capitalThresholdRub != null ? String(value.capitalThresholdRub) : '',
    );

    const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
    const firstRun = useRef(true);
    const [saveError, setSaveError] = useState<string | null>(null);

    // Дебаунс-PUT при каждом изменении локальных полей. Пропускаем самый первый прогон,
    // чтобы не слать избыточный PUT с начальными значениями при монтировании.
    useEffect(() => {
        if (firstRun.current) {
            firstRun.current = false;
            return;
        }
        if (timer.current) clearTimeout(timer.current);
        timer.current = setTimeout(() => {
            const capital = capitalStr.trim() === '' ? null : amountValue(capitalStr);   // ANO-33
            if (capital != null && Number.isNaN(capital)) return;
            const next: WishlistThresholds = { capitalThresholdRub: capital };
            onChange(next);
            setSaveError(null);
            updateWishlistSettings(next).catch(e => {
                setSaveError(e instanceof Error ? e.message : 'Не удалось сохранить настройки');
            });
        }, DEBOUNCE_MS);
        return () => {
            if (timer.current) clearTimeout(timer.current);
        };
    }, [capitalStr]); // eslint-disable-line react-hooks/exhaustive-deps

    return (
        <div
            className="rounded-2xl px-4 py-3 space-y-2"
            style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}>
            <div className="flex flex-wrap gap-3">
                <div className="flex-1 min-w-[140px]">
                    <label className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
                        Мин. капитал, ₽
                    </label>
                    <AmountInput
                        placeholder="выкл."
                        value={capitalStr}
                        onChange={setCapitalStr}
                        className="h-8 text-sm"
                    />
                </div>
            </div>
            <p className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
                {nzZoneText(nz)}
            </p>
            {saveError && <p className="text-xs text-destructive">{saveError}</p>}
        </div>
    );
}
