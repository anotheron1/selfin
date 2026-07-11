import type { PocketResponse } from '../types/api';

/**
 * Алерт кассового разрыва (ANO-14 §5): питается minPoint СТОРОЖЕВОГО скоупа
 * SECOND_INCOME — защита «между авансом и зп» не зависит от того, куда пользователь
 * переключил PocketCard. Это второе представление того же PocketEngine, не расчёт.
 */
export interface WatchdogAlert {
    date: string;
    deficit: number;
    drivenBy: string | null;
    /** Разрыв за концом горизонта, который сейчас показывает график. */
    beyondChart: boolean;
}

export function buildWatchdogAlert(
    watchdog: PocketResponse | null,
    userHorizonEnd: string | null,
): WatchdogAlert | null {
    if (!watchdog || watchdog.minPoint.balance >= 0) return null;
    return {
        date: watchdog.minPoint.date,
        deficit: Math.abs(watchdog.minPoint.balance),
        drivenBy: watchdog.minPoint.drivenBy,
        beyondChart: userHorizonEnd != null && watchdog.minPoint.date > userHorizonEnd,
    };
}
