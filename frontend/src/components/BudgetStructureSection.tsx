import type { AnalyticsReport, FinancialEvent } from '../types/api';

const fmt = (n: number) =>
    new Intl.NumberFormat('ru-RU', { minimumFractionDigits: 0 }).format(n) + ' ₽';

interface Props {
    breakdown: AnalyticsReport['priorityBreakdown'];
    wishlistItems: FinancialEvent[];
}

// ── Wishlist dot tooltip ──────────────────────────────────────────────────────

function WishlistDot({ item }: { item: FinancialEvent }) {
    const isDone = item.factAmount != null && item.factAmount > 0;
    const isPlanned = item.date != null && !isDone;

    const dotStyle: React.CSSProperties = isDone
        ? { background: 'var(--color-accent)' }
        : isPlanned
        ? { background: 'rgba(108,99,255,0.18)', border: '1.5px solid var(--color-accent)' }
        : { background: 'var(--color-surface)', border: '1px solid var(--color-border)' };

    const dateLabel = isDone
        ? `факт: ${new Date(item.date! + 'T00:00:00').toLocaleDateString('ru-RU', { day: 'numeric', month: 'short' })}`
        : item.date
        ? `план: ${new Date(item.date + 'T00:00:00').toLocaleDateString('ru-RU', { month: 'long', year: 'numeric' })}`
        : 'без даты';

    return (
        <div className="relative group" style={{ flexShrink: 0 }}>
            <div style={{ width: 22, height: 22, borderRadius: 5, cursor: 'default', ...dotStyle }} />
            {/* Tooltip */}
            <div className="absolute bottom-full left-1/2 -translate-x-1/2 mb-1.5 hidden group-hover:block z-50
                            whitespace-nowrap rounded-lg px-2.5 py-2 text-xs pointer-events-none"
                style={{ background: 'var(--color-surface-2)', border: '1px solid var(--color-border)',
                         boxShadow: '0 4px 20px rgba(0,0,0,0.5)' }}>
                <div className="font-semibold" style={{ color: 'var(--color-text)' }}>
                    {item.description || item.categoryName || 'Без названия'}
                </div>
                <div style={{ color: 'var(--color-text-muted)' }}>
                    {item.plannedAmount != null ? fmt(item.plannedAmount) : '—'}
                </div>
                <div style={{ color: isDone ? 'var(--color-success)' : 'var(--color-text-muted)' }}>
                    {dateLabel}
                </div>
                {/* Arrow */}
                <div className="absolute top-full left-1/2 -translate-x-1/2"
                    style={{ width: 0, height: 0, borderLeft: '5px solid transparent',
                             borderRight: '5px solid transparent',
                             borderTop: '5px solid var(--color-border)' }} />
            </div>
        </div>
    );
}

// ── Main component ────────────────────────────────────────────────────────────

