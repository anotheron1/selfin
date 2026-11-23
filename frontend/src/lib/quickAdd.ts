import type {
    Account, Category, FactPreview, FinancialEvent, Priority, RecurringConfig, RecurringFrequency, TargetFund,
} from '../types/api';
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
/** Копилка, на которую «Свободно» само держит взносы (PocketInputAssembler, п. 2а): зафиксированная цель-накопление с датой и суммой. */
export const fundReserved = (f: TargetFund | undefined) =>
    f != null && f.wishlistStatus === 'FIXED' && f.purchaseType === 'SAVINGS' && f.targetDate != null && f.targetAmount != null;

/**
 * Выбранный чип. Заглушка «Накопления» (SAVINGS_ID — форма открыта «Отложить сюда» с листа карты) — своя категория человека,
 * если она есть: иначе ни один чип не выбран и «в копилку» не показано (найдено на стенде PR 4 среза 4).
 */
export const chipOf = (all: CategoryChip[], id: string | undefined): CategoryChip | undefined =>
    all.find(c => c.id === id) ?? (id === SAVINGS_ID ? all.find(c => c.transfer) : undefined);

export function categoryChips(categories: Category[], type: 'EXPENSE' | 'INCOME'): { top: CategoryChip[]; rest: CategoryChip[] } {
    const chips: CategoryChip[] = categories.filter(c => c.type === type)
        .map(c => ({ id: c.id, name: c.name, transfer: c.systemKind === 'FUND_TRANSFER' }));
    if (type === 'EXPENSE' && !chips.some(c => c.transfer)) chips.push({ id: SAVINGS_ID, name: SAVINGS_NAME, transfer: true });
    return {
        top: chips.slice(0, TOP_CATEGORIES),
        rest: chips.slice(TOP_CATEGORIES).sort((a, b) => a.name.localeCompare(b.name, 'ru')),
    };
}

/** Сдвиг остатка: с какого дня и на сколько. */
export type Shift = { from: string; by: number };

/**
 * «Свободно» — самый низкий остаток до конца расчёта: изменение сдвигает остаток со своей даты, а «Свободно» — на столько,
 * на сколько сдвинулся минимум (ревью Codex #235: трата или возврат после минимума его не трогают). Нет траектории —
 * сумма сдвигов, как если бы минимум был в конце.
 */
