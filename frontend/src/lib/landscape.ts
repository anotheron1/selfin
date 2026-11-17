/**
 * Пейзаж «Сегодня» (лицо «Ж2», доски Zh2-390, Zh2-1440-7t, Zh2-G7b) — геометрия из ряда остатков `/pocket`.
 *
 * Склон — остаток по дням на шкале от нуля; тёмный слой — тот же остаток с обычными тратами; ниже нуля — вода,
 * ниже НЗ — туман, после конца срока — дымка. Солнце — доход, которым кончается срок, с его именем (ANO-96);
 * доходы раньше — маленькие солнца без подписи. Дни срока ложатся от левого поля до правого; день дохода
 * и информационный хвост (§3.9) — за правым полем, под дымкой.
 *
 * Чистые функции: компонент только рисует то, что здесь посчитано. Спека — 2026-11-16-slice2-first-entry-today-design.md, п. 2.
 */
import { fmtDayShort } from './format';
import type { PocketResponse } from '../types/api';

export type LandSize = 'narrow' | 'wide';

export interface LandFrame {
    w: number; h: number;
    /** Поля: «сегодня» — на x0, последний день срока — на x1. */
    x0: number; x1: number;
    /** Верх самого большого остатка, ноль без воды, нижняя видимая кромка (ниже — лист). */
    topY: number; zeroBase: number; floorY: number;
    labelY: number; maxLabels: number;
    sunY: number; sunR: [number, number, number]; sunInset: number; smallSunR: number;
    dotR: number; lowR: number;
}

/** Размеры — с досок: 390 × 270 (Zh2-390) и 1440 × 300 (Zh2-1440-7t). */
export const LAND_FRAMES: Record<LandSize, LandFrame> = {
    narrow: {
        w: 390, h: 270, x0: 20, x1: 370, topY: 112, zeroBase: 222, floorY: 240, labelY: 234, maxLabels: 3,
        sunY: 76, sunR: [60, 42, 25], sunInset: 48, smallSunR: 7, dotR: 4, lowR: 5,
    },
    wide: {
        w: 1440, h: 300, x0: 130, x1: 1310, topY: 120, zeroBase: 250, floorY: 268, labelY: 258, maxLabels: 7,
        sunY: 86, sunR: [76, 53, 31], sunInset: 72, smallSunR: 10, dotR: 4.5, lowR: 6,
    },
};

export interface LandPoint { date: string; x: number; y: number; yUsual: number | null }

export interface LandscapeModel {
    frame: LandFrame;
    w: number; h: number;
    zeroY: number;
    yOf: (balance: number) => number;
    points: LandPoint[];
    slopePath: string;
    usualPath: string | null;
    /** Верх тумана ниже НЗ; null — НЗ нет. Туман — от него до нуля. */
    mistY: number | null;
    water: boolean;
    /** Начало дымки после срока; null — хвоста нет. */
    hazeX: number | null;
    sun: { x: number; y: number; r: [number, number, number]; caption: string; captionY: number } | null;
    smallSuns: { x: number; y: number; r: number }[];
    today: { x: number; y: number; r: number };
    low: { x: number; y: number; r: number } | null;
    labels: { x: number; text: string; anchor: 'start' | 'middle' | 'end' }[];
}

const r1 = (n: number) => Math.round(n * 10) / 10;

/**
 * Монотонная кубическая кривая (Фриче — Карлсон) через точки, начиная с первой: «L x,y C …».
 * Обрыв на крупный платёж не даёт горбов выше и ниже соседних дней — склон не врёт об остатке.
 */
export function monotoneCurve(pts: [number, number][]): string {
    if (pts.length === 0) return '';
    const n = pts.length;
    let d = `L${r1(pts[0][0])},${r1(pts[0][1])}`;
    if (n === 1) return d;
    const h: number[] = [];
    const m: number[] = [];
    for (let i = 0; i < n - 1; i++) {
        h.push(pts[i + 1][0] - pts[i][0]);
        m.push(h[i] === 0 ? 0 : (pts[i + 1][1] - pts[i][1]) / h[i]);
    }
    const t: number[] = new Array(n);
    t[0] = m[0];
    t[n - 1] = m[n - 2];
    for (let i = 1; i < n - 1; i++) {
        const s0 = m[i - 1], s1 = m[i];
        if (s0 * s1 <= 0) { t[i] = 0; continue; }
        const p = (s0 * h[i] + s1 * h[i - 1]) / (h[i - 1] + h[i]);
        t[i] = (Math.sign(s0) + Math.sign(s1)) * Math.min(Math.abs(s0), Math.abs(s1), 0.5 * Math.abs(p));
    }
    // Концы: касательная не круче, чем позволяет монотонность отрезка.
    if (Math.sign(t[0]) !== Math.sign(m[0])) t[0] = 0;
    if (Math.sign(t[n - 1]) !== Math.sign(m[n - 2])) t[n - 1] = 0;
    for (let i = 0; i < n - 1; i++) {
        const [x0, y0] = pts[i];
        const [x1, y1] = pts[i + 1];
        const k = h[i] / 3;
        d += ` C${r1(x0 + k)},${r1(y0 + t[i] * k)} ${r1(x1 - k)},${r1(y1 - t[i + 1] * k)} ${r1(x1)},${r1(y1)}`;
    }
    return d;
}

