/**
 * Сторож дизайн-системы (веха «Дизайн», этап 3; решение 36 — два набора цветов с самого начала).
 *
 * Токен, забытый в ночном наборе, не падает: браузер берёт светлое значение с :root, и ночью на экране
 * светлое пятно. Ссылка на неопределённую переменную рисует ничем (ANO-172). Ни то ни другое не видно,
 * пока ночную тему не включат, — поэтому сторожит тест, а не глаз.
 */
import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { renderToStaticMarkup } from 'react-dom/server';
import { sfTheme } from './theme';
import { buttonVariants, Choices, Field, Flag, IconButton, KindMark } from './index';
import { cn } from '@/lib/utils';

const css = readFileSync(new URL('./tokens.css', import.meta.url), 'utf8')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/@import[^;]+;/g, '');

const blocks = new Map([...css.matchAll(/([^{}]+)\{([^}]*)\}/g)].map((m) => [
    m[1].replace(/\s+/g, ' ').trim(),
    new Map([...m[2].matchAll(/(--sf-[\w-]+)\s*:\s*([^;]+);/g)].map((d) => [d[1], d[2].trim()])),
]));
const light = blocks.get(':root, [data-theme="light"]')!;
const dark = blocks.get('[data-theme="dark"]')!;
const shared = blocks.get(':root')!;

const COLOR = /^(#|rgba?\()/;
const refs = (s: string) => [...s.matchAll(/var\((--sf-[\w-]+)\)/g)].map((m) => m[1]);

/** Значение токена в теме, со ссылками var(--sf-…) внутри своей темы. */
function resolve(theme: Map<string, string>, name: string): string {
    const value = theme.get(name) ?? shared.get(name);
    expect(value, `${name} не определён`).toBeDefined();
    const alias = /^var\((--sf-[\w-]+)\)$/.exec(value!);
    return alias ? resolve(theme, alias[1]) : value!;
}

function contrast(a: string, b: string): number {
    const lum = (hex: string) => [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255)
        .map((c) => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4))
        .reduce((sum, c, i) => sum + c * [0.2126, 0.7152, 0.0722][i], 0);
    const [hi, lo] = [lum(a), lum(b)].sort((x, y) => y - x);
    return (hi + 0.05) / (lo + 0.05);
}

describe('токены: две темы одного состава', () => {
    it('сторож видит оба набора и размеры — иначе он сравнивал бы пустоту', () => {
        expect(light.size).toBeGreaterThan(50);
        expect(shared.has('--sf-radius-pill')).toBe(true);
    });

    it('в ночном наборе те же токены, что в светлом, — ни одного забытого и ни одного лишнего', () => {
        expect([...dark.keys()].sort()).toEqual([...light.keys()].sort());
    });

    it('цвета — только в наборах тем: цвет в общем блоке ночью не сменится', () => {
        expect([...shared].filter(([, v]) => COLOR.test(v)).map(([k]) => k)).toEqual([]);
    });

    it('каждая ссылка var(--sf-…) — в токенах и в теме Tailwind — ведёт на определённый токен', () => {
        const missing = [
            ...[light, dark, shared].flatMap((theme) => [...theme.values()].flatMap(refs)
                .filter((r) => !theme.has(r) && !shared.has(r))),
            ...refs(JSON.stringify(sfTheme)).filter((r) => !light.has(r) && !shared.has(r)),
        ];
        expect(missing).toEqual([]);
    });
});

describe('текст читается в обеих темах (WCAG 2)', () => {
    // [цвет, фон, порог]: 4,5 — текст; 3 — значки и кольцо фокуса.
    const PAIRS: [string, string, number][] = [
        ...['page', 'plate', 'chip', 'chip-press', 'switch-on', 'capsule'].map((bg): [string, string, number] => ['ink', bg, 4.5]),
        ...['page', 'plate', 'chip'].map((bg): [string, string, number] => ['muted', bg, 4.5]),
        ...['page', 'plate', 'chip', 'accent-soft', 'accent-soft-hover'].map((bg): [string, string, number] => ['accent', bg, 4.5]),
        ['accent-press', 'accent-soft-press', 4.5],
        ...['accent', 'accent-hover', 'accent-press'].map((bg): [string, string, number] => ['on-accent', bg, 4.5]),
        ['error', 'page', 4.5], ['error', 'plate', 4.5],
        ['kind-bron', 'kind-bron-bg', 3], ['kind-ozh', 'kind-ozh-bg', 3], ['kind-hot', 'kind-hot-bg', 3],
    ];
    for (const [name, theme] of [['светлая', light], ['ночная', dark]] as const) {
        it(name, () => {
            const low = PAIRS.map(([fg, bg, min]) => ({ pair: `${fg} на ${bg}`, ratio: contrast(resolve(theme, `--sf-${fg}`), resolve(theme, `--sf-${bg}`)), min }))
                .filter((p) => p.ratio < p.min)
                .map((p) => `${p.pair}: ${p.ratio.toFixed(2)} < ${p.min}`);
            expect(low).toEqual([]);
        });
    }
});

describe('cn знает классы дизайн-системы', () => {
    it('размер текста и цвет текста — разные свойства: оба остаются', () => {
        expect(cn('text-sf-note', 'text-sf-ink')).toBe('text-sf-note text-sf-ink');
    });

    it('спорящие классы sf уступают последнему, как обычные', () => {
        expect(cn('text-sf-note', 'text-sf-body')).toBe('text-sf-body');
        expect(cn('rounded-sf-pill', 'rounded-none')).toBe('rounded-none');
        expect(cn('h-sf-tile', 'h-10')).toBe('h-10');
        expect(cn('shadow-sf-raised', 'shadow-none')).toBe('shadow-none');
    });

    it('у текстовой кнопки свой узкий отступ перекрывает отступ размера — и на ссылке тоже', () => {
        const classes = buttonVariants({ variant: 'text', size: 'm' }).split(' ');
        expect(classes).toContain('px-0.5');
        expect(classes).not.toContain('px-5');
    });
});

describe('детали называют себя для экранного диктора', () => {
    it('выбор — родные radio в именованной группе, выбранное отмечено', () => {
        const html = renderToStaticMarkup(
            <Choices name="kind" label="Вид" value="ozh" onChange={() => {}}
                options={[{ value: 'bron', label: 'Бронь' }, { value: 'ozh', label: 'Ожидание' }]} />,
        );
        expect(html).toContain('role="radiogroup" aria-label="Вид"');
        const radios = html.match(/<input[^>]*type="radio"[^>]*>/g) ?? [];
        expect(radios.map((r) => [/value="(\w+)"/.exec(r)?.[1], r.includes('checked=""')]))
            .toEqual([['bron', false], ['ozh', true]]);
    });

    it('галочка — родной checkbox; кнопка-значок — с именем; поле с ошибкой — aria-invalid', () => {
        expect(renderToStaticMarkup(<Flag>В кредит</Flag>)).toContain('type="checkbox"');
        expect(renderToStaticMarkup(<IconButton aria-label="Закрыть">x</IconButton>)).toContain('aria-label="Закрыть"');
        expect(renderToStaticMarkup(<Field aria-label="Сумма" invalid />)).toContain('aria-invalid="true"');
    });

    it('кружок плана — кнопка смены вида, кружок записи — только картинка', () => {
        expect(renderToStaticMarkup(<KindMark kind="hot" icon="·" onClick={() => {}} />)).toContain('aria-label="Вид: хотелка. Нажми — сменить"');
        expect(renderToStaticMarkup(<KindMark kind="hot" icon="·" fact />)).toContain('aria-hidden="true"');
    });
});
