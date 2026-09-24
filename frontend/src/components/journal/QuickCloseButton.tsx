import { Check } from 'lucide-react';
import type { QuickClose } from '../../lib/quickClose';

interface Props {
    decision: QuickClose;
    /** Подпись для всплывающей подсказки и экранного чтеца: что сделает касание. */
    label: string;
    busy?: boolean;
    onTap: () => void;
    onAskDate: () => void;
}

/**
 * Тихий кружок справа от суммы (ANO-176): как галочка в списке дел. Пилюля «✓ Оплачено» первой
 * редакции отвергнута владельцем — на плотном месяце она кричала бы с каждой строки.
 * Что делает касание, решает lib/quickClose; здесь только вид, и на дизайн-сессии его переделают.
 * Место под кружок держится у каждой строки, чтобы суммы стояли ровно.
 */
export default function QuickCloseButton({ decision, label, busy, onTap, onAskDate }: Props) {
    if (decision.kind === 'done') {
        return (
            <span title="Факт записан" className="w-11 h-11 shrink-0 flex items-center justify-center">
                <span className="w-5 h-5 rounded-full flex items-center justify-center"
                    style={{ background: 'rgba(74,222,128,0.16)' }}>
                    <Check size={12} strokeWidth={3} style={{ color: 'var(--color-success)' }} />
                </span>
            </span>
        );
    }
    if (decision.kind === 'none') return <span className="w-11 shrink-0" aria-hidden="true" />;
    return (
        <button
            type="button"
            aria-label={label}
            title={label}
            disabled={busy}
            onClick={(e) => { e.stopPropagation(); if (decision.kind === 'tap') onTap(); else onAskDate(); }}
            className="w-11 h-11 shrink-0 flex items-center justify-center rounded-full hover:bg-white/5 disabled:opacity-40"
        >
            <span className="w-5 h-5 rounded-full" style={{ border: '1.5px solid rgba(255,255,255,0.28)' }} />
        </button>
    );
}
