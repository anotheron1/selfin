import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError, del, get, patch, post, put } from './client';
import { setEventWishlistStatus, setFundWishlistStatus } from './index';
import { attempt } from '../lib/writeFailure';

/** Сеть отвечает одним ответом — настоящим `Response`, как в браузере. */
function answer(res: Response) {
    vi.stubGlobal('fetch', vi.fn(async () => res));
}

afterEach(() => { vi.unstubAllGlobals(); });

/**
 * ANO-208. Ручки статуса хотелки отвечают 200 без тела, а клиент звал `res.json()` на любом успехе,
 * кроме 204, — и падал. Запись проходила, а экран писал «Не записалось» и «Failed to execute 'json'».
 * Замер 28.09 — спека 2026-09-28-empty-success-body-design.md.
 */
describe('успешный ответ без тела (ANO-208)', () => {
    it('смена статуса хотелки: 200 без тела — записалось', async () => {
        answer(new Response(null, { status: 200 }));
        await expect(setEventWishlistStatus('e1', 'DISMISSED')).resolves.toBeUndefined();
    });

    it('смена статуса копилки: 200 без тела — записалось', async () => {
        answer(new Response(null, { status: 200 }));
        await expect(setFundWishlistStatus('f1', 'OPEN')).resolves.toBeUndefined();
    });

    it('«Что с капиталом» не пишет «Не записалось» на успехе', async () => {
        answer(new Response(null, { status: 200 }));
        expect(await attempt(() => setFundWishlistStatus('f1', 'FIXED'))).toBeNull();
    });

    it('204 — тоже «ничего»', async () => {
        answer(new Response(null, { status: 204 }));
        await expect(patch('/x', {})).resolves.toBeUndefined();
    });

    it('успех с телом — разобранный JSON', async () => {
        answer(new Response(JSON.stringify({ id: 'a1' }), { status: 200 }));
        await expect(patch('/x', {})).resolves.toEqual({ id: 'a1' });
    });

    it('отказ — ApiError со статусом и кодами', async () => {
        answer(new Response(JSON.stringify({ message: 'nope', details: ['CODE'] }), { status: 409 }));
        const err = await patch('/x', {}).catch((e: unknown) => e);
        expect(err).toBeInstanceOf(ApiError);
        expect((err as ApiError).status).toBe(409);
        expect((err as ApiError).details).toEqual(['CODE']);
    });
});

/**
 * ANO-195. Вход — через наш сервер: у браузера cookie сеанса, токенов во фронте нет.
 * Сеанса нет — сервер отвечает на API 401, и экран уходит на вход. Изменяющий запрос несёт CSRF-токен:
 * сервер кладёт его в cookie XSRF-TOKEN, фронт возвращает заголовком X-XSRF-TOKEN — иначе запись 403.
 * Спека — 2026-10-13-server-knows-owner-design.md.
 */
describe('вход через наш сервер (ANO-195)', () => {
    it('401 — уход на вход через сервер', async () => {
        const assign = vi.fn();
        vi.stubGlobal('window', { location: { assign } });
        answer(new Response(null, { status: 401 }));
        void get('/pocket');
        await vi.waitFor(() => expect(assign).toHaveBeenCalledWith('/oauth2/authorization/keycloak'));
    });

    it('изменяющие запросы несут CSRF-токен из cookie, GET — нет', async () => {
        vi.stubGlobal('document', { cookie: 'other=1; XSRF-TOKEN=abc%3D; tail=2' });
        const sent: Record<string, string | undefined> = {};
        vi.stubGlobal('fetch', vi.fn(async (_url: string, init: RequestInit) => {
            sent[init.method ?? 'GET'] = (init.headers as Record<string, string>)['X-XSRF-TOKEN'];
            return new Response(null, { status: 204 });
        }));
        await get('/x');
        await post('/x', {});
        await put('/x', {});
        await patch('/x', {});
        await del('/x');
        expect(sent).toEqual({ GET: undefined, POST: 'abc=', PUT: 'abc=', PATCH: 'abc=', DELETE: 'abc=' });
    });

    it('адрес API — относительный: запрос идёт через прокси экрана, с cookie сеанса', async () => {
        const fetch = vi.fn(async () => new Response(null, { status: 204 }));
        vi.stubGlobal('fetch', fetch);
        await get('/pocket');
        expect(fetch).toHaveBeenCalledWith('/api/v1/pocket', expect.anything());
    });
});
