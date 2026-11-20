import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import {
    ON_ACCOUNT_NOTE, SAVINGS_ID, afterLine, canSubmitQuickAdd, categoryChips, effectLine, formButtons, freeEffect,
    kindOf, repeatLabel, repeatOf, transferFundChoice,
} from './quickAdd';
import type { Category, TargetFund } from '../types/api';

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

describe('transferFundChoice', () => {
    const fund = (id: string, over: Partial<TargetFund> = {}) =>
        ({ id, name: id, status: 'FUNDING', accountId: null, ...over }) as TargetFund;

    it('копилки на счёте в списке нет — перевод в них сервер отвергает', () => {
        const { choices, onAccount } = transferFundChoice([
            fund('конверт'), fund('на счёте', { accountId: 'acc' }), fund('достигнута', { status: 'REACHED' }),
        ]);
        expect(choices.map(f => f.id)).toEqual(['конверт']);
        expect(onAccount).toBe(1);
    });

    it('достигнутая копилка на счёте в счётчик не входит — её не было в списке и раньше', () => {
        expect(transferFundChoice([fund('x', { accountId: 'acc', status: 'REACHED' })]).onAccount).toBe(0);
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
        expect(src).toContain('transferFundChoice(funds ?? [])');
        expect(src).toContain('{ON_ACCOUNT_NOTE}');
        expect(ON_ACCOUNT_NOTE).toContain('на счёте');
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
        expect(src).toContain('const id = await transferEventId(');
        expect(src).toContain('deleteEvent(id)');
        expect(src).toContain('e instanceof ApiError && e.status === 404');
    });
});
