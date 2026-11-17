import { describe, expect, it } from 'vitest';
import { ApiError } from '../api/client';
import type { AttemptKeys } from './attemptKey';
import { FirstEntryWrites } from './firstEntryWrites';
import type { BalanceCheckpointCreateDto, FinancialEventCreateDto, ScopeEnum } from '../types/api';

/**
 * Подменённый сервер: строки плана с ключами попыток, как у `POST /events` — повтор с тем же ключом
 * отдаёт уже записанное. `lose` — ответ теряется после записи: сервер записал, экран не узнал.
 */
function fakeServer() {
    const log: string[] = [];
    const events: { id: string; key: string; dto: FinancialEventCreateDto; deleted: boolean }[] = [];
    const checkpoints: { id: string; dto: BalanceCheckpointCreateDto }[] = [];
    let lose = 0;
    const api = {
        createCheckpoint: async (dto: BalanceCheckpointCreateDto) => {
            log.push(`POST checkpoint ${dto.amount}`);
            const cp = { id: `cp${checkpoints.length + 1}`, dto };
            checkpoints.push(cp);
            return { id: cp.id } as never;
        },
        updateCheckpoint: async (id: string, dto: BalanceCheckpointCreateDto) => {
            log.push(`PUT ${id} ${dto.amount}`);
            checkpoints.find(c => c.id === id)!.dto = dto;
            return { id } as never;
        },
        createPlanWithFact: async (attempts: AttemptKeys, dto: FinancialEventCreateDto) => {
            const key = attempts.keyFor('plan', dto);
            let ev = events.find(e => e.key === key);
            if (!ev) {
                ev = { id: `ev${events.length + 1}`, key, dto, deleted: false };
                events.push(ev);
                log.push(`POST ${ev.id} ${dto.plannedAmount}`);
            } else {
                log.push(`POST ${ev.id} повтор`);
            }
            await Promise.resolve();
            if (lose > 0) { lose--; throw new Error('ответ потерян'); }
            attempts.done('plan', key);
            return { id: ev.id } as never;
        },
        deleteEvent: async (id: string, scope: ScopeEnum = 'THIS') => {
            const ev = events.find(e => e.id === id && !e.deleted);
            if (!ev) throw new ApiError(404, [], 'API error: 404');
            ev.deleted = true;
            log.push(`DELETE ${id} ${scope}`);
            await Promise.resolve();
            if (lose > 0) { lose--; throw new Error('ответ потерян'); }
        },
    };
    return {
        api, log, checkpoints,
        live: () => events.filter(e => !e.deleted).map(e => `${e.dto.description ?? e.dto.type} ${e.dto.plannedAmount}`),
        loseNext: () => { lose = 1; },
    };
}

const pay = (amount: number, description = 'Аренда'): FinancialEventCreateDto => ({
    date: '2026-12-12', categoryId: 'home', type: 'EXPENSE', plannedAmount: amount, priority: 'HIGH', description,
    recurring: { frequency: 'MONTHLY', dayOfMonth: 12, startDate: '2026-12-12' },
});
const income: FinancialEventCreateDto = { date: '2026-11-25', categoryId: 'pay', type: 'INCOME', plannedAmount: 80_000, priority: 'HIGH', recurring: null };

