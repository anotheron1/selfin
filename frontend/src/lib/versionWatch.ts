/**
 * Открытая вкладка после выкладки (ANO-222). Заголовок `Cache-Control` у `index.html` помогает новой загрузке, но
 * открытая вкладка страницу больше не запрашивает, и её старый код говорит с новым сервером до перезагрузки.
 *
 * Экран знает коммит своей сборки (`VITE_GIT_COMMIT` — тот же `GIT_COMMIT`, что у бэка: оба образа собираются из одного
 * коммита) и сравнивает его с коммитом сервера. Сравнение без состояния: ни неудачная первая сверка, ни порядок ответов,
 * ни старый фронт, отданный во время выкладки, не делают старый экран «своим» (три круга ревью Codex на #169 — на
 * сравнении с коммитом сервера при открытии вкладки).
 *
 * Спека: docs/superpowers/specs/2026-10-17-index-cache-open-tab-design.md
 */

/** Коммит сборки экрана; `unknown` — сборка без `GIT_COMMIT` (разработка). */
export const BUILD_COMMIT: string = import.meta.env.VITE_GIT_COMMIT ?? 'unknown';

/** Экран и сервер из разных сборок. Без коммита с любой стороны или без ответа сервера — сравнивать нечего. */
export function stale(build: string | undefined, server: string | null): boolean {
    if (!build || !server || build === 'unknown' || server === 'unknown') return false;
    return build !== server;
}

/** Коммит сервера — `GET /api/v1/version`, открытая ручка (ANO-194); не ответил — `null`. */
export async function serverCommit(): Promise<string | null> {
    try {
        const res = await fetch('/api/v1/version');
        return res.ok ? (((await res.json()) as { commit?: string }).commit ?? null) : null;
    } catch {
        return null;
    }
}
