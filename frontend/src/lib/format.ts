/**
 * Единый формат рублей: целые (копейки размазки прогноза округляются),
 * без «−0 ₽» — значения в (−0.5, 0) нормализуются к нулю.
 */
export const fmtRub = (n: number): string => {
    const whole = Math.round(n);
    return new Intl.NumberFormat('ru-RU', { style: 'currency', currency: 'RUB', maximumFractionDigits: 0 })
        .format(whole === 0 ? 0 : whole);
};

/** «25 нояб.» из ISO-строки, тоже без UTC-парсинга, — короткая дата лица «Ж2». */
export const fmtDayShort = (iso: string): string => {
    const [y, m, d] = iso.split('-').map(Number);
    return new Date(y, m - 1, d).toLocaleDateString('ru-RU', { day: 'numeric', month: 'short' });
};

/** «14 июля» из ISO-строки БЕЗ UTC-парсинга (new Date('YYYY-MM-DD') сдвигает день в западных TZ). */
export const fmtDayMonth = (iso: string): string => {
    const [y, m, d] = iso.split('-').map(Number);
    return new Date(y, m - 1, d).toLocaleDateString('ru-RU', { day: 'numeric', month: 'long' });
};

/**
 * Сумма без округления вверх (срез 4, п. 8; ANO-84, п. 2): рубли, копейки — если есть, двумя знаками. 99 999,99 — не
 * «100 000 ₽». Группы — неразрывным пробелом: сумма не рвётся по строкам.
 */
export const fmtNum = (n: number): string => {
    const cents = Math.round(Math.abs(n) * 100) % 100 !== 0;
    return n.toLocaleString('ru-RU', { minimumFractionDigits: cents ? 2 : 0, maximumFractionDigits: 2 }).replace(/\s/g, ' ').replace('-', '−');
};

export const fmtAmount = (n: number): string => `${fmtNum(n)} ₽`;

/** «+12 400 ₽», «−320 000 ₽»; ноль — без знака. */
export const fmtSigned = (n: number): string => `${n > 0 ? '+' : n < 0 ? '−' : ''}${fmtAmount(Math.abs(n))}`;
