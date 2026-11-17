import { describe, expect, it } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { StaticRouter } from 'react-router-dom/server';
import { DaySheet } from './DaySheet';
import type { PocketResponse, UpcomingItem } from '../../types/api';

const row = (id: string, description: string): UpcomingItem => ({
    id, date: '2026-11-15', categoryName: 'Связь и подписки', amount: 800, description,
    overdue: false, wishlist: false, priority: 'HIGH', type: 'EXPENSE',
});
const pocket = {
    trajectory: [
        { date: '2026-11-14', balance: 21_100, income: 0, expense: 0, balanceWithForecast: null, items: [] },
        { date: '2026-11-15', balance: 19_900, income: 0, expense: 1_600, balanceWithForecast: null, items: [row('a', 'Связь'), row('b', 'Музыка')] },
    ],
} as unknown as PocketResponse;

const render = (size: 'narrow' | 'wide', xPct?: number) => renderToStaticMarkup(
    <StaticRouter location="/"><DaySheet data={pocket} date="2026-11-15" size={size} xPct={xPct} onDate={() => {}} onClose={() => {}} /></StaticRouter>);

describe('DaySheet', () => {
    it('на телефоне — лист снизу постоянной высоты, «Свернуть», строки, кнопка журнала на этот день', () => {
        const html = render('narrow');
        expect(html).toContain('role="dialog"');
        expect(html).toContain('fixed inset-x-0 bottom-0');
        expect(html).toContain('h-[392px]');
        expect(html).toContain('aria-label="Свернуть"');
        expect(html).toContain('>Музыка<');
        expect(html).toContain('href="/budget?date=2026-11-15"');
        expect(html).toContain('overflow-y-auto');
    });

    it('‹ › — соседние дни; у последнего дня графика «Следующий день» недоступна', () => {
        const html = render('narrow');
        expect(html).toContain('aria-label="Предыдущий день" class=');
        expect(html).toMatch(/aria-label="Следующий день" disabled=""/);
    });

    it('на 1440 — карточка у дня с крестиком; у правого края — слева от дня', () => {
        const left = render('wide', 30);
        expect(left).toContain('w-[340px]');
        expect(left).toContain('left:calc(30% + 14px)');
        expect(left).toContain('aria-label="Закрыть"');
        expect(left).not.toContain('Свернуть');
        expect(render('wide', 80)).toContain('right:calc(20% + 14px)');
    });
});
