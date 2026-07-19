import { useMemo } from 'react';
import type { PocketResponse } from '../../types/api';
import { fmtRub } from '../../lib/format';
import {
    buildLinePoints, computeDomain, fmtDayMonth, makeScales, pickTicks,
} from '../../lib/trajectoryChart';

const W = 600;
const H = 240;
const PAD_X = 10;
const TOP = 34;
const FLOOR = 200;
const BASE = 'rgba(143,134,255,0.45)';   // приглушённая «как сейчас»
const FIT = '#8f86ff';                   // яркая «с примеркой»
const AMBER = '#EF9F27';

/**
 * Двухсерийный график примерки (ANO-16 §7): baseline (приглушённая) и fitted (яркая)
 * по дням выбранного скоупа. Отдельный компонент, чтобы Dashboard-близнец
 * (PocketTrajectoryChart) жил независимо; общая математика — в lib/trajectoryChart.
 */
export default function SandboxChart({ baseline, fitted }: {
    baseline: PocketResponse;
    fitted: PocketResponse;
}) {
    const geom = useMemo(() => {
        const baseBal = baseline.trajectory.map(p => p.balance);
        const fitBal = fitted.trajectory.map(p => p.balance);
        const domain = computeDomain([...baseBal, ...fitBal]);
        const n = Math.max(baseBal.length, fitBal.length);
        const { x, y } = makeScales(n, domain, W, PAD_X, TOP, FLOOR);
        return {
            domain, x, y, n,
            baseLine: buildLinePoints(baseBal, W, PAD_X, TOP, FLOOR, domain),
            fitLine: buildLinePoints(fitBal, W, PAD_X, TOP, FLOOR, domain),
        };
    }, [baseline, fitted]);

    if (fitted.trajectory.length === 0) return null;

    const { domain, x, y, n, baseLine, fitLine } = geom;
    const yZero = y(0);
    const ticks = pickTicks(fitted.trajectory, fitted.minPoint.date);
    const baseMinIdx = baseline.trajectory.findIndex(p => p.date === baseline.minPoint.date);
    const fitMinIdx = fitted.trajectory.findIndex(p => p.date === fitted.minPoint.date);

    const diff = fitted.pocket - baseline.pocket;

    return (
        <div className="rounded-2xl p-5"
            style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}>
            <div className="flex justify-between items-baseline mb-2 gap-2">
                <h3 className="font-semibold text-sm" style={{ color: 'var(--color-text-muted)' }}>
                    ПРИМЕРКА
                </h3>
                <span className="text-xs shrink-0" style={{ color: 'var(--color-text-muted)' }}>
                    {fitted.horizon.label}
                </span>
            </div>

            <svg viewBox={`0 0 ${W} ${H}`} style={{ width: '100%', display: 'block' }}
                role="img" aria-label={`Примерка: как сейчас vs с примеркой, ${fitted.horizon.label}`}>
                {/* Зона разрыва (< 0) */}
                {domain.min < 0 && (
                    <rect x={PAD_X} y={yZero} width={W - 2 * PAD_X} height={y(domain.min) - yZero}
                        fill="#ef4444" opacity={0.1} />
                )}
                {/* Линия нуля */}
                <line x1={PAD_X} y1={yZero} x2={W - PAD_X} y2={yZero}
                    stroke="rgba(255,255,255,0.25)" strokeWidth={1} />

                {/* baseline — приглушённая пунктирная */}
                <polyline points={baseLine} fill="none" stroke={BASE} strokeWidth={2}
                    strokeDasharray="5 4" strokeLinejoin="round" />
                {/* fitted — яркая сплошная */}
                <polyline points={fitLine} fill="none" stroke={FIT} strokeWidth={2.5}
                    strokeLinejoin="round" />

                {/* Минимумы обеих серий */}
                {baseMinIdx >= 0 && (
                    <circle cx={x(baseMinIdx)} cy={y(baseline.minPoint.balance)} r={3.5}
                        fill={BASE} stroke="var(--color-surface)" strokeWidth={1.5} />
                )}
                {fitMinIdx >= 0 && (
                    <>
                        <circle cx={x(fitMinIdx)} cy={y(fitted.minPoint.balance)} r={4.5}
                            fill={fitted.minPoint.balance < 0 ? 'var(--color-danger)' : AMBER}
                            stroke="var(--color-surface)" strokeWidth={1.5} />
                        <text x={Math.min(Math.max(x(fitMinIdx), 90), W - 90)} y={TOP - 14}
                            fontSize={11} fontWeight={500}
                            fill={fitted.minPoint.balance < 0 ? 'var(--color-danger)' : AMBER}
                            textAnchor="middle">
                            мин {fmtDayMonth(fitted.minPoint.date)} · {fmtRub(fitted.minPoint.balance)}
                        </text>
                    </>
                )}

                {/* Тики дат */}
                {ticks.map(i => (
                    <text key={`tick-${i}`} x={x(i)} y={H - 8} fontSize={10}
                        fill="var(--color-text-muted)"
                        textAnchor={i === 0 ? 'start' : i === n - 1 ? 'end' : 'middle'}>
                        {i === 0 ? 'сегодня' : fmtDayMonth(fitted.trajectory[i].date)}
                    </text>
                ))}
            </svg>

            {/* Легенда + разница */}
            <div className="flex gap-4 mt-2 flex-wrap items-center"
                style={{ fontSize: 11, color: 'var(--color-text-muted)' }}>
                <span className="flex items-center gap-1.5">
                    <span className="inline-block w-3.5 h-0.5 rounded"
                        style={{ background: BASE, borderTop: `2px dashed ${BASE}` }} />
                    как сейчас
                </span>
                <span className="flex items-center gap-1.5">
                    <span className="inline-block w-3.5 h-0.5 rounded" style={{ background: FIT }} />
                    с примеркой
                </span>
                {diff !== 0 && (
                    <span className="ml-auto font-semibold"
                        style={{ color: diff < 0 ? 'var(--color-danger)' : 'var(--color-success)' }}>
                        {diff < 0 ? '' : '+'}{fmtRub(diff)} к кармашку
                    </span>
                )}
            </div>
        </div>
    );
}
