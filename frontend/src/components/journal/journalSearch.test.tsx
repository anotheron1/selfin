/**
 * Поиск на экране (срез 3, п. 9; доски S3-S1, S3-W6): поле, чипы, «Нашлось …», найденное по месяцам; нажатие —
 * карточка, крестик — обратно к месяцу. Состояние — у страницы, сторож по её исходнику.
 */
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import type { Category, FinancialEvent } from '../../types/api';
import { NO_SEARCH } from '../../lib/journalSearch';
import { SearchChips, SearchField, SearchResults } from './JournalSearch';

const ev = (over: Partial<FinancialEvent>): FinancialEvent => ({
    id: 'e', date: '2026-11-12', categoryId: 'c', categoryName: 'Продукты', type: 'EXPENSE', plannedAmount: null,
    factAmount: 1240, status: 'EXECUTED', priority: 'MEDIUM', description: 'Пятёрочка', rawInput: null,
    createdAt: '2026-11-12T09:00:00', eventKind: 'FACT', parentEventId: null, linkedFactsCount: 0,
    linkedFactsAmount: null, parentPlanDescription: null, ...over,
});
const cat = (id: string, name: string, type: Category['type']): Category =>
    ({ id, name, type, priority: 'MEDIUM', isSystem: false, forecastEnabled: false, primaryIncome: false, systemKind: null });
const text = (html: string) => html.replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').replace(/ /g, ' ');
const Q = { ...NO_SEARCH, q: 'пятёр' };

