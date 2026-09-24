/** Русское число при слове: 1 строка, 2 строки, 5 строк, 11 строк, 21 строка. */
export function ruPlural(n: number, [one, few, many]: [string, string, string]): string {
    const tens = Math.abs(n) % 100;
    const units = tens % 10;
    if (tens >= 11 && tens <= 14) return many;
    if (units === 1) return one;
    if (units >= 2 && units <= 4) return few;
    return many;
}
