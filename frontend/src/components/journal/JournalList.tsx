import { useState } from 'react';
import {
    ArrowDownToLine, Briefcase, CalendarDays, Car, ChevronDown, ChevronUp, Coffee, Gift, GraduationCap, HeartPulse,
    House, Landmark, Plane, Receipt, Repeat, Scissors, ShoppingBag, ShoppingCart, Target, Wifi, type LucideIcon,
} from 'lucide-react';
import { buttonVariants, KindMark } from '@/ds';
import { cn } from '@/lib/utils';
import type { FinancialEvent } from '../../types/api';
import type { JournalDay, JournalRow, JournalView, JournalWeek } from '../../lib/journalSections';
import { quickClose, type QuickClose } from '../../lib/quickClose';
import { characterLocked } from '../../lib/priority';
import { ruPlural } from '../../lib/plural';
import { signedFact } from '../../lib/factSign';

/**
 * Список журнала на деталях системы (срез 3, п. 6; доски Zh2-J8, Zh2-J8b, S3-F7). Что куда — lib/journalSections
 * (journalView); здесь только вид. Состояние, чтения и записи — у страницы (pages/Budget.tsx).
 */

/** Значок категории в кружке вида — по имени стартовой категории (Registration), иначе общий. */
const ICONS: Record<string, LucideIcon> = {
    'Жильё и быт': House, 'Связь и подписки': Wifi, 'Образование': GraduationCap, 'Обязательные платежи': Landmark,
    'Транспорт': Car, 'Продукты': ShoppingCart, 'Кафе и доставка': Coffee, 'Здоровье': HeartPulse,
    'Забота о себе': Scissors, 'Покупки': ShoppingBag, 'Досуг и поездки': Plane, 'Подарки и помощь': Gift,
    'Зарплата': ArrowDownToLine, 'Подработка': Briefcase, 'Прочий доход': ArrowDownToLine,
};

const iconOf = (e: FinancialEvent): LucideIcon =>
    e.type === 'FUND_TRANSFER' ? Target : ICONS[e.categoryName] ?? (e.type === 'INCOME' ? ArrowDownToLine : Receipt);

const MONTHS_GEN = ['января', 'февраля', 'марта', 'апреля', 'мая', 'июня', 'июля', 'августа', 'сентября', 'октября', 'ноября', 'декабря'];

/** Подсказка у значка повтора. */
function recurringTitle(e: FinancialEvent): string {
    if (e.recurringFrequency === 'MONTHLY' && e.recurringDayOfMonth != null) return `Повторяется ежемесячно ${e.recurringDayOfMonth}-го числа`;
    if (e.recurringFrequency === 'YEARLY' && e.recurringDayOfMonth != null && e.recurringMonthOfYear != null) {
        return `Повторяется ${e.recurringDayOfMonth} ${MONTHS_GEN[e.recurringMonthOfYear - 1]} каждого года`;
    }
    return 'Повторяющееся событие';
}

/**
 * Сумма строки. Знак — по направлению денег (ANO-184, lib/factSign): «+» у дохода и возврата из копилки; у ушедшего
 * знака нет — как на доске Zh2-J8.
 */
function money(r: JournalRow): string {
    if (r.amount == null) return '—';
    const s = signedFact(r.event.type, r.amount);
    return `${s.sign === '+' ? '+' : ''}${Math.round(s.amount).toLocaleString('ru-RU').replace(/\s/g, '\u00a0')}\u00a0₽`;
}

/** Подсветка пары наведением: план и его записи, только фон (ANO-42). */
const hover = (group: string, on: boolean) => document.querySelectorAll(`[data-group="${group}"]`)
    .forEach((el) => el.classList.toggle('pf-hovered', on));

export interface JournalHandlers {
    /** Нажатие на строку — правка (EditEventSheet до PR 5). */
    onRow: (e: FinancialEvent) => void;
    /** «Оплачено» / «Получено» — одним касанием или с вопросом о дате, если раньше сверки. */
    onPaid: (e: FinancialEvent, d: QuickClose) => void;
    /** «своё» — запись к этому плану (FactCreateSheet до PR 4). */
    onOwn: (e: FinancialEvent) => void;
    /** Кружок вида у плана — сменить вид. */
    onCycle: (e: FinancialEvent) => void;
    /** «обновить» после записи, когда журнал не перечитался. */
    onRefresh: () => void;
}

interface Props {
    view: JournalView;
    /** Сегодня по календарю сервера — граница записи (ANO-235). */
    allowedToday: string;
    /** Последняя сверка основного счёта — граница одного касания (lib/quickClose). */
    anchorDate: string | null;
    /** «Впереди» раскрыто сразу. */
    aheadOpen?: boolean;
    /** День, к которому журнал открыт (?date=): его неделя открыта. */
    openDate?: string | null;
    busy: (id: string) => boolean;
    failed: (id: string) => boolean;
    stale: (id: string) => boolean;
    handlers: JournalHandlers;
}

