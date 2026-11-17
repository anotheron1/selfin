import { describe, expect, it } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { WhyPlate } from './WhyPlate';
import type { PocketResponse, UpcomingItem } from '../../types/api';

type Line = PocketResponse['breakdown'][number];
const line = (type: string, amount: number, details: string[] = []): Line =>
    ({ type: type as Line['type'], label: 'подпись бэка', amount, details });

const bron = (date: string, amount: number, description: string, overdue = true): UpcomingItem => ({
    id: `e-${date}-${description}`, date, categoryName: 'Обязательные платежи', amount, description,
    overdue, wishlist: false, priority: 'HIGH', type: 'EXPENSE',
});

/** Тестовый человек стенда 16.11 вкратце: сверка 29 авг., брони без отметки — с конца августа до ноября. */
function stand(over: Partial<PocketResponse> = {}): PocketResponse {
    return {
        pocket: 17_000, currentBalance: 48_800, buffer: 0, checkpointDate: '2026-08-29',
        horizon: { type: 'NEXT_INCOME', endDate: '2026-11-25', label: '', fallback: false },
        minPoint: { date: '2026-11-24', balance: 17_000, drivenBy: null },
        minPointWithForecast: null,
        breakdown: [
            line('STARTING_BALANCE', 48_800),
            line('OVERDUE_RESERVE', -10_000),
            line('PLANNED_EXPENSES', -21_800),
            line('TRAJECTORY_MIN', 17_000),
            line('POCKET', 17_000),
            line('CREDIT_RESTORE', -15_000),
        ],
        upcoming: [
            bron('2026-10-01', 4_000, 'Коммуналка'),
            bron('2026-08-31', 2_000, 'Стрижка'),
            bron('2026-11-10', 4_000, 'Продукты'),
            bron('2026-11-20', 21_800, 'Аренда', false),
        ],
        trajectory: [{ date: '2026-11-17', balance: 48_800, income: 0, expense: 0, balanceWithForecast: null, items: [] }],
        pocketWithDeposits: 47_000,
        ...over,
    } as unknown as PocketResponse;
}

const noop = { busy: () => false, onMark: () => {} };
const render = (p: PocketResponse, extra: Partial<Parameters<typeof WhyPlate>[0]> = {}) =>
    renderToStaticMarkup(<WhyPlate data={p} size="narrow" onNz={() => {}} onReanchor={() => {}} overdue={noop} {...extra} />)
        .replace(/\s/g, ' ');
const rows = (html: string) => [...html.matchAll(/data-row="([^"]+)"/g)].map(m => m[1]);

describe('WhyPlate — «Из чего число»', () => {
    it('на телефоне свёрнуто: заголовок и полоса из двух кусков, строк нет', () => {
        const html = render(stand());
        expect(html).toContain('aria-expanded="false"');
        expect(html).toContain('data-bar="go"');
        expect(html).toContain('data-bar="free"');
        expect(rows(html)).toEqual([]);
    });

    it('раскрыто: строки по порядку, итог, «Кроме этого», НЗ — последней строкой', () => {
        const html = render(stand(), { initialOpen: true });
        expect(rows(html)).toEqual([
            'На картах', 'Брони с прошедшей датой', 'Ещё уйдёт до 24 ноября', 'Свободно',
            'Вернуть на кредитку', 'Если распечатать вклад',
        ]);
        expect(html.lastIndexOf('Задать НЗ')).toBeGreaterThan(html.lastIndexOf('data-row'));
        expect(html).toContain('НЗ не задан');
    });

    it('метки строк повторяют куски полосы: остаток — оба, вычеты — «уйдёт», итог — «свободно»', () => {
        const html = render(stand(), { initialOpen: true });
        const marks = [...html.matchAll(/data-row="([^"]+)".*?data-mark="([^"]+)"/g)].map(m => [m[1], m[2]]);
        expect(marks).toEqual([
            ['На картах', 'both'], ['Брони с прошедшей датой', 'go'], ['Ещё уйдёт до 24 ноября', 'go'], ['Свободно', 'free'],
        ]);
    });

    it('ANO-94: строка расходов и итог называют дату самого низкого остатка', () => {
        const html = render(stand(), { initialOpen: true });
        expect(html).toContain('Ещё уйдёт до 24 ноября');
        expect(html).toContain('самый низкий остаток — 24 ноября');
    });

    it('брони с прошедшей датой: пояснение — сколько и с какого по какое; список скрыт, пока строка не раскрыта', () => {
        const html = render(stand(), { initialOpen: true });
        expect(html).toContain('3 строки с 31 авг. по 10 нояб. — оплата не отмечена');
        expect(html).not.toContain('data-list="overdue"');
        expect(html).not.toContain('Отметить оплату');
    });

    it('раскрыт список: месяцы по порядку дат, у каждой брони — «Отметить оплату: сумма · дата», внизу — «Обновить»', () => {
        const html = render(stand(), { initialOpen: true, initialOverdueOpen: true });
        const list = html.slice(html.indexOf('data-list="overdue"'));
        const months = ['август', 'октябрь', 'ноябрь'].map(m => list.indexOf(`>${m}<`));
        expect(months.every(i => i > 0)).toBe(true);
        expect(months).toEqual([...months].sort((a, b) => a - b));
        expect([...list.matchAll(/aria-label="Отметить оплату: ([^"]+)"/g)].map(m => m[1])).toEqual([
            '2 000 ₽ · 31 авг.', '4 000 ₽ · 1 окт.', '4 000 ₽ · 10 нояб.',
        ]);
        expect(list).toContain('Всё это уже оплачено? Обнови остаток — брони до сверки снимутся разом.');
        expect(list).toContain('>Обновить<');
    });

    it('занятая бронь — кружок недоступен', () => {
        const busyOne = { busy: (id: string) => id === 'e-2026-08-31-Стрижка', onMark: () => {} };
        const html = render(stand(), { initialOpen: true, initialOverdueOpen: true, overdue: busyOne });
        expect(html).toMatch(/disabled="" aria-label="Отметить оплату: 2 000 ₽ · 31 авг\."|aria-label="Отметить оплату: 2 000 ₽ · 31 авг\." disabled=""/);
        expect(html).not.toMatch(/aria-label="Отметить оплату: 4 000 ₽ · 1 окт\." disabled=""/);
    });

    it('нехватка: итог «Не хватает», метка — цветом нехватки, куска «свободно» нет', () => {
        const html = render(stand({ pocket: -1_600, minPoint: { date: '2026-11-24', balance: -1_600, drivenBy: null } }), { initialOpen: true });
        expect(html).toMatch(/data-row="Не хватает".*?data-mark="short"/);
        expect(html).not.toContain('data-bar="free"');
    });

    it('на 1440 раскрыто сразу, «Кроме этого» — своей колонкой', () => {
        const html = renderToStaticMarkup(<WhyPlate data={stand()} size="wide" onNz={() => {}} onReanchor={() => {}} overdue={noop} />);
        expect(html).toContain('aria-expanded="true"');
        expect(html).toContain('grid-cols-2');
        expect(html.indexOf('Кроме этого')).toBeGreaterThan(html.indexOf('Задать НЗ'));
    });
});
