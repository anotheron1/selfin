import type { ReactNode } from 'react';
import { buildLandscape, type LandSize, type LandscapeModel } from '../../lib/landscape';
import { fmtDayShort, fmtRub } from '../../lib/format';
import type { PocketResponse } from '../../types/api';
import { LAND_ART } from './landscapeArt';

const ART_FILL = { far: 'var(--sf-land-far)', mid: 'var(--sf-land-mid)', near: 'var(--sf-land-near)', tree: 'var(--sf-land-tree)' };

/**
 * Пейзаж «Сегодня» и первого входа: склон — остаток по дням (спека среза 2, п. 2; доски Zh2-390, Zh2-1440-7t, Zh2-G7b).
 * Рисует только то, что посчитал `buildLandscape`. Поверх — `overlay`: точка «впритык», флажки первого входа.
 */
export function Landscape({ data, size, selectedDate, onDay, dashedSun = false, overlay }: {
    data: PocketResponse;
    size: LandSize;
    /** Выбранный день — пунктир и кольцо (лист дня открыт). */
    selectedDate?: string | null;
    onDay?: (date: string) => void;
    /** Первый вход до вопроса о доходе: солнце пунктиром, «доход». */
    dashedSun?: boolean;
    overlay?: (m: LandscapeModel) => ReactNode;
}) {
    const m = buildLandscape(data, size);
    const f = m.frame;
    const waterId = `sf-water-${size}`;
    const low = m.low;
    const selected = selectedDate ? m.points.find(p => p.date === selectedDate) : undefined;
    const aria = `Остаток по дням: сегодня ${fmtRub(data.trajectory[0]?.balance ?? 0)}`
        + (low ? `, самый низкий — ${fmtRub(data.minPoint.balance)}, ${fmtDayShort(data.minPoint.date)}` : '')
        + (m.sun && !dashedSun ? `; доход — ${m.sun.caption}` : '');

    return (
        <svg viewBox={`0 0 ${m.w} ${m.h}`} className="block h-auto w-full" role="img" aria-label={aria}>
            <defs>
                <linearGradient id={waterId} x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0" stopColor="var(--sf-water)" stopOpacity=".58" />
                    <stop offset="1" stopColor="var(--sf-water-deep)" stopOpacity=".8" />
                </linearGradient>
            </defs>
            {m.sun && !dashedSun && (
                <g>
                    <circle cx={m.sun.x} cy={m.sun.y} r={m.sun.r[0]} fill="var(--sf-orb)" fillOpacity=".12" />
                    <circle cx={m.sun.x} cy={m.sun.y} r={m.sun.r[1]} fill="var(--sf-orb)" fillOpacity=".2" />
                    <circle cx={m.sun.x} cy={m.sun.y} r={m.sun.r[2]} fill="var(--sf-orb)" />
                </g>
            )}
            {dashedSun && (
                <g>
                    <circle cx={f.x1 - f.sunInset} cy={f.sunY} r={f.sunR[2]} fill="none" stroke="#C9A06B"
                        strokeWidth="1.5" strokeDasharray="3 5" />
                    <text x={f.x1 - f.sunInset} y={f.sunY + 4} fontSize="11" textAnchor="middle" fill="var(--sf-kind-ozh)">доход</text>
                </g>
            )}
            {LAND_ART[size].map((s, i) => s.tag === 'polygon'
                ? <polygon key={i} points={s.v} fill={ART_FILL[s.layer]} />
                : <path key={i} d={s.v} fill={ART_FILL[s.layer]} />)}
            <path d={m.slopePath} fill="var(--sf-slope)" />
            {m.usualPath && <path d={m.usualPath} fill="var(--sf-usual)" />}
            {m.mistY !== null && m.mistY < m.zeroY && (
                <rect x="0" y={m.mistY} width={m.w} height={m.zeroY - m.mistY} fill="var(--sf-mist)" data-layer="mist" />
            )}
            {m.water && <rect x="0" y={m.zeroY} width={m.w} height={m.h - m.zeroY} fill={`url(#${waterId})`} data-layer="water" />}
            {m.hazeX !== null && (
                <rect x={m.hazeX} y="0" width={m.w - m.hazeX} height={m.h} fill="var(--sf-sky-bottom)" fillOpacity=".55" data-layer="haze" />
            )}
            {m.sun && !dashedSun && (
                <text x={m.sun.x} y={m.sun.captionY} fontSize={size === 'wide' ? 13 : 11} fontWeight="600"
                    textAnchor="middle" fill="var(--sf-ink)">{m.sun.caption}</text>
            )}
            {m.smallSuns.map((s, i) => <circle key={i} cx={s.x} cy={s.y} r={s.r} fill="var(--sf-orb)" data-layer="small-sun" />)}
            {m.labels.map(l => (
                <text key={l.text + l.x} x={l.x} y={f.labelY} fontSize={size === 'wide' ? 12 : 11} fontWeight="600"
                    textAnchor={l.anchor} fill="#FFFFFF">{l.text}</text>
            ))}
            {selected && (
                <g>
                    <line x1={selected.x} y1={f.topY - 70} x2={selected.x} y2={selected.y} stroke="var(--sf-ink)"
                        strokeWidth="1.2" strokeDasharray="3 3" />
                    <circle cx={selected.x} cy={selected.y} r={f.lowR} fill="var(--sf-page)" stroke="var(--sf-ink)" strokeWidth="2" />
                </g>
            )}
            <circle cx={m.today.x} cy={m.today.y} r={m.today.r} fill="var(--sf-dot)" stroke="var(--sf-dot-rim)" strokeWidth="2" />
            {low && <circle cx={low.x} cy={low.y} r={low.r} fill="var(--sf-ring)" stroke="var(--sf-ring-rim)" strokeWidth="2" data-layer="low" />}
            {onDay && m.points.map((p, i) => {
                const prev = m.points[i - 1]?.x ?? p.x;
                const next = m.points[i + 1]?.x ?? p.x;
                const left = (prev + p.x) / 2;
                const right = (p.x + next) / 2;
                return (
                    <rect key={p.date} x={left} y="0" width={Math.max(right - left, 1)} height={m.h} fill="transparent"
                        role="button" tabIndex={-1} aria-label={fmtDayShort(p.date)} onClick={() => onDay(p.date)}
                        style={{ cursor: 'pointer' }} />
                );
            })}
            {/* Поверх полос нажатия по дням: точка «впритык» и флажки нажимаются сами. */}
            {overlay?.(m)}
        </svg>
    );
}

