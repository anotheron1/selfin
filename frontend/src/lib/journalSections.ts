import type { FinancialEvent } from '../types/api';
import { PRIORITY_DOT_CONFIG } from './priority';

/**
 * Журнал на два списка (ANO-176): что идёт в недели, а что — в карточки ожиданий.
 *
 * Это правило, а не вид. На дизайн-сессии журнал будут перерисовывать — раскладка уйдёт,
 * правило останется, поэтому оно живёт здесь, а не в разметке страницы.
 *
 * Спека: docs/superpowers/specs/2026-09-24-journal-two-lists-design.md
 */

/** Плановая строка-ожидание: сумма примерная, тратится по ходу. Правило по характеру, а не по типу. */
export const isExpectationPlan = (e: FinancialEvent): boolean =>
    e.eventKind === 'PLAN' && e.priority === 'MEDIUM';

/** Основной список: брони, хотелки и все факты — факт всегда точен, в том числе факт ожидания. */
export const mainListEvents = (events: FinancialEvent[]): FinancialEvent[] =>
    events.filter((e) => !isExpectationPlan(e));

const DOW = ['вс', 'пн', 'вт', 'ср', 'чт', 'пт', 'сб'];

/** «пн 7». ISO-дата разбирается руками: new Date('yyyy-mm-dd') читает её как UTC и вечером сдвигает день. */
export function dayLabel(iso: string): string {
    const [y, m, d] = iso.split('-').map(Number);
    return `${DOW[new Date(y, m - 1, d).getDay()]} ${d}`;
}

export interface ExpectationLine {
    id: string;
    label: string;
    /** На строку уже записан факт — «✓» у даты. Сколько записано, не показывается: канон, граница по запрету 1. */
    recorded: boolean;
    event: FinancialEvent;
}

export interface ExpectationCard {
    key: string;
    title: string;
    count: number;
    /** Сумма одной строки, если у всех строк она одинакова, — «4 × 8 000 ₽»; иначе null и показывается итог. */
    each: number | null;
    total: number;
    income: boolean;
    lines: ExpectationLine[];
    /** Описания строк с их датой — «сб 26 — «День рождения друга»». */
    notes: string[];
}

const datedExpectations = (events: FinancialEvent[]) => events
    .filter((e) => isExpectationPlan(e) && e.date != null)
    .sort((a, b) => (a.date! < b.date! ? -1 : a.date! > b.date! ? 1 : 0));

/** Одна карточка на категорию; строки — по дате; карточки — по дате первой строки. */
export function expectationCards(events: FinancialEvent[]): ExpectationCard[] {
    const groups = new Map<string, FinancialEvent[]>();
    for (const e of datedExpectations(events)) {
        const key = e.type === 'FUND_TRANSFER' ? `fund:${e.targetFundId ?? ''}` : e.categoryId;
        groups.set(key, [...(groups.get(key) ?? []), e]);
    }
    return [...groups].map(([key, rows]) => {
        const amounts = rows.map((e) => e.plannedAmount ?? 0);
        const first = rows[0];
        return {
            key,
            title: first.type === 'FUND_TRANSFER' ? (first.targetFundName ?? first.categoryName) : first.categoryName,
            count: rows.length,
            each: amounts.every((a) => a === amounts[0]) ? amounts[0] : null,
            total: amounts.reduce((s, a) => s + a, 0),
            income: first.type === 'INCOME',
            lines: rows.map((e) => ({
                id: e.id,
                label: dayLabel(e.date!),
                recorded: e.linkedFactsCount > 0 || e.status === 'EXECUTED' || e.factAmount != null,
                event: e,
            })),
            notes: rows.filter((e) => e.description).map((e) => `${dayLabel(e.date!)} — «${e.description}»`),
        };
    });
}

/**
 * «14 строк · 53 100 ₽» — для заголовка колонки и свёрнутого блока. Сумма — только того, что
 * уходит: примерный доход, сложенный с расходами, дал бы число, которое ничего не значит.
 */
export function expectationsSummary(events: FinancialEvent[]): { count: number; total: number } {
    const rows = datedExpectations(events);
    const outflow = rows.filter((e) => e.type !== 'INCOME');
    return { count: rows.length, total: outflow.reduce((s, e) => s + (e.plannedAmount ?? 0), 0) };
}

/**
 * Подпись связи факта с планом — характером плана: «→ ожидание пн 7», «→ бронь «Ипотека»».
 * План не из этого месяца в выборке журнала не лежит — тогда прежняя подпись по описанию плана.
 */
export function factLinkLabel(f: FinancialEvent, plansById: Map<string, FinancialEvent>): string | null {
    if (f.eventKind !== 'FACT' || !f.parentEventId) return null;
    const plan = plansById.get(f.parentEventId);
    if (!plan) return f.parentPlanDescription ? `→ план «${f.parentPlanDescription}»` : null;
    const kind = PRIORITY_DOT_CONFIG[plan.priority].name.toLowerCase();
    if (isExpectationPlan(plan) && plan.date) return `→ ${kind} ${dayLabel(plan.date)}`;
    return `→ ${kind} «${plan.description || plan.categoryName}»`;
}
