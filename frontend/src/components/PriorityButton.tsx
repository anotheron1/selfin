import { PRIORITY_DOT_CONFIG } from '../lib/priority';
import type { Priority } from '../types/api';

interface PriorityButtonProps {
    priority: Priority;
    onCycle?: () => void;
    disabled?: boolean;
}

/**
 * Цветная точка приоритета. Если передан onCycle — кликабельна (циклично меняет приоритет).
 * HIGH → красная (#f87171), MEDIUM → жёлтая (#facc15), LOW → голубая (#60a5fa).
 */
export default function PriorityButton({ priority, onCycle, disabled }: PriorityButtonProps) {
    const { color, title } = PRIORITY_DOT_CONFIG[priority];
    const interactive = !!onCycle && !disabled;

    return (
        <span
            title={title}
            onClick={interactive ? (e) => { e.stopPropagation(); onCycle!(); } : undefined}
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
