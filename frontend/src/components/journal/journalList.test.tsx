/**
 * Журнал на деталях системы (срез 3, п. 6; доски Zh2-J8, Zh2-J8b, S3-F7): порядок дней, план блоком «по плану» над
 * записями, «Впереди», «Оплачено | своё»; кромки слева нет. Сводка месяца, «Последний факт», справка, «нет факта»
 * и «+ записать факт» ушли (решения 8 и 51) — сторож по исходнику страницы.
 */
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import type { FinancialEvent } from '../../types/api';
import { journalView } from '../../lib/journalSections';
import JournalList from './JournalList';
import type { JournalOpen } from '../../lib/screenSync';

const ev = (over: Partial<FinancialEvent>): FinancialEvent => ({
    id: 'e', date: '2026-11-12', categoryId: 'c', categoryName: 'Жильё и быт', type: 'EXPENSE', plannedAmount: null,
    factAmount: null, status: 'PLANNED', priority: 'HIGH', description: null, rawInput: null,
    createdAt: '2026-11-01T09:00:00', eventKind: 'PLAN', parentEventId: null, linkedFactsCount: 0,
    linkedFactsAmount: null, parentPlanDescription: null, ...over,
});

const TODAY = '2026-11-12';
const EVENTS = [
    ev({ id: 'rent', description: 'Аренда', plannedAmount: 25000 }),
    ev({ id: 'cafe', eventKind: 'FACT', priority: 'MEDIUM', description: 'Кофе', categoryName: 'Кафе и доставка', factAmount: 250, createdAt: '2026-11-12T12:00:00' }),
    ev({ id: 'taxi', eventKind: 'FACT', date: '2026-11-11', priority: 'MEDIUM', description: 'Такси', categoryName: 'Транспорт', factAmount: 540 }),
    ev({ id: 'salary', date: '2026-11-25', type: 'INCOME', description: 'Зарплата', categoryName: 'Зарплата', plannedAmount: 80000 }),
    ev({ id: 'phone', date: '2026-11-15', description: 'Связь', categoryName: 'Связь и подписки', plannedAmount: 800 }),
    ev({ id: 'wish', date: '2026-11-28', priority: 'LOW', description: 'Наушники', categoryName: 'Покупки', plannedAmount: 6000 }),
    ev({ id: 'old', eventKind: 'FACT', date: '2026-11-05', priority: 'MEDIUM', description: 'Пятёрочка', categoryName: 'Продукты', factAmount: 1200 }),
    ev({ id: 'income', date: '2026-11-10', type: 'INCOME', description: 'Аванс', categoryName: 'Зарплата', plannedAmount: 30000 }),
];

const AHEAD: JournalOpen = { ahead: true, weeks: {} };
const render = (opened?: JournalOpen) => renderToStaticMarkup(
    <JournalList view={journalView(EVENTS, 2026, 10, TODAY)} allowedToday={TODAY} anchorDate={null} opened={opened}
        busy={() => false} failed={() => false} stale={() => false} handlers={{
            onRow: () => {}, onPaid: () => {}, onOwn: () => {}, onCycle: () => {}, onRefresh: () => {},
        }} />);
const text = (html: string) => html.replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').replace(/ /g, ' ');
const order = (html: string, words: string[]) => words.map((w) => text(html).indexOf(w));

describe('журнал по дням', () => {
    it('«Впереди» сверху, дальше дни — свежий первым, прошлая неделя свёрнута', () => {
        const at = order(render(), ['Впереди в ноябре · 3', 'Сегодня, 12 нояб., чт', 'Вчера, 11 нояб., ср', 'Вт, 10 нояб.', '2–8 нояб.']);
        expect(at.every((i) => i >= 0)).toBe(true);
        expect([...at].sort((a, b) => a - b)).toEqual(at);
        expect(text(render())).not.toContain('Пятёрочка');
        expect(text(render())).toContain('2–8 нояб. 1 запись');
        expect(text(render())).toContain('1 нояб. Нет записей');
    });

    it('свёрнутое «Впереди» — строкой; раскрытое — по неделям', () => {
        expect(text(render())).toContain('Связь 15-го, Зарплата 25-го, Наушники 28-го');
        const open = text(render(AHEAD));
        expect(order(open, ['Эта неделя, 9–15 нояб.', 'Связь', '23–29 нояб.', 'Зарплата', 'Наушники']))
            .toEqual([...order(open, ['Эта неделя, 9–15 нояб.', 'Связь', '23–29 нояб.', 'Зарплата', 'Наушники'])].sort((a, b) => a - b));
        expect(open).toContain('+80 000 ₽');
    });

    it('в дне — «по плану» над записями; у брони с наступившей датой — «Оплачено | своё», у дохода — «Получено»', () => {
        const t = text(render());
        expect(order(t, ['по плану', 'Аренда', 'Кофе'])).toEqual([...order(t, ['по плану', 'Аренда', 'Кофе'])].sort((a, b) => a - b));
        expect(t).toContain('Аренда сегодня · бронь 25 000 ₽ Оплачено своё');
        expect(t).toContain('Аванс бронь +30 000 ₽ Получено своё');
    });

    it('у плана без одного касания — только «своё»: запись к нему не теряется (было «+ записать факт»)', () => {
        const open = text(render(AHEAD));
        expect(open).toContain('Наушники 28 нояб., сб · хотелка 6 000 ₽ своё');
        expect(open).not.toContain('Наушники 28 нояб., сб · хотелка 6 000 ₽ Оплачено');
    });

    it('кромки слева нет; кружок вида — деталь системы', () => {
        const html = render(AHEAD);
        expect(html).not.toMatch(/border-left|borderLeft|border-l-/);
        expect(html).toContain('Вид: бронь. Нажми — сменить');
    });
});

