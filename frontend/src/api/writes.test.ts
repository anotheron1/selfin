import { afterEach, describe, expect, it, vi } from 'vitest';
import {
    createLinkedFact, createPlanWithFact, createStandaloneFact, deleteFund, setEventWishlistStatus,
    setFundWishlistStatus, transferToFund,
} from './index';
import type { FinancialEventCreateDto } from '../types/api';
import { AttemptKeys } from '../lib/attemptKey';

type Call = { url: string; key: string | undefined };

/** Отказ сервера: статус и тело ошибки. */
class Refused {
    constructor(readonly status: number, readonly body: object) {}
}

/**
 * Сеть по сценарию. 'lost' — запрос ушёл, а ответ потерян: страница получает `TypeError`, как при
 * обрыве связи, хотя сервер, возможно, уже записал. `Refused` — отказ сервера. Объект — ответ.
 */
function network(...script: ('lost' | Refused | object)[]): Call[] {
    const calls: Call[] = [];
    vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
        calls.push({ url, key: (init.headers as Record<string, string>)['Idempotency-Key'] });
        const step = script.shift();
        if (step === undefined) throw new Error(`лишний запрос: ${url}`);
        if (step === 'lost') throw new TypeError('Failed to fetch');
        // Настоящий Response: клиент читает тело текстом (ANO-208), а не только через json().
        if (step instanceof Refused) {
            return new Response(JSON.stringify(step.body), { status: step.status });
        }
        return new Response(JSON.stringify(step), { status: 200 });
    }));
    return calls;
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

afterEach(() => { vi.unstubAllGlobals(); });

// У каждого теста своя память попыток — как у каждого экрана записи.
describe('записи денег держат ключ попытки (ANO-192)', () => {
    it('факт к плану: повтор после потерянного ответа — тот же ключ, следующая запись — новый', async () => {
        const attempts = new AttemptKeys();
        const calls = network('lost', { id: 'fact-1' }, { id: 'fact-2' });
        const dto = { date: '2026-09-27', factAmount: 192 };

        await expect(createLinkedFact(attempts, 'plan-linked', dto)).rejects.toThrow();
        await createLinkedFact(attempts, 'plan-linked', dto);
        await createLinkedFact(attempts, 'plan-linked', dto);

        expect(calls[0].key).toMatch(UUID);
        expect(calls[1].key, 'повтор — тот же ключ: сервер вернёт уже записанное').toBe(calls[0].key);
        expect(calls[2].key, 'после успеха — новая запись').not.toBe(calls[1].key);
    });

    it('факт без плана: повтор после потерянного ответа — тот же ключ, следующая запись — новый', async () => {
        const attempts = new AttemptKeys();
        const calls = network('lost', { id: 'fact-1' }, { id: 'fact-2' });
        const dto = { date: '2026-09-27', categoryId: 'cat-1', type: 'EXPENSE' as const, factAmount: 150 };

        await expect(createStandaloneFact(attempts, dto)).rejects.toThrow();
        await createStandaloneFact(attempts, dto);
        await createStandaloneFact(attempts, dto);

        expect(calls[0].key).toMatch(UUID);
        expect(calls[1].key, 'повтор — тот же ключ: сервер вернёт уже записанное').toBe(calls[0].key);
        expect(calls[2].key, 'после успеха — новая запись').not.toBe(calls[1].key);
    });

    it('перевод в копилку: повтор после потерянного ответа — тот же ключ, следующий перевод — новый', async () => {
        const attempts = new AttemptKeys();
        const calls = network('lost', { id: 'fund-1' }, { id: 'fund-1' });

        await expect(transferToFund(attempts, 'fund-transfer', 1000, undefined, 'SECOND_INCOME')).rejects.toThrow();
        await transferToFund(attempts, 'fund-transfer', 1000, undefined, 'SECOND_INCOME');
        await transferToFund(attempts, 'fund-transfer', 1000, undefined, 'SECOND_INCOME');

        expect(calls[0].key).toMatch(UUID);
        expect(calls[1].key, 'повтор — тот же ключ: второго перевода нет').toBe(calls[0].key);
        expect(calls[2].key, 'после успеха — новый перевод').not.toBe(calls[1].key);
    });

    it('перевод с подтверждением: подтверждение — та же попытка, повтор не переводит второй раз', async () => {
        const attempts = new AttemptKeys();
        // «Пополнить» при нехватке — два запроса: без подтверждения (сервер переспрашивает, 409,
        // ничего не записав) и с ним. Потерян ответ на подтверждённый — человек жмёт снова, и
        // первый же запрос повтора обязан прийти с ключом того, что уже записано.
        const calls = network(
            new Refused(409, { message: 'Needs confirmation', details: ['CONFIRM_REQUIRED'] }),
            'lost',
            { id: 'fund-2' },
        );

        await expect(transferToFund(attempts, 'fund-confirm', 1000, undefined, 'SECOND_INCOME')).rejects.toThrow();
        await expect(transferToFund(attempts, 'fund-confirm', 1000, true, 'SECOND_INCOME')).rejects.toThrow();
        await transferToFund(attempts, 'fund-confirm', 1000, undefined, 'SECOND_INCOME');

        expect(calls[1].key, 'подтверждение не меняет ключ').toBe(calls[0].key);
        expect(calls[2].key, 'повтор — ключ записанного перевода: сервер его вернёт').toBe(calls[1].key);
    });

    it('план с фактом: факт не дошёл — повтор шлёт и план, и факт с прежними ключами, второго плана нет', async () => {
        const attempts = new AttemptKeys();
        const calls = network(
            { id: 'plan-9' }, 'lost',            // план записан, ответ на факт потерян
            { id: 'plan-9' }, { id: 'fact-9' },  // повтор: сервер узнаёт план по ключу
            { id: 'plan-10' }, { id: 'fact-10' }, // следующая такая же запись
        );
        const plan = {
            date: '2026-09-27', type: 'EXPENSE', categoryId: 'cat-1', plannedAmount: 500, priority: 'MEDIUM',
        } as FinancialEventCreateDto;
        const fact = { date: '2026-09-27', factAmount: 500 };

        await expect(createPlanWithFact(attempts, plan, fact)).rejects.toThrow();
        await createPlanWithFact(attempts, plan, fact);
        await createPlanWithFact(attempts, plan, fact);

        expect(calls[0].key).toMatch(UUID);
        expect(calls[2].key, 'план повторён с тем же ключом — второго плана нет').toBe(calls[0].key);
        expect(calls[3].key, 'факт повторён с тем же ключом').toBe(calls[1].key);
        expect(calls[4].key, 'после успеха — новый план').not.toBe(calls[0].key);
    });
});

