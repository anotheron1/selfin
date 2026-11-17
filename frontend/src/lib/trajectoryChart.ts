import type { PocketResponse } from '../types/api';

/**
 * Чистые хелперы календаря-близнеца (ANO-14 §3): вся тестируемая логика графика
 * живёт здесь. С 17.11 (срез 2) график — только у примерки (SandboxChart); пейзаж «Сегодня» — lib/landscape.ts.
 */
export type TrajPoint = PocketResponse['trajectory'][number];

export interface Domain {
    min: number;
    max: number;
}

export const fmtDayMonth = (iso: string) => {
    const [, m, d] = iso.split('-');
    return `${d}.${m}`;
};

/** Домен Y всегда включает ноль (спека §3.2): расстояние до дна — главный сигнал. */
export function computeDomain(balances: number[]): Domain {
    const min = Math.min(0, ...balances);
    let max = Math.max(0, ...balances);
    if (max === min) max = min + 1;
    return { min, max };
}

/**
 * Индексы тиков оси X: сегодня, конец горизонта и день минимума — всегда;
 * дни доходов — если не липнут к уже выбранным (минимальная дистанция от длины).
 */
export function pickTicks(trajectory: TrajPoint[], minDate: string, maxTicks = 6): number[] {
    const n = trajectory.length;
    if (n === 0) return [];
    const picked = new Set<number>([0, n - 1]);
    const minIdx = trajectory.findIndex(p => p.date === minDate);
    if (minIdx >= 0) picked.add(minIdx);
    const minGap = Math.max(1, Math.floor(n / maxTicks));
    for (let i = 1; i < n - 1 && picked.size < maxTicks; i++) {
        if (trajectory[i].income <= 0 || picked.has(i)) continue;
        if ([...picked].every(j => Math.abs(j - i) >= minGap)) picked.add(i);
    }
    return [...picked].sort((a, b) => a - b);
}

/**
 * Скейлы координат графика: x по индексу дня, y по остатку (инвертирован).
 * Единственная реализация маппинга — компонент и buildLinePoints используют её же,
 * чтобы тестируемая математика совпадала с рендерящейся.
 */
export function makeScales(
    n: number, domain: Domain, w: number, padX: number, top: number, floor: number,
): { x: (i: number) => number; y: (v: number) => number } {
    const span = domain.max - domain.min || 1;
    return {
        x: (i: number) => (n > 1 ? padX + (i * (w - 2 * padX)) / (n - 1) : w / 2),
        y: (v: number) => floor - ((v - domain.min) / span) * (floor - top),
    };
}

/** Координаты polyline; одна точка рисуется в центре без деления на ноль. */
export function buildLinePoints(
    balances: number[], w: number, padX: number, top: number, floor: number, domain: Domain,
): string {
    const { x, y } = makeScales(balances.length, domain, w, padX, top, floor);
    const round = (v: number) => Math.round(v * 10) / 10;
    return balances.map((b, i) => `${round(x(i))},${round(y(b))}`).join(' ');
}
