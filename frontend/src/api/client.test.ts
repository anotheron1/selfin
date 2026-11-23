import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError, CHANGED, del, get, joinTabs, linkTabs, patch, post, postRead, put, signOut } from './client';
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
    it('401 — уход на наш экран входа, а не прямо в Keycloak (ANO-152)', async () => {
        const assign = vi.fn();
        vi.stubGlobal('window', { location: { assign } });
        answer(new Response(null, { status: 401 }));
        void get('/pocket');
        await vi.waitFor(() => expect(assign).toHaveBeenCalledWith('/login'));
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

/**
 * ANO-152. Выход — и у нас, и в Keycloak. Форма не годится: CSRF сервера (`spa()`) сверяет параметр формы как
 * замаскированный токен, а у экрана только сырой из cookie. Поэтому запрос с заголовком, а сервер отвечает адресом
 * выхода Keycloak — экран уходит по нему, и браузер гасит сеанс Keycloak.
 * Спека — 2026-10-15-sign-in-screen-design.md.
 */
describe('выход (ANO-152)', () => {
    it('POST /logout с CSRF-токеном в заголовке и переход по адресу выхода из ответа', async () => {
        vi.stubGlobal('document', { cookie: 'XSRF-TOKEN=tok%3D' });
        const assign = vi.fn();
        vi.stubGlobal('window', { location: { assign } });
        const fetch = vi.fn(async () => new Response(JSON.stringify({ redirect: 'http://kc/logout?id_token_hint=x' })));
        vi.stubGlobal('fetch', fetch);
        await signOut();
        expect(fetch).toHaveBeenCalledWith('/logout', { method: 'POST', headers: { 'X-XSRF-TOKEN': 'tok=' } });
        expect(assign).toHaveBeenCalledWith('http://kc/logout?id_token_hint=x');
    });

    it('выход не удался — ошибка, экран остаётся: иначе человек думал бы, что вышел', async () => {
        const assign = vi.fn();
        vi.stubGlobal('window', { location: { assign } });
        answer(new Response(null, { status: 403 }));
        await expect(signOut()).rejects.toBeInstanceOf(ApiError);
        expect(assign).not.toHaveBeenCalled();
    });
});

/**
 * Срез 1, ревью Codex на #225: шапка с «Свободно» видна поверх любого экрана, а экраны пишут своими запросами —
 * запись в журнале, перевод в копилку, НЗ в настройках. После любой удачной записи клиент сообщает об этом, и шапка
 * перечитывает число; чтение и отказ — молчат.
 */
describe('удачная запись сообщает шапке (CHANGED)', () => {
    const listen = () => {
        const target = new EventTarget();
        vi.stubGlobal('window', target);
        const heard = vi.fn();
        target.addEventListener(CHANGED, heard);
        return heard;
    };

    it.each([
        ['POST', () => post('/x', {})],
        ['PUT', () => put('/x', {})],
        ['PATCH', () => patch('/x', {})],
        ['DELETE', () => del('/x')],
    ])('%s — сообщает', async (_m, call) => {
        const heard = listen();
        answer(new Response(null, { status: 204 }));
        await call();
        expect(heard).toHaveBeenCalledTimes(1);
    });

    it('чтение — молчит', async () => {
        const heard = listen();
        answer(new Response('{}', { status: 200 }));
        await get('/x');
        expect(heard).not.toHaveBeenCalled();
    });

    it('отказ — молчит', async () => {
        const heard = listen();
        answer(new Response('{}', { status: 409 }));
        await post('/x', {}).catch(() => {});
        expect(heard).not.toHaveBeenCalled();
    });

    it('POST без записи (предпросмотр листа) — молчит, но идёт с CSRF: иначе экраны перечитывались бы на каждую цифру', async () => {
        const heard = listen();
        vi.stubGlobal('document', { cookie: 'XSRF-TOKEN=t1' });
        const fetch = vi.fn(async () => new Response('{}', { status: 200 }));
        vi.stubGlobal('fetch', fetch);
        await postRead('/x', {});
        expect(heard).not.toHaveBeenCalled();
        expect(fetch).toHaveBeenCalledWith('/api/v1/x', expect.objectContaining({
            method: 'POST', headers: expect.objectContaining({ 'X-XSRF-TOKEN': 't1' }),
        }));
    });
});

/** Вкладки обновляют друг друга (срез 3, п. 8, ANO-256): перемена уходит в BroadcastChannel и приходит из него. */
describe('вкладки — через канал', () => {
    const fakeChannel = () => ({ postMessage: vi.fn(), onmessage: null as ((e: MessageEvent) => void) | null });

    it('пришедшее из канала — то же событие в этой вкладке; чужое сообщение — нет', () => {
        const target = new EventTarget();
        const heard = vi.fn();
        target.addEventListener(CHANGED, heard);
        const channel = fakeChannel();
        linkTabs(target, channel);
        channel.onmessage!({ data: CHANGED } as MessageEvent);
        channel.onmessage!({ data: 'другое' } as MessageEvent);
        expect(heard).toHaveBeenCalledTimes(1);
        expect(channel.postMessage).not.toHaveBeenCalled();
    });

    it('удачная запись уходит в канал; чтение — нет', async () => {
        const channel = fakeChannel();
        vi.stubGlobal('window', new EventTarget());
        vi.stubGlobal('BroadcastChannel', function FakeChannel() { return channel; });
        joinTabs();
        answer(new Response(null, { status: 204 }));
        await post('/x', {});
        answer(new Response('{}', { status: 200 }));
        await get('/x');
        answer(new Response('{}', { status: 200 }));
        await postRead('/x', {});
        expect(channel.postMessage).toHaveBeenCalledTimes(1);
        expect(channel.postMessage).toHaveBeenCalledWith(CHANGED);
    });
});
