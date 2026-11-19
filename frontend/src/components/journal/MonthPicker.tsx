import { useEffect, useState } from 'react';
import { ChevronDown, ChevronLeft, ChevronRight, ChevronUp } from 'lucide-react';
import { chipVariants, iconButtonVariants } from '@/ds';
import { cn } from '@/lib/utils';

/**
 * Месяц и год журнала (срез 3, п. 7, ANO-257; доски S3-M1, S3-W5). Стрелки — соседний месяц одним касанием;
 * нажатие на месяц — сетка года со стрелками года, точка — в месяце, где есть строки. Один на «Журнал» и
 * «Итоги месяца»: переключатель среза 5 возьмёт этот же.
 */

const NAMES = ['Январь', 'Февраль', 'Март', 'Апрель', 'Май', 'Июнь', 'Июль', 'Август', 'Сентябрь', 'Октябрь', 'Ноябрь', 'Декабрь'];
const SHORT = ['Янв.', 'Февр.', 'Март', 'Апр.', 'Май', 'Июнь', 'Июль', 'Авг.', 'Сент.', 'Окт.', 'Нояб.', 'Дек.'];

/** «Ноябрь 2026»; месяц — с нуля. */
export const monthTitle = (year: number, month: number) => `${NAMES[month]} ${year}`;

/** Соседний месяц, через границу года. */
export function stepMonth(year: number, month: number, step: -1 | 1): { year: number; month: number } {
    const m = month + step;
    return m < 0 ? { year: year - 1, month: 11 } : m > 11 ? { year: year + 1, month: 0 } : { year, month: m };
}

/** Выбранный месяц отмечается только в сетке своего года. */
export const selectedIn = (gridYear: number, year: number, month: number): number | null =>
    gridYear === year ? month : null;

const arrow = cn(iconButtonVariants({ variant: 'plain' }), '[&_svg]:size-5');
const yearArrow = cn(iconButtonVariants({ variant: 'plain' }), 'bg-sf-chip hover:bg-sf-chip-hover [&_svg]:size-5');

export function MonthGrid({ year, selected, dots, onYear, onPick }: {
    year: number;
    /** Выбранный месяц, если сетка показывает его год; иначе null. */
    selected: number | null;
    /** Месяцы со строками, с единицы — как отвечает сервер. */
    dots: number[];
    onYear: (step: -1 | 1) => void;
    onPick: (month: number) => void;
}) {
    return (
        <div role="dialog" aria-label="Месяц и год"
            className="w-full rounded-sf-sheet bg-sf-page p-3.5 font-sf text-sf-ink shadow-sf-capsule">
            <div className="mb-3 flex items-center justify-between">
                <button type="button" aria-label="Предыдущий год" className={yearArrow} onClick={() => onYear(-1)}><ChevronLeft /></button>
                <span className="text-sf-button font-semibold tabular-nums">{year}</span>
                <button type="button" aria-label="Следующий год" className={yearArrow} onClick={() => onYear(1)}><ChevronRight /></button>
            </div>
            <div className="grid grid-cols-3 gap-2">
                {SHORT.map((label, i) => {
                    const on = i === selected;
                    return (
                        <button key={label} type="button" aria-pressed={on} onClick={() => onPick(i)}
                            className={cn('flex h-[48px] flex-col items-center justify-center gap-1 rounded-sf-field text-sf-control transition-colors',
                                on ? 'bg-sf-accent font-semibold text-sf-on-accent' : 'bg-sf-plate hover:bg-sf-plate-hover')}>
                            {label}{dots.includes(i + 1) && <span data-dot aria-hidden="true" className="size-1 rounded-full bg-current" />}
                        </button>
                    );
                })}
            </div>
            <p className="mt-3 text-center text-sf-caption text-sf-muted">точка — в месяце есть записи</p>
        </div>
    );
}

export default function MonthPicker({ year, month, onChange, loadMonths }: {
    year: number;
    month: number;
    onChange: (year: number, month: number) => void;
    /** Месяцы года со строками — GET /events/months. */
    loadMonths: (year: number) => Promise<number[]>;
}) {
    const [open, setOpen] = useState(false);
    const [gridYear, setGridYear] = useState(year);
    const [dots, setDots] = useState<number[]>([]);

    useEffect(() => {
        if (!open) return;
        let live = true;
        setDots([]);
        loadMonths(gridYear).then((m) => { if (live) setDots(m); }, () => {});
        return () => { live = false; };
    }, [open, gridYear, loadMonths]);

    useEffect(() => {
        if (!open) return;
        const esc = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false); };
        document.addEventListener('keydown', esc);
        return () => document.removeEventListener('keydown', esc);
    }, [open]);

    const step = (s: -1 | 1) => { const n = stepMonth(year, month, s); onChange(n.year, n.month); };
    const Chevron = open ? ChevronUp : ChevronDown;
    return (
        <div className="relative flex items-center gap-1">
            <button type="button" aria-label="Предыдущий месяц" className={arrow} onClick={() => step(-1)}><ChevronLeft /></button>
            <button type="button" aria-expanded={open} aria-haspopup="dialog"
                onClick={() => { setGridYear(year); setOpen((o) => !o); }}
                className={cn(chipVariants({ size: 'm' }), 'font-semibold [&_svg]:size-4', open && 'shadow-[inset_0_0_0_1.5px_var(--sf-accent)]')}>
                {monthTitle(year, month)}<Chevron />
            </button>
            <button type="button" aria-label="Следующий месяц" className={arrow} onClick={() => step(1)}><ChevronRight /></button>
            {open && <>
                {/* Нажатие мимо сетки закрывает её; на телефоне страница под ней притушена (S3-M1). */}
                <div aria-hidden="true" className="fixed inset-0 z-40 bg-[color-mix(in_srgb,var(--sf-ink)_12%,transparent)] lg:bg-transparent" onClick={() => setOpen(false)} />
                <div className="absolute left-0 top-full z-50 mt-2 w-[min(366px,calc(100vw-24px))] lg:w-[410px]">
                    <MonthGrid year={gridYear} selected={selectedIn(gridYear, year, month)} dots={dots}
                        onYear={(s) => setGridYear((y) => y + s)}
                        onPick={(m) => { setOpen(false); onChange(gridYear, m); }} />
                </div>
            </>}
        </div>
    );
}
