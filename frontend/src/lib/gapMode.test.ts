import { describe, expect, it } from 'vitest';
import { fmtRub as fmtC } from './format';
import { buildGapMode } from './gapMode';
import type { PocketResponse, UpcomingItem } from '../types/api';

type Row = Partial<UpcomingItem> & Pick<UpcomingItem, 'date' | 'amount'>;
const row = (r: Row): UpcomingItem => ({
    id: 'id-' + r.date + '-' + r.amount, categoryName: null, description: null,
    overdue: false, wishlist: false, priority: 'MEDIUM', ...r,
});

/**
 * Случай владельца 24.09.2026: на счёте уже −2 685, сегодня «Досуг» на 1 000 — узкий день
 * сегодня, −3 685. Доход 28.09 выводит в 63 315.
 */
function owner(overrides: Partial<PocketResponse> = {}): PocketResponse {
    return {
        pocket: -3685,
        currentBalance: -2685,
        buffer: 0,
        checkpointDate: '2026-09-01',
        horizon: { type: 'NEXT_INCOME', endDate: '2026-09-28', label: 'до дохода 28.09', fallback: false },
        minPoint: { date: '2026-09-24', balance: -3685, drivenBy: null },
        minPointWithForecast: null,
        breakdown: [],
        trajectory: [
            { date: '2026-09-24', balance: -3685, income: 0, expense: 1000, balanceWithForecast: null },
            { date: '2026-09-25', balance: -3685, income: 0, expense: 0, balanceWithForecast: null },
            { date: '2026-09-26', balance: -3685, income: 0, expense: 0, balanceWithForecast: null },
            { date: '2026-09-27', balance: -3685, income: 0, expense: 0, balanceWithForecast: null },
            { date: '2026-09-28', balance: 63315, income: 75000, expense: 8000, balanceWithForecast: null },
        ],
        wishlistCandidates: [],
        upcoming: [
            row({ date: '2026-09-24', amount: 1000, categoryName: 'Досуг' }),
            row({ date: '2026-09-28', amount: 8000, categoryName: 'Продукты' }),
        ],
        pocketAfterCreditRestore: -77205,
        pocketWithDeposits: null,
        pocketWithForecast: null,
        planHasExpectations: true,
        ...overrides,
    };
}

