/**
 * Единый формат рублей: целые (копейки размазки прогноза округляются),
 * без «−0 ₽» — значения в (−0.5, 0) нормализуются к нулю.
 */
export const fmtRub = (n: number): string => {
    const whole = Math.round(n);
    return new Intl.NumberFormat('ru-RU', { style: 'currency', currency: 'RUB', maximumFractionDigits: 0 })
        .format(whole === 0 ? 0 : whole);
};

/** «14 июля» из ISO-строки БЕЗ UTC-парсинга (new Date('YYYY-MM-DD') сдвигает день в западных TZ). */
export const fmtDayMonth = (iso: string): string => {
    const [y, m, d] = iso.split('-').map(Number);
    return new Date(y, m - 1, d).toLocaleDateString('ru-RU', { day: 'numeric', month: 'long' });
};
