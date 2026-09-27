import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { FUND_CLOSED, FUND_HOLDS_LESS, FUND_ON_ACCOUNT, fundMovementMessage } from './fundMovement';
import { fmtRub } from './format';

const refused = (...details: string[]) => ({ status: 409, details });

/**
 * ANO-169, ANO-201. Факт перевода и копилка меняются только вместе; когда копилка сдвинуться
 * не может, сервер отказывает кодом, и экран обязан назвать причину словами.
 */
describe('fundMovementMessage', () => {
    it('в копилке меньше — имя копилки и сколько в ней сейчас', () => {
        const msg = fundMovementMessage(refused(FUND_HOLDS_LESS, 'fund:Отпуск', 'holds:40.00'));
        expect(msg).toContain('«Отпуск»');
        expect(msg).toContain(`сейчас ${fmtRub(40)}`);
    });

    it('суммы в подсказке нет — не называем выдуманный ноль', () => {
        const msg = fundMovementMessage(refused(FUND_HOLDS_LESS, 'fund:Отпуск'));
        expect(msg).toContain('«Отпуск»');
        expect(msg).not.toContain('сейчас');
        expect(msg).not.toContain(fmtRub(0));
    });

    it('копилка на счёте — деньги двигаются на самом счёте', () => {
        expect(fundMovementMessage(refused(FUND_ON_ACCOUNT, 'fund:Первый взнос')))
            .toBe('Копилка «Первый взнос» лежит на счёте: деньги в ней двигаются на самом счёте, переводы в неё не записываются.');
    });

    it('копилка удалена — переводы остаются историей', () => {
        expect(fundMovementMessage(refused(FUND_CLOSED, 'fund:Египет')))
            .toBe('Копилка «Египет» удалена: её переводы остаются в журнале как история и не меняются.');
    });

    it('двоеточие в имени копилки не обрезает имя', () => {
        expect(fundMovementMessage(refused(FUND_CLOSED, 'fund:Отпуск: море'))).toContain('«Отпуск: море»');
    });

    it('чужой отказ — null: общий текст решает форма', () => {
        expect(fundMovementMessage(refused('CONFIRM_REQUIRED', 'short:2026-09-30:100'))).toBeNull();
        expect(fundMovementMessage({ status: 409, details: [] })).toBeNull();
        expect(fundMovementMessage({ status: 400, details: [FUND_CLOSED, 'fund:Египет'] })).toBeNull();
        expect(fundMovementMessage(new Error('network'))).toBeNull();
        expect(fundMovementMessage(undefined)).toBeNull();
    });
});

/**
 * Сторож по исходнику: формы журнала перестают молчать. Раньше ошибка правки уходила в
 * консоль, ошибка удаления — никуда, и отказ копилки был бы немой кнопкой (как ANO-198).
 */
describe('формы журнала показывают отказ (ANO-169, ANO-201)', () => {
    const read = (path: string) => readFileSync(new URL(path, import.meta.url), 'utf8');

    it('«Записать факт» — причина отказа или «Не записалось»', () => {
        const src = read('../components/FactCreateSheet.tsx');
        expect(src).toContain("setError(fundMovementMessage(err) ?? 'Не записалось — попробуйте ещё раз')");
        expect(src).toContain('{error && (');
    });

    it('правка и удаление факта — причина отказа, в том числе у удаления', () => {
        const src = read('../components/EditEventSheet.tsx');
        expect(src).toContain("setError(fundMovementMessage(err) ?? 'Не записалось — попробуйте ещё раз')");
        expect(src.match(/setError\(fundMovementMessage\(err\) \?\? 'Не удалилось — попробуйте ещё раз'\)/g))
            .toHaveLength(2);
        expect(src).toContain('{error && (');
    });

    it('быстрый ввод называет отказ копилки словами, остальное — «Не записалось» (ANO-170)', () => {
        // writeFailure — это fundMovementMessage, а без кода — запасная фраза записи:
        // lib/writeFailure.test.ts. Догадку про поля ловит сторож словаря (правило 5).
        expect(read('../components/Fab.tsx')).toContain('setError(writeFailure(err))');
    });
});
