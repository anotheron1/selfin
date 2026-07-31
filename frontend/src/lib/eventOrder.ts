/**
 * Детерминированный порядок записей журнала (ANO-7).
 *
 * У операции нет времени: в модели есть только `date` (день операции) и `createdAt`
 * (момент ввода). Поэтому внутри одного дня единственный осмысленный ключ — `createdAt`,
 * а `id` добирает стабильность: пачка recurring-событий создаётся одной транзакцией,
 * и `createdAt` у всех практически совпадает.
 *
 * Раньше порядок внутри дня задавался ИМЕНЕМ (`localeCompare`), то есть алфавитом,
 * а не хронологией; равные имена давали 0 и порядок «плавал» между запросами.
 */

export interface OrderableEvent {
    id: string;
    date: string | null;
    createdAt: string;
}

/** Хронологический порядок: старое → свежее. Стабилен при любых равных ключах. */
export function compareByRecency(a: OrderableEvent, b: OrderableEvent): number {
    const byDate = (a.date ?? '').localeCompare(b.date ?? '');
    if (byDate !== 0) return byDate;
    const byCreated = (a.createdAt ?? '').localeCompare(b.createdAt ?? '');
    if (byCreated !== 0) return byCreated;
    return a.id.localeCompare(b.id);
}

/**
 * Обратный порядок: свежее сверху. Аргументы переставлены, а не отрицаются:
 * `-0` от отрицания сортировке безразличен, но ломает строгие сравнения с 0.
 */
export function byRecencyDesc(a: OrderableEvent, b: OrderableEvent): number {
    return compareByRecency(b, a);
}
