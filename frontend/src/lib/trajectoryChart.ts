import type { PocketResponse } from '../types/api';
import { fmtRub } from './format';

/**
 * Чистые хелперы календаря-близнеца (ANO-14 §3): вся тестируемая логика графика
 * живёт здесь, PocketTrajectoryChart только рендерит результаты.
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
const fmtD = fmtDayMonth;

/** Домен Y всегда включает ноль (спека §3.2): расстояние до дна — главный сигнал. */
export function computeDomain(balances: number[]): Domain {
    const min = Math.min(0, ...balances);
    let max = Math.max(0, ...balances);
    if (max === min) max = min + 1;
    return { min, max };
}

/** Красная зона «разрыв» — только когда траектория реально уходит под ноль. */
export function showDangerZone(domain: Domain): boolean {
    return domain.min < 0;
}

/** Янтарная зона 0..буфер — только при настроенном буфере. */
export function showBufferZone(buffer: number): boolean {
    return buffer > 0;
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

/** Обрезка длинного виновника, чтобы аннотация не вылезала за viewBox. */
const MAX_DRIVEN_BY = 24;
const truncate = (s: string) =>
    s.length > MAX_DRIVEN_BY ? s.slice(0, MAX_DRIVEN_BY - 1).trimEnd() + '…' : s;

/** Аннотация минимума: «мин dd.MM · сумма · виновник», хвост опущен без drivenBy. */
export function buildMinAnnotation(minPoint: PocketResponse['minPoint']): string {
    const base = `мин ${fmtD(minPoint.date)} · ${fmtRub(minPoint.balance)}`;
    return minPoint.drivenBy ? `${base} · ${truncate(minPoint.drivenBy)}` : base;
}

/** Строка деталей выбранного дня: только ненулевые потоки, день 0 — «сегодня». */
export function buildDayDetails(point: TrajPoint, isToday: boolean): string {
    let s = `${isToday ? 'сегодня' : fmtD(point.date)} · остаток ${fmtRub(point.balance)}`;
    if (point.income > 0) s += ` · +${fmtRub(point.income)}`;
    if (point.expense > 0) s += ` · −${fmtRub(point.expense)}`;
    return s;
}
