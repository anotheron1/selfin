import { describe, expect, it } from 'vitest';
import { HELP, NZ_MEANING } from './help';
import { PRIORITY_DOT_CONFIG, PRIORITY_ORDER } from './priority';

/** Весь текст справки одной строкой: заголовок, подзаголовки, абзацы. */
const text = (topic: keyof typeof HELP) => {
    const a = HELP[topic];
    return [a.title, ...a.sections.flatMap(s => [s.heading ?? '', ...s.paragraphs])].join('\n');
};

describe('справка (ANO-186)', () => {
    it('справка журнала определяет каждый характер строки под тем именем, что на экране (требование 2.5)', () => {
        // Имена живут в lib/priority.ts; переименуют там — справка обязана заговорить так же.
        // Мало, чтобы имя где-то встречалось: «Ожидание» мелькает и в разделе про кармашек.
        // Нужно определение — абзац «Имя — …».
        const paragraphs = HELP.journal.sections.flatMap(s => s.paragraphs);
        const undefinedNames = PRIORITY_ORDER.map(p => PRIORITY_DOT_CONFIG[p].name)
            .filter(n => !paragraphs.some(p => p.startsWith(`${n} — `)));
        expect(undefinedNames, 'характеры без определения в справке журнала').toEqual([]);
    });

    it('смысл НЗ один: справка кармашка говорит ровно то, что форма НЗ', () => {
        expect(text('pocket')).toContain(NZ_MEANING);
    });
});
