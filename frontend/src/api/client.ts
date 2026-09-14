// Базовый HTTP-клиент для API Spring Boot.
// VITE_API_URL задаётся в .env.development для локальной разработки.
// В Docker-деплое не задаётся — используется relative URL, Nginx проксирует /api/ → backend.
const BASE_URL = import.meta.env.VITE_API_URL ?? '/api/v1';

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
 * Базовый метод HTTP-запроса. Автоматически добавляет `Content-Type: application/json`
 * и обрабатывает 204 No Content (возвращает `undefined`).
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
            ...extraHeaders,
        },
    });
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
    if (res.status === 204) return undefined as T;
    return res.json();
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
 * Генерирует UUID v4 на клиенте для использования в качестве `Idempotency-Key`.
 * Использует встроенный `crypto.randomUUID()` — доступен во всех современных браузерах.
 *
 * @returns строка вида `"xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx"`
 */
export function generateUUID(): string {
    return crypto.randomUUID();
}
