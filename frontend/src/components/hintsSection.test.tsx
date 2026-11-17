import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import type { ReactElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { HintsRow } from './HintsSection';
import type { HintsSettings } from '../types/api';

const render = (settings: HintsSettings | null) => renderToStaticMarkup(<HintsRow settings={settings} onChange={() => {}} />);

describe('«Настройки» — строка «Подсказки» с переключателем (срез 2, п. 12; ANO-252)', () => {
    it('переключатель с именем «Подсказки»; включено — пока подсказки не выключены', () => {
        const html = render({ off: false, closed: ['number'] });
        expect(html).toContain('role="switch"');
        expect(html).toMatch(/<input[^>]*checked/);
        expect(html).toContain('Подсказки');
    });

    it('«Скрыть все подсказки» на «Сегодня» — здесь выключено; не загружено — нажать нельзя', () => {
        expect(render({ off: true, closed: [] })).not.toMatch(/<input[^>]*checked/);
        expect(render(null)).toMatch(/<input[^>]*disabled/);
        expect(render(null)).not.toMatch(/<input[^>]*checked/);
    });

    it('включил — закрытые показываются снова', () => {
        const changes: HintsSettings[] = [];
        const row = HintsRow({ settings: { off: true, closed: ['slope'] }, onChange: s => changes.push(s) }) as ReactElement<{ children: ReactElement[] }>;
        const input = row.props.children[1] as ReactElement<{ onChange: (e: { target: { checked: boolean } }) => void }>;
        input.props.onChange({ target: { checked: true } });
        expect(changes).toEqual([{ off: false, closed: [] }]);
    });

    it('«Сегодня» и «Настройки» читают подсказки после записей в очереди, а не мимо неё (ревью Codex на #228)', () => {
        for (const file of ['../pages/Today.tsx', './HintsSection.tsx']) {
            const src = readFileSync(new URL(file, import.meta.url), 'utf8');
            expect(src, file).toContain('loadHints()');
            expect(src, file).not.toContain('fetchHintsSettings');
        }
    });

    it('стоит в «Настройках» — туда ведёт профиль', () => {
        // Экран читает сеть, статический рендер видит его пустым — сторож по исходнику.
        const settings = readFileSync(new URL('../pages/Settings.tsx', import.meta.url), 'utf8');
        expect(settings).toMatch(/^\s*<HintsSection \/>/m);
    });
});
