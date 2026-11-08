import type { SandboxItem, SandboxRef, SandboxTryOn } from '../types/api';
import { fmtDayMonth } from './format';

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

// Дата цели при фиксации растянутой примерки (§8) считается на СЕРВЕРЕ —
// SandboxLayout.stretchTargetDate. Клиентская копия убрана вместе с ANO-34 §1:
// два независимых правила разъехались бы, а движок всё равно верит только своему.

/** 1-е число месяца после месяца даты. */
function firstOfNextMonth(iso: string): string {
    const [y, m] = iso.split('-').map(Number);
    return m === 12 ? `${y + 1}-01-01` : `${y}-${String(m + 1).padStart(2, '0')}-01`;
}

/**
 * Дата, на которую хотелку примеряют (ANO-236). Сервер ждёт строго будущую («tryOn.date обязательна и строго
 * в будущем»), а у хотелки срок мог пройти — тогда 1-е число следующего месяца, как карточка «Что с капиталом»
 * подставляет срок хотелке без срока (ANO-29). Правило 5: ответ на устаревших данных, а не отказ.
 *
 * @param today сегодня по календарю сервера — по нему он судит «в будущем»
 */
export function tryOnDate(date: string, today: string): string {
    return date > today ? date : firstOfNextMonth(today);
}

/** Пометка у строки, если дату подставили: о дате, а не о человеке (правило 12). */
export function movedTryOnNote(date: string, today: string): string | null {
    if (date > today) return null;
    return `${date === today ? 'Дата сегодня' : 'Дата прошла'} — примеряю на ${fmtDayMonth(tryOnDate(date, today))}`;
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
