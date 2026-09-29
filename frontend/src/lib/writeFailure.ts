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
 * Удаление копилки, в которой лежат деньги: сервер ждёт ответа «куда они» (ANO-86). Повтор без
 * ответа не поможет, поэтому без «попробуйте ещё раз». Экран отвечает на этот отказ вопросом —
 * `asksWhereMoney` (ANO-198).
 */
export const FUND_HOLDS_MONEY = 'Не удалилось: в копилке лежат деньги';

/**
 * Отказ, на который экран отвечает вопросом «что с деньгами» (`FundMoneyQuestion`), а не строкой
 * (ANO-198): удаление копилки, в которой лежат деньги. Когда спрашивать, решает сервер — копии
 * правила «у копилки со счётом своих денег нет» на экране нет.
 */
export const asksWhereMoney = (failure: string | null): boolean => failure === FUND_HOLDS_MONEY;

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
 * Отказ «Отложить» с удалением созданного (ANO-210). Сервер удаляет созданное вместе со сменой
 * статуса и отвергает 409 одно на каждый вид: у плана — по нему есть факты, у копилки — в ней лежат
 * деньги. Отказ откатывает и статус, поэтому подсказка — отложить без галочки.
 *
 * Копилку с деньгами сменой статуса сервер не удаляет: её удаляют на своём экране, где спрашивают,
 * что с деньгами (`WishlistArtifactService`). Такой экран — «Цели» (ANO-198); строка говорит, где.
 */
export function dismissFailure(err: unknown, kind: Deletable): string {
    const status = (err as { status?: unknown } | null | undefined)?.status;
    if (status !== 409) return WRITE_FAILED;
    return kind === 'EVENT'
        ? 'Не отложилось: по созданному плану уже есть факт, и его не удалить. Без галочки — отложится, план останется.'
        : 'Не отложилось: в созданной копилке лежат деньги. Без галочки — отложится, а копилку можно '
            + 'удалить на «Целях»: там спросят, что с ними.';
}

/** 404 на удалении — части уже нет: цель достигнута, это не отказ. */
function alreadyGone(result: PromiseSettledResult<unknown>): boolean {
    return result.status === 'rejected'
        && (result.reason as { status?: unknown } | null | undefined)?.status === 404;
}

/**
 * Удаляет части вместе — хотелку и созданный из неё план или копилку. Параллельно, как и раньше.
 *
 * Уже удалённая часть — не отказ. Иначе повтор после частичного удаления (хотелка ушла,
 * копилка с деньгами — нет) назвал бы вместо денег пропавшую хотелку, а повтор после
 * потерянного ответа — отказом то, что удалилось.
 *
 * @returns `null`, если удалилось всё; иначе фраза об отказавшей части
 */
export async function deleteTogether(
    parts: { kind: Deletable; run: () => Promise<unknown> }[],
): Promise<string | null> {
    const results = await Promise.allSettled(parts.map(p => p.run()));
    const failed = results.findIndex(r => r.status === 'rejected' && !alreadyGone(r));
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
