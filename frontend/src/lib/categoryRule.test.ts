/**
 * Сторож правила категории (срез 3, п. 15, ANO-180; правила продукта 5 и 12): текст над «Новая категория» и лист
 * «Как делить категории» говорят, как делить, а не судят, как человек завёл. Текст — одна константа: в других
 * файлах его нет, экран берёт его отсюда.
 */
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { CATEGORY_RULE } from './categoryRule';

const SRC = fileURLToPath(new URL('..', import.meta.url));
const source = readFileSync(new URL('./categoryRule.ts', import.meta.url), 'utf8');
/** Только строки: комментарии слово-приговор назвать могут. */
const strings = (src: string) => [...src.replace(/\/\*[\s\S]*?\*\/|\/\/.*$/gm, '').matchAll(/'([^']*)'/g)].map(m => m[1]).join(' ');

/** Основы слов-приговоров: оценка того, как человек завёл категории, а не подсказка, как делить. */
const VERDICTS = [
    'неправильн', 'неверн', 'ошиб', 'лишн', 'зря', 'плох', 'нельзя', 'должн', 'обязан', 'не надо', 'не стоит',
    'бессмысл', 'мусор', 'беспоряд', 'хаос', 'не умеешь', 'запута',
];

const walk = (dir: string): string[] => readdirSync(dir).flatMap(n => {
    const p = join(dir, n);
    return statSync(p).isDirectory() ? walk(p) : /\.tsx?$/.test(n) && !/\.test\.tsx?$/.test(n) ? [p] : [];
});

describe('правило категории', () => {
    it('без слов-приговоров', () => {
        const text = strings(source).toLowerCase();
        expect(text.length).toBeGreaterThan(200);
        for (const v of VERDICTS) expect(text, v).not.toContain(v);
    });

    it('текст правила — одна константа: на экран идёт из lib/categoryRule', () => {
        const head = CATEGORY_RULE.slice(0, 40);
        const holders = walk(SRC).filter(p => readFileSync(p, 'utf8').includes(head));
        expect(holders.map(p => p.slice(SRC.length).replace(/\\/g, '/'))).toEqual(['lib/categoryRule.ts']);
        const section = readFileSync(new URL('../components/CategoriesSection.tsx', import.meta.url), 'utf8');
        expect(section).toContain('{CATEGORY_RULE}');
        expect(section).toContain('{CATEGORY_SPLIT_TITLE}');
    });
});
