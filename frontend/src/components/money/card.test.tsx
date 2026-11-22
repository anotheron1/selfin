/**
 * Лист карты и «Добавить карту» (срез 4, пп. 12–14; доски S4-C2–S4-C5, S4-A1, S4-A3–S4-A5). Разметка — на сервере, как
 * money.test.tsx; нажатия проверяет стенд.
 */
import { describe, expect, it } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { CardBody } from './CardSheet';
import { CardForm } from './AddCardSheet';
import MoneyView from './MoneyView';
import { WORDS } from '../../lib/moneyCard';
import type { Account, BalanceCheckpoint, CapitalItem, CapitalSummary, Category, TargetFund } from '../../types/api';

const acc = (over: Partial<Account>): Account => ({
    id: 'a', name: 'Карта', kind: 'DEBIT', trackBalance: true, purposeCategoryId: null, purposeCategoryName: null,
    creditLimit: null, availableFloor: null, isDefault: false, sortOrder: 0, balance: null, balanceDate: null, debt: null,
    allocatedThisMonth: null, floorSuggestion: null, ...over,
});
const cp = (id: string, date: string, amount: number, computedBalance: number | null, accountId = 'a'): BalanceCheckpoint => ({
    id, date, amount, accountId, accountName: '', createdAt: `${date}T10:00:00Z`, computedBalance,
    drift: computedBalance == null ? null : amount - computedBalance,
});
const cats = [{ id: 'petrol', name: 'Бензин', type: 'EXPENSE' }, { id: 'food', name: 'Продукты', type: 'EXPENSE' }] as Category[];
const noop = () => {};
const clean = (html: string) => html.replace(/ /g, ' ');

const body = (a: Account, history: BalanceCheckpoint[] = [], funds: TargetFund[] = []) => clean(renderToStaticMarkup(
    <CardBody account={a} history={history} funds={funds} categories={cats} today="2026-11-22" onRecord={noop} onDone={noop} />));
const at = (html: string, words: string[]) => words.map(w => html.indexOf(w));
const inOrder = (html: string, words: string[]) => {
    const pos = at(html, words);
    expect(pos.every(p => p >= 0), `нет: ${words.filter((_, i) => pos[i] < 0).join(', ')}`).toBe(true);
    expect([...pos].sort((x, y) => x - y)).toEqual(pos);
};
/** Значение строки настроек листа. */
const row = (v: string) => `<span class="truncate">${v}</span>`;
const OLD = ['приёмник', 'якор', 'планк', 'Природа', 'Зона ответственности', 'Слежу за остатком', 'Редактировать'];

