import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { renderToStaticMarkup } from 'react-dom/server';
import { HintsRow } from './HintsSection';

const render = (on: boolean | null) => renderToStaticMarkup(<HintsRow on={on} onChange={() => {}} />);

describe('«Настройки» — строка «Подсказки» с переключателем (срез 2, п. 12; ANO-252)', () => {
    it('переключатель с именем «Подсказки»', () => {
        const html = render(true);
        expect(html).toContain('role="switch"');
        expect(html).toMatch(/<input[^>]*checked/);
        expect(html).toContain('Подсказки');
    });

    it('стоит в «Настройках» — туда ведёт профиль', () => {
        // Экран читает сеть, статический рендер видит его пустым — сторож по исходнику.
        const settings = readFileSync(new URL('../pages/Settings.tsx', import.meta.url), 'utf8');
        expect(settings).toMatch(/^\s*<HintsSection \/>/m);
    });

    it('выключено — без отметки; не загружено — нажать нельзя', () => {
        expect(render(false)).not.toMatch(/<input[^>]*checked/);
        expect(render(null)).toMatch(/<input[^>]*disabled/);
    });
});
