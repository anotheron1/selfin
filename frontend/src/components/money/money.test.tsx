/**
 * Экран «Деньги» (срез 4, пп. 8–11, 15; доски S4-W1, S4-W2, S4-M4, S4-P1). Разметка — на сервере, как frame.test.tsx;
 * нажатия проверяет стенд.
 */
import { describe, expect, it } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import MoneyView from './MoneyView';
import { CapitalItemForm, deleteRevisionAsk, revisionUpdate } from './CapitalItemSheet';
import AccountForm from '../accounts/AccountForm';
import { PRESETS } from '../accounts/AccountSheet';
import type { Account, CapitalItem, CapitalSummary, CapitalTrajectory, TargetFund } from '../../types/api';

const acc = (over: Partial<Account>): Account => ({
    id: over.name ?? 'a', name: 'Карта', kind: 'DEBIT', trackBalance: true, purposeCategoryId: null,
    purposeCategoryName: null, creditLimit: null, availableFloor: null, isDefault: false, sortOrder: 0,
    balance: null, balanceDate: null, debt: null, allocatedThisMonth: null, floorSuggestion: null, ...over,
});
const item = (name: string, kind: 'ASSET' | 'LIABILITY', currentValue: number, isArchived = false): CapitalItem => ({
    id: name, kind, name, description: null, createdAt: '2026-05-12T00:00:00Z', currentValue,
    lastValuedAt: '2026-10-01', isArchived,
});

const main = acc({ name: 'Основная карта', isDefault: true, balance: 48800, balanceDate: '2026-11-12' });
const credit = acc({ name: 'Кредитка', kind: 'CREDIT', balance: 85000, creditLimit: 100000, debt: 15000, availableFloor: 90000 });
const cash = acc({ name: 'Наличные', kind: 'CASH', trackBalance: false, purposeCategoryName: 'Наличные', allocatedThisMonth: 3000 });
const deposit = acc({ name: 'Вклад', kind: 'DEPOSIT', balance: 99999.99, balanceDate: '2026-11-01' });

const summary = (items: CapitalItem[]): CapitalSummary => ({
    total: 653800, liquid: 138800, assetsTotal: 850000, liabilitiesTotal: 335000, cardDebts: 15000, items,
    deltas: { month: 12400, quarter: 0, year: 0 },
});
const points = (first: string): CapitalTrajectory['points'] => [
    { date: first, capital: 612000, liquid: 0, assets: 0, liabilities: 0 },
    { date: '2026-06-30', capital: 630000, liquid: 0, assets: 0, liabilities: 0 },
    { date: '2026-11-22', capital: 653800, liquid: 0, assets: 0, liabilities: 0 },
];

const noop = () => {};
const render = (p: { accounts: Account[]; items: CapitalItem[]; funds?: TargetFund[]; first?: string }) =>
    renderToStaticMarkup(
        <MoneyView summary={summary(p.items)} accounts={p.accounts} funds={p.funds ?? []} points={points(p.first ?? '2026-05-12')}
            today="2026-11-22" onUpdate={noop} onAddCard={noop} onAccount={noop} onItem={noop} onAddItem={noop} />,
    ).replace(/ /g, ' ');

const at = (html: string, words: string[]) => words.map(w => html.indexOf(w));