describe('buildGapMode (ANO-100)', () => {
    it('случай владельца: сегодня не хватает, доход закроет, «Досуг» можно сдвинуть', () => {
        expect(buildGapMode(owner())).toEqual({
            horizonLabel: 'до дохода 28.09',
            headline: `Сегодня по плану не хватает ${fmtC(3685)}`,
            subline: `Доход 28.09 закроет разрыв, останется ${fmtC(63315)}.`,
            moveHeader: 'Можно сдвинуть на после 28.09:',
            movable: [`Досуг, ${fmtC(1000)} → не хватит ${fmtC(2685)}`],
        });
    });

    it('денег хватает — режима нет, даже если кармашек ниже нуля из-за подушки', () => {
        expect(buildGapMode(owner({
            pocket: -2000, buffer: 5000,
            minPoint: { date: '2026-09-26', balance: 3000, drivenBy: null },
        }))).toBeNull();
    });

    it('узкий день впереди — дата словами и будущее время', () => {
        const gap = buildGapMode(owner({ minPoint: { date: '2026-09-26', balance: -3685, drivenBy: null } }));
        expect(gap?.headline).toBe(`26 сентября по плану не хватит ${fmtC(3685)}`);
    });

    it('доход не закрывает разрыв — план требует правки', () => {
        const p = owner();
        p.trajectory[4] = { ...p.trajectory[4], balance: -1200 };
        expect(buildGapMode(p)?.subline).toBe('Даже доход 28.09 не закроет разрыв — план требует правки.');
    });

    it('доход выводит ровно в ноль — разрыв закрыт', () => {
        const p = owner();
        p.trajectory[4] = { ...p.trajectory[4], balance: 0 };
        expect(buildGapMode(p)?.subline).toBe(`Доход 28.09 закроет разрыв, останется ${fmtC(0)}.`);
    });

    it('срок без дохода — о доходе ни слова, хотя точка на конец срока есть', () => {
        // Точка на 24.10 положительная: без проверки «срок заякорен доходом» строка
        // сказала бы «Доход 24.10 закроет разрыв» — о доходе, которого в плане нет.
        const p = owner({
            horizon: { type: 'NEXT_INCOME', endDate: '2026-10-24', label: '30 дней вперёд (нет плановых доходов)', fallback: true },
        });
        p.trajectory.push({ date: '2026-10-24', balance: 5000, income: 0, expense: 0, balanceWithForecast: null });
        expect(buildGapMode(p)?.subline).toBeNull();
    });

    it('сдвинуть предлагаются только ожидания и хотелки до узкого дня, без просрочки и синтетики', () => {
        const gap = buildGapMode(owner({
            minPoint: { date: '2026-09-26', balance: -3685, drivenBy: null },
            upcoming: [
                row({ date: '2026-09-20', amount: 4000, categoryName: 'Коммуналка', overdue: true, priority: 'HIGH' }),
                row({ date: '2026-09-24', amount: 23600, categoryName: 'Ипотека', priority: 'HIGH' }),
                row({ date: '2026-09-25', amount: 900, categoryName: 'Кафе' }),
                row({ date: '2026-09-26', amount: 1500, categoryName: 'Стрижка', priority: 'LOW' }),
                row({ date: '2026-09-27', amount: 5000, categoryName: 'После узкого дня' }),
                row({ date: '2026-09-25', amount: 7000, id: null, description: 'Взнос: Отпуск' }),
            ],
        }));
        expect(gap?.movable.map(m => m.split(',')[0])).toEqual(['Стрижка', 'Кафе']);
    });

    it('не больше трёх строк, крупные первыми', () => {
        const gap = buildGapMode(owner({
            upcoming: [100, 700, 300, 500].map(a => row({ date: '2026-09-24', amount: a, categoryName: 'Трата ' + a })),
        }));
        expect(gap?.movable.map(m => m.split(',')[0])).toEqual(['Трата 700', 'Трата 500', 'Трата 300']);
    });

    it('сдвиг, закрывающий разрыв целиком, — «разрыва не будет»', () => {
        const gap = buildGapMode(owner({
            upcoming: [row({ date: '2026-09-24', amount: 5000, categoryName: 'Отпуск' })],
        }));
        expect(gap?.movable).toEqual([`Отпуск, ${fmtC(5000)} → разрыва не будет`]);
    });

    it('новый разрыв — худший день срока: сдвиг поднимает только дни от даты строки, хвост не в счёт', () => {
        // До строки уже −2 500, в день строки −3 000. Сдвиг «Кафе» на 2 000 поднимает день
        // строки до −1 000, а день до неё остаётся −2 500 — он и есть новый разрыв.
        // Хвост за сроком (−9 000 третьего числа) кармашек не вычитает и здесь не считается.
        const p = owner({
            minPoint: { date: '2026-09-25', balance: -3000, drivenBy: null },
            trajectory: [
                { date: '2026-09-24', balance: -2500, income: 0, expense: 0, balanceWithForecast: null },
                { date: '2026-09-25', balance: -3000, income: 0, expense: 500, balanceWithForecast: null },
                { date: '2026-09-28', balance: 60000, income: 63000, expense: 0, balanceWithForecast: null },
                { date: '2026-10-03', balance: -9000, income: 0, expense: 69000, balanceWithForecast: null },
            ],
            upcoming: [row({ date: '2026-09-25', amount: 2000, categoryName: 'Кафе' })],
        });
        expect(buildGapMode(p)?.movable).toEqual([`Кафе, ${fmtC(2000)} → не хватит ${fmtC(2500)}`]);
    });
});
