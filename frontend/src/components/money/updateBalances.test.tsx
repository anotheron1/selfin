/**
 * «Обновить остатки» (срез 4, п. 16; доски S4-U1, S4-U4). Разметка — на сервере, как card.test.tsx; нажатия, предпросмотр
 * и строку после «Сохранить» проверяет стенд. Слова строк — lib/updateBalances.test.ts.
 */
import { describe, expect, it } from 'vitest';
import { readFileSync, existsSync } from 'node:fs';
import { renderToStaticMarkup } from 'react-dom/server';
import { BalancesForm } from './UpdateBalancesSheet';
import type { Account, BalanceSheet, BalanceSheetCard } from '../../types/api';

const card = (over: Partial<BalanceSheetCard>): BalanceSheetCard => ({
    accountId: 'main', name: 'Основная карта', kind: 'DEBIT', isDefault: true,
    byRecords: 48_800, updatedOn: '2026-11-12', transfersSinceUpdate: null, ...over,
});
const SHEET: BalanceSheet = {
    cards: [
        card({}),
        card({ accountId: 'tg', name: 'Вторая', isDefault: false, byRecords: 10_000, transfersSinceUpdate: 0 }),
        card({ accountId: 'dep', name: 'Отпуск', kind: 'DEPOSIT', isDefault: false, byRecords: 30_000, transfersSinceUpdate: 0 }),
        card({ accountId: 'new', name: 'Вклад', kind: 'DEPOSIT', isDefault: false, byRecords: null, updatedOn: null }),
        card({ accountId: 'cr1', name: 'Т-Банк', kind: 'CREDIT', isDefault: false, byRecords: 85_000 }),
        card({ accountId: 'cr2', name: 'Альфа', kind: 'CREDIT', isDefault: false, byRecords: 40_000, updatedOn: '2026-11-20' }),
    ],
    onCredit: { since: '2026-11-12', amount: 4_500 },
};
const GAS = { id: 'gas', name: 'Карта для бензина', trackBalance: false } as Account;
const clean = (html: string) => html.replace(/ /g, ' ');
const form = (initial: Record<string, string> = {}, sheet = SHEET) => clean(renderToStaticMarkup(
    <BalancesForm sheet={sheet} spending={[GAS]} today="2026-11-23" onSaved={() => {}} initial={initial} />));
const inOrder = (html: string, words: string[]) => {
    const pos = words.map(w => html.indexOf(w));
    expect(pos.every(p => p >= 0), `нет: ${words.filter((_, i) => pos[i] < 0).join(', ')}`).toBe(true);
    expect([...pos].sort((x, y) => x - y)).toEqual(pos);
};
const read = (path: string) => readFileSync(new URL(path, import.meta.url), 'utf8');

