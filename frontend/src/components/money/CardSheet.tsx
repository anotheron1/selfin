import { useState } from 'react';
import { Banknote, ChevronDown, ChevronRight, CreditCard, Pencil, PiggyBank, Target, Trash2, Wallet, type LucideIcon } from 'lucide-react';
import { Button, Field, Money, Plate } from '@/ds';
import { cn } from '@/lib/utils';
import {
    createCheckpoint, deleteAccount, deleteCheckpoint, makeAccountDefault, updateAccount, updateCheckpoint,
} from '../../api';
import type { Account, BalanceCheckpoint, Category, FinancialEventCreateDto, TargetFund } from '../../types/api';
import FormSheet from '../FormSheet';
import { amountValue } from '../ui/amount-input';
import { CardForm, Labeled } from './AddCardSheet';
import {
    WORDS, answerLabel, canMakeMain, cardFormProblem, cardView, cardWriteFailure, deleteCardFailure, diffLine, historyChart,
    makeMainFailure, pointNote, savingsSince, updateDto, type CardView,
} from '../../lib/moneyCard';
import { monthIn } from '../../lib/moneyGroups';
import { fmtAmount, fmtDayYear, fmtNum } from '../../lib/format';
import { latestAllowedIso } from '../../lib/factDate';
import { DELETE_FAILED, writeFailure } from '../../lib/writeFailure';

/**
 * Лист карты (срез 4, п. 12; S4-C2 — любая карта, S4-C3 — кредитка, S4-C4 — накопительная, S4-C5 — для трат; на 1440 —
 * окном). История линией по обновлениям, нажатая точка — поправить или удалить; «Обновить остаток»; «Сделать основной»;
 * «Изменить», «Удалить». Заменил AccountCard и лист AccountSheet: опись 21–32.
 */

/** Форма «+» с ответом карты: «Записать «Бензин»», «Отложить сюда». */
export type RecordPreset = Pick<FinancialEventCreateDto, 'type' | 'categoryId' | 'targetFundId'>;

interface CardProps {
    account: Account;
    /** Обновления этой карты по дате (`cardHistory`). */
    history: BalanceCheckpoint[];
    funds: TargetFund[];
    categories: Category[];
    /** Местная дата, ISO: «записано в ноябре», подписи дат. */
    today: string;
    onRecord: (preset: RecordPreset) => void;
    /** Карта удалена или стала основной — закрыть лист со строкой внизу экрана. */
    onDone: (message: string) => void;
}

export default function CardSheet({ onClose, ...p }: CardProps & { onClose: () => void }) {
    return (
        <FormSheet label={p.account.name} onClose={onClose}>
            <CardBody {...p} />
        </FormSheet>
    );
}

const SUB: Record<CardView, string> = {
    main: 'основная · от неё считается «Свободно»',
    together: 'на всё · считается вместе с основной',
    credit: 'кредитка · это долг',
    savings: 'накопительная · в «Свободно» не входит',
    spending: 'для отдельных трат',
};

const iconOf = (a: Account): LucideIcon =>
    a.kind === 'DEPOSIT' ? PiggyBank : a.kind === 'CASH' ? Banknote : a.trackBalance ? CreditCard : Wallet;

/** Что раскрыто: обновить сегодня или на дату, поправить или удалить точку, весь список, правка, удаление карты. */
type Open = 'update' | 'date' | 'fix' | 'drop' | 'all' | 'edit' | 'delete' | null;