describe('лист карты (п. 12)', () => {
    const main = acc({ name: 'Основная карта', isDefault: true, balance: 47000, balanceDate: '2026-11-12' });
    const mainHistory = [cp('1', '2026-10-14', 51000, null), cp('2', '2026-11-12', 48800, 48500)];

    it('основная (S4-C2): история линией, нажатая точка, обновить, строки, «Основную не удалить»', () => {
        const html = body(main, mainHistory);
        inOrder(html, ['Основная карта', 'основная · от неё считается «Свободно»', '47 000', 'по записям',
            'Остаток по обновлениям', 'точки — из банка, пунктир — по записям', 'stroke-dasharray',
            '12 нояб. — 48 800 ₽', 'По записям было 48 500 ₽: в банке на 300 ₽ больше.', 'Поправить', 'Удалить',
            'Все обновления — 2', 'Обновить остаток', 'На другую дату',
            'Для чего эта карта', '>Основная<', 'Название', WORDS.mainNoDelete]);
        expect(html).not.toContain('Удалить карту');
        expect(html).not.toContain('Сделать основной');
        // последнее обновление сегодня — «, сегодня» у нажатой точки
        expect(body(main, [...mainHistory, cp('3', '2026-11-22', 47100, 47000)])).toContain('22 нояб., сегодня — 47 100 ₽');
        expect(html).not.toContain(', сегодня');
    });

    it('без обновлений — «первое обновление», без графика', () => {
        const html = body({ ...main, balance: null, balanceDate: null });
        expect(html).toContain('первое обновление');
        expect(html).toContain('Обновить остаток');
        expect(html).not.toContain('stroke-dasharray');
        expect(html).not.toContain('Все обновления');
    });

    it('вторая «на всё» (п. 10): «Сделать основной», ответ — заново, «Удалить карту»', () => {
        const html = body(acc({ name: 'Кошелёк', balance: 3000, balanceDate: '2026-11-10' }), [cp('1', '2026-11-10', 3000, null)]);
        inOrder(html, ['Кошелёк', 'на всё · считается вместе с основной', 'Сделать основной', 'На всё, вместе с основной', 'Удалить карту']);
    });

    it('вторая «на всё» без обновления — «Сделать основной» нет', () => {
        expect(body(acc({ name: 'Кошелёк' }))).not.toContain('Сделать основной');
    });

    it('кредитка (S4-C3): доступно из лимита, долг, «Возвращать до X?», лимит и «возвращаю до» строками', () => {
        const html = body(acc({
            name: 'Кредитка', kind: 'CREDIT', balance: 92000, balanceDate: '2026-11-22', creditLimit: 100000, debt: 8000,
            availableFloor: 90000, floorSuggestion: 92000,
        }), [cp('1', '2026-11-22', 92000, null)]);
        inOrder(html, ['Кредитка', 'кредитка · это долг', '92 000', 'доступно из 100 000',
            'Обновлена сегодня. Долг — 8 000 ₽ до лимита.', 'Доступно уже больше, чем «возвращаю до 90 000 ₽». Возвращать до 92 000 ₽?',
            'Да, до 92 000', 'Оставить 90 000', 'Обновить остаток', 'Для чего эта карта', row('Кредитка'), '>Лимит<', row('100 000 ₽'),
            '>Возвращаю до<', row('90 000 ₽'), 'Название', WORDS.floorNote, 'Удалить карту']);
        expect(html).not.toContain('Сделать основной');
    });

    it('кредитка: второе обновление — сравнение с прошлым разом, не «первое обновление»', () => {
        const html = body(acc({ kind: 'CREDIT', balance: 96000, balanceDate: '2026-11-22', creditLimit: 100000, debt: 4000 }),
            [cp('1', '2026-11-01', 92000, null), cp('2', '2026-11-22', 96000, null)]);
        expect(html).toContain('В прошлый раз было 92 000 ₽: на 4 000 ₽ больше.');
        expect(html).not.toContain('Первое обновление');
    });

    it('кредитка без «возвращать до» — плашки нет', () => {
        const html = body(acc({ kind: 'CREDIT', balance: 85000, balanceDate: '2026-11-22', creditLimit: 100000, debt: 15000 }));
        expect(html).not.toContain('Возвращать до');
        // «возвращаю до» задано, а доступно не выросло — сервер подсказки не дал
        expect(body(acc({ kind: 'CREDIT', balance: 85000, balanceDate: '2026-11-22', creditLimit: 100000, debt: 15000,
            availableFloor: 90000, floorSuggestion: null }))).not.toContain('Возвращать до');
    });

    it('накопительная (S4-C4): обновление и переводы, копилка, «Отложить сюда», рост — «обычно это проценты»', () => {
        const html = body(acc({ name: 'Накопительный счёт', kind: 'DEPOSIT', balance: 30000, balanceDate: '2026-11-03' }),
            [cp('1', '2026-10-01', 13820, null), cp('2', '2026-11-03', 22000, 21820)],
            [{ id: 'f', name: 'Отпуск', accountId: 'a', currentBalance: 30000, targetAmount: 64000 } as TargetFund,
                { id: 'g', name: 'Чужая', accountId: 'b', currentBalance: 1, targetAmount: null } as TargetFund]);
        inOrder(html, ['Накопительный счёт', 'накопительная · в «Свободно» не входит', '30 000', 'по записям',
            'Обновлён 3 нояб. на 22 000 ₽, после — отложено 8 000 ₽.', 'Здесь копилка «Отпуск»', '30 000 из 64 000 ₽',
            'Отложить сюда', 'По записям было 21 820 ₽: в банке на 180 ₽ больше — обычно это проценты.', 'Обновить остаток',
            '>Накопительная<']);
        expect(html).not.toContain('Чужая');
        expect(html).not.toContain('Сделать основной');
    });

    it('накопительная без копилки — «Отложить сюда» нет', () => {
        expect(body(acc({ kind: 'DEPOSIT', balance: 1, balanceDate: '2026-11-03' }))).not.toContain('Отложить сюда');
    });

    it('для отдельных трат (S4-C5): остатка нет, «записано в ноябре», «Записать «Бензин»», «Для каких трат»', () => {
        const html = body(acc({
            name: 'Карта для бензина', trackBalance: false, purposeCategoryId: 'petrol', purposeCategoryName: 'Бензин', allocatedThisMonth: 5000,
        }));
        inOrder(html, ['Карта для бензина', 'для отдельных трат · Бензин', '5 000', 'записано в ноябре',
            'Остаток этой карты selfin не ведёт.', 'В ноябре в «Бензин» записано 5 000 ₽ — с любой карты.',
            'Записать «Бензин»', '>Для отдельных трат<', 'Для каких трат', '>Бензин<', 'Название', 'Удалить карту']);
        expect(html).not.toContain('Обновить остаток');
        expect(html).not.toContain('stroke-dasharray');
        // обновления остались с тех пор, когда остаток вёлся, — истории у карты без остатка нет
        expect(body(acc({ trackBalance: false, purposeCategoryId: 'petrol', purposeCategoryName: 'Бензин' }), [cp('1', '2026-10-14', 5000, null)]))
            .not.toContain('Остаток по обновлениям');
    });

    // Нажатия разметка не видит (стенд их проверяет) — сторож по исходнику: что уходит из кнопок листа.
    it('кнопки листа: «Да, до X» пишет подсказку, «Отложить сюда» — в единственную копилку, «Записать» — расход своей категории', async () => {
        const { readFileSync } = await import('node:fs');
        const src = readFileSync(new URL('./CardSheet.tsx', import.meta.url), 'utf8');
        expect(src).toContain('creditLimit: a.creditLimit, availableFloor: a.floorSuggestion,');
        expect(src).toContain("onRecord({ type: 'FUND_TRANSFER', targetFundId: own.length === 1 ? own[0].id : undefined })");
        expect(src).toContain("onRecord({ type: 'EXPENSE', categoryId: a.purposeCategoryId! })");
        // «Все обновления» раскрываются нажатием: строка без «по записям» — с прошлым разом, как у точки
        expect(src).toContain('{diffLine(c, savings, prevOf(c))}');
        // вопрос «Удалить обновление?»: последнее — «первое обновление», иначе — от предыдущего
        expect(src).toContain("history.length === 1 ? 'Обновлений не останется — будет «первое обновление».' : 'Остаток посчитается от предыдущего.'");
        // имя окна «Добавить карту» — по ответу группы (FormSheet — портал, разметка его не видит)
        expect(readFileSync(new URL('./AddCardSheet.tsx', import.meta.url), 'utf8')).toContain("label={purpose ? PRESET[purpose].title : 'Новая карта'}");
    });

    it('для трат без категории — просит выбрать, записывать нечего', () => {
        const html = body(acc({ trackBalance: false }));
        expect(html).toContain('Выбери, для каких она трат');
        expect(html).not.toContain('Записать «');
    });

    it('старых слов нет ни в одном виде', () => {
        for (const html of [body(main, mainHistory), body(acc({ kind: 'CREDIT', creditLimit: 1, availableFloor: 1 })),
            body(acc({ trackBalance: false })), body(acc({ kind: 'DEPOSIT' }))]) {
            for (const w of OLD) expect(html).not.toContain(w);
        }
    });
});

