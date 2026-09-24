import type { CategoryProgressBar } from '../types/api';

/**
 * Полосы «По категориям за месяц» на «Аналитике» (ANO-172) — правило, а не вид.
 *
 * Цвет говорит, ЧТО это за метка, а не хорошо ли дело. Факт, план и прогноз — три разные
 * вещи, и глаз обязан их различать; но ни один цвет не меняется оттого, что факт или прогноз
 * перешёл план: красный за превышение — оценка, правила 12 и 5.
 *
 * Палитра — те же токены, что в index.css, и тест держит их в согласии. Исключение — прогноз:
 * его цвет взят у линии прогноза в графике при наведении, чтобы в одном блоке прогноз был
 * одного цвета.
 *
 * Спека: docs/superpowers/specs/2026-09-24-plan-fact-readable-design.md
 */
export const PLAN_FACT_PALETTE = {
    /** --color-surface-2 */
    track: '#1a1a28',
    /** --color-accent */
    fact: '#6c63ff',
    /** --color-text: засечка плана во всю контрастность, а не 20% белого, как было */
    plan: '#e2e8f0',
    /** линия прогноза в графике при наведении */
    forecast: '#ffaa44',
} as const;

export interface BarView {
    factPct: number;
    planPct: number;
    /** null — иглы нет: прогноз по категории выключен или не посчитан. */
    forecastPct: number | null;
    colors: { fact: string; plan: string; forecast: string };
}

/** Верх шкалы: план, а если прогноз его перерастает — прогноз с запасом. */
function scaleMax(plannedLimit: number, projection: number | null): number {
    if (!projection || projection <= plannedLimit) return plannedLimit;
    return Math.max(plannedLimit * 1.25, projection * 1.1);
}

export function barView(bar: CategoryProgressBar): BarView {
    const scale = scaleMax(bar.plannedLimit, bar.projectionAmount);
    // Плана нет, а траты есть — шкала по факту: иначе живые деньги рисовались пустой полосой.
    const max = scale > 0 ? scale : bar.currentFact;
    const pct = (value: number) => (value / max) * 100;
    return {
        factPct: max > 0 ? Math.min(pct(bar.currentFact), 100) : 0,
        planPct: max > 0 ? pct(bar.plannedLimit) : 100,
        // Игла ровно на плане не прилипает к краю дорожки — как было до правки.
        forecastPct: bar.forecastEnabled && bar.projectionAmount && max > 0
            ? Math.min(pct(bar.projectionAmount), 98)
            : null,
        colors: {
            fact: PLAN_FACT_PALETTE.fact,
            plan: PLAN_FACT_PALETTE.plan,
            forecast: PLAN_FACT_PALETTE.forecast,
        },
    };
}

/** Контраст цвета к дорожке по WCAG 2: метка, которую не видно на дорожке, ничего не говорит. */
export function contrastOnTrack(hex: string): number {
    const luminance = (h: string) => {
        const [r, g, b] = [1, 3, 5]
            .map((i) => parseInt(h.slice(i, i + 2), 16) / 255)
            .map((c) => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4));
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    };
    const [hi, lo] = [luminance(hex), luminance(PLAN_FACT_PALETTE.track)].sort((a, b) => b - a);
    return (hi + 0.05) / (lo + 0.05);
}
