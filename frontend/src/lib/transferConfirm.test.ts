import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { needsConfirmation, shortfallQuestion, shortfallQuestionFor } from './transferConfirm';

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

/**
 * ANO-88, решение владельца 26.09 (вариант А): «Пополнить фонд» говорит тем же числом и словом,
 * что карточка кармашка, и спрашивает с датой, к которой не хватит.
 */
describe('shortfallQuestion', () => {
    it('называет дату, к которой не хватит', () => {
        expect(shortfallQuestion('2026-09-28'))
            .toBe('Это больше, чем свободно в кармашке — к 28 сентября не хватит. Отложить всё равно?');
    });

    it('без даты — без выдуманной даты', () => {
        expect(shortfallQuestion(undefined))
            .toBe('Это больше, чем свободно в кармашке. Отложить всё равно?');
    });

    it('минимум сегодня — «уже сегодня», как карточка говорит «Сегодня по плану не хватает»', () => {
        expect(shortfallQuestion('2026-09-26', true))
            .toBe('Это больше, чем свободно в кармашке — не хватит уже сегодня. Отложить всё равно?');
    });
});

describe('shortfallQuestionFor — по кармашку с карточки', () => {
    const pocket = (minDate: string) => ({
        minPoint: { date: minDate }, trajectory: [{ date: '2026-09-26' }, { date: '2026-09-27' }],
    });

    it('«сегодня» — день нулевой точки траектории, то есть сегодня сервера, как решает карточка', () => {
        expect(shortfallQuestionFor(pocket('2026-09-26'))).toContain('не хватит уже сегодня');
    });

    it('минимум позже — называет его день', () => {
        expect(shortfallQuestionFor(pocket('2026-09-28'))).toContain('к 28 сентября не хватит');
    });

    it('кармашка ещё нет — без даты', () => {
        expect(shortfallQuestionFor(null)).toBe('Это больше, чем свободно в кармашке. Отложить всё равно?');
    });
});

describe('диалог «Пополнить фонд» (ANO-88)', () => {
    // Сторож по исходнику: компонентных тестов нет, а вернуть число нулевого дня — правка
    // в одну строку, и три числа на экране «Цели» снова разойдутся.
    const src = readFileSync(new URL('../pages/Funds.tsx', import.meta.url), 'utf8');

    it('пишет «свободно» и число кармашка, а не остаток нулевого дня', () => {
        expect(src).not.toContain('trajectory[0]');
        expect(src).toContain('свободно {fmt(free)}');
        expect(src).not.toContain('доступно {');
    });

    it('спрашивает с датой и шлёт горизонт карточки', () => {
        expect(src).toContain('shortfallQuestionFor(');
        expect(src).toContain('transferToFund(fund.id, num, undefined, scope)');
        expect(src).toContain('transferToFund(fund.id, num, true, scope)');
    });
});
