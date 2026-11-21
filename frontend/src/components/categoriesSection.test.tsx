/**
 * «Категории» в «Настройках» (срез 3, п. 15; доска S3-F5 без перетаскивания): «Расходы | Доходы», порядок сервера,
 * кружок вида, галочка «прогноз», правка и удаление, правило над «Новая категория».
 */
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import type { Category } from '../types/api';
import CategoriesSection from './CategoriesSection';

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
});
