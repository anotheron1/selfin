import { describe, expect, it } from 'vitest';
import { accountDeleteFailure, accountFormProblem, accountWriteFailure, copy } from './accountsCopy';
import { DELETE_FAILED, WRITE_FAILED } from '../../lib/writeFailure';
import type { AccountCreateDto, AccountKind } from '../../types/api';

/**
 * ANO-245. Форма счёта показывала отказ сервера как есть: «API error: 400 /accounts/… — Available floor must not
 * exceed the credit limit». Запасная фраза «Не записалось — попробуйте ещё раз» здесь соврала бы: повтор упрётся
 * в тот же отказ. Поэтому то, что экран знает сам, он говорит до отправки, а смену природы через кредитку —
 * после 400, единственного ответа сервера на неё из этой формы.
 */

const dto = (kind: AccountKind, more: Partial<AccountCreateDto> = {}): AccountCreateDto =>
    ({ name: 'Платинум', kind, trackBalance: true, purposeCategoryId: null, creditLimit: null, availableFloor: null, ...more });
const was = (kind: AccountKind, isDefault = false) => ({ kind, isDefault });
const refused = (status: number) => Object.assign(new Error(`API error: ${status} /accounts/1 — refused`), { status });

describe('форма счёта: что экран знает сам, говорит до отправки (ANO-245)', () => {
    it('планка выше лимита — доступного больше лимита не бывает', () => {
        expect(accountFormProblem(dto('CREDIT', { creditLimit: 100_000, availableFloor: 150_000 }), null))
            .toBe(copy.floorAboveLimit);
    });

    it('планка не выше лимита, без лимита или без планки — отправляем', () => {
        expect(accountFormProblem(dto('CREDIT', { creditLimit: 100_000, availableFloor: 100_000 }), null)).toBeNull();
        expect(accountFormProblem(dto('CREDIT', { creditLimit: null, availableFloor: 150_000 }), null)).toBeNull();
        expect(accountFormProblem(dto('CREDIT', { creditLimit: 100_000, availableFloor: null }), null)).toBeNull();
    });

    it('приёмник — только дебетовая карта или наличные', () => {
        expect(accountFormProblem(dto('DEPOSIT'), was('DEBIT', true))).toBe(copy.defaultKindOnly);
        expect(accountFormProblem(dto('CREDIT'), was('DEBIT', true))).toBe(copy.defaultKindOnly);
        expect(accountFormProblem(dto('CASH'), was('DEBIT', true))).toBeNull();
        expect(accountFormProblem(dto('DEPOSIT'), was('DEBIT'))).toBeNull();
    });
});

describe('форма счёта: отказ сервера словами (ANO-245)', () => {
    it('400 на смене природы через кредитку — остатки записаны в другом смысле', () => {
        expect(accountWriteFailure(refused(400), dto('DEBIT'), was('CREDIT'))).toBe(copy.kindAcrossCredit);
        expect(accountWriteFailure(refused(400), dto('CREDIT'), was('DEBIT'))).toBe(copy.kindAcrossCredit);
    });

    it('без перехода через кредитку, при новом счёте и при сбое — запасная фраза', () => {
        expect(accountWriteFailure(refused(400), dto('CASH'), was('DEBIT'))).toBe(WRITE_FAILED);
        expect(accountWriteFailure(refused(400), dto('CREDIT'), null)).toBe(WRITE_FAILED);
        expect(accountWriteFailure(refused(500), dto('DEBIT'), was('CREDIT'))).toBe(WRITE_FAILED);
        expect(accountWriteFailure(new TypeError('Failed to fetch'), dto('DEBIT'), was('CREDIT'))).toBe(WRITE_FAILED);
    });

    it('удаление: 409 — на счёте лежит копилка; остальное — запасная фраза удаления', () => {
        expect(copy.deleteHoldsGoal).toBe('Не удалилось: на счёте лежит копилка — сначала перенеси её на другой счёт');
        expect(accountDeleteFailure(refused(409))).toBe(copy.deleteHoldsGoal);
        expect(accountDeleteFailure(refused(500))).toBe(DELETE_FAILED);
        expect(accountDeleteFailure(new TypeError('Failed to fetch'))).toBe(DELETE_FAILED);
    });
});
