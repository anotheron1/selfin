import { updateHintsSettings } from '../api';
import type { HintsSettings, PocketResponse } from '../types/api';
import { listNames, plannedNames } from './breakdownRows';
import { fmtDayShort } from './format';
import { pocketState } from './gapMode';
import { noExpectationsNote } from './noExpectationsNote';

/**
 * Подсказки первого дня на «Сегодня» (срез 2, п. 12; доски S2-P1, S2-P-1440; ANO-59, ANO-252). Видны, пока человек
 * их не закрыл крестиком или не выключил все — и новому, и тому, кто уже пользуется. Закрытые и выключатель — на
 * сервере, ключ «hints»: телефон и компьютер видят одно.
 *
 * Тексты — абзацы справки «Как считаются свободные деньги», вставшие на место (спека среза 2, «Справка → подача»).
 */

export type HintId = 'number' | 'slope' | 'plus';

/** Сверху вниз по экрану: «Скрыть все подсказки» стоит у первой видимой. */
const ORDER: HintId[] = ['number', 'slope', 'plus'];

export const SLOPE_HINT = 'Склон — сколько останется по дням. Нажми на день — что в нём.';
export const PLUS_HINT = 'Купил — запиши здесь. Можно не всё: число держится на плане и остатке.';

/** «Столько можно тратить до 25 нояб. Аренда и Связь уже вычтены.» — строки, что движок сложил в «Ещё уйдёт». */
export function numberHint(p: PocketResponse): string {
    // «25 нояб.» уже с точкой, «15 мая» — без: точка в конце одна.
    const head = `Столько можно тратить до ${fmtDayShort(p.horizon.endDate)}`.replace(/\.?$/, '.');
    const names = plannedNames(p);
    if (names.length === 0) return head;
    // Род имени, которое задал человек, неизвестен: одна строка — без согласования.
    if (names.length === 1) return `${head} Уже вычтено: ${names[0]}.`;
    const list = listNames(names);
    return `${head} ${list.includes(' и ещё ') ? list : list.replace(/, ([^,]*)$/, ' и $1')} уже вычтены.`;
}

/**
 * Что видно на «Сегодня» в первый день. У числа — только когда оно «Свободно»: при нехватке и НЗ над склоном другое
 * число. У склона — только на пейзаже. Плашка «Обычных трат в плане нет» от настроек не зависит (ANO-252): это ответ
 * о числе, а не помощь. Настройки не пришли — подсказок нет.
 */
export function firstDay(p: PocketResponse, s: HintsSettings | null, view: 'land' | 'cells'): { hints: HintId[]; noUsual: boolean } {
    const offered = ORDER.filter(id => id === 'number' ? pocketState(p) === 'ok' : id === 'slope' ? view === 'land' : true);
    return {
        hints: s == null || s.off ? [] : offered.filter(id => !s.closed.includes(id)),
        noUsual: noExpectationsNote(p) != null,
    };
}

export const closeHint = (s: HintsSettings, id: HintId): HintsSettings =>
    s.closed.includes(id) ? s : { ...s, closed: [...s.closed, id] };

export const hideAllHints = (s: HintsSettings): HintsSettings => ({ ...s, off: true });

/** Выключатель «Подсказки» в «Настройках»: включил — закрытые показываются снова, выключил — закрытые помнятся. */
export const switchHints = (s: HintsSettings, on: boolean): HintsSettings => (on ? { off: false, closed: [] } : { ...s, off: true });

let saving: Promise<void> = Promise.resolve();

/** Записи — по очереди: ответ на раннюю не перетрёт позднюю. Не записалось — на экране уже скрыто, вернётся при входе. */
export function saveHints(s: HintsSettings): Promise<void> {
    saving = saving.then(() => updateHintsSettings(s)).then(() => undefined, err => { console.error(err); });
    return saving;
}
