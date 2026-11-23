/**
 * Слова копилки на счёте — одни на карточке «Целей» и в выборе счёта (ANO-164, ANO-174).
 *
 * Привязка к счёту — ярлык, а не перемещение денег (ANO-158): «накоплено» становится остатком
 * счёта, перевод в копилку уводит деньги с основной на этот счёт (срез 4, п. 6). Уже переведённое в
 * конверт, пока копилка на счёте, в накопленном не считается; отвязка возвращает его. Деньги
 * копилки без счёта лежат на основной карте (Р10-А, ANO-212), поэтому на время привязки они снова
 * свободны, а капитал от привязки не меняется. До Р10-А замер 29.09 давал капитал −1 000.
 */

import type { Account, TargetFund } from '../types/api';
import { fmtDayShort } from './format';

export const ON_ACCOUNT = 'Накопленное берётся с остатка счёта.';

/** Копилка на своём счёте принимает перевод (срез 4, п. 6; ANO-262): «Отложить» уводит деньги с основной на её счёт. */
export const ON_ACCOUNT_MOVES = '«Отложить» уводит деньги с основной карты на этот счёт; остаток счёта обновляй, когда '
    + 'заглядываешь в банк.';

/** Где лежит копилка или НЗ (S4-G1, S4-G2): без счёта — на основной карте; на счёте — его имя и день обновления. */
export const ON_MAIN_WHERE = 'лежит на основной карте';
export const onAccountWhere = (a: Pick<Account, 'name' | 'balanceDate'> | undefined): string =>
    !a ? 'лежит на своём счёте'
        : `лежит на «${a.name}»${a.balanceDate ? ` · обновлён ${fmtDayShort(a.balanceDate)}` : ''}`;

/** «Где будут лежать деньги» у новой цели (срез 4, п. 20; S4-G3): свой счёт советуем, цена основной — одной строкой. */
export const WHERE_TITLE = 'Где будут лежать деньги';
export const WHERE_OWN_SUB = 'Накопительный или вклад. В банке на карте будет столько же, сколько в selfin.';
export const WHERE_MAIN_SUB = 'Тоже работает, но банк покажет на карте больше, чем «Свободно».';

export const ENVELOPE = 'Копилка держит свой баланс на основной карте: перевод откладывает деньги '
    + 'из свободных, а с карты они не уходят.';

/**
 * Строки подсказки у выбора счёта.
 *
 * @param p.linked        выбран счёт
 * @param p.wasLinked     копилка была на счёте до правки; у новой — нет
 * @param p.holdsOwnMoney в копилке лежат деньги — смысл имеет, только пока она конверт
 */
export function fundAccountHint(p: { linked: boolean; wasLinked: boolean; holdsOwnMoney: boolean }): string[] {
    if (p.linked) {
        return p.wasLinked || !p.holdsOwnMoney
            ? [ON_ACCOUNT, ON_ACCOUNT_MOVES]
            : [ON_ACCOUNT, ON_ACCOUNT_MOVES,
                'То, что уже переведено в копилку, пока она на счёте, снова свободно на основной карте — '
                + 'отложится обратно, если отвязать.'];
    }
    return p.wasLinked
        ? [ENVELOPE, 'Накопленное снова покажет только то, что в неё переводили.']
        : [ENVELOPE];
}

export interface AccountChoice {
    id: string;
    label: string;
    /** На счёте лежит другая копилка — выбрать нельзя. */
    taken: boolean;
}

/**
 * Счета в выборе — только те, что сервер примет (ANO-203, как диалог конверсии после ANO-141).
 *
 * Кредитка и счёт без слежения отсеиваются: на кредитке «накоплено» показало бы неизрасходованный
 * лимит, а остаток счёта без слежения не подтверждён ничем — сервер обе привязки отвергает 400.
 * Счёт, где уже лежит другая копилка, виден, но недоступен: сервер ответил бы 409, а приписка
 * говорит, чей он. Своя копилка в листе правки свой счёт не блокирует.
 *
 * @param funds  копилки — тот же список, по которому проверяет сервер (`TargetFundService`)
 * @param selfId своя копилка в листе правки; у новой — нет
 */
/**
 * «На своём счёте» (срез 4, п. 20; S4-G3) — счета, что примет сервер, без основной: на основной копилка лежит без счёта
 * («На основной карте»), а цель поверх основной значила бы «вся моя наличность и есть эта цель».
 */
export const ownAccountChoices = (
    accounts: Pick<Account, 'id' | 'name' | 'kind' | 'trackBalance' | 'isDefault'>[],
    funds: Pick<TargetFund, 'id' | 'name' | 'accountId'>[],
    selfId?: string,
): AccountChoice[] => accountChoices(accounts.filter(a => !a.isDefault), funds, selfId);

export function accountChoices(
    accounts: Pick<Account, 'id' | 'name' | 'kind' | 'trackBalance'>[],
    funds: Pick<TargetFund, 'id' | 'name' | 'accountId'>[],
    selfId?: string,
): AccountChoice[] {
    return accounts
        .filter(a => a.trackBalance && a.kind !== 'CREDIT')
        .map(a => {
            const holder = funds.find(f => f.accountId === a.id && f.id !== selfId);
            return holder
                ? { id: a.id, label: `${a.name} — здесь копилка «${holder.name}»`, taken: true }
                : { id: a.id, label: a.name, taken: false };
        });
}
