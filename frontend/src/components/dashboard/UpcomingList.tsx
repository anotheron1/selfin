import { useState } from 'react';
import FactCreateSheet from '../FactCreateSheet';
import { groupUpcoming, sumUpcoming } from '../../lib/upcoming';
import { fmtRub } from '../strategy/strategyChartUtils';
import type { UpcomingItem } from '../../types/api';

interface Props {
    items: UpcomingItem[];
    /** Конец горизонта кармашка — до какой даты смотрит список. */
    horizonEnd: string;
    /** Записали факт — родитель перезапрашивает кармашек вместе со списком. */
    onRecorded: () => void;
}

/** «21 сентября» — дата человеческим языком, без года: горизонт всегда близкий. */
function humanDate(iso: string): string {
    const months = ['января', 'февраля', 'марта', 'апреля', 'мая', 'июня',
        'июля', 'августа', 'сентября', 'октября', 'ноября', 'декабря'];
    const [, m, d] = iso.split('-').map(Number);
    return `${d} ${months[m - 1]}`;
}

/**
 * «Осталось потратить» (ANO-119) — платёжный календарь по категориям на горизонте кармашка.
 *
 * <p>Блок отвечает на вопрос «что ещё уйдёт», а не «сколько я уже потратил». Прежний
 * «План / факт за месяц» сравнивал план целиком с фактом на сегодня и подписывал разницу
 * приговором: 16-го числа девять строк с отметкой о неисполнении, хотя ипотека платится
 * 21-го. Разбор прошлого переехал на «Аналитику», куда заходят намеренно.
 *
 * <p>Здесь нет оценок — ни слов, ни цвета. Правило 12: предложение да, упрёк нет.
 * Сторож в upcoming.test.ts читает этот файл и запрещает вернуть их даже в комментарий —
 * поэтому сами слова здесь не пишем, как и в сторожe Clock (ANO-39).
 */
export default function UpcomingList({ items, horizonEnd, onRecorded }: Props) {
    const [factFor, setFactFor] = useState<UpcomingItem | null>(null);
    const { overdue, byDate } = groupUpcoming(items);

    const row = (item: UpcomingItem) => (
        <div key={item.id ?? `${item.date}-${item.description}`}
             className="flex items-baseline justify-between gap-2 py-1 min-w-0">
            <div className="min-w-0">
                <span className="text-sm truncate">{item.categoryName ?? item.description ?? '—'}</span>
                {item.categoryName && item.description && (
                    <span className="text-xs ml-1.5 truncate" style={{ color: 'var(--color-text-muted)' }}>
                        {item.description}
                    </span>
                )}
                {item.wishlist && (
                    <span className="text-xs ml-1.5" style={{ color: 'var(--color-text-muted)' }}>
                        хотелка
                    </span>
                )}
            </div>
            <div className="flex items-baseline gap-2 shrink-0">
                <span className="text-sm">{fmtRub(item.amount)}</span>
                {item.id && (
                    <button
                        onClick={() => setFactFor(item)}
                        className="text-xs underline"
                        style={{ color: 'var(--color-text-muted)' }}>
                        записать
                    </button>
                )}
            </div>
        </div>
    );

    return (
        <div className="rounded-2xl p-5 space-y-3"
             style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}>
            <div className="flex items-baseline justify-between gap-2">
                <h3 className="font-semibold text-sm" style={{ color: 'var(--color-text-muted)' }}>
                    ОСТАЛОСЬ ПОТРАТИТЬ
                </h3>
                {items.length > 0 && (
                    <span className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
                        {fmtRub(sumUpcoming(items))} до {humanDate(horizonEnd)}
                    </span>
                )}
            </div>

            {items.length === 0 && (
                <p className="text-sm" style={{ color: 'var(--color-text-muted)' }}>
                    До {humanDate(horizonEnd)} плановых трат не осталось.
                </p>
            )}

            {overdue.length > 0 && (
                <div className="space-y-0.5">
                    <p className="text-xs" style={{ color: 'var(--color-text-muted)' }}>раньше по плану</p>
                    {overdue.map(row)}
                </div>
            )}

            {byDate.map(group => (
                <div key={group.date} className="space-y-0.5">
                    <p className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
                        {humanDate(group.date)}
                    </p>
                    {group.items.map(row)}
                </div>
            ))}

            {factFor && factFor.id && (
                <FactCreateSheet
                    planId={factFor.id}
                    planDescription={factFor.description ?? factFor.categoryName ?? ''}
                    planPriority="MEDIUM"
                    open={!!factFor}
                    onClose={() => setFactFor(null)}
                    onCreated={() => { setFactFor(null); onRecorded(); }}
                />
            )}
        </div>
    );
}
