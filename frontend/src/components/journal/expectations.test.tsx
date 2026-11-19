/**
 * Ожидания журнала (срез 3, пп. 10–11; доски Zh2-J8, S3-X1, S3-X4, S3-X5, S3-W1d): кружки, лист и сетка недель.
 * «Записано» — без «осталось», без цвета превышения и без полосы: граница по запрету 1.
 */
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import type { FinancialEvent } from '../../types/api';
import { expectationGroups, journalView } from '../../lib/journalSections';
import { weekGrid } from '../../lib/weekGrid';
import ExpectationCircles from './ExpectationCircles';
import ExpectationSheet from './ExpectationSheet';
import WeekGrid from './WeekGrid';
import JournalList from './JournalList';

const ev = (over: Partial<FinancialEvent>): FinancialEvent => ({
    id: 'e', date: '2026-11-09', categoryId: 'food', categoryName: 'Продукты', type: 'EXPENSE', plannedAmount: 8000,
    factAmount: null, status: 'PLANNED', priority: 'MEDIUM', description: null, rawInput: null,
    createdAt: '2026-11-01T09:00:00', eventKind: 'PLAN', parentEventId: null, linkedFactsCount: 0,
    linkedFactsAmount: null, parentPlanDescription: null, ...over,
});

// Ноябрь 2026, сегодня — чт 12 нояб. Продукты неделей по 8 000; прошлая неделя записана сверх строки.
const TODAY = '2026-11-12';
const EVENTS = [
    ev({ id: 'w0', date: '2026-11-02', linkedFactsCount: 1, linkedFactsAmount: 8650 }),
    ev({ id: 'w1', date: '2026-11-09', linkedFactsCount: 2, linkedFactsAmount: 2440 }),
    ev({ id: 'w2', date: '2026-11-16' }),
    ev({ id: 'w3', date: '2026-11-23' }),
    ev({ id: 'w4', date: '2026-11-30' }),
    ev({ id: 'car', date: '2026-11-20', categoryId: 'car', categoryName: 'Транспорт', plannedAmount: 15000 }),
    ev({ id: 'p', eventKind: 'FACT', date: '2026-11-12', parentEventId: 'w1', plannedAmount: null, factAmount: 1240, description: 'Пятёрочка' }),
    ev({ id: 'q', eventKind: 'FACT', date: '2026-11-11', parentEventId: 'w1', plannedAmount: null, factAmount: 1200 }),
    ev({ id: 'old', eventKind: 'FACT', date: '2026-11-03', parentEventId: 'w0', plannedAmount: null, factAmount: 8650 }),
];
const groups = expectationGroups(EVENTS, 2026, 10, TODAY);
const text = (html: string) => html.replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').replace(/ /g, ' ');
/** Ни «осталось», ни цвета ошибки, ни полосы: в разметке нет слов и классов приговора. */
const VERDICT = /осталось|превыш|text-sf-error|destructive|progress|red-|width:\s*\d+%/i;

describe('кружки ожиданий — 390 (Zh2-J8)', () => {
    const html = renderToStaticMarkup(<ExpectationCircles groups={groups} onRecord={() => {}} onOpen={() => {}} />);

    it('кружок с «+» — запись; подпись — имя и сумма, она открывает лист', () => {
        expect(html).toContain('aria-label="Записать: Продукты"');
        expect(text(html)).toContain('Продукты 8 000 ₽ · неделя');
        expect(text(html)).toContain('Транспорт ≈ 15 000 ₽');
        expect(text(html)).toContain('Нажми на кружок — запись к ожиданию, на подпись — строки по датам.');
    });

    it('нет цвета превышения и полосы', () => {
        expect(html).not.toMatch(VERDICT);
    });
});

