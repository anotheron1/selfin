import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError, patch } from './client';
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
