import type { CapitalItem, CapitalItemKind, CapitalSummary } from '../types/api';

/**
 * Строки разделов «Капитала», которые считаются сами (Р7, ANO-217): деньги — в «Активах», долги по
 * картам — в «Обязательствах». Без них «Капитал на сегодня» не складывался из экрана: деньги и долги
 * по картам входили в число, а строкой не стояли нигде.
 */
export const MONEY_ROW = 'Деньги на счетах, вкладах и в копилках';
export const CARD_DEBT_ROW = 'Долги по картам';

export interface ComputedRow { name: string; value: number }

/** Строка раздела, которая считается сама; нулевая не показывается. */
export function computedRow(kind: CapitalItemKind, s: CapitalSummary): ComputedRow | null {
    const row = kind === 'ASSET'
        ? { name: MONEY_ROW, value: s.liquid }
        : { name: CARD_DEBT_ROW, value: s.cardDebts };
    return row.value !== 0 ? row : null;
}

/**
 * Экран «Капитала» пуст, только когда нет ни статей, ни денег, ни долгов по картам (ревью Codex на
 * #135). Раньше заглушку решали одни статьи — и человек со счетами без ручных статей не видел ни
 * капитала, ни строк, которые считаются сами.
 */
export function capitalIsEmpty(s: CapitalSummary): boolean {
    return s.items.length === 0 && !computedRow('ASSET', s) && !computedRow('LIABILITY', s);
}

/** Итог раздела: строки без архивных плюс строка, которая считается сама. */
export function sectionTotal(items: CapitalItem[], computed: ComputedRow | null): number {
    return items.filter(i => !i.isArchived).reduce((s, i) => s + i.currentValue, 0) + (computed?.value ?? 0);
}
