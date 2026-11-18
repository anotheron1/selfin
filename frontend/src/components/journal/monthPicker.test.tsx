/**
 * Месяц и год журнала (срез 3, п. 7, ANO-257; доски S3-M1, S3-W5): стрелки — соседний месяц; нажатие на месяц —
 * сетка года со стрелками года, точка — в месяце со строками.
 */
import { describe, expect, it } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import MonthPicker, { MonthGrid, monthTitle, stepMonth } from './MonthPicker';

const text = (html: string) => html.replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim();

describe('строка месяца', () => {
    it('стрелки и месяц с годом; сетка закрыта', () => {
        const html = renderToStaticMarkup(
            <MonthPicker year={2026} month={10} onChange={() => {}} loadMonths={async () => []} />);
        expect(text(html)).toBe('Ноябрь 2026');
        expect(html).toContain('aria-label="Предыдущий месяц"');
        expect(html).toContain('aria-label="Следующий месяц"');
        expect(html).toContain('aria-expanded="false"');
        expect(html).not.toContain('role="dialog"');
    });

    it('стрелка — соседний месяц, через границу года', () => {
        expect(stepMonth(2026, 0, -1)).toEqual({ year: 2025, month: 11 });
        expect(stepMonth(2026, 11, 1)).toEqual({ year: 2027, month: 0 });
        expect(stepMonth(2026, 5, 1)).toEqual({ year: 2026, month: 6 });
        expect(monthTitle(2027, 0)).toBe('Январь 2027');
    });
});

describe('сетка года', () => {
    const grid = (selected: number | null, dots: number[]) => renderToStaticMarkup(
        <MonthGrid year={2026} selected={selected} dots={dots} onYear={() => {}} onPick={() => {}} />);

    it('год со стрелками, двенадцать месяцев, подпись о точке', () => {
        const html = grid(10, []);
        expect(text(html)).toBe(
            '2026 Янв. Февр. Март Апр. Май Июнь Июль Авг. Сент. Окт. Нояб. Дек. точка — в месяце есть записи');
        expect(html).toContain('aria-label="Предыдущий год"');
        expect(html).toContain('aria-label="Следующий год"');
    });

    it('выбранный месяц отмечен; в другом году — ни один', () => {
        expect(grid(10, []).match(/aria-pressed="true"/g)).toHaveLength(1);
        expect(grid(10, [])).toMatch(/aria-pressed="true"[^>]*>Нояб\./);
        expect(grid(null, [])).not.toContain('aria-pressed="true"');
    });

    it('точка — у месяцев со строками, и только у них', () => {
        const html = grid(10, [1, 2, 12]);
        expect(html.match(/data-dot/g)).toHaveLength(3);
        expect(html).toMatch(/Янв\.<span[^>]*data-dot/);
        expect(html).toMatch(/Дек\.<span[^>]*data-dot/);
        expect(html).not.toMatch(/Март<span[^>]*data-dot/);
    });
});
