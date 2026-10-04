import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { inPlanFirst, outOfPlanNote } from './fundPlan';
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
});
