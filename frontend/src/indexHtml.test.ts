/**
 * Сторож index.html (ANO-233): страницу тестом не открыть, поэтому сторож читает исходник. Вкладка браузера называется
 * «Selfin», а не заглушкой шаблона Vite, и страница объявлена русской: с lang="en" Chrome предлагает перевести её
 * «с английского», а экранный диктор читает русский текст как английский.
 *
 * Спека: docs/superpowers/specs/2026-10-27-tab-title-design.md
 */
import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';

const html = readFileSync(new URL('../index.html', import.meta.url), 'utf8');

describe('index.html (ANO-233)', () => {
    it('вкладка называется Selfin — так её увидят тестировщики в списке вкладок и закладках', () => {
        expect(html).toMatch(/<title>Selfin<\/title>/);
    });

    it('страница объявлена русской — интерфейс целиком русский', () => {
        expect(html).toMatch(/<html lang="ru"[ >]/);
    });
});
