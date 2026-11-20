import type { FinancialEvent } from '../types/api';
import { fmtDayShort, fmtRub } from './format';

/**
 * Запись к плану — «своё» и «Оплачено» брони раньше сверки (срез 3 п. 3, решения 32 и 52; доски S3-F6, S3-F9,
 * S3-F9b, S3-F6b). Сумма плана подставлена остатком; записано меньше остатка брони — выбор: «Доплачу позже» по
 * умолчанию держит остаток бронью (правило 11: ошибка занижает «Свободно», а не завышает), «Больше не нужно» закрывает
 * бронь на заплаченном (`closePlan`).
 */

/** Сколько по плану ещё не записано. */
export const planRest = (p: FinancialEvent) => Math.max(0, (p.plannedAmount ?? 0) - (p.linkedFactsAmount ?? 0));

/** Что делать с остатком брони: «Доплачу позже» или «Больше не нужно». */
export type RestChoice = 'later' | 'close';

/** Вопрос об остатке — только у брони и только когда записано меньше остатка: ожидание и так не держится после даты. */
export function restQuestion(p: FinancialEvent, amount: number | null): { rest: number; options: [string, string] } | null {
    const rest = planRest(p);
    if (p.priority !== 'HIGH' || amount == null || amount <= 0 || amount >= rest) return null;
    return { rest: rest - amount, options: p.type === 'INCOME' ? ['Придут позже', 'Больше не жду'] : ['Доплачу позже', 'Больше не нужно'] };
}

/** Признак в запросе — только если вопрос стоит и выбрано «Больше не нужно». */
export const closePlanOf = (p: FinancialEvent, amount: number | null, choice: RestChoice): true | undefined =>
    restQuestion(p, amount) && choice === 'close' ? true : undefined;

/**
 * Строка над кнопкой у брони расхода (S3-F9, S3-F9b): что будет с остатком. У дохода и не брони — нет. Бронь с датой
 * не позже сверки «Свободно» не держит (сверка уже её съела), и обещать «вернутся» нечего — строки нет (ревью Codex #235).
 */
export function ownEffect(p: FinancialEvent, amount: number | null, choice: RestChoice, checkpointDate?: string | null,
                          paidOn?: string, horizonEnd?: string): string | null {
    if (p.priority !== 'HIGH' || p.type !== 'EXPENSE' || amount == null || amount <= 0) return null;
    if (checkpointDate != null && p.date != null && p.date <= checkpointDate) return null;
    // Оплата раньше сверки уже в остатке банка — освободится больше, чем остаток после неё (ревью Codex #235): строки нет.
    if (checkpointDate != null && paidOn && paidOn < checkpointDate) return null;
    // Бронь за концом расчёта «Свободно» не держит — «вернутся» не про неё (ревью Codex #235).
    if (horizonEnd && p.date != null && p.date > horizonEnd) return null;
    const rest = planRest(p);
    const q = restQuestion(p, amount);
    if (q) {
        return choice === 'close'
            ? `Бронь закроется на ${fmtRub(amount + (p.linkedFactsAmount ?? 0))} — остальные ${fmtRub(q.rest)} вернутся в «Свободно».`
            : `Остальные ${fmtRub(q.rest)} держатся бронью, пока не доплатишь или не обновишь остаток.`;
    }
    return amount === rest ? 'Бронь оплачена целиком.' : `Бронь оплачена; сверх неё — «Свободно» меньше на ${fmtRub(amount - rest)}.`;
}

/**
 * Бронь с датой раньше последней сверки (S3-F6b): деньги могли уйти и до сверки, и после — дату не угадать
 * (lib/quickClose, askDate). Вместо поля даты — «До — дата плана», «После — сегодня» и «Другая дата».
 */
export const askDateChoices = (p: FinancialEvent, today: string) => [
    { value: p.date!, label: `До — ${fmtDayShort(p.date!)}` },
    { value: today, label: 'После — сегодня' },
];

/** Бронь старше сверки движок не держит: «до» — деньги уже в остатке; «после» — остаток станет меньше (больше). */
export const askDateNote = (p: FinancialEvent, amount: number) =>
    `До — значит, они уже в остатке, и «Свободно» не изменится. После — «Свободно» станет ${p.type === 'INCOME' ? 'больше' : 'меньше'} на ${fmtRub(amount)}.`;
