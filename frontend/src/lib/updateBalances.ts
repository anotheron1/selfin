import type { BalanceBatch, BalanceBatchResult, BalanceCheckpoint, BalanceSheet, BalanceSheetCard } from '../types/api';
import { WORDS, cardAmount, diffLine } from './moneyCard';
import { fmtAmount, fmtDayShort, fmtDayYear, fmtNum } from './format';

/**
 * Лист «Обновить остатки» (срез 4, п. 16; доски S4-U1, S4-U4): строки, запрос и итог словами. Вид —
 * components/money/UpdateBalancesSheet.tsx. «Свободно станет…» — по предпросмотру сервера: правило на клиенте не повторяется.
 */

/**
 * «По записям» — у карты, число которой двигают записи: основная — тратами, накопительная и вторая «на всё» с копилкой —
 * переводами. Кредитку и прочую вторую «на всё» записи не двигают: их число — прошлое обновление (как лист карты, PR 4).
 */
const followsRecords = (c: BalanceSheetCard) => c.isDefault || c.kind === 'DEPOSIT' || (c.transfersSinceUpdate ?? 0) !== 0;

/** Имя строки: у кредитки в поле — доступное. */
export const rowName = (c: BalanceSheetCard): string => (c.kind === 'CREDIT' ? `${c.name}, доступно` : c.name);

/** Справа от имени: «по записям 48 800», «обновлён 12 нояб.» или «первое обновление». */
export function rowCaption(c: BalanceSheetCard, today: string): string {
    if (c.byRecords == null || c.updatedOn == null) return 'первое обновление';
    return followsRecords(c) ? `по записям ${fmtNum(c.byRecords)}` : `обновлён ${fmtDayYear(c.updatedOn, today)}`;
}

/** Под полем: разница словами — те же слова, что в листе карты (`diffLine`); нечитаемое — сразу. null — молчать. */
export function rowNote(c: BalanceSheetCard, raw: string): string | null {
    const amount = cardAmount(raw);
    if (amount != null && Number.isNaN(amount)) return WORDS.unreadable(rowName(c));
    if (amount == null || c.byRecords == null) return null;
    // Обе суммы — уже до копеек (поле округляет, сервер хранит копейки): равные дают ровно ноль, остаток округлит показ.
    const drift = amount - c.byRecords;
    const line = followsRecords(c)
        ? diffLine({ computedBalance: c.byRecords, drift } as BalanceCheckpoint, c.kind === 'DEPOSIT')
        : diffLine({ computedBalance: null, drift: null, amount } as BalanceCheckpoint, false, { amount: c.byRecords } as BalanceCheckpoint);
    return `${line[0].toUpperCase()}${line.slice(1)}.`;
}

/** Пакет: только заполненные поля, в порядке листа; есть нечитаемое — null: без него карта молча не обновилась бы. */
export function batchOf(cards: BalanceSheetCard[], raws: Record<string, string>): BalanceBatch | null {
    const entries: BalanceBatch['entries'] = [];
    for (const c of cards) {
        const amount = cardAmount(raws[c.accountId] ?? '');
        if (amount == null) continue;
        if (Number.isNaN(amount)) return null;
        entries.push({ accountId: c.accountId, amount });
    }
    return { entries };
}

/** «В кредит записано» — одной суммой под последней кредиткой (п. 2): у записи нет карты. */
export const lastCreditId = (cards: BalanceSheetCard[]): string | null =>
    cards.filter(c => c.kind === 'CREDIT').pop()?.accountId ?? null;

export const onCreditLine = (o: NonNullable<BalanceSheet['onCredit']>, today: string): string =>
    `с ${fmtDayYear(o.since, today)} в кредит записано ${fmtAmount(o.amount)}`;

/**
 * «Свободно» предпросмотра — до ближайшего дохода, как в шапке; на «Сегодня» может быть выбран другой период, поэтому срок
 * назван. `until` — конец срока, ISO; не пришёл — без срока.
 */
const freeName = (until?: string | null) => (until ? `«Свободно до ${fmtDayShort(until)}»` : '«Свободно»');

/** Внизу листа — по предпросмотру. */
export const freeLine = (r: BalanceBatchResult, until?: string | null): string => (r.freeAfter === r.freeBefore
    ? `${freeName(until)} не изменится.`
    : `${freeName(until)} станет ${fmtAmount(r.freeAfter)} вместо ${fmtAmount(r.freeBefore)}.`);

/**
 * Строка после «Сохранить» (S4-U4): «Свободно» вместо прежнего, разница основной, рост накопительных — проценты. Разница
 * основной — объяснением через двоеточие, только когда «Свободно» сдвинулось ровно на неё; иначе — отдельной фразой:
 * сдвиг дали и другие карты или отпущенные прошедшие брони (решение 65), и одна разница его не объясняет (ревью #244).
 */
export function savedText(r: BalanceBatchResult, cards: BalanceSheetCard[], until?: string | null): string {
    const byId = new Map(cards.map(c => [c.accountId, c]));
    const main = r.cards.find(x => byId.get(x.accountId)?.isDefault && x.difference);
    const d = main?.difference ?? 0;
    const bank = `в банке на ${fmtAmount(Math.abs(d))} ${d > 0 ? 'больше' : 'меньше'}, чем по записям`;
    const exact = main != null && Math.round((r.freeAfter - r.freeBefore - d) * 100) === 0;
    const free = r.freeAfter === r.freeBefore ? `${freeName(until)} не изменилось.`
        : `${freeName(until)} — ${fmtAmount(r.freeAfter)} вместо ${fmtNum(r.freeBefore)}${exact ? `: ${bank}` : ''}.`;
    const apart = main && !exact ? ` ${byId.get(main.accountId)!.name}: ${bank}.` : '';
    // П. 16: меньше, чем по записям, — не повод вспоминать, на что ушло.
    const forget = d < 0 ? ' Вспоминать траты не нужно.' : '';
    const grown = r.cards
        .filter(x => byId.get(x.accountId)?.kind === 'DEPOSIT' && (x.difference ?? 0) > 0)
        .map(x => ` «${byId.get(x.accountId)!.name}»: +${fmtAmount(x.difference!)} сверх переводов — обычно это проценты.`);
    return `Остатки обновлены. ${free}${apart}${forget}${grown.join('')}`;
}
