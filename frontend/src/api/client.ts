// Базовый HTTP-клиент для API Spring Boot
const BASE_URL = 'http://localhost:8080/api/v1';

async function request<T>(path: string, options?: RequestInit & { extraHeaders?: Record<string, string> }): Promise<T> {
    const { extraHeaders, ...rest } = options ?? {};
    const res = await fetch(`${BASE_URL}${path}`, {
        ...rest,
        headers: {
            'Content-Type': 'application/json',
            ...extraHeaders,
        },
    });
    if (!res.ok) throw new Error(`API error: ${res.status} ${path}`);
    if (res.status === 204) return undefined as T;
    return res.json();
}

export function get<T>(path: string) {
    return request<T>(path);
}

export function post<T>(path: string, body: unknown, headers?: Record<string, string>) {
    return request<T>(path, {
        method: 'POST',
        body: JSON.stringify(body),
        extraHeaders: headers,
    });
}

export function put<T>(path: string, body: unknown) {
    return request<T>(path, { method: 'PUT', body: JSON.stringify(body) });
}

export function del(path: string) {
    return request<void>(path, { method: 'DELETE' });
}

export function patch<T>(path: string, body?: unknown) {
    return request<T>(path, {
        method: 'PATCH',
        body: body !== undefined ? JSON.stringify(body) : undefined,
    });
}

// Генерация UUID v4 на клиенте (для Idempotency-Key)
export function generateUUID(): string {
    return crypto.randomUUID();
}
