import { describe, expect, it } from 'vitest';
import { fmtRub as fmtC } from './format';
import { buildBreakdownView } from './breakdownRows';
import type { PocketResponse, UpcomingItem } from '../types/api';

type Line = PocketResponse['breakdown'][number];
const line = (type: string, amount: number, details: string[] = [], label = 'подпись бэка'): Line =>
    ({ type: type as Line['type'], label, amount, details });

type Row = Partial<UpcomingItem> & Pick<UpcomingItem, 'date' | 'amount'>;
const up = (r: Row): UpcomingItem => ({
    id: 'id-' + r.date + '-' + r.amount + '-' + (r.categoryName ?? r.description ?? ''),
    categoryName: null, description: null, overdue: false, wishlist: false,
    priority: 'HIGH', type: 'EXPENSE', ...r,
});

/** Семь броней с прошедшей датой со стенда 25.09: своё имя есть только у стрижки. */
const OVERDUE: UpcomingItem[] = [
    up({ date: '2026-09-01', amount: 23600, categoryName: 'Ипотека', overdue: true }),
    ...[2, 3, 4, 5].map(d => up({ date: `2026-09-0${d}`, amount: 8000, categoryName: 'Продукты', overdue: true })),
    up({ date: '2026-09-10', amount: 2000, categoryName: 'Услуги людские', description: 'Стрижка', overdue: true }),
    up({ date: '2026-09-15', amount: 4000, categoryName: 'Коммуналка', description: '  ', overdue: true }),
];

/** Стенд 25.09: нехватка сегодня −1 600, НЗ не задан, доход 28.09. */
function stand(overrides: Partial<PocketResponse> = {}): PocketResponse {
    return {
        pocket: -1600,
        currentBalance: 60000,
        buffer: 0,
        checkpointDate: '2026-08-29',
        horizon: { type: 'NEXT_INCOME', endDate: '2026-09-28', label: 'до дохода 28.09', fallback: false },
        minPoint: { date: '2026-09-25', balance: -1600, drivenBy: null },
        minPointWithForecast: null,
        breakdown: [
            line('STARTING_BALANCE', 60000),
            line('OVERDUE_RESERVE', -61600, ['', '', '', '', '', 'Стрижка', '']),
            line('TRAJECTORY_MIN', -1600),
            line('POCKET', -1600),
            line('OVERDUE_RELEASED', 13000, ['', 'Страховка']),
            line('CREDIT_RESTORE', -70680),
            line('WISHLIST_INFO', 594048),
        ],
        trajectory: [
            { date: '2026-09-25', balance: -1600, income: 0, expense: 0, balanceWithForecast: null },
            { date: '2026-09-28', balance: 61400, income: 75000, expense: 12000, balanceWithForecast: null },
        ],
        wishlistCandidates: [],
        upcoming: [
            ...OVERDUE,
            up({ date: '2026-09-28', amount: 4000, categoryName: 'Авто', description: 'Бензин', priority: 'MEDIUM' }),
        ],
        pocketAfterCreditRestore: -72280,
        pocketWithDeposits: 148400,
        pocketWithForecast: null,
        planHasExpectations: true,
        ...overrides,
    };
}

