/**
 * Сторож решения 45: старые экраны берут цвета и шрифт дизайн-системы через свои прежние переменные.
 * Тёмное значение, вернувшееся в одну переменную, не падает — экран просто снова темнеет кусками.
 * Спека: docs/superpowers/specs/2026-11-15-slice1-app-frame-design.md.
 */
import { describe, expect, it } from 'vitest';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const SRC = fileURLToPath(new URL('.', import.meta.url));
const css = readFileSync(join(SRC, 'index.css'), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');

function walk(dir: string): string[] {
    return readdirSync(dir).flatMap((name) => {
        const path = join(dir, name);
        return statSync(path).isDirectory() ? walk(path) : [path];
    });
}

describe('цвета и шрифт старых экранов — из дизайн-системы (решение 45)', () => {
    it('каждая --color-* — ссылка на --sf-*', () => {
        const vars = [...css.matchAll(/(--color-[\w-]+)\s*:\s*([^;]+);/g)];
        expect(vars.length).toBeGreaterThanOrEqual(11);
        for (const [, name, value] of vars) expect(value.trim(), name).toMatch(/^var\(--sf-[\w-]+\)$|^rgba\(47, 107, 85,/);
    });

    it('фон shadcn — бумага, текст — чернила', () => {
        expect(css).toMatch(/--background:\s*40 100% 98%;/);
        expect(css).toMatch(/--foreground:\s*90 11% 15%;/);
    });

    it('страница — бумагой и Geologica, Inter не грузится', () => {
        expect(css).not.toMatch(/Inter/);
        expect(css).toMatch(/body\s*\{[^}]*background-color:\s*var\(--sf-page\)/);
        expect(css).toMatch(/body\s*\{[^}]*font-family:\s*'Geologica'/);
    });

    it('старого фиолетового нет ни в коде, ни в стилях', () => {
        const hits = walk(SRC).filter((p) => /\.(tsx?|css)$/.test(p) && !/\.test\.tsx?$/.test(p))
            .filter((p) => /#6c63ff|#9f8cff|#8f86ff|108,\s*99,\s*255/i.test(readFileSync(p, 'utf8')));
        expect(hits).toEqual([]);
    });
});
