import { describe, expect, it } from 'vitest';
import type { FinancialEvent } from '../types/api';
import {
    categoryLocked, dayLabel, duplicateOf, editChips, endSeriesDto, factEditPatch, factNote, nextDates, planEditDto, seriesOf,
    withCurrent,
} from './eventCard';

const ev = (over: Partial<FinancialEvent>): FinancialEvent => ({
    id: 'e', date: '2026-11-12', categoryId: 'c', categoryName: 'Жильё и быт', type: 'EXPENSE', plannedAmount: 25000,
    factAmount: null, status: 'PLANNED', priority: 'HIGH', description: 'Аренда', rawInput: null,
    createdAt: '2026-11-01T09:00:00', eventKind: 'PLAN', parentEventId: null, linkedFactsCount: 0,
    linkedFactsAmount: null, parentPlanDescription: null, ...over,
});
const series = (over: Partial<FinancialEvent> = {}) => ev({
    recurringRuleId: 'r', recurringFrequency: 'MONTHLY', recurringDayOfMonth: 12, recurringMonthOfYear: null,
    recurringStartDate: '2026-03-12', recurringEndDate: null, ...over,
});
const fact = (over: Partial<FinancialEvent> = {}) => ev({
    eventKind: 'FACT', status: 'EXECUTED', plannedAmount: null, factAmount: 1240, priority: 'MEDIUM',
    categoryName: 'Продукты', description: 'Пятёрочка', ...over,
});

/** Срез 3 п. 18 (ANO-130): серия в карточке — правило, начало, конец или «без конца», три следующие даты. */
describe('seriesOf', () => {
    it('месяц: «Каждый месяц, 12-го», с какой даты, без конца, три даты дальше', () => {
        expect(seriesOf(series())).toEqual({
            title: 'Каждый месяц, 12-го', since: 'с 12 мар. 2026', until: 'без конца',
            next: ['2026-12-12', '2027-01-12', '2027-02-12'],
        });
    });
    it('год: число и месяц из правила, конец — датой', () => {
        const s = seriesOf(series({ recurringFrequency: 'YEARLY', recurringMonthOfYear: 11, recurringEndDate: '2030-11-12' }));
        expect(s?.title).toBe('Каждый год, 12 нояб.');
        expect(s?.until).toBe('до 12 нояб. 2030');
        expect(s?.next).toEqual(['2027-11-12', '2028-11-12', '2029-11-12']);
    });
    it('строка 28 февр. серии 31-го — подпись по правилу: «31-го»', () => {
        expect(seriesOf(series({ date: '2027-02-28', recurringDayOfMonth: 31 }))?.title).toBe('Каждый месяц, 31-го');
    });
    it('не серия — нет блока', () => {
        expect(seriesOf(ev({}))).toBeNull();
    });
});

describe('nextDates', () => {
    it('31-е в коротком месяце — последний день, как у сервера (RecurringEventGenerator)', () => {
        expect(nextDates(series({ date: '2027-01-31', recurringDayOfMonth: 31 })))
            .toEqual(['2027-02-28', '2027-03-31', '2027-04-30']);
    });
    it('строка в коротком месяце — следующая снова на число правила, а не на её день', () => {
        expect(nextDates(series({ date: '2027-02-28', recurringDayOfMonth: 31 }))[0]).toBe('2027-03-31');
    });
    it('серия кончается — дат меньше трёх', () => {
        expect(nextDates(series({ recurringEndDate: '2027-01-12' }))).toEqual(['2026-12-12', '2027-01-12']);
    });
    it('последняя строка серии — дальше ничего', () => {
        expect(nextDates(series({ recurringEndDate: '2026-11-12' }))).toEqual([]);
    });
});

/** «Закончить серию»: этот платёж последний — PUT со scope=FOLLOWING и концом серии на его дату (п. 18). */
describe('endSeriesDto', () => {
    it('конец серии — дата строки; число и месяц — правила, не строки', () => {
        const dto = endSeriesDto(series({ date: '2027-02-28', recurringDayOfMonth: 31, mandatory: true }));
        expect(dto.recurring).toEqual({ frequency: 'MONTHLY', dayOfMonth: 31, monthOfYear: null, endDate: '2027-02-28' });
        expect(dto).toMatchObject({ date: '2027-02-28', categoryId: 'c', type: 'EXPENSE', plannedAmount: 25000, priority: 'HIGH', mandatory: true });
    });
});

