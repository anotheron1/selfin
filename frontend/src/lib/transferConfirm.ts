/** Код из ErrorResponse.details. Зеркалит ConfirmationRequiredException.CODE на бэкенде. */
export const CONFIRM_REQUIRED = 'CONFIRM_REQUIRED';

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
