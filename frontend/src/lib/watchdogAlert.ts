import type { PocketResponse } from '../types/api';

/**
 * Алерт кассового разрыва (ANO-14 §5): питается minPoint СТОРОЖЕВОГО скоупа
 * SECOND_INCOME — защита «между авансом и зп» не зависит от того, куда пользователь
 * переключил PocketCard. Это второе представление того же PocketEngine, не расчёт.
 */

/**
 * ANO-80: род предупреждения.
 *
 * 'PLAN' — разрыв держится на собственных планах человека: это требует действия.
 * 'FORECAST' — по планам хватает, но так обычно не выходит: это повод для внимания.
 *
 * Разница между «это случится по твоим же планам» и «так обычно бывает» — разница
 * в том, что человек должен сделать, а не оттенок формулировки. Разными плашками она
 * видна без чтения; одной плашкой с двумя числами — только после сравнения чисел,
 * то есть после арифметики (правило 8).
 */
export type WatchdogKind = 'PLAN' | 'FORECAST';

export interface WatchdogAlert {
    kind: WatchdogKind;
    date: string;
    deficit: number;
    drivenBy: string | null;
    /** Разрыв за концом горизонта, который сейчас показывает график. */
    beyondChart: boolean;
    /**
     * Только для kind==='PLAN': с обычными тратами разрыв глубже — дата и сумма.
     * null, когда прогноза нет или он ничего не добавляет к уже названному разрыву.
     */
    forecastNote: { date: string; deficit: number } | null;
}

export function buildWatchdogAlert(
    watchdog: PocketResponse | null,
    userHorizonEnd: string | null,
): WatchdogAlert | null {
    if (!watchdog) return null;

    const hard = watchdog.minPoint;
    const soft = watchdog.minPointWithForecast;
    const beyond = (date: string) => userHorizonEnd != null && date > userHorizonEnd;

    if (hard.balance < 0) {
        return {
            kind: 'PLAN',
            date: hard.date,
            deficit: Math.abs(hard.balance),
            drivenBy: hard.drivenBy,
            beyondChart: beyond(hard.date),
            // Оговорка только когда с обычными тратами ДЕЙСТВИТЕЛЬНО глубже: повторять
            // то же число другими словами — шум, а не предупреждение.
            forecastNote: soft != null && soft.balance < hard.balance
                ? { date: soft.date, deficit: Math.abs(soft.balance) }
                : null,
        };
    }

    if (soft != null && soft.balance < 0) {
        return {
            kind: 'FORECAST',
            date: soft.date,
            deficit: Math.abs(soft.balance),
            // Размазка прогноза виновника не имеет: называть его было бы выдумкой.
            drivenBy: null,
            beyondChart: beyond(soft.date),
            forecastNote: null,
        };
    }

    return null;
}

/**
 * Красная карточка молчит, когда говорит о том же разрыве, что и карточка кармашка в режиме
 * нехватки (ANO-100): та уже называет его датой и суммой, и второе сообщение под ней
 * повторяло бы то же число. Разрыв с другой датой — новые сведения, карточка остаётся.
 * Предупреждение «с обычными тратами» кармашек не показывает — его не прячем.
 */
export function withoutSameGap(
    alert: WatchdogAlert | null,
    pocket: PocketResponse | null,
): WatchdogAlert | null {
    if (!alert || !pocket) return alert;
    const sameGap = alert.kind === 'PLAN'
        && pocket.minPoint.balance < 0
        && alert.date === pocket.minPoint.date;
    return sameGap ? null : alert;
}
