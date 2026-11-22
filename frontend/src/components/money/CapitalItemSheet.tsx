import { useCallback, useEffect, useState } from 'react';
import { ChevronRight, House, Landmark, Pencil } from 'lucide-react';
import { Button, Choices, Field, Money } from '@/ds';
import {
    addCapitalRevaluation, createCapitalItem, deleteCapitalItem, deleteCapitalRevaluation, fetchCapitalHistory,
    fetchCapitalItem, updateCapitalItem, updateCapitalRevaluation,
} from '../../api';
import type { CapitalItem, CapitalItemKind, CapitalRevaluation } from '../../types/api';
import { amountValue } from '../ui/amount-input';
import FormSheet from '../FormSheet';
import { latestAllowedIso } from '../../lib/factDate';
import { fmtAmount, fmtDayShort, fmtNum } from '../../lib/format';
import { DELETE_FAILED, writeFailure } from '../../lib/writeFailure';

/**
 * Лист вещи и долга (срез 4, п. 15; S4-P1): оценка, «Переоценить» с датой и заметкой, история оценок, «Продал» у вещи и
 * «Погасил» у долга с датой, «Удалить насовсем». Новая запись — тот же лист, вид выбирается в нём (опись 7). Заменил
 * CapitalSheet и CapitalRevaluationHistory: их функции — опись 10–15.
 */

export type ItemSheetMode = { type: 'create' } | { type: 'view'; item: CapitalItem };

const WORDS = {
    ASSET: { kind: 'имущество', worth: 'Сколько стоит', gone: 'Продал', goneFrom: 'Продал — с даты' },
    LIABILITY: { kind: 'долг', worth: 'Сколько должен', gone: 'Погасил', goneFrom: 'Погасил — с даты' },
} as const;

const KINDS = [{ value: 'ASSET', label: 'Имущество' }, { value: 'LIABILITY', label: 'Долг' }];

const today = () => latestAllowedIso(new Date());

/**
 * Правка записи истории. Заметка уходит всегда: сервер правит её, только если она пришла, — стёртая иначе осталась бы.
 * У записи «Продал» сумма 0 — её можно оставить, чтобы поправить дату (ревью #241).
 */
export function revisionUpdate(r: CapitalRevaluation, raw: string, valuedAt: string, note: string) {
    const value = r.value === 0 && raw.trim() === '0' ? 0 : amountValue(raw);
    return value == null || !valuedAt ? null : { value, valuedAt, note: note.trim() };
}

/** Последняя запись удаляется — стоимость станет нулём, и вещь уйдёт в «Архивные». */
export const deleteRevisionAsk = (only: boolean) => only
    ? 'Удалить единственную запись? Оценки не останется — уйдёт в «Архивные».'
    : 'Удалить запись? Если она последняя, оценкой станет предыдущая.';

/** Лист с чтениями: вещь и история перечитываются после каждой записи. */
/** Экран перечитывается сам — по событию удачной записи (api/client, CHANGED). */
export default function CapitalItemSheet({ mode, onClose }: { mode: ItemSheetMode; onClose: () => void }) {
    const [current, setCurrent] = useState(mode);
    const [history, setHistory] = useState<CapitalRevaluation[]>([]);
    const id = mode.type === 'view' ? mode.item.id : null;

    const reload = useCallback(() => {
        if (!id) return;
        fetchCapitalItem(id).then(item => setCurrent({ type: 'view', item })).catch(() => {});
        fetchCapitalHistory(id).then(setHistory).catch(() => {});
    }, [id]);
    useEffect(reload, [reload]);

    const title = current.type === 'view' ? current.item.name : 'Имущество или долг';
    return (
        <FormSheet label={title} onClose={onClose}>
            <CapitalItemForm mode={current} history={history} onDone={onClose}
                onChanged={reload} />
        </FormSheet>
    );
}