function area(frame: LandFrame, pts: [number, number][]): string {
    const first = pts[0];
    const last = pts[pts.length - 1];
    return `M0,${frame.h} L0,${r1(first[1])} ${monotoneCurve(pts)} L${frame.w},${r1(last[1])} L${frame.w},${frame.h} Z`;
}

const incomeName = (p: PocketResponse['trajectory'][number]): string | null => {
    const inc = p.items.find(i => i.type === 'INCOME');
    if (!inc) return null;
    return inc.description?.trim() || inc.categoryName || null;
};

export function buildLandscape(p: PocketResponse, size: LandSize): LandscapeModel {
    const f = LAND_FRAMES[size];
    const traj = p.trajectory;

    // Шкала от нуля: одна на оба направления, чтобы глубина воды была честной.
    const values = [0, ...traj.map(t => t.balance), ...traj.map(t => t.balanceWithForecast ?? t.balance)];
    if (p.buffer > 0) values.push(p.buffer);
    const max = Math.max(...values, 1);
    const min = Math.min(...values);
    let k = (f.zeroBase - f.topY) / max;
    let zeroY = f.zeroBase;
    if (min < 0 && f.zeroBase - min * k > f.floorY) {
        k = (f.floorY - f.topY) / (max - min);
        zeroY = f.topY + max * k;
    }
    const yOf = (b: number) => zeroY - b * k;

    // Дни срока — от x0 до x1; день дохода, которым кончается срок, и хвост — за x1.
    const endIdx = Math.max(0, traj.findIndex(t => t.date === p.horizon.endDate));
    const endsWithIncome = traj[endIdx] && traj[endIdx].income > 0 && endIdx > 0;
    const lastTerm = endsWithIncome ? endIdx - 1 : (traj.findIndex(t => t.date === p.horizon.endDate) >= 0 ? endIdx : traj.length - 1);
    const tail = traj.length - 1 - lastTerm;
    const xOf = (i: number) => {
        if (i <= lastTerm) return lastTerm === 0 ? f.x0 : r1(f.x0 + (f.x1 - f.x0) * i / lastTerm);
        return r1(f.x1 + (f.w - f.x1) * (i - lastTerm) / tail);
    };

    const points: LandPoint[] = traj.map((t, i) => ({
        date: t.date, x: xOf(i), y: yOf(t.balance),
        yUsual: t.balanceWithForecast == null ? null : yOf(t.balanceWithForecast),
    }));
    const hasUsual = traj.some(t => t.balanceWithForecast != null);

    const sunPoint = endsWithIncome ? traj[endIdx] : null;
    const sunName = sunPoint ? incomeName(sunPoint) : null;
    const sun = sunPoint ? {
        x: f.x1 - f.sunInset, y: f.sunY, r: f.sunR,
        caption: sunName ? `${sunName} · ${fmtDayShort(sunPoint.date)}` : fmtDayShort(sunPoint.date),
        captionY: f.sunY + f.sunR[1] + 4,
    } : null;
    const smallSuns = traj.flatMap((t, i) => (t.income > 0 && i !== endIdx && i > 0)
        ? [{ x: points[i].x, y: r1(points[i].y - f.smallSunR - 8), r: f.smallSunR }] : []);

    const lowIdx = traj.findIndex(t => t.date === p.minPoint.date);
    const low = lowIdx >= 0 ? { x: points[lowIdx].x, y: yOf(p.minPoint.balance), r: f.lowR } : null;

    return {
        frame: f, w: f.w, h: f.h, zeroY, yOf, points,
        slopePath: area(f, points.map(pt => [pt.x, pt.y])),
        usualPath: hasUsual ? area(f, points.map(pt => [pt.x, pt.yUsual ?? pt.y])) : null,
        mistY: p.buffer > 0 ? yOf(p.buffer) : null,
        water: min < 0,
        hazeX: tail > 0 ? f.x1 : null,
        sun, smallSuns,
        today: { x: points[0]?.x ?? f.x0, y: points[0]?.y ?? zeroY, r: f.dotR },
        low,
        labels: dayLabels(traj.slice(0, lastTerm + 1).map(t => t.date), xOf, f.maxLabels),
    };
}

/** «сегодня» слева, последний день срока справа, между ними — ровным шагом, сколько влезает. */
function dayLabels(dates: string[], xOf: (i: number) => number, maxLabels: number): LandscapeModel['labels'] {
    const last = dates.length - 1;
    if (last < 0) return [];
    if (last === 0) return [{ x: xOf(0), text: 'сегодня', anchor: 'start' }];
    const step = Math.max(1, Math.ceil(last / (maxLabels - 1)));
    const idx: number[] = [];
    for (let i = 0; i < last; i += step) idx.push(i);
    if (idx.length > 1 && last - idx[idx.length - 1] < step / 2) idx.pop();
    idx.push(last);
    return idx.map(i => ({
        x: xOf(i),
        text: i === 0 ? 'сегодня' : fmtDayShort(dates[i]),
        anchor: i === 0 ? 'start' : i === last ? 'end' : 'middle',
    }));
}
