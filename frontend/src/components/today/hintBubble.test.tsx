import { describe, expect, it } from 'vitest';
import { isValidElement, type ReactElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { DayHint, HintBubble } from './HintBubble';
import type { HintId } from '../../lib/hints';
import type { HintsSettings } from '../../types/api';

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

describe('DayHint — подсказка на своём месте', () => {
    const ON: HintsSettings = { off: false, closed: ['number'] };
    const changes: HintsSettings[] = [];
    /** Компонент как функция: нажатия статический рендер не видит, а обработчики — свойства пузыря. */
    const hint = (id: HintId, shown: HintId[], settings: HintsSettings | null = ON) =>
        DayHint({ id, shown, settings, onChange: s => changes.push(s), text: id, arrow: 'up', arrowClass: '' }) as
            ReactElement<{ onClose: () => void; onHideAll?: () => void }> | null;

    it('не среди видимых или настройки не пришли — пузыря нет', () => {
        expect(hint('number', ['slope', 'plus'])).toBeNull();
        expect(hint('slope', ['slope', 'plus'], null)).toBeNull();
        expect(isValidElement(hint('slope', ['slope', 'plus']))).toBe(true);
    });

    it('«Скрыть все подсказки» — у первой видимой, а не у первой по счёту', () => {
        expect(hint('slope', ['slope', 'plus'])!.props.onHideAll).toBeTypeOf('function');
        expect(hint('plus', ['slope', 'plus'])!.props.onHideAll).toBeUndefined();
    });

    it('крестик закрывает её одну; «Скрыть все» выключает все', () => {
        changes.length = 0;
        hint('plus', ['slope', 'plus'])!.props.onClose();
        hint('slope', ['slope', 'plus'])!.props.onHideAll!();
        expect(changes).toEqual([{ off: false, closed: ['number', 'plus'] }, { off: true, closed: ['number'] }]);
    });
});
