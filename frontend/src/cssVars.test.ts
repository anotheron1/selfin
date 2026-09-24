/**
 * Сторож: каждая CSS-переменная, которую читает код, определена в стилях (ANO-172).
 *
 * Неопределённая переменная не падает и ничего не пишет в консоль: браузер молча делает
 * фон прозрачным, а рамку — пустой. Так полоса факта на «Аналитике» рисовалась ничем,
 * а включённый тумблер примерки — пустым, с марта 2026, и ни один тест этого не видел.
 *
 * Спека: docs/superpowers/specs/2026-09-24-plan-fact-readable-design.md
 */
import { describe, expect, it } from 'vitest';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const SRC = fileURLToPath(new URL('.', import.meta.url));

/** Переменные, которые выставляет не наш CSS, а библиотека во время работы. Каждая — с причиной. */
const RUNTIME: { prefix: string; why: string }[] = [
    { prefix: '--radix-', why: 'Radix пишет размеры триггера на элемент выпадающего списка сам' },
];

function walk(dir: string): string[] {
    return readdirSync(dir).flatMap((name) => {
        const path = join(dir, name);
        return statSync(path).isDirectory() ? walk(path) : [path];
    });
}

const files = walk(SRC);
const code = files.filter((p) => /\.(tsx?|css)$/.test(p) && !/\.test\.tsx?$/.test(p) && !p.endsWith('.d.ts'));
const styles = files.filter((p) => p.endsWith('.css'));

const defined = new Set(styles.flatMap((p) =>
    [...readFileSync(p, 'utf8').matchAll(/(--[A-Za-z0-9-]+)\s*:/g)].map((m) => m[1])));

const used = code.flatMap((p) =>
    [...readFileSync(p, 'utf8').matchAll(/var\(\s*(--[A-Za-z0-9-]+)/g)].map((m) => ({
        file: relative(SRC, p).split(sep).join('/'),
        name: m[1],
    })));

describe('CSS-переменные из кода определены в стилях (ANO-172)', () => {
    it('сторож видит и определения, и использования — иначе он проверял бы пустоту', () => {
        expect(defined.has('--color-accent')).toBe(true);
        expect(used.length).toBeGreaterThan(100);
    });

    it('нет ни одной неопределённой', () => {
        const missing = used
            .filter((u) => !defined.has(u.name) && !RUNTIME.some((r) => u.name.startsWith(r.prefix)))
            .map((u) => `  ${u.file}: ${u.name}`);
        expect(missing, `не определены в src/*.css:\n${[...new Set(missing)].join('\n')}`).toEqual([]);
    });
});
