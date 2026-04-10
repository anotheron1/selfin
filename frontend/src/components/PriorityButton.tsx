import type { Priority } from '../types/api';

interface PriorityButtonProps {
    priority: Priority;
    onCycle: () => void;
    disabled?: boolean;
}

/**
 * Кнопка циклической смены приоритета события: HIGH → MEDIUM → LOW → HIGH.
 * HIGH — красный «обяз», LOW — приглушённый «хотелка», MEDIUM скрыт.
 * Для MEDIUM показывает точку для возможности смены приоритета.
 */
export default function PriorityButton({ priority, onCycle, disabled }: PriorityButtonProps) {
    const handleClick = (e: React.MouseEvent) => {
        e.stopPropagation();
        if (!disabled) onCycle();
    };

    if (priority === 'HIGH') {
        return (
            <button
                onClick={handleClick}
                title="Обязательный — нажмите для смены приоритета"
                disabled={disabled}
                className="shrink-0 text-xs px-1.5 py-0 rounded border leading-5 font-normal"
                style={{ color: 'hsl(var(--destructive))', borderColor: 'hsl(var(--destructive) / 0.6)' }}
            >
                обяз
            </button>
        );
    }

    if (priority === 'LOW') {
        return (
            <button
                onClick={handleClick}
                title="Хотелка — нажмите для смены приоритета"
                disabled={disabled}
                className="shrink-0 text-xs px-1.5 py-0 rounded border leading-5 font-normal"
                style={{ color: 'var(--color-text-muted)', borderColor: 'var(--color-border)' }}
            >
                хотелка
            </button>
        );
    }

    // MEDIUM: плановый элемент — читаемая метка в стиле значка
    return (
        <button
            onClick={handleClick}
            title="Плановый — нажмите для смены приоритета"
            disabled={disabled}
            className="shrink-0 text-xs px-1.5 py-0 rounded border leading-5 font-normal opacity-50 hover:opacity-80 transition-opacity"
            style={{ color: 'var(--color-text-muted)', borderColor: 'var(--color-border)' }}
        >
            план
        </button>
    );
}