export default function JournalList({ view, allowedToday, anchorDate, aheadOpen = false, openDate, busy, failed, stale, handlers }: Props) {
    const [ahead, setAhead] = useState(aheadOpen);
    const [toggled, setToggled] = useState<Record<string, boolean>>({});

    const row = (r: JournalRow) => {
        const Icon = iconOf(r.event);
        const cycle = r.event.eventKind === 'PLAN' && !characterLocked(r.event);
        return (
            <div data-group={r.group} onClick={() => handlers.onRow(r.event)}
                onMouseEnter={() => hover(r.group, true)} onMouseLeave={() => hover(r.group, false)}
                className={cn('flex cursor-pointer items-center gap-3 rounded-sf-field px-2 py-2.5 transition-colors',
                    r.fact ? 'pf-is-fact' : 'pf-is-plan', r.muted && 'opacity-60')}>
                <KindMark kind={r.kind} fact={r.fact} icon={<Icon strokeWidth={1.7} />}
                    onClick={cycle ? (e) => { e.stopPropagation(); handlers.onCycle(r.event); } : undefined} />
                <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-1.5">
                        <span className="truncate text-sf-body">{r.name}</span>
                        {r.event.recurringRuleId && (
                            <span title={recurringTitle(r.event)} className="shrink-0 text-sf-muted"><Repeat size={13} /></span>
                        )}
                    </div>
                    {r.sub && <div className="truncate text-sf-label text-sf-muted">{r.sub}</div>}
                </div>
                <span className={cn('shrink-0 text-sf-body tabular-nums', r.fact && 'font-medium')}>{money(r)}</span>
            </div>
        );
    };

    /** План: строка, под ней «Оплачено | своё» и ошибки записи. */
    const planRow = (r: JournalRow) => {
        const e = r.event;
        const d = quickClose(e, allowedToday, anchorDate);
        const pays = d.kind === 'tap' || d.kind === 'askDate';
        const held = busy(e.id);
        return (
            <div key={e.id}>
                {row(r)}
                <div className="flex flex-col items-end gap-1 px-2 pb-2">
                    {d.kind !== 'done' && (
                        <div className="inline-flex">
                            {pays && (
                                <button type="button" disabled={held} onClick={() => handlers.onPaid(e, d)}
                                    className={cn(buttonVariants({ size: 's' }), 'rounded-r-none')}>
                                    {r.income ? 'Получено' : 'Оплачено'}
                                </button>
                            )}
                            <button type="button" disabled={held} onClick={() => handlers.onOwn(e)}
                                className={cn(buttonVariants({ variant: 'soft', size: 's' }), pays && 'rounded-l-none')}>
                                своё
                            </button>
                        </div>
                    )}
                    {failed(e.id) && <p className="text-sf-label text-sf-error">Не записалось — попробуйте ещё раз</p>}
                    {stale(e.id) && (
                        <p className="text-sf-label text-sf-muted">
                            Записано, журнал не обновился —{' '}
                            <button type="button" className="underline" onClick={handlers.onRefresh}>обновить</button>
                        </p>
                    )}
                </div>
            </div>
        );
    };

    const day = (d: JournalDay) => (
        <section key={d.iso} id={`day-${d.iso}`}>
            <h3 className="px-2 pb-1.5 pt-4 text-sf-label font-semibold text-sf-muted">{d.title}</h3>
            {d.plans.length > 0 && (
                <div className="rounded-sf-plate bg-sf-plate px-1.5 pt-2">
                    <div className="px-2 text-sf-caption font-semibold text-sf-muted">по плану</div>
                    {d.plans.map(planRow)}
                </div>
            )}
            <div className="divide-y divide-sf-rule">
                {d.records.map((r) => <div key={r.event.id}>{row(r)}</div>)}
            </div>
        </section>
    );

    const week = (w: JournalWeek) => {
        const opened = toggled[w.label] ?? (w.open || (openDate != null && w.start <= openDate && openDate <= w.end));
        const count = w.count === 0 ? 'Нет записей' : `${w.count} ${ruPlural(w.count, ['запись', 'записи', 'записей'])}`;
        const Chevron = opened ? ChevronUp : ChevronDown;
        return (
            <section key={w.label} data-week-start={w.start} data-week-end={w.end}>
                {!w.current && (
                    <button type="button" aria-expanded={opened}
                        onClick={() => setToggled((s) => ({ ...s, [w.label]: !opened }))}
                        className="flex w-full items-center justify-between border-b border-sf-rule px-2 py-3 text-left">
                        <span className="text-sf-body font-semibold">{w.label}</span>
                        <span className="flex items-center gap-1 text-sf-note text-sf-muted">{count}<Chevron size={14} /></span>
                    </button>
                )}
                {opened && (w.days.length > 0
                    ? w.days.map(day)
                    : w.current && <p className="px-2 py-3 text-sf-note text-sf-muted">Нет записей</p>)}
            </section>
        );
    };

    const a = view.ahead;
    return (
        <div className="font-sf text-sf-ink">
            {a && (
                <section className="mb-1 rounded-sf-plate border border-dashed border-sf-rule">
                    <button type="button" aria-expanded={ahead} onClick={() => setAhead((o) => !o)}
                        className="flex w-full items-center gap-3 px-4 py-3 text-left">
                        <CalendarDays size={20} strokeWidth={1.6} className="shrink-0 text-sf-muted" />
                        <span className="min-w-0 flex-1">
                            <span className="block text-sf-body font-semibold">{a.title} · {a.count}</span>
                            {!ahead && <span className="block truncate text-sf-label text-sf-muted">{a.summary}</span>}
                        </span>
                        {ahead ? <ChevronUp size={18} /> : <ChevronDown size={18} />}
                    </button>
                    {ahead && a.groups.map((g) => (
                        <div key={g.label} className="px-1.5 pb-1">
                            <div className="px-2 pb-1 pt-2 text-sf-label font-semibold text-sf-muted">{g.label}</div>
                            {g.rows.map(planRow)}
                        </div>
                    ))}
                </section>
            )}
            {view.weeks.map(week)}
        </div>
    );
}
