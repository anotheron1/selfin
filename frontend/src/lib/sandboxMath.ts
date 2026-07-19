import type { SandboxItem, SandboxRef, SandboxTryOn } from '../types/api';

/**
 * Производные для ползунка растяжки и скоуп-чипа. НИКАКИХ правил движка —
 * только арифметика UI (сам расчёт кармашка всегда на сервере, ANO-16 §5).
 */

/** Взнос в месяц при растяжке на n месяцев (0/меньше → вся сумма разом). */
export function monthlyFor(amount: number, months: number): number {
    if (months <= 1) return amount;
    return Math.round((amount / months) * 100) / 100;
}

/** Обратное: сколько месяцев при заданном взносе (минимум 1). */
export function monthsFor(amount: number, monthly: number): number {
    if (monthly <= 0) return 1;
    return Math.max(1, Math.round(amount / monthly));
}

/** Ключ ref для сравнения/Map (совпадает с бэковым type:id). */
export function refKey(ref: SandboxRef): string {
    return `${ref.type}:${ref.id}`;
}

export function sameRef(a: SandboxRef | null, b: SandboxRef | null): boolean {
    if (a == null || b == null) return a === b;
    return a.type === b.type && a.id === b.id;
}

/**
 * Скоуп «до реализации» = DATE:<макс. targetDate включённых датированных элементов>
 * или null, если ни один включённый элемент не датирован (§7). tryOn-даты и даты
 * включённых items берутся как есть (YYYY-MM-DD, лексикографический max = хронологический).
 */
export function realizationScope(dates: (string | null | undefined)[]): string | null {
    const valid = dates.filter((d): d is string => !!d);
    if (valid.length === 0) return null;
    const max = valid.reduce((a, b) => (b > a ? b : a));
    return `DATE:${max}`;
}

/** Последний день месяца даты (для fundTargetDate конверсии растянутой хотелки, §8). */
export function lastDayOfMonth(isoDate: string): string {
    const [y, m] = isoDate.split('-').map(Number);
    const last = new Date(y, m, 0).getDate(); // day 0 следующего месяца = последний текущего
    return `${y}-${String(m).padStart(2, '0')}-${String(last).padStart(2, '0')}`;
}

/** Дефолтный tryOn для item (из серверных дефолтов §4). */
export function defaultTryOn(item: SandboxItem): SandboxTryOn {
    return {
        ref: item.ref,
        amount: item.amount ?? 0,
        date: item.date ?? '',
        stretchMonths: item.stretchMonthsDefault ?? 0,
        creditRate: item.creditRate ?? null,
        creditTermMonths: item.creditTermMonths ?? null,
    };
}