// ANO-210: «Отложить» с удалением созданного — одна запись на сервере: статус и флаг вместе.
describe('смена статуса хотелки несёт выбор судьбы созданного (ANO-210)', () => {
    it('с флагом — deleteArtifact: true; без флага — только статус', async () => {
        const bodies: unknown[] = [];
        vi.stubGlobal('fetch', vi.fn(async (_url: string, init: RequestInit) => {
            bodies.push(JSON.parse(init.body as string));
            return new Response(null, { status: 200 });   // ручка статуса отвечает без тела (ANO-208)
        }));
        await setEventWishlistStatus('e1', 'DISMISSED', true);
        await setEventWishlistStatus('e1', 'DISMISSED');
        await setFundWishlistStatus('f1', 'DISMISSED', true);
        await setFundWishlistStatus('f1', 'OPEN');
        expect(bodies).toEqual([
            { status: 'DISMISSED', deleteArtifact: true },
            { status: 'DISMISSED' },
            { status: 'DISMISSED', deleteArtifact: true },
            { status: 'OPEN' },
        ]);
    });
});

// ANO-198: копилку с деньгами сервер удаляет только с ответом, что с ними (ANO-86): без него — 409.
describe('удаление копилки несёт ответ «что с деньгами» (ANO-198)', () => {
    it('ответ уходит параметром money; без ответа — без параметра', async () => {
        const calls: string[] = [];
        vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
            calls.push(`${init.method} ${url}`);
            return new Response(null, { status: 204 });
        }));
        await deleteFund('f1', 'RETURN');
        await deleteFund('f1', 'SPENT');
        await deleteFund('f1');
        expect(calls.map(c => c.replace(/ \S*\/funds\//, ' /funds/'))).toEqual([
            'DELETE /funds/f1?money=RETURN',
            'DELETE /funds/f1?money=SPENT',
            'DELETE /funds/f1',
        ]);
    });
});
