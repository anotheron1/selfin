import { useState } from 'react';
import { Check, Lock, RotateCcw, X, SlidersHorizontal } from 'lucide-react';
import type { SandboxItem, SandboxTryOn } from '../../types/api';
import { fmtRub } from '../../lib/format';
import { monthlyFor } from '../../lib/sandboxMath';

/**
 * Строка окна примерки (ANO-16 §7, модель уточнена в ANO-30).
 *
 * Три состояния:
 * — ВЫКЛ: вне примерки (по умолчанию при входе);
 * — ВКЛ: участвует только в линии «с примеркой»; здесь появляется «Зафиксировать»;
 * — ВКЛ + замок: зафиксировано, живёт в реальном плане и на Dashboard; параметры
 *   заперты, выход — «вернуть в обсуждение».
 */
export default function SandboxItemRow({
    item, enabled, locked, params, onToggle, onParamsChange, onFix, onUnfix, onDismiss,
}: {
    item: SandboxItem;
    enabled: boolean;
    /** Зафиксировано: тумблер заперт, правки — только после возврата в обсуждение. */
    locked: boolean;
    /** Текущие параметры примерки (когда enabled и не locked). */
    params: SandboxTryOn | null;
    onToggle: (next: boolean) => void;
    onParamsChange: (next: SandboxTryOn) => void;
    onFix: () => void;
    onUnfix: () => void;
    onDismiss: () => void;
}) {
    const [open, setOpen] = useState(false);
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
                border: locked
                    ? '1px solid var(--color-primary)'
                    : '1px dashed var(--color-border)',
                opacity: !enabled ? 0.85 : 1,
            }}>
            <div className="flex items-center gap-2.5">
                {/* Тумблер: заперт у зафиксированного */}
                <button
                    onClick={() => !locked && onToggle(!enabled)}
                    disabled={locked}
                    title={locked ? 'Зафиксировано — вернуть в обсуждение можно в параметрах' : undefined}
                    className="shrink-0 w-9 h-5 rounded-full transition-colors relative"
                    style={{
                        background: enabled ? 'var(--color-primary)' : 'var(--color-border)',
                        cursor: locked ? 'not-allowed' : 'pointer',
                    }}
                    aria-label={locked ? 'Зафиксировано' : enabled ? 'Убрать из примерки' : 'Примерить'}
                    aria-pressed={enabled}>
                    <span className="absolute top-0.5 w-4 h-4 rounded-full bg-white transition-all
                        flex items-center justify-center"
                        style={{ left: enabled ? '18px' : '2px' }}>
                        {locked && <Lock size={9} color="var(--color-primary)" />}
                    </span>
                </button>

                <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-1.5">
                        <span className="text-sm font-medium truncate">{item.name}</span>
                        {locked && (
                            <span className="text-[10px] px-1.5 py-0.5 rounded shrink-0"
                                style={{ background: 'var(--color-primary)', color: 'white' }}>
                                в плане
                            </span>
                        )}
                        {locked && !item.inBaseline && (
                            <span className="text-[10px] px-1.5 py-0.5 rounded shrink-0"
                                title={item.kind === 'CREDIT'
                                    ? 'У кредита поток начинается с покупки — платежи приходят отдельными событиями'
                                    : !item.date
                                        ? 'Без срока нечего раскладывать по месяцам — деньги не резервируются'
                                        : 'Цель уже накоплена или срок прошёл — деньги не резервируются'}
                                style={{ border: '1px solid var(--color-border)', color: 'var(--color-text-muted)' }}>
                                {item.kind === 'CREDIT' ? 'платежи отдельно'
                                    : !item.date ? 'без срока' : 'не резервируется'}
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

            {needsDate && enabled && !locked && (
                <p className="text-[11px] mt-1.5" style={{ color: 'var(--color-danger)' }}>
                    Укажите дату — без неё примерку не посчитать.
                </p>
            )}

            {open && (
                <div className="mt-2.5 pt-2.5 space-y-2.5"
                    style={{ borderTop: '1px solid var(--color-border)' }}>
                    {locked && (
                        <p className="text-[11px]" style={{ color: 'var(--color-text-muted)' }}>
                            Решение принято — сумма учтена в кармашке и на дашборде.
                            Чтобы менять или примерять, верните в обсуждение.
                        </p>
                    )}
                    <label className="flex items-center justify-between gap-2 text-xs">
                        <span style={{ color: 'var(--color-text-muted)' }}>Сумма</span>
                        <input type="number" value={amount} disabled={locked}
                            onChange={e => patch({ amount: Number(e.target.value) })}
                            className="w-28 px-2 py-1 rounded text-right text-sm disabled:opacity-50"
                            style={{ background: 'var(--color-bg)', border: '1px solid var(--color-border)' }} />
                    </label>
                    <label className="flex items-center justify-between gap-2 text-xs">
                        <span style={{ color: 'var(--color-text-muted)' }}>Дата</span>
                        <input type="date" value={date} disabled={locked}
                            onChange={e => patch({ date: e.target.value })}
                            className="px-2 py-1 rounded text-sm disabled:opacity-50"
                            style={{ background: 'var(--color-bg)', border: '1px solid var(--color-border)' }} />
                    </label>
                    {item.kind !== 'CREDIT' && max >= 1 && (
                        <label className="block text-xs">
                            <div className="flex justify-between mb-1" style={{ color: 'var(--color-text-muted)' }}>
                                <span>Растянуть</span>
                                <span>{stretch <= 0 ? 'разом' : `${stretch} мес · ${monthlyFor(amount, stretch)}/мес`}</span>
                            </div>
                            <input type="range" min={0} max={max} step={1} value={stretch} disabled={locked}
                                onChange={e => patch({ stretchMonths: Number(e.target.value) })}
                                className="w-full disabled:opacity-50" />
                        </label>
                    )}
                    <div className="flex gap-2 pt-1">
                        {locked ? (
                            <button onClick={onUnfix}
                                className="flex-1 flex items-center justify-center gap-1 text-xs py-1.5 rounded-lg"
                                style={{ border: '1px solid var(--color-primary)', color: 'var(--color-primary)' }}>
                                <RotateCcw size={13} /> Вернуть в обсуждение
                            </button>
                        ) : enabled ? (
                            // «Зафиксировать» появляется только у включённого в примерку —
                            // так видно, что это следующий шаг ПОСЛЕ примерки, а не её часть.
                            <button onClick={onFix} disabled={needsDate}
                                className="flex-1 flex items-center justify-center gap-1 text-xs py-1.5 rounded-lg disabled:opacity-40"
                                style={{ background: 'var(--color-primary)', color: 'white' }}>
                                <Check size={13} /> Зафиксировать в плане
                            </button>
                        ) : (
                            <span className="flex-1 text-[11px] self-center"
                                style={{ color: 'var(--color-text-muted)' }}>
                                Включите тумблер, чтобы примерить
                            </span>
                        )}
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
