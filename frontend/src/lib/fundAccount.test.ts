import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { ENVELOPE, NO_TRANSFER, ON_ACCOUNT, fundAccountHint } from './fundAccount';

/**
 * ANO-164, ANO-174. Следствие привязки копилки к счёту не проговаривалось: карточка копилки на
 * счёте — без кнопки «Пополнить» и без слова почему; привязка молча переставала считать уже
 * переведённое в конверт. Замер 29.09 — спека 2026-09-29-fund-account-words-design.md.
 */

const read = (path: string) => readFileSync(new URL(path, import.meta.url), 'utf8');
const MOVED = 'То, что уже переведено в копилку, пока она на счёте, не входит ни в накопленное, ни в капитал — '
    + 'вернётся, если отвязать.';
const UNLINKED = 'Накопленное снова покажет только то, что в неё переводили.';

describe('слова копилки на счёте (ANO-164, ANO-174)', () => {
    it('общие строки — прежние слова выбора счёта', () => {
        expect(ON_ACCOUNT).toBe('Накопленное берётся с остатка счёта.');
        expect(NO_TRANSFER).toBe('Пополнять переводом нельзя — двигай деньги на счёте и обновляй его остаток.');
        expect(ENVELOPE).toBe('Копилка держит свой баланс и пополняется переводом из свободных денег.');
    });

    it('новая копилка: конверт — свой баланс; на счёте — остаток счёта и запрет перевода', () => {
        expect(fundAccountHint({ linked: false, wasLinked: false, holdsOwnMoney: false })).toEqual([ENVELOPE]);
        expect(fundAccountHint({ linked: true, wasLinked: false, holdsOwnMoney: false })).toEqual([ON_ACCOUNT, NO_TRANSFER]);
    });

    it('привязка конверта с деньгами говорит, что станет с переведённым', () => {
        // Замер 29.09: 1 000 в конверте, привязка к «Основной карте» — накоплено 53 900, капитал −1 000;
        // отвязка — всё обратно.
        expect(fundAccountHint({ linked: true, wasLinked: false, holdsOwnMoney: true }))
            .toEqual([ON_ACCOUNT, NO_TRANSFER, MOVED]);
    });

    it('копилка и была на счёте — про переведённое молчит: деньги уже не конверта', () => {
        expect(fundAccountHint({ linked: true, wasLinked: true, holdsOwnMoney: true })).toEqual([ON_ACCOUNT, NO_TRANSFER]);
    });

    it('отвязка — накопленное снова своё', () => {
        expect(fundAccountHint({ linked: false, wasLinked: true, holdsOwnMoney: true })).toEqual([ENVELOPE, UNLINKED]);
    });
});

describe('карточка и выбор счёта берут слова из lib/fundAccount.ts (сторож по исходнику)', () => {
    // Компонентных тестов во фронте нет; убрать строку с карточки — правка в одну строку.
    const PAGE = '../pages/Funds.tsx';

    it('карточка копилки на счёте говорит, почему её не пополнить (ANO-174)', () => {
        expect(read(PAGE)).toMatch(/\{fund\.accountId && \([\s\S]*?Лежит на счёте[\s\S]*?\{NO_TRANSFER\}[\s\S]*?\)\}/);
    });

    it('выбор счёта строит подсказку из fundAccountHint, своих строк у него нет', () => {
        const src = read(PAGE);
        expect(src).toMatch(/fundAccountHint\(\{ linked: !!value, wasLinked, holdsOwnMoney \}\)\.map\(/);
        expect(src).not.toMatch(/Накопленное берётся с остатка счёта|Копилка держит свой баланс/);
    });

    it('лист правки передаёт, была ли копилка на счёте и лежат ли в ней деньги (ANO-164)', () => {
        expect(read(PAGE)).toMatch(
            /<FundAccountPicker accounts=\{accounts\} value=\{accountId\} onChange=\{setAccountId\}\s*wasLinked=\{fund\.accountId != null\} holdsOwnMoney=\{fund\.currentBalance !== 0\} \/>/);
    });
});