/** «Ещё раз сегодня» (п. 17, ANO-254): поля образца сегодняшним числом, без связи с бронью. */
describe('duplicateOf', () => {
    it('сумма, категория, название, вид, тип — как у образца; дата — сегодня', () => {
        expect(duplicateOf(fact({ date: '2026-11-05', rawInput: '1000+240', parentEventId: 'bron' }), '2026-11-21')).toEqual({
            date: '2026-11-21', categoryId: 'c', type: 'EXPENSE', factAmount: 1240, description: 'Пятёрочка',
            priority: 'MEDIUM', rawInput: '1000+240',
        });
    });
    it('связь с планом не копируется — к ожиданию запись встанет по правилу сервера', () => {
        expect(duplicateOf(fact({ parentEventId: 'bron' }), '2026-11-21')).not.toHaveProperty('parentEventId');
    });
});

/** Правка записи (п. 2, ANO-253): дата, категория и вид — тем же PATCH. */
describe('factEditPatch', () => {
    const form = { amount: 1300, description: 'Пятёрочка', rawInput: undefined, date: '2026-11-10', categoryId: 'cafe', kind: 'LOW' as const };
    it('дата, категория, вид и сумма уходят в запрос', () => {
        expect(factEditPatch(fact(), form)).toEqual({
            factAmount: 1300, description: 'Пятёрочка', rawInput: undefined, date: '2026-11-10', categoryId: 'cafe', priority: 'LOW',
        });
    });
    it('категория та же — не шлётся: у записи к брони сервер отверг бы и её', () => {
        expect(factEditPatch(fact(), { ...form, categoryId: 'c' }).categoryId).toBeUndefined();
    });
    it('пустое название — без изменений, как раньше', () => {
        expect(factEditPatch(fact(), { ...form, description: '' }).description).toBeUndefined();
    });
});

/** У записи к брони и у перевода в копилку категория не правится (п. 2) — форма её не предлагает. */
describe('categoryLocked', () => {
    it('запись к брони — заперта, к ожиданию — нет, без плана — нет', () => {
        expect(categoryLocked(fact({ parentEventId: 'p', parentPlanPriority: 'HIGH' }))).toBe(true);
        expect(categoryLocked(fact({ parentEventId: 'p', parentPlanPriority: 'LOW' }))).toBe(true);
        expect(categoryLocked(fact({ parentEventId: 'p', parentPlanPriority: 'MEDIUM' }))).toBe(false);
        expect(categoryLocked(fact())).toBe(false);
    });
    it('перевод в копилку — заперт и у записи, и у плана', () => {
        expect(categoryLocked(fact({ type: 'FUND_TRANSFER' }))).toBe(true);
        expect(categoryLocked(ev({ type: 'FUND_TRANSFER' }))).toBe(true);
    });
    it('план — не заперт', () => {
        expect(categoryLocked(ev({}))).toBe(false);
    });
});

/** Правка плана (п. 16): PUT со своим scope; у серии — повтор, число из даты, если дату сменили. */
describe('planEditDto', () => {
    const form = {
        date: '2026-11-12', categoryId: 'c', type: 'EXPENSE' as const, amount: 26000, kind: 'HIGH' as const,
        description: 'Аренда', rawInput: undefined, fundId: undefined, endDate: null, scope: 'FOLLOWING' as const,
    };
    it('поля формы уходят в запрос', () => {
        expect(planEditDto(ev({ mandatory: true }), { ...form, scope: 'THIS' })).toEqual({
            date: '2026-11-12', categoryId: 'c', type: 'EXPENSE', plannedAmount: 26000, priority: 'HIGH', mandatory: true,
            description: 'Аренда', rawInput: undefined, targetFundId: undefined, recurring: undefined,
        });
    });
    it('«Только этот» — без повтора: серия не меняется', () => {
        expect(planEditDto(series(), { ...form, scope: 'THIS', date: '2026-11-15' }).recurring).toBeUndefined();
    });
    it('«Этот и дальше» с новой датой — число серии из даты', () => {
        expect(planEditDto(series(), { ...form, date: '2026-11-15' }).recurring)
            .toEqual({ frequency: 'MONTHLY', dayOfMonth: 15, monthOfYear: null, endDate: null });
    });
    it('дата не менялась — число правила: строка 28 февр. серии 31-го не переводит серию на 28-е', () => {
        expect(planEditDto(series({ date: '2027-02-28', recurringDayOfMonth: 31 }), { ...form, date: '2027-02-28', scope: 'ALL' }).recurring?.dayOfMonth)
            .toBe(31);
    });
    it('год — месяц из даты; конец серии — из формы', () => {
        expect(planEditDto(series({ recurringFrequency: 'YEARLY', recurringMonthOfYear: 11 }),
            { ...form, date: '2026-12-01', endDate: '2030-12-01', scope: 'ALL' }).recurring)
            .toEqual({ frequency: 'YEARLY', dayOfMonth: 1, monthOfYear: 12, endDate: '2030-12-01' });
    });
    it('перевод — копилка из формы, тип прежний', () => {
        expect(planEditDto(ev({ type: 'FUND_TRANSFER' }), { ...form, type: 'EXPENSE', fundId: 'f', scope: 'THIS' }))
            .toMatchObject({ type: 'FUND_TRANSFER', targetFundId: 'f' });
    });
});