describe('«Добавить карту» (п. 13)', () => {
    const form = (p: Parameters<typeof CardForm>[0]) => clean(renderToStaticMarkup(<CardForm {...p} />));
    const add = (purpose?: 'SPEND' | 'SAVINGS' | 'CREDIT') =>
        form({ account: null, purpose, categories: cats, busy: false, error: null, onSave: noop });

    it('вопрос списком (S4-A1): три ответа с пояснениями, без «на всё» и наличных; без ответа не отправить', () => {
        const html = add();
        inOrder(html, ['Новая карта', 'Название', 'Для чего эта карта?', 'Для отдельных трат', 'бензин, продукты',
            'Накопительная', 'вклад или накопительный счёт', 'Кредитка', 'не деньги, а долг', 'Добавить']);
        expect(html).not.toContain('На всё');
        expect(html).not.toContain('Наличные');
        expect(html).toMatch(/<button[^>]*type="submit"[^>]*disabled=""/);
    });

    it('«+» в группе для трат (S4-A3): без вопроса, категория чипами, как записывать, «ошибся группой»', () => {
        const html = add('SPEND');
        inOrder(html, ['Карта для отдельных трат', 'остаток не нужен — selfin его не ведёт', 'Название', 'Для каких трат',
            'Бензин', 'Продукты', 'Перевёл на неё 5 000 ₽ — запиши одну трату', 'Добавить', WORDS.wrongGroup]);
        expect(html).not.toContain('Для чего эта карта?');
        expect(html).not.toContain('Сколько сейчас');
    });

    it('категории — шесть и «ещё N»; выбранная дальше шестой видна сразу', () => {
        const many = Array.from({ length: 8 }, (_, i) => ({ id: `c${i}`, name: `Кат${i}`, type: 'EXPENSE' })) as Category[];
        const fresh = form({ account: null, purpose: 'SPEND', categories: many, busy: false, error: null, onSave: noop });
        expect(fresh).toContain('>Кат5<');
        expect(fresh).not.toContain('>Кат6<');
        expect(fresh).toContain('ещё 2 ▾');
        const edit = form({ account: acc({ trackBalance: false, purposeCategoryId: 'c7', purposeCategoryName: 'Кат7' }), categories: many,
            busy: false, error: null, onSave: noop, onCancel: noop });
        expect(edit).toContain('>Кат7<');
        expect(edit).not.toContain('>Кат6<');
    });

    it('накопительная — остаток сразу', () => {
        const html = add('SAVINGS');
        inOrder(html, ['Накопительный счёт', 'Название', 'Сколько сейчас на ней', 'Добавить']);
        expect(html).not.toContain('Для каких трат');
    });

    it('кредитка (S4-A4): лимит, доступно сейчас, «возвращаю до» с пояснением', () => {
        const html = add('CREDIT');
        inOrder(html, ['Кредитка', 'Название', '>Лимит<', 'Доступно сейчас', 'Возвращаю до', WORDS.floorAddNote, 'Добавить']);
    });

    it('«Изменить» у кредитки: ответ отмечен, лимит, «возвращаю до»; доступного — нет', () => {
        const html = form({
            account: acc({ name: 'Кредитка', kind: 'CREDIT', creditLimit: 100000, availableFloor: 90000 }),
            categories: cats, busy: false, error: null, onSave: noop, onCancel: noop,
        });
        inOrder(html, ['Изменить карту', 'Название', 'Для чего эта карта?', '>Лимит<', 'Возвращаю до', WORDS.floorNote, 'Сохранить', 'Отмена']);
        expect(html).toMatch(/checked="" value="CREDIT"|value="CREDIT" checked=""/);
        expect(html).toContain('value="100000"');
        expect(html).not.toContain('Доступно сейчас');
    });

    it('«Изменить» у основной — только название: ответа нет', () => {
        const html = form({ account: acc({ isDefault: true }), categories: cats, busy: false, error: null, onSave: noop, onCancel: noop });
        expect(html).toContain('Название');
        expect(html).not.toContain('Для чего эта карта?');
    });

    it('старых слов нет', () => {
        for (const html of [add(), add('SPEND'), add('CREDIT'), add('SAVINGS')]) for (const w of OLD) expect(html).not.toContain(w);
    });
});

