/**
 * «Категории» в «Настройках» (срез 3, п. 15; доска S3-F5 без перетаскивания): «Расходы | Доходы», порядок сервера,
 * кружок вида, галочка «прогноз», правка и удаление, правило над «Новая категория».
 */
import { readFileSync } from 'node:fs';
import type { ReactNode } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import type { Category } from '../types/api';
import CategoriesSection, { CategoryRow, CategorySheet, saveFailure } from './CategoriesSection';
import { ApiError } from '../api/client';

vi.mock('./FormSheet', () => ({ default: ({ children }: { children: ReactNode }) => <div>{children}</div> }));

const cat = (over: Partial<Category>): Category => ({
    id: 'c', name: 'Продукты', type: 'EXPENSE', priority: 'MEDIUM', isSystem: false, forecastEnabled: false,
    primaryIncome: false, systemKind: null, ...over,
});
const CATS = [
    cat({ id: 'cafe', name: 'Кафе и доставка', forecastEnabled: true }),
    cat({ id: 'food', name: 'Продукты' }),
    cat({ id: 'home', name: 'Жильё и быт', priority: 'HIGH' }),
    cat({ id: 'wish', name: 'Хотелки', priority: 'LOW', isSystem: true, systemKind: 'WISHLIST' }),
    cat({ id: 'salary', name: 'Зарплата', type: 'INCOME', primaryIncome: true }),
];
const text = (html: string) => html.replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').replace(/ /g, ' ');
const render = () => renderToStaticMarkup(
    <CategoriesSection categories={CATS} readiness={{ readyFrom: '2026-12' } as never} onChanged={() => {}} showToast={() => {}} />);

describe('«Категории» в «Настройках»', () => {
    it('расходы — в порядке сервера, доходов не видно; «Расходы | Доходы» сверху', () => {
        const t = text(render());
        expect(t).toMatch(/Категории Расходы Доходы .*Кафе и доставка .*Продукты .*Жильё и быт .*Хотелки/);
        expect(t).not.toContain('Зарплата');
    });

    it('кружок вида — кнопка смены вида; «прогноз» — галочкой; у системной — ни галочки, ни правки, ни удаления', () => {
        const html = render();
        expect(html).toContain('aria-label="Вид: ожидание. Нажми — сменить"');
        expect(html).toContain('aria-label="Вид: бронь. Нажми — сменить"');
        expect(html).toContain('aria-label="Вид: хотелка. Нажми — сменить"');
        expect(html.match(/type="checkbox"/g)).toHaveLength(3);
        expect(html).toMatch(/<input type="checkbox"[^>]*checked=""[^>]*\/>прогноз/);
        expect(text(html)).toContain('Кафе и доставка прогноз — с декабря');
        expect(text(html)).toContain('Хотелки системная');
        expect(text(html)).not.toContain('Продукты прогноз — с');
        expect(html).toContain('aria-label="Изменить «Продукты»"');
        expect(html).toContain('aria-label="Удалить «Продукты»"');
        expect(html).not.toContain('Изменить «Хотелки»');
        expect(html).not.toContain('Удалить «Хотелки»');
    });

    it('правило над «Новая категория» и «Как делить категории»; перетаскивания и черты «на виду» нет', () => {
        const t = text(render());
        expect(t).toContain('Категория отвечает на один вопрос: на что ушли деньги. Новую заводи, только если из-за неё ты станешь тратить по-другому. Как делить категории Новая категория');
        expect(t).not.toMatch(/Перетащ|на виду/);
    });

    it('удаление — с вопросом, как раньше; новая категория — того типа, что открыт; отказ — словами', () => {
        const src = readFileSync(new URL('./CategoriesSection.tsx', import.meta.url), 'utf8');
        expect(src).toContain(`if (!confirm('Удалить категорию? Связанные события сохранятся.')) return;`);
        expect(src).toMatch(/setSheet\(\{ id: null, name: '', type, priority: 'MEDIUM'/);
        expect(src).toMatch(/\(\) => setFailed\(failure\)/);
        expect(src).toMatch(/forecastEnabled: d\.type === 'EXPENSE' \? d\.forecast : false,\s*primaryIncome: d\.type === 'INCOME' \? d\.primary : false/);
    });

    it('нет категорий этого типа — «Нет категорий»', () => {
        const html = renderToStaticMarkup(<CategoriesSection categories={[CATS[4]]} readiness={null} onChanged={() => {}} showToast={() => {}} />);
        expect(text(html)).toContain('Нет категорий');
    });

    it('строка дохода: «основной доход», без галочки «прогноз»', () => {
        const row = (c: Category) => renderToStaticMarkup(<ul><CategoryRow c={c} readiness={null}
            onCycle={() => {}} onForecast={() => {}} onEdit={() => {}} onRemove={() => {}} /></ul>);
        const html = row(CATS[4]);
        expect(text(html)).toContain('Зарплата основной доход');
        expect(html).not.toContain('type="checkbox"');
        expect(html).toContain('Изменить «Зарплата»');
        // Прогноз без даты готовности — только галочкой, без подписи.
        expect(text(row(CATS[0]))).not.toContain('прогноз — с');
    });

    it('лист: новая или правка; расход — «Отслеживать прогноз» с пояснением, доход — «Основной доход»; без имени не сохранить', () => {
        const sheet = (start: Parameters<typeof CategorySheet>[0]['start']) =>
            renderToStaticMarkup(<CategorySheet start={start} onClose={() => {}} onSaved={() => {}} />);
        const fresh = sheet({ id: null, name: '', type: 'EXPENSE', priority: 'MEDIUM', forecast: false, primary: false });
        expect(text(fresh)).toContain('Новая категория');
        expect(text(fresh)).toContain('Отслеживать прогноз');
        expect(text(fresh)).toContain('Нужно три месяца наблюдений');
        expect(text(fresh)).not.toContain('Основной доход');
        expect(fresh).toMatch(/<button type="submit"[^>]*disabled=""/);
        const income = sheet({ id: 'salary', name: 'Зарплата', type: 'INCOME', priority: 'MEDIUM', forecast: false, primary: true });
        expect(text(income)).toContain('Изменить категорию');
        expect(text(income)).toContain('Основной доход (до него считаются свободные деньги)');
        expect(text(income)).not.toContain('Отслеживать прогноз');
        expect(income).not.toMatch(/<button type="submit"[^>]*disabled=""/);
    });
});

describe('отказ сохранения категории', () => {
    it('имя уже было (409) — выбрать другое, без «попробуйте ещё раз»; остальное — запасной фразой', () => {
        expect(saveFailure(new ApiError(409, [], 'conflict'))).toBe('Такое имя у категории уже было — выбери другое');
        expect(saveFailure(new ApiError(500, [], 'boom'))).toBe('Не записалось — попробуйте ещё раз');
        expect(saveFailure(new Error('сеть'))).toBe('Не записалось — попробуйте ещё раз');
    });
});