/** Чипы категорий у правки (п. 16). */
describe('editChips', () => {
    const c = (id: string, transfer = false) => ({ id, name: id, transfer });
    const chips = () => ({ top: [c('c'), c('cafe'), c('savings', true)], rest: [c('zoo')] });
    it('расход переводом не становится: «Накопления» среди чипов нет', () => {
        expect(editChips(chips(), fact(), 'EXPENSE', true)).toEqual({ top: [c('c'), c('cafe')], rest: [c('zoo')] });
    });
    it('перевод остаётся переводом: только «Накопление»', () => {
        expect(editChips(chips(), fact({ type: 'FUND_TRANSFER', categoryId: 'savings' }), 'EXPENSE', true))
            .toEqual({ top: [c('savings', true)], rest: [] });
    });
    it('категория строки в архиве — в списке с пометкой; пока категории не пришли — нет', () => {
        const e = fact({ categoryId: 'old', categoryName: 'Старое' });
        expect(editChips(chips(), e, 'EXPENSE', true).rest).toContainEqual({ id: 'old', name: 'Старое (в архиве)', transfer: false });
        expect(editChips(chips(), e, 'EXPENSE', false).rest).not.toContainEqual(expect.objectContaining({ id: 'old' }));
    });
    it('сменил тип плана — архивная категория прежнего типа не предлагается', () => {
        expect(editChips(chips(), ev({ categoryId: 'old' }), 'INCOME', true).rest).not.toContainEqual(expect.objectContaining({ id: 'old' }));
    });
    it('запись к брони — на виду только её категория, без «ещё»', () => {
        expect(editChips(chips(), fact({ parentEventId: 'p', parentPlanPriority: 'HIGH', categoryId: 'zoo' }), 'EXPENSE', true))
            .toEqual({ top: [c('zoo')], rest: [] });
    });
});

describe('withCurrent', () => {
    it('копилки строки нет в списке — она добавляется; есть — список как был', () => {
        const all = [{ id: 'a' }, { id: 'reached' }];
        expect(withCurrent([{ id: 'a' }], all, 'reached')).toEqual([{ id: 'a' }, { id: 'reached' }]);
        expect(withCurrent([{ id: 'a' }], all, 'a')).toEqual([{ id: 'a' }]);
        expect(withCurrent([{ id: 'a' }], all, undefined)).toEqual([{ id: 'a' }]);
    });
});

/** Строка карточки о том, что случилось с записью (S3-E2a). Про «Свободно» — нет: оно зависит от даты и сверки. */
describe('factNote', () => {
    it('к ожиданию, по брони, к хотелке — по виду плана', () => {
        expect(factNote(fact({ parentEventId: 'p', parentPlanPriority: 'MEDIUM', parentPlanDescription: 'Продукты' })))
            .toBe('Встала к ожиданию «Продукты».');
        expect(factNote(fact({ parentEventId: 'p', parentPlanPriority: 'HIGH', parentPlanDescription: 'Аренда' }))).toBe('По брони «Аренда».');
        expect(factNote(fact({ parentEventId: 'p', parentPlanPriority: 'LOW', parentPlanDescription: 'Наушники' }))).toBe('К хотелке «Наушники».');
    });
    it('без плана — «В плане этого не было.», как строка формы', () => {
        expect(factNote(fact())).toBe('В плане этого не было.');
    });
    it('у перевода и у плана — нет', () => {
        expect(factNote(fact({ type: 'FUND_TRANSFER' }))).toBeNull();
        expect(factNote(ev({}))).toBeNull();
    });
});

describe('dayLabel', () => {
    it('сегодня, вчера, другой день; другой год — с годом', () => {
        expect(dayLabel('2026-11-12', '2026-11-12')).toBe('сегодня, 12 нояб.');
        expect(dayLabel('2026-11-11', '2026-11-12')).toBe('вчера, 11 нояб.');
        expect(dayLabel('2026-11-01', '2026-11-12')).toBe('1 нояб.');
        expect(dayLabel('2025-12-31', '2026-01-01')).toBe('вчера, 31 дек. 2025');
    });
});
