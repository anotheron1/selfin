import { describe, expect, it } from 'vitest';
import type { Account, PocketResponse, TargetFund } from '../types/api';
import { fmtRub } from './format';
import { NZ_OWN_ACCOUNT_EASIER, nzOnCardNote, reserveAccounts, reserveDto, reserveFreeLine, reserveOf } from './reserve';

/** НЗ — копилка (срез 4, пп. 7, 19; доска S4-G2): слова карточки и листа, тело `PUT /funds/{id}`. */

const nz = {
    id: 'nz', name: 'НЗ', targetAmount: 30_000, currentBalance: 0, accountId: null, status: 'FUNDING', priority: 0,
    targetDate: null, estimatedCompletionDate: null, purchaseType: 'SAVINGS', creditRate: null, creditTermMonths: null,
    wishlistStatus: null, reserve: true,
} as TargetFund;
const free = (pocket: number, buffer: number) => ({ pocket, buffer, horizon: { endDate: '2026-12-05' } }) as PocketResponse;

describe('НЗ', () => {
    it('находится по признаку, а не по имени', () => {
        expect(reserveOf([{ ...nz, id: 'x', name: 'НЗ', reserve: false }, { ...nz, name: 'Запас' }])?.name).toBe('Запас');
    });

    it('«Свободно до …» за вычетом НЗ — только когда «Свободно» не в минусе и НЗ задан', () => {
        expect(reserveFreeLine(free(17_000, 30_000)))
            .toBe(`«Свободно до 5 дек.» — ${fmtRub(17_000)}: уже за вычетом ${fmtRub(30_000)} НЗ.`);
        expect(reserveFreeLine(free(0, 30_000))).not.toBeNull();
        expect(reserveFreeLine(free(-1, 30_000))).toBeNull();
        expect(reserveFreeLine(free(17_000, 0))).toBeNull();
        expect(reserveFreeLine(null)).toBeNull();
    });

    it('на основной — «сколько держать» из листа и без счёта; на своём счёте — прежняя сумма и счёт', () => {
        expect(reserveDto(nz, { accountId: null, keep: 45_000 })).toMatchObject({ targetAmount: 45_000, accountId: null, name: 'НЗ' });
        expect(reserveDto(nz, { accountId: 'dep', keep: 0 })).toMatchObject({ targetAmount: 30_000, accountId: 'dep' });
    });

    it('записка на карточке: с суммой — сколько остаётся на карте; без — одно «свой счёт проще»', () => {
        expect(nzOnCardNote(30_000)).toBe(`«Свободно» считается так, чтобы на карте оставалось не меньше ${fmtRub(30_000)}. ${NZ_OWN_ACCOUNT_EASIER}`);
        expect(nzOnCardNote(0)).toBe(NZ_OWN_ACCOUNT_EASIER);
    });

    it('свой счёт НЗ — только накопительная: основная и карта «на всё» и так в «Свободно», привязка к ним — 400', () => {
        const acc = (id: string, kind: Account['kind'], isDefault = false) => ({ id, name: id, kind, trackBalance: true, isDefault }) as Account;
        expect(reserveAccounts([acc('main', 'DEBIT', true), acc('second', 'DEBIT'), acc('cash', 'CASH'), acc('dep', 'DEPOSIT')], [nz], nz)
            .map(c => c.id)).toEqual(['dep']);
    });
});
