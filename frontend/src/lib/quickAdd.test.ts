import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import {
    ON_MAIN_NOTE, SAVINGS_ID, afterLine, canSubmitQuickAdd, categoryChips, chipOf, effectLine, formButtons, freeEffect,
    fundReserved, kindOf, repeatLabel, repeatOf, transferFundChoice,
    CREDIT_ASK, CREDIT_EFFECT, CREDIT_OVER_NOTE, accountFundLine, creditAsks, creditForm, creditOffered, creditWritten, formLine,
    type FreeEffect,
} from './quickAdd';
import { fmtRub } from './format';
import type { Account, Category, FinancialEvent, TargetFund } from '../types/api';

const TODAY = '2026-11-19';

/** Срез 3 п. 12: кнопки — по дате, повтор — только у плана (замечание 2.3). */
describe('formButtons', () => {
    it('сегодня — «Запланировать» и «Записать», главная — «Записать»', () => {
        expect(formButtons(TODAY, TODAY, false)).toEqual(['plan', 'record']);
    });
    it('впереди — только «Запланировать»', () => {
        expect(formButtons('2026-11-20', TODAY, false)).toEqual(['plan']);
    });
    it('прошлая дата — только «Записать»', () => {
        expect(formButtons('2026-11-18', TODAY, false)).toEqual(['record']);
    });
    it('повтор включён — кнопка одна, «Запланировать»', () => {
        expect(formButtons(TODAY, TODAY, true)).toEqual(['plan']);
    });
    it('повтор у прошлой даты не действует: план в прошлом не заводится', () => {
        expect(formButtons('2026-11-18', TODAY, true)).toEqual(['record']);
    });
});

/** Ошибка Fab.tsx:51, RecurringFields.tsx:27: повтор всегда вставал на 1-е число. */
describe('repeatOf', () => {
    it('месяц — число из даты формы', () => {
        expect(repeatOf('MONTHLY', '2026-11-19', null)).toEqual({
            frequency: 'MONTHLY', dayOfMonth: 19, monthOfYear: null, endDate: null, startDate: '2026-11-19',
        });
    });
    it('год — число и месяц из даты; конец — как выбран', () => {
        expect(repeatOf('YEARLY', '2026-03-07', '2028-03-07')).toEqual({
            frequency: 'YEARLY', dayOfMonth: 7, monthOfYear: 3, endDate: '2028-03-07', startDate: '2026-03-07',
        });
    });
    it('подпись — с числом даты', () => {
        expect(repeatLabel('MONTHLY', '2026-11-19')).toBe('Каждый месяц, 19-го');
        expect(repeatLabel('YEARLY', '2026-11-19')).toBe('Каждый год, 19 нояб.');
    });
});

/** Ошибка quickAdd.ts:38: расход сохранялся и без суммы — план с «—» выпадал из прогноза. */
describe('canSubmitQuickAdd', () => {
    it('без суммы — нельзя, и у расхода тоже', () => {
        expect(canSubmitQuickAdd({ amount: null, categoryId: 'c1', transfer: false })).toBe(false);
        expect(canSubmitQuickAdd({ amount: 0, categoryId: 'c1', transfer: false })).toBe(false);
    });
    it('сумма и категория — можно; без категории — нельзя', () => {
        expect(canSubmitQuickAdd({ amount: 640, categoryId: 'c1', transfer: false })).toBe(true);
        expect(canSubmitQuickAdd({ amount: 640, transfer: false })).toBe(false);
    });
    it('«Накопление» — нужна копилка', () => {
        expect(canSubmitQuickAdd({ amount: 5000, transfer: true })).toBe(false);
        expect(canSubmitQuickAdd({ amount: 5000, transfer: true, fundId: 'f1' })).toBe(true);
    });
});

describe('kindOf', () => {
    const cat = (priority: Category['priority']) => ({ priority }) as Category;
    it('вид по умолчанию — от категории', () => {
        expect(kindOf(cat('HIGH'))).toBe('HIGH');
        expect(kindOf(cat('LOW'))).toBe('LOW');
        expect(kindOf(cat('MEDIUM'))).toBe('MEDIUM');
    });
    it('категории нет — ожидание', () => {
        expect(kindOf(undefined)).toBe('MEDIUM');
    });
});

