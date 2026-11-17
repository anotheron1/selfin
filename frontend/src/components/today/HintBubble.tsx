import { X } from 'lucide-react';
import { cn } from '@/lib/utils';

const ARROW = { up: '-top-1.5', down: '-bottom-1.5', left: '-left-1.5' } as const;

/**
 * Пузырь подсказки первого дня (доски S2-P1, S2-P-1440): текст, крестик, у первой — «Скрыть все подсказки».
 * Место и уголок задаёт экран: пузырь лежит над соседями, а не сдвигает их.
 */
export function HintBubble({ text, arrow, arrowClass, onClose, onHideAll, className }: {
    text: string;
    arrow: keyof typeof ARROW;
    /** Где на стороне уголок — к чему пузырь говорит. */
    arrowClass: string;
    onClose: () => void;
    onHideAll?: () => void;
    className?: string;
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
