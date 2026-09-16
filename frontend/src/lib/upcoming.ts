import type { UpcomingItem } from '../types/api';

/**
 * ANO-119: группировка списка «осталось потратить» для дашборда.
 *
 * Две группы, и они разные по смыслу. Просроченное — то, чему дата уже прошла, а факта
 * нет; кармашек держит это бронью, и человеку важно увидеть это первым. Остальное — по
 * датам вперёд, как платёжный календарь.
 *
 * Порядок внутри приходит с сервера (движок отдаёт просрочку, затем даты по возрастанию)
 * и здесь не пересортировывается: сортировать во второй раз — значит завести второе
 * правило порядка, которое однажды разойдётся с первым.
 */
export interface UpcomingGroups {
    overdue: UpcomingItem[];
    byDate: { date: string; items: UpcomingItem[] }[];
}

export function groupUpcoming(items: UpcomingItem[]): UpcomingGroups {
    const overdue: UpcomingItem[] = [];
    const byDate: { date: string; items: UpcomingItem[] }[] = [];

    for (const item of items) {
        if (item.overdue) {
            overdue.push(item);
            continue;
        }
        const last = byDate[byDate.length - 1];
        if (last && last.date === item.date) last.items.push(item);
        else byDate.push({ date: item.date, items: [item] });
    }

    return { overdue, byDate };
}

/** Сумма строк — для подписи группы. Отдельной функцией, чтобы не считать в разметке. */
export function sumUpcoming(items: UpcomingItem[]): number {
    return items.reduce((acc, i) => acc + i.amount, 0);
}
