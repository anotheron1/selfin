import type { PocketResponse } from '../types/api';
import { fmtDayMonth, fmtRub } from './format';
import { buildGapMode } from './gapMode';

/**
 * Строка диалога «Пополнить фонд» — теми же словами, что карточка кармашка (ANO-88, вариант А):
 * денег хватает — «свободно X»; нехватка или задетый НЗ — заголовок карточки в этом режиме
 * (ANO-100, ANO-92). Иначе диалог писал бы «свободно −1 600 ₽» там, где карточка говорит
 * «Сегодня по плану не хватает 1 600 ₽», — а отрицательное «свободно» посторонний читал как долг.
 *
 * @return {@code null}, пока кармашка нет: выдуманный ноль хуже пустоты
 */
export function transferFreeLine(p: PocketResponse | null): string | null {
    if (!p) return null;
    const gap = buildGapMode(p);
    if (!gap) return `свободно ${fmtRub(p.pocket)}`;
    return gap.headline.charAt(0).toLowerCase() + gap.headline.slice(1);
}

/** Код из ErrorResponse.details. Зеркалит ConfirmationRequiredException.CODE на бэкенде. */
export const CONFIRM_REQUIRED = 'CONFIRM_REQUIRED';

/** Подсказка сервера после кода: {@code short:ДЕНЬ:СУММА} или {@code nz:ДЕНЬ:СУММА} (ANO-88). */
const HINT = /^(short|nz):(\d{4}-\d{2}-\d{2}):(\d+(?:\.\d+)?)$/;

/**
 * Вопрос перед переводом, после которого не хватит (ANO-88, решение владельца 26.09, вариант А).
 *
 * Повторяет слова карточки о том, что будет ПОСЛЕ перевода: «по плану не хватит N» или «по плану
 * придётся взять из НЗ N» — в день минимума. Что будет, говорит сервер: он уже посчитал кармашек
 * после перевода тем же движком (`TargetFundService.afterTransferHint`). Своей оценки здесь нет:
 * с резервом взносов в копилки она разошлась бы с правдой.
 *
 * «Сегодня» — нулевая точка траектории карточки, то есть сегодня сервера, а не браузера: так же
 * решает карточка (`gapMode`).
 *
 * @param err    отказ сервера с кодом {@link CONFIRM_REQUIRED}
 * @param pocket кармашек с карточки — только чтобы узнать «сегодня»
 */
export function confirmQuestion(err: unknown, pocket: { trajectory?: { date: string }[] } | null): string {
    const details = (err as { details?: unknown } | null | undefined)?.details;
    const hint = Array.isArray(details)
        ? details.map(d => (typeof d === 'string' ? HINT.exec(d) : null)).find(m => m !== null)
        : null;
    if (!hint) return 'Это больше, чем свободно. Отложить всё равно?';
    const [, kind, day, amount] = hint;
    const when = day === pocket?.trajectory?.[0]?.date ? 'уже сегодня' : fmtDayMonth(day);
    const what = kind === 'short'
        ? `не хватит ${fmtRub(Number(amount))}`
        : `придётся взять из НЗ ${fmtRub(Number(amount))}`;
    return `После перевода ${when} по плану ${what}. Отложить всё равно?`;
}

/**
 * Можно ли отменить этот отказ осознанным подтверждением (ANO-157).
 *
 * Перевод в копилку отвечает 409 в двух несовместимых смыслах: «снять больше накопленного»
 * — отказ безусловный, денег физически нет; «перевод сверх остатка» — ради подтверждения
 * флаг и заведён (ANO-87 §4.2). По статусу они неразличимы.
 *
 * По тексту сообщения НЕ матчим: формулировка стала бы негласным контрактом, и правка
 * текста молча сломала бы диалог.
 *
 * Проверка структурная, а не `instanceof ApiError`: модуль остаётся чистым и не тянет за
 * собой `api/client.ts` с его `import.meta.env`. Тесты проекта ходят под
 * `environment: 'node'`, и чистый модуль проверяется без всякой оснастки.
 */
export function needsConfirmation(err: unknown): boolean {
    const e = err as { status?: unknown; details?: unknown } | null | undefined;
    return e?.status === 409
        && Array.isArray(e.details)
        && e.details.includes(CONFIRM_REQUIRED);
}
