/**
 * Сторож по исходнику (ANO-245): текст ошибки на экран не идёт. Клиент собирает `err.message` из кода, адреса
 * и сообщения сервера по-английски — «API error: 409 /accounts/… — The goal "уке" lives on this account», —
 * и экраны показывали его как есть в двадцати местах (ANO-203, ANO-245). Слова отказа записи — `lib/writeFailure.ts`,
 * чтения — «Не удалось загрузить…». Новая форма с `err.message` этот тест не пройдёт.
 *
 * Спека: docs/superpowers/specs/2026-10-27-server-words-design.md
 */
import { describe, expect, it } from 'vitest';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const SRC = fileURLToPath(new URL('.', import.meta.url));

/** Где `.message` — не текст на экран. Каждое — с причиной, как ALLOWED у сторожа словаря. */
const ALLOWED: { file: string; why: string }[] = [
    { file: 'api/client.ts', why: 'собирает текст ошибки из ответа сервера; экраны его не показывают' },
];

function walk(dir: string): string[] {
    return readdirSync(dir).flatMap((name) => {
        const path = join(dir, name);
        if (statSync(path).isDirectory()) return walk(path);
        return /\.tsx?$/.test(name) && !/\.test\.tsx?$/.test(name) ? [path] : [];
    });
}
const files = walk(SRC).map((path) => ({ file: relative(SRC, path).split(sep).join('/'), text: readFileSync(path, 'utf8') }));
const read = (file: string) => files.find((f) => f.file === file)?.text ?? '';

describe('текст ошибки на экран не идёт (ANO-245)', () => {
    it('сторож видит исходники — иначе зелёный цвет ничего не значит', () => {
        expect(files.length).toBeGreaterThan(100);
        expect(read('pages/Funds.tsx')).toMatch(/writeFailure/);
    });

    it('`.message` и `String(e)` — только в исключениях', () => {
        // String(e) — тот же текст с приставкой «Error:»: так его показывал «Капитал».
        const hits = files
            .filter((f) => !ALLOWED.some((a) => a.file === f.file))
            .flatMap((f) => f.text.split('\n').flatMap((line, i) =>
                /\.message\b|String\((?:e|err|error)\)/.test(line) ? [`${f.file}:${i + 1}  ${line.trim()}`] : []));
        expect(hits).toEqual([]);
    });

    it('исключения живые: файл есть, `.message` в нём есть', () => {
        for (const a of ALLOWED) expect(read(a.file), a.file).toMatch(/\.message\b/);
    });
});

describe('места ANO-245 говорят словами экрана', () => {
    it('счета: форма проверяет до отправки и отвечает словами; удаление — про копилку; остальное — writeFailure', () => {
        const src = read('components/accounts/AccountsSection.tsx');
        expect(src).toMatch(/const problem = accountFormProblem\(dto, null\);/);
        expect(src).toMatch(/const problem = accountFormProblem\(dto, editing\);/);
        expect(src.match(/if \(problem\) \{ setFormError\(problem\); return; \}/g)).toHaveLength(2);
        expect(src).toMatch(/setFormError\(writeFailure\(e\)\)/);
        expect(src).toMatch(/setFormError\(accountWriteFailure\(e, dto, editing\)\)/);
        expect(src).toMatch(/showToast\(accountDeleteFailure\(e\)\)/);
        expect(src.match(/showToast\(writeFailure\(e\)\)/g)).toHaveLength(3);
    });

    it('НЗ, сверка остатка и пороги «Хотелок» — writeFailure', () => {
        expect(read('components/pocket/NzForm.tsx')).toMatch(/setError\(writeFailure\(err\)\)/);
        expect(read('components/pocket/ReanchorSheet.tsx')).toMatch(/setError\(writeFailure\(err\)\)/);
        expect(read('components/wishlist/WishlistThresholdsHeader.tsx')).toMatch(/setSaveError\(writeFailure\(e\)\)/);
    });

    it('новая хотелка: форма с границами сервера — ставка 0,01–99,99, срок 1–360; отказ — writeFailure', () => {
        // Кнопка вне формы не запускала проверку браузера: ставка 0 и срок 500 уходили на сервер.
        const src = read('components/wishlist/AddWishlistDialog.tsx');
        expect(src).toMatch(/<form onSubmit=\{handleSubmit\}/);
        expect(src).toMatch(/min="0\.01"\s+max="99\.99"/);
        expect(src).toMatch(/min="1"\s+max="360"/);
        expect(src).toMatch(/<Button type="submit"/);
        expect(src).toMatch(/setError\(writeFailure\(e\)\)/);
    });

    it('«Хотелки»: заметка без текста сервера, отказ записи — словами, загрузка — «Не удалось загрузить»', () => {
        const src = read('pages/Wishlist.tsx');
        expect(src).toMatch(/setNotice\('Примерка сброшена — попробуйте ещё раз'\)/);
        expect(src).toMatch(/const failed = \(e: unknown\) => setError\(writeFailure\(e\)\);/);
        expect(src.match(/setError\(LOAD_FAILED\)/g)).toHaveLength(2);
        expect(src).not.toMatch(/Ошибка примерки/);
    });

    it('дашборд, капитал и карточка свободных денег — «Не удалось загрузить…» и «Повторить»', () => {
        expect(read('pages/Dashboard.tsx'))
            .toMatch(/Не удалось загрузить дашборд[\s\S]{0,200}onClick=\{\(\) => loadAll\(\)\}[^>]*>Повторить</);
        expect(read('pages/Capital.tsx'))
            .toMatch(/Не удалось загрузить капитал[\s\S]{0,200}onClick=\{\(\) => reload\(\)\}[^>]*>Повторить</);
        expect(read('components/PocketCard.tsx'))
            .toMatch(/Не удалось загрузить свободные деньги[\s\S]{0,300}onClick=\{\(\) => load\(\)\}[^>]*>Повторить</);
    });
});
