import { describe, expect, it } from 'vitest';
import type { FinancialEvent } from '../types/api';
import {
    expectationCards, expectationsSummary, factLinkLabel, isExpectationPlan, mainListEvents,
} from './journalSections';

const ev = (over: Partial<FinancialEvent> = {}): FinancialEvent => ({
    id: 'e1', date: '2026-09-07', categoryId: 'food', categoryName: 'Еда / Продукты', type: 'EXPENSE',
    plannedAmount: 8000, factAmount: null, status: 'PLANNED', priority: 'MEDIUM', description: null,
    rawInput: null, createdAt: '2026-09-01T10:00:00', eventKind: 'PLAN', parentEventId: null,
    linkedFactsCount: 0, linkedFactsAmount: null, parentPlanDescription: null, ...over,
});
const fact = (over: Partial<FinancialEvent> = {}) =>
    ev({ eventKind: 'FACT', plannedAmount: null, factAmount: 7350, ...over });

describe('что куда (ANO-176)', () => {
    it('ожидание уходит из недель; бронь, хотелка и любой факт остаются', () => {
        const events = [
            ev({ id: 'exp', priority: 'MEDIUM' }),
            ev({ id: 'bron', priority: 'HIGH' }),
            ev({ id: 'wish', priority: 'LOW' }),
            fact({ id: 'factOfExp', priority: 'MEDIUM', parentEventId: 'exp' }),
        ];
        expect(mainListEvents(events).map((e) => e.id)).toEqual(['bron', 'wish', 'factOfExp']);
        expect(events.filter(isExpectationPlan).map((e) => e.id)).toEqual(['exp']);
    });

    it('правило по характеру, а не по типу: ожидаемый доход — тоже ожидание', () => {
        expect(isExpectationPlan(ev({ type: 'INCOME', priority: 'MEDIUM' }))).toBe(true);
        expect(isExpectationPlan(ev({ type: 'INCOME', priority: 'HIGH' }))).toBe(false);
    });
});

describe('карточки ожиданий', () => {
    it('одна карточка на категорию; строки по дате; карточки — по первой дате', () => {
        const cards = expectationCards([
            ev({ id: 'f28', date: '2026-09-28' }),
            ev({ id: 'cafe12', date: '2026-09-12', categoryId: 'cafe', categoryName: 'Кафе', plannedAmount: 3000 }),
            ev({ id: 'f7', date: '2026-09-07' }),
            ev({ id: 'bron', date: '2026-09-05', priority: 'HIGH' }),
        ]);
        expect(cards.map((c) => c.title)).toEqual(['Еда / Продукты', 'Кафе']);
        expect(cards[0].lines.map((l) => l.id)).toEqual(['f7', 'f28']);
        expect(cards[0].lines.map((l) => l.label)).toEqual(['пн 7', 'пн 28']);
    });

    it('равные суммы — «сколько × сколько»; разные — только итог', () => {
        const [equal, mixed] = expectationCards([
            ev({ id: 'a', date: '2026-09-07' }), ev({ id: 'b', date: '2026-09-14' }),
            ev({ id: 'c', date: '2026-09-12', categoryId: 'cafe', categoryName: 'Кафе', plannedAmount: 3000 }),
            ev({ id: 'd', date: '2026-09-26', categoryId: 'cafe', categoryName: 'Кафе', plannedAmount: 4500 }),
        ]);
        expect(equal).toMatchObject({ count: 2, each: 8000, total: 16000 });
        expect(mixed).toMatchObject({ count: 2, each: null, total: 7500 });
    });

    it('«✓» — на строку записан факт; ничего не считается «осталось»', () => {
        const [card] = expectationCards([
            ev({ id: 'a', date: '2026-09-07', linkedFactsCount: 1, linkedFactsAmount: 7350 }),
            ev({ id: 'b', date: '2026-09-14' }),
        ]);
        expect(card.lines.map((l) => l.recorded)).toEqual([true, false]);
        expect(Object.keys(card)).not.toContain('remaining');
    });

    it('описание строки — примечание с её датой', () => {
        const [card] = expectationCards([
            ev({ id: 'a', date: '2026-09-26', description: 'День рождения друга' }),
            ev({ id: 'b', date: '2026-09-12' }),
        ]);
        expect(card.notes).toEqual(['сб 26 — «День рождения друга»']);
    });

    it('сводка месяца — сколько строк и на какую сумму', () => {
        expect(expectationsSummary([
            ev({ plannedAmount: 8000 }), ev({ plannedAmount: 3000 }), ev({ priority: 'HIGH', plannedAmount: 45000 }),
        ])).toEqual({ count: 2, total: 11000 });
    });

    it('в сумму сводки примерный доход не складывается с расходами', () => {
        expect(expectationsSummary([
            ev({ plannedAmount: 8000 }), ev({ type: 'INCOME', categoryId: 'side', plannedAmount: 20000 }),
        ])).toEqual({ count: 2, total: 8000 });
    });
});

describe('подпись связи факта с планом', () => {
    const plans = [
        ev({ id: 'exp', date: '2026-09-07' }),
        ev({ id: 'mort', priority: 'HIGH', categoryName: 'Ипотека', date: '2026-09-05' }),
        ev({ id: 'kg', priority: 'HIGH', categoryName: 'Образование', description: 'Детсад', date: '2026-09-03' }),
    ];
    const byId = new Map(plans.map((p) => [p.id, p]));

    it('факт ожидания — характер и дата строки', () => {
        expect(factLinkLabel(fact({ parentEventId: 'exp' }), byId)).toBe('→ ожидание пн 7');
    });

    it('факт брони — характер и имя: описание, иначе категория', () => {
        expect(factLinkLabel(fact({ parentEventId: 'mort' }), byId)).toBe('→ бронь «Ипотека»');
        expect(factLinkLabel(fact({ parentEventId: 'kg' }), byId)).toBe('→ бронь «Детсад»');
    });

    it('план не из этого месяца — прежняя подпись по описанию плана, иначе ничего', () => {
        expect(factLinkLabel(fact({ parentEventId: 'aug', parentPlanDescription: 'Отпуск' }), byId))
            .toBe('→ план «Отпуск»');
        expect(factLinkLabel(fact({ parentEventId: 'aug' }), byId)).toBeNull();
        expect(factLinkLabel(fact({ parentEventId: null }), byId)).toBeNull();
    });
});
