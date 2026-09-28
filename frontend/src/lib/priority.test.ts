import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import {
    PRIORITY_DOT_CONFIG, PRIORITY_FIELD_LABEL, PRIORITY_ORDER, WISHLIST_CHARACTER_NOTE, characterLocked,
} from './priority';

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

// ANO-183: строка с экрана «Хотелки» характер не меняет — он у неё всегда «Хотелка» (инвариант I1
// спеки 29.05). Экран предлагал сменить его точкой и в форме; сервер отвечал отказом базы, экран
// молчал или звал «попробовать ещё раз». Теперь не предлагает, а в форме говорит, где о ней решают.
describe('характер строки с экрана «Хотелки» не меняется (ANO-183)', () => {
    it('заперт у строки со статусом хотелки — при любом статусе', () => {
        for (const wishlistStatus of ['OPEN', 'FIXED', 'DISMISSED'] as const) {
            expect(characterLocked({ wishlistStatus }), wishlistStatus).toBe(true);
        }
    });

    it('не заперт у обычной строки — в том числе характера «Хотелка»', () => {
        expect(characterLocked({ wishlistStatus: null })).toBe(false);
        // Ответ сервера до ANO-183 поля не знал.
        expect(characterLocked({})).toBe(false);
    });

    const read = (path: string) => readFileSync(new URL(path, import.meta.url), 'utf8');

    // Сторожа по исходнику: компонентных тестов во фронте нет, а вернуть точке смену характера
    // или форме выбор — правка в одно слово, и правило выше осталось бы зелёным.
    it('журнал даёт точке смену характера только незапертой строке', () => {
        expect(read('../pages/Budget.tsx'))
            .toMatch(/onCycle=\{isPlan && !characterLocked\(event\) \? \(\) => cycleEventPriority\(/);
    });

    it('форма у запертой строки показывает имя и объяснение, выбор — только у незапертой', () => {
        expect(read('../components/EditEventSheet.tsx')).toMatch(
            /characterLocked\(event\) \?[\s\S]*?\{WISHLIST_CHARACTER_NOTE\}[\s\S]*?\) : \([\s\S]*?<Select value=\{priority\}/);
    });

    it('объяснение называет экран так же, как навигация', () => {
        // Имена экранов — из BottomNav.tsx: «Хотелки», а не «Крупные решения».
        expect(read('../components/BottomNav.tsx')).toContain("label: 'Хотелки'");
        expect(WISHLIST_CHARACTER_NOTE).toContain('«Хотелки»');
    });
});
