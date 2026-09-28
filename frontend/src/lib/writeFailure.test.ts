import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import ts from 'typescript';
import { FUND_ON_ACCOUNT } from './fundMovement';
import {
    DELETE_FAILED, FUND_HOLDS_MONEY, WRITE_FAILED,
    attempt, deleteFailure, deleteTogether, dismissFailure, writeFailure,
} from './writeFailure';

const lost = () => new TypeError('Failed to fetch');
/** Отказ, как его бросает api/client.ts: статус, коды и английский текст сервера в сообщении. */
const refused = (status: number, ...details: string[]) => ({
    status, details,
    message: `API error: ${status} /wishlist/items/1/convert — Unsupported target for WISHLIST source: FUND_WITH_CREDIT`,
});
const read = (path: string) => readFileSync(new URL(path, import.meta.url), 'utf8');

/**
 * ANO-141, ANO-170. Отказ записи доходит до человека словами продукта — не текстом сервера
 * (он по-английски, ANO-203) и не догадкой про поля (правило 5). Замер 28.09 — спека
 * 2026-09-28-wishlist-write-errors-design.md.
 */
describe('фраза отказа записи', () => {
    it('обрыв связи — «Не записалось — попробуйте ещё раз»: повтор безопасен (ANO-192)', () => {
        expect(WRITE_FAILED).toBe('Не записалось — попробуйте ещё раз');
        expect(writeFailure(lost())).toBe(WRITE_FAILED);
    });

    it('отказ сервера без кода — та же фраза, текст сервера на экран не идёт', () => {
        const msg = writeFailure(refused(400));
        expect(msg).toBe(WRITE_FAILED);
        expect(msg).not.toContain('Unsupported');
    });

    it('отказ копилки с кодом — её словами, как везде (ANO-169)', () => {
        expect(writeFailure(refused(409, FUND_ON_ACCOUNT, 'fund:Отпуск'))).toContain('«Отпуск» лежит на счёте');
    });

    it('те же слова, что в «Записать факт» и листе правки: одна фраза на один случай', () => {
        expect(read('../components/FactCreateSheet.tsx')).toContain(`'${WRITE_FAILED}'`);
        expect(read('../components/EditEventSheet.tsx')).toContain(`'${WRITE_FAILED}'`);
        expect(read('../components/EditEventSheet.tsx')).toContain(`'${DELETE_FAILED}'`);
    });
});

describe('фраза отказа удаления', () => {
    it('копилка, 409 — в ней лежат деньги: повтор не поможет, «попробуйте ещё раз» соврало бы', () => {
        expect(deleteFailure(refused(409), 'FUND')).toBe(FUND_HOLDS_MONEY);
        expect(FUND_HOLDS_MONEY).toBe('Не удалилось: в копилке лежат деньги');
    });

    it('копилка, обрыв связи — «Не удалилось — попробуйте ещё раз»', () => {
        expect(DELETE_FAILED).toBe('Не удалилось — попробуйте ещё раз');
        expect(deleteFailure(lost(), 'FUND')).toBe(DELETE_FAILED);
    });

    it('событие, 409 — это не деньги копилки: общая фраза', () => {
        expect(deleteFailure(refused(409), 'EVENT')).toBe(DELETE_FAILED);
    });

    it('из двух удалений называется отказавшее — в каком бы порядке оно ни стояло', async () => {
        const ok = () => Promise.resolve();
        const holds = () => Promise.reject(refused(409));
        expect(await deleteTogether([{ kind: 'EVENT', run: ok }, { kind: 'FUND', run: holds }])).toBe(FUND_HOLDS_MONEY);
        expect(await deleteTogether([{ kind: 'FUND', run: holds }, { kind: 'EVENT', run: ok }])).toBe(FUND_HOLDS_MONEY);
    });

    it('удалилось всё — фразы нет', async () => {
        const ok = () => Promise.resolve();
        expect(await deleteTogether([{ kind: 'EVENT', run: ok }, { kind: 'FUND', run: ok }])).toBeNull();
    });

    it('уже удалённое — не отказ: повтор после частичного удаления называет деньги, а не пропажу', async () => {
        const gone = () => Promise.reject(refused(404));
        const holds = () => Promise.reject(refused(409));
        expect(await deleteTogether([{ kind: 'EVENT', run: gone }, { kind: 'FUND', run: holds }])).toBe(FUND_HOLDS_MONEY);
        expect(await deleteTogether([{ kind: 'EVENT', run: gone }])).toBeNull();
    });
});

