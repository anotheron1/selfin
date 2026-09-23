import { PRIORITY_DOT_CONFIG, priorityTitle } from '../lib/priority';
import type { Priority } from '../types/api';

interface PriorityButtonProps {
    priority: Priority;
    onCycle?: () => void;
    disabled?: boolean;
}

/**
 * Цветная точка характера строки (ANO-173). Если передан onCycle — кликабельна (циклично меняет характер).
 * Обязательство → красная, ожидание → жёлтая, хотелка → голубая; имя и подсказка — во всплывающей подсказке.
 */
export default function PriorityButton({ priority, onCycle, disabled }: PriorityButtonProps) {
    const { color } = PRIORITY_DOT_CONFIG[priority];
    const title = priorityTitle(priority);
    const interactive = !!onCycle && !disabled;

    return (
        <span
            title={title}
            role={interactive ? 'button' : undefined}
            tabIndex={interactive ? 0 : undefined}
            onClick={interactive ? (e) => { e.stopPropagation(); onCycle!(); } : undefined}
            onKeyDown={interactive ? (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); e.stopPropagation(); onCycle!(); } } : undefined}
            style={{
                display: 'inline-block',
                width: 8,
                height: 8,
                borderRadius: '50%',
                backgroundColor: color,
                flexShrink: 0,
                cursor: interactive ? 'pointer' : 'default',
            }}
        />
    );
}
