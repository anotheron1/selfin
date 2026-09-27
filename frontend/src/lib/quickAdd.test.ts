import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { ON_ACCOUNT_NOTE, canSubmitQuickAdd, quickAddAction, transferFundChoice } from './quickAdd';
import type { TargetFund } from '../types/api';

/** ANO-169: быстрый ввод «В копилку» — решение владельца 27.09. */
describe('quickAddAction', () => {
    it('перевод «уже перевёл» без плана — перевод, как кнопка «Пополнить», без плана', () => {
        expect(quickAddAction(true, false, true)).toBe('transfer');
    });

    it('расход «уже произошло» без плана — отдельный факт, как раньше', () => {
        expect(quickAddAction(false, false, true)).toBe('standaloneFact');
    });

    it('план — план; с фактом — факт к нему (у перевода деньги двигает сервер)', () => {
        expect(quickAddAction(true, true, false)).toBe('plan');
        expect(quickAddAction(true, true, true)).toBe('plan');
        expect(quickAddAction(false, true, true)).toBe('plan');
    });
});

describe('canSubmitQuickAdd', () => {
    const transfer = { isFundTransfer: true, targetFundId: 'f1', hasPlanAmount: false, hasFactAmount: false };

    it('перевод без суммы вовсе — сохранять нечего: был бы план «Горнолыжка 0 ₽»', () => {
        expect(canSubmitQuickAdd(transfer)).toBe(false);
    });

    it('перевод с суммой факта или плана — можно', () => {
        expect(canSubmitQuickAdd({ ...transfer, hasFactAmount: true })).toBe(true);
        expect(canSubmitQuickAdd({ ...transfer, hasPlanAmount: true })).toBe(true);
    });

    it('перевод без копилки — нельзя', () => {
        expect(canSubmitQuickAdd({ ...transfer, targetFundId: undefined, hasFactAmount: true })).toBe(false);
    });

    it('расход и доход — по категории, как раньше', () => {
        const expense = { isFundTransfer: false, hasPlanAmount: false, hasFactAmount: false };
        expect(canSubmitQuickAdd({ ...expense, categoryId: 'c1' })).toBe(true);
        expect(canSubmitQuickAdd(expense)).toBe(false);
    });
});

describe('transferFundChoice', () => {
    const fund = (id: string, over: Partial<TargetFund> = {}) =>
        ({ id, name: id, status: 'FUNDING', accountId: null, ...over }) as TargetFund;

    it('копилки на счёте в списке нет — перевод в них сервер отвергает', () => {
        const { choices, onAccount } = transferFundChoice([
            fund('конверт'), fund('на счёте', { accountId: 'acc' }), fund('достигнута', { status: 'REACHED' }),
        ]);
        expect(choices.map(f => f.id)).toEqual(['конверт']);
        expect(onAccount).toBe(1);
    });

    it('достигнутая копилка на счёте в счётчик не входит — её не было в списке и раньше', () => {
        expect(transferFundChoice([fund('x', { accountId: 'acc', status: 'REACHED' })]).onAccount).toBe(0);
    });
});

/** Сторож по исходнику: форма зовёт именно эти решения. */
describe('быстрый ввод «В копилку» (ANO-169)', () => {
    const src = readFileSync(new URL('../components/Fab.tsx', import.meta.url), 'utf8');

    it('«уже перевёл» — перевод без вопроса и той датой, что в форме', () => {
        expect(src).toContain('quickAddAction(isFundTransfer, hasPlanAmount, hasFactAmount)');
        expect(src).toContain('transferToFund(form.targetFundId!, factAmount!, true, undefined, form.date!)');
    });

    it('факт к плану перевода больше не пропускается', () => {
        expect(src).not.toContain('hasFactAmount && !isFundTransfer');
        // ANO-192: план и факт — одна попытка, факт уходит вторым аргументом, если деньги уже ушли.
        expect(src).toMatch(/createPlanWithFact\([\s\S]*?\}, hasFactAmount \? \{/);
    });

    it('«Сохранить» — по правилу canSubmitQuickAdd, копилки — по transferFundChoice, со строкой почему', () => {
        expect(src).toContain('canSubmitQuickAdd({');
        expect(src).toContain('transferFundChoice(funds)');
        expect(src).toContain('{ON_ACCOUNT_NOTE}');
        expect(ON_ACCOUNT_NOTE).toContain('на счёте');
    });

    it('у перевода нет поля описания и нет повтора', () => {
        expect(src).toMatch(/\{!isFundTransfer && \(\s*<Input\s+placeholder="Название транзакции/);
        expect(src).toContain('recurringEnabled && !isFundTransfer');
    });
});