/** Срез 3 п. 14 (S3-F4): порядок — сервера; шесть первых своего типа, остальные за «ещё» по алфавиту. */
describe('categoryChips', () => {
    const c = (name: string, type: Category['type'] = 'EXPENSE', systemKind: Category['systemKind'] = null) =>
        ({ id: name, name, type, priority: 'MEDIUM', isSystem: systemKind != null, systemKind }) as Category;
    const server = ['Транспорт', 'Продукты', 'Кафе', 'Жильё', 'Связь', 'Здоровье', 'Подарки', 'Аптека'].map(n => c(n));

    it('шесть первых — в порядке сервера, без пересортировки; остальные — по алфавиту', () => {
        const chips = categoryChips([...server, c('Зарплата', 'INCOME')], 'EXPENSE');
        expect(chips.top.map(x => x.name)).toEqual(['Транспорт', 'Продукты', 'Кафе', 'Жильё', 'Связь', 'Здоровье']);
        expect(chips.rest.map(x => x.name)).toEqual(['Аптека', 'Накопление', 'Подарки']);
    });

    it('«Накопление» — среди расходов; своей системной категории ещё нет — всё равно есть', () => {
        const chips = categoryChips(server.slice(0, 2), 'EXPENSE');
        expect(chips.top.map(x => x.name)).toEqual(['Транспорт', 'Продукты', 'Накопление']);
        expect(chips.top[2]).toMatchObject({ id: SAVINGS_ID, transfer: true });
    });

    it('системная «Накопление» уже есть — стоит на своём месте сервера, второй нет', () => {
        const chips = categoryChips([c('Накопление', 'EXPENSE', 'FUND_TRANSFER'), ...server.slice(0, 2)], 'EXPENSE');
        expect(chips.top.map(x => x.name)).toEqual(['Накопление', 'Транспорт', 'Продукты']);
        expect(chips.top[0]).toMatchObject({ id: 'Накопление', transfer: true });
    });

    it('у доходов «Накопления» нет', () => {
        expect(categoryChips([c('Зарплата', 'INCOME')], 'INCOME').top.map(x => x.name)).toEqual(['Зарплата']);
    });
});

