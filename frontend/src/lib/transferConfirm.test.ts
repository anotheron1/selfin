import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { confirmQuestion, needsConfirmation, transferFreeLine } from './transferConfirm';
import { fmtRub as fmtC } from './format';
import type { PocketResponse } from '../types/api';

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
 * ANO-88, решение владельца 26.09 (вариант А): вопрос повторяет слова карточки о том, что будет
 * ПОСЛЕ перевода. Что будет — говорит сервер: он уже посчитал кармашек после перевода.
 */
describe('confirmQuestion', () => {
    const card = { trajectory: [{ date: '2026-09-26' }, { date: '2026-09-27' }] };
    const refusal = (hint?: string) => ({ status: 409, details: hint ? ['CONFIRM_REQUIRED', hint] : ['CONFIRM_REQUIRED'] });

    it('не хватит денег — день и сумма, как заголовок карточки', () => {
        expect(confirmQuestion(refusal('short:2026-09-28:5000.00'), card))
            .toBe(`После перевода 28 сентября по плану не хватит ${fmtC(5000)}. Отложить всё равно?`);
    });

    it('минимум сегодня — «уже сегодня»: по нулевой точке траектории карточки, как решает карточка', () => {
        expect(confirmQuestion(refusal('short:2026-09-26:1601.00'), card))
            .toBe(`После перевода уже сегодня по плану не хватит ${fmtC(1601)}. Отложить всё равно?`);
    });

    it('денег хватит, но задет НЗ — про НЗ, а не «не хватит»', () => {
        expect(confirmQuestion(refusal('nz:2026-09-27:3000'), card))
            .toBe(`После перевода 27 сентября по плану придётся взять из НЗ ${fmtC(3000)}. Отложить всё равно?`);
    });

    it('подсказки нет — вопрос без выдуманных дня и суммы', () => {
        expect(confirmQuestion(refusal(), card)).toBe('Это больше, чем свободно. Отложить всё равно?');
        expect(confirmQuestion(refusal('garbage'), null)).toBe('Это больше, чем свободно. Отложить всё равно?');
    });
});

describe('transferFreeLine — строка диалога теми же словами, что карточка (ANO-88)', () => {
    // Вариант А: «то же число и то же слово, что карточка». В режиме нехватки карточка не пишет
    // «свободно −1 600» — у неё заголовок «Сегодня по плану не хватает 1 600 ₽» (ANO-100).
    const p = (min: number, buffer = 0) => ({
        pocket: min - buffer, buffer,
        minPoint: { date: '2026-09-26', balance: min, drivenBy: null },
        horizon: { type: 'NEXT_INCOME', endDate: '2026-09-28', label: 'до дохода 28.09', fallback: false },
        trajectory: [{ date: '2026-09-26', balance: min, income: 0, expense: 0, balanceWithForecast: null }],
        upcoming: [],
    }) as unknown as PocketResponse;

    it('денег хватает — «свободно» и кармашек', () => {
        expect(transferFreeLine(p(26500))).toBe(`свободно ${fmtC(26500)}`);
    });

    it('нехватка — заголовок карточки, а не «свободно» с минусом', () => {
        expect(transferFreeLine(p(-1600))).toBe(`сегодня по плану не хватает ${fmtC(1600)}`);
    });

    it('НЗ задет — «придётся взять из НЗ», как на карточке', () => {
        expect(transferFreeLine(p(3000, 5000))).toBe(`сегодня по плану придётся взять из НЗ ${fmtC(2000)}`);
    });

    it('кармашка ещё нет — строки нет, а не выдуманный ноль', () => {
        expect(transferFreeLine(null)).toBeNull();
    });
});

describe('диалог «Пополнить фонд» (ANO-88)', () => {
    // Сторож по исходнику: компонентных тестов нет, а вернуть число нулевого дня — правка
    // в одну строку, и три числа на экране «Цели» снова разойдутся.
    const src = readFileSync(new URL('../pages/Funds.tsx', import.meta.url), 'utf8');

    it('пишет «свободно» и число кармашка, а не остаток нулевого дня', () => {
        expect(src).not.toContain('trajectory[0]');
        expect(src).toContain('transferFreeLine(pocket)');
        expect(src).not.toContain('доступно {');
    });

    it('спрашивает с датой и шлёт горизонт карточки', () => {
        expect(src).toContain('confirmQuestion(err, pocket)');
        expect(src).toContain('transferToFund(fund.id, num, undefined, scope)');
        expect(src).toContain('transferToFund(fund.id, num, true, scope)');
    });
});
