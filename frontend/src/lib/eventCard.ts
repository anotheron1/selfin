import type {
    EventType, FinancialEvent, FinancialEventCreateDto, Priority, RecurringConfig, ScopeEnum, StandaloneFactCreateDto,
} from '../types/api';
import { fmtDayShort } from './format';
import { repeatLabel } from './quickAdd';

/**
 * Карточка записи и плана и правка той же формой (срез 3 пп. 2, 16–18; доски S3-E2a, S3-E2b, S3-E1a, S3-E1b, S3-W2):
 * серия целиком, дубль «Ещё раз сегодня», «Закончить серию», запросы правки.
 */

/** Как строка названа в журнале: перевод — копилкой, иначе название, выражение суммы или категория. */
export const eventName = (e: FinancialEvent): string =>
    e.type === 'FUND_TRANSFER' ? e.targetFundName ?? 'Копилка' : e.description || e.rawInput || e.categoryName || '';

const pad = (n: number) => String(n).padStart(2, '0');
const daysIn = (y: number, m: number) => new Date(y, m, 0).getDate();
const withYear = (iso: string) => `${fmtDayShort(iso)} ${iso.slice(0, 4)}`;

/** «сегодня, 12 нояб.», «вчера, 11 нояб.», «1 нояб.»; другой год — с годом. */
export function dayLabel(iso: string, today: string): string {
    const [y, m, d] = today.split('-').map(Number);
    const t = new Date(y, m - 1, d - 1);
    const yesterday = `${t.getFullYear()}-${pad(t.getMonth() + 1)}-${pad(t.getDate())}`;
    const day = iso.slice(0, 4) === today.slice(0, 4) ? fmtDayShort(iso) : withYear(iso);
    return iso === today ? `сегодня, ${day}` : iso === yesterday ? `вчера, ${day}` : day;
}

/**
 * Даты следующих платежей серии после этой строки — как их ставит сервер (`RecurringEventGenerator`): число правила,
 * в коротком месяце — последний день; не позже конца серии.
 */
export function nextDates(e: FinancialEvent, n = 3): string[] {
    if (!e.recurringRuleId || !e.date || e.recurringDayOfMonth == null) return [];
    const [y, m] = e.date.split('-').map(Number);
    const step = e.recurringFrequency === 'YEARLY' ? 12 : 1;
    const month0 = e.recurringFrequency === 'YEARLY' && e.recurringMonthOfYear != null ? e.recurringMonthOfYear - 1 : m - 1;
    const out: string[] = [];
    for (let i = 1; i <= n; i++) {
        const total = y * 12 + month0 + i * step;
        const yy = Math.floor(total / 12), mm = total % 12 + 1;
        const iso = `${yy}-${pad(mm)}-${pad(Math.min(e.recurringDayOfMonth, daysIn(yy, mm)))}`;
        if (e.recurringEndDate && iso > e.recurringEndDate) break;
        out.push(iso);
    }
    return out;
}

/** Серия в карточке плана (S3-E2b): «Каждый месяц, 12-го», «с 12 мар. 2026», «без конца», три даты дальше. */
export function seriesOf(e: FinancialEvent): { title: string; since: string; until: string; next: string[] } | null {
    if (!e.recurringRuleId || !e.recurringFrequency || e.recurringDayOfMonth == null) return null;
    // Подпись — по числу правила, а не строки: у 31-го в феврале строка стоит на 28-е.
    const ruleDay = `2000-${pad(e.recurringMonthOfYear ?? 1)}-${pad(e.recurringDayOfMonth)}`;
    return {
        title: repeatLabel(e.recurringFrequency, ruleDay),
        since: e.recurringStartDate ? `с ${withYear(e.recurringStartDate)}` : '',
        until: e.recurringEndDate ? `до ${withYear(e.recurringEndDate)}` : 'без конца',
        next: nextDates(e),
    };
}

/**
 * Категория не правится (п. 2): у записи к брони или хотелке — её держит план, у перевода в копилку — копилка.
 * Сервер в этих случаях отвечает 400; форма выбора не предлагает.
 */
export const categoryLocked = (e: FinancialEvent): boolean =>
    e.type === 'FUND_TRANSFER' || (e.eventKind === 'FACT' && e.parentEventId != null && e.parentPlanPriority !== 'MEDIUM');