export function CardBody({ account: a, history, funds, categories, today, onRecord, onDone }: CardProps) {
    const view = cardView(a);
    const savings = view === 'savings';
    const [picked, setPicked] = useState<string | null>(null);
    const [open, setOpen] = useState<Open>(null);
    const [floorKept, setFloorKept] = useState(false);
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState<string | null>(null);

    const point = history.find(c => c.id === picked) ?? history[history.length - 1] ?? null;
    const last = history[history.length - 1] ?? null;
    const prevOf = (c: BalanceCheckpoint) => history[history.indexOf(c) - 1];
    const own = funds.filter(f => f.accountId === a.id);
    const toggle = (key: Open) => { setError(null); setOpen(o => (o === key ? null : key)); };
    const run = async (write: () => Promise<unknown>, after: () => void, failure: (e: unknown) => string = writeFailure) => {
        setBusy(true);
        setError(null);
        try {
            await write();
            after();
        } catch (e) {
            setError(failure(e));
        } finally {
            setBusy(false);
        }
    };
    const saved = () => { setOpen(null); setPicked(null); };

    const Icon = iconOf(a);
    const value = view === 'spending' ? a.allocatedThisMonth : a.balance;
    const caption = view === 'spending' ? (value == null ? null : `записано ${monthIn(today)}`)
        : a.balance == null ? 'первое обновление'
            : view === 'credit' ? (a.creditLimit != null ? WORDS.creditAvailable(fmtNum(a.creditLimit)) : 'доступно') : 'по записям';
    const category = a.purposeCategoryName;

    return (
        <div className="flex flex-col gap-3">
            {/* Длинная сумма уходит на свою строку, а не сжимает название (как лист вещи). */}
            <div className="flex flex-wrap items-center gap-3 pr-10">
                <span aria-hidden="true" className="flex size-12 shrink-0 items-center justify-center rounded-full bg-sf-chip">
                    <Icon size={22} strokeWidth={1.6} />
                </span>
                <span className="flex min-w-[150px] flex-1 flex-col">
                    <span className="text-[20px] font-semibold leading-6">{a.name}</span>
                    <span className="text-sf-label text-sf-muted">{view === 'spending' && category ? `${SUB.spending} · ${category}` : SUB[view]}</span>
                </span>
                <span className="ml-auto flex flex-col items-end">
                    {value != null && <Money value={fmtNum(value)} size="m" />}
                    {caption && <span className="text-sf-label text-sf-muted">{caption}</span>}
                </span>
            </div>

            {view === 'credit' && a.balanceDate && a.debt != null && (
                <p className="px-1 text-sf-note text-sf-muted">{WORDS.creditSince(a.balanceDate === today ? 'сегодня' : fmtDayYear(a.balanceDate, today), fmtAmount(a.debt))}</p>
            )}
            {view === 'credit' && a.floorSuggestion != null && a.availableFloor != null && !floorKept && (
                <Plate className="flex flex-col gap-2">
                    <span className="text-sf-note">
                        {`Доступно уже больше, чем «возвращаю до ${fmtAmount(a.availableFloor)}». Возвращать до ${fmtAmount(a.floorSuggestion)}?`}
                    </span>
                    <div className="flex flex-wrap gap-2">
                        <Button size="s" disabled={busy} onClick={() => void run(() => updateAccount(a.id, {
                            name: a.name, kind: a.kind, trackBalance: a.trackBalance, purposeCategoryId: a.purposeCategoryId,
                            creditLimit: a.creditLimit, availableFloor: a.floorSuggestion,
                        }), () => {})}>{`Да, до ${fmtNum(a.floorSuggestion)}`}</Button>
                        <Button size="s" variant="soft" onClick={() => setFloorKept(true)}>{`Оставить ${fmtNum(a.availableFloor)}`}</Button>
                    </div>
                </Plate>
            )}

            {savings && last && a.balance != null && <p className="px-1 text-sf-note text-sf-muted">{savingsSince(last, a.balance, today)}</p>}
            {savings && own.map(f => (
                <Plate key={f.id} className="flex items-center gap-3">
                    <Target size={20} strokeWidth={1.6} className="shrink-0" />
                    <span className="flex flex-col">
                        <span className="text-sf-body font-semibold">{`Здесь копилка «${f.name}»`}</span>
                        <span className="text-sf-label text-sf-muted">
                            {f.targetAmount != null ? `${fmtNum(f.currentBalance)} из ${fmtAmount(f.targetAmount)}` : fmtAmount(f.currentBalance)}
                        </span>
                    </span>
                </Plate>
            ))}
            {savings && own.length > 0 && a.balanceDate && (
                <Button variant="soft" onClick={() => onRecord({ type: 'FUND_TRANSFER', targetFundId: own.length === 1 ? own[0].id : undefined })}>
                    Отложить сюда
                </Button>
            )}

            {view === 'spending' && (
                <Plate className="flex flex-col gap-2 text-sf-note">
                    {category ? (
                        <>
                            <span>{`Остаток этой карты selfin не ведёт. Перевёл на неё деньги — запиши одну трату «${category}»: покупки по отдельности не нужны.`}</span>
                            <span className="text-sf-muted">
                                {`${capital(monthIn(today))} в «${category}» записано ${fmtAmount(a.allocatedThisMonth ?? 0)} — с любой карты.`}
                            </span>
                        </>
                    ) : 'Остаток этой карты selfin не ведёт. Выбери, для каких она трат, — тогда здесь будет видно, сколько записано за месяц.'}
                </Plate>
            )}
            {view === 'spending' && a.purposeCategoryId && (
                <Button onClick={() => onRecord({ type: 'EXPENSE', categoryId: a.purposeCategoryId! })}>{`Записать «${category}»`}</Button>
            )}

            {a.trackBalance && point && (
                <section className="flex flex-col gap-2">
                    <div className="flex items-baseline justify-between gap-2">
                        <h3 className="text-sf-body font-semibold">Остаток по обновлениям</h3>
                        <span className="text-right text-sf-label text-sf-muted">точки — из банка, пунктир — по записям</span>
                    </div>
                    <HistoryChart history={history} now={a.balance != null ? { date: today, value: a.balance } : null}
                        picked={point.id} onPick={id => { setPicked(id); setOpen(null); }} />
                    {open === 'fix' ? (
                        <BalanceForm credit={view === 'credit'} savings={savings} byRecords={null} withDate initial={point} busy={busy}
                            onSave={(amount, date) => void run(() => updateCheckpoint(point.id, { date, amount, accountId: a.id }), saved)}
                            onCancel={() => setOpen(null)} />
                    ) : (
                        <Plate className="flex flex-col gap-2">
                            <span className="text-sf-body font-semibold">
                                {`${fmtDayYear(point.date, today)}${point.date === today ? ', сегодня' : ''} — ${fmtAmount(point.amount)}`}
                            </span>
                            <span className="text-sf-note text-sf-muted">{pointNote(point, savings, prevOf(point))}</span>
                            {open === 'drop' ? (
                                <>
                                    <span className="text-sf-note">
                                        {`Удалить обновление ${fmtDayYear(point.date, today)}? `
                                            + (history.length === 1 ? 'Обновлений не останется — будет «первое обновление».' : 'Остаток посчитается от предыдущего.')}
                                    </span>
                                    <div className="flex gap-2">
                                        <Button size="s" disabled={busy}
                                            onClick={() => void run(() => deleteCheckpoint(point.id), saved, () => DELETE_FAILED)}>Удалить</Button>
                                        <Button size="s" variant="text" onClick={() => setOpen(null)}>Отмена</Button>
                                    </div>
                                </>
                            ) : (
                                <div className="flex gap-2">
                                    <Button size="s" variant="soft" onClick={() => toggle('fix')}>Поправить</Button>
                                    <Button size="s" variant="soft" onClick={() => toggle('drop')}>Удалить</Button>
                                </div>
                            )}
                        </Plate>
                    )}
                    <button type="button" aria-expanded={open === 'all'} onClick={() => toggle('all')}
                        className="flex items-center gap-1 self-start px-1 text-sf-label text-sf-muted hover:text-sf-ink">
                        {`Все обновления — ${history.length}`}
                        <ChevronDown size={14} className={cn('transition-transform', open === 'all' && 'rotate-180')} />
                    </button>
                    {open === 'all' && (
                        <ul>
                            {[...history].reverse().map(c => (
                                <li key={c.id}>
                                    <button type="button" onClick={() => setPicked(c.id)}
                                        className={cn('flex w-full items-center gap-3 border-b border-sf-rule py-2 text-left hover:bg-sf-chip/40',
                                            c.id === point.id && 'bg-sf-accent-soft')}>
                                        <span className="flex min-w-0 flex-1 flex-col">
                                            <span className="text-sf-body">{fmtDayYear(c.date, today)}</span>
                                            <span className="text-sf-label text-sf-muted">{diffLine(c, savings, prevOf(c))}</span>
                                        </span>
                                        <span className="whitespace-nowrap text-sf-body tabular-nums">{fmtAmount(c.amount)}</span>
                                        <Pencil size={14} className="shrink-0 text-sf-muted" />
                                    </button>
                                </li>
                            ))}
                        </ul>
                    )}
                </section>
            )}

            {a.trackBalance && (open === 'update' || open === 'date' ? (
                <BalanceForm credit={view === 'credit'} savings={savings} byRecords={open === 'update' ? a.balance : null}
                    withDate={open === 'date'} busy={busy}
                    onSave={(amount, date) => void run(() => createCheckpoint({ date, amount, accountId: a.id }), saved)}
                    onCancel={() => setOpen(null)} />
            ) : (
                <div className="flex gap-2.5">
                    <Button className="flex-[1.3] px-3" onClick={() => toggle('update')}>Обновить остаток</Button>
                    <Button variant="soft" className="flex-1 px-3" onClick={() => toggle('date')}>На другую дату</Button>
                </div>
            ))}
            {canMakeMain(a) && (
                <Button variant="soft" disabled={busy}
                    onClick={() => void run(() => makeAccountDefault(a.id), () => onDone(`«${a.name}» — теперь основная`), makeMainFailure)}>
                    Сделать основной
                </Button>
            )}

            {open === 'edit' ? (
                <Plate>
                    <CardForm account={a} categories={categories} busy={busy} error={error} onCancel={() => setOpen(null)}
                        onSave={v => {
                            const problem = cardFormProblem(v);
                            if (problem) { setError(problem); return; }
                            const dto = updateDto(a, v);
                            void run(() => updateAccount(a.id, dto), () => setOpen(null), e => cardWriteFailure(e, a, dto.kind));
                        }} />
                </Plate>
            ) : (
                <ul>
                    {[['Для чего эта карта', answerLabel(a)],
                        ...(view === 'credit' ? [
                            [WORDS.limit, a.creditLimit != null ? fmtAmount(a.creditLimit) : 'не задан'],
                            [WORDS.floor, a.availableFloor != null ? fmtAmount(a.availableFloor) : 'не задано'],
                        ] : []),
                        ...(view === 'spending' ? [['Для каких трат', category ?? 'не задано']] : []),
                        ['Название', a.name]].map(([k, v]) => (
                        <li key={k}>
                            <button type="button" onClick={() => toggle('edit')}
                                className="flex min-h-[52px] w-full items-center justify-between gap-3 border-b border-sf-rule text-left text-sf-body hover:bg-sf-chip/40">
                                <span className="text-sf-note text-sf-muted">{k}</span>
                                <span className="flex min-w-0 items-center gap-1"><span className="truncate">{v}</span><ChevronRight size={16} className="shrink-0 text-sf-muted" /></span>
                            </button>
                        </li>
                    ))}
                </ul>
            )}
            {view === 'credit' && open !== 'edit' && <p className="px-1 text-sf-label text-sf-muted">{WORDS.floorNote}</p>}

            {view === 'main' ? <p className="px-1 text-sf-label text-sf-muted">{WORDS.mainNoDelete}</p>
                : open === 'delete' ? (
                    <Plate className="flex flex-col gap-2">
                        <span className="text-sf-note">{WORDS.deleteAsk}</span>
                        <div className="flex gap-2">
                            <Button size="s" disabled={busy}
                                onClick={() => void run(() => deleteAccount(a.id), () => onDone('Карта удалена'), deleteCardFailure)}>Удалить карту</Button>
                            <Button size="s" variant="text" onClick={() => setOpen(null)}>Отмена</Button>
                        </div>
                    </Plate>
                ) : (
                    <Button variant="soft" onClick={() => toggle('delete')}><Trash2 size={16} strokeWidth={1.8} />Удалить карту</Button>
                )}
            {error && open !== 'edit' && <p role="alert" className="text-sf-note text-sf-error">{error}</p>}
        </div>
    );
}

