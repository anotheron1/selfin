import { describe, expect, it } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { HintBubble } from './HintBubble';

const render = (hideAll: boolean) => renderToStaticMarkup(
    <HintBubble text="Склон — сколько останется по дням." arrow="up" arrowClass="left-10" onClose={() => {}}
        onHideAll={hideAll ? () => {} : undefined} />,
);

describe('HintBubble — пузырь подсказки (S2-P1)', () => {
    it('текст и крестик у каждой', () => {
        const html = render(false);
        expect(html).toContain('role="note"');
        expect(html).toContain('Склон — сколько останется по дням.');
        expect(html).toContain('aria-label="Закрыть подсказку"');
    });

    it('«Скрыть все подсказки» — только у первой', () => {
        expect(render(true)).toContain('Скрыть все подсказки');
        expect(render(false)).not.toContain('Скрыть все подсказки');
    });
});
