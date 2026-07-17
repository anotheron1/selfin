import { useEffect, useMemo, useState } from 'react';
import type { PocketResponse } from '../../types/api';
import {
    buildDayDetails,
    buildLinePoints,
    buildMinAnnotation,
    computeDomain,
    fmtDayMonth,
    makeScales,
    pickTicks,
    showBufferZone,
    showDangerZone,
} from '../../lib/trajectoryChart';

const W = 600;
const H = 230;
const PAD_X = 10;
const TOP = 30;
const FLOOR = 196;
const LINE = '#8f86ff';
const AMBER = '#EF9F27';
const AMBER_LIGHT = '#FAC775';

/**
 * Календарь-близнец кармашка (ANO-14 §3): траектория из GET /pocket, нарисованная
 * по дням. Чистое представление — не фетчит, скоуп и рефреш наследует от PocketCard
 * через лифт PocketResponse на странице. Вся расчётная логика — в lib/trajectoryChart.
 */
export default function PocketTrajectoryChart({ data }: { data: PocketResponse }) {
    const [selected, setSelected] = useState<number | null>(null);
    const { trajectory, minPoint, buffer, horizon } = data;

    const geom = useMemo(() => {
        const balances = trajectory.map(p => p.balance);
        const domain = computeDomain(balances);
        const n = trajectory.length;
        const { x, y } = makeScales(n, domain, W, PAD_X, TOP, FLOOR);
        const line = buildLinePoints(balances, W, PAD_X, TOP, FLOOR, domain);
        return { domain, x, y, line, n };
    }, [trajectory]);

    // Смена горизонта (скоуп/рефреш) делает старый индекс дня бессмысленным — сбрасываем.
    useEffect(() => {
        setSelected(null);
    }, [data.horizon.endDate, trajectory.length]);

    if (trajectory.length === 0) return null;

    const { domain, x, y, line, n } = geom;
    const yZero = y(0);
    const yBuffer = y(Math.min(buffer, domain.max));
    // Информационный хвост (§3.9): дни за горизонтом рисуются приглушённо
    const horizonIdx = trajectory.findIndex(p => p.date === horizon.endDate);
    const hasTail = horizonIdx >= 0 && horizonIdx < n - 1;
    const pt = (i: number) => `${x(i)},${y(trajectory[i].balance)}`;
    const mainLine = hasTail
        ? trajectory.slice(0, horizonIdx + 1).map((_, i) => pt(i)).join(' ')
        : line;
    const tailLine = hasTail
        ? trajectory.slice(horizonIdx).map((_, i) => pt(horizonIdx + i)).join(' ')
        : null;
    // Пунктир буфера рисуем только когда его уровень реально внутри домена —
    // иначе линия на потолке графика врала бы о величине буфера (зона-подложка остаётся).
    const showBufferLine = showBufferZone(buffer) && buffer <= domain.max;
    const minIdx = trajectory.findIndex(p => p.date === minPoint.date);
    const ticks = pickTicks(trajectory, minPoint.date);
    const minX = minIdx >= 0 ? x(minIdx) : null;
    const annotationX = minX != null ? Math.min(Math.max(minX, 110), W - 110) : null;
    const step = n > 1 ? (W - 2 * PAD_X) / (n - 1) : W;

    return (
        <div className="rounded-2xl p-5"
            style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}>
            <div className="flex justify-between items-baseline mb-2 gap-2">
                <h3 className="font-semibold text-sm" style={{ color: 'var(--color-text-muted)' }}>
                    КАССОВЫЙ КАЛЕНДАРЬ
                </h3>
                <span className="text-xs shrink-0" style={{ color: 'var(--color-text-muted)' }}>
                    {horizon.label}
                </span>
            </div>

            <svg viewBox={`0 0 ${W} ${H}`} style={{ width: '100%', display: 'block' }}
                role="img" aria-label={`Траектория остатка, ${horizon.label}`}>
                {/* Зона ниже буфера (0..buffer) */}
                {showBufferZone(buffer) && yZero > yBuffer && (
                    <rect x={PAD_X} y={yBuffer} width={W - 2 * PAD_X} height={yZero - yBuffer}
                        fill={AMBER} opacity={0.08} />
                )}
                {/* Зона разрыва (< 0) */}
                {showDangerZone(domain) && (
                    <rect x={PAD_X} y={yZero} width={W - 2 * PAD_X} height={y(domain.min) - yZero}
                        fill="#ef4444" opacity={0.12} />
                )}
                {/* Линия нуля */}
                <line x1={PAD_X} y1={yZero} x2={W - PAD_X} y2={yZero}
                    stroke="rgba(255,255,255,0.25)" strokeWidth={1} />
                {/* Линия буфера */}
                {showBufferLine && (
                    <>
                        <line x1={PAD_X} y1={yBuffer} x2={W - PAD_X} y2={yBuffer}
                            stroke={AMBER} strokeWidth={1} strokeDasharray="4 4" opacity={0.7} />
                        <text x={W - PAD_X} y={yBuffer - 4} fontSize={10} fill={AMBER_LIGHT}
                            textAnchor="end">буфер</text>
                    </>
                )}
                {/* Заливка между траекторией и нулём */}
                <polygon points={`${x(0)},${yZero} ${line} ${x(n - 1)},${yZero}`}
                    fill="#6c63ff" opacity={0.14} />
                {/* Траектория: внутри горизонта — плотно, хвост за горизонтом — приглушённо */}
                <polyline points={mainLine} fill="none" stroke={LINE} strokeWidth={2}
                    strokeLinejoin="round" />
                {tailLine && (
                    <>
                        <polyline points={tailLine} fill="none" stroke={LINE} strokeWidth={2}
                            strokeLinejoin="round" opacity={0.4} />
                        <line x1={x(horizonIdx)} y1={TOP} x2={x(horizonIdx)} y2={FLOOR}
                            stroke="rgba(255,255,255,0.25)" strokeWidth={1} strokeDasharray="2 4" />
                    </>
                )}
                {/* Точки дней — только на коротких горизонтах */}
                {n <= 31 && trajectory.map((p, i) => (
                    <circle key={p.date} cx={x(i)} cy={y(p.balance)} r={2} fill={LINE}
                        opacity={hasTail && i > horizonIdx ? 0.4 : 1} />
                ))}
                {/* Дни доходов */}
                {trajectory.map((p, i) => p.income > 0 && (
                    <circle key={`inc-${p.date}`} cx={x(i)} cy={y(p.balance)} r={3.5}
                        fill="var(--color-success)" stroke="var(--color-surface)" strokeWidth={1.5} />
                ))}
                {/* Минимум + аннотация */}
                {minIdx >= 0 && minX != null && annotationX != null && (
                    <>
                        <line x1={minX} y1={TOP - 6} x2={minX} y2={y(minPoint.balance) - 7}
                            stroke={AMBER} strokeWidth={1} strokeDasharray="2 3" opacity={0.6} />
                        <circle cx={minX} cy={y(minPoint.balance)} r={4.5}
                            fill={minPoint.balance < 0 ? 'var(--color-danger)' : AMBER}
                            stroke="var(--color-surface)" strokeWidth={1.5} />
                        <text x={annotationX} y={TOP - 12} fontSize={11} fontWeight={500}
                            fill={minPoint.balance < 0 ? 'var(--color-danger)' : AMBER_LIGHT}
                            textAnchor="middle">
                            {buildMinAnnotation(minPoint)}
                        </text>
                    </>
                )}
                {/* Сегодня */}
                <circle cx={x(0)} cy={y(trajectory[0].balance)} r={3}
                    fill="var(--color-text)" stroke="var(--color-surface)" strokeWidth={1} />
                {/* Выбранный день */}
                {selected != null && (
                    <line x1={x(selected)} y1={TOP} x2={x(selected)} y2={FLOOR}
                        stroke="rgba(255,255,255,0.35)" strokeWidth={1} strokeDasharray="3 3" />
                )}
                {/* Тики дат */}
                {ticks.map(i => (
                    <text key={`tick-${i}`} x={x(i)} y={H - 8} fontSize={10}
                        fill={i === minIdx ? AMBER_LIGHT : 'var(--color-text-muted)'}
                        textAnchor={i === 0 ? 'start' : i === n - 1 ? 'end' : 'middle'}>
                        {i === 0 ? 'сегодня' : fmtDayMonth(trajectory[i].date)}
                    </text>
                ))}
                {/* Хит-зоны тапа по дню */}
                {trajectory.map((p, i) => (
                    <rect key={`hit-${p.date}`} x={x(i) - step / 2} y={TOP}
                        width={step} height={FLOOR - TOP} fill="transparent"
                        style={{ cursor: 'pointer' }}
                        onClick={() => setSelected(s => (s === i ? null : i))} />
                ))}
            </svg>

            {/* Детали выбранного дня */}
            {selected != null && trajectory[selected] && (
                <div className="rounded-lg px-3 py-2 mt-2 text-sm"
                    style={{ border: '1px solid var(--color-border)', color: 'var(--color-text)' }}>
                    {buildDayDetails(trajectory[selected], selected === 0)}
                </div>
            )}

            {/* Легенда цветовых зон */}
            <div className="flex gap-4 mt-2 flex-wrap" style={{ fontSize: 11, color: 'var(--color-text-muted)' }}>
                <span className="flex items-center gap-1.5">
                    <span className="inline-block w-3.5 h-0.5 rounded" style={{ background: LINE }} />
                    остаток
                </span>
                {showBufferZone(buffer) && (
                    <span className="flex items-center gap-1.5">
                        <span className="inline-block w-2 h-2 rounded-sm" style={{ background: AMBER, opacity: 0.6 }} />
                        ниже буфера
                    </span>
                )}
                <span className="flex items-center gap-1.5">
                    <span className="inline-block w-2 h-2 rounded-sm" style={{ background: '#ef4444', opacity: 0.6 }} />
                    разрыв (&lt;0)
                </span>
                <span className="flex items-center gap-1.5">
                    <span className="inline-block w-2 h-2 rounded-full" style={{ background: 'var(--color-success)' }} />
                    доход
                </span>
                {hasTail && (
                    <span className="flex items-center gap-1.5">
                        <span className="inline-block w-3.5 h-0.5 rounded" style={{ background: LINE, opacity: 0.4 }} />
                        за горизонтом
                    </span>
                )}
                <span className="ml-auto">тап по дню — детали</span>
            </div>
        </div>
    );
}
