import { afterEach, describe, expect, it, vi } from 'vitest';
import { dayAfter, moveLater } from './moveLater';
import type { UpcomingItem } from '../types/api';

type Call = { method: string; url: string; body: unknown };

/** Сеть: записывает запросы, отвечает по порядку. */
function network(...answers: unknown[]): Call[] {
    const calls: Call[] = [];
    vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit = {}) => {
        calls.push({ method: init.method ?? 'GET', url, body: init.body ? JSON.parse(String(init.body)) : undefined });
        return new Response(JSON.stringify(answers.shift() ?? null), { status: 200 });
    }));
    return calls;
}
afterEach(() => { vi.unstubAllGlobals(); });

const item = (over: Partial<UpcomingItem> = {}): UpcomingItem => ({
    id: 'r1', date: '2026-12-05', categoryName: 'Дом', amount: 40_000, description: 'Ремонт',
    overdue: false, wishlist: false, priority: 'MEDIUM', type: 'EXPENSE', ...over,
});

describe('moveLater — «Сдвинуть»', () => {
    it('день после конца срока — и через конец месяца и года', () => {
        expect(dayAfter('2026-12-25')).toBe('2026-12-26');
        expect(dayAfter('2026-11-30')).toBe('2026-12-01');
        expect(dayAfter('2026-12-31')).toBe('2027-01-01');
    });

    it('строка плана: читает журнал на её дату и правит только эту строку прежними полями, датой после срока', async () => {
        const calls = network(
            [{ id: 'r1', date: '2026-12-05', categoryId: 'cat-home', type: 'EXPENSE', plannedAmount: 45_000, priority: 'MEDIUM',
                mandatory: false, description: 'Ремонт', rawInput: '40000+5000', eventKind: 'PLAN' },
            { id: 'other', date: '2026-12-05' }],
            {},
        );
        await moveLater(item(), '2026-12-25');
        expect(calls.map(c => [c.method, c.url.replace(/^.*\/api\/v1/, '')])).toEqual([
            ['GET', '/events?startDate=2026-12-05&endDate=2026-12-05'],
            ['PUT', '/events/r1?scope=THIS'],
        ]);
        expect(calls[1].body).toEqual({
            date: '2026-12-26', categoryId: 'cat-home', type: 'EXPENSE', plannedAmount: 45_000, priority: 'MEDIUM',
            mandatory: false, description: 'Ремонт', rawInput: '40000+5000',
        });
    });

    it('хотелка: своей правкой параметров, без чтения журнала', async () => {
        const calls = network(null);
        await moveLater(item({ id: 'w1', wishlist: true, amount: 12_000 }), '2026-12-25');
        expect(calls.map(c => [c.method, c.url.replace(/^.*\/api\/v1/, '')])).toEqual([['PATCH', '/events/w1/wishlist-params']]);
        expect(calls[0].body).toEqual({ plannedAmount: 12_000, date: '2026-12-26' });
    });

    it('строки нет на её дате — отказ, а не правка вслепую', async () => {
        const calls = network([]);
        await expect(moveLater(item(), '2026-12-25')).rejects.toThrow();
        expect(calls).toHaveLength(1);
    });
});
