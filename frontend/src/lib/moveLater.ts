import { fetchEvents, setEventWishlistParams, updateEvent } from '../api';
import type { UpcomingItem } from '../types/api';

/** День после ISO-даты, без UTC-разбора. */
export function dayAfter(iso: string): string {
    const [y, m, d] = iso.split('-').map(Number);
    const n = new Date(y, m - 1, d + 1);
    return `${n.getFullYear()}-${String(n.getMonth() + 1).padStart(2, '0')}-${String(n.getDate()).padStart(2, '0')}`;
}

/**
 * «Сдвинуть» у строки «Можно сдвинуть на после …» (спека среза 2, п. 6): на следующий день после конца срока.
 * Хотелка — своей правкой параметров. Строка плана — `PUT /events/{id}` со `scope=THIS` и прежними полями:
 * у повтора сдвигается только эта строка, правило остаётся. Поля берутся из журнала на её дату — в «осталось
 * потратить» остаток, а не сумма плана, и нет категории.
 */
export async function moveLater(u: UpcomingItem, horizonEnd: string): Promise<void> {
    if (u.id == null) throw new Error('Строку без id сдвинуть нельзя');
    const date = dayAfter(horizonEnd);
    if (u.wishlist) return setEventWishlistParams(u.id, { plannedAmount: u.amount, date });
    const e = (await fetchEvents(u.date, u.date)).find(x => x.id === u.id);
    if (!e) throw new Error('Строка плана не нашлась на своей дате');
    await updateEvent(e.id, {
        date, categoryId: e.categoryId, type: e.type, plannedAmount: e.plannedAmount ?? undefined,
        priority: e.priority, mandatory: e.mandatory, description: e.description ?? undefined,
        rawInput: e.rawInput ?? undefined,
    }, 'THIS');
}
