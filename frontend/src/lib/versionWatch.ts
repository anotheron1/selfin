/**
 * Открытая вкладка после выкладки (ANO-222). Заголовок `Cache-Control` у `index.html` помогает новой загрузке, но
 * открытая вкладка страницу больше не запрашивает, и её старый код говорит с новым сервером до перезагрузки.
 *
 * Вкладка запоминает коммит сервера при открытии и сверяет при следующих вызовах: сменился — сервер обновили, пока она
 * была открыта. Сравнение с коммитом сервера, а не со сборкой фронта: так не нужен аргумент сборки, и нет ложного
 * «обновитесь», когда пересобран только бэк, — перезагрузка такую строку не сняла бы.
 *
 * Спека: docs/superpowers/specs/2026-10-17-index-cache-open-tab-design.md
 */

/** Коммит, который отдаёт сервер; `unknown` — сборка без `GIT_COMMIT`. */
export type ServerCommit = () => Promise<string | null>;

/**
 * Проверка: первый вызов запоминает коммит, следующие отвечают, сменился ли он. Если при открытии сверить не удалось
 * (сервер перезапускался), первая удачная проверка предлагает обновиться: код вкладки мог остаться старым, а новый
 * коммит иначе молча стал бы базой (ревью Codex на #169). Лишняя перезагрузка после сбоя связи безвредна.
 */
export function versionWatch(serverCommit: ServerCommit): () => Promise<boolean> {
    let opened: string | undefined;
    let first = true;
    let unverified = false;
    // Проверки — по очереди вызова (ревью Codex на #169): иначе ранний ответ второй проверки стал бы базой раньше первой.
    let queue: Promise<unknown> = Promise.resolve();
    const check = async (): Promise<boolean> => {
        const opening = first;
        first = false;
        let commit: string | null;
        try {
            commit = await serverCommit();
        } catch {
            commit = null;
        }
        if (!commit) {
            if (opening) unverified = true;
            return false; // нет связи — не повод звать обновляться
        }
        if (commit === 'unknown') return false;
        if (opened === undefined) {
            opened = commit;
            return unverified;
        }
        return commit !== opened;
    };
    return () => {
        const result = queue.then(check);
        queue = result.catch(() => undefined);
        return result;
    };
}

/** Коммит сервера — `GET /api/v1/version`, открытая ручка (ANO-194). */
export const serverCommit: ServerCommit = async () => {
    const res = await fetch('/api/v1/version');
    if (!res.ok) return null;
    return ((await res.json()) as { commit?: string }).commit ?? null;
};
