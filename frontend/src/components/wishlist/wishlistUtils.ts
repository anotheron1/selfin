import type { EventWishlistParams, FundWishlistParams, MonthDelta, WishlistItem, WishlistKind } from '../../types/api';

export interface BaselinePoint { account: number; capital: number; }
export interface ActiveItem { active: boolean; delta: MonthDelta[]; }

/**
 * Что включено, когда открываешь «Что с капиталом»: только зафиксированное.
 *
 * <p>ANO-142, решение владельца 25.09: блок открывается той же картиной, что Стратегия и
 * кармашек, — обсуждаемая хотелка не трата, пока её не примеришь галочкой. Раньше по умолчанию
 * включались и обсуждаемые, и блок отвечал «если возьмёшь всё».
 */
export function defaultActiveMap(items: WishlistItem[]): Record<string, boolean> {
    const active: Record<string, boolean> = {};
    for (const item of items) active[item.id] = item.status === 'FIXED';
    return active;
}

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

/**
 * Дельта строки примерки с учётом подкрученного: пересчитанная > исходная; подкрученная сумма
 * масштабирует ту, что взята, — от суммы, на которой её считали (ANO-105).
 *
 * <p>ANO-142: у сконвертированной хотелки деньги несёт артефакт — план или копилка, — и они уже
 * в baseline. Её дельта пуста, что бы ни подкрутили: пересчёт ставки или срока вернул бы полную
 * дельту кредита, и покупка легла бы второй раз (ревью Codex #73). Одна функция на хук и блок
 * «Что с капиталом» — копий было две.
 */
export function effectiveDelta(
    item: WishlistItem,
    override: { amount?: number; delta?: MonthDelta[]; deltaAmount?: number } | undefined,
): MonthDelta[] {
    if (item.convertedTo) return [];
    if (override?.delta != null) {
        // ANO-105: сервер посчитал дельту на сумме запроса — `deltaAmount`. Сумма, подкрученная после,
        // масштабирует её от этой суммы, как исходную — от суммы хотелки. Иначе после пересчёта
        // ставкой, сроком или датой ползунок суммы график больше не двигал.
        return override.amount != null && override.deltaAmount != null
            ? scaleDelta(override.delta, override.deltaAmount, override.amount)
            : override.delta;
    }
    if (override?.amount != null) return scaleDelta(item.delta, item.amount, override.amount);
    return item.delta;
}

/**
 * Месячный взнос копилки в примерке — строка «Взнос ≈» карточки (ANO-105, ревью Codex #124).
 *
 * <p>Сервер делит сумму на число месяцев до срока, поэтому взнос, как и дельта, масштабируется суммой.
 * Пересчитанный — из ответа пересчёта, от суммы, на которой считали; иначе загруженный — от суммы
 * хотелки. У сконвертированной примерка ничего не двигает — как и график (ANO-142).
 */
export function effectiveContribution(
    item: WishlistItem,
    override: { amount?: number; deltaAmount?: number; monthlyContribution?: number | null } | undefined,
): number | null {
    const scale = (value: number, base: number) =>
        override?.amount != null && base !== 0 ? value * override.amount / base : value;
    if (!item.convertedTo && override?.monthlyContribution != null && override.deltaAmount != null) {
        return scale(override.monthlyContribution, override.deltaAmount);
    }
    if (item.monthlyContribution == null) return null;
    return item.convertedTo ? item.monthlyContribution : scale(item.monthlyContribution, item.amount);
}

/**
 * Сумма, на которой просить пересчёт (ANO-105, ревью Codex #124). Подкрученный ноль — не основа:
 * от нуля дельту не масштабировать, и поднятая после сумма не сдвинула бы график. Тогда пересчёт
 * идёт на сумме хотелки, а подкрученный ноль даёт масштаб в `effectiveDelta`.
 */
