import { describe, expect, it } from 'vitest';
import { buildWatchdogAlert } from './watchdogAlert';
import type { PocketResponse } from '../types/api';

function watchdog(minBalance: number, minDate = '2026-07-22'): PocketResponse {
    return {
        pocket: minBalance,
        currentBalance: 50000,
        buffer: 0,
        horizon: { type: 'SECOND_INCOME', endDate: '2026-07-25', label: 'до 2-го дохода 25.07', fallback: false },
        minPoint: { date: minDate, balance: minBalance, drivenBy: minBalance < 0 ? 'Аренда' : null },
        breakdown: [],
        trajectory: [],
        wishlistCandidates: [],
    };
}

describe('buildWatchdogAlert', () => {
    it('null пока сторожевой pocket не загружен', () => {
        expect(buildWatchdogAlert(null, '2026-07-15')).toBeNull();
    });

    it('null при неотрицательном минимуме (нет разрыва)', () => {
        expect(buildWatchdogAlert(watchdog(0), '2026-07-15')).toBeNull();
        expect(buildWatchdogAlert(watchdog(3000), '2026-07-15')).toBeNull();
    });

    it('разрыв: дата, дефицит по модулю, виновник', () => {
        expect(buildWatchdogAlert(watchdog(-4500), '2026-07-30')).toEqual({
            date: '2026-07-22',
            deficit: 4500,
            drivenBy: 'Аренда',
            beyondChart: false,
        });
    });

    it('beyondChart: дата разрыва позже конца пользовательского горизонта', () => {
        expect(buildWatchdogAlert(watchdog(-4500), '2026-07-15')?.beyondChart).toBe(true);
        expect(buildWatchdogAlert(watchdog(-4500), '2026-07-22')?.beyondChart).toBe(false);
    });

    it('пользовательский горизонт неизвестен (null) → beyondChart false', () => {
        expect(buildWatchdogAlert(watchdog(-4500), null)?.beyondChart).toBe(false);
    });
});
