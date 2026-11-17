import type { Category, FinancialEventCreateDto, PocketResponse } from '../types/api';

/**
 * Обычные траты до дохода (спека среза 2, пп. 10, 11, вопрос 4): продукты, транспорт, кафе и доставка — по ожиданию
 * на категорию. Дата — день перед доходом срока: ожидание с прошедшей датой не держится, и запись сегодняшним
 * числом завтра вернула бы эти деньги в «Свободно». Доход повторяется каждый месяц — ожидание тоже, тем же днём
 * перед ним; доход 1-го — повтор 31-го: генератор повторов прижимает число к концу месяца.
 */
export const USUAL_CATEGORIES = ['Продукты', 'Транспорт', 'Кафе и доставка'] as const;
export type UsualName = (typeof USUAL_CATEGORIES)[number];

/** Доход, которым кончается срок: его дата и id строки плана (повтор узнаётся по ней). null — срок не доходом. */
export function termIncome(p: PocketResponse): { date: string; id: string | null } | null {
    const end = p.trajectory.find(t => t.date === p.horizon.endDate);
    const inc = end?.items.find(i => i.type === 'INCOME');
    return end && end.income > 0 && inc ? { date: end.date, id: inc.id } : null;
}

const iso = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;

export function usualDtos(
    amounts: Partial<Record<UsualName, number>>,
    categories: Pick<Category, 'id' | 'name' | 'type'>[],
    income: { date: string; monthly: boolean },
    today: string,
): FinancialEventCreateDto[] {
    const [y, m, d] = income.date.split('-').map(Number);
    const before = iso(new Date(y, m - 1, d - 1));
    const date = before < today ? today : before;
    const recurring = income.monthly
        ? { frequency: 'MONTHLY' as const, dayOfMonth: d === 1 ? 31 : d - 1, startDate: date }
        : null;
    return USUAL_CATEGORIES.flatMap(name => {
        const amount = amounts[name];
        if (!amount || amount <= 0) return [];
        const cat = categories.find(c => c.type === 'EXPENSE' && c.name === name);
        if (!cat) throw new Error(`Нет категории «${name}»`);
        return [{ date, categoryId: cat.id, type: 'EXPENSE' as const, plannedAmount: amount, priority: 'MEDIUM' as const, recurring }];
    });
}
