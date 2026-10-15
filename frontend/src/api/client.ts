// Базовый HTTP-клиент для API Spring Boot.
//
// Вход — через наш сервер (ANO-195, вариант Б): у браузера cookie сеанса, токенов во фронте нет.
// Адрес API — только относительный: и в разработке (прокси Vite), и в docker (nginx) запрос идёт
// тем же адресом, что и экран, — с cookie сеанса и CSRF. Спека — 2026-10-13-server-knows-owner-design.md.
const BASE_URL = '/api/v1';
/** Свой экран входа (ANO-152): на него уводит 401. */
const SIGN_IN_SCREEN = '/login';
/**
 * Вход начинает сервер: он уводит в Keycloak и возвращает на экран с сеансом. `provider=telegram` сервер
 * превращает в `kc_idp_hint` — Keycloak сразу уводит к Telegram, без своей страницы (ANO-152).
 */
export const SIGN_IN_WITH_TELEGRAM = '/oauth2/authorization/keycloak?provider=telegram';

/**
 * CSRF-токен для изменяющего запроса: сервер кладёт его в cookie `XSRF-TOKEN`, фронт возвращает
 * заголовком — иначе сеанс отвергнет запись (403). Чужая страница cookie прочесть не может.
 */
function csrfHeader(): Record<string, string> {
    const token = globalThis.document?.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]*)/)?.[1];
    return token ? { 'X-XSRF-TOKEN': decodeURIComponent(token) } : {};
}

/**
 * Ошибка ответа API (ANO-157). До неё клиент бросал голый `Error` со строкой, и ни один
 * экран не мог отличить 409 от 500 — а два разных 409 друг от друга тем более.
 *
 * Текст сообщения сохраняет прежний формат: экраны, показывающие `err.message`, не меняются.
 *
 * @param status  HTTP-статус ответа
 * @param details машиночитаемые коды из `ErrorResponse.details`; пустой массив, если их нет
 */
export class ApiError extends Error {
    constructor(
        readonly status: number,
        readonly details: string[],
        message: string,
    ) {
        super(message);
        this.name = 'ApiError';
    }
}

/**
 * Базовый метод HTTP-запроса. Автоматически добавляет `Content-Type: application/json`;
 * на успехе без тела — 204 или 200 без тела — возвращает `undefined` (ANO-208).
 *
 * @param path    путь относительно BASE_URL, например `/events?startDate=...`
 * @param options стандартный `RequestInit` плюс необязательный `extraHeaders` для
 *                дополнительных заголовков (например, `Idempotency-Key`)
 * @returns десериализованный JSON-ответ
 * @throws ApiError при HTTP-статусе 4xx/5xx
 */
async function request<T>(path: string, options?: RequestInit & { extraHeaders?: Record<string, string> }): Promise<T> {
    const { extraHeaders, ...rest } = options ?? {};
    const res = await fetch(`${BASE_URL}${path}`, {
        ...rest,
        headers: {
            'Content-Type': 'application/json',
            ...(rest.method && rest.method !== 'GET' ? csrfHeader() : {}),
            ...extraHeaders,
        },
    });
    if (res.status === 401) {
        // Сеанса нет — на экран входа. Ответ не приходит никогда: страница уходит, и экран не успевает
        // показать «Не записалось» на запросе, который просто не дошёл до входа.
        window.location.assign(SIGN_IN_SCREEN);
        return new Promise<T>(() => {});
    }
    if (!res.ok) {
        // Причина с бэка (ErrorResponse.message) — иначе на фронте виден голый код
        // и любая 400 выглядит как «ничего не произошло» (ANO-30).
        let detail = '';
        let details: string[] = [];
        try {
            const body = await res.json();
            detail = body?.message ?? body?.error ?? '';
            // ANO-157: коды из ErrorResponse.details — по ним экран отличает
            // подтверждаемый отказ от безусловного, не разбирая текст сообщения.
            if (Array.isArray(body?.details)) details = body.details;
        } catch { /* тело не JSON — обойдёмся кодом */ }
        throw new ApiError(res.status, details,
            `API error: ${res.status} ${path}${detail ? ` — ${detail}` : ''}`);
    }
    // ANO-208: у успеха без тела нет и данных — у 204 и у 200 без тела одинаково. Ручки статуса
    // хотелки отвечают 200 без тела, и res.json() на нём падал: запись проходила, а экран писал
    // «Не записалось».
    const text = await res.text();
    return (text === '' ? undefined : JSON.parse(text)) as T;
}

/**
 * HTTP GET запрос.
 *
 * @param path путь к ресурсу (с query-параметрами если нужно)
 */
export function get<T>(path: string) {
    return request<T>(path);
}

/**
 * HTTP POST запрос с JSON-телом.
 *
 * @param path    путь к ресурсу
 * @param body    тело запроса, будет сериализовано в JSON
 * @param headers дополнительные заголовки (например, `{ 'Idempotency-Key': uuid }`)
 */
export function post<T>(path: string, body: unknown, headers?: Record<string, string>) {
    return request<T>(path, {
        method: 'POST',
        body: JSON.stringify(body),
        extraHeaders: headers,
    });
}

/**
 * HTTP PUT запрос с JSON-телом (полное обновление ресурса).
 *
 * @param path путь к ресурсу
 * @param body новые данные ресурса целиком
 */
export function put<T>(path: string, body: unknown) {
    return request<T>(path, { method: 'PUT', body: JSON.stringify(body) });
}

/**
 * HTTP DELETE запрос (soft delete на уровне API).
 *
 * @param path путь к ресурсу, например `/events/uuid`
 */
export function del(path: string) {
    return request<void>(path, { method: 'DELETE' });
}

/**
 * HTTP PATCH запрос (частичное обновление ресурса).
 * Используется для ввода фактической суммы (`PATCH /events/{id}/fact`)
 * и переключения флагов (`PATCH /categories/{id}/mandatory`).
 *
 * @param path путь к ресурсу
 * @param body необязательное тело; если не передано — PATCH без тела
 */
export function patch<T>(path: string, body?: unknown) {
    return request<T>(path, {
        method: 'PATCH',
        body: body !== undefined ? JSON.stringify(body) : undefined,
    });
}

/**
 * Выход — и у нас, и в Keycloak (ANO-152), иначе «Войти» впустит снова без подтверждения в Telegram.
 * Форма не годится: CSRF сервера (`spa()`) сверяет параметр формы как замаскированный токен, а у экрана только
 * сырой из cookie. Поэтому запрос с заголовком; сервер гасит наш сеанс и отвечает адресом выхода Keycloak —
 * экран уходит по нему, браузер гасит сеанс Keycloak, и Keycloak возвращает на экран входа.
 *
 * @throws ApiError если сервер выход не принял — экран остаётся, чтобы человек не думал, что вышел
 */
export async function signOut(): Promise<void> {
    const res = await fetch('/logout', { method: 'POST', headers: csrfHeader() });
    if (!res.ok) throw new ApiError(res.status, [], `API error: ${res.status} /logout`);
    const { redirect } = await res.json() as { redirect: string };
    window.location.assign(redirect);
}
