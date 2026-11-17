import { X } from 'lucide-react';
import { cn } from '@/lib/utils';
import { closeHint, hideAllHints, type HintId } from '../../lib/hints';
import type { HintsSettings } from '../../types/api';

const ARROW = { up: '-top-1.5', down: '-bottom-1.5', left: '-left-1.5' } as const;

interface BubbleLook {
    text: string;
    arrow: keyof typeof ARROW;
    /** Где на стороне уголок — к чему пузырь говорит. */
    arrowClass: string;
    className?: string;
}

/**
 * Пузырь подсказки первого дня (доски S2-P1, S2-P-1440): текст, крестик, у первой — «Скрыть все подсказки».
 * Место и уголок задаёт экран: пузырь лежит над соседями, а не сдвигает их.
 */
export function HintBubble({ text, arrow, arrowClass, onClose, onHideAll, className }: BubbleLook & {
    onClose: () => void;
    onHideAll?: () => void;
}) {
    return (
        <div role="note" className={cn('absolute z-30 rounded-2xl bg-sf-page py-2.5 pl-3 pr-10 text-sf-note shadow-sf-capsule lg:py-3 lg:pl-3.5 lg:pr-11 lg:text-sf-control', className)}>
            <span aria-hidden="true" className={cn('absolute h-3 w-3 rotate-45 bg-sf-page', ARROW[arrow], arrowClass)} />
            <span className="relative">{text}</span>
            {onHideAll && (
                <button type="button" onClick={onHideAll} className="relative mt-1.5 block font-semibold text-sf-accent">
                    Скрыть все подсказки
                </button>
            )}
            <button type="button" aria-label="Закрыть подсказку" onClick={onClose}
                className="absolute right-0.5 top-0.5 flex h-9 w-9 items-center justify-center text-sf-muted lg:right-1 lg:top-1">
                <X size={14} strokeWidth={2} aria-hidden="true" />
            </button>
        </div>
    );
}

/**
 * Подсказка `id` на своём месте: видна, пока она среди `shown` (`firstDay`); крестик закрывает её одну,
 * «Скрыть все подсказки» — у первой видимой — выключает все.
 */
export function DayHint({ id, shown, settings, onChange, ...look }: BubbleLook & {
    id: HintId;
    shown: HintId[];
    settings: HintsSettings | null;
    onChange: (next: HintsSettings) => void;
}) {
    if (!settings || !shown.includes(id)) return null;
    return (
        <HintBubble {...look} onClose={() => onChange(closeHint(settings, id))}
            onHideAll={shown[0] === id ? () => onChange(hideAllHints(settings)) : undefined} />
    );
}
