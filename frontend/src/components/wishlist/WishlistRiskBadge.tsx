import type { RiskLevel } from './wishlistUtils';

interface Props {
    /** Цветовой уровень риска; undefined — item выключен (серая точка). */
    level: RiskLevel | undefined;
    /** Текстовая подпись рядом с точкой (опционально). */
    label?: string;
}

const COLOR: Record<RiskLevel, string> = {
    green: '#10b981',
    yellow: '#f59e0b',
    red: '#ef4444',
};

const TITLE: Record<RiskLevel, string> = {
    green: 'Безопасно: счёт и капитал держатся выше порогов на всём горизонте.',
    yellow: 'Впритык: счёт или капитал приближается к порогу в каком-то месяце.',
    red: 'Риск: счёт уходит в минус или капитал падает ниже порога.',
};

const DISABLED_TITLE = 'Не учитывается в симуляции (галочка снята).';

/**
 * Маленькая цветная точка с риском solo-влияния item'а на счёт/капитал.
 * Зелёный/жёлтый/красный по {@link RiskLevel}; серый, когда {@code level} не задан.
 * Tooltip — нативный {@code title} с русским пояснением.
 */
export default function WishlistRiskBadge({ level, label }: Props) {
    const color = level ? COLOR[level] : '#6b7280';
    const title = level ? TITLE[level] : DISABLED_TITLE;
    return (
        <span className="inline-flex items-center gap-1.5" title={title}>
            <span
                className="inline-block rounded-full"
                style={{ width: 9, height: 9, background: color }}
            />
            {label && (
                <span className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
                    {label}
                </span>
            )}
        </span>
    );
}
