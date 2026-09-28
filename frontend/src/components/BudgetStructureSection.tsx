import type { AnalyticsReport } from '../types/api';
import { PRIORITY_DOT_CONFIG } from '../lib/priority';
import { monthStructureRows } from '../lib/monthStructure';

const fmt = (n: number) =>
    new Intl.NumberFormat('ru-RU', { minimumFractionDigits: 0 }).format(n) + ' ₽';

interface Props {
    breakdown: AnalyticsReport['priorityBreakdown'];
}

/**
 * «Структура месяца» — по каждому характеру факт и план числами (ANO-165). Без оценок: ни
 * «сэкономил», ни «перерасход», ни процентов, ни счётчика хотелок. Карточка хотелок ушла вместе
 * со своей выборкой (ANO-161): она считала строки «Хотелка» прошлых месяцев, а не хотелки.
 */
export default function BudgetStructureSection({ breakdown }: Props) {
    return (
        <div className="rounded-2xl p-5 space-y-3"
            style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}>
            <div>
                <h3 className="font-semibold text-sm" style={{ color: 'var(--color-text-muted)' }}>
                    СТРУКТУРА МЕСЯЦА
                </h3>
                <p className="text-xs mt-1" style={{ color: 'var(--color-text-muted)' }}>
                    Сколько потрачено по каждому характеру и сколько на него запланировано в этом месяце
                </p>
            </div>
            {monthStructureRows(breakdown).map((row) => (
                <div key={row.priority} className="flex items-center justify-between gap-3 text-sm">
                    <span className="flex items-center gap-2">
                        {/* Цвет — только характер строки, не оценка. */}
                        <span style={{ display: 'inline-block', width: 8, height: 8, borderRadius: 4,
                                       background: PRIORITY_DOT_CONFIG[row.priority].color }} />
                        {row.name}
                    </span>
                    <span style={{ color: 'var(--color-text-muted)' }}>
                        {fmt(row.fact)} из {fmt(row.plan)}
                    </span>
                </div>
            ))}
        </div>
    );
}
