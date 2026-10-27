/**
 * Сторож по исходнику «Целей» (ANO-203, ANO-205): компонентных тестов во фронте нет, а вернуть текст сервера на экран —
 * правка в одну строку. Формы «Новый фонд» и «Редактировать» показывали отказ как есть: «API error: 409 /funds —
 * Account already holds the goal "уке"». Лист «Пополнить» на любой отказ, кроме подтверждаемого, молчал: лист открыт,
 * кнопка снова активна, ни слова. Список, который не загрузился, писал «Ошибка: Failed to fetch».
 *
 * Спека: docs/superpowers/specs/2026-10-27-funds-words-design.md
 */
import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';

const page = readFileSync(new URL('./Funds.tsx', import.meta.url), 'utf8');
/** Тело функции компонента — от объявления до следующего раздела `// ───`. */
const component = (name: string) => page.match(new RegExp(`function ${name}\\([\\s\\S]*?(?=\\n// ───|$)`))?.[0] ?? '';

describe('«Цели» говорят словами (ANO-203, ANO-205)', () => {
    it('текст сервера на экран не идёт: ни err.message, ни (err as Error).message', () => {
        expect(page).not.toMatch(/\.message\b/);
    });

    it('формы «Новый фонд» и «Редактировать» — отказ словами writeFailure', () => {
        for (const form of ['CreateFundModal', 'EditFundModal']) {
            expect(component(form), form).toMatch(/setError\(writeFailure\(err\)\)/);
        }
    });

    it('«Пополнить» — отказ строкой под суммой, лист открыт: повтор безопасен, та же попытка (ANO-192)', () => {
        const transfer = component('TransferModal');
        expect(transfer).toMatch(/setFailure\(writeFailure\(err\)\)/);
        expect(transfer).toMatch(/\{failure && <p[^>]*>\{failure\}<\/p>\}/);
    });

    it('список не загрузился — «Не удалось загрузить цели» и «Повторить», как в «Стратегии»', () => {
        expect(page).toMatch(/Не удалось загрузить цели/);
        expect(page).toMatch(/onClick=\{\(\) => load\(\)\}[^>]*>Повторить</);
    });

    it('выбор счёта — из accountChoices, счёт с другой копилкой недоступен; формам передан список копилок', () => {
        expect(component('FundAccountPicker')).toMatch(/accountChoices\(accounts, funds, selfId\)/);
        expect(component('FundAccountPicker')).toMatch(/disabled=\{c\.taken\}/);
        expect(component('CreateFundModal')).toMatch(/<FundAccountPicker accounts=\{accounts\} funds=\{funds\}/);
        expect(component('EditFundModal')).toMatch(/<FundAccountPicker accounts=\{accounts\} funds=\{funds\} selfId=\{fund\.id\}/);
    });
});
