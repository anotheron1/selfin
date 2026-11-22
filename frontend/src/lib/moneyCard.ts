import type {
    Account, AccountCreateDto, AccountKind, AccountPurpose, AccountUpdateDto, BalanceCheckpoint, CapitalItem, Category,
} from '../types/api';
import { amountValue } from '../components/ui/amount-input';
import { DELETE_FAILED, writeFailure } from './writeFailure';
import { fmtAmount, fmtDayYear } from './format';

/**
 * Лист карты и «Добавить карту» (срез 4, пп. 12–14; доски S4-C2–S4-C5, S4-A1, S4-A3–S4-A5): правила и слова. Вид —
 * components/money/CardSheet.tsx и AddCardSheet.tsx. Заменили «Счета»: AccountCard, AccountForm, accountsCopy.
 */

/** Слова листов, кроме ответов. Кредитный лимит — свойство карты от банка, а не потолок трат (правило 1). */
export const WORDS = {
    limit: 'Лимит',
    floor: 'Возвращаю до',
    floorNote: '«Возвращаю до» — сколько доступного хочешь держать. Не задано — строки «если вернуть на кредитку» на «Сегодня» нет.',
    floorAddNote: 'Сколько доступного хочешь держать. Не знаешь — оставь пустым: тогда строки «если вернуть на кредитку» нет.',
    floorAboveLimit: '«Возвращаю до» — уровень доступного, а доступного больше лимита не бывает: не выше лимита.',
    kindAcrossCredit: 'Не записалось: у кредитки остаток — это доступное, у других карт — деньги на карте. '
        + 'Записанные остатки поменяли бы смысл, поэтому заведи новую карту.',
    deleteHoldsGoal: 'Не удалилось: на счёте лежит копилка — сначала перенеси её на другой счёт',
    updateFirst: 'Сначала обнови остатки — иначе смена основной сдвинула бы «Свободно».',
    mainNoDelete: 'Основную не удалить: сначала сделай основной другую карту «на всё».',
    deleteAsk: 'Удалить карту? Обновления остатка сохранятся, карта уйдёт из всех сумм.',
    wrongGroup: 'Ошибся группой — «Для чего эта карта» можно сменить в листе карты.',
    creditAvailable: (limit: string) => `доступно из ${limit}`,
    /** @param date «сегодня» или «12 нояб.» — точка сокращения не удваивается */
    creditSince: (date: string, debt: string) => `Обновлена ${date}${date.endsWith('.') ? '' : '.'} Долг — ${debt} до лимита. `
        + 'Записанное «в кредит» эту карту само не двигает — обновляй, когда заглядываешь в банк.',
};

export type CardView = 'main' | 'together' | 'credit' | 'savings' | 'spending';

/** Вид листа: основная, вторая «на всё» (п. 10), кредитка, накопительная, для отдельных трат. */
export function cardView(a: Pick<Account, 'isDefault' | 'kind' | 'trackBalance'>): CardView {
    if (a.isDefault) return 'main';
    if (a.kind === 'CREDIT') return 'credit';
    if (a.kind === 'DEPOSIT') return 'savings';
    return a.trackBalance ? 'together' : 'spending';
}

/** Ответы на «Для чего эта карта?» (S4-A1). «На всё» и «основной» среди них нет (пп. 4, 10). */
export const ANSWERS: { value: AccountPurpose; label: string; hint: string }[] = [
    { value: 'SPEND', label: 'Для отдельных трат', hint: 'бензин, продукты: одна запись вместо десяти' },
    { value: 'SAVINGS', label: 'Накопительная', hint: 'вклад или накопительный счёт: здесь лежит копилка, в «Свободно» не входит' },
    { value: 'CREDIT', label: 'Кредитка', hint: 'не деньги, а долг' },
];

const PURPOSE: Partial<Record<CardView, AccountPurpose>> = { spending: 'SPEND', savings: 'SAVINGS', credit: 'CREDIT' };

/** Ответ карты; у основной и второй «на всё» его нет. */
export const purposeOf = (a: Account): AccountPurpose | null => PURPOSE[cardView(a)] ?? null;

/** Строка «Для чего эта карта» в листе. */
export function answerLabel(a: Account): string {
    const v = cardView(a);
    if (v === 'main') return 'Основная';
    if (v === 'together') return 'На всё, вместе с основной';
    return ANSWERS.find(x => x.value === PURPOSE[v])!.label;
}

/** «Сделать основной» — у карты «на всё» с обновлением (п. 12): записи иначе пошли бы на остаток, которого не вводили. */
export const canMakeMain = (a: Account): boolean => cardView(a) === 'together' && a.balanceDate != null;

/** Расходные категории для «Для каких трат» — без «Накопления»: это перевод в копилку, а не трата. */
export const spendCategories = (categories: Category[]): Category[] =>
    categories.filter(c => c.type === 'EXPENSE' && c.systemKind !== 'FUND_TRANSFER');

