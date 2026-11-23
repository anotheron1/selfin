import { useState } from 'react';
import { ChevronRight, Repeat, SkipForward, Trash2 } from 'lucide-react';
import { Button, IconButton, KindMark, Plate, buttonVariants } from '@/ds';
import { cn } from '@/lib/utils';
import {
    createStandaloneFact, deleteEvent, fetchFactPreview, fetchPocket, transferToFund, updateEvent,
} from '../../api';
import type { FinancialEvent, ScopeEnum } from '../../types/api';
import FormSheet from '../FormSheet';
import DeleteRecurringDialog from '../DeleteRecurringDialog';
import { transferEventId } from '../Fab';
import { iconOf } from './JournalList';
import { AttemptKeys } from '../../lib/attemptKey';
import { dayLabel, duplicateOf, endSeriesDto, eventName, factNote, seriesOf } from '../../lib/eventCard';
import { fmtDayShort, fmtRub } from '../../lib/format';
import { fundMovementMessage } from '../../lib/fundMovement';
import { PRIORITY_DOT_CONFIG } from '../../lib/priority';
import { afterLine, creditWritten, freeEffect } from '../../lib/quickAdd';
import type { QuickClose } from '../../lib/quickClose';
import { DELETE_FAILED, writeFailure } from '../../lib/writeFailure';

const KIND = { HIGH: 'bron', MEDIUM: 'ozh', LOW: 'hot' } as const;

/** Что записано — для строки снизу и «Отменить» (S3-F3); в кредит — ещё «Обновить кредитку» (S4-K2). */
export interface Written { text: string; undo: () => Promise<unknown>; credit?: boolean }

/**
 * «Ещё раз сегодня» (п. 17, ANO-254): новая запись сегодняшним числом по образцу, строка снизу — как после формы «+»:
 * что записано, что со «Свободно» (по строке ожидания с сервера до записи), «Отменить».
 */
export async function writeDuplicate(e: FinancialEvent, today: string, attempts: AttemptKeys): Promise<Written> {
    const amount = e.factAmount ?? 0;
    const head = `Записано: ${eventName(e)}, ${fmtRub(amount)}.`;
    if (e.type === 'FUND_TRANSFER') {
        // Перевод — тем же путём, что «Отложить» в форме: копилка растёт; запись ищется для «Отменить».
        await transferToFund(attempts, e.targetFundId!, amount, true, undefined, today);
        const id = await transferEventId(e.targetFundId!, amount, today).catch(() => undefined);
        return { text: head, undo: () => (id ? deleteEvent(id) : Promise.reject(new Error('перевод не найден'))) };
    }
    const dto = duplicateOf(e, today);
    // В кредит (срез 4, п. 17): «Свободно» не меняется — строка S4-K2, без строки ожидания.
    if (dto.onCredit) {
        const fact = await createStandaloneFact(attempts, dto);
        return { text: creditWritten(eventName(e), amount), undo: () => deleteEvent(fact.id), credit: true };
    }
    // undefined — строка ожидания не пришла: строки о «Свободно» нет, как у формы.
    const [pocket, line] = await Promise.all([
        fetchPocket().catch(() => null),
        fetchFactPreview({ categoryId: dto.categoryId, date: today, type: dto.type, priority: e.priority })
            .then(v => v ?? null, () => undefined),
    ]);
    const fact = await createStandaloneFact(attempts, dto);
    const after = pocket && line !== undefined ? afterLine(freeEffect({
        action: 'record', type: e.type as 'EXPENSE' | 'INCOME', kind: e.priority, amount, date: today, today,
        checkpointDate: pocket.checkpointDate, horizon: pocket.horizon, line, trajectory: pocket.trajectory,
    })) : null;
    return { text: after ? `${head} ${after}` : head, undo: () => deleteEvent(fact.id) };
}

/**
 * Корзина: без серии — «Удалить запись?»; у серии — выбор «это, дальше, все», «Исполненные события прошлого не
 * удаляются» (DeleteRecurringDialog). Отказ сервера — словами (ANO-201).
 */
