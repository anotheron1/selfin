/**
 * Карточка и правка той же формой (срез 3 пп. 16–18; доски S3-E2a, S3-E2b, S3-E1a, S3-E1b). Лист — Radix Dialog на
 * портале, на сервере он не рисуется: здесь подменён простым div, содержимое — настоящее.
 */
import { readFileSync } from 'node:fs';
import { describe, expect, it, vi } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import type { ReactNode } from 'react';
import type { FinancialEvent, ScopeEnum } from '../../types/api';
import type { QuickClose } from '../../lib/quickClose';

vi.mock('../FormSheet', () => ({ default: ({ children }: { children: ReactNode }) => <div>{children}</div> }));
const { default: EventCard } = await import('./EventCard');
const { default: QuickAddModal } = await import('../Fab');

const ev = (over: Partial<FinancialEvent>): FinancialEvent => ({
    id: 'e', date: '2026-11-12', categoryId: 'c', categoryName: 'Жильё и быт', type: 'EXPENSE', plannedAmount: 25000,
    factAmount: null, status: 'PLANNED', priority: 'HIGH', description: 'Аренда', rawInput: null,
    createdAt: '2026-11-01T09:00:00', eventKind: 'PLAN', parentEventId: null, linkedFactsCount: 0,
    linkedFactsAmount: null, parentPlanDescription: null, ...over,
});
const SERIES = ev({
    recurringRuleId: 'r', recurringFrequency: 'MONTHLY', recurringDayOfMonth: 12, recurringStartDate: '2026-03-12', recurringEndDate: null,
});
const FACT = ev({
    eventKind: 'FACT', status: 'EXPENSE' as never, factAmount: 1240, plannedAmount: null, priority: 'MEDIUM', categoryName: 'Продукты',
    description: 'Пятёрочка', parentEventId: 'p', parentPlanPriority: 'MEDIUM', parentPlanDescription: 'Продукты',
});
const TODAY = '2026-11-12';
const text = (html: string) => html.replace(/<[^>]+>/g, ' ').replace(/&nbsp;| /g, ' ').replace(/\s+/g, ' ');
const card = (e: FinancialEvent, pay?: QuickClose) => text(renderToStaticMarkup(
    <EventCard event={e} today={TODAY} pay={pay} onClose={() => {}} onEdit={() => {}} onPaid={() => {}} onOwn={() => {}} onWritten={() => {}} />));
const form = (e: FinancialEvent, scope: ScopeEnum = 'FOLLOWING') => renderToStaticMarkup(
    <QuickAddModal edit={{ event: e, scope }} onClose={() => {}} onSuccess={() => {}} />);

describe('карточка записи (S3-E2a)', () => {
    const t = card(FACT);
    it('что это, строки, что с ней случилось', () => {
        expect(t).toContain('Пятёрочка');
        expect(t).toContain('запись · трата');
        expect(t).toContain('1 240');
        for (const k of ['Категория Продукты', 'Дата сегодня, 12 нояб.', 'Название Пятёрочка', 'Вид ожидание']) expect(t).toContain(k);
        expect(t).toContain('Встала к ожиданию «Продукты».');
    });
    it('корзина, «Изменить», «Ещё раз сегодня»; «Оплачено» и серии нет', () => {
        expect(renderToStaticMarkup(<EventCard event={FACT} today={TODAY} onClose={() => {}} onEdit={() => {}} onPaid={() => {}}
            onOwn={() => {}} onWritten={() => {}} />)).toContain('aria-label="Удалить запись"');
        expect(t).toContain('Изменить');
        expect(t).toContain('Ещё раз сегодня');
        expect(t).not.toContain('Оплачено');
        expect(t).not.toContain('Закончить серию');
    });
});

