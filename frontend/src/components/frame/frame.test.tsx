/**
 * Каркас приложения — срез 1 вехи «Дизайн» (спека 2026-11-15-slice1-app-frame-design.md).
 * Опись: восемь прежних пунктов навигации и «+» не теряются — каждый адрес принадлежит разделу, у раздела с несколькими
 * экранами есть дорожка к каждому, «+» есть в обеих раскладках (решение 27).
 */
import { describe, expect, it } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { StaticRouter } from 'react-router-dom/server';
import { SECTIONS, headerLine, sectionOf, showsHeader } from './sections';
import { FrameHeader, FrameNav, SectionTrack } from './AppFrame';
import type { PocketResponse } from '../../types/api';

const SCREENS = ['/', '/budget', '/analytics', '/funds', '/wishlist', '/strategy', '/capital', '/settings'];

describe('разделы', () => {
    it('четыре раздела в порядке капсулы', () => {
        expect(SECTIONS.map(s => s.label)).toEqual(['Сегодня', 'Журнал', 'Цели', 'Деньги']);
    });

    it('каждый прежний экран, кроме настроек, — в своём разделе', () => {
        expect(SCREENS.map(p => sectionOf(p)?.label ?? null)).toEqual(
            ['Сегодня', 'Журнал', 'Журнал', 'Цели', 'Цели', 'Цели', 'Деньги', null]);
    });

    it('экраны внутри раздела — по решению 25', () => {
        expect(SECTIONS.map(s => s.screens.map(x => `${x.to} ${x.label}`))).toEqual([
            ['/ Сегодня'],
            ['/budget Журнал', '/analytics Итоги месяца'],
            ['/funds Цели', '/wishlist Примерить покупку', '/strategy На годы вперёд'],
            ['/capital Деньги'],
        ]);
    });

    it('шапка — на всех экранах, кроме «Сегодня»', () => {
        expect(SCREENS.filter(showsHeader)).toEqual(SCREENS.slice(1));
    });
});

const pocket = (over: Partial<PocketResponse>): PocketResponse => ({
    pocket: 17000, currentBalance: 48800, buffer: 0, checkpointDate: '2026-11-12',
    horizon: { type: 'INCOME', endDate: '2026-11-25', label: 'до 25 ноября', fallback: false },
    minPoint: { date: '2026-11-24', balance: 17000, drivenBy: null },
    ...over,
} as PocketResponse);

describe('строка шапки', () => {
    it('свободно — до конца срока', () => {
        expect(headerLine(pocket({}))).toEqual({ label: 'Свободно до 25 нояб.', amount: 17000 });
    });

    it('нехватка — те же слова, что у карточки, без минуса', () => {
        expect(headerLine(pocket({ pocket: -3000, minPoint: { date: '2026-11-24', balance: -3000, drivenBy: null } })))
            .toEqual({ label: 'Не хватает до 25 нояб.', amount: 3000 });
    });

    it('ниже НЗ — сколько придётся взять', () => {
        expect(headerLine(pocket({ buffer: 20000 })))
            .toEqual({ label: 'Придётся взять из НЗ до 25 нояб.', amount: 3000 });
    });
});

const render = (path: string, el: React.ReactElement) =>
    renderToStaticMarkup(<StaticRouter location={path}>{el}</StaticRouter>);

describe('навигация', () => {
    const nav = (path: string) => render(path, <FrameNav onAdd={() => {}} />);

    it('капсула и полоса — четыре раздела и «+» в каждой', () => {
        const html = nav('/');
        for (const label of ['Сегодня', 'Журнал', 'Цели', 'Деньги']) {
            expect(html.split(`>${label}<`).length - 1, label).toBe(2);
        }
        expect(html).toContain('aria-label="Записать"');
        expect(html).toContain('>Записать<');
        expect(html).toContain('>Selfin<');
    });

    it('профиль ведёт в настройки', () => {
        const profile = nav('/').match(/<a[^>]*aria-label="Профиль и настройки"[^>]*>/)?.[0];
        expect(profile).toContain('href="/settings"');
    });

    it.each([
        ['/analytics', 'Журнал'],
        ['/strategy', 'Цели'],
        ['/capital', 'Деньги'],
    ])('на %s текущий раздел — %s', (path, label) => {
        const current = [...nav(path).matchAll(/aria-current="page"[^>]*>(?:<[^>]+>)*([^<]+)</g)].map(m => m[1]);
        expect(new Set(current)).toEqual(new Set([label]));
    });

    it('на настройках текущего раздела нет', () => {
        expect(nav('/settings')).not.toContain('aria-current');
    });
});