/**
 * Что случилось с записью (S3-E2a) — по плану, к которому она встала. Про «Свободно» строка не говорит: изменилось
 * ли оно, зависит от даты строки и сверки остатка (стенд PR 3).
 */
export function factNote(e: FinancialEvent): string | null {
    if (e.eventKind !== 'FACT' || e.type === 'FUND_TRANSFER') return null;
    if (!e.parentEventId) return 'В плане этого не было.';
    const name = e.parentPlanDescription ?? '';
    if (e.parentPlanPriority === 'MEDIUM') return `Встала к ожиданию «${name}».`;
    if (e.parentPlanPriority === 'HIGH') return `По брони «${name}».`;
    if (e.parentPlanPriority === 'LOW') return `К хотелке «${name}».`;
    return `К плану «${name}».`;
}

/** «Ещё раз сегодня» (п. 17): поля образца сегодняшним числом. Связи с планом нет — к ожиданию запись встанет по п. 1. */
export const duplicateOf = (e: FinancialEvent, today: string): StandaloneFactCreateDto => ({
    date: today, categoryId: e.categoryId, type: e.type, factAmount: e.factAmount ?? 0,
    description: e.description ?? undefined, priority: e.priority, rawInput: e.rawInput ?? undefined,
});

/** Правка записи (п. 2): сумма, название, дата, категория, вид. Та же категория не шлётся — у записи к брони её нельзя. */
export const factEditPatch = (e: FinancialEvent, f: {
    amount: number; description: string; rawInput?: string; date: string; categoryId: string; kind: Priority;
}) => ({
    factAmount: f.amount, description: f.description || undefined, rawInput: f.rawInput, date: f.date,
    categoryId: f.categoryId !== e.categoryId ? f.categoryId : undefined, priority: f.kind,
});

/**
 * Правка плана (п. 16): PUT со своим scope. У серии, кроме «Только этот», уходит и повтор: число и месяц — из даты,
 * если её сменили, иначе прежние правила (строка 28 февр. серии 31-го серию на 28-е не переводит), конец — из формы.
 */
export function planEditDto(e: FinancialEvent, f: {
    date: string; categoryId: string; type: Exclude<EventType, 'FUND_TRANSFER'>; amount: number; kind: Priority;
    description: string; rawInput?: string; fundId?: string; endDate: string | null; scope: ScopeEnum;
}): FinancialEventCreateDto {
    const transfer = e.type === 'FUND_TRANSFER';
    const moved = f.date !== e.date;
    const [, m, d] = f.date.split('-').map(Number);
    const recurring: RecurringConfig | undefined = e.recurringRuleId && e.recurringFrequency && f.scope !== 'THIS' ? {
        frequency: e.recurringFrequency,
        dayOfMonth: moved ? d : e.recurringDayOfMonth ?? d,
        monthOfYear: e.recurringFrequency === 'YEARLY' ? (moved ? m : e.recurringMonthOfYear ?? m) : null,
        endDate: f.endDate,
    } : undefined;
    return {
        date: f.date, categoryId: f.categoryId, type: transfer ? 'FUND_TRANSFER' : f.type, plannedAmount: f.amount,
        priority: f.kind, mandatory: e.mandatory, description: f.description || undefined, rawInput: f.rawInput,
        targetFundId: transfer ? f.fundId : undefined, recurring,
    };
}

/** «Закончить серию» (п. 18): этот платёж последний — конец серии на его дату, остальное как было. Шлётся с FOLLOWING. */
export const endSeriesDto = (e: FinancialEvent): FinancialEventCreateDto => ({
    date: e.date!, categoryId: e.categoryId, type: e.type, plannedAmount: e.plannedAmount ?? undefined, priority: e.priority,
    mandatory: e.mandatory, description: e.description ?? undefined, rawInput: e.rawInput ?? undefined,
    targetFundId: e.type === 'FUND_TRANSFER' ? e.targetFundId : undefined,
    recurring: {
        frequency: e.recurringFrequency!, dayOfMonth: e.recurringDayOfMonth!,
        monthOfYear: e.recurringFrequency === 'YEARLY' ? e.recurringMonthOfYear ?? null : null, endDate: e.date,
    },
});
