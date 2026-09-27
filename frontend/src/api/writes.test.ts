import { afterEach, describe, expect, it, vi } from 'vitest';
import { createLinkedFact, createPlanWithFact, createStandaloneFact, transferToFund } from './index';
import type { FinancialEventCreateDto } from '../types/api';

type Call = { url: string; key: string | undefined };

/**
 * Сеть по сценарию. 'lost' — запрос ушёл, а ответ потерян: страница получает `TypeError`, как при
 * обрыве связи, хотя сервер, возможно, уже записал. Объект — ответ сервера.
 */
function network(...script: ('lost' | object)[]): Call[] {
    const calls: Call[] = [];
    vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
        calls.push({ url, key: (init.headers as Record<string, string>)['Idempotency-Key'] });
        const step = script.shift();
        if (step === undefined) throw new Error(`лишний запрос: ${url}`);
        if (step === 'lost') throw new TypeError('Failed to fetch');
        return { ok: true, status: 200, json: async () => step } as Response;
    }));
    return calls;
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

afterEach(() => { vi.unstubAllGlobals(); });

// У каждого теста своя цель записи — свой план или копилка: память попыток одна на приложение.
describe('записи денег держат ключ попытки (ANO-192)', () => {
    it('факт к плану: повтор после потерянного ответа — тот же ключ, следующая запись — новый', async () => {
        const calls = network('lost', { id: 'fact-1' }, { id: 'fact-2' });
        const dto = { date: '2026-09-27', factAmount: 192 };

        await expect(createLinkedFact('plan-linked', dto)).rejects.toThrow();
        await createLinkedFact('plan-linked', dto);
        await createLinkedFact('plan-linked', dto);

        expect(calls[0].key).toMatch(UUID);
        expect(calls[1].key, 'повтор — тот же ключ: сервер вернёт уже записанное').toBe(calls[0].key);
        expect(calls[2].key, 'после успеха — новая запись').not.toBe(calls[1].key);
    });

    it('факт без плана: повтор после потерянного ответа — тот же ключ, следующая запись — новый', async () => {
        const calls = network('lost', { id: 'fact-1' }, { id: 'fact-2' });
        const dto = { date: '2026-09-27', categoryId: 'cat-1', type: 'EXPENSE' as const, factAmount: 150 };

        await expect(createStandaloneFact(dto)).rejects.toThrow();
        await createStandaloneFact(dto);
        await createStandaloneFact(dto);

        expect(calls[0].key).toMatch(UUID);
        expect(calls[1].key, 'повтор — тот же ключ: сервер вернёт уже записанное').toBe(calls[0].key);
        expect(calls[2].key, 'после успеха — новая запись').not.toBe(calls[1].key);
    });

    it('перевод в копилку: повтор после потерянного ответа — тот же ключ, следующий перевод — новый', async () => {
        const calls = network('lost', { id: 'fund-1' }, { id: 'fund-1' });

        await expect(transferToFund('fund-transfer', 1000, undefined, 'SECOND_INCOME')).rejects.toThrow();
        await transferToFund('fund-transfer', 1000, undefined, 'SECOND_INCOME');
        await transferToFund('fund-transfer', 1000, undefined, 'SECOND_INCOME');

        expect(calls[0].key).toMatch(UUID);
        expect(calls[1].key, 'повтор — тот же ключ: второго перевода нет').toBe(calls[0].key);
        expect(calls[2].key, 'после успеха — новый перевод').not.toBe(calls[1].key);
    });

    it('план с фактом: факт не дошёл — повтор шлёт и план, и факт с прежними ключами, второго плана нет', async () => {
        const calls = network(
            { id: 'plan-9' }, 'lost',            // план записан, ответ на факт потерян
            { id: 'plan-9' }, { id: 'fact-9' },  // повтор: сервер узнаёт план по ключу
            { id: 'plan-10' }, { id: 'fact-10' }, // следующая такая же запись
        );
        const plan = {
            date: '2026-09-27', type: 'EXPENSE', categoryId: 'cat-1', plannedAmount: 500, priority: 'MEDIUM',
        } as FinancialEventCreateDto;
        const fact = { date: '2026-09-27', factAmount: 500 };

        await expect(createPlanWithFact(plan, fact)).rejects.toThrow();
        await createPlanWithFact(plan, fact);
        await createPlanWithFact(plan, fact);

        expect(calls[0].key).toMatch(UUID);
        expect(calls[2].key, 'план повторён с тем же ключом — второго плана нет').toBe(calls[0].key);
        expect(calls[3].key, 'факт повторён с тем же ключом').toBe(calls[1].key);
        expect(calls[4].key, 'после успеха — новый план').not.toBe(calls[0].key);
    });
});