describe('дорожка экранов раздела', () => {
    const track = (path: string) => render(path, <SectionTrack />);

    it('у «Журнала» — Журнал и Итоги месяца, текущий отмечен', () => {
        const html = track('/analytics');
        expect(html).toContain('href="/budget"');
        expect(html).toMatch(/href="\/analytics"[^>]*aria-current="page"|aria-current="page"[^>]*href="\/analytics"/);
    });

    it('у «Целей» — три экрана', () => {
        const html = track('/funds');
        for (const to of ['/funds', '/wishlist', '/strategy']) expect(html).toContain(`href="${to}"`);
    });

    it('у «Денег» и настроек дорожки нет', () => {
        expect(track('/capital')).not.toContain('aria-label="Экраны раздела"');
        expect(track('/settings')).not.toContain('aria-label="Экраны раздела"');
    });
});

describe('ширина «Настроек» на телефоне (ANO-231)', () => {
    it('поле названия категории не раздвигает страницу', async () => {
        const { readFileSync } = await import('node:fs');
        // С среза 3 (п. 15) поле — Field системы в листе «Новая категория»: его input сжимается сам (w-0 min-w-0).
        const src = readFileSync(new URL('../CategoriesSection.tsx', import.meta.url), 'utf8');
        const ds = readFileSync(new URL('../../ds/index.tsx', import.meta.url), 'utf8');
        expect(src).toMatch(/<Field aria-label="Название категории" placeholder="Название категории"/);
        // Имя категории в строке списка — тоже: «Техника и все для нее» раздвигало раздел до 413 на 390.
        expect(src).toMatch(/<span className="w-0 min-w-0 flex-1">\s*<span className="block truncate text-sf-body">\{c\.name\}/);
        expect(ds).toMatch(/<input ref=\{ref\} aria-invalid=\{invalid \|\| undefined\}\s*className="h-full w-0 min-w-0 flex-1/);
    });
});

describe('шапка', () => {
    const header = (p: PocketResponse | null) => render('/budget', <FrameHeader pocket={p} onReanchor={() => {}} />);

    it('«Свободно» ведёт на «Сегодня», плитка «на картах» — обновить остаток', () => {
        const html = header(pocket({}));
        expect(html).toContain('Свободно до 25 нояб.');
        expect(html).toMatch(/href="\/"[^>]*>/);
        expect(html).toContain('aria-label="На картах 48 800 ₽, обновлён 12 нояб. Обновить"');
    });

    it('без данных — без чисел, но с профилем', () => {
        const html = header(null);
        expect(html).not.toContain('₽');
        expect(html).toContain('aria-label="Профиль и настройки"');
    });
});

describe('после записи — свежие числа (ревью Codex на #225)', () => {
    const read = async (path: string) => (await import('node:fs')).readFileSync(new URL(path, import.meta.url), 'utf8');

    it('остаток из шапки перечитывает и экран — через общий сигнал обновления', async () => {
        expect(await read('./AppFrame.tsx')).toMatch(/<ReanchorSheet[\s\S]*?onSuccess=\{onChanged\}/);
        expect(await read('../../App.tsx')).toMatch(/<AppFrame .*onChanged=\{handleSuccess\}/);
    });

    it('шапка слушает удачные записи любого экрана', async () => {
        expect(await read('./AppFrame.tsx')).toMatch(/window\.addEventListener\(CHANGED, load\)/);
    });
});
