import { describe, expect, it } from 'vitest';
import { buildWatchdogAlert } from './watchdogAlert';
import type { PocketResponse } from '../types/api';

type MinPoint = PocketResponse['minPoint'];

function watchdog(
    minBalance: number,
    minDate = '2026-07-22',
    minPointWithForecast: MinPoint | null = null,
): PocketResponse {
    return {
        pocket: minBalance,
        currentBalance: 50000,
        buffer: 0,
        checkpointDate: '2026-07-01',
        horizon: { type: 'SECOND_INCOME', endDate: '2026-07-25', label: 'до 2-го дохода 25.07', fallback: false },
        minPoint: { date: minDate, balance: minBalance, drivenBy: minBalance < 0 ? 'Аренда' : null },
        minPointWithForecast,
        breakdown: [],
        trajectory: [],
        wishlistCandidates: [],
        upcoming: [],
        pocketAfterCreditRestore: null,
        pocketWithDeposits: null,
        pocketWithForecast: minPointWithForecast ? minPointWithForecast.balance : null,
    };
}

const softMin = (balance: number, date = '2026-07-19'): MinPoint =>
    ({ date, balance, drivenBy: null });

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
            kind: 'PLAN',
            date: '2026-07-22',
            deficit: 4500,
            drivenBy: 'Аренда',
            beyondChart: false,
            forecastNote: null,
        });
    });

    it('beyondChart: дата разрыва позже конца пользовательского горизонта', () => {
        expect(buildWatchdogAlert(watchdog(-4500), '2026-07-15')?.beyondChart).toBe(true);
        expect(buildWatchdogAlert(watchdog(-4500), '2026-07-22')?.beyondChart).toBe(false);
    });

    it('пользовательский горизонт неизвестен (null) → beyondChart false', () => {
        expect(buildWatchdogAlert(watchdog(-4500), null)?.beyondChart).toBe(false);
    });

    // ── ANO-80: два рода предупреждения ───────────────────────────────────────

    it('ANO-80: по планам хватает, с обычными тратами — нет: плашка другого рода', () => {
        const alert = buildWatchdogAlert(watchdog(12000, '2026-07-22', softMin(-3000)), null);

        expect(alert?.kind).toBe('FORECAST');
        expect(alert?.date).toBe('2026-07-19');
        expect(alert?.deficit).toBe(3000);
        expect(alert?.drivenBy)
            .toBeNull();  // размазка прогноза виновника не имеет
    });

    it('ANO-80: оба минимума ниже нуля — жёсткая плашка с оговоркой', () => {
        const alert = buildWatchdogAlert(
            watchdog(-4500, '2026-07-22', softMin(-9000, '2026-07-18')), null);

        // Разрыв по собственным планам требует действия и потому главнее; прогнозный
        // уходит в оговорку внутри той же плашки, а не во вторую плашку рядом.
        expect(alert?.kind).toBe('PLAN');
        expect(alert?.forecastNote).toEqual({ date: '2026-07-18', deficit: 9000 });
    });

    it('ANO-80: прогнозный минимум ПОЗЖЕ планового, но глубже — оговорка всё равно есть', () => {
        // Замер на эталонном стенде: минимум по планам 12.10, с обычными тратами — 14.10.
        // Признак оговорки — «глубже», а не «раньше»; текст плашки обязан говорить то же.
        const alert = buildWatchdogAlert(
            watchdog(-130712, '2026-10-12', softMin(-226991, '2026-10-14')), null);

        expect(alert?.kind).toBe('PLAN');
        expect(alert?.forecastNote).toEqual({ date: '2026-10-14', deficit: 226991 });
    });

    it('ANO-80: с обычными тратами не глубже — оговорки нет', () => {
        const alert = buildWatchdogAlert(
            watchdog(-4500, '2026-07-22', softMin(-4500, '2026-07-22')), null);

        expect(alert?.kind).toBe('PLAN');
        expect(alert?.forecastNote)
            .toBeNull();  // говорить «а ещё» не о чем: то же число, тот же день
    });

    it('ANO-80: оба выше нуля — плашки нет вовсе', () => {
        expect(buildWatchdogAlert(watchdog(12000, '2026-07-22', softMin(500)), null)).toBeNull();
    });

    it('ANO-80: прогноза нет — поведение прежнее', () => {
        expect(buildWatchdogAlert(watchdog(12000), null)).toBeNull();
        expect(buildWatchdogAlert(watchdog(-4500), null)?.kind).toBe('PLAN');
    });
});
