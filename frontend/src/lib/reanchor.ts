import { fmtDayMonth } from './format';
import { todayIso } from './factDate';

/**
 * Хелперы жеста ре-якоря (ANO-15 §3): возраст якоря и строка о перерыве. Лист — UpdateBalancesSheet.
 * Чистые функции — вся логика шторки и напоминалки тестируется здесь.
 */

const MS_PER_DAY = 24 * 60 * 60 * 1000;
const STALE_AFTER_DAYS = 30;
const BREAK_AFTER_DAYS = 7;

const parseIso = (iso: string) => {
    const [y, m, d] = iso.split('-').map(Number);
    return new Date(y, m - 1, d);
};

/** Полных дней между датой якоря и сегодня; null — якоря не было. */
export function checkpointAgeDays(checkpointDate: string | null, todayIso: string): number | null {
    if (!checkpointDate) return null;
    return Math.max(0, Math.round(
        (parseIso(todayIso).getTime() - parseIso(checkpointDate).getTime()) / MS_PER_DAY));
}

/**
 * Строка напоминалки в hero: null — якорь свежий, ничего не показываем;
 * «якорь ещё не задан» — онбординг-подсказка при отсутствии якоря.
 */
export function buildAgeHint(checkpointDate: string | null, todayIso: string): string | null {
    const age = checkpointAgeDays(checkpointDate, todayIso);
    if (age == null) return 'остаток ещё не якорился — тапни, чтобы задать';
    if (age <= STALE_AFTER_DAYS) return null;
    return `остаток обновлялся ${age} дн. назад`;
}

/** ISO-дата, сдвинутая на {@code days} дней, — местной арифметикой, без UTC. */
const shiftIso = (iso: string, days: number) => {
    const d = parseIso(iso);
    d.setDate(d.getDate() + days);
    return todayIso(d);
};

/**
 * Строка о перерыве (ANO-102, вариант А владельца): прошлый визит больше недели назад. За перерыв продукт о тратах
 * не знает, и одно число в шторке остатка проще разбора пропущенного. Без упрёка и без счётчика дней (правило 12).
 *
 * Дни — между прошлым визитом и сегодня: в них записей нет по определению, и запись сегодня строку неправдой не
 * делает (ревью Codex на #187). Сегодня — по календарю сервера: дни визита ставит он. Остаток сверен после прошлого
 * визита — строки нет: предложение исполнено, и это сравнение двух дат от пояса не зависит. Первый визит — строки нет.
 *
 * @param serverToday сегодня по календарю сервера — {@code serverTodayIso}
 */
export function buildBreakHint(lastVisit: string | null, checkpointDate: string | null, serverToday: string): string | null {
    if (!lastVisit || (checkpointDate != null && checkpointDate > lastVisit)) return null;
    const gap = Math.round((parseIso(serverToday).getTime() - parseIso(lastVisit).getTime()) / MS_PER_DAY);
    if (gap <= BREAK_AFTER_DAYS) return null;
    const from = shiftIso(lastVisit, 1);
    const to = shiftIso(serverToday, -1);
    const fromText = from.slice(0, 7) === to.slice(0, 7) ? String(parseIso(from).getDate()) : fmtDayMonth(from);
    return `С ${fromText} по ${fmtDayMonth(to)} записей нет — обновить остаток?`;
}
