import { describe, expect, it, vi } from 'vitest';
import { fetchHintsSettings, updateHintsSettings } from '../api';
import {
    closeHint, firstDay, firstJournal, hideAllHints, JOURNAL_PAID_HINT, JOURNAL_PLUS_HINT, journalCircleHint, loadHints, numberHint,
    saveHints, switchHints,
} from './hints';
import type { FinancialEvent, HintsSettings, PocketResponse, UpcomingItem } from '../types/api';

vi.mock('../api', () => ({ updateHintsSettings: vi.fn(), fetchHintsSettings: vi.fn() }));

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

    it('чтение — после записей в очереди: включил в «Настройках», ушёл на «Сегодня» — видит включённое (ревью Codex)', async () => {
        const put = vi.mocked(updateHintsSettings);
        const get = vi.mocked(fetchHintsSettings);
        let done!: () => void;
        put.mockImplementationOnce(() => new Promise(resolve => { done = () => resolve(ON); }));
        get.mockResolvedValueOnce(ON);
        saveHints(ON);
        const read = loadHints();
        await new Promise(r => setTimeout(r));
        expect(get).not.toHaveBeenCalled();
        done();
        expect(await read).toEqual(ON);
        expect(get).toHaveBeenCalledTimes(1);
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

describe('подсказки первого журнала (срез 3, п. 6; ANO-59; S3-N1)', () => {
    const plan = { eventKind: 'PLAN' } as FinancialEvent;
    const record = { eventKind: 'FACT' } as FinancialEvent;
    const month = (over: Partial<Parameters<typeof firstJournal>[0]> = {}) =>
        ({ current: true, events: [plan], circle: true, paid: true, ...over });

    it('три пузыря — у первого кружка ожидания, у «Оплачено | своё», у «+»', () => {
        expect(firstJournal(month(), ON)).toEqual(['journal-circle', 'journal-paid', 'journal-plus']);
    });

    it('в текущем месяце есть запись — пузырей нет; другой месяц — нет', () => {
        expect(firstJournal(month({ events: [plan, record] }), ON)).toEqual([]);
        expect(firstJournal(month({ current: false }), ON)).toEqual([]);
    });

    it('нет ожиданий — нет пузыря у кружка; нет брони — нет у «Оплачено»', () => {
        expect(firstJournal(month({ circle: false }), ON)).toEqual(['journal-paid', 'journal-plus']);
        expect(firstJournal(month({ paid: false }), ON)).toEqual(['journal-circle', 'journal-plus']);
    });

    it('закрытая крестиком не возвращается; выключенные и не пришедшие настройки — пузырей нет', () => {
        expect(firstJournal(month(), closeHint(ON, 'journal-paid'))).toEqual(['journal-circle', 'journal-plus']);
        expect(firstJournal(month(), hideAllHints(ON))).toEqual([]);
        expect(firstJournal(month(), null)).toEqual([]);
    });

    it('«Скрыть все» в журнале прячет и подсказки «Сегодня»: выключатель один', () => {
        const fromJournal = hideAllHints(closeHint(ON, 'journal-circle'));
        expect(firstDay(fresh(), fromJournal, 'land').hints).toEqual([]);
    });

    it('имя подсказки годится серверу: латиница, цифры и дефис, до 32 знаков', () => {
        for (const id of firstJournal(month(), ON)) expect(id).toMatch(/^[a-z0-9-]{1,32}$/);
    });

    it('у кружка — имя ожидания; тексты — с доски', () => {
        expect(journalCircleHint('Продукты')).toBe('Купил — нажми на кружок «Продукты». Трата встанет к ожиданию, «Свободно» не изменится.');
        expect(JOURNAL_PAID_HINT).toBe('Заплатил — «Оплачено». Сумма другая — «своё».');
        expect(JOURNAL_PLUS_HINT).toBe('Остальные траты и доходы — через «+».');
    });
});