describe('сторож: что ушло с экрана журнала (решения 8, 51)', () => {
    const page = readFileSync(resolve(__dirname, '../../pages/Budget.tsx'), 'utf8')
        + readFileSync(resolve(__dirname, './JournalList.tsx'), 'utf8');

    it.each([
        ['сводка месяца', /totalPlannedIncome|totalFactExpense|Доходы|Расходы/],
        ['«Последний факт»', /Последний факт|lastFact/],
        ['справка «Как устроен журнал»', /HelpDialog|Как устроен журнал/],
        ['«нет факта»', /нет факта/],
        ['«+ записать факт»', /записать факт/],
        ['кромка слева', /borderLeft|border-left/],
    ])('%s', (_name, pattern) => {
        expect(page).not.toMatch(pattern);
    });
});

describe('раскрытое — как было (ревью Codex на #232)', () => {
    it('запомненные разделы раскрыты сразу: прошлая неделя и «Впереди»', () => {
        const html = text(render({ ahead: true, weeks: { '2–8 нояб.': true } }));
        expect(html).toContain('Пятёрочка');
        expect(html).toContain('Зарплата');
    });

    it('у строк «Впереди» — дата: «Открыть день в журнале» находит будущий день', () => {
        const html = render(AHEAD);
        expect(html).toContain('data-date="2026-11-25"');
        expect(html).toContain('data-ahead');
    });
});

describe('сторож: раскрытое и будущий день на странице', () => {
    const list = readFileSync(resolve(__dirname, './JournalList.tsx'), 'utf8');
    const page = readFileSync(resolve(__dirname, '../../pages/Budget.tsx'), 'utf8');

    it('журнал сообщает, что раскрыто, — страница это запоминает', () => {
        expect(list).toContain('useEffect(() => { onOpened?.({ ahead, weeks: toggled }); }, [ahead, toggled, onOpened]);');
        expect(page).toContain('saveJournalPosition(sessionStore(), { year, month, scroll: window.scrollY, open: openNow.current })');
        expect(page).toContain('const onOpened = useCallback((o: JournalOpen) => { openNow.current = o; savePosition(); }');
    });

    it('возврат — запомненное раскрытие; будущий день из ?date= — «Впереди» раскрыто', () => {
        expect(page).toContain('saved && saved.year === year && saved.month === month ? saved.open');
        expect(page).toContain('target.iso > today ? { ahead: true, weeks: {} }');
    });

    it('расстановка: день, строка «Впереди», для будущего — «Впереди», для прошлого — неделя', () => {
        expect(page).toContain('?? document.querySelector<HTMLElement>(`[data-date="${target.iso}"]`)');
        expect(page).toContain("?? (target.iso > today ? document.querySelector<HTMLElement>('[data-ahead]') : week)");
    });

    it('цель встаёт под липкой строкой месяца, а не за ней', () => {
        expect(page).toContain("const cover = top && getComputedStyle(top).position === 'sticky' ? top.offsetHeight : 0;");
        expect(page).toContain('window.scrollTo(0, el.getBoundingClientRect().top + window.scrollY - cover);');
        expect(page).toContain('<div ref={topRef} className="sticky top-0');
    });
});

describe('сторож: смена месяца (стенд 19.11 — «‹» ронял экран)', () => {
    const page = readFileSync(resolve(__dirname, '../../pages/Budget.tsx'), 'utf8');

    it('вид считается только по событиям показанного месяца', () => {
        expect(page).toMatch(/const events = loaded\.key === shownKey \? loaded\.events : \[\];/);
        expect(page).toMatch(/const loading = fetching \|\| loaded\.key !== shownKey;/);
    });

    it('поздний ответ прошлого месяца не перезаписывает новый', () => {
        expect(page).toMatch(/if \(keyNow\.current === key\) setLoaded\(\{ key, events: data \}\);/);
    });
});

describe('знак суммы — по направлению денег (ANO-184)', () => {
    it('ушедшее — без знака, доход и возврат из копилки — «+»', () => {
        const html = renderToStaticMarkup(
            <JournalList view={journalView([
                ev({ id: 'out', eventKind: 'FACT', priority: 'MEDIUM', factAmount: 300, description: 'Расход' }),
                ev({ id: 'back', eventKind: 'FACT', type: 'FUND_TRANSFER', targetFundName: 'Отпуск', priority: 'MEDIUM', factAmount: -100 }),
                ev({ id: 'in', eventKind: 'FACT', type: 'INCOME', priority: 'MEDIUM', factAmount: 500, description: 'Возврат долга' }),
            ], 2026, 10, TODAY)} allowedToday={TODAY} anchorDate={null}
                busy={() => false} failed={() => false} stale={() => false} handlers={{
                    onRow: () => {}, onPaid: () => {}, onOwn: () => {}, onCycle: () => {}, onRefresh: () => {},
                }} />);
        const t = text(html);
        expect(t).toContain('Расход Жильё и быт 300 ₽');
        expect(t).toContain('Отпуск Накопление · в копилку +100 ₽');
        expect(t).toContain('Возврат долга Жильё и быт +500 ₽');
    });
});

describe('«Оплачено» и сверка остатка', () => {
    it('бронь раньше сверки — «Оплачено» есть: оно спросит дату (lib/quickClose)', () => {
        const html = renderToStaticMarkup(
            <JournalList view={journalView(EVENTS, 2026, 10, TODAY)} allowedToday={TODAY} anchorDate="2026-11-11"
                busy={() => false} failed={() => false} stale={() => false} handlers={{
                    onRow: () => {}, onPaid: () => {}, onOwn: () => {}, onCycle: () => {}, onRefresh: () => {},
                }} />);
        expect(text(html)).toContain('Аванс бронь +30 000 ₽ Получено своё');
    });
});