describe('карточка плана из серии (S3-E2b)', () => {
    const tap: QuickClose = { kind: 'tap', amount: 25000, date: TODAY };
    const t = card(SERIES, tap);
    it('«Оплачено | своё», когда дата наступила', () => {
        expect(t).toContain('Оплачено');
        expect(t).toContain('своё');
        expect(t).toContain('план · бронь · сегодня, 12 нояб.');
    });
    it('серия целиком: правило, с какой даты, без конца, три даты дальше', () => {
        expect(t).toContain('Каждый месяц, 12-го');
        expect(t).toContain('с 12 мар. 2026, без конца · дальше:');
        for (const d of ['12 дек.', '12 янв.', '12 февр.']) expect(t).toContain(d);
        expect(t).toContain('Изменить серию');
        expect(t).toContain('Закончить серию');
    });
    it('«Удалить этот платёж», «Пропустить», «Изменить этот»', () => {
        const html = renderToStaticMarkup(<EventCard event={SERIES} today={TODAY} pay={tap} onClose={() => {}} onEdit={() => {}}
            onPaid={() => {}} onOwn={() => {}} onWritten={() => {}} />);
        expect(html).toContain('aria-label="Удалить этот платёж"');
        expect(t).toContain('Пропустить');
        expect(t).toContain('Изменить этот');
    });
    it('дата впереди — оплаты нет', () => {
        expect(card(SERIES, { kind: 'none' } as QuickClose)).not.toContain('Оплачено');
        expect(card(SERIES)).not.toContain('Оплачено');
    });
    it('последний платёж серии — «это последний платёж», закончить нечего', () => {
        const last = card({ ...SERIES, recurringEndDate: '2026-11-12' });
        expect(last).toContain('это последний платёж');
        expect(last).not.toContain('дальше:');
    });
    it('план без серии — «Изменить», без «Пропустить» и блока серии', () => {
        const one = card(ev({}));
        expect(one).toContain('Изменить');
        expect(one).not.toContain('Пропустить');
        expect(one).not.toContain('Каждый месяц');
    });
});

describe('правка той же формой (S3-E1a, S3-E1b)', () => {
    it('запись: «Сохранить», «Ещё раз», корзина; кнопок по дате и типа нет', () => {
        const html = form(FACT);
        expect(html).toContain('Сохранить');
        expect(html).toContain('Ещё раз');
        expect(html).toContain('aria-label="Удалить запись"');
        expect(text(html)).toMatch(/Запись · 12 нояб\./);
        expect(html).not.toContain('Запланировать');
        expect(html).not.toContain('Расход или доход');
        expect(html).not.toContain('Изменить</span>');
    });
    it('план из серии: «Изменить: Только этот | Этот и дальше | Всю серию», открытый scope выбран', () => {
        const html = form(SERIES, 'ALL');
        for (const s of ['Только этот', 'Этот и дальше', 'Всю серию']) expect(html).toContain(s);
        expect(html).toContain('checked="" value="ALL"');
        expect(html).toContain('aria-label="Удалить план"');
        expect(html).not.toContain('Ещё раз');
        expect(text(html)).toContain('План · повторяется');
    });
    it('«Только этот» — повтора в форме нет: меняется одна строка', () => {
        expect(form(SERIES, 'THIS')).not.toContain('Каждый месяц');
        expect(form(SERIES, 'FOLLOWING')).toContain('Каждый месяц, 12-го');
    });
    it('сумма и название заполнены', () => {
        const html = form(SERIES);
        expect(html).toContain('value="25000"');
        expect(html).toContain('value="Аренда"');
    });
    it('вид — с пояснением, у строки с экрана «Хотелки» — заперт', () => {
        expect(form(SERIES)).toContain('сумма и дата известны заранее');
        const wish = form(ev({ priority: 'LOW', wishlistStatus: 'FIXED' }));
        expect(wish).toContain('характер не меняется');
        expect(wish).toMatch(/<input(?=[^>]*name="kind")(?=[^>]*checked="")(?=[^>]*disabled="")[^>]*value="LOW"/);
    });
});

describe('сторожа по исходнику: нажатия проверяет стенд', () => {
    const fab = readFileSync(new URL('../Fab.tsx', import.meta.url), 'utf8');
    const page = readFileSync(new URL('../../pages/Budget.tsx', import.meta.url), 'utf8');
    it('у записи к брони и у перевода на виду только своя категория, без «ещё»', () => {
        expect(fab).toContain('const locked = ev != null && categoryLocked(ev);');
        expect(fab).toContain('const shownChips = locked ? all.filter(c => c.id === categoryId)');
        expect(fab).toContain('{chips.rest.length > 0 && !locked && (');
    });
    it('правка плана — PUT со своим scope у серии, «Только этот» у разовой', () => {
        expect(fab).toContain("}), series ? scope : 'THIS');");
    });
    it('нажатие на строку журнала — карточка, не форма', () => {
        expect(page).toContain('onRow: setCard,');
        expect(page).not.toContain('EditEventSheet');
    });
});
