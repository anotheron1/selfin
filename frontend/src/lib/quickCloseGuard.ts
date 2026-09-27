import { AttemptKeys } from './attemptKey';

/**
 * Когда кружок закрытия брони снова можно нажать (ANO-176, ревью #56).
 *
 * Повтор после сбоя безопасен: он уходит с тем же ключом попытки, и сервер вернёт уже
 * записанный факт (ANO-192, `attemptKey.ts`). А после успеха ключ забыт, и новое касание —
 * уже новый факт: бронь посчиталась бы дважды. Поэтому кружок занят, пока закрытие не разрешилось:
 *
 *   * запрос упал — факта, может быть, и нет; кружок снова доступен, повтор — с тем же ключом;
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
    /**
     * Попытки кружка (ревью #104). Живут, пока журнал не показал их исход: удачное чтение,
     * начатое после последнего сбоя, когда ничего не в пути, их кончает. Дольше жить им нельзя —
     * оставленная попытка отдала бы свой ключ такой же записи позже, и та молча пропала бы.
     */
    private scope = new AttemptKeys();
    private failures = 0;

    /** Память попыток для записи факта кружком. */
    get attempts(): AttemptKeys {
        return this.scope;
    }

    /** false — эта бронь уже закрывается, касание ничего не делает. */
    begin(id: string): boolean {
        if (this.held(id)) return false;
        this.sending.add(id);
        return true;
    }

    /** Запрос не прошёл: факта, может быть, и нет — попытка ждёт повтора или чтения журнала. */
    failed(id: string): void {
        this.sending.delete(id);
        this.failures++;
    }

    /** Факт записан: держать, пока журнал не перечитан. */
    wrote(id: string): void {
        this.sending.delete(id);
        this.written.add(id);
    }

    /** Начало чтения журнала: что освободит его успех — брони, записанные до этого момента. */
    readStarted(): ReadTicket {
        return { releases: [...this.written], failures: this.failures };
    }

    readSucceeded(read: ReadTicket): void {
        for (const id of read.releases) {
            this.written.delete(id);
            this.unread.delete(id);
        }
        // Журнал показал исход всех сбоев до начала чтения. Сбой после начала или запрос в пути —
        // их исхода в чтении нет, и попытки остаются.
        if (read.failures === this.failures && this.sending.size === 0) this.scope = new AttemptKeys();
    }

    readFailed(read: ReadTicket): void {
        for (const id of read.releases) if (this.written.has(id)) this.unread.add(id);
    }

    held(id: string): boolean {
        return this.sending.has(id) || this.written.has(id);
    }

    /** Факт записан, а журнал перечитать не удалось — строке есть что сказать. */
    stale(id: string): boolean {
        return this.unread.has(id);
    }
}

/** Отметка начала чтения журнала: что его успех освободит и сколько сбоев было до него. */
export interface ReadTicket {
    readonly releases: readonly string[];
    readonly failures: number;
}
