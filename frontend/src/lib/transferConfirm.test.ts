import { describe, expect, it } from 'vitest';
import { needsConfirmation } from './transferConfirm';

/**
 * ANO-157. Перевод в копилку отвечает 409 в двух несовместимых смыслах, и предложить
 * подтверждение можно только в одном из них.
 */
describe('needsConfirmation', () => {
    it('409 с кодом CONFIRM_REQUIRED — предлагаем подтвердить', () => {
        expect(needsConfirmation({ status: 409, details: ['CONFIRM_REQUIRED'] })).toBe(true);
    });

    it('409 без кода — отказ безусловный, подтверждать нечего', () => {
        // «Снять больше накопленного»: денег в копилке физически нет, и повтор с confirm
        // упёрся бы в тот же отказ.
        expect(needsConfirmation({ status: 409, details: [] })).toBe(false);
    });

    it('другой статус с тем же кодом — не наш случай', () => {
        expect(needsConfirmation({ status: 500, details: ['CONFIRM_REQUIRED'] })).toBe(false);
    });

    it('не ошибка API — false, а не исключение', () => {
        expect(needsConfirmation(new Error('boom'))).toBe(false);
        expect(needsConfirmation(undefined)).toBe(false);
        expect(needsConfirmation(null)).toBe(false);
        expect(needsConfirmation({ status: 409 })).toBe(false);
    });
});