const capital = (s: string) => s.charAt(0).toUpperCase() + s.slice(1);

const W = 340;
const H = 150;

/** История линией (S4-C2): точки — обновления, пунктир — по записям; нажатая точка — крупнее и закрашена. */
function HistoryChart({ history, now, picked, onPick }: {
    history: BalanceCheckpoint[]; now: { date: string; value: number } | null; picked: string; onPick: (id: string) => void;
}) {
    const c = historyChart(history, now, W, H);
    return (
        <svg role="img" viewBox={`0 0 ${W} ${H}`} className="block h-auto w-full"
            aria-label={`Остаток по обновлениям: ${history.map(h => `${h.date} — ${fmtAmount(h.amount)}`).join(', ')}`}>
            {c.area && <path d={c.area} fill="var(--sf-accent-soft)" fillOpacity={0.6} />}
            <path d={c.path} fill="none" stroke="var(--sf-accent)" strokeWidth={1.6} strokeDasharray="4 4" />
            {c.dots.map(d => (
                <g key={d.id} onClick={() => onPick(d.id)} className="cursor-pointer">
                    <circle cx={d.x} cy={d.y} r={12} fill="transparent" />
                    <circle cx={d.x} cy={d.y} r={d.id === picked ? 6 : 4.5} strokeWidth={2} stroke="var(--sf-accent)"
                        fill={d.id === picked ? 'var(--sf-accent)' : 'var(--sf-page)'} />
                </g>
            ))}
            {c.labels.map(l => (
                <text key={l.text} x={l.x} y={c.baseline} textAnchor={l.anchor} fontSize={10} fill="var(--sf-muted)">{l.text}</text>
            ))}
        </svg>
    );
}

