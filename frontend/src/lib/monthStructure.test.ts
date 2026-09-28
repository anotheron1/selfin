import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { monthStructureRows } from './monthStructure';
import { PRIORITY_DOT_CONFIG, PRIORITY_ORDER } from './priority';

// ANO-165, ANO-161. «Структура месяца» показывала «сэкономил 61 600 ₽» по неоплаченным броням,
// «5 000 ₽ · 500000% бюджета» (доля от дохода, которого ещё нет, — делитель подменён на 1),
// «Перерасход» и «Хотелки · 0 из 5 выполнена» — счётчик по пяти строкам «Хотелка» прошлых месяцев,
// а не по хотелкам с экрана «Хотелки». Вариант А владельца 28.09: только числа — факт и план.
// Числа — замер стенда 28.09, `priorityBreakdown` ответа /analytics/report.
const breakdown = {
    highFact: 0, highPlanned: 61600,
    mediumFact: 5000, mediumPlanned: 20500,
    lowFact: 0, lowPlanned: 77004,
    totalIncomeFact: 0,
};

const read = (path: string) => readFileSync(new URL(path, import.meta.url), 'utf8');

describe('«Структура месяца» — только числа (ANO-165)', () => {
    it('три строки в порядке характеров, имена — из lib/priority.ts', () => {
        expect(monthStructureRows(breakdown).map(r => r.name))
            .toEqual(PRIORITY_ORDER.map(p => PRIORITY_DOT_CONFIG[p].plural));
    });

    it('факт и план — как в ответе сервера', () => {
        expect(monthStructureRows(breakdown).map(r => [r.priority, r.fact, r.plan])).toEqual([
            ['HIGH', 0, 61600],
            ['MEDIUM', 5000, 20500],
            ['LOW', 0, 77004],
        ]);
    });

    it('ничего сверх: ни процентов, ни оценок — только характер, имя, факт и план', () => {
        for (const row of monthStructureRows(breakdown)) {
            expect(Object.keys(row).sort()).toEqual(['fact', 'name', 'plan', 'priority']);
        }
    });

    // Сторожа по исходнику: компонентных тестов во фронте нет, а вернуть процент или карточку
    // хотелок — правка в несколько строк, и правило выше осталось бы зелёным.
    it('блок строит строки правилом и не показывает ни процентов, ни хотелок', () => {
        const block = read('../components/BudgetStructureSection.tsx');
        expect(block).toMatch(/monthStructureRows\(breakdown\)/);
        // Процент в этом блоке — всегда доля от чего-то, то есть оценка: «% бюджета» был от дохода.
        expect(block).not.toMatch(/%/);
        expect(block).not.toMatch(/wishlistItems|WishlistDot/);
    });

    it('«Аналитика» не зовёт старую выборку хотелок (ANO-161)', () => {
        expect(read('../pages/Analytics.tsx')).not.toMatch(/fetchWishlist/);
        expect(read('../api/index.ts')).not.toMatch(/fetchWishlist\b/);
    });
});