describe('«Деньги» (п. 9)', () => {
    const full = render({
        accounts: [deposit, cash, credit, main],
        items: [item('Машина', 'ASSET', 850000), item('Кредит на машину', 'LIABILITY', 320000), item('Дача', 'ASSET', 0, true)],
        funds: [{ id: 'f', name: 'Отпуск', accountId: 'Вклад' } as TargetFund],
    });

    it('капитал, за месяц, «есть · должен», обе кнопки — сверху', () => {
        const pos = at(full, ['Капитал — что есть минус что должен', '653 800', '+12 400 ₽', 'за месяц',
            'есть 988 800 · должен 335 000', 'Обновить остатки', 'Добавить карту', '>Основная<']);
        expect(pos.every(p => p >= 0)).toBe(true);
        expect([...pos].sort((a, b) => a - b)).toEqual(pos);
    });

    it('группы по порядку доски', () => {
        const pos = at(full, ['>Основная<', '>Для отдельных трат<', '>Накопительные<', '>Имущество<', '>Долги<',
            'Имущество или долг', 'Архивные · 1', 'Капитал по месяцам']);
        expect(pos.every(p => p >= 0)).toBe(true);
        expect([...pos].sort((a, b) => a - b)).toEqual(pos);
    });

    it('кредитка — парой с основной, до «Для отдельных трат»', () => {
        const [m, c, s] = at(full, ['Основная карта', 'долг 15 000 из 100 000', '>Для отдельных трат<']);
        expect(m).toBeLessThan(c);
        expect(c).toBeLessThan(s);
        expect(full).toContain('возвращаю до 90 000');
        expect(full).not.toContain('Кредитка — не деньги, а долг');
    });

    it('накопительный — с копилкой, «в «Свободно» не входят»', () => {
        expect(full).toContain('копилка «Отпуск» · обновлён 1 нояб.');
        expect(full).toContain('в «Свободно» не входят');
    });

    it('копейки — без округления вверх', () => {
        expect(full).toContain('99 999,99 ₽');
        expect(full).not.toContain('100 000 ₽');
    });

    it('у долга — минус, у групп — итог', () => {
        expect(full).toContain('−320 000 ₽');
        expect(full).toContain('850 000 ₽');
    });

    it('итог группы — сумма её строк, у долгов — без минуса', () => {
        const html = render({ accounts: [main], items: [item('Машина', 'ASSET', 850000), item('Гараж', 'ASSET', 150000),
            item('Кредит на машину', 'LIABILITY', 320000), item('Займ', 'LIABILITY', 30000)] });
        expect(html).toMatch(/>Имущество<\/h2><span[^>]*>1 000 000 ₽</);
        expect(html).toMatch(/>Долги<\/h2><span[^>]*>350 000 ₽</);
    });

    it('у каждой группы — строка «как записывать»', () => {
        expect(full).toContain('Сюда приходит доход, отсюда платишь.');
        expect(full).toContain('Снял наличные — одна трата «Наличные».');
        expect(full).toContain('Отложил — переведи на счёт в банке и обнови его остаток.');
    });

    it('на 1440 — две колонки: группы слева, капитал, кнопки и график справа', () => {
        expect(full).toMatch(/lg:grid-cols-\[minmax\(0,1fr\)_420px\]/);
        expect(full).toMatch(/lg:col-start-2[^"]*"[^>]*>[\s\S]*Капитал — что есть минус что должен/);
    });

    it('без «Что такое капитал?» и старых слов', () => {
        for (const w of ['Что такое капитал', 'Активы', 'АКТИВЫ', 'Обязательства', 'приёмник', 'якорь']) {
            expect(full).not.toContain(w);
        }
    });
});

describe('вторая карта «на всё» (п. 10)', () => {
    it('в «Основной», строкой «считается вместе с основной»', () => {
        const html = render({ accounts: [main, acc({ name: 'Вторая', balance: 1000, balanceDate: '2026-11-10' })], items: [] });
        const [g, m, s, t] = at(html, ['>Основная<', 'Основная карта', 'Вторая', 'считается вместе с основной']);
        expect(g).toBeLessThan(m);
        expect(m).toBeLessThan(s);
        expect(s).toBeLessThan(t);
    });
});

describe('новый человек (п. 11, S4-M4)', () => {
    const html = render({ accounts: [cash, { ...main, balance: null, balanceDate: null }], items: [] });

    it('основная — «первое обновление», наличные — первой строкой «Для отдельных трат»', () => {
        expect(html).toContain('первое обновление');
        const [s, c] = at(html, ['>Для отдельных трат<', '>Наличные<']);
        expect(s).toBeLessThan(c);
    });

    it('остальное — приглашениями; «Кредитка» — под основной, в паре', () => {
        const pos = at(html, ['Основная карта', '>Кредитка<', '>Для отдельных трат<', '>Накопительный счёт<', '>Имущество и долги<']);
        expect(pos.every(p => p >= 0)).toBe(true);
        expect([...pos].sort((a, b) => a - b)).toEqual(pos);
        expect(html).not.toContain('Имущество или долг');
        expect(html).not.toContain('Архивные');
    });
});

describe('график (п. 8)', () => {
    it('истории меньше года — выбрано «Всё», изменение — с первой точки', () => {
        const html = render({ accounts: [main], items: [], first: '2026-05-12' });
        expect(html).toMatch(/checked="" value="all"/);
        expect(html).toContain('+41 800 ₽');
        expect(html).toContain('с 12 мая');
    });

    it('больше года — выбран «Год»', () => {
        const html = render({ accounts: [main], items: [], first: '2025-10-01' });
        expect(html).toMatch(/checked="" value="year"/);
        expect(html).not.toMatch(/checked="" value="all"/);
        expect(html).not.toContain('+41 800 ₽');
        // «Год» режет точку старше года: изменение — от июня, а не «История появится…».
        expect(html).toContain('+23 800 ₽');
    });

    it('чипы — Квартал · 6 мес · Год · 3 года · Всё', () => {
        const html = render({ accounts: [main], items: [] });
        expect(at(html, ['>Квартал<', '>6 мес<', '>Год<', '>3 года<', '>Всё<']).every((p, i, a) => p > (a[i - 1] ?? -1))).toBe(true);
    });
});

describe('лист вещи и долга (п. 15, S4-P1)', () => {
    const sheet = (i: CapitalItem) => renderToStaticMarkup(
        <CapitalItemForm mode={{ type: 'view', item: i }} history={[
            { id: 'r1', itemId: i.id, value: i.currentValue, valuedAt: '2026-10-01', note: 'по объявлениям', createdAt: '' },
        ]} onChanged={noop} onDone={noop} />,
    ).replace(/ /g, ' ');

    it('вещь: оценка, «Переоценить» с датой и заметкой, история, «Продал — с даты», «Удалить насовсем»', () => {
        const html = sheet(item('Машина', 'ASSET', 850000));
        const pos = at(html, ['Машина', 'имущество · оценка 1 окт.', '850 000', 'Переоценить', 'Сколько стоит', 'На дату',
            'Заметка (необязательно)', 'Сохранить оценку', 'История оценок', '>по объявлениям<', 'Продал — с даты', 'Удалить насовсем']);
        expect(pos.every(p => p >= 0)).toBe(true);
        expect([...pos].sort((a, b) => a - b)).toEqual(pos);
        expect(html).not.toContain('Погасил');
    });

    it('долг: «Погасил — с даты», «Сколько должен»', () => {
        const html = sheet(item('Кредит на машину', 'LIABILITY', 320000));
        expect(html).toContain('Погасил — с даты');
        expect(html).toContain('Сколько должен');
        expect(html).toContain('долг · оценка 1 окт.');
        expect(html).not.toContain('Продал');
    });

    it('новая запись — вид выбирается в листе', () => {
        const html = renderToStaticMarkup(<CapitalItemForm mode={{ type: 'create' }} history={[]} onChanged={noop} onDone={noop} />);
        expect(html).toContain('Имущество или долг');
        expect(html).toMatch(/checked="" value="ASSET"/);
        expect(html).toContain('value="LIABILITY"');
    });
});

describe('«+» в группе уже знает ответ (п. 11, S4-A3)', () => {
    const form = (preset: keyof typeof PRESETS) => renderToStaticMarkup(
        <AccountForm initial={null} preset={PRESETS[preset]} categories={[]} error={null} onSubmit={noop} onCancel={noop} />);

    it('ответы групп: для трат — без остатка, накопительный — вклад, кредитка — кредит', () => {
        expect(PRESETS).toEqual({
            spending: { kind: 'DEBIT', trackBalance: false },
            savings: { kind: 'DEPOSIT', trackBalance: true },
            credit: { kind: 'CREDIT', trackBalance: true },
        });
    });

    it('форма открывается с ответом: кредитка — с лимитом и «возвращаю до», для трат — без остатка', () => {
        expect(form('credit')).toMatch(/<option value="CREDIT" selected=""/);
        expect(form('credit')).toContain('Кредитный лимит');
        expect(form('spending')).toMatch(/<option value="DEBIT" selected=""/);
        expect(form('spending')).toMatch(/<input type="checkbox"[^>]*>/);
        expect(form('spending')).not.toMatch(/<input type="checkbox"[^>]*checked=""/);
        expect(form('credit')).toMatch(/<input type="checkbox"[^>]*checked=""/);
        expect(form('savings')).toMatch(/<option value="DEPOSIT" selected=""/);
    });
});

describe('лист вещи и долга: записи (ревью #241)', () => {
    const r = { id: 'r', itemId: 'i', value: 120000, valuedAt: '2026-11-01', note: 'по объявлениям', createdAt: '' };
    const src = async (path: string) => (await import('node:fs')).readFileSync(new URL(path, import.meta.url), 'utf8');

    it('стёртая заметка уходит пустой строкой — сервер правит заметку, только если она пришла', () => {
        expect(revisionUpdate(r, '120000', '2026-11-01', '  ')).toEqual({ value: 120000, valuedAt: '2026-11-01', note: '' });
    });
    it('запись «Продал» с нулём правится — дата и заметка, сумма остаётся 0', () => {
        expect(revisionUpdate({ ...r, value: 0 }, '0', '2026-10-15', '')).toEqual({ value: 0, valuedAt: '2026-10-15', note: '' });
        expect(revisionUpdate(r, '0', '2026-11-01', '')).toBeNull();
    });
    it('удаление единственной записи — вещь уходит в «Архивные», и вопрос это говорит', () => {
        expect(deleteRevisionAsk(true)).toContain('уйдёт в «Архивные»');
        expect(deleteRevisionAsk(false)).toContain('оценкой станет предыдущая');
    });
    it('«Продал — с даты» — своя дата и не раньше последней оценки: иначе ноль ляжет под неё и вещь не уйдёт в архив', async () => {
        const s = await src('./CapitalItemSheet.tsx');
        expect(s).toContain('addCapitalRevaluation(item.id, { value: 0, valuedAt: goneDate })');
        expect(s).toContain('min={item.lastValuedAt ?? undefined}');
    });
    it('экран: устаревший ответ не затирает новый, сбой фонового перечитывания не прячет экран', async () => {
        const s = await src('../../pages/Capital.tsx');
        expect(s).toMatch(/if \(n !== seq\.current\) return;\s+loaded\.current = true;/);
        expect(s).toMatch(/\.catch\(\(\) => \{\s+if \(n !== seq\.current\) return;/);
        expect(s).toContain('if (!loaded.current) setLoadFailed(true);');
    });
});

describe('ревью Gemini на #241', () => {
    const src = async (path: string) => (await import('node:fs')).readFileSync(new URL(path, import.meta.url), 'utf8');

    it('график: период по умолчанию считается заново, пока человек не выбрал свой', async () => {
        const s = await src('./MoneyChart.tsx');
        expect(s).toContain('const range = picked ?? defaultRange(points[0]?.date ?? null, today);');
    });
    it('график, история и лист вещи — дата другого года с годом', async () => {
        expect(await src('./MoneyChart.tsx')).toMatch(/с \$\{fmtDayYear\(change\.from, today\)\}/);
        expect(await src('./MoneyChart.tsx')).toContain('{fmtDayYear(p.date, today)} · {fmtAmount(p.capital)}');
        const sheet = await src('./CapitalItemSheet.tsx');
        expect(sheet).toContain('{fmtDayYear(r.valuedAt, today())}');
        expect(sheet).toContain('`оценка ${fmtDayYear(item.lastValuedAt, today())}`');
    });
    it('пустая дата не уходит: «Добавить» и «Сохранить оценку» закрыты', async () => {
        const sheet = await src('./CapitalItemSheet.tsx');
        expect(sheet).toContain('disabled={busy || !name.trim() || amount == null || !date}');
        expect(sheet).toContain('disabled={busy || amount == null || !date}>Сохранить оценку');
    });
    it('лист закрывается после записи — без чтения удалённой вещи', async () => {
        const sheet = await src('./CapitalItemSheet.tsx');
        expect(sheet).not.toMatch(/onChanged\(\); onDone\(\);/);
    });
    it('«Продал» раскрывается с сегодняшней датой', async () => {
        expect(await src('./CapitalItemSheet.tsx')).toContain("if (key === 'gone') setGoneDate(today());");
    });
    it('«Счетов» в «Настройках» больше нет — справка не отсылает туда', async () => {
        expect(await src('../../lib/help.ts')).not.toContain('«Счетов»');
    });
    it('подпись карты обрезается многоточием — текст внутри своего span', async () => {
        expect(await src('./MoneyView.tsx')).toContain('{i === 0 && mark}<span className="min-w-0 truncate">{s}</span>');
    });
    it('архивировал последний долг в подсказке — лист закрывается, а не висит пустым', async () => {
        const s = await src('../accounts/AccountSheet.tsx');
        expect(s).toContain('if (left.length === 0) { done(copy.capitalHintArchived); return; }');
        expect(s).toContain('onEdit={() => { setEditing(true); setFormError(null); setNotice(null); }}');
    });
});

describe('ширина «Денег» на 1440', () => {
    it('страница без прежних 672 px — иначе две колонки не помещаются (стенд, 1440)', async () => {
        const app = (await import('node:fs')).readFileSync(new URL('../../App.tsx', import.meta.url), 'utf8');
        expect(app).toContain("['/', '/budget', '/capital'].includes(path) ? undefined : 'max-w-2xl mx-auto'");
    });
});