export function TrashButton({ event, label, disabled, onDeleted, onError }: {
    event: FinancialEvent; label: string; disabled?: boolean; onDeleted: () => void; onError: (text: string | null) => void;
}) {
    const [ask, setAsk] = useState(false);
    const run = (scope: ScopeEnum) => {
        onError(null);
        deleteEvent(event.id, scope).then(onDeleted, (err) => {
            console.error(err);
            onError(fundMovementMessage(err) ?? DELETE_FAILED);
        });
    };
    return (
        <>
            <IconButton aria-label={label} size="xl" disabled={disabled} className="bg-sf-chip hover:bg-sf-chip-hover"
                onClick={() => { if (event.recurringRuleId) setAsk(true); else if (confirm('Удалить запись?')) run('THIS'); }}>
                <Trash2 size={20} strokeWidth={1.7} />
            </IconButton>
            <DeleteRecurringDialog open={ask} onClose={() => setAsk(false)} onConfirm={(s) => { setAsk(false); run(s); }} />
        </>
    );
}

/**
 * Карточка (срез 3 п. 16; доски S3-E2a, S3-E2b; на 1440 — окном S3-W2). Нажатие на строку журнала открывает её, а не
 * форму. Запись: что это, строки, что с ней случилось, корзина, «Изменить», «Ещё раз сегодня». План: «Оплачено | своё»,
 * если дата наступила, строки, серия целиком, «Удалить этот платёж», «Пропустить», «Изменить этот». Строка карточки и
 * «Изменить» открывают ту же форму «+», заполненную (S3-E1a, S3-E1b).
 */
