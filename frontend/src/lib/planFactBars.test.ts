import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import type { CategoryProgressBar } from '../types/api';
import { PLAN_FACT_PALETTE, barView, contrastOnTrack } from './planFactBars';

const bar = (over: Partial<CategoryProgressBar> = {}): CategoryProgressBar => ({
    categoryName: 'Еда / Продукты', currentFact: 3000, plannedLimit: 8000, percentage: 37.5,
    projectionAmount: 7000, forecastEnabled: true, history: [], ...over,
});

describe('цвет говорит, что это за метка, а не хорошо ли дело (ANO-172)', () => {
    it('факт и прогноз выше плана красятся так же, как ниже плана', () => {
        const under = barView(bar({ currentFact: 3000, projectionAmount: 7000 }));
        const over = barView(bar({ currentFact: 12000, projectionAmount: 15000 }));
        expect(over.colors).toEqual(under.colors);
        expect(under.colors).toEqual({
            fact: PLAN_FACT_PALETTE.fact,
            plan: PLAN_FACT_PALETTE.plan,
            forecast: PLAN_FACT_PALETTE.forecast,
        });
    });
});

describe('метки видны и различимы', () => {
    const marks = ['fact', 'plan', 'forecast'] as const;

    it.each(marks)('%s — непрозрачный цвет, контраст к дорожке не меньше 3:1', (mark) => {
        const color = PLAN_FACT_PALETTE[mark];
        expect(color).toMatch(/^#[0-9a-f]{6}$/i);
        expect(contrastOnTrack(color)).toBeGreaterThanOrEqual(3);
    });

    it('три метки — три разных цвета', () => {
        expect(new Set(marks.map((m) => PLAN_FACT_PALETTE[m].toLowerCase())).size).toBe(3);
    });
});

describe('блок берёт цвета меток только из правила', () => {
    // Сторож по исходнику: компонентных тестов в проекте нет. Полупрозрачный белый — то, чем
    // метки были нарисованы до ANO-172; остальное — цвета-оценки, которые снимали по ANO-119.
    const src = readFileSync(new URL('../components/analytics/CategoryProgressSection.tsx', import.meta.url), 'utf8');

    it.each(['bg-white/', 'rgba(255,255,255', 'destructive', 'green-', '#ef4444', '#22c55e', 'color-danger', 'color-success'])(
        'в блоке нет «%s»', (fragment) => {
            expect(src).not.toContain(fragment);
        });
});

describe('палитра — те же токены, что в index.css', () => {
    const css = readFileSync(new URL('../index.css', import.meta.url), 'utf8');
    const token = (name: string) =>
        css.match(new RegExp(`${name}:\\s*(#[0-9a-fA-F]{6})\\s*;`))?.[1]?.toLowerCase();

    it.each([
        ['track', '--color-surface-2'],
        ['fact', '--color-accent'],
        ['plan', '--color-text'],
    ] as const)('%s = %s', (key, name) => {
        expect(token(name)).toBeDefined();
        expect(PLAN_FACT_PALETTE[key].toLowerCase()).toBe(token(name));
    });
});

describe('положение меток — как до правки', () => {
    it('прогноз не выше плана — шкала по плану', () => {
        const v = barView(bar({ currentFact: 3000, plannedLimit: 8000, projectionAmount: 7000 }));
        expect(v.planPct).toBe(100);
        expect(v.factPct).toBeCloseTo(37.5);
        expect(v.forecastPct).toBeCloseTo(87.5);
    });

    it('прогноз выше плана — шкала растёт, засечка плана уходит влево', () => {
        // верх шкалы — max(8 000 × 1,25; 12 000 × 1,1) = 13 200
        const v = barView(bar({ currentFact: 6000, plannedLimit: 8000, projectionAmount: 12000 }));
        expect(v.planPct).toBeCloseTo((8000 / 13200) * 100);
        expect(v.forecastPct).toBeCloseTo((12000 / 13200) * 100);
        expect(v.factPct).toBeCloseTo((6000 / 13200) * 100);
    });

    it('факт не вылезает за дорожку', () => {
        expect(barView(bar({ currentFact: 20000, projectionAmount: null })).factPct).toBe(100);
    });

    it('прогноз выключен или не посчитан — иглы нет', () => {
        expect(barView(bar({ forecastEnabled: false })).forecastPct).toBeNull();
        expect(barView(bar({ projectionAmount: null })).forecastPct).toBeNull();
    });

    it('игла ровно на плане не прилипает к краю дорожки', () => {
        expect(barView(bar({ projectionAmount: 8000 })).forecastPct).toBe(98);
    });

    it('ни плана, ни трат — пустая полоса', () => {
        const v = barView(bar({ plannedLimit: 0, currentFact: 0, projectionAmount: null }));
        expect(v.planPct).toBe(100);
        expect(v.factPct).toBe(0);
    });

    it('плана нет, а траты есть — полоса полная, засечка плана у левого края', () => {
        // До ANO-172 шкала строилась только по плану: 1 550 ₽ трат рисовались пустой полосой.
        const v = barView(bar({ plannedLimit: 0, currentFact: 1550, projectionAmount: null }));
        expect(v.factPct).toBe(100);
        expect(v.planPct).toBe(0);
    });
});
