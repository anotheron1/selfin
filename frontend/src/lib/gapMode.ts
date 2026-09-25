import type { PocketResponse, UpcomingItem } from '../types/api';
import { fmtDayMonth, fmtRub as fmtC } from './format';

/**
 * Режим нехватки у карточки кармашка (ANO-100 вместе с ANO-76, вариант Б владельца 24.09).
 *
 * Включается, когда деньги кончаются в пределах срока: минимум траектории ниже нуля.
 * Кармашек ниже нуля только из-за подушки — «впритык», не этот режим.
 *
 * Отрицательное число под словом «В кармашке» посторонний читал как долг в прошлом:
 * «ушла в долг, потратила больше положенного» (провал A1). Поэтому здесь заголовок — фраза
 * с датой впереди и без минуса, а вместо «займи» карточка говорит, что можно сдвинуть.
 *
 * Спека: docs/superpowers/specs/2026-09-24-gap-mode-design.md
 */
export interface GapMode {
    horizonLabel: string;
    headline: string;
    /** Что будет с доходом; null, когда срок не заякорен доходом. */
    subline: string | null;
    moveHeader: string;
    /** До трёх строк «Кафе — 900 ₽ → не хватит 2 785 ₽», крупные первыми. */
    movable: string[];
}

const MOVABLE_LIMIT = 3;

const fmtD = (iso: string) => {
    const [, m, d] = iso.split('-');
    return `${d}.${m}`;
};

export function buildGapMode(p: PocketResponse): GapMode | null {
    const { minPoint, horizon, trajectory } = p;
    if (minPoint.balance >= 0) return null;

    const gap = -minPoint.balance;
    // «Сегодня» — день 0 траектории, то есть сегодня сервера, а не браузера: так же решает
    // фраза-ответ, и в разных часовых поясах они не разойдутся.
    const today = trajectory[0]?.date;
    const headline = minPoint.date === today
        ? `Сегодня по плану не хватает ${fmtC(gap)}`
        : `${fmtDayMonth(minPoint.date)} по плану не хватит ${fmtC(gap)}`;

    const incomeAnchored =
        (horizon.type === 'NEXT_INCOME' || horizon.type === 'SECOND_INCOME') && !horizon.fallback;
    const endPoint = trajectory.find(pt => pt.date === horizon.endDate);
    const subline = !incomeAnchored || !endPoint ? null
        : endPoint.balance >= 0
            ? `Доход ${fmtD(horizon.endDate)} закроет разрыв, останется ${fmtC(endPoint.balance)}.`
            : `Даже доход ${fmtD(horizon.endDate)} не закроет разрыв — план требует правки.`;

    const movable = p.upcoming
        .filter(u => isMovable(u, minPoint.date))
        .sort((a, b) => b.amount - a.amount)
        .slice(0, MOVABLE_LIMIT)
        .map(u => {
            const after = gapAfterMoving(p, u);
            // Своё имя строки точнее категории («Стрижка», а не «Услуги людские»). Тире, а не
            // запятая: в названиях категорий запятые есть — «Кафе, рестики, фастфуд».
            const label = u.description ?? u.categoryName ?? '—';
            return `${label} — ${fmtC(u.amount)} → ${after > 0 ? `не хватит ${fmtC(after)}` : 'разрыва не будет'}`;
        });

    return {
        horizonLabel: horizon.label,
        headline,
        subline,
        moveHeader: `Можно сдвинуть на после ${fmtD(horizon.endDate)}:`,
        movable,
    };
}

/**
 * Что можно сдвинуть: ожидания и хотелки не позже узкого дня.
 *
 * Список «осталось потратить» уже несёт только непогашенные строки от сегодня до конца срока
 * и просрочку. Остаётся решить три вещи:
 * - бронь — сумма и дата известны заранее, двигать её продукт не предлагает; просрочку
 *   отсекает тот же характер: в её резерве только брони;
 * - строка позже узкого дня его не поднимет;
 * - синтетику (взносы копилок, id нет) двигают через копилку, а не через строку плана;
 * - перевод в копилку — не ожидание, хоть форма и ставит ему «Ожидание» принудительно
 *   (ревью Codex #65).
 */
function isMovable(u: UpcomingItem, gapDate: string): boolean {
    return u.id != null
        && u.type === 'EXPENSE'
        && (u.priority === 'MEDIUM' || u.priority === 'LOW')
        && u.date <= gapDate;
}

/**
 * Разрыв после сдвига строки за конец срока: каждый день от её даты до конца срока богаче на
 * её сумму, новый разрыв — худший из дней. Хвост траектории за сроком кармашек не вычитает —
 * и здесь он не в счёт.
 */
function gapAfterMoving(p: PocketResponse, u: UpcomingItem): number {
    let min = Infinity;
    for (const pt of p.trajectory) {
        if (pt.date > p.horizon.endDate) break;
        const balance = pt.balance + (pt.date >= u.date ? u.amount : 0);
        if (balance < min) min = balance;
    }
    return min < 0 ? -min : 0;
}
