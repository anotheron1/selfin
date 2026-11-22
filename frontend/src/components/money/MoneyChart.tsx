import { useState } from 'react';
import { Area, AreaChart, ResponsiveContainer, Tooltip, XAxis } from 'recharts';
import { Choices } from '@/ds';
import type { CapitalTrajectory } from '../../types/api';
import { RANGES, defaultRange, rangeChange, rangePoints, type Range } from '../../lib/moneyGroups';
import { fmtAmount, fmtDayShort, fmtSigned } from '../../lib/format';

/**
 * «Капитал по месяцам» (срез 4, п. 8; S4-W1). Точки — вся история одним запросом, период режется здесь: по умолчанию
 * «Всё», пока первой точке меньше года, потом «Год»; изменение у графика — последняя точка минус первая.
 */
type Point = CapitalTrajectory['points'][number];

const month = (iso: string) => {
    const [y, m] = iso.split('-').map(Number);
    return new Date(y, m - 1, 1).toLocaleDateString('ru-RU', { month: 'short' });
};

export default function MoneyChart({ points, today }: { points: Point[]; today: string }) {
    const [range, setRange] = useState<Range>(() => defaultRange(points[0]?.date ?? null, today));
    const shown = rangePoints(points, range, today);
    const change = rangeChange(shown);

    return (
        <section className="flex flex-col gap-3">
            <div className="flex items-baseline justify-between gap-2.5">
                <h2 className="text-sf-body font-semibold">Капитал по месяцам</h2>
                {change && <span className="whitespace-nowrap text-sf-note">
                    <span className="font-medium">{fmtSigned(change.amount)}</span>{` с ${fmtDayShort(change.from)}`}
                </span>}
            </div>
            {shown.length < 2 ? (
                <p className="py-6 text-sf-note text-sf-muted">
                    История появится по мере оценок и обновлений остатка. Хочешь увидеть прошлое — добавь оценку задним числом в листе любой вещи.
                </p>
            ) : (
                <div className="h-[150px]" role="img"
                    aria-label={`Капитал по месяцам: с ${fmtAmount(shown[0].capital)} до ${fmtAmount(shown[shown.length - 1].capital)}`}>
                    <ResponsiveContainer width="100%" height="100%">
                        <AreaChart data={shown} margin={{ top: 8, right: 6, bottom: 0, left: 6 }}>
                            <XAxis dataKey="date" tickFormatter={month} tickLine={false} axisLine={false} interval="preserveStartEnd"
                                tick={{ fontSize: 11, fill: 'var(--sf-muted)' }} minTickGap={16} />
                            <Tooltip content={<PointTip />} />
                            <Area type="linear" dataKey="capital" stroke="var(--sf-accent)" strokeWidth={2}
                                fill="var(--sf-accent-soft)" fillOpacity={0.6} isAnimationActive={false}
                                dot={false} activeDot={{ r: 4 }} />
                        </AreaChart>
                    </ResponsiveContainer>
                </div>
            )}
            <Choices name="money-range" label="Период графика" look="chip" size="control" options={RANGES} value={range}
                onChange={v => setRange(v as Range)} />
            <p className="text-sf-label leading-[1.4] text-sf-muted">
                Изменение у графика — за выбранный период. Истории меньше года — по умолчанию «Всё».
            </p>
        </section>
    );
}

/** Точка под пальцем: дата, капитал и из чего он — деньги плюс имущество минус долги. */
function PointTip({ active, payload }: { active?: boolean; payload?: { payload: Point }[] }) {
    if (!active || !payload?.length) return null;
    const p = payload[0].payload;
    return (
        <div className="rounded-sf-field bg-sf-plate px-3 py-2 font-sf text-sf-label text-sf-ink shadow-sf-raised">
            <div className="font-medium">{fmtDayShort(p.date)} · {fmtAmount(p.capital)}</div>
            <div className="text-sf-muted">деньги {fmtAmount(p.liquid)} + имущество {fmtAmount(p.assets)} − долги {fmtAmount(p.liabilities)}</div>
        </div>
    );
}