/** Строка о «Свободно» (п. 12, решение 43): что будет с числом после главной кнопки. Правило привязки — сервера. */
describe('freeEffect', () => {
    const base = {
        type: 'EXPENSE' as const, kind: 'MEDIUM' as const, amount: 1200, date: TODAY, today: TODAY,
        checkpointDate: '2026-11-10', horizon: { endDate: '2026-11-25', label: 'до зарплаты 25 нояб.' }, line: null,
    };
    const line = (date: string, plannedAmount = 6000, recorded = 0) =>
        ({ id: 'p', name: 'Продукты', date, plannedAmount, recorded });

    // Ревью Codex #235: «Свободно» — самый низкий остаток до конца расчёта; сдвигается минимум, а не каждая точка.
    const trajectory = [
        { date: TODAY, balance: 10000 }, { date: '2026-11-22', balance: 9000 }, { date: '2026-11-25', balance: 14000 },
        { date: '2026-11-30', balance: 1000 },
    ];
    it('план в день дохода, после минимума — «Свободно» не изменится', () => {
        expect(effectLine(freeEffect({ ...base, action: 'plan', date: '2026-11-25', amount: 2000, trajectory })))
            .toBe('Ожидание на 25 нояб. — «Свободно» не изменится.');
    });
    it('план до минимума — меньше на сумму; минимум сдвинулся частично — на сдвиг', () => {
        expect(freeEffect({ ...base, action: 'plan', date: '2026-11-21', amount: 2000, trajectory })!.delta).toBe(-2000);
        expect(freeEffect({ ...base, action: 'plan', date: '2026-11-25', amount: 6000, trajectory })!.delta).toBe(-1000);
    });
    it('план с повтором — строки нет: платежей до конца расчёта может быть несколько (ревью Codex #235)', () => {
        expect(freeEffect({ ...base, action: 'plan', date: '2026-11-21', amount: 2000, trajectory, repeat: true })).toBeNull();
        expect(freeEffect({ ...base, action: 'plan', date: '2026-12-01', amount: 2000, trajectory, repeat: true })!.delta).toBe(0);
        expect(readFileSync(new URL('../components/Fab.tsx', import.meta.url), 'utf8')).toContain('repeat: repeatOn');
    });
    it('точки после конца расчёта в минимум не входят', () => {
        expect(freeEffect({ ...base, action: 'plan', date: '2026-11-21', amount: 500, trajectory })!.delta).toBe(-500);
        expect(freeEffect({ ...base, action: 'record', amount: 500, trajectory })!.delta).toBe(-500);
    });
    it('запись к строке впереди, минимум раньше строки — «Свободно» меньше на сумму', () => {
        const t = [{ date: TODAY, balance: 10000 }, { date: '2026-11-21', balance: 4000 }, { date: '2026-11-23', balance: 7000 }];
        expect(freeEffect({ ...base, action: 'record', line: line('2026-11-23'), trajectory: t })!.delta).toBe(-1200);
        expect(freeEffect({ ...base, action: 'record', line: line('2026-11-23'), trajectory: [t[0], { date: '2026-11-23', balance: 3000 }] })!.delta).toBe(0);
    });

    it('запись без строки после сверки — «Свободно» меньше на сумму', () => {
        expect(effectLine(freeEffect({ ...base, action: 'record' }))).toBe('В плане этого не было — «Свободно» меньше на 1 200 ₽.');
    });

    it('запись к строке впереди с остатком — «Свободно» не изменится', () => {
        expect(effectLine(freeEffect({ ...base, action: 'record', line: line('2026-11-23') })))
            .toBe('Трата встанет к ожиданию «Продукты» — «Свободно» не изменится.');
    });

    it('сверх остатка строки — меньше на лишнее', () => {
        expect(effectLine(freeEffect({ ...base, action: 'record', line: line('2026-11-23', 6000, 5500) })))
            .toBe('Трата встанет к ожиданию «Продукты» — «Свободно» меньше на 700 ₽.');
    });

    it('прошедшая строка недели не держится — меньше на всю сумму (PR 3, находка стенда)', () => {
        expect(effectLine(freeEffect({ ...base, action: 'record', line: line('2026-11-16') })))
            .toBe('Трата встанет к ожиданию «Продукты» — «Свободно» меньше на 1 200 ₽.');
    });

    it('строка после конца расчёта «Свободно» в нём не держится — меньше на всю сумму', () => {
        expect(effectLine(freeEffect({ ...base, action: 'record', line: line('2026-11-28') })))
            .toBe('Трата встанет к ожиданию «Продукты» — «Свободно» меньше на 1 200 ₽.');
    });

    it('запись до сверки — деньги уже в остатке; без строки «Свободно» не изменится', () => {
        expect(effectLine(freeEffect({ ...base, action: 'record', date: '2026-11-08' })))
            .toBe('Это было до сверки остатка 10 нояб. — «Свободно» не изменится.');
    });

    it('запись до сверки к строке впереди — строка держит меньше, «Свободно» больше', () => {
        expect(effectLine(freeEffect({ ...base, action: 'record', date: '2026-11-08', line: line('2026-11-23') })))
            .toBe('Трата встанет к ожиданию «Продукты» — «Свободно» больше на 1 200 ₽.');
    });

    it('план на дату расчёта — меньше уже сейчас (Zh2-F8a)', () => {
        expect(effectLine(freeEffect({ ...base, action: 'plan', kind: 'HIGH', amount: 12000, date: '2026-11-19' })))
            .toBe('Бронь на 19 нояб. — «Свободно» меньше уже сейчас, на 12 000 ₽.');
    });

    it('план после конца расчёта — сейчас не изменится', () => {
        expect(effectLine(freeEffect({ ...base, action: 'plan', kind: 'LOW', amount: 3000, date: '2026-12-02' })))
            .toBe('Хотелка на 2 дек. — «Свободно» сейчас не изменится: оно считается до зарплаты 25 нояб.');
    });

    it('«Отложить» в копилку — меньше на сумму', () => {
        expect(effectLine(freeEffect({ ...base, action: 'transfer', fundName: 'Отпуск', amount: 5000 })))
            .toBe('В копилку «Отпуск» — «Свободно» меньше на 5 000 ₽.');
    });

    it('в копилку с взносами — строки нет: перевод уменьшит и взносы (ревью Codex #235)', () => {
        expect(freeEffect({ ...base, action: 'transfer', fundName: 'Отпуск', amount: 5000, fundReserved: true })).toBeNull();
        const f = { wishlistStatus: 'FIXED', purchaseType: 'SAVINGS', targetDate: '2027-06-01', targetAmount: 100000 } as TargetFund;
        expect(fundReserved(f)).toBe(true);
        expect(fundReserved({ ...f, wishlistStatus: null })).toBe(false);
        expect(fundReserved({ ...f, purchaseType: 'CREDIT' })).toBe(false);
        expect(fundReserved({ ...f, targetDate: null })).toBe(false);
        expect(fundReserved({ ...f, targetAmount: null })).toBe(false);
        expect(fundReserved(undefined)).toBe(false);
        expect(readFileSync('src/components/Fab.tsx', 'utf8')).toContain('fundReserved: fundReserved(fund)');
    });

    it('доход без строки — больше на сумму; доход к строке — строки нет: горизонт может сдвинуться', () => {
        expect(effectLine(freeEffect({ ...base, action: 'record', type: 'INCOME', amount: 8000 })))
            .toBe('В плане этого не было — «Свободно» больше на 8 000 ₽.');
        expect(freeEffect({ ...base, action: 'record', type: 'INCOME', line: line('2026-11-23') })).toBeNull();
    });

    it('суммы нет — сказать нечего', () => {
        expect(freeEffect({ ...base, action: 'record', amount: null })).toBeNull();
    });

    it('после записи — те же слова в прошедшем времени (S3-F3)', () => {
        expect(afterLine(freeEffect({ ...base, action: 'record', line: line('2026-11-23') })))
            .toBe('«Свободно» не изменилось.');
        expect(afterLine(freeEffect({ ...base, action: 'record' }))).toBe('«Свободно» меньше на 1 200 ₽.');
    });
});

