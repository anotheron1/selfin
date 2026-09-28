import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import ts from 'typescript';
import { FUND_MONEY_ANSWERS, FUND_MONEY_KEEP, FUND_MONEY_QUESTION } from './fundMoney';

/**
 * ANO-198. Копилку с деньгами сервер удаляет только с ответом, что с ними (ANO-86): вернуть в
 * свободные или признать потраченными на цель. Экран ответа не спрашивал — копилку с деньгами было
 * не удалить никак. Замер 29.09 — спека 2026-09-29-fund-delete-asks-money-design.md.
 */

const read = (path: string) => readFileSync(new URL(path, import.meta.url), 'utf8');

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

describe('вопрос «что с деньгами» (ANO-198)', () => {
    it('два ответа: вернуть в свободные — RETURN, потрачены на цель — SPENT', () => {
        expect(FUND_MONEY_QUESTION).toBe('В копилке лежат деньги. Что с ними?');
        expect(FUND_MONEY_ANSWERS.map(a => [a.money, a.label])).toEqual([
            ['RETURN', 'Вернуть в свободные'],
            ['SPENT', 'Потрачены на цель'],
        ]);
        expect(FUND_MONEY_KEEP).toBe('Не удалять');
    });

    it('под каждым ответом — его следствие, таблица §4.3 спеки ANO-86 словами экрана', () => {
        // «Вернуть» — обратный перевод: свободные растут, капитал тот же. «Потрачены» — копилка
        // закрывается, перевод остаётся тратой: капитал падает, свободные не трогаются.
        const effect = Object.fromEntries(FUND_MONEY_ANSWERS.map(a => [a.money, a.effect]));
        expect(effect.RETURN).toBe('Свободные деньги вырастут на эту сумму, капитал не изменится');
        expect(effect.SPENT).toBe('Капитал уменьшится на эту сумму, свободные деньги не изменятся');
    });

    it('компонент строит кнопки из ответов и отдаёт выбранный — слова живут в одном месте', () => {
        const src = read('../components/FundMoneyQuestion.tsx');
        expect(src).toMatch(/FUND_MONEY_ANSWERS\.map\(/);
        expect(src).toMatch(/onAnswer\(a\.money\)/);
        expect(src).toMatch(/\{FUND_MONEY_QUESTION\}/);
        expect(src).toMatch(/\{FUND_MONEY_KEEP\}/);
        expect(src, 'слова ответов — только из lib/fundMoney.ts').not.toMatch(/Вернуть в свободные|Потрачены на цель/);
    });
});

describe('«Цели» спрашивают, что с деньгами (ANO-198, сторож по исходнику)', () => {
    // Компонентных тестов во фронте нет; проводка — правка в одну строку.
    const PAGE = '../pages/Funds.tsx';

    it('удаление: без ответа — подтверждение и попытка; 409 копилки — вопрос; ответ уходит в запрос', () => {
        const body = handlerBody(PAGE, 'handleDelete');
        expect(body).toMatch(/\(money\?: FundMoney\)/);
        expect(body, 'подтверждение — только у первой попытки').toMatch(/if \(!money && !confirm\(/);
        expect(body).toMatch(/deleteTogether\(\[\{ kind: 'FUND', run: \(\) => deleteFund\(fund\.id, money\) \}\]\)/);
        expect(body).toMatch(/if \(!money && asksWhereMoney\(failure\)\) \{\s*setAsksMoney\(true\);\s*return;\s*\}/);
        expect(body, 'иной отказ — строкой листа, а не молча').toMatch(/if \(failure\) \{\s*setDeleteError\(failure\);\s*return;\s*\}/);
    });

    it('лист показывает вопрос и отказ; кнопка удаления не передаёт событие клика как ответ', () => {
        const src = read(PAGE);
        expect(src).toMatch(/<FundMoneyQuestion[\s\S]*?onAnswer=\{money => handleDelete\(money\)\}/);
        expect(src).toMatch(/onClick=\{\(\) => handleDelete\(\)\}/);
        expect(src).not.toMatch(/onClick=\{handleDelete\}/);
        expect(src).toMatch(/\{deleteError && /);
    });
});

describe('«Что с капиталом» спрашивает, что с деньгами (ANO-198, сторож по исходнику)', () => {
    const BLOCK = '../components/sandbox/CapitalWhatIf.tsx';
    const DIALOG = '../components/wishlist/DeleteWishlistDialog.tsx';

    it('ответ уходит каждой удаляемой копилке; 409 копилки без ответа — вопрос', () => {
        const body = handlerBody(BLOCK, 'handleDeleteConfirm');
        expect(body).toMatch(/\(alsoArtifact: boolean, money\?: FundMoney\)/);
        expect(body).toMatch(/deleteFund\(id, money\)/);
        expect(body).toMatch(/if \(!money && asksWhereMoney\(failure\)\) \{\s*setDeleteAsksMoney\(true\);\s*return;\s*\}/);
    });

    it('вопрос сбрасывается при каждом открытии и доходит до диалога', () => {
        expect(handlerBody(BLOCK, 'openDelete')).toMatch(/setDeleteAsksMoney\(false\)/);
        expect(read(BLOCK)).toMatch(/asksMoney=\{deleteAsksMoney\}/);
    });

    it('диалог показывает вопрос и повторяет удаление с ответом и той же галочкой', () => {
        const src = read(DIALOG);
        expect(src).toMatch(/<FundMoneyQuestion[\s\S]*?onAnswer=\{money => onConfirm\(alsoArtifact, money\)\}/);
        expect(src).toMatch(/onConfirm: \(alsoDeleteArtifact: boolean, money\?: FundMoney\) => void/);
    });
});