export default function BudgetStructureSection({ breakdown, wishlistItems }: Props) {
    const b = breakdown;
    const highPct = b.highPlanned > 0
        ? Math.min(Math.round((b.highFact / b.highPlanned) * 100), 100) : 0;

    // Stack bar segments (as % of totalIncomeFact; 1 if no income to avoid division by zero)
    const total = b.totalIncomeFact > 0 ? b.totalIncomeFact : 1;
    const highShare = Math.round((b.highFact / total) * 100);
    const mediumShare = Math.round((b.mediumFact / total) * 100);
    const lowShare = Math.round((b.lowFact / total) * 100);
    const remainder = 100 - highShare - mediumShare - lowShare;
    const isOverspent = remainder < 0;

    // Wishlist dots (max 20)
    const MAX_DOTS = 20;
    const displayed = wishlistItems.slice(0, MAX_DOTS);
    const overflow = wishlistItems.length - MAX_DOTS;
    const doneCount = wishlistItems.filter(i => i.factAmount != null && i.factAmount > 0).length;

    const cardStyle = {
        background: 'var(--color-surface-2)',
        border: '1px solid var(--color-border)',
        borderRadius: 12,
        padding: '13px 15px',
    };

    return (
        <div className="rounded-2xl p-5 space-y-3"
            style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}>
            <h3 className="font-semibold text-sm" style={{ color: 'var(--color-text-muted)' }}>
                СТРУКТУРА МЕСЯЦА
            </h3>

            {/* Card 1: HIGH */}
            <div style={cardStyle}>
                <div className="flex justify-between items-center mb-2">
                    <span className="text-xs font-semibold" style={{ color: 'var(--color-danger)' }}>Обязательные</span>
                    <span className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
                        {fmt(b.highFact)} / {fmt(b.highPlanned)}
                    </span>
                </div>
                <div style={{ background: 'var(--color-border)', borderRadius: 4, height: 6, overflow: 'hidden', marginBottom: 5 }}>
                    <div style={{ width: `${highPct}%`, height: 6, background: 'var(--color-danger)', borderRadius: 4 }} />
                </div>
                <div className="text-xs" style={{
                    color: b.highFact <= b.highPlanned ? 'var(--color-success)' : 'var(--color-danger)',
                }}>
                    {b.highFact <= b.highPlanned
                        ? `сэкономил ${fmt(b.highPlanned - b.highFact)}`
                        : `перерасход ${fmt(b.highFact - b.highPlanned)}`}
                </div>
            </div>

            {/* Card 2: MEDIUM — stack bar */}
            <div style={cardStyle}>
                <div className="flex justify-between items-center mb-2">
                    <span className="text-xs font-semibold" style={{ color: 'var(--color-text)' }}>Прочие расходы</span>
                    <span className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
                        {fmt(b.mediumFact)} · {mediumShare}% бюджета
                    </span>
                </div>
                {/* Stack bar */}
                <div style={{ display: 'flex', height: 8, borderRadius: 5, overflow: 'hidden', gap: 1, marginBottom: 7 }}>
                    <div style={{ flex: highShare, background: 'var(--color-danger)' }} />
                    <div style={{ flex: mediumShare, background: 'var(--color-text-muted)' }} />
                    <div style={{ flex: lowShare, background: 'var(--color-accent)' }} />
                    {!isOverspent && <div style={{ flex: remainder, background: 'var(--color-border)' }} />}
                    {isOverspent && <div style={{ flex: 1, background: 'var(--color-danger)' }} />}
                </div>
                {isOverspent && (
                    <div className="text-xs mb-1" style={{ color: 'var(--color-danger)' }}>
                        Перерасход: {fmt(b.highFact + b.mediumFact + b.lowFact - b.totalIncomeFact)}
                    </div>
                )}
                <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap' }}>
                    {[
                        { label: `обязат. ${highShare}%`, color: 'var(--color-danger)' },
                        { label: `прочие ${mediumShare}%`, color: 'var(--color-text-muted)' },
                        { label: `хотелки ${lowShare}%`, color: 'var(--color-accent)' },
                        !isOverspent
                            ? { label: `остаток ${remainder}%`, color: 'var(--color-border)', border: '1px solid var(--color-text-muted)' }
                            : null,
                    ].filter(Boolean).map((seg, i) => (
                        <span key={i} className="text-xs flex items-center gap-1"
                            style={{ color: 'var(--color-text-muted)' }}>
                            <span style={{ display: 'inline-block', width: 8, height: 8, borderRadius: 2,
                                           background: seg!.color, border: (seg as any).border }} />
                            {seg!.label}
                        </span>
                    ))}
                </div>
            </div>

            {/* Card 3: Wishlist dots */}
            <div style={cardStyle}>
                <div className="flex justify-between items-center mb-2">
                    <span className="text-xs font-semibold" style={{ color: 'var(--color-text-muted)' }}>Хотелки</span>
                    <span className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
                        {doneCount} из {wishlistItems.length} выполнена
                    </span>
                </div>
                {wishlistItems.length === 0 ? (
                    <div className="text-xs" style={{ color: 'var(--color-text-muted)' }}>Список пуст</div>
                ) : (
                    <>
                        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                            {displayed.map(item => <WishlistDot key={item.id} item={item} />)}
                            {overflow > 0 && (
                                <span className="text-xs self-center" style={{ color: 'var(--color-text-muted)' }}>
                                    + ещё {overflow}
                                </span>
                            )}
                        </div>
                        <div style={{ display: 'flex', gap: 12, marginTop: 10, flexWrap: 'wrap' }}>
                            {[
                                { label: 'выполнена', bg: 'var(--color-accent)' },
                                { label: 'в плане', bg: 'rgba(108,99,255,0.18)', border: '1px solid var(--color-accent)' },
                                { label: 'в списке', bg: 'var(--color-surface)', border: '1px solid var(--color-border)' },
                            ].map(s => (
                                <span key={s.label} className="text-xs flex items-center gap-1"
                                    style={{ color: 'var(--color-text-muted)' }}>
                                    <span style={{ display: 'inline-block', width: 9, height: 9, borderRadius: 2,
                                                   background: s.bg, border: s.border }} />
                                    {s.label}
                                </span>
                            ))}
                        </div>
                    </>
                )}
            </div>
        </div>
    );
}