/** Обновление остатка одной карты: сегодня — с разницей словами; на дату или правка точки — с датой. */
function BalanceForm({ credit, savings, byRecords, withDate, initial, busy, onSave, onCancel }: {
    credit: boolean; savings: boolean;
    /** Остаток по записям на сегодня — для разницы словами; null — без неё. */
    byRecords: number | null;
    withDate: boolean;
    initial?: BalanceCheckpoint;
    busy: boolean;
    onSave: (amount: number, date: string) => void;
    onCancel: () => void;
}) {
    // ANO-235: дата — не позже даты сервера.
    const max = latestAllowedIso(new Date());
    const [raw, setRaw] = useState(initial ? String(initial.amount) : '');
    const [date, setDate] = useState(initial?.date ?? max);
    const amount = raw.trim() === '' ? null : amountValue(raw);
    const ok = amount != null && !Number.isNaN(amount) && amount >= 0 && date !== '';
    const live = ok && byRecords != null
        ? diffLine({ computedBalance: byRecords, drift: Math.round((amount! - byRecords) * 100) / 100 } as BalanceCheckpoint, savings) : null;
    return (
        <form className="flex flex-col gap-2 rounded-sf-plate bg-sf-plate p-3.5" onSubmit={e => {
            e.preventDefault();
            if (ok) onSave(amount!, date);
        }}>
            <div className="flex gap-2.5">
                <Labeled label={credit ? 'Доступно в банке' : 'В банке'}>
                    <Field autoFocus inputMode="decimal" value={raw} onChange={e => setRaw(e.target.value)} placeholder="0" suffix="₽" />
                </Labeled>
                {withDate && (
                    <Labeled label="На дату"><Field type="date" value={date} max={max} onChange={e => setDate(e.target.value)} /></Labeled>
                )}
            </div>
            {live && <span role="status" className="text-sf-note">{live}</span>}
            <div className="flex gap-2">
                <Button size="s" type="submit" disabled={busy || !ok}>Сохранить</Button>
                <Button size="s" variant="text" onClick={onCancel}>Отмена</Button>
            </div>
        </form>
    );
}
