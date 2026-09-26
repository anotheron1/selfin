import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';

/**
 * ANO-123, правило 12: цвет — не приговор. Разница план-факт и дрейф сверки показываются
 * нейтрально: красный «против» и зелёный «в пользу» — оценка, а дрейф — мера учёта, и красный
 * на нём говорит «ты не записал» (правило 5).
 *
 * Сторож по исходнику: компонентных тестов нет, а вернуть цвет — правка в одну строку.
 * Спека: docs/superpowers/specs/2026-09-26-no-verdict-colors-design.md.
 */
const read = (path: string) => readFileSync(new URL(path, import.meta.url), 'utf8');
const VERDICT = /color-(danger|success)/;

/** Кусок исходника от первого маркера до второго — чтобы не задеть законный красный ошибки. */
function span(src: string, from: string, to: string): string {
    const i = src.indexOf(from);
    expect(i, `маркер «${from}» пропал — сторож надо переписать, а не отключить`).toBeGreaterThanOrEqual(0);
    const j = src.indexOf(to, i);
    return src.slice(i, j > i ? j : undefined);
}

describe('цвет — не приговор (ANO-123)', () => {
    it('таблица план-факт «Аналитики»: «Разница» без цвета и без «в пользу»', () => {
        const src = read('../pages/Analytics.tsx');
        expect(src).not.toMatch(VERDICT);
        expect(src).not.toMatch(/deltaColor|favourable/);
        expect(src).toContain('>Разница</th>');
        expect(src).not.toContain('>Δ</th>');
    });

    it('журнал: «факт» под строкой плана — без цвета «в пользу» и «против»', () => {
        // Пункт 6 от 07.09. Перемер 26.09 сначала записал его ушедшим — grep был обрезан
        // head -10, и строка журнала в выдачу не попала; поймала проверка типов.
        const src = read('../pages/Budget.tsx');
        expect(src).not.toMatch(/deltaColor|favourable/);
        expect(span(src, 'planFact != null ?', 'нет факта')).not.toMatch(VERDICT);
    });

    it('дрейф в «Настройках» — нейтральным цветом', () => {
        const src = read('../pages/Settings.tsx');
        expect(span(src, 'Дрейф интервала', 'дрейф {')).not.toMatch(VERDICT);
    });

    it('дрейф в шторке ре-якоря — нейтральным цветом', () => {
        const src = read('../components/pocket/ReanchorSheet.tsx');
        expect(span(src, 'buildDriftPreview(', 'const submit')).not.toMatch(VERDICT);
    });
});
