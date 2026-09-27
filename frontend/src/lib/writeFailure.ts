import { fundMovementMessage } from './fundMovement';

/**
 * Отказ записи словами экрана (ANO-141, ANO-170).
 *
 * Текст сервера на экран не идёт: он по-английски (болезнь ANO-203). Догадка про «заполненные
 * поля» — тоже: она отправляет искать пустое поле, которого нет (правило 5). Остаётся то, что
 * экран знает наверняка: запись не прошла.
 */

/** Запасная фраза записи — та же, что в «Записать факт» и листе правки. Повтор безопасен (ANO-192). */
export const WRITE_FAILED = 'Не записалось — попробуйте ещё раз';

/** Запасная фраза удаления — та же, что в листе правки. */
export const DELETE_FAILED = 'Не удалилось — попробуйте ещё раз';

/**
 * Удаление копилки, в которой лежат деньги: сервер ждёт ответа «куда они» (ANO-86), а экран его
 * пока не спрашивает — это ANO-198. Повтор не поможет, поэтому без «попробуйте ещё раз».
 */
export const FUND_HOLDS_MONEY = 'Не удалилось: в копилке лежат деньги';

/** Отказ записи: отказ копилки — её словами, остальное — запасной фразой. */
export function writeFailure(err: unknown): string {
    return fundMovementMessage(err) ?? WRITE_FAILED;
}

export type Deletable = 'EVENT' | 'FUND';

/**
 * Отказ удаления. У удаления копилки 409 один — в ней лежат деньги, а ответа «куда» в запросе
 * нет (`TargetFundService.delete`).
 */
export function deleteFailure(err: unknown, kind: Deletable): string {
    const status = (err as { status?: unknown } | null | undefined)?.status;
    if (kind === 'FUND' && status === 409) return FUND_HOLDS_MONEY;
    return fundMovementMessage(err) ?? DELETE_FAILED;
}

/**
 * Удаляет части вместе — хотелку и созданный из неё план или копилку. Параллельно, как и раньше.
 *
 * @returns `null`, если удалилось всё; иначе фраза об отказавшей части
 */
export async function deleteTogether(
    parts: { kind: Deletable; run: () => Promise<unknown> }[],
): Promise<string | null> {
    const results = await Promise.allSettled(parts.map(p => p.run()));
    const failed = results.findIndex(r => r.status === 'rejected');
    if (failed < 0) return null;
    return deleteFailure((results[failed] as PromiseRejectedResult).reason, parts[failed].kind);
}

/**
 * Запись с ответом для экрана.
 *
 * @returns `null`, если записалось; иначе фраза отказа
 */
export async function attempt(
    write: () => Promise<unknown>,
    failure: (err: unknown) => string = writeFailure,
): Promise<string | null> {
    try {
        await write();
        return null;
    } catch (err) {
        return failure(err);
    }
}