describe('chipOf', () => {
    const food = { id: 'food', name: 'Продукты', transfer: false };
    const own = { id: 'nak', name: 'Накопление', transfer: true };

    it('«Отложить сюда» (лист карты): «Накопление» — своя категория человека, если она есть (найдено на стенде)', () => {
        expect(chipOf([food, own], SAVINGS_ID)).toBe(own);
        // своей нет — заглушка из categoryChips
        expect(chipOf([food, { id: SAVINGS_ID, name: 'Накопление', transfer: true }], SAVINGS_ID)?.id).toBe(SAVINGS_ID);
    });

    it('остальное — по id', () => {
        expect(chipOf([food, own], 'food')).toBe(food);
        expect(chipOf([food, own], 'нет')).toBeUndefined();
        expect(chipOf([food, own], undefined)).toBeUndefined();
    });
});

describe('transferFundChoice', () => {
    const fund = (id: string, over: Partial<TargetFund> = {}) =>
        ({ id, name: id, status: 'FUNDING', accountId: null, ...over }) as TargetFund;

    it('копилка на своём счёте в списке есть — «Отложить сюда» с карты (срез 4 п. 12); на основной — нет, перевод в неё сервер отвергает', () => {
        const { choices, onMain } = transferFundChoice([
            fund('конверт'), fund('на накопительном', { accountId: 'savings' }), fund('на основной', { accountId: 'main' }),
            fund('достигнута', { status: 'REACHED' }),
        ], 'main');
        expect(choices.map(f => f.id)).toEqual(['конверт', 'на накопительном']);
        expect(onMain).toBe(1);
    });

    it('НЗ на основной — не в списке и не в счётчике: перевод в него сервер отвергает (срез 4, п. 7); НЗ на вкладе — в списке', () => {
        const { choices, onMain } = transferFundChoice([
            fund('НЗ', { reserve: true }), fund('конверт'),
        ], 'main');
        expect(choices.map(f => f.id)).toEqual(['конверт']);
        expect(onMain).toBe(0);
        expect(transferFundChoice([fund('НЗ', { reserve: true, accountId: 'deposit' })], 'main').choices.map(f => f.id))
            .toEqual(['НЗ']);
    });

    it('достигнутая копилка на основной в счётчик не входит — её не было в списке и раньше', () => {
        expect(transferFundChoice([fund('x', { accountId: 'main', status: 'REACHED' })], 'main').onMain).toBe(0);
    });
});

