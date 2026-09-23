import type { FinancialEvent } from '../types/api';
import { canRecordFact } from './factDate';

/**
 * Кружок закрытия брони одним касанием (ANO-176) — правило, а не вид.
 *
 * Касание пишет факт на ОСТАТОК брони датой плана: план гасится фактами, а не замещается (ANO-155).
 * Граница — сверка остатка основного счёта. По AnchorWindow.countsTowardBalance факт раньше
 * даты сверки считается сидящим в банковском числе, позже — вычитается, а в сам день сверки
 * решает время записи, и записанный сейчас факт посчитается. Единственный случай, где дату
 * угадать нельзя, — дата плана раньше сверки: платёж мог пройти и до сверки, и после.
 * Тогда кружок не пишет, а открывает запись факта с вопросом о дате.
 *
 * Спека: docs/superpowers/specs/2026-09-24-journal-two-lists-design.md, раздел 2.
 */
export type QuickClose =
    | { kind: 'none' }
    | { kind: 'done' }
    | { kind: 'tap'; amount: number; date: string }
    | { kind: 'askDate'; amount: number; anchorDate: string };

export function quickClose(e: FinancialEvent, today: string, anchorDate: string | null): QuickClose {
    if (e.eventKind !== 'PLAN' || e.plannedAmount == null) return { kind: 'none' };
    const remaining = e.plannedAmount - (e.linkedFactsAmount ?? 0);
    // Старый путь записи факта прямо в план (ANO-25) тоже значит «закрыто».
    if (e.status === 'EXECUTED' || e.factAmount != null || (e.linkedFactsCount > 0 && remaining <= 0)) {
        return { kind: 'done' };
    }
    // Одно касание — только у брони: сумма известна заранее ровно у неё (канон, «Характер
    // плановой строки»). Факт на плане-переводе двигает деньги в копилку — это другой путь.
    if (e.priority !== 'HIGH' || e.type === 'FUND_TRANSFER' || e.date == null) return { kind: 'none' };
    if (!canRecordFact(e.date, today) || remaining <= 0) return { kind: 'none' };
    if (anchorDate != null && e.date < anchorDate) return { kind: 'askDate', amount: remaining, anchorDate };
    return { kind: 'tap', amount: remaining, date: e.date };
}

/** Последняя сверка ОСНОВНОГО счёта не позже сегодня: тем же счётом якорится кармашек (PocketInputAssembler). */
export function anchorDateOf(
    checkpoints: { accountId: string; date: string }[],
    accounts: { id: string; isDefault: boolean }[],
    today: string,
): string | null {
    const main = accounts.find((a) => a.isDefault);
    if (!main) return null;
    const dates = checkpoints.filter((c) => c.accountId === main.id && c.date <= today).map((c) => c.date);
    return dates.length ? dates.reduce((a, b) => (a > b ? a : b)) : null;
}
