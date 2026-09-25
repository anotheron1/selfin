import { fmtDayMonth } from './format';

/** Код из ErrorResponse.details. Зеркалит ConfirmationRequiredException.CODE на бэкенде. */
export const CONFIRM_REQUIRED = 'CONFIRM_REQUIRED';

/**
 * Вопрос перед переводом сверх свободного (ANO-88, решение владельца 26.09, вариант А).
 *
 * Сервер переспрашивает, когда кармашек после перевода ушёл бы в минус, — значит, не хватит
 * к дню минимума траектории. Этот день и называем: «больше, чем свободно» без даты не говорит,
 * когда станет туго.
 *
 * @param shortOn день минимума кармашка (ISO); без него дату не выдумываем
 */
export function shortfallQuestion(shortOn: string | undefined): string {
    return shortOn
        ? `Это больше, чем свободно в кармашке — к ${fmtDayMonth(shortOn)} не хватит. Отложить всё равно?`
        : 'Это больше, чем свободно в кармашке. Отложить всё равно?';
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