describe('«Деньги»: «+» в группе и «Не та же ли это кредитка?»', () => {
    const summary = (items: CapitalItem[]): CapitalSummary => ({
        total: 0, liquid: 0, assetsTotal: 0, liabilitiesTotal: 15000, cardDebts: 0, items, deltas: { month: 0, quarter: 0, year: 0 },
    });
    const main = acc({ id: 'm', name: 'Основная карта', isDefault: true, balance: 1, balanceDate: '2026-11-12' });
    const credit = acc({ id: 'c', name: 'Кредитка', kind: 'CREDIT', balance: 85000, creditLimit: 100000, debt: 15000 });
    const cash = acc({ id: 'cash', name: 'Наличные', kind: 'CASH', trackBalance: false });
    const deposit = acc({ id: 'd', name: 'Вклад', kind: 'DEPOSIT', balance: 1, balanceDate: '2026-11-01' });
    const debt: CapitalItem = { id: 'i', kind: 'LIABILITY', name: 'Кредитка Т-Банка', description: null, createdAt: '',
        currentValue: 15000, lastValuedAt: '2026-09-01', isArchived: false };
    const view = (creditCheck?: { accountId: string; item: CapitalItem }) => clean(renderToStaticMarkup(
        <MoneyView summary={summary([debt])} accounts={[main, credit, cash, deposit]} funds={[]} points={[]} today="2026-11-22"
            onUpdate={noop} onAddCard={noop} onAccount={noop} onItem={noop} onAddItem={noop}
            creditCheck={creditCheck && { ...creditCheck, busy: false, onRemove: noop, onOther: noop }} />));

    it('у непустой группы — «+» с ответом группы', async () => {
        const html = view();
        inOrder(html, ['Кредитка', '>Кредитка</button>', 'Наличные', '>Карта для трат</button>', 'Вклад', '>Накопительный счёт</button>']);
        // нажатие разметка не видит — сторож по исходнику: «+» группы открывает лист с её ответом
        const { readFileSync } = await import('node:fs');
        const src = readFileSync(new URL('./MoneyView.tsx', import.meta.url), 'utf8');
        for (const [g, answer] of [['credit', 'CREDIT'], ['spending', 'SPEND'], ['savings', 'SAVINGS']]) {
            expect(src).toContain(`<AddInGroup label={INVITE.${g}.add} onClick={() => p.onAddCard('${answer}')} />`);
        }
    });

    it('после кредитки при живой ручной записи долга (S4-A5) — вопрос под кредиткой', () => {
        const html = view({ accountId: 'c', item: debt });
        inOrder(html, ['>Кредитка<', 'Не та же ли это кредитка?',
            'В «Долгах» есть «Кредитка Т-Банка» на 15 000 ₽. Если это она — убери запись: карта теперь сама считает долг, иначе он посчитается дважды.',
            'Убрать запись', 'Это другой долг', '>Для отдельных трат<']);
    });

    it('без вопроса — плашки нет', () => {
        expect(view()).not.toContain('Не та же ли это кредитка?');
    });
});
