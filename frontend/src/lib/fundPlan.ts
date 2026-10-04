import type { TargetFund } from '../types/api';

type WithStatus = Pick<TargetFund, 'wishlistStatus'>;

/**
 * Р8 (ANO-218): копилка-хотелка, которую не зафиксировали, — вне плана: её взносы ядро не держит.
 * На «Целях» она говорит об этом и стоит в конце, но с экрана не уходит — в ней могут быть деньги.
 * На «Хотелках» у отложенных свой раздел «Отложено» — там пометка не нужна.
 */
export function outOfPlanNote(fund: WithStatus): string | null {
    if (fund.wishlistStatus === 'OPEN') return 'обсуждается — вне плана';
    if (fund.wishlistStatus === 'DISMISSED') return 'отложена — вне плана';
    return null;
}

/** Порядок «Целей»: вне плана — в конце; внутри обеих групп — как прислал сервер (сортировка устойчивая). */
export function inPlanFirst<T extends WithStatus>(funds: T[]): T[] {
    const outside = (x: T) => (outOfPlanNote(x) ? 1 : 0);
    return [...funds].sort((a, b) => outside(a) - outside(b));
}
