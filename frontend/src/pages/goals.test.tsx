/**
 * «Цели» среза 4 (пп. 19–20; доски S4-G1–S4-G3): НЗ первой — на основной карте и на своём счёте; лист НЗ в обоих «Где
 * лежит»; карточка копилки на своём счёте; «Где будут лежать деньги» у новой цели. Листы — Radix Dialog на портале, на
 * сервере они не рисуются: FormSheet подменён простым div. Нажатия проверяет стенд; куда ведут кнопки — исходник.
 */
import { readFileSync } from 'node:fs';
import { describe, expect, it, vi } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import type { ReactNode } from 'react';
import type { Account, PocketResponse, TargetFund } from '../types/api';
import { fmtRub } from '../lib/format';
import { NZ_MEANING } from '../lib/help';
import { NZ_ON_ACCOUNT_NOTE, NZ_WHERE_NOTE } from '../lib/reserve';
import { WHERE_MAIN_SUB, WHERE_OWN_SUB } from '../lib/fundAccount';

vi.mock('../components/FormSheet', () => ({ default: ({ children }: { children: ReactNode }) => <div>{children}</div> }));
const { ReserveCard, FundCard, FundWhere } = await import('./Funds');
const { NzForm } = await import('../components/pocket/NzSheet');

// Пробелы схлопываются, кроме неразрывного: им fmtRub отделяет разряды и ₽.
const text = (html: string) => html.replace(/<[^>]+>/g, ' ').replace(/[ \t\r\n]+/g, ' ');
const read = (path: string) => readFileSync(new URL(path, import.meta.url), 'utf8');
/** Радиокнопка выбора со значением `value` отмечена. */
const checked = (html: string, value: string) => (html.match(new RegExp(`<input[^>]*value="${value}"[^>]*>`))?.[0] ?? '').includes('checked');

const fund = (over: Partial<TargetFund>): TargetFund => ({
    id: 'otpusk', name: 'Отпуск', targetAmount: 64_000, currentBalance: 30_000, accountId: null, status: 'FUNDING', priority: 1,
    targetDate: null, estimatedCompletionDate: null, purchaseType: 'SAVINGS', creditRate: null, creditTermMonths: null,
    wishlistStatus: null, reserve: false, ...over,
});
const account = (over: Partial<Account>): Account => ({
    id: 'dep', name: 'Вклад', kind: 'DEPOSIT', trackBalance: true, purposeCategoryId: null, purposeCategoryName: null,
    creditLimit: null, availableFloor: null, isDefault: false, sortOrder: 1, balance: 60_000, balanceDate: '2026-11-01',
    debt: null, allocatedThisMonth: null, floorSuggestion: null, ...over,
});
const main = account({ id: 'main', name: 'Основная', kind: 'DEBIT', isDefault: true });
const dep = account({});
const sav = account({ id: 'sav', name: 'Накопительный' });
const nzCard = fund({ id: 'nz', name: 'НЗ', reserve: true, targetAmount: 30_000, currentBalance: 0, priority: 0 });
const nzDep = { ...nzCard, accountId: 'dep', currentBalance: 60_000 };
const otpuskSav = fund({ accountId: 'sav' });
const pocket = { pocket: 17_000, buffer: 30_000, horizon: { endDate: '2026-12-05' } } as PocketResponse;
const noop = () => {};

describe('карточка НЗ — первой в «Целях» (п. 19; S4-G1, S4-G2)', () => {
    it('на основной карте: «сколько держать», что это значит для «Свободно», «Изменить» и «Перенести на свой счёт»', () => {
        const t = text(renderToStaticMarkup(<ReserveCard nz={nzCard} account={undefined} onSheet={noop} onSave={noop} />));
        expect(t).toContain(`НЗ — запас ${fmtRub(30_000)}`);
        expect(t).toContain('лежит на основной карте');
        expect(t).toContain(`«Свободно» считается так, чтобы на карте оставалось не меньше ${fmtRub(30_000)}. Свой счёт для запаса проще`);
        expect(t).toContain('Изменить');
        expect(t).toContain('Перенести на свой счёт');
        expect(t).not.toContain('Отложить');
    });

    it('на своём счёте: остаток счёта, где лежит и когда обновлён; «Отложить», «Обновить остаток», карандаш — лист НЗ', () => {
        const html = renderToStaticMarkup(<ReserveCard nz={nzDep} account={dep} onSheet={noop} onSave={noop} />);
        const t = text(html);
        expect(t).toContain(`НЗ — запас ${fmtRub(60_000)}`);
        expect(t).toContain('лежит на «Вклад» · обновлён 1 нояб.');
        expect(t).toContain(NZ_ON_ACCOUNT_NOTE);
        expect(t).toContain('Отложить');
        expect(t).toContain('Обновить остаток');
        expect(html).toContain('aria-label="Изменить НЗ"');
        expect(t).not.toContain('Перенести на свой счёт');
    });
});

