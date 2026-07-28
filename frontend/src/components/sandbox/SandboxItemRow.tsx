import { useState } from 'react';
import { Check, X, SlidersHorizontal } from 'lucide-react';
import type { SandboxItem, SandboxTryOn } from '../../types/api';
import { fmtRub } from '../../lib/format';
import { monthlyFor } from '../../lib/sandboxMath';

/**
 * Строка окна примерки (ANO-16 §7): тумблер, инлайн-правки суммы/даты, ползунок
 * растяжки; отрисовка по статусу — OPEN пунктир/приглушённо, FIXED сплошная «в плане».
 * inBaseline=false FIXED-копилка (края §6) ведёт себя как OPEN (только tryOn).
 */
export default function SandboxItemRow({
    item, enabled, params, onToggle, onParamsChange, onFix, onDismiss,
}: {
    item: SandboxItem;
    enabled: boolean;
    /** Текущие параметры примерки (когда enabled). */
    params: SandboxTryOn | null;
    onToggle: (next: boolean) => void;
    onParamsChange: (next: SandboxTryOn) => void;
    onFix: () => void;
    onDismiss: () => void;
}) {
    const [open, setOpen] = useState(false);
    const isFixed = item.wishlistStatus === 'FIXED';
    const inPlan = isFixed && item.inBaseline;   // реально сидит в кармашке
    const needsDate = !item.date && (!params || !params.date);

    const amount = params?.amount ?? item.amount ?? 0;
    const date = params?.date ?? item.date ?? '';
    const stretch = params?.stretchMonths ?? item.stretchMonthsDefault ?? 0;
    const max = item.stretchMonthsMax ?? 0;

    const patch = (p: Partial<SandboxTryOn>) =>
        onParamsChange({
            ref: item.ref, amount, date, stretchMonths: stretch,
            creditRate: item.creditRate, creditTermMonths: item.creditTermMonths, ...p,
        });

    return (
        <div className="rounded-xl px-3 py-2.5"
            style={{
                background: 'var(--color-surface)',
                border: inPlan
                    ? '1px solid var(--color-primary)'
                    : '1px dashed var(--color-border)',
                opacity: item.wishlistStatus === 'OPEN' && !enabled ? 0.85 : 1,
            }}>
            <div className="flex items-center gap-2.5">
                {/* Тумблер */}
                <button
                    onClick={() => onToggle(!enabled)}
                    className="shrink-0 w-9 h-5 rounded-full transition-colors relative"
                    style={{ background: enabled ? 'var(--color-primary)' : 'var(--color-border)' }}
                    aria-label={enabled ? 'Выключить примерку' : 'Примерить'}
                    aria-pressed={enabled}>
                    <span className="absolute top-0.5 w-4 h-4 rounded-full bg-white transition-all"
                        style={{ left: enabled ? '18px' : '2px' }} />
                </button>

                <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-1.5">
                        <span className="text-sm font-medium truncate">{item.name}</span>
                        {inPlan && (
                            <span className="text-[10px] px-1.5 py-0.5 rounded shrink-0"
                                style={{ background: 'var(--color-primary)', color: 'white' }}>
                                в плане
                            </span>
                        )}
                        {isFixed && !inPlan && (
                            <span className="text-[10px] px-1.5 py-0.5 rounded shrink-0"
                                style={{ border: '1px solid var(--color-border)', color: 'var(--color-text-muted)' }}>
                                не в кармашке
                            </span>
                        )}
                    </div>
                    <div className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
                        {fmtRub(amount)}
                        {stretch >= 1 && ` · ${monthlyFor(amount, stretch)}/мес × ${stretch}`}
                        {date && ` · до ${date.slice(8, 10)}.${date.slice(5, 7)}`}
                    </div>
                </div>

                <button onClick={() => setOpen(o => !o)}
                    className="shrink-0 p-1.5 rounded-lg transition-colors hover:bg-white/5"
                    style={{ color: open ? 'var(--color-primary)' : 'var(--color-text-muted)' }}
                    aria-label="Параметры">
                    <SlidersHorizontal size={15} />
                </button>
            </div>

            {needsDate && enabled && (
                <p className="text-[11px] mt-1.5" style={{ color: 'var(--color-danger)' }}>
                    Укажите дату — без неё примерку не посчитать.
                </p>
            )}

            {open && (
                <div className="mt-2.5 pt-2.5 space-y-2.5"
                    style={{ borderTop: '1px solid var(--color-border)' }}>
                    <label className="flex items-center justify-between gap-2 text-xs">
                        <span style={{ color: 'var(--color-text-muted)' }}>Сумма</span>
                        <input type="number" value={amount}
                            onChange={e => patch({ amount: Number(e.target.value) })}
                            className="w-28 px-2 py-1 rounded text-right text-sm"
                            style={{ background: 'var(--color-bg)', border: '1px solid var(--color-border)' }} />
                    </label>
                    <label className="flex items-center justify-between gap-2 text-xs">
                        <span style={{ color: 'var(--color-text-muted)' }}>Дата</span>
                        <input type="date" value={date}
                            onChange={e => patch({ date: e.target.value })}
                            className="px-2 py-1 rounded text-sm"
                            style={{ background: 'var(--color-bg)', border: '1px solid var(--color-border)' }} />
                    </label>
                    {item.kind !== 'CREDIT' && max >= 1 && (
                        <label className="block text-xs">
                            <div className="flex justify-between mb-1" style={{ color: 'var(--color-text-muted)' }}>
                                <span>Растянуть</span>
                                <span>{stretch <= 0 ? 'разом' : `${stretch} мес · ${monthlyFor(amount, stretch)}/мес`}</span>
                            </div>
                            <input type="range" min={0} max={max} step={1} value={stretch}
                                onChange={e => patch({ stretchMonths: Number(e.target.value) })}
                                className="w-full" />
                        </label>
                    )}
                    <div className="flex gap-2 pt-1">
                        <button onClick={onFix}
                            className="flex-1 flex items-center justify-center gap-1 text-xs py-1.5 rounded-lg"
                            style={{ background: 'var(--color-primary)', color: 'white' }}>
                            <Check size={13} /> Зафиксировать
                        </button>
                        <button onClick={onDismiss}
                            className="flex items-center justify-center gap-1 text-xs py-1.5 px-3 rounded-lg"
                            style={{ border: '1px solid var(--color-border)', color: 'var(--color-text-muted)' }}>
                            <X size={13} /> Отложить
                        </button>
                    </div>
                </div>
            )}
        </div>
    );
}