describe('поиск на экране', () => {
    it('поле: лупа, «Название, категория, сумма»; крестик — только пока ищем', () => {
        const closed = renderToStaticMarkup(<SearchField value="" open={false} onChange={() => {}} onOpen={() => {}} onClose={() => {}} />);
        expect(closed).toContain('aria-label="Поиск по журналу"');
        expect(closed).toContain('placeholder="Название, категория, сумма"');
        expect(closed).not.toContain('Закрыть поиск');
        expect(renderToStaticMarkup(<SearchField value="п" open onChange={() => {}} onOpen={() => {}} onClose={() => {}} />))
            .toContain('aria-label="Закрыть поиск"');
    });

    it('чипы: «За всё время», «Категория», «Вид», «Только записи» — включён; выбранное — подписью', () => {
        const cats = [cat('food', 'Продукты', 'EXPENSE'), cat('sal', 'Зарплата', 'INCOME')];
        const html = renderToStaticMarkup(<SearchChips criteria={NO_SEARCH} categories={cats} onChange={() => {}} />);
        const t = text(html);
        expect(t.indexOf('За всё время')).toBeLessThan(t.indexOf('Категория'));
        expect(t.indexOf('Категория')).toBeLessThan(t.indexOf('Вид'));
        expect(t.indexOf('Вид')).toBeLessThan(t.indexOf('Только записи'));
        expect(html).toContain('aria-pressed="true"');
        expect(html).toMatch(/<optgroup label="Расходы"><option value="food">Продукты<\/option><\/optgroup><optgroup label="Доходы"><option value="sal">Зарплата/);
        // Подпись чипа — выбранное (в списке выбора те же слова есть всегда).
        const chosen = renderToStaticMarkup(<SearchChips categories={cats} onChange={() => {}}
            criteria={{ ...NO_SEARCH, period: 'year', categoryId: 'food', priority: 'HIGH', factsOnly: false }} />);
        for (const label of ['Этот год', 'Продукты', 'Бронь']) expect(chosen).toContain(`<span class="max-w-[180px] truncate">${label}</span>`);
        // Выбранное — залито: три чипа с выбором; по умолчанию — только «Только записи».
        expect(chosen.match(/bg-sf-accent font-medium/g)).toHaveLength(3);
        expect(html.match(/bg-sf-accent font-medium/g)).toHaveLength(1);
    });

    it('«Нашлось 2 записи · 2 220 ₽ за всё время», месяцы, строки — кнопки', () => {
        const html = renderToStaticMarkup(<SearchResults criteria={Q} fresh failed={false} onRetry={() => {}} onRow={() => {}}
            result={{ total: 2, recorded: -2220, rows: [ev({ id: 'a' }), ev({ id: 'b', date: '2026-10-29', factAmount: 980 })] }} />);
        expect(text(html)).toContain('Нашлось 2 записи · 2 220 ₽ за всё время');
        expect(text(html)).toMatch(/Ноябрь 2026 · 1 .*Пятёрочка 12 нояб\. · Продукты 1 240 ₽ .*Октябрь 2026 · 1/);
        expect(html.match(/<button type="button"/g)).toHaveLength(2);
        // Запись — кружок залит, план — обводкой.
        expect(html).toContain('bg-sf-kind-ozh-bg');
        const plan = renderToStaticMarkup(<SearchResults criteria={Q} fresh failed={false} onRetry={() => {}} onRow={() => {}}
            result={{ total: 1, recorded: 0, rows: [ev({ eventKind: 'PLAN', factAmount: null, plannedAmount: 900 })] }} />);
        expect(plan).not.toContain('bg-sf-kind-ozh-bg');
    });

    it('пока ищется новое — прежнее приглушено; нашлось больше, чем пришло, — «Показаны N свежих»', () => {
        const html = renderToStaticMarkup(<SearchResults criteria={Q} fresh={false} failed={false} onRetry={() => {}} onRow={() => {}}
            result={{ total: 340, recorded: -1000, rows: [ev({})] }} />);
        expect(html).toContain('opacity-60');
        expect(html).toContain('aria-busy="true"');
        expect(text(html)).toContain('Показаны 1 свежих');
        expect(renderToStaticMarkup(<SearchResults criteria={Q} fresh failed={false} onRetry={() => {}} onRow={() => {}}
            result={{ total: 1, recorded: -1000, rows: [ev({})] }} />)).not.toContain('opacity-60');
    });

    it('пусто, ошибка, ожидание и «ещё не что искать» — словами', () => {
        const at = (props: Partial<Parameters<typeof SearchResults>[0]>) => text(renderToStaticMarkup(
            <SearchResults criteria={Q} result={null} fresh failed={false} onRetry={() => {}} onRow={() => {}} {...props} />));
        expect(at({ result: { rows: [], total: 0, recorded: 0 } })).toContain('Ничего не нашлось за всё время');
        expect(at({ failed: true })).toContain('Поиск не удался — повторить');
        expect(at({})).toContain('Загрузка…');
        expect(at({ criteria: NO_SEARCH })).toContain('Название, категория или сумма — по всем месяцам.');
    });

    it('страница: лупа открывает поиск, крестик — к месяцу и прокрутке; журнал месяца прячется, а не снимается; карточка — и из найденного', () => {
        const page = readFileSync(new URL('../../pages/Budget.tsx', import.meta.url), 'utf8');
        expect(page).toMatch(/aria-label="Поиск по журналу" onClick=\{openSearch\}/);
        expect(page).toMatch(/onClose=\{closeSearch\}/);
        expect(page).toMatch(/const closeSearch = \(\) => \{[^}]*setSearching\(false\);[^}]*setCriteria\(NO_SEARCH\);[^}]*scrollTo\(0, scrollBefore\.current\)/);
        expect(page).toMatch(/<div className=\{cn\(searching && 'hidden'\)\}>\s*\{loading/);
        expect(page).toMatch(/onRetry=\{found\.retry\} onRow=\{setCard\}/);
        expect(page).toMatch(/const inMonth = events\.find\(e => e\.id === card\.id\) \?\? after\.find\(e => e\.id === card\.id\);/);
        expect(page).toMatch(/const live = inMonth \?\? found\.result\?\.rows\.find\(e => e\.id === card\.id\)/);
        // Ревью Codex #237: строка только из найденного занята, пока поиск не перечитан после записи.
        expect(page).toMatch(/const held = guard\.held\(live\.id\) \|\| \(!inMonth && !found\.fresh\);/);
        expect(page).toMatch(/<EventCard key=\{live\.id\} event=\{live\} today=\{allowedToday\} pay=\{pay\} held=\{held\}/);
        expect(page).toMatch(/const plan = events\.find[^;]*\?\? found\.result\?\.rows\.find\(e => e\.id === factSheet\.planId\)/);
        expect(page).toMatch(/if \(placed\.current && !searchingNow\.current\) saveJournalPosition/);
        // Телефон: пока ищем, строки месяца и кружков нет, поле — вместо них; на 1440 строка месяца стоит.
        expect(page).toMatch(/<div className=\{cn\(searching && 'hidden lg:block'\)\}>\s*<MonthPicker/);
        expect(page).toMatch(/\{!searching && !loading && header && \(/);
        expect(page).toMatch(/className=\{cn\('flex-1 lg:max-w-\[440px\]', !searching && 'hidden lg:flex'\)\}/);
        // Повторное открытие (фокус в поле на 1440) не перетирает прокрутку месяца; лупа ставит фокус в поле.
        expect(page).toMatch(/const openSearch = \(\) => \{\s*if \(searchingNow\.current\) return;\s*scrollBefore\.current = window\.scrollY;/);
        expect(page).toMatch(/useEffect\(\(\) => \{ if \(searching\) searchRef\.current\?\.focus\(\); \}, \[searching\]\);/);
    });

    it('поиск заново после записи в этой вкладке и в другой; ввод открывает поиск, Esc закрывает', () => {
        const src = readFileSync(new URL('./JournalSearch.tsx', import.meta.url), 'utf8');
        expect(src).toMatch(/window\.addEventListener\(CHANGED, again\)/);
        expect(src).toMatch(/\}, \[key, tick\]\);/);
        // Свежее — только чтение этих критериев после последней записи (tick в состоянии).
        expect(src).toContain('result => { if (live) setState({ key, tick, result, failed: false }); }');
        expect(src).toContain('const read = key !== \'\' && state.key === key && state.tick === tick;');
        expect(src).toContain('fresh: read && !state.failed,');
        expect(src).toContain('onChange={e => { onChange(e.target.value); onOpen(); }} onFocus={onOpen}');
        expect(src).toContain(`onKeyDown={e => { if (e.key === 'Escape') onClose(); }}`);
    });
});
