import { describe, expect, it } from 'vitest';
import { readdirSync, readFileSync } from 'node:fs';

/**
 * Сторож по исходнику «Сегодня» (срез 2, задача 1.9). Компонент экрана читает сеть, и статический рендер видит
 * только «Загрузка…»; а вернуть справку, «Примерить» или список строк — правка в одну строку.
 */
const read = (p: string) => readFileSync(new URL(p, import.meta.url), 'utf8');
const today = read('./Today.tsx');
const parts = readdirSync(new URL('../components/today/', import.meta.url))
    .filter(f => f.endsWith('.tsx') && !f.includes('.test.'))
    .map(f => [f, read(`../components/today/${f}`)] as const);
const all = [['Today.tsx', today] as const, ...parts];

describe('«Сегодня» — только то, что решено (решения 28, 32, 35; ANO-189)', () => {
    it('справки нет: ни HelpDialog, ни «Как это считается» — подача на самом экране', () => {
        for (const [f, src] of all) {
            expect(src, f).not.toContain('HelpDialog');
            expect(src, f).not.toContain('Как это считается');
        }
    });

    it('«Примерить» — только в «Целях»', () => {
        for (const [f, src] of all) expect(src, f).not.toContain('Примерить');
    });

    it('списков плана и записей нет: строки впереди — в «Журнале», запись — «+»; прежних блоков нет', () => {
        expect(today).not.toMatch(/fetchEvents|UpcomingList|PocketTrajectoryChart|PocketCard|todayEvents/);
        expect(today).not.toContain('Добавь первую трату');
    });

    it('без вердиктов (ANO-119, правило 12)', () => {
        const verdicts = ['Не выполнено', 'Перерасход', 'Не запланировано', 'В процессе', 'Выполнено'];
        for (const [f, src] of all) for (const w of verdicts) expect(src, `${f}: ${w}`).not.toContain(w);
    });

    it('«Прогноза конца дня» нет (Р6, ANO-216)', () => {
        for (const [f, src] of all) expect(src, f).not.toContain('Прогноз конца дня');
    });

    it('без красного и «!» у разрыва: цвет не приговор (правило 12)', () => {
        for (const [f, src] of all) {
            expect(src, f).not.toMatch(/danger|destructive|AlertTriangle|Кассовый разрыв!/);
        }
    });
});
