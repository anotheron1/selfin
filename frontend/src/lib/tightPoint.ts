import type { PocketResponse } from '../types/api';
import { fmtDayShort, fmtRub } from './format';
import { buildWatchdogAlert, withoutSameGap } from './watchdogAlert';

/**
 * Что «Сегодня» показывает от сторожа до 2-го дохода (спека среза 2, пп. 6, 7; доски Zh2-G7a, S2-T3, S2-T2).
 *
 * * Разрыв по плану — песочная плашка `Zh2-G7a`; за краем графика — с кнопкой «Показать до 2-го дохода».
 * * Впритык (по плану хватает, с обычными тратами — нет) в пределах графика — точка на пейзаже, нажатие —
 *   подпись «может не хватить около N». За краем графика рисовать точку негде — плашка того же вида, что разрыв.
 * * Тот же разрыв, о котором уже говорит число, — молчание (`withoutSameGap`).
 */
export type WatchdogView =
    | { kind: 'dot'; date: string; lines: [string, string] }
    | { kind: 'plate'; tone: 'plan' | 'tight'; title: string; body: string; small: string | null; showSecond: boolean }
    | null;

export function watchdogView(watchdog: PocketResponse | null, pocket: PocketResponse): WatchdogView {
    const alert = withoutSameGap(buildWatchdogAlert(watchdog, pocket.horizon.endDate), pocket);
    if (!alert) return null;
    const day = fmtDayShort(alert.date);
    const sum = fmtRub(alert.deficit);
    const { horizon } = pocket;
    const edge = horizon.type === 'NEXT_INCOME' && !horizon.fallback
        ? ` Это после дохода ${fmtDayShort(horizon.endDate)}, за краем графика.`
        : ' Это за краем графика.';
    const beyond = alert.beyondChart ? edge : '';

    if (alert.kind === 'FORECAST') {
        if (!alert.beyondChart) return { kind: 'dot', date: alert.date, lines: [`с обычными тратами, ${day}`, `может не хватить около ${sum}`] };
        return {
            kind: 'plate', tone: 'tight', title: `Впритык — ${day}`,
            body: `По плану хватает. С обычными тратами может не хватить около ${sum}.${beyond}`,
            small: null, showSecond: true,
        };
    }
    return {
        kind: 'plate', tone: 'plan', title: `Кассовый разрыв — ${day}`,
        body: `По плану не хватит ${sum}${alert.drivenBy ? `, в этот день — «${alert.drivenBy}»` : ''}.${beyond}`,
        small: alert.forecastNote ? `С обычными тратами — глубже: ${fmtRub(alert.forecastNote.deficit)}.` : null,
        showSecond: alert.beyondChart,
    };
}