describe('лист ожидания — 390 (S3-X4, S3-X5)', () => {
    const sheet = (i: number) => renderToStaticMarkup(
        <ExpectationSheet group={groups[i]} onClose={() => {}} onEdit={() => {}} onRecord={() => {}} />);

    it('неделей: открыта эта неделя — «записано» и записи к ней; прошедшая — приглушена, с «записано»', () => {
        const html = sheet(0);
        const t = text(html);
        expect(t).toContain('ожидание · каждый понедельник по 8 000 ₽');
        expect(t).toContain('Эта неделя, 9–15 нояб. 8 000 ₽ записано 2 440 ₽');
        expect(t).toContain('Пятёрочка · сегодня 1 240 ₽');
        expect(t).toContain('Продукты · вчера 1 200 ₽');
        expect(html).toMatch(/text-sf-muted[^"]*"[^>]*>\s*<span>2–8 нояб\., прошла<\/span><span[^>]*>8 000 ₽ · записано 8 650 ₽/);
        expect(t).toContain('Изменить ожидание');
        expect(t).toContain('Записать');
    });

    it('разовое — одной строкой с датой', () => {
        const t = text(sheet(1));
        expect(t).toContain('20 нояб., пт 15 000 ₽');
        expect(t).toContain('встают к этой строке, даже если пришлись раньше 20-го');
    });

    it('нет цвета превышения и полосы', () => {
        expect(sheet(0) + sheet(1)).not.toMatch(VERDICT);
    });
});

describe('сетка недель — 1440 (S3-W1d)', () => {
    const grid = weekGrid(groups, 2026, 10, TODAY);
    const html = renderToStaticMarkup(
        <WeekGrid grid={grid} selected={grid.selected} onSelect={() => {}} onRecord={() => {}} onEdit={() => {}} />);

    it('столбцы с понедельника, текущая — «эта»; клетки — записанное и сумма впереди', () => {
        const t = text(html);
        expect(t).toContain('2–8 эта 9–15 16–22 23–29 30–6');
        expect(t).toContain('Продукты 8 000 ₽ · неделя 8 650 2 440 8 000 8 000 8 000');
    });

    it('нажата клетка этой недели первого ожидания: под сеткой её записи, они подсвечены в списке', () => {
        expect(html).toContain('aria-pressed="true" aria-label="Продукты, 9–15 нояб."');
        expect(text(html)).toContain('Продукты · 9–15 нояб. 8 000 ₽ записано 2 440 ₽ · эти записи подсвечены в списке');
    });

    it('нет цвета превышения и полосы', () => {
        expect(html).not.toMatch(VERDICT);
    });

    it('записи нажатой клетки подсвечены в списке фоном — только на 1440', () => {
        const list = renderToStaticMarkup(
            <JournalList view={journalView(EVENTS, 2026, 10, TODAY)} allowedToday={TODAY} anchorDate={null} highlight={new Set(['p'])}
                busy={() => false} failed={() => false} stale={() => false} handlers={{
                    onRow: () => {}, onPaid: () => {}, onOwn: () => {}, onCycle: () => {}, onRefresh: () => {},
                }} />);
        const rows = list.match(/<div data-group="[^"]*"[^>]*>/g) ?? [];
        expect(rows.filter((r) => r.includes('lg:bg-sf-accent-soft'))).toHaveLength(1);
        expect(list).toMatch(/data-group="w1"[^>]*lg:bg-sf-accent-soft[^>]*>(?:(?!data-group).)*Пятёрочка/);
    });
});

describe('журнал: старые карточки ожиданий ушли, их место заняли кружки и сетка', () => {
    const page = readFileSync(resolve(__dirname, '../../pages/Budget.tsx'), 'utf8');

    it('страница рисует кружки, лист и сетку, а не ExpectationCards', () => {
        expect(page).not.toMatch(/ExpectationCards|expectationCards|expectationsSummary/);
        expect(page).toMatch(/<ExpectationCircles/);
        expect(page).toMatch(/<ExpectationSheet/);
        expect(page).toMatch(/<WeekGrid/);
    });
});
