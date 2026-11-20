import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { askDateChoices, askDateNote, closePlanOf, ownEffect, planRest, restQuestion } from './ownFact';
import type { FinancialEvent } from '../types/api';

const plan = (over: Partial<FinancialEvent> = {}) => ({
    id: 'p', eventKind: 'PLAN', type: 'EXPENSE', priority: 'HIGH', plannedAmount: 25000, linkedFactsAmount: 0,
    linkedFactsCount: 0, date: '2026-11-12', status: 'PLANNED', ...over,
}) as FinancialEvent;

/** Срез 3 п. 3 (S3-F6, S3-F9, S3-F9b, решение 52): «своё» у брони. */
describe('planRest', () => {
    it('остаток — план минус записанное к нему', () => {
        expect(planRest(plan({ linkedFactsAmount: 10000 }))).toBe(15000);
        expect(planRest(plan({ linkedFactsAmount: 30000 }))).toBe(0);
    });
});

describe('restQuestion', () => {
    it('у брони записано меньше остатка — вопрос, по умолчанию «Доплачу позже»', () => {
        expect(restQuestion(plan(), 10000)).toEqual({ rest: 15000, options: ['Доплачу позже', 'Больше не нужно'] });
    });
    it('у доходной брони — свои слова', () => {
        expect(restQuestion(plan({ type: 'INCOME' }), 10000)?.options).toEqual(['Придут позже', 'Больше не жду']);
    });
    it('весь остаток или больше — вопроса нет', () => {
        expect(restQuestion(plan(), 25000)).toBeNull();
        expect(restQuestion(plan(), 30000)).toBeNull();
    });
    it('не бронь — вопроса нет: ожидание и так не держится после своей даты', () => {
        expect(restQuestion(plan({ priority: 'MEDIUM' }), 1000)).toBeNull();
        expect(restQuestion(plan({ priority: 'LOW' }), 1000)).toBeNull();
    });
    it('суммы нет — вопроса нет', () => {
        expect(restQuestion(plan(), null)).toBeNull();
    });
});

describe('closePlanOf', () => {
    it('«Больше не нужно» — closePlan в запросе; «Доплачу позже» — нет', () => {
        expect(closePlanOf(plan(), 10000, 'close')).toBe(true);
        expect(closePlanOf(plan(), 10000, 'later')).toBeUndefined();
    });
    it('вопроса не было — признака нет, даже если выбор остался от прежней суммы', () => {
        expect(closePlanOf(plan(), 25000, 'close')).toBeUndefined();
        expect(closePlanOf(plan({ priority: 'MEDIUM' }), 100, 'close')).toBeUndefined();
    });
});

