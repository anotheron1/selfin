import type { Account, PocketResponse, TargetFund } from '../types/api';
import { ownAccountChoices, type AccountChoice } from './fundAccount';
import { fmtDayShort, fmtRub } from './format';

/**
 * НЗ — копилка (срез 4, пп. 7, 19; доски S4-G1, S4-G2). На основной карте — «сколько держать»: «Свободно» считается так,
 * чтобы на карте оставалось не меньше; на своём счёте — остаток счёта, с карт ничего не вычитается. Сервер:
 * `PocketInputAssembler` (буфер), `TargetFundService` (отказы).
 */

export const reserveOf = (funds: TargetFund[]): TargetFund | undefined => funds.find(f => f.reserve);

export const NZ_TITLE = 'НЗ — запас';
export const NZ_WHERE_NOTE = 'На своём счёте — вклад или накопительный: НЗ — его остаток, с карт ничего не вычитается. '
    + 'Счёта нет — заведёшь здесь же.';
export const NZ_ON_ACCOUNT_NOTE = 'На своём счёте — в «Свободно» не входит, с карт ничего не вычитается.';
export const NZ_OWN_ACCOUNT_EASIER = 'Свой счёт для запаса проще: в банке на карте будет столько же, сколько в selfin.';

/** Записка на карточке НЗ на основной (S4-G2). */
export const nzOnCardNote = (keep: number): string =>
    keep > 0
        ? `«Свободно» считается так, чтобы на карте оставалось не меньше ${fmtRub(keep)}. ${NZ_OWN_ACCOUNT_EASIER}`
        : NZ_OWN_ACCOUNT_EASIER;

/**
 * Счета для «На своём счёте» — только накопительные: НЗ на счёте с карт не вычитается, а остаток основной и карты
 * «на всё» и так в «Свободно». Привязка к ним — 400 (`TargetFundService.validateAccountLink`).
 */
export const reserveAccounts = (accounts: Account[], funds: TargetFund[], nz: TargetFund): AccountChoice[] =>
    ownAccountChoices(accounts.filter(a => a.kind === 'DEPOSIT'), funds, nz.id);

/**
 * Строка над «Сохранить» (S4-G2): «Свободно» сейчас и что в нём уже вычтено. Срок — как в шапке: на «Сегодня» может быть
 * выбран другой. В минусе строки нет: там «Не хватает», а не «Свободно».
 */
export function reserveFreeLine(p: Pick<PocketResponse, 'pocket' | 'buffer' | 'horizon'> | null): string | null {
    if (!p || Number(p.pocket) < 0 || Number(p.buffer) <= 0) return null;
    return `«Свободно до ${fmtDayShort(p.horizon.endDate)}» — ${fmtRub(Number(p.pocket))}: уже за вычетом ${fmtRub(Number(p.buffer))} НЗ.`;
}

/**
 * Тело `PUT /funds/{id}` листа НЗ: «Сколько держать» и «Где лежит», прочие поля — как были (ручка пишет их все). На своём
 * счёте «сколько держать» не правится и остаётся прежним — вернётся на карту, когда НЗ туда перенесут.
 */
export function reserveDto(nz: TargetFund, f: { accountId: string | null; keep: number }) {
    return {
        name: nz.name, targetAmount: f.accountId ? nz.targetAmount ?? 0 : f.keep, targetDate: nz.targetDate ?? undefined,
        purchaseType: nz.purchaseType, creditRate: nz.creditRate ?? undefined, creditTermMonths: nz.creditTermMonths ?? undefined,
        accountId: f.accountId,
    };
}