/**
 * Платежи первого входа на обрывах (решение 48, S2-W2d, S2-W-1440): флажок на краю обрыва, на флажке — только сумма
 * расходов дня. Имени нет: «подписи плашками — как грязь». Минус — дефис, как во всём приложении.
 */
export function PayFlags({ m, data }: { m: LandscapeModel; data: PocketResponse }) {
    const [pole, fw, fh, fs] = m.w > 390 ? [34, 15, 10, 13] : [30, 13, 9, 12];
    return (
        <g data-layer="flags" pointerEvents="none">
            {data.trajectory.map((t, i) => {
                if (t.expense <= 0) return null;
                const x = m.points[i].x;
                const top = m.yOf(t.balance + t.expense) - pole;
                return (
                    <g key={t.date} data-flag={t.date}>
                        <path d={`M${x},${top + pole} L${x},${top}`} stroke="var(--sf-ink)" strokeWidth="1.3" />
                        <path d={`M${x},${top} L${x + fw},${top + fh / 2} L${x},${top + fh} Z`} fill="var(--sf-accent)" />
                        <text x={x + fw + 4} y={top + fh / 2 + 4} fontSize={fs} fontWeight="600" fill="var(--sf-ink)">
                            {`-${Math.round(t.expense).toLocaleString('ru-RU')}`}
                        </text>
                    </g>
                );
            })}
        </g>
    );
}

/**
 * Точка «впритык» у воды (S2-T3): по умолчанию — только точка на тёмном слое; нажатие показывает и прячет подпись
 * «может не хватить около N» (S2-T2). Дня нет на графике — рисовать нечего.
 */
export function TightDot({ m, date, lines, open, onToggle }: {
    m: LandscapeModel;
    date: string;
    lines: [string, string];
    open: boolean;
    onToggle: () => void;
}) {
    const p = m.points.find(x => x.date === date);
    if (!p) return null;
    const y = p.yUsual ?? p.y;
    const wide = m.w > 390;
    const bw = wide ? 260 : 236;
    const bh = wide ? 48 : 44;
    const bx = Math.min(Math.max(p.x + 12 - bw, 8), m.w - bw - 8);
    const by = 20;
    return (
        <g role="button" tabIndex={0} aria-expanded={open} aria-label={`Впритык: ${lines[1]}, ${lines[0]}`}
            onClick={onToggle} onKeyDown={e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onToggle(); } }}
            style={{ cursor: 'pointer' }} data-layer="tight">
            <circle cx={p.x} cy={y} r="14" fill="transparent" />
            {open ? <>
                <path d={`M${p.x},${y - 4} L${p.x},${by + bh}`} stroke="var(--sf-ink)" strokeWidth="1" strokeDasharray="2 3" />
                <circle cx={p.x} cy={y} r="3.5" fill="var(--sf-ink)" />
                <rect x={bx} y={by} width={bw} height={bh} rx="12" fill="var(--sf-page)" fillOpacity=".94" />
                <text x={bx + 14} y={by + 18} fontSize="11" fill="var(--sf-muted)">{lines[0]}</text>
                <text x={bx + 14} y={by + 35} fontSize="13" fontWeight="600" fill="var(--sf-ink)">{lines[1]}</text>
            </> : <circle cx={p.x} cy={y} r="5.5" fill="var(--sf-plate)" stroke="var(--sf-ink)" strokeWidth="2" />}
        </g>
    );
}
