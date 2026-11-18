/**
 * Экран и время (срез 3, п. 8, ANO-256, ANO-126): вернулся на вкладку или в приложение после паузы — экран
 * перечитывается, так видна запись с телефона; ушёл из журнала и вернулся в той же вкладке — тот же месяц и прокрутка.
 */

/** Пауза, после которой экран перечитывается: сервер о переменах сам не сообщает. */
export const PAUSE_MS = 60_000;

/** Вкладку прятали минуту и дольше. hiddenAt — когда спрятали; null — не прятали. */
export const returnedAfterPause = (hiddenAt: number | null, now: number): boolean =>
    hiddenAt != null && now - hiddenAt >= PAUSE_MS;

export interface JournalPosition { year: number; month: number; scroll: number }

type Store = Pick<Storage, 'getItem' | 'setItem'> | null;

const KEY = 'journal-position';

/** sessionStorage, если он есть: в приватном окне и в превью он бросает. */
export function sessionStore(): Store {
    try { return globalThis.sessionStorage ?? null; } catch { return null; }
}

export function saveJournalPosition(store: Store, p: JournalPosition) {
    try { store?.setItem(KEY, JSON.stringify(p)); } catch { /* хранилище недоступно — позиция просто не запомнится */ }
}

export function readJournalPosition(store: Store): JournalPosition | null {
    try {
        const p = JSON.parse(store?.getItem(KEY) ?? 'null');
        const ok = p && Number.isInteger(p.year) && Number.isInteger(p.month) && p.month >= 0 && p.month <= 11
            && typeof p.scroll === 'number';
        return ok ? { year: p.year, month: p.month, scroll: p.scroll } : null;
    } catch {
        return null;
    }
}
