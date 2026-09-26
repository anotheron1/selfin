import type { PocketResponse, UpcomingItem } from '../types/api';
import { fmtDayMonth, fmtRub as fmtC } from './format';
import { FREE_LABEL, pocketState } from './gapMode';
import { ruPlural } from './plural';

/**
 * Расшифровка «почему столько» строками, которые читаются вслух (ANO-77, вариант В владельца
 * 25.09): слева подпись словами пользователя, справа сумма, под строкой серым — пояснение.
 *
 * Проверка A4 (5.09) провалилась на формуле: «чекпоинт» и «движение» из кода, скобки в каждой
 * строке, «Кармашек» читался как ещё одна статья, оговорки мешались с расчётом. Здесь строки
 * до итога объясняют число, итог назван по состоянию карточки, оговорки — отдельным блоком.
 *
 * Подписи собирает фронт, а не бэк: итог зависит от режима карточки, а он живёт здесь
 * (gapMode.ts). Подпись бэка — запасная, для типа строки, которого фронт ещё не знает.
 *
 * Спека: docs/superpowers/specs/2026-09-25-breakdown-readable-design.md
 */

export type BreakdownRowKind = 'item' | 'subtotal' | 'result' | 'caveat';

export interface BreakdownRow {
    kind: BreakdownRowKind;
    label: string;
    amount: string;
    note: string | null;
}

export interface BreakdownView {
    /** Из чего число — строки до итога, в порядке движка. */
    calc: BreakdownRow[];
    result: BreakdownRow;
    /** Кроме этого — в число не входит. */
    caveats: BreakdownRow[];
}

const NAMES_LIMIT = 4;

/** Своё имя строки, если оно не пустое, иначе категория (ANO-101: пустое описание — не имя). */
const nameOf = (u: UpcomingItem): string | null => u.description?.trim() || u.categoryName?.trim() || null;

/** «Ипотека, Продукты ×4, Стрижка»: одинаковые вместе, больше четырёх разных — «и ещё N». */
function listNames(names: string[]): string {
    const groups = new Map<string, number>();
    for (const n of names) groups.set(n, (groups.get(n) ?? 0) + 1);
    const entries = [...groups.entries()];
    const shown = entries.slice(0, NAMES_LIMIT).map(([n, count]) => (count > 1 ? `${n} ×${count}` : n));
    const rest = entries.slice(NAMES_LIMIT).reduce((sum, [, count]) => sum + count, 0);
    return rest > 0 ? `${shown.join(', ')} и ещё ${rest}` : shown.join(', ');
}

const payments = (n: number) => `${n} ${ruPlural(n, ['платёж', 'платежа', 'платежей'])}`;

/** Слагаемое со знаком: доход — с плюсом, чтобы его не прочли вычетом. */
const signed = (n: number) => (n > 0 ? `+${fmtC(n)}` : fmtC(n));

const row = (kind: BreakdownRowKind, label: string, amount: string, note: string | null): BreakdownRow =>
    ({ kind, label, amount, note });