export function CapitalItemForm({ mode, history, onChanged, onDone }: {
    mode: ItemSheetMode;
    history: CapitalRevaluation[];
    /** Запись прошла — перечитать вещь, историю и экран. */
    onChanged: () => void;
    /** Закрыть лист: создано, продано, удалено. */
    onDone: () => void;
}) {
    const item = mode.type === 'view' ? mode.item : null;
    const [kind, setKind] = useState<CapitalItemKind>(item?.kind ?? 'ASSET');
    const [name, setName] = useState('');
    const [description, setDescription] = useState('');
    const [value, setValue] = useState('');
    const [date, setDate] = useState(today);
    const [note, setNote] = useState('');
    /** Дата «Продал» — своя: несохранённая дата «Переоценить» не становится датой продажи. */
    const [goneDate, setGoneDate] = useState(today);
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState<string | null>(null);
    /** Что раскрыто: правка строки истории, названия, описания, «Продал», «Удалить насовсем». */
    const [open, setOpen] = useState<string | null>(null);
    const w = WORDS[item?.kind ?? kind];

    const run = async (write: () => Promise<unknown>, after: () => void, failure = writeFailure) => {
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
    const amount = amountValue(value);

    if (!item) {
        return (
            <form className="flex flex-col gap-3" onSubmit={e => {
                e.preventDefault();
                if (!name.trim() || amount == null) return;
                void run(() => createCapitalItem({
                    kind, name: name.trim(), description: description.trim() || undefined, initialValue: amount, initialValuedAt: date,
                }), () => { onChanged(); onDone(); });
            }}>
                <h2 className="pr-10 text-[20px] font-semibold">Имущество или долг</h2>
                <Choices name="item-kind" label="Что это" options={KINDS} value={kind} onChange={v => setKind(v as CapitalItemKind)} />
                <Labeled label="Название"><Field value={name} onChange={e => setName(e.target.value)} placeholder="например, Машина" /></Labeled>
                <Worth w={w} value={value} onValue={setValue} date={date} onDate={setDate} />
                <Labeled label="Описание (необязательно)">
                    <Field value={description} onChange={e => setDescription(e.target.value)} placeholder="например, Лада Веста, 2021" />
                </Labeled>
                {error && <p role="alert" className="text-sf-note text-sf-error">{error}</p>}
                <Button type="submit" disabled={busy || !name.trim() || amount == null}>Добавить</Button>
            </form>
        );
    }

    const Icon = item.kind === 'LIABILITY' ? Landmark : House;
    const toggle = (key: string) => { setError(null); setOpen(o => o === key ? null : key); };

    return (
        <div className="flex flex-col gap-3">
            {/* Длинная сумма уходит на свою строку, а не сжимает название (стенд, 390). */}
            <div className="flex flex-wrap items-center gap-3 pr-10">
                <span aria-hidden="true" className="flex size-12 shrink-0 items-center justify-center rounded-full bg-sf-chip">
                    <Icon size={22} strokeWidth={1.6} />
                </span>
                <span className="flex min-w-[150px] flex-1 flex-col">
                    <span className="text-[20px] font-semibold leading-6">{item.name}</span>
                    <span className="text-sf-label text-sf-muted">
                        {[w.kind, item.lastValuedAt && `оценка ${fmtDayShort(item.lastValuedAt)}`].filter(Boolean).join(' · ')}
                    </span>
                </span>
                <Money value={fmtNum(item.currentValue)} size="m" className="ml-auto" />
            </div>

            <form className="flex flex-col gap-3" onSubmit={e => {
                e.preventDefault();
                if (amount == null) return;
                void run(() => addCapitalRevaluation(item.id, { value: amount, valuedAt: date, note: note.trim() || undefined }),
                    () => { setValue(''); setNote(''); onChanged(); });
            }}>
                <h3 className="text-sf-body font-semibold">Переоценить</h3>
                <Worth w={w} value={value} onValue={setValue} date={date} onDate={setDate} />
                <Labeled label="Заметка (необязательно)">
                    <Field value={note} onChange={e => setNote(e.target.value)} placeholder="например, по объявлениям" />
                </Labeled>
                <Button type="submit" disabled={busy || amount == null}>Сохранить оценку</Button>
            </form>

            <div className="flex items-baseline justify-between gap-2">
                <h3 className="text-sf-body font-semibold">История оценок</h3>
                <span className="text-sf-label text-sf-muted">нажми — поправить или удалить</span>
            </div>
            <ul>
                {history.map(r => open === `rev-${r.id}`
                    ? <RevisionEdit key={r.id} r={r} busy={busy} only={history.length === 1}
                        onSave={(dto) => void run(() => updateCapitalRevaluation(r.id, dto), () => { setOpen(null); onChanged(); })}
                        onDelete={() => void run(() => deleteCapitalRevaluation(r.id), () => { setOpen(null); onChanged(); }, () => DELETE_FAILED)}
                        onCancel={() => setOpen(null)} />
                    : (
                        <li key={r.id}>
                            <button type="button" onClick={() => toggle(`rev-${r.id}`)}
                                className="flex w-full items-center gap-3 border-b border-sf-rule py-2.5 text-left hover:bg-sf-chip/40">
                                <span className="flex min-w-0 flex-1 flex-col">
                                    <span className="text-sf-body">{fmtDayShort(r.valuedAt)}</span>
                                    {r.note && <span className="truncate text-sf-label text-sf-muted">{r.note}</span>}
                                </span>
                                <span className="whitespace-nowrap text-sf-body tabular-nums">{fmtAmount(r.value)}</span>
                                <Pencil size={14} className="shrink-0 text-sf-muted" />
                            </button>
                        </li>
                    ))}
            </ul>

            <ul>
                <li className="flex min-h-[52px] items-center justify-between border-b border-sf-rule text-sf-body">
                    <span className="text-sf-note text-sf-muted">Что это</span>{item.kind === 'LIABILITY' ? 'Долг' : 'Имущество'}
                </li>
                <TextRow label="Описание" value={item.description ?? ''} open={open === 'description'} busy={busy}
                    onOpen={() => toggle('description')}
                    onSave={v => void run(() => updateCapitalItem(item.id, { name: item.name, description: v }), () => { setOpen(null); onChanged(); })} />
                <TextRow label="Название" value={item.name} open={open === 'name'} busy={busy} required
                    onOpen={() => toggle('name')}
                    onSave={v => void run(() => updateCapitalItem(item.id, { name: v, description: item.description ?? undefined }),
                        () => { setOpen(null); onChanged(); })} />
            </ul>

            {open === 'gone' && (
                <form className="flex flex-col gap-2 rounded-sf-plate bg-sf-plate p-3.5" onSubmit={e => {
                    e.preventDefault();
                    void run(() => addCapitalRevaluation(item.id, { value: 0, valuedAt: goneDate }), () => { onChanged(); onDone(); });
                }}>
                    {/* Не раньше последней оценки: ноль под ней не делает вещь архивной (ревью #241). */}
                    <Labeled label={`${w.gone} — дата`}>
                        <Field type="date" value={goneDate} min={item.lastValuedAt ?? undefined} max={today()}
                            onChange={e => setGoneDate(e.target.value)} />
                    </Labeled>
                    <span className="text-sf-label text-sf-muted">Уйдёт в «Архивные», история останется.</span>
                    <Button type="submit" disabled={busy || !goneDate || (!!item.lastValuedAt && goneDate < item.lastValuedAt)}>{w.gone}</Button>
                </form>
            )}
            {open === 'delete' && (
                <div className="flex flex-col gap-2 rounded-sf-plate bg-sf-plate p-3.5">
                    <span className="text-sf-note">
                        {`Удалить «${item.name}» насовсем? История оценок и вклад в график капитала исчезнут — это не отменить.`
                            + (item.isArchived ? '' : ` Если ${item.kind === 'LIABILITY' ? 'долг погашен' : 'вещь продана'} — лучше «${w.gone}»: история останется.`)}
                    </span>
                    <Button disabled={busy} onClick={() => void run(() => deleteCapitalItem(item.id), () => { onChanged(); onDone(); }, () => DELETE_FAILED)}>
                        Удалить насовсем
                    </Button>
                </div>
            )}
            {error && <p role="alert" className="text-sf-note text-sf-error">{error}</p>}
            <div className="flex gap-2.5">
                {!item.isArchived && <Button variant="soft" className="flex-1 px-3" onClick={() => toggle('gone')}>{w.goneFrom}</Button>}
                <Button variant="soft" className="flex-1 px-3" onClick={() => toggle('delete')}>Удалить насовсем</Button>
            </div>
        </div>
    );
}

function Labeled({ label, children }: { label: string; children: React.ReactNode }) {
    return <label className="flex min-w-0 flex-1 flex-col gap-1.5"><span className="text-sf-note text-sf-muted">{label}</span>{children}</label>;
}

/** «Сколько стоит» и «На дату» — одной строкой. Дата — не позже даты сервера (`latestAllowedIso`). */
function Worth({ w, value, onValue, date, onDate }: {
    w: (typeof WORDS)[CapitalItemKind]; value: string; onValue: (v: string) => void; date: string; onDate: (v: string) => void;
}) {
    return (
        <div className="flex gap-2.5">
            <Labeled label={w.worth}>
                <Field inputMode="decimal" value={value} onChange={e => onValue(e.target.value)} placeholder="0" suffix="₽" />
            </Labeled>
            <Labeled label="На дату">
                <Field type="date" value={date} max={today()} onChange={e => onDate(e.target.value)} />
            </Labeled>
        </div>
    );
}

function RevisionEdit({ r, busy, only, onSave, onDelete, onCancel }: {
    r: CapitalRevaluation; busy: boolean; only: boolean;
    onSave: (dto: { value: number; valuedAt: string; note?: string }) => void; onDelete: () => void; onCancel: () => void;
}) {
    const [value, setValue] = useState(String(r.value));
    const [date, setDate] = useState(r.valuedAt);
    const [note, setNote] = useState(r.note ?? '');
    const [asking, setAsking] = useState(false);
    const dto = revisionUpdate(r, value, date, note);
    return (
        <li className="flex flex-col gap-2 border-b border-sf-rule py-2.5">
            <div className="flex gap-2.5">
                <Field inputMode="decimal" aria-label="Сумма" value={value} onChange={e => setValue(e.target.value)} suffix="₽" />
                <Field type="date" aria-label="Дата" value={date} max={today()} onChange={e => setDate(e.target.value)} />
            </div>
            <Field aria-label="Заметка" value={note} onChange={e => setNote(e.target.value)} placeholder="Заметка" />
            {asking && <span className="text-sf-note">{deleteRevisionAsk(only)}</span>}
            <div className="flex flex-wrap gap-2">
                {asking
                    ? <Button size="s" disabled={busy} onClick={onDelete}>Удалить</Button>
                    : <Button size="s" disabled={busy || !dto} onClick={() => dto && onSave(dto)}>Сохранить</Button>}
                {!asking && <Button size="s" variant="soft" onClick={() => setAsking(true)}>Удалить</Button>}
                <Button size="s" variant="text" onClick={onCancel}>Отмена</Button>
            </div>
        </li>
    );
}

function TextRow({ label, value, open, busy, required = false, onOpen, onSave }: {
    label: string; value: string; open: boolean; busy: boolean; required?: boolean; onOpen: () => void; onSave: (v: string) => void;
}) {
    const [draft, setDraft] = useState(value);
    useEffect(() => { if (open) setDraft(value); }, [open, value]);
    if (open) {
        return (
            <li className="flex flex-col gap-2 border-b border-sf-rule py-2.5">
                <Labeled label={label}><Field autoFocus value={draft} onChange={e => setDraft(e.target.value)} /></Labeled>
                <div className="flex gap-2">
                    <Button size="s" disabled={busy || (required && !draft.trim())} onClick={() => onSave(draft.trim())}>Сохранить</Button>
                    <Button size="s" variant="text" onClick={onOpen}>Отмена</Button>
                </div>
            </li>
        );
    }
    return (
        <li>
            <button type="button" onClick={onOpen}
                className="flex min-h-[52px] w-full items-center justify-between gap-3 border-b border-sf-rule text-left text-sf-body hover:bg-sf-chip/40">
                <span className="text-sf-note text-sf-muted">{label}</span>
                <span className="flex min-w-0 items-center gap-1"><span className="truncate">{value || '—'}</span><ChevronRight size={16} className="shrink-0 text-sf-muted" /></span>
            </button>
        </li>
    );
}
