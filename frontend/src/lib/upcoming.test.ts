import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { groupUpcoming, sumUpcoming } from './upcoming';
import type { UpcomingItem } from '../types/api';

const item = (over: Partial<UpcomingItem> = {}): UpcomingItem => ({
    id: 'e1', date: '2026-09-21', categoryName: 'Ипотека', amount: 23600,
    description: null, overdue: false, wishlist: false, priority: 'HIGH', ...over,
});

describe('groupUpcoming (ANO-119)', () => {
    it('просроченное уходит в свою группу, а не в даты', () => {
        const out = groupUpcoming([
            item({ id: 'a', date: '2026-09-10', categoryName: 'Коммуналка', overdue: true }),
            item({ id: 'b', date: '2026-09-21' }),
        ]);
        expect(out.overdue.map(i => i.id)).toEqual(['a']);
        expect(out.byDate).toHaveLength(1);
        expect(out.byDate[0].date).toBe('2026-09-21');
    });

    it('строки одного дня складываются в одну группу', () => {
        const out = groupUpcoming([
            item({ id: 'a', date: '2026-09-21' }),
            item({ id: 'b', date: '2026-09-21', categoryName: 'Продукты' }),
            item({ id: 'c', date: '2026-09-28', categoryName: 'Авто' }),
        ]);
        expect(out.byDate.map(g => g.date)).toEqual(['2026-09-21', '2026-09-28']);
        expect(out.byDate[0].items.map(i => i.id)).toEqual(['a', 'b']);
    });

    it('порядок с сервера сохраняется', () => {
        // Движок отдаёт просрочку, затем даты по возрастанию. Пересортировывать здесь —
        // значит завести второе правило порядка, которое однажды разойдётся с первым.
        const out = groupUpcoming([
            item({ id: 'a', date: '2026-09-28' }),
            item({ id: 'b', date: '2026-09-21' }),
        ]);
        expect(out.byDate.map(g => g.date)).toEqual(['2026-09-28', '2026-09-21']);
    });

    it('пустой вход даёт пустые группы, а не падает', () => {
        expect(groupUpcoming([])).toEqual({ overdue: [], byDate: [] });
    });
});

describe('sumUpcoming (ANO-119)', () => {
    it('складывает суммы строк', () => {
        expect(sumUpcoming([item({ amount: 23600 }), item({ amount: 8000 })])).toBe(31600);
    });

    it('пустой список — ноль', () => {
        expect(sumUpcoming([])).toBe(0);
    });
});

describe('дашборд не судит (ANO-119)', () => {
    // Сторож по исходнику: компонентных тестов в проекте нет, а вернуть вердикт —
    // правка в одну строку. Правило 12: предложение да, упрёк нет.
    const verdicts = ['Не выполнено', 'Перерасход', 'Не запланировано', 'В процессе', 'Выполнено'];

    it('на дашборде не осталось слов-вердиктов', () => {
        const src = readFileSync(new URL('../pages/Dashboard.tsx', import.meta.url), 'utf8');
        for (const word of verdicts) expect(src).not.toContain(word);
    });

    it('в списке «осталось потратить» их тоже нет', () => {
        const src = readFileSync(
            new URL('../components/dashboard/UpcomingList.tsx', import.meta.url), 'utf8');
        for (const word of verdicts) expect(src).not.toContain(word);
    });
});

describe('отчёт отклонений не встречает человека сам (ANO-119, ANO-122)', () => {
    // Запрет 2 действует на всех экранах: «Аналитика» раньше открывалась именно этим
    // отчётом, то есть переезд с дашборда в лоб просто перенёс бы нарушение.
    const src = () => readFileSync(new URL('../pages/Analytics.tsx', import.meta.url), 'utf8');

    it('раздел свёрнут по умолчанию', () => {
        expect(src()).toContain('useState(false)');
    });

    it('обе секции отчёта рендерятся только по раскрытию', () => {
        expect(src()).toContain('{showPlanFact && <PlanFactSection');
        expect(src()).toContain('{showPlanFact && <CategoryProgressSection');
    });
});