describe('ownEffect', () => {
    it('«Доплачу позже» — остаток держится бронью', () => {
        expect(ownEffect(plan(), 10000, 'later')).toBe('Остальные 15 000 ₽ держатся бронью, пока не доплатишь или не обновишь остаток.');
    });
    it('«Больше не нужно» — бронь закроется, остаток вернётся в «Свободно»', () => {
        expect(ownEffect(plan(), 10000, 'close')).toBe('Бронь закроется на 10 000 ₽ — остальные 15 000 ₽ вернутся в «Свободно».');
    });
    it('часть уже оплачена — бронь закроется на всё заплаченное, вернётся остаток после этой записи', () => {
        expect(ownEffect(plan({ linkedFactsAmount: 5000 }), 10000, 'close'))
            .toBe('Бронь закроется на 15 000 ₽ — остальные 10 000 ₽ вернутся в «Свободно».');
    });
    it('весь остаток — бронь оплачена; больше — лишнее уменьшает «Свободно»', () => {
        expect(ownEffect(plan(), 25000, 'later')).toBe('Бронь оплачена целиком.');
        expect(ownEffect(plan(), 26000, 'later')).toBe('Бронь оплачена; сверх неё — «Свободно» меньше на 1 000 ₽.');
    });
    it('у ожидания и без суммы — строки нет', () => {
        expect(ownEffect(plan({ priority: 'MEDIUM' }), 1000, 'later')).toBeNull();
        expect(ownEffect(plan(), null, 'later')).toBeNull();
    });
    it('бронь не позже сверки «Свободно» не держит — обещать нечего, строки нет (ревью Codex #235)', () => {
        expect(ownEffect(plan(), 10000, 'close', '2026-11-12')).toBeNull();
        expect(ownEffect(plan(), 10000, 'close', '2026-11-15')).toBeNull();
        expect(ownEffect(plan(), 10000, 'close', '2026-11-11')).toContain('вернутся в «Свободно»');
        expect(ownEffect(plan(), 10000, 'close', '2026-11-11', '2026-11-10')).toBeNull();
        expect(ownEffect(plan(), 10000, 'close', '2026-11-11', '2026-11-11')).toContain('вернутся в «Свободно»');
        expect(readFileSync('src/components/PlanFactSheet.tsx', 'utf8')).toContain('ownEffect(plan, amount, choice, checkpointDate, date, horizonEnd)');
        expect(readFileSync('src/pages/Budget.tsx', 'utf8')).toContain('checkpointDate={anchor.loaded ? anchor.date : undefined}');
    });
    it('бронь за концом расчёта «Свободно» не держит — строки нет; без горизонта лист её не показывает (ревью Codex #235)', () => {
        expect(ownEffect(plan(), 10000, 'close', null, undefined, '2026-11-11')).toBeNull();
        expect(ownEffect(plan(), 10000, 'close', null, undefined, '2026-11-12')).toContain('вернутся в «Свободно»');
        expect(readFileSync('src/components/PlanFactSheet.tsx', 'utf8')).toContain('const effect = horizonEnd ? ownEffect(');
    });
    it('после неудачной отмены срок строки идёт заново — строка не повисает (ревью Codex #235)', () => {
        const toast = readFileSync('src/components/UndoToast.tsx', 'utf8');
        expect(toast).toContain('if (busy) return;');
        expect(toast).toContain('}, [busy, failed]);');
        expect(toast).not.toContain('busy || failed');
    });
    it('лист записи — Radix Dialog: фокус в окне, фон инертен (ревью Codex #235)', () => {
        expect(readFileSync('src/components/FormSheet.tsx', 'utf8')).toContain('<Dialog.Content');
    });
    it('у доходной брони — строки нет', () => {
        expect(ownEffect(plan({ type: 'INCOME' }), 10000, 'close')).toBeNull();
    });
});

/** S3-F6b: бронь с датой раньше сверки — когда ушли деньги, двумя кнопками и «Другая дата». */
describe('askDate', () => {
    it('«До — дата плана», «После — сегодня»', () => {
        expect(askDateChoices(plan({ date: '2026-11-08' }), '2026-11-19')).toEqual([
            { value: '2026-11-08', label: 'До — 8 нояб.' },
            { value: '2026-11-19', label: 'После — сегодня' },
        ]);
    });
    it('пояснение: до — «Свободно» не изменится, после — меньше на сумму; у дохода — больше', () => {
        expect(askDateNote(plan(), 600))
            .toBe('До — значит, они уже в остатке, и «Свободно» не изменится. После — «Свободно» станет меньше на 600 ₽.');
        expect(askDateNote(plan({ type: 'INCOME' }), 600)).toContain('станет больше на 600 ₽');
    });
});

/** Сторож: «Оплачено» раньше сверки — вопрос, а не поле даты; запись — к плану с closePlan. */
describe('форма записи к плану', () => {
    const src = readFileSync(new URL('../components/PlanFactSheet.tsx', import.meta.url), 'utf8');
    it('пишет к плану, с признаком «Больше не нужно» по правилу closePlanOf', () => {
        expect(src).toContain('createLinkedFact(attempts, plan.id');
        expect(src).toContain('closePlan: closePlanOf(');
    });
    it('вопрос «до или после» — только при askDate', () => {
        expect(src).toMatch(/askDate \?[\s\S]*askDateChoices\(/);
    });
});
