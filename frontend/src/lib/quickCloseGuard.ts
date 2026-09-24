/**
 * Когда кружок закрытия брони снова можно нажать (ANO-176, ревью #56).
 *
 * Запись факта не идемпотентна: второй запрос — второй факт, и бронь посчитается дважды.
 * Поэтому кружок занят, пока закрытие не разрешилось:
 *
 *   * запрос упал — факта нет, кружок снова доступен;
 *   * факт записан — кружок занят, пока журнал не перечитан ПОСЛЕ записи. До этого строка
 *     показывает прежний остаток, и кружок предложил бы записать его ещё раз. Чтение, начатое
 *     до записи, бронь не освобождает: в нём остаток ещё старый.
 *
 * Занятость — у каждой брони своя: закрытие брони Б не освобождает бронь А.
 */
export class QuickCloseGuard {
    private readonly sending = new Set<string>();
    private readonly written = new Set<string>();
    private readonly unread = new Set<string>();

    /** false — эта бронь уже закрывается, касание ничего не делает. */
    begin(id: string): boolean {
        if (this.held(id)) return false;
        this.sending.add(id);
        return true;
    }

    /** Запрос не прошёл: факта нет. */
    failed(id: string): void {
        this.sending.delete(id);
    }

    /** Факт записан: держать, пока журнал не перечитан. */
    wrote(id: string): void {
        this.sending.delete(id);
        this.written.add(id);
    }

    /** Начало чтения журнала: какие брони освободит его успех — записанные до этого момента. */
    readStarted(): readonly string[] {
        return [...this.written];
    }

    readSucceeded(ids: readonly string[]): void {
        for (const id of ids) {
            this.written.delete(id);
            this.unread.delete(id);
        }
    }

    readFailed(ids: readonly string[]): void {
        for (const id of ids) if (this.written.has(id)) this.unread.add(id);
    }

    held(id: string): boolean {
        return this.sending.has(id) || this.written.has(id);
    }

    /** Факт записан, а журнал перечитать не удалось — строке есть что сказать. */
    stale(id: string): boolean {
        return this.unread.has(id);
    }
}
