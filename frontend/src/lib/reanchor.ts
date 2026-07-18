import { fmtRub } from './format';

/**
 * Хелперы жеста ре-якоря (ANO-15 §3): возраст якоря и зеркало дрейфа.
 * Чистые функции — вся логика шторки и напоминалки тестируется здесь.
 */

const MS_PER_DAY = 24 * 60 * 60 * 1000;
const STALE_AFTER_DAYS = 30;

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

/** Подпись зеркала в шторке: у первого якоря «считает» вводило бы в заблуждение. */
export function buildMirrorLabel(hasAnchor: boolean, computed: number): string {
    return hasAnchor
        ? `selfin считает: ${fmtRub(computed)}`
        : `расчёт selfin по записанным фактам: ${fmtRub(computed)}`;
}

/**
 * Живая строка дрейфа под полем ввода; null — не показывать
 * (пустой ввод или первый якорь — сравнивать не с чем).
 */
export function buildDriftPreview(
    entered: number | null, computed: number, ageDays: number | null,
): string | null {
    if (entered == null || Number.isNaN(entered) || ageDays == null) return null;
    const drift = entered - computed;
    if (drift === 0) return 'дрейф 0 ₽ — расчёт сходится';
    const sign = drift > 0 ? '+' : '−';
    const days = ageDays === 0 ? 'за сегодня' : `за ${ageDays} дн.`;
    return `дрейф ${sign}${fmtRub(Math.abs(drift))} ${days}`;
}
