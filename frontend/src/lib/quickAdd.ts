import type { Category, FactPreview, Priority, RecurringConfig, RecurringFrequency, TargetFund } from '../types/api';
import { fmtDayShort, fmtRub } from './format';
import { PRIORITY_DOT_CONFIG } from './priority';

/**
 * Правила формы «+» (срез 3 п. 12, ANO-228; доски Zh2-F8a–c, S3-F1, S3-F4, S3-F8). Одна сумма; кнопки — по дате;
 * повтор — только у плана, число — из даты; вид — от категории; «Накопление» — перевод в копилку.
 */

/** Что делает кнопка: «Запланировать» — план, «Записать» («Отложить» у «Накопления») — запись. */
export type FormAction = 'plan' | 'record';

/**
 * Кнопки по дате: сегодня — «Запланировать» и «Записать» (главная — последняя), впереди — «Запланировать», прошлая —
 * «Записать». Повтор бывает только у плана: включён — кнопка одна, «Запланировать» (замечание 2.3); у прошлой даты
 * плана нет, и повтор не действует.
 */
export function formButtons(date: string, today: string, repeat: boolean): FormAction[] {
    if (date < today) return ['record'];
    if (repeat || date > today) return ['plan'];
    return ['plan', 'record'];
}

/** Повтор с числом из даты формы — берётся при отправке, поэтому смена даты меняет и число (ошибка Fab.tsx:51). */
export function repeatOf(frequency: RecurringFrequency, date: string, endDate: string | null): RecurringConfig {
    const [, m, d] = date.split('-').map(Number);
    return { frequency, dayOfMonth: d, monthOfYear: frequency === 'YEARLY' ? m : null, endDate, startDate: date };
}

/** «Каждый месяц, 19-го», «Каждый год, 19 нояб.» (S3-F8). */
export function repeatLabel(frequency: RecurringFrequency, date: string): string {
    return frequency === 'YEARLY'
        ? `Каждый год, ${fmtDayShort(date)}`
        : `Каждый месяц, ${Number(date.split('-')[2])}-го`;
}

/** Сохранять можно с суммой (ошибка quickAdd.ts:38: расход без суммы становился планом с «—») и категорией, у
 *  «Накопления» — с копилкой. */
export function canSubmitQuickAdd(p: { amount: number | null; categoryId?: string; transfer: boolean; fundId?: string }): boolean {
    if (p.amount == null || p.amount <= 0) return false;
    return p.transfer ? !!p.fundId : !!p.categoryId;
}

/** Вид по умолчанию — от категории: бронь, ожидание или хотелка. */
export const kindOf = (c: Category | undefined): Priority => c?.priority ?? 'MEDIUM';

/** Чип категории: у «Накопления» — перевод в копилку. */
export interface CategoryChip { id: string; name: string; transfer: boolean }

/** «Накопление», пока системной категории ещё нет: сервер заведёт её первым переводом (CategoryService.systemCategory). */
export const SAVINGS_ID = 'savings';
const SAVINGS_NAME = 'Накопление';

/** Сколько категорий на виду, остальные — за «ещё N ▾» (S3-F4). */
export const TOP_CATEGORIES = 6;

/**
 * Категории типа в порядке сервера (п. 14: частые первыми — сортирует сервер, форма не пересортировывает, ошибка
 * Fab.tsx:75): шесть первых на виду, остальные — по алфавиту. У расходов есть «Накопление» (ANO-259): системная
 * стоит на своём месте, а пока её нет — последней.
 */
export function categoryChips(categories: Category[], type: 'EXPENSE' | 'INCOME'): { top: CategoryChip[]; rest: CategoryChip[] } {
    const chips: CategoryChip[] = categories.filter(c => c.type === type)
        .map(c => ({ id: c.id, name: c.name, transfer: c.systemKind === 'FUND_TRANSFER' }));
    if (type === 'EXPENSE' && !chips.some(c => c.transfer)) chips.push({ id: SAVINGS_ID, name: SAVINGS_NAME, transfer: true });
    return {
        top: chips.slice(0, TOP_CATEGORIES),
        rest: chips.slice(TOP_CATEGORIES).sort((a, b) => a.name.localeCompare(b.name, 'ru')),
    };
}

/** Что будет со «Свободно»: с чего начать фразу, на сколько оно изменится и, если надо, своими словами — как. */
export interface FreeEffect { lead: string; delta: number; tail?: string }

