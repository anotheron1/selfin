/**
 * Сторож заголовков кэша (ANO-222): nginx фронта — конфиг, тестом его не выполнить, поэтому сторож читает исходник.
 * Без заголовка браузер держит старый index.html, и после выкладки старая страница говорит с новым бэком.
 *
 * Спека: docs/superpowers/specs/2026-10-17-index-cache-open-tab-design.md
 */
import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';

const conf = readFileSync(new URL('../nginx.conf', import.meta.url), 'utf8');
const block = (location: string) => conf.match(new RegExp(`location ${location} \\{([^}]*)\\}`))?.[1] ?? '';

describe('nginx фронта: кэш (ANO-222)', () => {
    it('index.html — без хранения: браузер сверяет ETag и после выкладки грузит новый бандл', () => {
        expect(block('= /index\\.html')).toMatch(/add_header\s+Cache-Control\s+"no-cache"/);
    });

    it('бандлы с хэшем в имени — навсегда, а чего нет — 404, а не страница', () => {
        const assets = block('/assets/');
        expect(assets).toMatch(/add_header\s+Cache-Control\s+"public, max-age=31536000, immutable"/);
        expect(assets).toMatch(/try_files \$uri =404;/);
    });
});
