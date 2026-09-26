import type { PocketResponse, UpcomingItem } from '../types/api';
import { fmtDayMonth, fmtRub as fmtC } from './format';

/**
 * Режим нехватки у карточки кармашка (ANO-100 вместе с ANO-76, вариант Б владельца 24.09).
 *
 * Включается, когда деньги кончаются в пределах срока: минимум траектории ниже нуля.
 * Тот же режим — когда денег хватает, но план задевает НЗ (ANO-92, решение владельца 25.09):
 * минимум не ниже нуля, но ниже НЗ. Тогда всё считается от НЗ, а не от нуля.
 *
 * Отрицательное число под словом «В кармашке» посторонний читал как долг в прошлом:
 * «ушла в долг, потратила больше положенного» (провал A1). Поэтому здесь заголовок — фраза
 * с датой впереди и без минуса, а вместо «займи» карточка говорит, что можно сдвинуть.
 *
 * Спеки: docs/superpowers/specs/2026-09-24-gap-mode-design.md, 2026-09-25-nz-design.md
 */
export interface GapMode {
    horizonLabel: string;
    headline: string;
    /** Что будет с доходом; null, когда срок не заякорен доходом. */
    subline: string | null;
    moveHeader: string;
    /** До трёх строк «Кафе — 900 ₽ → не хватит 2 785 ₽» или «… → из НЗ 1 500 ₽», крупные первыми. */
    movable: string[];
}

const MOVABLE_LIMIT = 3;

const fmtD = (iso: string) => {
    const [, m, d] = iso.split('-');
    return `${d}.${m}`;
};

/**
 * Состояние кармашка — одно правило на карточку и расшифровку (ANO-77):
 * 'gap' — деньги кончаются в пределах срока; 'nz' — денег хватает, но план задевает НЗ;
 * 'ok' — ни того, ни другого. Нехватка денег важнее НЗ: при остатке ниже нуля счёт от нуля.
 */
export type PocketState = 'gap' | 'nz' | 'ok';

export function pocketState(p: PocketResponse): PocketState {
    if (p.minPoint.balance < 0) return 'gap';
    return p.minPoint.balance < p.buffer ? 'nz' : 'ok';
}

/**
 * Имя главного числа в обычном режиме (ANO-76) — одно на карточку, итог расшифровки и шапку
 * примерки. «Кармашек» в исследованиях писали только мы; «свободные деньги» знает каждый (правило 13).
 */
export const FREE_LABEL = 'Свободно';

/**
 * Итог числа словами — по состоянию (ANO-77): «Свободно», «Не хватает», «Придётся взять из НЗ»;
 * сумма всегда без минуса. Одно правило на итог расшифровки и шапку примерки: там над минусом
 * стояло «Свободно сейчас −25 612 ₽» — противоречие в одной строке (ревью Codex, #80).
 * Нехватка считается от нуля, НЗ — от НЗ, как у карточки.
 */
export function pocketResult(p: PocketResponse): { label: string; amount: number } {
    const state = pocketState(p);
    if (state === 'gap') return { label: 'Не хватает', amount: -p.minPoint.balance };
    if (state === 'nz') return { label: 'Придётся взять из НЗ', amount: p.buffer - p.minPoint.balance };
    return { label: FREE_LABEL, amount: p.pocket };
}

export function buildGapMode(p: PocketResponse): GapMode | null {
    const { minPoint, horizon, trajectory } = p;
    const state = pocketState(p);
    if (state === 'ok') return null;
    // Когда денег хватает, уровень — НЗ: всё, что ниже него, план берёт из неприкосновенного.
    const nz = state === 'nz';
    const level = nz ? p.buffer : 0;

    const shortfall = level - minPoint.balance;
    // «Сегодня» — день 0 траектории, то есть сегодня сервера, а не браузера: так же решает
    // фраза-ответ, и в разных часовых поясах они не разойдутся.
    const today = minPoint.date === trajectory[0]?.date;
    const headline = nz
        ? `${today ? 'Сегодня' : fmtDayMonth(minPoint.date)} по плану придётся взять из НЗ ${fmtC(shortfall)}`
        : today
            ? `Сегодня по плану не хватает ${fmtC(shortfall)}`
            : `${fmtDayMonth(minPoint.date)} по плану не хватит ${fmtC(shortfall)}`;

    const incomeAnchored =
        (horizon.type === 'NEXT_INCOME' || horizon.type === 'SECOND_INCOME') && !horizon.fallback;
    const endPoint = trajectory.find(pt => pt.date === horizon.endDate);
    const on = fmtD(horizon.endDate);
    const subline = !incomeAnchored || !endPoint ? null
        : endPoint.balance >= level
            ? `${nz ? `Доход ${on} восстановит НЗ` : `Доход ${on} закроет разрыв`}, останется ${fmtC(endPoint.balance)}.`
            : `${nz ? `Даже доход ${on} не восстановит НЗ` : `Даже доход ${on} не закроет разрыв`} — план требует правки.`;

    const movable = p.upcoming
        .filter(u => isMovable(u, minPoint.date))
        .sort((a, b) => b.amount - a.amount)
        .slice(0, MOVABLE_LIMIT)
        .map(u => {
            const after = shortfallAfterMoving(p, u, level);
            // Своё имя строки точнее категории («Стрижка», а не «Услуги людские»). Тире, а не
            // запятая: в названиях категорий запятые есть — «Кафе, рестики, фастфуд».
            const label = u.description ?? u.categoryName ?? '—';
            const outcome = after > 0
                ? (nz ? `из НЗ ${fmtC(after)}` : `не хватит ${fmtC(after)}`)
                : (nz ? 'НЗ цел' : 'разрыва не будет');
            return `${label} — ${fmtC(u.amount)} → ${outcome}`;
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
 * Сколько не хватит до уровня после сдвига строки за конец срока: каждый день от её даты до
 * конца срока богаче на её сумму, решает худший из дней. Уровень — ноль при нехватке и НЗ,
 * когда задет НЗ. Хвост траектории за сроком кармашек не вычитает — и здесь он не в счёт.
 */
function shortfallAfterMoving(p: PocketResponse, u: UpcomingItem, level: number): number {
    let min = Infinity;
    for (const pt of p.trajectory) {
        if (pt.date > p.horizon.endDate) break;
        const balance = pt.balance + (pt.date >= u.date ? u.amount : 0);
        if (balance < min) min = balance;
    }
    return min < level ? level - min : 0;
}