export function trajectoryShift(trajectory: { date: string; balance: number }[] | undefined, horizonEnd: string | undefined,
                                shifts: Shift[]): number {
    const points = (trajectory ?? []).filter(t => horizonEnd == null || t.date <= horizonEnd);
    if (points.length === 0) return shifts.reduce((s, x) => s + x.by, 0);
    const at = (t: { date: string; balance: number }) => t.balance + shifts.filter(s => s.from <= t.date).reduce((s, x) => s + x.by, 0);
    return Math.min(...points.map(at)) - Math.min(...points.map(t => t.balance));
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
    /** Траектория «Свободно» (`/pocket`): по ней — сдвиг минимума. Нет — сумма сдвигов, как если бы минимум был в конце. */
    trajectory?: { date: string; balance: number }[];
    /** План с повтором: серия заводится целиком, и в расчёт могут войти несколько платежей. */
    repeat?: boolean;
    /** Копилка, на которую движок сам держит взносы: перевод их уменьшит, и «Свободно» изменится не на сумму. */
    fundReserved?: boolean;
}): FreeEffect | null {
    const a = p.amount;
    if (a == null || a <= 0) return null;
    const inRange = (d: string) => p.horizon == null || d <= p.horizon.endDate;
    const minShift = (shifts: Shift[]) => trajectoryShift(p.trajectory, p.horizon?.endDate, shifts);
    // ponytail: у копилки с взносами строки нет — перевод пересчитывает взносы (ревью Codex #235); посчитать — когда понадобится
    if (p.action === 'transfer' && p.fundReserved) return null;
    if (p.action === 'transfer') return { lead: `В копилку «${p.fundName ?? ''}»`, delta: minShift([{ from: p.today, by: -a }]) };
    if (p.action === 'plan') {
        if (p.type === 'INCOME') return null;
        const lead = `${PRIORITY_DOT_CONFIG[p.kind].name} на ${fmtDayShort(p.date)}`;
        if (!inRange(p.date)) return { lead, delta: 0, tail: `сейчас не изменится: оно считается ${p.horizon!.label}` };
        // ponytail: у серии строки нет — платежей до конца расчёта может быть несколько (ревью Codex #235); посчитать — когда понадобится
        if (p.repeat) return null;
        const delta = minShift([{ from: p.date, by: -a }]);
        return delta < 0 ? { lead, delta, tail: `меньше уже сейчас, на ${fmtRub(-delta)}` } : { lead, delta };
    }
    const sign = p.type === 'INCOME' ? 1 : -1;
    if (p.type === 'INCOME' && p.line) return null;
    const before = p.checkpointDate != null && p.date < p.checkpointDate;
    const held = p.line != null && p.line.date >= p.today && inRange(p.line.date);
    const rest = held ? Math.max(0, p.line!.plannedAmount - p.line!.recorded) : 0;
    const delta = minShift([
        ...(before ? [] : [{ from: p.today, by: sign * a }]),
        ...(rest > 0 ? [{ from: p.line!.date, by: -sign * Math.min(a, rest) }] : []),
    ]);
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
 * Копилки, в которые перевод возможен, и сколько не показано, потому что они лежат на основной карте.
 *
 * Достигнутые копилки прячутся, как и прежде. Копилка на своём счёте перевод принимает с среза 4
 * (PR 2): основная меньше, её счёт больше — так работает «Отложить сюда» с карты. Копилка на основной
 * перевода не принимает (сервер — 400): деньги и так на ней; её правила — НЗ, PR 6.
 */
export function transferFundChoice(funds: TargetFund[], mainId: string | undefined): { choices: TargetFund[]; onMain: number } {
    // НЗ на основной — уровень, а не отложенные деньги: перевода не принимает (сервер — 400; срез 4, п. 7).
    const open = funds.filter(f => f.status !== 'REACHED' && !(f.reserve && f.accountId == null));
    const onMain = (f: TargetFund) => mainId != null && f.accountId === mainId;
    return { choices: open.filter(f => !onMain(f)), onMain: open.filter(onMain).length };
}

/** Строка под списком копилок — почему копилки на основной в нём нет. */
export const ON_MAIN_NOTE = 'Копилок на основной карте здесь нет: деньги в них и так на ней.';

// ── «В кредит» (срез 4, пп. 17–18; доски S4-K1, S4-K2) ────────────────────────────────────────────────────────────

/**
 * Галочка «В кредит» — у расхода без плана (п. 1): у новой записи — пока есть «Записать» (у плана отметки нет), у
 * правки — у записи расхода, которая не встала к плану. Сервер в остальных случаях отвечает 400.
 */
export function creditOffered(p: {
    type: 'EXPENSE' | 'INCOME'; transfer: boolean; buttons: FormAction[]; edit?: FinancialEvent;
}): boolean {
    if (p.edit) return p.edit.eventKind === 'FACT' && p.edit.type === 'EXPENSE' && !p.edit.parentEventId;
    return p.type === 'EXPENSE' && !p.transfer && p.buttons.includes('record');
}

/** Сумма больше, чем на картах (то же число, что в плитке «на картах»), а отметки нет — форма спрашивает. */
export const creditAsks = (amount: number | null, cards: number | null, onCredit: boolean): boolean =>
    !onCredit && amount != null && cards != null && amount > cards;

export const CREDIT_EFFECT = 'В кредит: «Свободно» не изменится. Долг будет виден на кредитке, когда обновишь её остаток.';
export const CREDIT_ASK = 'На картах столько нет. Это в кредит?';
export const CREDIT_OVER_NOTE = 'Больше, чем на картах, — отметка стоит';

/** Строка после записи в кредит (S4-K2); рядом — «Обновить кредитку» и «Отменить». */
export const creditWritten = (name: string, amount: number): string =>
    `В кредит: ${name}, ${fmtRub(amount)}. «Свободно» не изменилось — обнови кредитку, чтобы видеть долг.`;

/**
 * «Накопление» в копилку на своём счёте (п. 20, S4-G4): деньги уходят с основной на счёт копилки. На накопительном —
 * словами доски, на другой карте с остатком — её именем.
 */
export function accountFundLine(fundName: string, account: Pick<Account, 'name' | 'kind'>, amount: number): string {
    const where = account.kind === 'DEPOSIT' ? 'на накопительном' : `на «${account.name}»`;
    const lying = account.kind === 'DEPOSIT' ? where : `на карте «${account.name}»`;
    return `«${fundName}» ${lying}: на картах станет на ${fmtRub(amount)} меньше, ${where} — на ${fmtRub(amount)} больше.`;
}
