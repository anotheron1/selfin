import type { EventType } from '../types/api';

/**
 * Знак суммы факта — по направлению денег для свободных, а не по типу строки (ANO-184).
 *
 *   * доход — «+»;
 *   * расход и перевод в копилку — «−»: деньги ушли из свободных;
 *   * перевод с отрицательной суммой — возврат из копилки (ANO-86, ANO-87) — «+»: деньги вернулись.
 *
 * Раньше знак брался по типу: к собственному минусу возврата приклеивался второй — «--100 ₽», —
 * а «Последний факт» ставил минус и доходу.
 *
 * Спека: docs/superpowers/specs/2026-09-27-fact-sign-design.md
 */
export function signedFact(type: EventType, amount: number): { sign: '+' | '-'; amount: number } {
    const outflow = type !== 'INCOME';
    const toFree = outflow ? -amount : amount;
    // Ноль — по направлению (ревью #105): правка факта разрешает ноль, а -0 < 0 ложно.
    const sign = toFree < 0 || (toFree === 0 && outflow) ? '-' : '+';
    return { sign, amount: Math.abs(toFree) };
}

/** Сумма факта со знаком для экрана; без суммы — что скажет форматтер, без знака. */
export function factAmountText(type: EventType, amount: number | null, fmt: (n: number | null) => string): string {
    if (amount == null) return fmt(null);
    const s = signedFact(type, amount);
    return `${s.sign}${fmt(s.amount)}`;
}
