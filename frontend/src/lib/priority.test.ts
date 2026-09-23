import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { PRIORITY_DOT_CONFIG, PRIORITY_FIELD_LABEL, PRIORITY_ORDER } from './priority';

// Канон — единственный источник имён; экран обязан совпадать с ним, а не наоборот.
const canon = readFileSync(
    new URL('../../../docs/superpowers/specs/2026-09-01-product-rules.md', import.meta.url), 'utf8');
const start = canon.indexOf('## Характер плановой строки');
const section = start < 0 ? '' : canon.slice(start, canon.indexOf('\n## ', start + 1));

describe('характер плановой строки: имена на экране (ANO-173)', () => {
    it('в каноне есть раздел про характер', () => {
        expect(section).not.toBe('');
    });

    it('имена и подпись поля — ровно те, что в каноне', () => {
        expect(PRIORITY_ORDER.map((p) => PRIORITY_DOT_CONFIG[p].name))
            .toEqual(['Бронь', 'Ожидание', 'Хотелка']);
        for (const p of PRIORITY_ORDER) expect(section).toContain(`**${PRIORITY_DOT_CONFIG[p].name}**`);
        expect(section).toContain(`«${PRIORITY_FIELD_LABEL}»`);
    });

    it('у каждого уровня есть множественное число и подсказка', () => {
        // toMatch, а не not.toBe(''): отсутствующее поле — undefined, и сравнение с '' пропустило бы его.
        for (const p of PRIORITY_ORDER) {
            expect(PRIORITY_DOT_CONFIG[p].plural).toMatch(/\S/);
            expect(PRIORITY_DOT_CONFIG[p].hint).toMatch(/\S/);
        }
    });
});
