import { todayIso } from './factDate';

/**
 * Период таблицы «Аналитики»: последние {@code months} месяцев по текущий включительно, с первого
 * дня по последний. Местными датами (ANO-220): toISOString() переводил местную полночь в UTC, и в
 * Москве «3 мес» начинались месяцем раньше, а последний день текущего выпадал.
 */
export function periodRange(months: number, today: Date): { startDate: string; endDate: string } {
    const start = new Date(today.getFullYear(), today.getMonth() - months + 1, 1);
    const end = new Date(today.getFullYear(), today.getMonth() + 1, 0);
    return { startDate: todayIso(start), endDate: todayIso(end) };
}