/**
 * Что будет со «Свободно» после главной кнопки (п. 12, решение 43 — на пробу). Строку ожидания называет сервер
 * (`fact-preview`, правило привязки п. 1), здесь — только арифметика движка над ней:
 * * запись уменьшает остаток на свою сумму, если сделана после сверки; до сверки она уже в числе банка;
 * * встав к строке ожидания, запись снимает с неё удержание — на свою сумму, не больше остатка строки, — но только если
 *   строка ещё держится: не прошла (прошедшее ожидание не держится) и не позже конца расчёта «Свободно»;
 * * план держит свою сумму с сегодняшнего дня, если его дата не позже конца расчёта;
 * * перевод в копилку откладывает сумму.
 * У дохода к строке ответа нет: полученный по плану доход может сдвинуть сам расчёт до следующего дохода.
 */
export function freeEffect(p: {
    action: FormAction | 'transfer';
    type: 'EXPENSE' | 'INCOME';
    kind: Priority;
    amount: number | null;
    date: string;
    today: string;
    checkpointDate: string | null;
    horizon: { endDate: string; label: string } | null;
    line: FactPreview | null;
    fundName?: string;
}): FreeEffect | null {
    const a = p.amount;
    if (a == null || a <= 0) return null;
    const inRange = (d: string) => p.horizon == null || d <= p.horizon.endDate;
    if (p.action === 'transfer') return { lead: `В копилку «${p.fundName ?? ''}»`, delta: -a };
    if (p.action === 'plan') {
        if (p.type === 'INCOME') return null;
        const lead = `${PRIORITY_DOT_CONFIG[p.kind].name} на ${fmtDayShort(p.date)}`;
        return inRange(p.date)
            ? { lead, delta: -a, tail: `меньше уже сейчас, на ${fmtRub(a)}` }
            : { lead, delta: 0, tail: `сейчас не изменится: оно считается ${p.horizon!.label}` };
    }
    const sign = p.type === 'INCOME' ? 1 : -1;
    if (p.type === 'INCOME' && p.line) return null;
    const before = p.checkpointDate != null && p.date < p.checkpointDate;
    const held = p.line != null && p.line.date >= p.today && inRange(p.line.date);
    const rest = held ? Math.max(0, p.line!.plannedAmount - p.line!.recorded) : 0;
    const delta = (before ? 0 : sign * a) - sign * Math.min(a, rest);
    const lead = p.line ? `Трата встанет к ожиданию «${p.line.name}»`
        : before ? `Это было до сверки остатка ${fmtDayShort(p.checkpointDate!)}`
        : 'В плане этого не было';
    return { lead, delta };
}

const change = (delta: number) => (delta < 0 ? `меньше на ${fmtRub(-delta)}` : `больше на ${fmtRub(delta)}`);

/** Строка над кнопкой: «В плане этого не было — «Свободно» меньше на 1 200 ₽.» */
export function effectLine(e: FreeEffect | null): string | null {
    if (!e) return null;
    const line = `${e.lead} — «Свободно» ${e.tail ?? (e.delta === 0 ? 'не изменится' : change(e.delta))}`;
    // «до зарплаты 25 нояб.» уже кончается точкой сокращения
    return line.endsWith('.') ? line : `${line}.`;
}

/** Строка после записи (S3-F3): «Свободно» не изменилось / меньше на X. */
export function afterLine(e: FreeEffect | null): string | null {
    if (!e) return null;
    return e.delta === 0 ? '«Свободно» не изменилось.' : `«Свободно» ${change(e.delta)}.`;
}

/**
 * Копилки, в которые перевод возможен, и сколько не показано, потому что они лежат на счёте.
 *
 * Достигнутые копилки прячутся, как и прежде. Копилки на счёте перевода не принимают —
 * деньги в них двигаются на самом счёте (§3.3); раньше они стояли в списке, и план на них не
 * исполнялся никаким путём.
 */
export function transferFundChoice(funds: TargetFund[]): { choices: TargetFund[]; onAccount: number } {
    const open = funds.filter(f => f.status !== 'REACHED');
    return {
        choices: open.filter(f => !f.accountId),
        onAccount: open.filter(f => !!f.accountId).length,
    };
}

/** Строка под списком копилок — почему копилок на счёте в нём нет. */
export const ON_ACCOUNT_NOTE = 'Копилок на счёте здесь нет: деньги в них двигаются на самом счёте.';
