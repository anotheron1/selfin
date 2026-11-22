import { pocketResult } from '../../lib/gapMode';
import { fmtDayShort } from '../../lib/format';
import type { PocketResponse } from '../../types/api';

/**
 * Разделы приложения (решение 25) и их экраны. Адреса экранов прежние: раздел — это группа, а не новый адрес.
 * Настройки — ни в одном разделе: их открывает профиль.
 */
export const SECTIONS = [
    { key: 'today', label: 'Сегодня', screens: [{ to: '/', label: 'Сегодня' }] },
    { key: 'journal', label: 'Журнал', screens: [{ to: '/budget', label: 'Журнал' }, { to: '/analytics', label: 'Итоги месяца' }] },
    {
        key: 'goals', label: 'Цели', screens: [
            { to: '/funds', label: 'Цели' },
            { to: '/wishlist', label: 'Примерить покупку' },
            { to: '/strategy', label: 'На годы вперёд' },
        ],
    },
    { key: 'money', label: 'Деньги', screens: [{ to: '/capital', label: 'Деньги' }] },
] as const;

export type Section = (typeof SECTIONS)[number];

export const sectionOf = (path: string): Section | null =>
    SECTIONS.find(s => s.screens.some(x => x.to === path)) ?? null;

/** На «Сегодня» число уже на карточке; раскрытая шапка там — срез 2. */
export const showsHeader = (path: string) => path !== '/';

/** Плитка «на картах» в шапке — не на «Деньгах»: она вела бы туда же (срез 4, п. 9, решение 55). */
export const showsCards = (path: string) => path !== '/capital';

const shortDate = fmtDayShort;

/** Строка шапки — те же слова и сумма без минуса, что у итога карточки (pocketResult), и конец срока. */
export function headerLine(p: PocketResponse): { label: string; amount: number } {
    const { label, amount } = pocketResult(p);
    return { label: `${label} до ${shortDate(p.horizon.endDate)}`, amount };
}

export { shortDate };
