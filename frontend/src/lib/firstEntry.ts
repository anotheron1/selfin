import { parseAmountExpression } from './amountExpression';
import type { Category, FinancialEventCreateDto, PocketResponse } from '../types/api';

/**
 * Первый вход (спека среза 2, пп. 11, 16; ANO-250): ответы на четыре вопроса → записи. Вопрос 1 — сверка остатка,
 * тот же запрос, что у `ReanchorSheet`: `{ date: сегодня, amount }`. Вопрос 4 — `UsualSpendingForm` из PR 1.
 * Кто и когда пишет — `firstEntryWrites.ts`.
 */

type Cat = Pick<Category, 'id' | 'name' | 'type' | 'isSystem'>;

/** Ни сверки остатка, ни строк плана впереди — то же условие, что у прежнего пустого Дашборда. */
export const needsFirstEntry = (p: Pick<PocketResponse, 'checkpointDate' | 'upcoming'>): boolean =>
    p.checkpointDate == null && p.upcoming.length === 0;

const positive = (raw: string) => {
    const v = parseAmountExpression(raw).value;
    return v != null && v > 0 ? v : null;
};

/** Вопрос 1: остаток. Ноль — тоже ответ: до зарплаты на карте бывает пусто. */
export const balanceAnswer = (raw: string): number | null => (/^\s*0+\s*$/.test(raw) ? 0 : positive(raw));

/** Вопрос 2: дата не раньше сегодня — повтор с прошедшей датой сервер не заведёт (I3); сумма больше нуля. */
export function incomeAnswer(raw: { date: string; amount: string; monthly: boolean }, today: string) {
    const amount = positive(raw.amount);
    return raw.date >= today && amount != null ? { ...raw, amount } : null;
}

/** Вопрос 3: строка платежа пишется, когда в ней есть число и сумма; имя — не обязательно. */
export function paymentAnswer(raw: { name: string; day: string; amount: string }) {
    const amount = positive(raw.amount);
    return raw.day && amount != null ? { name: raw.name, day: Number(raw.day), amount } : null;
}

const own = (cats: Cat[], type: Cat['type']) => cats.filter(c => c.type === type && !c.isSystem);

function categoryId(cats: Cat[], type: Cat['type'], ...names: string[]): string {
    const mine = own(cats, type);
    const id = names.map(n => mine.find(c => c.name === n)?.id).find(Boolean) ?? mine[0]?.id;
    if (!id) throw new Error(`Нет своей категории ${type}`);
    return id;
}

const monthly = (date: string, day: number) => ({ frequency: 'MONTHLY' as const, dayOfMonth: day, startDate: date });

/** Вопрос 2: доход в «Зарплате», бронь; «каждый месяц» — повтор тем же числом. */
export function incomeDto(cats: Cat[], a: { date: string; amount: number; monthly: boolean }): FinancialEventCreateDto {
    return {
        date: a.date, categoryId: categoryId(cats, 'INCOME', 'Зарплата'), type: 'INCOME', plannedAmount: a.amount,
        priority: 'HIGH', recurring: a.monthly ? monthly(a.date, Number(a.date.slice(8))) : null,
    };
}

const iso = (y: number, m: number, d: number) => `${y}-${String(m).padStart(2, '0')}-${String(d).padStart(2, '0')}`;
const lastDay = (y: number, m: number) => new Date(y, m, 0).getDate();

/**
 * Ближайшее `day`-е число начиная с `today`. Как у генератора повторов (`RecurringEventGenerator`): в коротком месяце
 * число прижимается к последнему дню — 31-е в ноябре — 30-е. Иначе первая дата брони разошлась бы с её повторами.
 */
export function firstPayDate(day: number, today: string): string {
    const [y, m] = today.split('-').map(Number);
    const here = iso(y, m, Math.min(day, lastDay(y, m)));
    if (here >= today) return here;
    const [ny, nm] = m === 12 ? [y + 1, 1] : [y, m + 1];
    return iso(ny, nm, Math.min(day, lastDay(ny, nm)));
}

/** Категория платежа по имени; правится потом в журнале. */
export function paymentCategory(name: string): 'Жильё и быт' | 'Связь и подписки' | 'Обязательные платежи' {
    const n = name.toLowerCase();
    if (/аренд|ипотек/.test(n)) return 'Жильё и быт';
    if (/связ|интернет/.test(n)) return 'Связь и подписки';
    return 'Обязательные платежи';
}

/** Вопрос 3: строка «имя, число месяца, сумма» — бронь с повтором каждый месяц. */
export function paymentDto(cats: Cat[], row: { name: string; day: number; amount: number }, today: string): FinancialEventCreateDto {
    const date = firstPayDate(row.day, today);
    const name = row.name.trim();
    return {
        date, categoryId: categoryId(cats, 'EXPENSE', paymentCategory(name), 'Обязательные платежи'), type: 'EXPENSE',
        plannedAmount: row.amount, priority: 'HIGH', description: name || undefined, recurring: monthly(date, row.day),
    };
}
