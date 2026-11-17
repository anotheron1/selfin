import { describe, expect, it, vi } from 'vitest';
import { updateHintsSettings } from '../api';
import { closeHint, firstDay, hideAllHints, numberHint, saveHints, switchHints } from './hints';
import type { HintsSettings, PocketResponse, UpcomingItem } from '../types/api';

vi.mock('../api', () => ({ updateHintsSettings: vi.fn() }));

const item = (description: string, date = '2026-11-20', over: Partial<UpcomingItem> = {}): UpcomingItem => ({
    id: `e-${description}-${date}`, date, categoryName: 'Обязательные платежи', amount: 1_000, description,
    overdue: false, wishlist: false, priority: 'HIGH', type: 'EXPENSE', ...over,
});

/** Человек сразу после `/start`: доход 25 нояб., аренда и связь до него, обычных трат в плане нет (S2-P1). */
function fresh(over: Partial<PocketResponse> = {}): PocketResponse {
    return {
        pocket: 23_000, currentBalance: 48_800, buffer: 0, checkpointDate: '2026-11-17',
        horizon: { type: 'NEXT_INCOME', endDate: '2026-11-25', label: '', fallback: false },
        minPoint: { date: '2026-11-24', balance: 23_000, drivenBy: null },
        minPointWithForecast: null, pocketWithForecast: null, planHasExpectations: false,
        breakdown: [], upcoming: [item('Аренда', '2026-11-18'), item('Связь', '2026-11-20')],
        trajectory: [{ date: '2026-11-17', balance: 48_800, income: 0, expense: 0, balanceWithForecast: null, items: [] }],
        ...over,
    } as unknown as PocketResponse;
}

const ON: HintsSettings = { off: false, closed: [] };

describe('подсказки первого дня (срез 2, п. 12; S2-P1)', () => {
    it('три подсказки — у числа, у склона, у «+» — сверху вниз; первая несёт «Скрыть все»', () => {
        expect(firstDay(fresh(), ON, 'land').hints).toEqual(['number', 'slope', 'plus']);
    });

    it('показываются и тем, кто уже пользуется: условие — только настройки, а не «новый человек»', () => {
        const old = fresh({ checkpointDate: '2025-03-01', planHasExpectations: true });
        expect(firstDay(old, ON, 'land').hints).toEqual(['number', 'slope', 'plus']);
    });

    it('закрытая не возвращается — и после повторного закрытия, и с другими закрытыми', () => {
        const once = closeHint(ON, 'slope');
        expect(firstDay(fresh(), once, 'land').hints).toEqual(['number', 'plus']);
        expect(closeHint(once, 'slope')).toEqual(once);
        expect(firstDay(fresh(), closeHint(once, 'number'), 'land').hints).toEqual(['plus']);
    });

    it('«Скрыть все подсказки» прячет все — и незакрытые', () => {
        expect(hideAllHints(closeHint(ON, 'slope'))).toEqual({ off: true, closed: ['slope'] });
        expect(firstDay(fresh(), hideAllHints(ON), 'land').hints).toEqual([]);
    });

    it('выключатель не прячет плашку «Обычных трат в плане нет»: это ответ о числе, а не помощь (ANO-252)', () => {
        expect(firstDay(fresh(), hideAllHints(ON), 'land').noUsual).toBe(true);
        expect(firstDay(fresh(), ON, 'land').noUsual).toBe(true);
        expect(firstDay(fresh(), null, 'land').noUsual).toBe(true);
        expect(firstDay(fresh({ planHasExpectations: true }), ON, 'land').noUsual).toBe(false);
    });

    it('настройки не пришли — подсказок нет: помощь не важнее экрана', () => {
        expect(firstDay(fresh(), null, 'land').hints).toEqual([]);
    });

    it('выключатель «Подсказки»: включил — закрытые показываются снова; выключил — закрытые помнятся', () => {
        const closed = closeHint(ON, 'number');
        expect(switchHints(hideAllHints(closed), true)).toEqual(ON);
        expect(switchHints(closed, false)).toEqual({ off: true, closed: ['number'] });
    });

    it('у числа — только когда оно «Свободно»: при нехватке и НЗ число другое', () => {
        const gap = fresh({ pocket: -5_000, minPoint: { date: '2026-11-24', balance: -5_000, drivenBy: null } });
        expect(firstDay(gap, ON, 'land').hints).toEqual(['slope', 'plus']);
        const nz = fresh({ buffer: 30_000 });
        expect(firstDay(nz, ON, 'land').hints).toEqual(['slope', 'plus']);
    });

    it('у склона — только на пейзаже: у клеток склона нет', () => {
        expect(firstDay(fresh(), ON, 'cells').hints).toEqual(['number', 'plus']);
    });
});

describe('запись настроек подсказок', () => {
    it('по очереди: ответ на раннюю не перетрёт позднюю; сбой не останавливает следующую', async () => {
        const put = vi.mocked(updateHintsSettings);
        let fail!: (e: Error) => void;
        put.mockImplementationOnce(() => new Promise((_, reject) => { fail = reject; }));
        put.mockResolvedValueOnce(ON);
        const first = closeHint(ON, 'number');
        const second = closeHint(first, 'slope');
        saveHints(first);
        const done = saveHints(second);
        await new Promise(r => setTimeout(r));
        expect(put).toHaveBeenCalledTimes(1);
        fail(new Error('сеть'));
        await done;
        expect(put.mock.calls.map(c => c[0])).toEqual([first, second]);
    });
});

describe('подсказка у числа — строки «Ещё уйдёт» по именам (S2-P1)', () => {
    it('две и больше — как на доске', () => {
        expect(numberHint(fresh())).toBe('Столько можно тратить до 25 нояб. Аренда и Связь уже вычтены.');
        const many = ['Аренда', 'Связь', 'Интернет', 'Детсад', 'Кредит', 'Спорт'].map(n => item(n));
        expect(numberHint(fresh({ upcoming: many })))
            .toBe('Столько можно тратить до 25 нояб. Аренда, Связь, Интернет, Детсад и ещё 2 уже вычтены.');
    });

    it('одна — без согласования рода', () => {
        expect(numberHint(fresh({ upcoming: [item('Аренда')] })))
            .toBe('Столько можно тратить до 25 нояб. Уже вычтено: Аренда.');
    });

    it('ничего впереди — одна фраза; прошедшие брони и строки после самого низкого остатка не названы', () => {
        const none = [item('Коммуналка', '2026-10-01', { overdue: true }), item('Отпуск', '2026-11-25')];
        expect(numberHint(fresh({ upcoming: none }))).toBe('Столько можно тратить до 25 нояб.');
        // «25 нояб.» с точкой, «15 мая» — без: точка в конце одна.
        const may = fresh({ upcoming: [], horizon: { type: 'NEXT_INCOME', endDate: '2027-05-15', label: '', fallback: false } });
        expect(numberHint(may)).toBe('Столько можно тратить до 15 мая.');
    });
});
