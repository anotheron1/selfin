/**
 * Единый формат рублей: целые (копейки размазки прогноза округляются),
 * без «−0 ₽» — значения в (−0.5, 0) нормализуются к нулю.
 */
export const fmtRub = (n: number): string => {
    const whole = Math.round(n);
    return new Intl.NumberFormat('ru-RU', { style: 'currency', currency: 'RUB', maximumFractionDigits: 0 })
        .format(whole === 0 ? 0 : whole);
};
