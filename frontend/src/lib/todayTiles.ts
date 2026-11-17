import type { PocketResponse } from '../types/api';
import type { WatchdogAlert } from './watchdogAlert';
import { fmtDayShort, fmtRub } from './format';

/**
 * Плитки под числом «Сегодня» (спека среза 2, п. 3; доски Zh2-390, S2-P1, S2-T3).
 * «С обычными тратами» — только когда есть прогноз; сторож по прогнозу красит её песочной и подписывает «впритык».
 * «На картах» — остаток и дата сверки, плитка-кнопка к листу остатка. «Самый низкий» — минимум срока с датой.
 */
export interface TodayTile {
    key: 'usual' | 'cards' | 'low';
    value: string;
    caption: string;
    /** Скрытое начало имени для экранного чтеца; у «на картах» имя — целиком в `cardsTileLabel`. */
    sr: string;
    tight?: boolean;
}

/** «На картах 48 800 ₽, обновлён 12 нояб. Обновить» — точка перед «Обновить» одна. */
export function cardsTileLabel(p: PocketResponse): string {
    const updated = p.checkpointDate ? `, обновлён ${fmtDayShort(p.checkpointDate)}` : '';
    return `На картах ${fmtRub(p.currentBalance)}${updated}`.replace(/\.?$/, '. Обновить');
}

export function buildTodayTiles(p: PocketResponse, alert: WatchdogAlert | null): TodayTile[] {
    const tiles: TodayTile[] = [];
    if (p.pocketWithForecast != null) {
        const tight = alert?.kind === 'FORECAST';
        tiles.push({
            key: 'usual', value: fmtRub(p.pocketWithForecast), caption: tight ? 'впритык' : 'как обычно',
            sr: tight ? 'С обычными тратами, впритык:' : 'С обычными тратами:', tight,
        });
    }
    tiles.push({
        key: 'cards', value: fmtRub(p.currentBalance),
        caption: p.checkpointDate ? fmtDayShort(p.checkpointDate) : 'сверки не было', sr: '',
    });
    tiles.push({ key: 'low', value: fmtRub(p.minPoint.balance), caption: fmtDayShort(p.minPoint.date), sr: 'Самый низкий остаток:' });
    return tiles;
}