describe('запись с ответом для экрана', () => {
    it('записалось — фразы нет', async () => {
        expect(await attempt(() => Promise.resolve('ok'))).toBeNull();
    });

    it('отказ — фраза записи', async () => {
        expect(await attempt(() => Promise.reject(lost()))).toBe(WRITE_FAILED);
    });
});

/** Текст инициализатора `const имя = …` — тело обработчика. */
function handlerBody(path: string, name: string): string {
    const src = read(path);
    const sf = ts.createSourceFile(path, src, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
    let body: string | null = null;
    const visit = (node: ts.Node): void => {
        if (ts.isVariableDeclaration(node) && ts.isIdentifier(node.name)
            && node.name.text === name && node.initializer) {
            body = node.initializer.getText(sf);
        }
        ts.forEachChild(node, visit);
    };
    visit(sf);
    if (body === null) throw new Error(`${path}: нет обработчика ${name}`);
    return body;
}

describe('фраза отказа «Отложить» с удалением созданного (ANO-210)', () => {
    // Сервер отвергает удаление созданного 409 по-английски: у плана — есть факты, у копилки —
    // лежат деньги. Повтор не поможет; поможет отложить без удаления.
    it('план, 409 — по нему уже есть факт; подсказка — без галочки', () => {
        expect(dismissFailure(refused(409), 'EVENT')).toBe(
            'Не отложилось: по созданному плану уже есть факт, и его не удалить. Без галочки — отложится, план останется.');
    });

    it('копилка, 409 — в ней лежат деньги', () => {
        expect(dismissFailure(refused(409), 'FUND')).toBe(
            'Не отложилось: в созданной копилке лежат деньги, и её не удалить. Без галочки — отложится, копилка останется.');
    });

    it('обрыв связи — «Не записалось — попробуйте ещё раз»', () => {
        expect(dismissFailure(lost(), 'EVENT')).toBe(WRITE_FAILED);
        expect(dismissFailure(lost(), 'FUND')).toBe(WRITE_FAILED);
    });

    it('текст сервера на экран не идёт', () => {
        for (const kind of ['EVENT', 'FUND'] as const) {
            expect(dismissFailure(refused(409), kind)).not.toMatch(/[A-Za-z]/);
        }
    });
});

describe('«Что с капиталом» не глотает отказ (ANO-141, сторож по исходнику)', () => {
    // Компонентных тестов во фронте нет, а вернуть `.catch(refetch)` — правка в одну строку.
    const BLOCK = '../components/sandbox/CapitalWhatIf.tsx';
    const FIX = '../components/wishlist/FixWishlistDialog.tsx';
    const DELETE = '../components/wishlist/DeleteWishlistDialog.tsx';
    const DISMISS = '../components/wishlist/DismissWishlistDialog.tsx';

    it.each([
        ['handleStatusChange', /attempt\(/, /setStatusErrors\(/],
        ['handleFixConfirm', /attempt\(/, /setFixError\(failure\)/],
        ['handleFixWithoutConversion', /attempt\(/, /setFixError\(failure\)/],
        ['handleDeleteConfirm', /deleteTogether\(/, /setDeleteError\(failure\)/],
        ['handleDismissConfirm', /attempt\(/, /setDismissError\(failure\)/],
    ])('%s передаёт отказ на экран', (name, write, shown) => {
        const body = handlerBody(BLOCK, name);
        expect(body).toMatch(write);
        expect(body).toMatch(shown);
    });

    it.each(['handleStatusChange', 'handleFixConfirm', 'handleFixWithoutConversion', 'handleDeleteConfirm',
        'handleDismissConfirm'])(
        '%s: на отказе список не перечитывается',
        (name) => {
            // Перечитывание сбрасывает подкрученное (overrideMap): диалог держит снимок хотелки с
            // записанными числами, и повтор ушёл бы с ними — найдено перечиткой диффа. И уводит блок
            // в загрузку, а без связи — в ошибку загрузки: карточка со строкой отказа пропадает
            // (ревью Codex, #108).
            const body = handlerBody(BLOCK, name);
            expect(body.match(/refetch\(\)/g)).toHaveLength(1);
            expect(body).toMatch(/if \(failure\) \{[\s\S]*?return;\s*\}[\s\S]*refetch\(\);/);
        });

    it.each([['closeFix', 'fixError'], ['closeDelete', 'deleteError'], ['closeDismiss', 'dismissError']])(
        '%s перечитывает список только после отказа',
        (name, error) => {
            // После отказа часть записи могла пройти: примерка до отказа конверсии, одна из двух
            // частей удаления. Без попытки перечитывание сбросило бы подкрученное простым «Отмена».
            expect(handlerBody(BLOCK, name)).toMatch(new RegExp(`if\\s*\\(${error}\\)\\s*refetch\\(\\)`));
        });

    it('ни один обработчик не прячет отказ за перечитыванием', () => {
        const src = read(BLOCK);
        expect(src).not.toMatch(/\.catch\(\s*refetch\s*\)/);
        expect(src).not.toMatch(/allSettled/);
    });

    it('отказ и занятость доходят до диалогов и карточек', () => {
        const src = read(BLOCK);
        expect(src).toMatch(/busy=\{fixBusy\}/);
        expect(src).toMatch(/error=\{fixError\}/);
        expect(src).toMatch(/busy=\{deleteBusy\}/);
        expect(src).toMatch(/error=\{deleteError\}/);
        expect(src).toMatch(/busy=\{dismissBusy\}/);
        expect(src).toMatch(/error=\{dismissError\}/);
        expect(src).toMatch(/statusErrors=\{statusErrors\}/);
        expect(read('../components/wishlist/WishlistItemList.tsx'))
            .toMatch(/statusError=\{p\.statusErrors\[item\.id\]\}/);
    });

    it('«Отложить» у хотелки с созданным сначала спрашивает, а не пишет (ANO-210)', () => {
        const body = handlerBody(BLOCK, 'handleStatusChange');
        const ask = body.search(/status === 'DISMISSED' && dismissQuestion\(item\)\) \{\s*setDismissError\(null\);\s*setDismissItem\(item\);\s*return;/);
        expect(ask, 'вопрос до записи').toBeGreaterThanOrEqual(0);
        expect(ask, 'вопрос раньше записи статуса').toBeLessThan(body.search(/attempt\(/));
        // С галочкой статус уходит вместе с удалением созданного — одной записью на сервере.
        expect(handlerBody(BLOCK, 'handleDismissConfirm')).toMatch(/changeStatus\(item, 'DISMISSED', alsoArtifact\)/);
        expect(handlerBody(BLOCK, 'handleDismissConfirm')).toMatch(/dismissFailure\(err, /);
    });

    it('диалог «Отложить»: вопрос, строка и галочка — выключенная при каждом открытии (ANO-210)', () => {
        const src = read(DISMISS);
        expect(src).toMatch(/Отложить «\{item\.name\}»\?/);
        expect(src).toMatch(/\{question\.stays\}/);
        expect(src).toMatch(/\{question\.also\}/);
        expect(src).toMatch(/useState\(false\)/);
        expect(src).toMatch(/if \(open\) setAlsoArtifact\(false\)/);
    });

    it('диалоги показывают отказ и не отпускают, пока идёт запись: обе кнопки заняты', () => {
        for (const path of [FIX, DELETE, DISMISS]) {
            const src = read(path);
            expect(src, path).toMatch(/\{error\s*&&/);
            expect(src.match(/disabled=\{busy/g), path).toHaveLength(2);
            expect(src, path).toMatch(/!busy\s*&&\s*onClose\(\)/);
        }
    });

    it('карточка показывает отказ смены статуса', () => {
        expect(read('../components/wishlist/WishlistItemCard.tsx')).toMatch(/\{statusError\s*&&/);
    });

    it('диалог фиксации строит пункты правилом, а не одним списком на всех', () => {
        const src = read(FIX);
        expect(src).toMatch(/conversionChoice\(/);
        expect(src).not.toMatch(/'FUND_WITH_CREDIT'\s*\]/);
    });

    it('обе кнопки фиксации заперты, когда примерку не записать (ревью Codex, #108)', () => {
        // Запись примерки идёт до любой фиксации: вне границ откажет и «без конверсии».
        expect(read(FIX).match(/!choice\.savable/g)).toHaveLength(2);
    });

    it('в диалог уходят ставка и срок, которые запишет persistTrial, — подкрученные (fixPatch)', () => {
        const src = read(BLOCK);
        expect(src).toMatch(/rate:\s*fixView\.rate/);
        expect(src).toMatch(/termMonths:\s*fixView\.termMonths/);
        expect(src).toMatch(/fixView\s*=\s*fixItem\s*&&\s*fixPatch\(/);
    });
});
