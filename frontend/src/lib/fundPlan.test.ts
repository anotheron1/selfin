import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { inPlanFirst, outOfPlanNote, paceLine } from './fundPlan';
import type { WishlistStatus } from '../types/api';

const f = (name: string, wishlistStatus: WishlistStatus | null) => ({ name, wishlistStatus });

describe('копилка вне плана на «Целях» (Р8, ANO-218)', () => {
    it('обсуждаемая и отложенная — с пометкой «вне плана»', () => {
        expect(outOfPlanNote(f('кредит', 'OPEN'))).toBe('обсуждается — вне плана');
        expect(outOfPlanNote(f('Египет', 'DISMISSED'))).toBe('отложена — вне плана');
    });

    it('зафиксированная и обычная копилка — без пометки: их взносы ядро держит', () => {
        expect(outOfPlanNote(f('Ноутбук', 'FIXED'))).toBeNull();
        expect(outOfPlanNote(f('Подушка', null))).toBeNull();
    });

    it('вне плана — в конце; порядок внутри обеих групп прежний', () => {
        const funds = [
            f('Египет', 'DISMISSED'), f('Подушка', null), f('кредит', 'OPEN'),
            f('Ноутбук', 'FIXED'), f('Машина', 'DISMISSED'), f('Отпуск', null),
        ];
        expect(inPlanFirst(funds).map(x => x.name))
            .toEqual(['Подушка', 'Ноутбук', 'Отпуск', 'Египет', 'кредит', 'Машина']);
        expect(funds[0].name).toBe('Египет'); // ответ сервера не переставлен на месте
    });
});

describe('темп копилки — не второй срок (Р9, ANO-219)', () => {
    it('месяц в дательном падеже и год', () => {
        expect(paceLine('2027-04-11')).toBe('В нынешнем темпе — к апрелю 2027');
        expect(paceLine('2026-05-01')).toBe('В нынешнем темпе — к маю 2026');
    });

    it('все двенадцать месяцев — дательный, без «г.» и без часового пояса', () => {
        const months = Array.from({ length: 12 }, (_, i) =>
            paceLine(`2027-${String(i + 1).padStart(2, '0')}-01`).replace('В нынешнем темпе — к ', ''));
        expect(months).toEqual([
            'январю 2027', 'февралю 2027', 'марту 2027', 'апрелю 2027', 'маю 2027', 'июню 2027',
            'июлю 2027', 'августу 2027', 'сентябрю 2027', 'октябрю 2027', 'ноябрю 2027', 'декабрю 2027',
        ]);
    });
});

describe('экран «Целей» берёт порядок и пометку из fundPlan (Р8)', () => {
    // Компонентных тестов в проекте нет — сторож читает исходник.
    const src = readFileSync(new URL('../pages/Funds.tsx', import.meta.url), 'utf8');

    it('список — в порядке inPlanFirst, а не как пришёл', () => {
        expect(src).toContain('inPlanFirst(data.funds).map(fund =>');
        expect(src).not.toContain('data.funds.map(');
    });

    it('карточка пишет пометку из outOfPlanNote', () => {
        expect(src).toMatch(/const note = outOfPlanNote\(fund\)/);
        expect(src).toMatch(/\{note && \(/);
    });

    it('под «Сроком» — темп из paceLine, слова «Прогноз» нет (Р9)', () => {
        expect(src).toContain('{paceLine(fund.estimatedCompletionDate)}');
        expect(src).not.toContain('Прогноз');
    });
});