describe('копилка на своём счёте (п. 20; S4-G1)', () => {
    it('где лежит и когда обновлён; «Отложить» и «Обновить остаток»; прежнего запрета перевода нет', () => {
        const t = text(renderToStaticMarkup(<FundCard fund={otpuskSav} account={sav} onTransfer={noop} onSave={noop} onEdit={noop} />));
        expect(t).toContain('лежит на «Накопительный» · обновлён 1 нояб.');
        expect(t).toContain('Отложить');
        expect(t).toContain('Обновить остаток');
        expect(t).not.toContain('Пополнять переводом нельзя');
    });

    it('цель достигнута — «Отложить» нет, остаток обновить можно', () => {
        const t = text(renderToStaticMarkup(
            <FundCard fund={{ ...otpuskSav, status: 'REACHED' }} account={sav} onTransfer={noop} onSave={noop} onEdit={noop} />));
        expect(t).not.toContain('Отложить');
        expect(t).toContain('Обновить остаток');
    });

    it('копилка без счёта — как была: «Пополнить», без «Обновить остаток»', () => {
        const html = renderToStaticMarkup(<FundCard fund={fund({})} account={undefined} onTransfer={noop} onSave={noop} onEdit={noop} />);
        expect(html).toContain('title="Пополнить из свободных денег"');
        expect(text(html)).not.toContain('Обновить остаток');
        expect(text(html)).not.toContain('Отложить');
    });

    it('«Отложить» — форма «+» с этой копилкой: там строка «на картах станет на … меньше» (S4-G4)', () => {
        const page = read('./Funds.tsx');
        expect(page).toContain("<QuickAddModal preset={{ type: 'FUND_TRANSFER', targetFundId: saveTo.id }}");
        expect(page).toContain('onSheet={toAccount => setNzSheet({ toAccount })} onSave={setSaveTo} />');
        expect(read('../components/Fab.tsx')).toContain('[accountFundLine(fund.name, fundAccount, amount), afterLine(effect)]');
    });
});

describe('новая цель: «Где будут лежать деньги» (п. 20; S4-G3)', () => {
    const where = (w: 'account' | 'main') => renderToStaticMarkup(
        <FundWhere where={w} onWhere={noop} accountId="dep" onAccount={noop} onAdd={noop}
            choices={[{ id: 'dep', label: 'Вклад', taken: false }, { id: 'sav', label: 'Накопительный — здесь копилка «Отпуск»', taken: true }]} />);

    it('свой счёт — советуем, цена каждого ответа — одной строкой; выбран свой счёт', () => {
        const html = where('account');
        const t = text(html);
        expect(t).toContain('Где будут лежать деньги');
        expect(t).toContain(`На своём счёте — советуем ${WHERE_OWN_SUB}`);
        expect(t).toContain(`На основной карте ${WHERE_MAIN_SUB}`);
        expect(html).toMatch(/<input type="radio" name="fund-where"[^>]*checked/);
    });

    it('счета: свободный — выбором, занятый другой копилкой — виден, но недоступен; «Новый счёт» — здесь же', () => {
        const html = where('account');
        expect(checked(html, 'dep')).toBe(true);
        expect(html).toMatch(/<button[^>]*disabled=""[^>]*>Накопительный — здесь копилка «Отпуск»<\/button>/);
        expect(text(html)).toContain('Новый счёт');
    });

    it('на основной карте — выбора счёта нет', () => {
        expect(text(where('main'))).not.toContain('Новый счёт');
    });

    it('счёт уходит только с ответом «На своём счёте»; без счёта цель не создать', () => {
        const page = read('./Funds.tsx');
        expect(page).toContain("accountId: where === 'account' ? accountId || null : null,");
        expect(page).toContain("disabled={loading || !name.trim() || (where === 'account' && !accountId)}>");
    });
});

describe('лист НЗ (п. 19; S4-G2) — оба «Где лежит»', () => {
    const form = (nz: TargetFund, toAccount = false) => renderToStaticMarkup(
        <NzForm nz={nz} funds={[nz, otpuskSav]} accounts={[main, dep, sav]} pocket={pocket} toAccount={toAccount}
            onClose={noop} onSaved={noop} onAccountAdded={noop} />);

    it('на основной карте: «Сколько держать», что это значит и «Свободно» сейчас за вычетом НЗ', () => {
        const html = form(nzCard);
        const t = text(html);
        expect(t).toContain(NZ_MEANING);
        expect(html).toContain('aria-label="Сколько держать"');
        expect(html).toContain('value="30000"');
        expect(checked(html, 'card')).toBe(true);
        expect(t).toContain(NZ_WHERE_NOTE);
        expect(t).toContain(`«Свободно до 5 дек.» — ${fmtRub(17_000)}: уже за вычетом ${fmtRub(30_000)} НЗ.`);
        expect(t).not.toContain('Новый счёт');
    });

    it('на своём счёте: счета без основной, занятый — недоступен, «Новый счёт»; «Сколько держать» нет', () => {
        const html = form(nzDep);
        const t = text(html);
        expect(checked(html, 'account')).toBe(true);
        expect(checked(html, 'dep')).toBe(true);
        expect(html).toMatch(/<button[^>]*disabled=""[^>]*>Накопительный — здесь копилка «Отпуск»<\/button>/);
        expect(t).toContain('Новый счёт');
        expect(html).not.toContain('value="main"');
        expect(html).not.toContain('aria-label="Сколько держать"');
        expect(t).not.toContain('«Свободно до');
    });

    it('«Перенести на свой счёт» открывает лист сразу на своём счёте; без выбранного счёта не сохранить', () => {
        const html = form(nzCard, true);
        expect(checked(html, 'account')).toBe(true);
        expect(html).not.toContain('aria-label="Сколько держать"');
        expect(html).toMatch(/<button[^>]*disabled=""[^>]*>Сохранить<\/button>/);
        expect(form(nzCard)).not.toMatch(/<button[^>]*disabled=""[^>]*>Сохранить<\/button>/);
    });

    it('лист открывают оба места: «почему столько» на «Сегодня» и карточка кармашка, и карточка НЗ в «Целях»', () => {
        expect(read('./Today.tsx')).toContain("<NzSheet open={sheet === 'nz'}");
        expect(read('../components/PocketCard.tsx')).toContain('<NzSheet');
        expect(read('./Funds.tsx')).toContain('<NzSheet open={nzSheet != null} toAccount={nzSheet?.toAccount}');
    });
});
