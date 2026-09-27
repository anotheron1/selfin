import type { TargetFund } from '../types/api';

/**
 * Быстрый ввод «В копилку» (ANO-169, решение владельца 27.09).
 *
 * Раньше форма для перевода всегда создавала только план, а сумма из поля «если уже перевёл»
 * молча выбрасывалась: в журнале — «Горнолыжка 0 ₽», в копилке — ноль. Теперь запись уже
 * сделанного перевода — тот же перевод, что кнопка «Пополнить», а план — только когда человек
 * назвал плановую сумму.
 */

/** Что делать с заполненной формой. */
export type QuickAddAction =
    /** «уже перевёл» без плана — перевод ручкой «Пополнить», одна запись, без плана */
    | 'transfer'
    /** расход или доход «уже произошло» без плана — отдельный факт */
    | 'standaloneFact'
    /** план; с фактом — факт к нему (для перевода его переводит сервер) */
    | 'plan';

export function quickAddAction(isFundTransfer: boolean, hasPlanAmount: boolean, hasFactAmount: boolean): QuickAddAction {
    if (hasFactAmount && !hasPlanAmount) return isFundTransfer ? 'transfer' : 'standaloneFact';
    return 'plan';
}

/**
 * Можно ли сохранять. У перевода нужна сумма: без неё получался план без суммы, который ни
 * во что не превращается. Расход и доход — как раньше, по категории.
 */
export function canSubmitQuickAdd(p: {
    isFundTransfer: boolean;
    targetFundId?: string;
    categoryId?: string;
    hasPlanAmount: boolean;
    hasFactAmount: boolean;
}): boolean {
    if (p.isFundTransfer) return !!p.targetFundId && (p.hasPlanAmount || p.hasFactAmount);
    return !!p.categoryId;
}

/**
 * Копилки, в которые перевод возможен, и сколько не показано, потому что они лежат на счёте.
 *
 * Достигнутые копилки прячутся, как и прежде. Копилки на счёте перевода не принимают —
 * деньги в них двигаются на самом счёте (§3.3); раньше они стояли в списке, и план на них не
 * исполнялся никаким путём.
 */
export function transferFundChoice(funds: TargetFund[]): { choices: TargetFund[]; onAccount: number } {
    const open = funds.filter(f => f.status !== 'REACHED');
    return {
        choices: open.filter(f => !f.accountId),
        onAccount: open.filter(f => !!f.accountId).length,
    };
}

/** Строка под списком копилок — почему копилок на счёте в нём нет. */
export const ON_ACCOUNT_NOTE = 'Копилок на счёте здесь нет: деньги в них двигаются на самом счёте.';