export function recomputeBasis(amount: number, itemAmount: number): number {
    return amount !== 0 ? amount : itemAmount;
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

/**
 * ANO-104: график платежей — только у «Кредита». Галочка видна только там, а уходила при любой
 * цели: включена по умолчанию, и каждая фиксация в копилку или план просила правило, которого у
 * них нет. Сервер такую просьбу отвергает (400) — без этого правила сломалась бы фиксация в копилку.
 */
export function recurringPaymentsFor(target: ConvertTarget, checked: boolean): boolean {
    return target === 'FUND_WITH_CREDIT' && checked;
}

/**
 * ANO-107: заметка в примерке после «Отложить». Строка из примерки уходит, а вернуть её можно ниже,
 * в разделе «Отложено» блока «Что с капиталом», — без заметки путь назад не найти.
 */
export function dismissedNotice(name: string): string {
    return `Отложено: «${name}». Вернуть можно ниже — в «Что с капиталом», раздел «Отложено».`;
}

/** Что показывает диалог фиксации: пункты, выбранный при открытии и строку «почему нет кредита». */
export interface ConversionChoice {
    targets: ConvertTarget[];
    initial: ConvertTarget;
    /** Кредит без годных ставки и срока: пункта нет, и диалог говорит почему. */
    creditNeedsParams: boolean;
    /**
     * Примерку можно записать. Нельзя — ставка или срок заданы, но вне границ записи копилки:
     * тогда откажет любая фиксация, ведь запись примерки идёт первой (ревью Codex, #108).
     */
    savable: boolean;
}

/** Границы записи копилки — `FundWishlistParamsDto`: `persistTrial` пишет ставку и срок до конверсии. */
const RATE_MIN = 0.01;
const RATE_MAX = 99.99;
const TERM_MIN = 1;
const TERM_MAX = 360;

/** Задано ли число. NaN и бесконечность в JSON уходят пустыми — для сервера их нет. */
const given = (v: number | null | undefined): v is number => Number.isFinite(v);

/** Прежняя цель по умолчанию — по виду хотелки. */
const PREFERRED: Record<WishlistKind, ConvertTarget> = {
    WISHLIST: 'PLAN_EVENT',
    SAVINGS: 'FUND',
    CREDIT: 'FUND_WITH_CREDIT',
};

/**
 * ANO-141: диалог предлагает только то, что сервер примет.
 *
 * Фиксация копилки — два запроса: сначала запись примерки (`PUT /funds`, границы
 * `TargetFundCreateDto`: ставка 0,01–99,99, срок — целое 1–360), потом конверсия
 * (`WishlistConversionService`: «Кредит» — только копилке со ставкой и сроком больше нуля;
 * у хотелки-события такой ветки нет вовсе). Пункт предлагается, если пройдут оба. Ставка 0 —
 * рассрочка — не проходит первый (ревью Codex, #108). Раньше пункты были одни на всех, отказ
 * глотался, и «Зафиксировать» выглядело как «ничего не произошло».
 *
 * Ставка и срок — те, что уйдут в запись: подкрученные на карточке (`fixPatch`). Не заданы —
 * запись пройдёт, но без кредита; заданы вне границ — не пройдёт никакая фиксация.
 */
export function conversionChoice(kind: WishlistKind, rate: number | null | undefined,
                                 termMonths: number | null | undefined): ConversionChoice {
    // Хотелка-событие пишется через PUT /events: ставки и срока там нет.
    const rateOk = kind === 'WISHLIST' || !given(rate) || (rate >= RATE_MIN && rate <= RATE_MAX);
    const termOk = kind === 'WISHLIST' || !given(termMonths)
        || (Number.isInteger(termMonths) && termMonths >= TERM_MIN && termMonths <= TERM_MAX);
    const savable = rateOk && termOk;
    const creditReady = kind !== 'WISHLIST' && given(rate) && given(termMonths) && savable;
    const targets: ConvertTarget[] = creditReady
        ? ['PLAN_EVENT', 'FUND', 'FUND_WITH_CREDIT']
        : ['PLAN_EVENT', 'FUND'];
    const preferred = PREFERRED[kind];
    return {
        targets,
        initial: targets.includes(preferred) ? preferred : targets[0],
        creditNeedsParams: kind === 'CREDIT' && !creditReady,
        savable,
    };
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
/** Вопрос перед «Отложить»: что останется в расчёте и как предложить удалить (ANO-210). */
export interface DismissQuestion {
    stays: string;
    also: string;
}

/**
 * Спросить ли перед «Отложить» (ANO-210). У хотелки, из которой создан план или копилка, созданное
 * остаётся в расчёте: отложенная покупка по-прежнему держит деньги, и экран об этом молчал. Спека
 * модуля 29.05: «FIXED → DISMISSED — с подтверждением», «артефакт остаётся или удаляется по явному
 * выбору». Условие — есть созданное: вернувшаяся в обсуждение после конверсии хранит его так же.
 *
 * @returns строки диалога или `null` — созданного нет, «Отложить» пишет статус сразу
 */
export function dismissQuestion(item: Pick<WishlistItem, 'convertedTo'>): DismissQuestion | null {
    if (!item.convertedTo) return null;
    return item.convertedTo.kind === 'EVENT'
        ? { stays: 'Созданный план останется в расчёте.', also: 'Удалить и созданный план' }
        : { stays: 'Созданная копилка останется в расчёте.', also: 'Удалить и созданную копилку' };
}

/** Что «Что с капиталом» записывает при фиксации — и куда: в хотелку или в копилку (ANO-162). */
export type TrialWrite =
    | { kind: 'event'; body: EventWishlistParams }
    | { kind: 'fund'; body: FundWishlistParams };

/**
 * Параметры примерки для записи — и ничего сверх (ANO-162). Раньше блок писал полной перезаписью
 * тем, что знал: копилка на счёте отвязывалась, хотелка без описания получала имя категории и
 * теряла исходный текст, а хотелку без срока не писал вовсе — `PUT /events` требует дату.
 *
 * @param explicitDate дата из диалога фиксации (только «Плановое событие») — главнее подкрученной:
 *                     последний явный выбор человека
 */
export function trialParams(item: { kind: WishlistKind }, patch: FixPatch, explicitDate?: string): TrialWrite {
    const date = explicitDate ?? patch.targetDate;
    if (item.kind === 'WISHLIST') {
        return { kind: 'event', body: { plannedAmount: patch.amount, ...(date ? { date } : {}) } };
    }
    return {
        kind: 'fund',
        body: {
            targetAmount: patch.amount,
            ...(date ? { targetDate: date } : {}),
            ...(given(patch.rate) ? { creditRate: patch.rate } : {}),
            ...(given(patch.termMonths) ? { creditTermMonths: patch.termMonths } : {}),
        },
    };
}

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