describe('FirstEntryWrites — «Назад» правит, а не пишет второй раз (задача 2.2)', () => {
    it('вопрос 1: тот же ответ — без записи; другой — правка той же сверки', async () => {
        const s = fakeServer();
        const w = new FirstEntryWrites(s.api);
        expect(await w.balance({ date: '2026-11-17', amount: 48_800 })).toBe(true);
        expect(await w.balance({ date: '2026-11-17', amount: 48_800 })).toBe(false);
        expect(await w.balance({ date: '2026-11-17', amount: 50_000 })).toBe(true);
        expect(s.log).toEqual(['POST checkpoint 48800', 'PUT cp1 50000']);
        expect(s.checkpoints).toHaveLength(1);
    });

    it('«Назад» на шаге 3 и новая сумма: прежняя строка уходит вся, в журнале одна', async () => {
        const s = fakeServer();
        const w = new FirstEntryWrites(s.api);
        await w.plan('pay:1', pay(25_000));
        await w.plan('pay:1', pay(27_000));
        expect(s.log).toEqual(['POST ev1 25000', 'DELETE ev1 ALL', 'POST ev2 27000']);
        expect(s.live()).toEqual(['Аренда 27000']);
    });

    it('доход без повтора удаляется сам по себе, не серией', async () => {
        const s = fakeServer();
        const w = new FirstEntryWrites(s.api);
        await w.plan('income', income);
        await w.plan('income', { ...income, plannedAmount: 90_000 });
        expect(s.log).toContain('DELETE ev1 THIS');
        expect(s.live()).toEqual(['INCOME 90000']);
    });

    it('строку очистили — удаление; нечего удалять — ничего', async () => {
        const s = fakeServer();
        const w = new FirstEntryWrites(s.api);
        expect(await w.plan('pay:2', null)).toBe(false);
        await w.plan('pay:1', pay(25_000));
        expect(await w.plan('pay:1', null)).toBe(true);
        expect(await w.plan('pay:1', null)).toBe(false);
        expect(s.live()).toEqual([]);
        expect(s.log).toEqual(['POST ev1 25000', 'DELETE ev1 ALL']);
    });

    it('двойное нажатие «Дальше» — одна запись', async () => {
        const s = fakeServer();
        const w = new FirstEntryWrites(s.api);
        const [a, b] = await Promise.all([w.plan('income', income), w.plan('income', income)]);
        expect([a, b]).toEqual([true, false]);
        expect(s.live()).toHaveLength(1);
    });

    it('ответ потерялся — повтор с тем же ключом, второй строки нет', async () => {
        const s = fakeServer();
        const w = new FirstEntryWrites(s.api);
        s.loseNext();
        await expect(w.plan('pay:1', pay(25_000))).rejects.toThrow('ответ потерян');
        await w.plan('pay:1', pay(25_000));
        expect(s.log).toEqual(['POST ev1 25000', 'POST ev1 повтор']);
        expect(s.live()).toEqual(['Аренда 25000']);
    });

    it('ключ — на каждый ответ: соседняя строка не стирает попытку потерянной', async () => {
        const s = fakeServer();
        const w = new FirstEntryWrites(s.api);
        s.loseNext();
        await expect(w.plan('pay:1', pay(25_000))).rejects.toThrow();
        await w.plan('pay:2', pay(800, 'Связь'));
        await w.plan('pay:1', pay(25_000));
        expect(s.live()).toEqual(['Аренда 25000', 'Связь 800']);
    });

    it('две одинаковые строки платежа — две записи', async () => {
        const s = fakeServer();
        const w = new FirstEntryWrites(s.api);
        s.loseNext();
        await expect(w.plan('pay:1', pay(800, 'Связь'))).rejects.toThrow();
        await w.plan('pay:2', pay(800, 'Связь'));
        await w.plan('pay:1', pay(800, 'Связь'));
        expect(s.live()).toEqual(['Связь 800', 'Связь 800']);
    });

    it('удаление прошло, а ответ потерялся — повтор не застревает на 404', async () => {
        const s = fakeServer();
        const w = new FirstEntryWrites(s.api);
        await w.plan('pay:1', pay(25_000));
        s.loseNext();
        await expect(w.plan('pay:1', pay(27_000))).rejects.toThrow('ответ потерян');
        await w.plan('pay:1', pay(27_000));
        expect(s.live()).toEqual(['Аренда 27000']);
    });

    it('сбой одной записи не останавливает следующие', async () => {
        const s = fakeServer();
        const w = new FirstEntryWrites(s.api);
        s.loseNext();
        const first = w.plan('pay:1', pay(25_000));
        const second = w.plan('pay:2', pay(800, 'Связь'));
        await expect(first).rejects.toThrow();
        expect(await second).toBe(true);
    });
});
