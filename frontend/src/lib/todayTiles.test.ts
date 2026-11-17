import { describe, expect, it } from 'vitest';
import { buildTodayTiles, cardsTileLabel } from './todayTiles';
import type { PocketResponse } from '../types/api';
import type { WatchdogAlert } from './watchdogAlert';

const pocket = (over: Partial<PocketResponse> = {}): PocketResponse => ({
    pocket: 17_000, currentBalance: 48_800, buffer: 0, checkpointDate: '2026-11-12',
    minPoint: { date: '2026-11-24', balance: 17_000, drivenBy: null },
    pocketWithForecast: 15_900,
    ...over,
} as unknown as PocketResponse);

/** Суммы — с неразрывными пробелами Intl; сравниваем обычными. */
const sp = (s: string) => s.replace(/\s/g, ' ');

const forecastAlert = { kind: 'FORECAST', date: '2026-11-24', deficit: 1_100, drivenBy: null, beyondChart: false, forecastNote: null } as WatchdogAlert;

describe('buildTodayTiles', () => {
    it('обычно — три плитки: как обычно, на картах с датой сверки, самый низкий с датой', () => {
        const t = buildTodayTiles(pocket(), null);
        expect(t.map(x => [x.key, sp(x.value), x.caption])).toEqual([
            ['usual', '15 900 ₽', 'как обычно'],
            ['cards', '48 800 ₽', '12 нояб.'],
            ['low', '17 000 ₽', '24 нояб.'],
        ]);
        expect(t[0].tight).toBe(false);
    });

    it('прогноза нет — плитки «как обычно» нет', () => {
        expect(buildTodayTiles(pocket({ pocketWithForecast: null }), null).map(x => x.key)).toEqual(['cards', 'low']);
    });

    it('сторож по прогнозу — «как обычно» песочная и «впритык»', () => {
        const [usual] = buildTodayTiles(pocket({ pocketWithForecast: -1_100 }), forecastAlert);
        expect(sp(usual.value)).toBe('-1 100 ₽');
        expect(usual).toMatchObject({ caption: 'впритык', tight: true, sr: 'С обычными тратами, впритык:' });
    });

    it('сторож по плану «впритык» не пишет', () => {
        const [usual] = buildTodayTiles(pocket(), { ...forecastAlert, kind: 'PLAN' });
        expect(usual.caption).toBe('как обычно');
    });

    it('имя плитки «на картах» — с датой и «Обновить»; сверки не было — без даты', () => {
        expect(sp(cardsTileLabel(pocket()))).toBe('На картах 48 800 ₽, обновлён 12 нояб. Обновить');
        expect(sp(cardsTileLabel(pocket({ checkpointDate: null })))).toBe('На картах 48 800 ₽. Обновить');
    });
});