export function buildBreakdownView(p: PocketResponse): BreakdownView {
    const { minPoint, buffer, checkpointDate } = p;
    const isToday = minPoint.date === p.trajectory[0]?.date;
    const day = fmtDayMonth(minPoint.date);
    const when = isToday ? 'сегодня' : day;
    const state = pocketState(p);
    // «Самый низкий остаток» и НЗ стоят в расчёте, только когда НЗ задан и нехватки нет. Без НЗ
    // самый низкий остаток и есть итог — отдельной строкой он повторил бы то же число.
    const nzInCalc = buffer > 0 && state !== 'gap';

    const calc: BreakdownRow[] = [];
    const caveats: BreakdownRow[] = [];
    let afterPocket = false;

    for (const l of p.breakdown) {
        switch (l.type) {
            case 'STARTING_BALANCE':
                calc.push(row('item', 'На счёте', fmtC(l.amount), checkpointDate
                    ? `по сверке ${fmtDayMonth(checkpointDate)} и тому, что записано после`
                    : 'по записанным операциям — сверки ещё не было'));
                break;
            case 'OVERDUE_RESERVE': {
                // Движок строит список «осталось потратить» из того же набора, что и эту сумму,
                // а сервис подставляет категории — своих имён у броней часто нет.
                const overdue = p.upcoming.filter(u => u.overdue);
                const names = overdue.map(nameOf).filter((n): n is string => n != null);
                const head = `${payments(overdue.length)} ещё без факта`;
                calc.push(row('item', 'Брони с прошедшей датой', signed(l.amount),
                    names.length ? `${head}: ${listNames(names)}` : head));
                break;
            }
            case 'PLANNED_EXPENSES': {
                // Те строки, что движок сложил в эту сумму: впереди, до узкого дня, без синтетики —
                // у взносов копилок своя строка.
                const names = p.upcoming
                    .filter(u => !u.overdue && u.id != null && u.date <= minPoint.date)
                    .map(nameOf).filter((n): n is string => n != null);
                calc.push(row('item', isToday ? 'Ещё уйдёт сегодня' : `Ещё уйдёт до ${day}`, signed(l.amount),
                    names.length ? `по плану: ${listNames(names)}` : 'по плану'));
                break;
            }
            case 'SAVINGS_CONTRIBUTIONS': {
                const names = l.details.filter(d => d.trim());
                calc.push(row('item', 'Взносы в копилки', signed(l.amount), names.length ? listNames(names) : null));
                break;
            }
            case 'PLANNED_INCOME':
                calc.push(row('item', `Придёт до ${day}`, signed(l.amount), 'по плану'));
                break;
            case 'TRAJECTORY_MIN':
                if (nzInCalc) {
                    calc.push(row('subtotal', `Самый низкий остаток — ${when}`, fmtC(l.amount),
                        `столько останется ${when}, дальше по плану не ниже`));
                }
                break;
            case 'BUFFER':
                if (nzInCalc) {
                    calc.push(row('item', 'НЗ', signed(l.amount), 'не трогаем'));
                } else if (state === 'gap') {
                    // Нехватка считается от нуля (правило карточки); НЗ сверху — оговорка.
                    caveats.push(row('caveat', 'НЗ', fmtC(l.amount),
                        `при нехватке счёт от нуля; вместе с НЗ не хватает ${fmtC(-p.pocket)}`));
                }
                break;
            case 'POCKET':
                afterPocket = true;
                break;
            case 'UNPLANNED_FORECAST': {
                const until = p.minPointWithForecast ? ` до ${fmtDayMonth(p.minPointWithForecast.date)}` : '';
                const names = l.details.filter(d => d.trim());
                caveats.push(row('caveat', 'С обычными тратами', fmtC(l.amount),
                    `так обычно уходит${until}${names.length ? `: ${listNames(names)}` : ''}`));
                break;
            }
            case 'OVERDUE_RELEASED': {
                // Категорий у этих строк движок не видит: без своего имени — «ещё N платежей».
                const named = l.details.filter(d => d.trim());
                const unnamed = l.details.length - named.length;
                const list = named.length === 0 ? payments(unnamed)
                    : unnamed > 0 ? `${listNames(named)} и ещё ${payments(unnamed)}` : listNames(named);
                caveats.push(row('caveat', 'Сверка сняла с брони', fmtC(l.amount),
                    checkpointDate ? `${fmtDayMonth(checkpointDate)}: ${list}` : list));
                break;
            }
            case 'CREDIT_RESTORE':
                caveats.push(row('caveat', 'Погасить карты до планки', fmtC(l.amount),
                    'столько внести на карты, чтобы доступное дошло до планки из «Счетов»'));
                break;
            case 'WISHLIST_INFO':
                caveats.push(row('caveat', 'Хотелки', fmtC(l.amount), 'не входят, пока не зафиксированы с датой'));
                break;
            default: {
                // Незнакомый тип не пропадает: подпись и имена от бэка, место — по итогу.
                const names = l.details.filter(d => d.trim());
                const note = names.length ? names.join(', ') : null;
                if (afterPocket) caveats.push(row('caveat', l.label, fmtC(l.amount), note));
                else calc.push(row('item', l.label, signed(l.amount), note));
            }
        }
    }

    // ANO-99: слово справки (ANO-124), а не наше «самый узкий день».
    const narrowest = `самый низкий остаток — ${isToday ? `сегодня, ${day}` : day}`;
    const result = state === 'gap'
        ? row('result', 'Не хватает', fmtC(-minPoint.balance), narrowest)
        : state === 'nz'
            ? row('result', 'Придётся взять из НЗ', fmtC(buffer - minPoint.balance), null)
            : row('result', FREE_LABEL, fmtC(p.pocket), buffer > 0 ? null : narrowest);

    return { calc, result, caveats };
}