/** Сторож по исходнику: форма зовёт именно эти решения. */
describe('форма «+» (срез 3 п. 12)', () => {
    const src = readFileSync(new URL('../components/Fab.tsx', import.meta.url), 'utf8');

    it('сумма одна: «Сумма (план)» и «Сумма (факт)» ушли (ANO-228)', () => {
        expect(src).not.toContain('Сумма (план)');
        expect(src).not.toContain('Сумма (факт');
    });

    it('кнопки — formButtons, «Сохранить» — canSubmitQuickAdd, копилки — transferFundChoice со строкой почему', () => {
        expect(src).toContain('formButtons(');
        expect(src).toContain('canSubmitQuickAdd({');
        expect(src).toContain('transferFundChoice(funds ?? [], accounts.find(a => a.isDefault)?.id)');
        // выбранный чип — chipOf: «Отложить сюда» открывает форму с «Накоплением» человека
        expect(src).toContain('const chosen = chipOf(all, categoryId);');
        expect(src).toContain("value={chosen?.id ?? ''}");
        expect(src).toContain('.then(([d, all]) => { setAccounts(all); setFunds(d.funds); })');
        expect(src).toContain('{ON_MAIN_NOTE}');
        expect(ON_MAIN_NOTE).toContain('на основной');
    });

    it('«Отложить» — перевод без вопроса и той датой, что в форме (ANO-169)', () => {
        expect(src).toContain('transferToFund(attempts, fundId!, amount!, true, undefined, date)');
    });

    it('повтор — из даты формы при отправке; порядок категорий — сервера, без sort', () => {
        expect(src).toContain('repeatOf(');
        expect(src).not.toMatch(/\.sort\(/);
    });

    it('строка о «Свободно» — по ответу сервера (fact-preview), не по своему правилу', () => {
        expect(src).toContain('fetchFactPreview(');
    });

    it('строка о «Свободно» — только у собранной формы: без категории или копилки её нет', () => {
        expect(src).toContain('pocket && canSubmit');
    });

    it('строка после записи — по нажатой кнопке, а не по главной (ревью Codex #235)', () => {
        expect(src).toContain('afterLine(effectOf(action))');
        expect(src).toContain("line: action === 'record' ? lineNow : null");
    });

    it('«Отменить» у перевода снимает запись, найденную сразу после перевода, — повтор её же (ревью Codex #235)', () => {
        expect(src).toContain('const id = await transferEventId(fundId!, amount, date).catch(() => undefined)');
        expect(src).toContain('trajectory: pocket.trajectory');
        expect(src).toContain('deleteEvent(id)');
        expect(src).toContain('e instanceof ApiError && e.status === 404');
    });
});

/** Срез 4, п. 17 (S4-K1): «В кредит» — у расхода без плана; вопрос — при сумме больше, чем на картах. */
describe('creditOffered', () => {
    const fact = (over: Partial<FinancialEvent> = {}) =>
        ({ eventKind: 'FACT', type: 'EXPENSE', parentEventId: null, ...over }) as FinancialEvent;

    it('новая запись: у расхода с «Записать» — есть; у дохода, у «Накопления» и у одного «Запланировать» — нет', () => {
        expect(creditOffered({ type: 'EXPENSE', transfer: false, buttons: ['plan', 'record'] })).toBe(true);
        expect(creditOffered({ type: 'EXPENSE', transfer: false, buttons: ['record'] })).toBe(true);
        expect(creditOffered({ type: 'INCOME', transfer: false, buttons: ['plan', 'record'] })).toBe(false);
        expect(creditOffered({ type: 'EXPENSE', transfer: true, buttons: ['plan', 'record'] })).toBe(false);
        expect(creditOffered({ type: 'EXPENSE', transfer: false, buttons: ['plan'] })).toBe(false);
    });
    it('правка: у расхода без плана — есть; у записи к плану, у дохода, у перевода и у плана — нет', () => {
        const offered = (e: FinancialEvent) => creditOffered({ type: 'EXPENSE', transfer: false, buttons: ['record'], edit: e });
        expect(offered(fact())).toBe(true);
        expect(offered(fact({ parentEventId: 'plan' }))).toBe(false);
        expect(offered(fact({ type: 'INCOME' }))).toBe(false);
        expect(offered(fact({ type: 'FUND_TRANSFER' }))).toBe(false);
        expect(offered(fact({ eventKind: 'PLAN' }))).toBe(false);
    });
});

describe('creditAsks', () => {
    it('больше, чем на картах, и отметки нет — спрашивает; ровно столько, меньше, с отметкой или без числа — нет', () => {
        expect(creditAsks(250_000, 48_800, false)).toBe(true);
        expect(creditAsks(48_800, 48_800, false)).toBe(false);
        expect(creditAsks(1_000, 48_800, false)).toBe(false);
        expect(creditAsks(250_000, 48_800, true)).toBe(false);
        expect(creditAsks(null, 48_800, false)).toBe(false);
        expect(creditAsks(250_000, null, false)).toBe(false);
    });
    it('на картах минус — любая трата больше: вопрос', () => {
        expect(creditAsks(1, -5_000, false)).toBe(true);
    });
});

describe('слова «В кредит»', () => {
    it('строка над кнопкой, вопрос и подпись под суммой — слова S4-K1', () => {
        expect(CREDIT_EFFECT).toBe('В кредит: «Свободно» не изменится. Долг будет виден на кредитке, когда обновишь её остаток.');
        expect(CREDIT_ASK).toBe('На картах столько нет. Это в кредит?');
        expect(CREDIT_OVER_NOTE).toBe('Больше, чем на картах, — отметка стоит');
    });
    it('строка после записи — S4-K2', () => {
        expect(creditWritten('Ремонт', 250_000))
            .toBe(`В кредит: Ремонт, ${fmtRub(250_000)}. «Свободно» не изменилось — обнови кредитку, чтобы видеть долг.`);
    });
});

/** Срез 4, п. 20 (S4-G4): «Накопление» в копилку на своём счёте — что станет на картах и на счёте. */
describe('accountFundLine', () => {
    const account = (over: Partial<Account>) => ({ id: 'a', name: 'Т-Вклад', kind: 'DEPOSIT', ...over }) as Account;
    it('на накопительном — словами доски', () => {
        expect(accountFundLine('Отпуск', account({}), 8_000))
            .toBe(`«Отпуск» на накопительном: на картах станет на ${fmtRub(8_000)} меньше, на накопительном — на ${fmtRub(8_000)} больше.`);
    });
    it('на другой карте с остатком — её именем', () => {
        expect(accountFundLine('Отпуск', account({ kind: 'DEBIT', name: 'Альфа' }), 8_000))
            .toBe(`«Отпуск» на карте «Альфа»: на картах станет на ${fmtRub(8_000)} меньше, на «Альфа» — на ${fmtRub(8_000)} больше.`);
    });
});

/** Срез 4, п. 17 (S4-K1): «В кредит» в форме — когда отметка действует, когда вопрос, когда пометка под суммой. */
describe('creditForm', () => {
    const base = { offered: true, isNew: true, record: true, onCredit: false, amount: 250_000, cards: 17_000 };

    it('больше, чем на картах, без отметки — вопрос', () => {
        expect(creditForm(base)).toEqual({ credit: false, asks: true, over: false });
    });
    it('отметка стоит — вопроса нет, пометка под суммой', () => {
        expect(creditForm({ ...base, onCredit: true })).toEqual({ credit: true, asks: false, over: true });
    });
    it('галочка не предложена — отметка не действует, вопроса нет', () => {
        expect(creditForm({ ...base, offered: false, onCredit: true })).toEqual({ credit: false, asks: false, over: false });
        expect(creditForm({ ...base, offered: false }).asks).toBe(false);
    });
    it('правка — отметка действует, но ни вопроса, ни пометки', () => {
        expect(creditForm({ ...base, isNew: false, onCredit: true })).toEqual({ credit: true, asks: false, over: false });
        expect(creditForm({ ...base, isNew: false }).asks).toBe(false);
    });
    it('не «Записать» — вопроса нет', () => {
        expect(creditForm({ ...base, record: false }).asks).toBe(false);
    });
    it('не больше, чем на картах, — ни вопроса, ни пометки', () => {
        expect(creditForm({ ...base, amount: 17_000 }).asks).toBe(false);
        expect(creditForm({ ...base, amount: 17_000, onCredit: true }).over).toBe(false);
    });
    it('«на картах» ещё не пришло или суммы нет — молчит, даже когда на картах минус', () => {
        expect(creditForm({ ...base, cards: null })).toEqual({ credit: false, asks: false, over: false });
        expect(creditForm({ ...base, cards: null, onCredit: true }).over).toBe(false);
        expect(creditAsks(null, -5, false)).toBe(false);
        expect(creditForm({ ...base, amount: null, cards: -5, onCredit: true }).over).toBe(false);
    });
});

/** Срез 4, пп. 17, 20 (S4-K1, S4-G4): строка над кнопкой формы «+». */
describe('formLine', () => {
    const effect: FreeEffect = { lead: 'Запишется сегодня', delta: -8_000 };
    const savings = { name: 'Отпуск', account: { name: 'Т-Вклад', kind: 'DEPOSIT' as const } };
    const base = { isNew: true, record: true, credit: false, asks: false, transfer: false, amount: 8_000, effect };

    it('в кредит у новой записи с «Записать» — что будет', () => {
        expect(formLine({ ...base, credit: true })).toBe(CREDIT_EFFECT);
    });
    it('в кредит у правки и у «Запланировать» — о «Свободно»', () => {
        expect(formLine({ ...base, credit: true, isNew: false })).toBe(effectLine(effect));
        expect(formLine({ ...base, credit: true, record: false })).toBe(effectLine(effect));
    });
    it('сумма больше, чем на картах, — вопрос', () => {
        expect(formLine({ ...base, asks: true })).toBe(CREDIT_ASK);
    });
    it('перевод в копилку на своём счёте — что станет на картах и на счёте, потом «Свободно»', () => {
        expect(formLine({ ...base, transfer: true, fund: savings }))
            .toBe(`${accountFundLine('Отпуск', savings.account, 8_000)} ${afterLine(effect)}`);
    });
    it('копилка без счёта, не перевод, правка, без суммы — о «Свободно»', () => {
        expect(formLine({ ...base, transfer: true, fund: { name: 'Отпуск' } })).toBe(effectLine(effect));
        expect(formLine({ ...base, fund: savings })).toBe(effectLine(effect));
        expect(formLine({ ...base, transfer: true, fund: savings, isNew: false })).toBe(effectLine(effect));
        expect(formLine({ ...base, transfer: true, fund: savings, amount: null })).toBe(effectLine(effect));
        expect(formLine({ ...base, transfer: true, fund: savings, amount: 0 })).toBe(effectLine(effect));
    });
});
