/**
 * Раскраска дельты план-факт — один источник правды (ANO-32).
 *
 * Прежде правило было написано в логике расхода («больше плана = плохо») и применялось
 * ко всем строкам без учёта типа: зарплата выше плановой подсвечивалась красным.
 * Для дохода знак смысла обратный, поэтому «хорошо/плохо» решается только вместе с типом.
 */

export type PlanFactType = 'INCOME' | 'EXPENSE';

/**
 * Дельта, приведённая «в пользу пользователя»: положительная — исход хороший.
 * На вход идёт сырая дельта в том виде, как её отдаёт бэк: {@code факт − план}.
 * Для дохода это уже нужный знак, для расхода — обратный (потратил меньше плана = хорошо).
 */
export function favourableFromDelta(type: PlanFactType, delta: number): number {
    return type === 'INCOME' ? delta : -delta;
}

/**
 * То же, но из пары план/факт. План отсутствует → 0 («сравнивать не с чем»),
 * чтобы строка без плана не красилась тревожным цветом.
 */
export function favourableDelta(
    type: PlanFactType,
    planned: number | null | undefined,
    fact: number | null | undefined,
): number {
    if (planned == null || fact == null) return 0;
    return favourableFromDelta(type, fact - planned);
}

/** Цвет дельты план-факт: зелёный, когда исход в пользу пользователя. */
export function deltaColor(favourable: number): string {
    return favourable >= 0 ? 'var(--color-success)' : 'var(--color-danger)';
}

/** Знак для подписи дельты. Минус обязателен: без него отрицательная дельта читается как прибавка. */
export function deltaSign(favourable: number): string {
    return favourable >= 0 ? '+' : '−';
}