export default function EventCard({ event: e, today, pay, held, onClose, onEdit, onPaid, onOwn, onWritten }: {
    event: FinancialEvent;
    /** Сегодня по календарю сервера — дата дубля (ANO-235). */
    today: string;
    /** «Оплачено» одним касанием или с вопросом о дате (lib/quickClose); у записи не нужно. */
    pay?: QuickClose;
    /** Оплата этой брони уже идёт (QuickCloseGuard). */
    held?: boolean;
    onClose: () => void;
    /** Форма правки; scope — с чем она открыта у серии. */
    onEdit: (scope: ScopeEnum) => void;
    onPaid: () => void;
    onOwn: () => void;
    /** Дубль записан — строка снизу с «Отменить». Журнал перечитывается сам — по selfin:changed. */
    onWritten: (w: Written) => void;
}) {
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState<string | null>(null);
    // ANO-192: повтор дубля после сбоя — тот же ключ попытки, пока карточка открыта.
    const [attempts] = useState(() => new AttemptKeys());
    const fact = e.eventKind === 'FACT';
    const name = eventName(e);
    const kind = PRIORITY_DOT_CONFIG[e.priority ?? 'MEDIUM'].name.toLowerCase();
    const amount = (fact ? e.factAmount : e.plannedAmount) ?? 0;
    const Icon = iconOf(e);
    const series = fact ? null : seriesOf(e);
    const pays = pay?.kind === 'tap' || pay?.kind === 'askDate';
    const income = e.type === 'INCOME';
    const what = e.type === 'FUND_TRANSFER' ? (amount < 0 ? 'из копилки' : 'в копилку') : income ? 'доход' : 'трата';
    const sub = fact ? `запись · ${what}` : `план · ${kind}${e.date ? ` · ${dayLabel(e.date, today)}` : ''}`;

    const act = (run: () => Promise<unknown>, done: () => void, removes = false) => {
        setBusy(true);
        setError(null);
        run().then(done, (err) => {
            console.error(err);
            setError(removes ? fundMovementMessage(err) ?? DELETE_FAILED : writeFailure(err));
        }).finally(() => setBusy(false));
    };
    const line = (k: string, v: string) => (
        <button type="button" key={k} onClick={() => onEdit('FOLLOWING')}
            className="flex min-h-[52px] items-center justify-between gap-2.5 border-b border-sf-rule px-1 text-left outline-none focus-visible:outline focus-visible:outline-2 focus-visible:outline-sf-accent">
            <span className="text-sf-note text-sf-muted">{k}</span>
            <span className="flex min-w-0 items-center gap-1.5 text-sf-body">
                <span className="truncate">{v}</span><ChevronRight size={16} strokeWidth={2} className="shrink-0 text-sf-muted" />
            </span>
        </button>
    );

    return (
        <FormSheet label={`${fact ? 'Запись' : 'План'} «${name}»`} onClose={onClose} focusSheet>
            <div className="flex items-center gap-3.5 px-1 pr-10 pt-1">
                <KindMark kind={KIND[e.priority ?? 'MEDIUM']} fact={fact} icon={<Icon strokeWidth={1.6} />} className="size-12" />
                <div className="flex min-w-0 flex-1 flex-col">
                    <span className="truncate text-[18px] font-semibold leading-6">{name}</span>
                    <span className="text-sf-label text-sf-muted">{sub}</span>
                </div>
                <span className="whitespace-nowrap text-[30px] font-light leading-9 tracking-[-0.03em] tabular-nums">
                    {income ? '+' : ''}{Math.round(Math.abs(amount)).toLocaleString('ru-RU')}<span className="text-[.6em] tracking-normal text-sf-muted">&nbsp;₽</span>
                </span>
            </div>
            {pays && (
                <div className="flex">
                    <button type="button" disabled={held} onClick={() => { onPaid(); onClose(); }}
                        className={cn(buttonVariants({ size: 'm' }), 'flex-[2_1_0] rounded-r-none')}>
                        {income ? 'Получено' : 'Оплачено'}
                    </button>
                    <button type="button" disabled={held} onClick={() => { onOwn(); onClose(); }}
                        className={cn(buttonVariants({ variant: 'soft', size: 'm' }), 'flex-[1_1_0] rounded-l-none')}>
                        своё
                    </button>
                </div>
            )}
            <div className="flex flex-col">
                {fact
                    ? [line('Категория', e.categoryName), line('Дата', e.date ? dayLabel(e.date, today) : '—'),
                        line('Название', e.description || '—'), line('Вид', kind),
                        // Срез 4, п. 18: отметка правится в той же форме, что строка.
                        ...(e.onCredit ? [line('В кредит', 'да')] : [])]
                    : [line('Дата', e.date ? dayLabel(e.date, today) : '—'), line('Категория', e.categoryName)]}
            </div>
            {fact && factNote(e) && <span className="px-1 text-sf-label text-sf-muted">{factNote(e)}</span>}
            {series && (
                <Plate className="flex flex-col gap-2 px-3.5 py-3">
                    <span className="flex items-center gap-2 text-sf-body font-semibold"><Repeat size={16} strokeWidth={1.8} />{series.title}</span>
                    <span className="text-sf-label text-sf-muted">
                        {[series.since, series.until].filter(Boolean).join(', ')}{series.next.length > 0 ? ' · дальше:' : ' · это последний платёж'}
                    </span>
                    {series.next.length > 0 && (
                        <div className="flex flex-wrap gap-1.5">
                            {series.next.map(d => (
                                <span key={d} className="flex h-[30px] items-center whitespace-nowrap rounded-sf-pill bg-sf-page px-3 text-sf-label">
                                    {fmtDayShort(d)} · {fmtRub(e.plannedAmount ?? 0)}
                                </span>
                            ))}
                        </div>
                    )}
                    <div className="mt-0.5 flex gap-2">
                        <Button variant="soft" size="s" className="h-10 flex-1" onClick={() => onEdit('ALL')}>Изменить серию</Button>
                        {/* Этот платёж — последний: конец серии на его дату, дальше строк нет (п. 18). */}
                        <Button variant="outline" size="s" className="h-10 flex-1 text-sf-ink" disabled={busy || series.next.length === 0}
                            onClick={() => act(() => updateEvent(e.id, endSeriesDto(e), 'FOLLOWING'), onClose)}>
                            Закончить серию
                        </Button>
                    </div>
                </Plate>
            )}
            {error && <p className="px-1 text-sf-note text-sf-error">{error}</p>}
            <div className="flex gap-2">
                <TrashButton event={e} label={fact ? 'Удалить запись' : 'Удалить этот платёж'} disabled={busy}
                    onDeleted={onClose} onError={setError} />
                {fact ? (
                    <>
                        <Button variant="soft" className="flex-[1_1_0] px-3" onClick={() => onEdit('THIS')}>Изменить</Button>
                        <Button className="flex-[1.5_1_0] px-3" disabled={busy}
                            onClick={() => act(() => writeDuplicate(e, today, attempts).then(onWritten), onClose)}>
                            {busy ? 'Сохраняем…' : 'Ещё раз сегодня'}
                        </Button>
                    </>
                ) : (
                    <>
                        {/* «Пропустить» — снимает этот платёж, серия идёт дальше (п. 18). */}
                        {e.recurringRuleId && (
                            <Button variant="soft" className="flex-[1_1_0] px-3" disabled={busy}
                                onClick={() => act(() => deleteEvent(e.id, 'THIS'), onClose, true)}>
                                <SkipForward size={17} strokeWidth={1.9} />Пропустить
                            </Button>
                        )}
                        <Button variant="soft" className="flex-[1_1_0] px-3" onClick={() => onEdit('THIS')}>
                            {e.recurringRuleId ? 'Изменить этот' : 'Изменить'}
                        </Button>
                    </>
                )}
            </div>
        </FormSheet>
    );
}