describe('лист «Обновить остатки» (S4-U1)', () => {
    it('строки в порядке сервера: имя, «по записям» или дата, поле с прежним числом', () => {
        const html = form();
        inOrder(html, [
            'Основная карта', 'по записям 48 800', 'placeholder="48 800"',
            'Вторая', 'обновлён 12 нояб.',
            'Отпуск', 'по записям 30 000',
            'Вклад', 'первое обновление', 'placeholder="0"',
            'Т-Банк, доступно', 'Альфа, доступно', 'обновлён 20 нояб.',
            'Карта для бензина', 'остаток не нужен',
        ]);
    });

    it('«в кредит записано» — один раз, под последней кредиткой', () => {
        const html = form();
        const line = 'с 12 нояб. в кредит записано 4 500 ₽';
        expect(html.split(line)).toHaveLength(2);
        expect(html.indexOf(line)).toBeGreaterThan(html.indexOf('Альфа, доступно'));
        expect(form({}, { ...SHEET, onCredit: null })).not.toContain('в кредит записано');
    });

    it('ничего не введено — «Сохранить» закрыт, «Свободно станет» нет', () => {
        const html = form();
        expect(html).toMatch(/<button type="submit"[^>]*disabled=""[^>]*>Сохранить<\/button>/);
        expect(html).not.toContain('Свободно');
    });

    it('введено — разница словами под полем, «Сохранить» открыт; пустые поля — без строки', () => {
        const html = form({ main: '48100', dep: '30240' });
        inOrder(html, ['Основная карта', 'В банке на 700 ₽ меньше, чем по записям.', 'Отпуск',
            'В банке на 240 ₽ больше, чем по записям, — обычно это проценты.']);
        expect(html).toMatch(/<button type="submit"[^>]*>Сохранить<\/button>/);
        expect(html).not.toMatch(/<button type="submit"[^>]*disabled=""/);
        expect(html.match(/чем по записям|чем в прошлый раз/g)).toHaveLength(2);
    });

    it('нечитаемое — сказано под полем, поле выделено, «Сохранить» закрыт', () => {
        const html = form({ main: '48100', cr1: 'много' });
        expect(html).toContain('Не понял сумму в «Т-Банк, доступно» — нужно число, можно 0.');
        expect(html).toMatch(/aria-invalid="true"[^>]*aria-label="Т-Банк, доступно"|aria-label="Т-Банк, доступно"[^>]*aria-invalid="true"/);
        expect(html).toMatch(/<button type="submit"[^>]*disabled=""/);
    });

    it('слова пользователей: ни «якоря», ни «дрейфа» (правило 13)', () => {
        const html = form({ main: '48100' });
        expect(html).not.toMatch(/якор|Заякор|дрейф|приёмник/i);
    });
});

describe('запрос и предпросмотр', () => {
    const src = read('./UpdateBalancesSheet.tsx');

    it('пишет пакет только заполненных полей; «Свободно станет» — ответ предпросмотра на то же введённое', () => {
        expect(src).toContain('const batch = batchOf(sheet.cards, raws);');
        expect(src).toContain('onSaved(savedText(await saveBalances(batch), sheet.cards));');
        expect(src).toContain('previewBalances({ entries: JSON.parse(key) })');
        // Устаревший ответ молчит: цифру дописали, пока сервер считал прежнее.
        expect(src).toContain('const shown = preview && preview.key === key ? preview.result : null;');
        expect(src).toContain('{shown && <span role="status" className="px-1 text-sf-note">{freeLine(shown)}</span>}');
    });

    it('предпросмотр не шлёт «записано»: экраны под листом не перечитываются на каждую цифру', () => {
        expect(read('../../api/index.ts')).toContain("postRead<BalanceBatchResult>('/balance-checkpoints/preview', dto)");
    });
});

describe('входы — все открывают этот лист (п. 16)', () => {
    it('прежнего листа остатка нет', () => {
        expect(existsSync(new URL('../pocket/ReanchorSheet.tsx', import.meta.url))).toBe(false);
    });

    it('«Сегодня»: плитка «на картах», «С … записей нет», «Обновить» в «почему столько» и у «Сдвинуть»', () => {
        const today = read('../../pages/Today.tsx');
        expect(today).toContain('onClick={openUpdateBalances}\n                                aria-label={cardsTileLabel(data)}'.replace(/\n/g, today.includes('\r\n') ? '\r\n' : '\n'));
        expect(today).toMatch(/\{breakHint && \(\s*<button type="button" onClick=\{openUpdateBalances\}/);
        expect(today).toMatch(/<WhyPlate [^\n]{0,120}onReanchor=\{openUpdateBalances\}/);
        expect(today).toMatch(/<MovePlate [^\n]{0,120}onReanchor=\{openUpdateBalances\}/);
        expect(today).not.toContain('ReanchorSheet');
    });

    it('шапка, «Деньги» и карточка «Целей»', () => {
        expect(read('../frame/AppFrame.tsx')).toContain('onReanchor={() => setBalances(true)}');
        expect(read('../../pages/Capital.tsx')).toContain('onUpdate={openUpdateBalances}');
        const pocket = read('../PocketCard.tsx');
        expect(pocket.match(/onClick=\{openUpdateBalances\}/g)).toHaveLength(2);
        expect(pocket).not.toContain('ReanchorSheet');
    });
});
