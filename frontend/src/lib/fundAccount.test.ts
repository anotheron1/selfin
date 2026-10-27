import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { ENVELOPE, NO_TRANSFER, ON_ACCOUNT, accountChoices, fundAccountHint } from './fundAccount';
import type { Account } from '../types/api';

/**
 * ANO-164, ANO-174. Следствие привязки копилки к счёту не проговаривалось: карточка копилки на
 * счёте — без кнопки «Пополнить» и без слова почему; привязка молча переставала считать уже
 * переведённое в конверт. Замер 29.09 — спека 2026-09-29-fund-account-words-design.md.
 */

const read = (path: string) => readFileSync(new URL(path, import.meta.url), 'utf8');
// Р10-А (ANO-212): деньги копилки без счёта лежат на основной карте — привязка их не уничтожает.
const MOVED = 'То, что уже переведено в копилку, пока она на счёте, снова свободно на основной карте — '
    + 'отложится обратно, если отвязать.';
const UNLINKED = 'Накопленное снова покажет только то, что в неё переводили.';

describe('слова копилки на счёте (ANO-164, ANO-174)', () => {
    it('общие строки — прежние слова выбора счёта', () => {
        expect(ON_ACCOUNT).toBe('Накопленное берётся с остатка счёта.');
        expect(NO_TRANSFER).toBe('Пополнять переводом нельзя — двигай деньги на счёте и обновляй его остаток.');
        expect(ENVELOPE).toBe('Копилка держит свой баланс на основной карте: перевод откладывает деньги '
            + 'из свободных, а с карты они не уходят.');
    });

    it('новая копилка: конверт — свой баланс; на счёте — остаток счёта и запрет перевода', () => {
        expect(fundAccountHint({ linked: false, wasLinked: false, holdsOwnMoney: false })).toEqual([ENVELOPE]);
        expect(fundAccountHint({ linked: true, wasLinked: false, holdsOwnMoney: false })).toEqual([ON_ACCOUNT, NO_TRANSFER]);
    });

    it('привязка конверта с деньгами говорит, что станет с переведённым', () => {
        // Замер 29.09: 1 000 в конверте, привязка к «Основной карте» — накоплено 53 900, капитал −1 000;
        // отвязка — всё обратно. С Р10-А капитал от привязки не меняется: деньги и так на карте.
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

/**
 * ANO-203. Выбор счёта предлагал и счёт, где уже лежит другая копилка, — сервер отвечал 409 «Account already holds the
 * goal», и форма показывала это по-английски. На стенде 26.10 так два счёта из трёх. Предлагаем только то, что сервер
 * примет, как диалог конверсии после ANO-141; счёт с чужой копилкой — виден, но недоступен, с припиской.
 */
describe('выбор счёта: счёт с другой копилкой виден, но недоступен (ANO-203)', () => {
    type Choosable = Pick<Account, 'id' | 'name' | 'kind' | 'trackBalance'>;
    const account = (id: string, name: string, more: Partial<Choosable> = {}): Choosable =>
        ({ id, name, kind: 'DEBIT', trackBalance: true, ...more });
    const fund = (id: string, name: string, accountId: string | null) => ({ id, name, accountId });
    const accounts = [account('card', 'Основная карта'), account('deposit', 'Вклад сбер')];

    it('счёт, где лежит другая копилка, — с припиской «здесь копилка» и недоступен', () => {
        expect(accountChoices(accounts, [fund('f1', 'уке', 'deposit')])).toEqual([
            { id: 'card', label: 'Основная карта', taken: false },
            { id: 'deposit', label: 'Вклад сбер — здесь копилка «уке»', taken: true },
        ]);
    });

    it('в форме правки своя копилка свой счёт не блокирует', () => {
        expect(accountChoices(accounts, [fund('f1', 'уке', 'deposit')], 'f1')).toEqual([
            { id: 'card', label: 'Основная карта', taken: false },
            { id: 'deposit', label: 'Вклад сбер', taken: false },
        ]);
    });

    it('копилка-конверт счёт не занимает', () => {
        expect(accountChoices(accounts, [fund('f1', 'Отпуск', null)]).every(c => !c.taken)).toBe(true);
    });

    it('кредитка и счёт без слежения в выбор не идут — как раньше', () => {
        const all = [...accounts, account('cc', 'Платинум', { kind: 'CREDIT' }), account('cash', 'Бензин', { trackBalance: false })];
        expect(accountChoices(all, []).map(c => c.id)).toEqual(['card', 'deposit']);
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
            /<FundAccountPicker accounts=\{accounts\} funds=\{funds\} selfId=\{fund\.id\} value=\{accountId\} onChange=\{setAccountId\}\s*wasLinked=\{fund\.accountId != null\} holdsOwnMoney=\{fund\.currentBalance !== 0\} \/>/);
    });
});