/** Обычный день со стенда (#67): +5 000 сегодня, «Кафе с коллегами» на 900, НЗ 1 000. */
function normalWithNz(overrides: Partial<PocketResponse> = {}): PocketResponse {
    return stand({
        pocket: 1500,
        currentBalance: 65000,
        buffer: 1000,
        minPoint: { date: '2026-09-25', balance: 2500, drivenBy: null },
        breakdown: [
            line('STARTING_BALANCE', 65000),
            line('OVERDUE_RESERVE', -61600, ['', '', '', '', '', 'Стрижка', '']),
            line('PLANNED_EXPENSES', -900),
            line('TRAJECTORY_MIN', 2500),
            line('BUFFER', -1000),
            line('POCKET', 1500),
            line('OVERDUE_RELEASED', 13000, ['', 'Страховка']),
            line('CREDIT_RESTORE', -70680),
            line('WISHLIST_INFO', 594048),
        ],
        upcoming: [
            ...OVERDUE,
            up({ date: '2026-09-25', amount: 900, categoryName: 'Кафе, рестики, фастфуд', description: 'Кафе с коллегами', priority: 'MEDIUM' }),
            // Синтетика — взнос копилки: у неё своя строка, в «ещё уйдёт» её нет.
            up({ id: null, date: '2026-09-25', amount: 3000, description: 'Взнос: Отпуск', type: 'FUND_TRANSFER' }),
            up({ date: '2026-09-28', amount: 4000, categoryName: 'Авто', description: 'Бензин', priority: 'MEDIUM' }),
        ],
        ...overrides,
    });
}

const RESERVE_NOTE = '7 платежей ещё без факта: Ипотека, Продукты ×4, Стрижка, Коммуналка';