// ---------- Запросы ----------

export interface CardFormValues {
    name: string;
    purpose: AccountPurpose | null;
    categoryId: string | null;
    limit: string;
    floor: string;
}

const num = (raw: string): number | null => (raw.trim() === '' ? null : amountValue(raw));

/** Заведение — ответом (п. 4): что ответ не берёт, не уходит. Остаток — первое обновление сегодня. */
export function createDto(f: CardFormValues & { purpose: AccountPurpose; balance: string }): AccountCreateDto {
    const dto: AccountCreateDto = { name: f.name.trim(), purpose: f.purpose };
    if (f.purpose === 'SPEND') return { ...dto, purposeCategoryId: f.categoryId };
    const [limit, floor, balance] = [num(f.limit), num(f.floor), num(f.balance)];
    if (f.purpose === 'CREDIT') {
        if (limit != null) dto.creditLimit = limit;
        if (floor != null) dto.availableFloor = floor;
    }
    if (balance != null) dto.balance = balance;
    return dto;
}

/**
 * Правка — по осям (`PUT /accounts`). Новый ответ раскладывается, как при заведении (`AccountService.byPurpose`), только
 * наличные остаются наличными. Без нового ответа оси прежние: у основной и второй «на всё» его и нет.
 */
export function updateDto(a: Account, f: CardFormValues): AccountUpdateDto {
    const axes: { kind: AccountKind; trackBalance: boolean } =
        f.purpose == null ? { kind: a.kind, trackBalance: a.trackBalance }
            : f.purpose === 'SPEND' ? { kind: a.kind === 'CASH' ? 'CASH' : 'DEBIT', trackBalance: false }
                : { kind: f.purpose === 'SAVINGS' ? 'DEPOSIT' : 'CREDIT', trackBalance: true };
    const credit = axes.kind === 'CREDIT';
    return {
        name: f.name.trim(), ...axes,
        purposeCategoryId: axes.trackBalance ? null : f.categoryId,
        creditLimit: credit ? num(f.limit) : null,
        availableFloor: credit ? num(f.floor) : null,
    };
}

/** Что лист знает сам и говорит до отправки (ANO-245): сервер ответил бы 400. */
export function cardFormProblem(f: Pick<CardFormValues, 'purpose' | 'limit' | 'floor'>): string | null {
    const [limit, floor] = [num(f.limit), num(f.floor)];
    return f.purpose === 'CREDIT' && limit != null && floor != null && floor > limit ? WORDS.floorAboveLimit : null;
}

const statusOf = (err: unknown): unknown => (err as { status?: unknown } | null | undefined)?.status;

/**
 * Отказ правки. Смену через кредитку при записанных остатках сервер отвергает 400 (`rejectCreditBoundaryChange`), а есть ли
 * остатки, лист наверняка не знает. Остальные 400 отсеяны до отправки — значит, 400 на таком переходе — он.
 */
export function cardWriteFailure(err: unknown, a: Pick<Account, 'kind'>, toKind: AccountKind): string {
    const crossed = (a.kind === 'CREDIT') !== (toKind === 'CREDIT');
    return crossed && statusOf(err) === 400 ? WORDS.kindAcrossCredit : writeFailure(err);
}

/** Удаление: 409 один — на счёте лежит копилка; основную лист не удаляет. */
export const deleteCardFailure = (err: unknown): string => statusOf(err) === 409 ? WORDS.deleteHoldsGoal : DELETE_FAILED;

/** «Сделать основной»: 400 — смена сдвинула бы «Свободно» или у прежней основной минус (решение 65). */
export const makeMainFailure = (err: unknown): string => statusOf(err) === 400 ? WORDS.updateFirst : writeFailure(err);

// ---------- История обновлений ----------

/** Обновления этой карты — по дате, в один день — по времени записи. */
export const cardHistory = (all: BalanceCheckpoint[], accountId: string): BalanceCheckpoint[] =>
    all.filter(c => c.accountId === accountId)
        .sort((x, y) => x.date.localeCompare(y.date) || x.createdAt.localeCompare(y.createdAt));

/** Разница строкой истории: «по записям» вместо «дрейфа» (концепция, раздел 3); рост вклада — проценты (решение 24). */
export function diffLine(c: BalanceCheckpoint, savings: boolean): string {
    if (c.computedBalance == null || c.drift == null) return 'первое обновление';
    if (c.drift === 0) return 'сошлось с записями';
    const more = c.drift > 0;
    return `в банке на ${fmtAmount(Math.abs(c.drift))} ${more ? 'больше' : 'меньше'}, чем по записям`
        + (more && savings ? ', — обычно это проценты' : '');
}

