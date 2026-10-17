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

/** Проверка: первый удачный вызов запоминает коммит, следующие отвечают, сменился ли он. */
export function versionWatch(serverCommit: ServerCommit): () => Promise<boolean> {
    let opened: string | undefined;
    return async () => {
        let commit: string | null;
        try {
            commit = await serverCommit();
        } catch {
            return false; // нет связи — не повод звать обновляться
        }
        if (!commit || commit === 'unknown') return false;
        if (opened === undefined) {
            opened = commit;
            return false;
        }
        return commit !== opened;
    };
}

/** Коммит сервера — `GET /api/v1/version`, открытая ручка (ANO-194). */
export const serverCommit: ServerCommit = async () => {
    const res = await fetch('/api/v1/version');
    if (!res.ok) return null;
    return ((await res.json()) as { commit?: string }).commit ?? null;
};