describe('buildBreakdownView (ANO-77): число и пояснение', () => {
    it('стенд, нехватка без НЗ: самый узкий день сливается с итогом «Не хватает»', () => {
        expect(buildBreakdownView(stand())).toEqual({
            calc: [
                { kind: 'item', label: 'На счёте', amount: fmtC(60000), note: 'по сверке 29 августа и тому, что записано после' },
                { kind: 'item', label: 'Брони с прошедшей датой', amount: fmtC(-61600), note: RESERVE_NOTE },
            ],
            result: { kind: 'result', label: 'Не хватает', amount: fmtC(1600), note: 'самый узкий день — сегодня, 25 сентября' },
            caveats: [
                { kind: 'caveat', label: 'Сверка сняла с брони', amount: fmtC(13000), note: '29 августа: Страховка и ещё 1 платёж' },
                { kind: 'caveat', label: 'Погасить карты до планки', amount: fmtC(-70680), note: 'столько внести на карты, чтобы доступное дошло до планки из «Счетов»' },
                { kind: 'caveat', label: 'Хотелки', amount: fmtC(594048), note: 'не входят, пока не зафиксированы с датой' },
            ],
        });
    });

    it('обычный день с НЗ: самый узкий день — промежуточный итог, затем НЗ и «Свободно»', () => {
        const v = buildBreakdownView(normalWithNz());
        expect(v.calc).toEqual([
            { kind: 'item', label: 'На счёте', amount: fmtC(65000), note: 'по сверке 29 августа и тому, что записано после' },
            { kind: 'item', label: 'Брони с прошедшей датой', amount: fmtC(-61600), note: RESERVE_NOTE },
            { kind: 'item', label: 'Ещё уйдёт сегодня', amount: fmtC(-900), note: 'по плану: Кафе с коллегами' },
            { kind: 'subtotal', label: 'Самый узкий день — сегодня', amount: fmtC(2500), note: 'столько останется сегодня, дальше по плану не ниже' },
            { kind: 'item', label: 'НЗ', amount: fmtC(-1000), note: 'не трогаем' },
        ]);
        expect(v.result).toEqual({ kind: 'result', label: 'Свободно', amount: fmtC(1500), note: null });
    });

    it('НЗ задет: итог «Придётся взять из НЗ» — сколько не хватает до НЗ', () => {
        const v = buildBreakdownView(normalWithNz({ buffer: 5000, pocket: -2500 }));
        expect(v.result).toEqual({ kind: 'result', label: 'Придётся взять из НЗ', amount: fmtC(2500), note: null });
        expect(v.calc.map(r => r.kind)).toEqual(['item', 'item', 'item', 'subtotal', 'item']);
    });

    it('нехватка с НЗ: счёт от нуля, НЗ уходит в оговорки и говорит, сколько с ним', () => {
        const p = stand({ buffer: 5000, pocket: -6600 });
        p.breakdown.splice(3, 0, line('BUFFER', -5000));
        const v = buildBreakdownView(p);
        expect(v.result.amount).toBe(fmtC(1600));
        expect(v.calc.map(r => r.label)).toEqual(['На счёте', 'Брони с прошедшей датой']);
        expect(v.caveats[0]).toEqual({
            kind: 'caveat', label: 'НЗ', amount: fmtC(-5000),
            note: `при нехватке счёт от нуля; вместе с НЗ не хватает ${fmtC(6600)}`,
        });
    });

    it('обычный день без НЗ: промежуточной строки нет, узкий день — в пояснении «Свободно»', () => {
        const p = normalWithNz({ buffer: 0, pocket: 2500 });
        p.breakdown = p.breakdown.filter(l => l.type !== 'BUFFER')
            .map(l => l.type === 'POCKET' ? { ...l, amount: 2500 } : l);
        const v = buildBreakdownView(p);
        expect(v.calc.some(r => r.kind === 'subtotal')).toBe(false);
        expect(v.result).toEqual({ kind: 'result', label: 'Свободно', amount: fmtC(2500), note: 'самый узкий день — сегодня, 25 сентября' });
    });

    it('узкий день впереди — «ещё уйдёт до …», и в список берутся только строки до него', () => {
        const p = normalWithNz({ minPoint: { date: '2026-10-12', balance: 2500, drivenBy: null } });
        p.upcoming = [
            up({ date: '2026-10-01', amount: 900, categoryName: 'Кафе', priority: 'MEDIUM' }),
            up({ date: '2026-10-12', amount: 2000, categoryName: 'Услуги', description: 'Стрижка', priority: 'MEDIUM' }),
            up({ date: '2026-10-20', amount: 5000, categoryName: 'После узкого дня', priority: 'MEDIUM' }),
        ];
        const v = buildBreakdownView(p);
        expect(v.calc.find(r => r.label.startsWith('Ещё уйдёт'))).toEqual({
            kind: 'item', label: 'Ещё уйдёт до 12 октября', amount: fmtC(-900), note: 'по плану: Кафе, Стрижка',
        });
        expect(v.calc.find(r => r.kind === 'subtotal')?.label).toBe('Самый узкий день — 12 октября');
    });

    it('больше четырёх разных имён — первые четыре и «и ещё N»', () => {
        const names = ['А', 'Б', 'В', 'Г', 'Д', 'Е'];
        const p = stand({ upcoming: names.map((n, i) => up({ date: `2026-09-0${i + 1}`, amount: 100, categoryName: n, overdue: true })) });
        p.breakdown[1] = line('OVERDUE_RESERVE', -600, names.map(() => ''));
        expect(buildBreakdownView(p).calc[1].note).toBe('6 платежей ещё без факта: А, Б, В, Г и ещё 2');
    });

    it('сверка без единого имени — просто сколько платежей', () => {
        const p = stand();
        p.breakdown[4] = line('OVERDUE_RELEASED', 13000, ['', '']);
        expect(buildBreakdownView(p).caveats[0].note).toBe('29 августа: 2 платежа');
    });

    it('без сверки — остаток по записанным операциям', () => {
        expect(buildBreakdownView(stand({ checkpointDate: null })).calc[0].note)
            .toBe('по записанным операциям — сверки ещё не было');
    });

    it('незнакомый тип строки не пропадает: подпись и имена от бэка', () => {
        const p = stand();
        p.breakdown.splice(1, 0, line('SOMETHING_NEW', -500, ['раз', 'два'], 'Новая строка'));
        p.breakdown.push(line('ANOTHER_NEW', 700, [], 'Новая оговорка'));
        const v = buildBreakdownView(p);
        expect(v.calc[1]).toEqual({ kind: 'item', label: 'Новая строка', amount: fmtC(-500), note: 'раз, два' });
        expect(v.caveats.at(-1)).toEqual({ kind: 'caveat', label: 'Новая оговорка', amount: fmtC(700), note: null });
    });
});