/** Нажатая точка: «По записям было 48 500 ₽: в банке на 300 ₽ больше.» */
export function pointNote(c: BalanceCheckpoint, savings: boolean): string {
    if (c.computedBalance == null || c.drift == null) return 'Первое обновление.';
    const was = `По записям было ${fmtAmount(c.computedBalance)}: `;
    if (c.drift === 0) return `${was}сошлось.`;
    const more = c.drift > 0;
    return `${was}в банке на ${fmtAmount(Math.abs(c.drift))} ${more ? 'больше' : 'меньше'}${more && savings ? ' — обычно это проценты' : ''}.`;
}

const dayOf = (iso: string) => Date.UTC(+iso.slice(0, 4), +iso.slice(5, 7) - 1, +iso.slice(8, 10)) / 864e5;

/**
 * История линией (S4-C2): точки — обновления из банка, пунктир — «по записям»: от точки до того, что насчитали записи к
 * следующей, и до сегодня.
 *
 * @param now сегодня и остаток по записям; null или не позже последней точки — пунктир кончается на ней
 */
export function historyChart(points: BalanceCheckpoint[], now: { date: string; value: number } | null, w: number, h: number) {
    const last = points[points.length - 1];
    const tail = now && last && now.date > last.date ? now : null;
    const days = [...points.map(p => dayOf(p.date)), ...(tail ? [dayOf(tail.date)] : [])];
    const values = [...points.flatMap(p => [p.amount, p.computedBalance ?? p.amount]), ...(tail ? [tail.value] : [])];
    const [d0, d1] = [Math.min(...days), Math.max(...days)];
    const [lo, hi] = [Math.min(...values), Math.max(...values)];
    const top = 8, bottom = h - 22;
    const x = (iso: string) => (d1 === d0 ? w / 2 : 6 + (dayOf(iso) - d0) / (d1 - d0) * (w - 12));
    const y = (v: number) => (hi === lo ? (top + bottom) / 2 : top + (hi - v) / (hi - lo) * (bottom - top));
    const r = (n: number) => Math.round(n * 10) / 10;
    const steps = points.flatMap((p, i) => i === 0 || p.computedBalance == null
        ? [[x(p.date), y(p.amount)]]
        : [[x(p.date), y(p.computedBalance)], [x(p.date), y(p.amount)]]);
    if (tail) steps.push([x(tail.date), y(tail.value)]);
    const path = steps.map(([px, py], i) => `${i === 0 ? 'M' : 'L'}${r(px)},${r(py)}`).join(' ');
    const end = tail?.date ?? last?.date ?? '';
    return {
        path,
        area: steps.length > 1 ? `${path} L${r(steps[steps.length - 1][0])},${bottom} L${r(steps[0][0])},${bottom} Z` : '',
        dots: points.map(p => ({ id: p.id, x: r(x(p.date)), y: r(y(p.amount)) })),
        labels: points.length === 0 ? [] : [
            { x: r(x(points[0].date)), text: fmtDayYear(points[0].date, end), anchor: 'start' as const },
            ...(end !== points[0].date ? [{ x: r(x(end)), text: fmtDayYear(end, end), anchor: 'end' as const }] : []),
        ],
        baseline: h - 6,
    };
}

// ---------- Строки листа ----------

/** Накопительная (S4-C4): «по записям» — обновление плюс переводы в копилку после него. */
export function savingsSince(last: BalanceCheckpoint, byRecords: number, today: string): string {
    const moved = Math.round((byRecords - last.amount) * 100) / 100;
    const after = moved > 0 ? `, после — отложено ${fmtAmount(moved)}` : moved < 0 ? `, после — снято ${fmtAmount(-moved)}` : '';
    return `Обновлён ${fmtDayYear(last.date, today)} на ${fmtAmount(last.amount)}${after}.`;
}

/** Кредитка в листе добавления (S4-A4): что будет со «Свободно». */
export function creditDebtLine(limitRaw: string, availableRaw: string): string | null {
    const [limit, available] = [num(limitRaw), num(availableRaw)];
    if (limit == null || available == null || limit - available <= 0) return null;
    return `Долг ${fmtAmount(limit - available)} в «Свободно» не входит — он виден на «Сегодня» строкой «если вернуть на кредитку».`;
}

// ---------- «Не та же ли это кредитка?» ----------

/**
 * Живые ручные долги — кандидаты в ту же кредитку (S4-A5, бывшее «Проверь Капитал»). Показываются все: запись, названная
 * иначе, чем карта, задваивает долг чаще всего; похожие по имени — первыми.
 */
export function creditCheckItems(items: CapitalItem[], cardName: string): CapitalItem[] {
    const tokens = (s: string) => new Set(s.toLowerCase().replace(/[^\p{L}\p{N}]+/gu, ' ').split(' ').filter(t => t.length >= 3));
    const card = tokens(cardName);
    const alike = (i: CapitalItem) => Number([...tokens(i.name)].some(t => card.has(t)));
    return items.filter(i => i.kind === 'LIABILITY' && !i.isArchived).sort((x, y) => alike(y) - alike(x));
}
