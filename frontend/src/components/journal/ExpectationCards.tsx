import type { FinancialEvent } from '../../types/api';
import type { ExpectationCard } from '../../lib/journalSections';
import { PRIORITY_DOT_CONFIG } from '../../lib/priority';

const fmt = (n: number) => new Intl.NumberFormat('ru-RU', { minimumFractionDigits: 0 }).format(n) + ' ₽';

/** «4 × 8 000 ₽», у одной строки — просто сумма, у разных сумм — итог. */
function cardAmount(card: ExpectationCard): string {
    const sign = card.income ? '+' : '';
    return card.each != null && card.count > 1
        ? `${card.count} × ${sign}${fmt(card.each)}`
        : `${sign}${fmt(card.total)}`;
}

const hint = PRIORITY_DOT_CONFIG.MEDIUM.hint;

interface Props {
    cards: ExpectationCard[];
    /** Касание даты — запись факта на эту строку: у ожиданий это самое частое действие. */
    onLine: (plan: FinancialEvent) => void;
}

/**
 * Карточки ожиданий (ANO-176), по одной на категорию. Только плановые строки и «✓» у даты,
 * на которую записан факт. Сколько записано из ожидаемого, процентов и цвета за превышение
 * нет — канон, «Характер плановой строки», граница по запрету 1. Вид под переделку на
 * дизайн-сессии; что куда — lib/journalSections.
 */
export default function ExpectationCards({ cards, onLine }: Props) {
    return (
        <div className="flex flex-col gap-2.5">
            {cards.map((card) => (
                <div key={card.key} className="rounded-2xl px-3.5 py-3 flex flex-col gap-2"
                    style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}>
                    <div className="flex items-center justify-between gap-2">
                        <span className="flex items-center gap-2 min-w-0">
                            <span className="w-2 h-2 rounded-full shrink-0" style={{ background: PRIORITY_DOT_CONFIG.MEDIUM.color }} />
                            <span className="text-sm font-medium truncate">{card.title}</span>
                        </span>
                        <span className="text-xs whitespace-nowrap" style={{ color: 'var(--color-text-muted)' }}>
                            {cardAmount(card)}
                        </span>
                    </div>
                    <div className="flex flex-wrap gap-1.5">
                        {card.lines.map((line) => (
                            <button key={line.id} type="button" onClick={() => onLine(line.event)}
                                title="Записать факт"
                                className="text-xs px-2.5 min-h-8 rounded-full hover:bg-white/5"
                                style={{
                                    border: '1px solid var(--color-border)',
                                    color: line.recorded ? 'var(--color-text-muted)' : 'var(--color-text)',
                                }}>
                                {line.label}{line.recorded ? ' ✓' : ''}
                            </button>
                        ))}
                    </div>
                    {card.notes.map((note) => (
                        <span key={note} className="text-xs" style={{ color: 'var(--color-text-muted)' }}>{note}</span>
                    ))}
                </div>
            ))}
            <p className="text-xs px-1 leading-relaxed" style={{ color: 'var(--color-text-muted)' }}>
                {hint[0].toUpperCase() + hint.slice(1)}. ✓ — на строку уже записан факт; сам факт — в журнале.
            </p>
        </div>
    );
}
