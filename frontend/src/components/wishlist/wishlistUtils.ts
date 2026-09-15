import type { MonthDelta } from '../../types/api';

export interface BaselinePoint { account: number; capital: number; }
export interface ActiveItem { active: boolean; delta: MonthDelta[]; }

/**
 * Аннуитетный месячный платёж по кредиту. Используется в WishlistItemCard для живого
 * пересчёта PMT при перетаскивании ползунка суммы (backend пересчитывает delta только
 * при смене ставки/срока). Перенесён из удалённого savingsStrategyUtils при миграции на /wishlist.
 */
export function calcPMT(principal: number, annualRate: number, termMonths: number): number {
    const r = annualRate / 100 / 12;
    if (r === 0) return principal / termMonths;
    const pow = Math.pow(1 + r, termMonths);
    return (principal * r * pow) / (pow - 1);
}

/**
 * Накладывает delta всех активных items на baseline. Delta трактуется как поток (flow),
 * применяемый начиная с monthIndex и накапливающийся вперёд — зеркалит backend applyDeltas.
 */
export function composeTimeline(baseline: BaselinePoint[], items: ActiveItem[]): BaselinePoint[] {
    const accountCum = new Array(baseline.length).fill(0);
    const capitalCum = new Array(baseline.length).fill(0);
    for (const item of items) {
        if (!item.active) continue;
        for (const d of item.delta) {
            for (let i = d.monthIndex; i < baseline.length; i++) {
                accountCum[i] += d.accountDelta;
                capitalCum[i] += d.capitalDelta;
            }
        }
    }
    return baseline.map((p, i) => ({
        account: p.account + accountCum[i],
        capital: p.capital + capitalCum[i],
    }));
}

/** Линейно масштабирует delta по отношению override/base (для слайдера суммы хотелки). */
export function scaleDelta(delta: MonthDelta[], baseAmount: number, override: number): MonthDelta[] {
    if (baseAmount === 0) return delta;
    const k = override / baseAmount;
    return delta.map(d => ({
        ...d,
        accountDelta: d.accountDelta * k,
        capitalDelta: d.capitalDelta * k,
        fundDelta: d.fundDelta != null ? d.fundDelta * k : d.fundDelta,
        liabilityDelta: d.liabilityDelta != null ? d.liabilityDelta * k : d.liabilityDelta,
    }));
}

export type RiskLevel = 'green' | 'yellow' | 'red';

export function riskZones(
    points: BaselinePoint[],
    thresholds: { capitalThresholdRub: number | null; cashBufferMonths: number },
    monthlyExpensesAvg: number,
): RiskLevel[] {
    const buffer = monthlyExpensesAvg * thresholds.cashBufferMonths;
    return points.map(p => {
        const accountRisk: RiskLevel =
            p.account < 0 ? 'red' : p.account < buffer ? 'yellow' : 'green';
        let capitalRisk: RiskLevel = 'green';
        if (thresholds.capitalThresholdRub != null) {
            const t = thresholds.capitalThresholdRub;
            capitalRisk = p.capital < t ? 'red' : p.capital < t * 1.1 ? 'yellow' : 'green';
        }
        return worse(accountRisk, capitalRisk);
    });
}

function worse(a: RiskLevel, b: RiskLevel): RiskLevel {
    const rank = { green: 0, yellow: 1, red: 2 };
    return rank[a] >= rank[b] ? a : b;
}

// ── Конверсия хотелки (ANO-138) ───────────────────────────────────────────────

export type ConvertTarget = 'PLAN_EVENT' | 'FUND' | 'FUND_WITH_CREDIT';

/**
 * ANO-138: «Плановое событие» подтверждается только со сроком строго в будущем.
 *
 * Пустая дата уводила план в невидимость: Бюджет, /strategy и кармашек выбирают
 * события по диапазону дат. Прошедшая и сегодняшняя отвергаются сервером
 * (`requireFutureDate`) — граница здесь ровно та же, чтобы форма не предлагала
 * того, что сервер не примет: ошибку человек всё равно не увидит, пока не починен
 * ANO-141.
 *
 * Копилке и кредиту срок в этом диалоге не нужен: фонд без targetDate — законное
 * состояние (V4, «null = не указана»), он виден на экране и дату можно поставить потом.
 *
 * Даты сравниваются как строки: ISO-формат yyyy-mm-dd лексикографически совпадает
 * с календарным порядком.
 */
export function canConfirmConversion(target: ConvertTarget, planDate: string,
                                     todayIso: string): boolean {
    if (target !== 'PLAN_EVENT') return true;
    return planDate !== '' && planDate > todayIso;
}

// ── Фиксация примерки (ANO-139) ───────────────────────────────────────────────

/** Что человек подкрутил ползунками и полями. Пусто — ничего не трогал. */
export interface TrialParams {
    amount?: number;
    targetDate?: string;
    rate?: number;
    termMonths?: number;
}

/** Что уходит в запись при фиксации. */
export interface FixPatch {
    amount: number;
    /** Отсутствует, если срока нет и его не задавали: выдумывать дату нельзя (ANO-29). */
    targetDate?: string;
    rate?: number;
    termMonths?: number;
}

/**
 * ANO-139: примерка перестала писать по жесту, и запись переехала на «Зафиксировать».
 * Отсюда правило: в запись уходит ровно то, что человек видит на экране.
 *
 * Без этого «зафиксировать» после подкрутки создало бы план на ЗАПИСАННУЮ сумму:
 * человек видел 120 000, а получил 50 000 — тот же класс дефекта, только злее.
 *
 * Срок — только настоящий: у хотелки «когда-нибудь» его нет, ползунок показывает
 * подставной ближайший месяц, и молча записывать его нельзя (ANO-29).
 *
 * Подстановка через `??`, а не `||`: ноль — законная сумма и законная ставка.
 */
export function fixPatch(
    item: { amount: number; targetDate: string | null; rate?: number | null; termMonths?: number | null },
    trial: TrialParams | undefined,
): FixPatch {
    return {
        amount: trial?.amount ?? item.amount,
        targetDate: trial?.targetDate ?? item.targetDate ?? undefined,
        rate: trial?.rate ?? item.rate ?? undefined,
        termMonths: trial?.termMonths ?? item.termMonths ?? undefined,
    };
}
