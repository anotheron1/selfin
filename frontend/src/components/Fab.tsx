import { useEffect, useState } from 'react';
import { ChevronDown, Repeat } from 'lucide-react';
import {
    createPlanWithFact, createStandaloneFact, deleteEvent, fetchCategories, fetchEvents, fetchFactPreview, fetchFunds,
    fetchPocket, transferToFund,
} from '../api';
import type { Category, FactPreview, FinancialEvent, FinancialEventCreateDto, Priority, RecurringFrequency, TargetFund } from '../types/api';
import { Button, Choices, Chip, Field } from '@/ds';
import { amountRawInput, amountValue } from './ui/amount-input';
import { AmountField, DateChips } from './formParts';
import FormSheet from './FormSheet';
import UndoToast from './UndoToast';
import { latestAllowedIso } from '../lib/factDate';
import { fmtDayShort, fmtRub } from '../lib/format';
import { PRIORITY_DOT_CONFIG, PRIORITY_ORDER } from '../lib/priority';
import {
    ON_ACCOUNT_NOTE, SAVINGS_ID, afterLine, canSubmitQuickAdd, categoryChips, effectLine, formButtons, freeEffect, kindOf,
    repeatLabel, repeatOf, transferFundChoice, type FormAction,
} from '../lib/quickAdd';
import { writeFailure } from '../lib/writeFailure';
import { AttemptKeys } from '../lib/attemptKey';

/** Что записано — для строки снизу и «Отменить» (S3-F3). */
interface Written { text: string; undo: () => Promise<unknown> }

/**
 * Форма «+» (срез 3 п. 12, ANO-228; доски Zh2-F8a–c, S3-F1, S3-F4, S3-F8; на 1440 — окном S3-W4). Открывают «+»
 * капсулы, «Записать» полосы (components/frame) и кружок ожидания журнала. Одна сумма; кнопки — по дате; «Накопление» —
 * перевод в копилку. После записи — строка снизу: что записано, что со «Свободно», «Отменить».
 */
export default function QuickAddModal({ onClose, onSuccess, preset }: {
    onClose: () => void;
    onSuccess: () => void;
    /** Кружок ожидания с «+» и «Записать» в листе — форма с категорией ожидания (срез 3, п. 10). */
    preset?: Pick<FinancialEventCreateDto, 'type' | 'categoryId' | 'targetFundId'>;
}) {
    // ANO-155 + ревью #45: умолчание и граница «сегодня» — из одного источника, местной датой, а не UTC.
    // ANO-235: и не позже даты сервера — восточнее Москвы ночью местное «сегодня» для него завтра, и запись получала 400.
    const today = latestAllowedIso(new Date());
    const [type, setType] = useState<'EXPENSE' | 'INCOME'>(preset?.type === 'INCOME' ? 'INCOME' : 'EXPENSE');
    const [categoryId, setCategoryId] = useState<string | undefined>(
        preset?.type === 'FUND_TRANSFER' ? SAVINGS_ID : preset?.categoryId);
    const [fundId, setFundId] = useState<string | undefined>(preset?.targetFundId);
    // ANO-33: сумма — сырой текст: в ней может быть выражение, число — парсером при отправке.
    const [amountRaw, setAmountRaw] = useState('');
    const [description, setDescription] = useState('');
    const [date, setDate] = useState(today);
    // Из кружка ожидания — ожидание: запись встаёт к его строке. Иначе — от категории.
    const [kind, setKind] = useState<Priority>('MEDIUM');
    const [repeat, setRepeat] = useState<{ frequency: RecurringFrequency; endDate: string | null } | null>(null);
    const [repeatOpen, setRepeatOpen] = useState(false);
    const [moreOpen, setMoreOpen] = useState(false);
    const [categories, setCategories] = useState<Category[] | null>(null);
    const [funds, setFunds] = useState<TargetFund[] | null>(null);
    const [pocket, setPocket] = useState<{ checkpointDate: string | null; horizon: { endDate: string; label: string } } | null>(null);
    const [line, setLine] = useState<{ key: string; value: FactPreview | null } | null>(null);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [written, setWritten] = useState<Written | null>(null);
    // ANO-192, ревью #104: попытка живёт, пока открыт быстрый ввод — повтор после сбоя тот же.
    const [attempts] = useState(() => new AttemptKeys());

    useEffect(() => { fetchCategories().then(setCategories).catch(() => setCategories([])); }, []);
    // Для строки о «Свободно»: дата сверки и до какого дня оно считается. Не пришло — строки нет.
    useEffect(() => { fetchPocket().then(p => setPocket({ checkpointDate: p.checkpointDate, horizon: p.horizon })).catch(() => {}); }, []);

    const chips = categoryChips(categories ?? [], type);
    const all = [...chips.top, ...chips.rest];
    const chosen = all.find(c => c.id === categoryId);
    const transfer = chosen?.transfer ?? false;
    const category = categories?.find(c => c.id === categoryId);
    useEffect(() => {
        if (transfer && funds == null) fetchFunds().then(d => setFunds(d.funds)).catch(() => setFunds([]));
    }, [transfer, funds]);
    // ANO-169: копилки на счёте перевода не принимают — в списке их нет, под списком строка почему.
    const { choices: openFunds, onAccount } = transferFundChoice(funds ?? []);
    const fund = openFunds.find(f => f.id === fundId);

    const amount = amountValue(amountRaw);
    const repeatOn = repeat != null && !transfer && date >= today;
    const buttons = formButtons(date, today, repeatOn);
    const main: FormAction = buttons[buttons.length - 1];

    // Строка ожидания, к которой встанет запись, — у сервера (правило п. 1 на клиенте не повторяется).
    const previewKey = main === 'record' && !transfer && category ? `${category.id}|${date}|${type}|${kind}` : null;
    useEffect(() => {
        if (!previewKey || !category) return;
        let gone = false;
        fetchFactPreview({ categoryId: category.id, date, type, priority: kind })
            .then(v => { if (!gone) setLine({ key: previewKey, value: v ?? null }); })
            .catch(() => { if (!gone) setLine(null); });
        return () => { gone = true; };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [previewKey]);
    const lineNow = previewKey && line?.key === previewKey ? line.value : null;
    const canSubmit = canSubmitQuickAdd({ amount, categoryId: chosen && !transfer ? chosen.id : undefined, transfer, fundId: fund?.id });
    // Строка — когда форма собрана: без категории или копилки сказать нечего (стенд PR 4). Над кнопками — по главной,
    // после записи — по нажатой: «Запланировать» рядом с «Записать» держит сумму, строка ожидания ему ни при чём.
    const effectOf = (action: FormAction) => pocket && canSubmit
        && (transfer || action === 'plan' || previewKey == null || line?.key === previewKey)
        ? freeEffect({
            action: transfer && action === 'record' ? 'transfer' : action, type, kind, amount, date, today,
            checkpointDate: pocket.checkpointDate, horizon: pocket.horizon, line: action === 'record' ? lineNow : null,
            fundName: fund?.name,
        })
        : null;
    const effect = effectOf(main);

    const pickCategory = (id: string) => {
        setCategoryId(id);
        // Вид — от категории; у прошлой даты он остаётся тем, что выбран (S3-F1).
        setKind(kindOf(categories?.find(c => c.id === id)));
    };

    const submit = async (action: FormAction) => {
        if (!canSubmit || amount == null) return;
        setLoading(true);
        setError(null);
        try {
            const rawInput = amountRawInput(amountRaw);
            const name = transfer ? (fund?.name ?? '') : (description || chosen?.name || '');
            let undo: () => Promise<unknown>;
            if (transfer && action === 'record') {
                // ANO-169: «Отложить» — тот же перевод, что «Пополнить»: одна запись, копилка растёт. Без вопроса
                // «отложить всё равно?»: человек записывает то, что уже сделал (правило 5).
                await transferToFund(attempts, fundId!, amount!, true, undefined, date);
                // Запись ищется сразу и запоминается: повтор «Отменить» после потерянного ответа снимает её же,
                // а не более ранний такой же перевод (ревью Codex #235).
                const id = await transferEventId(fundId!, amount, date);
                undo = () => (id ? deleteEvent(id) : Promise.reject(new Error('перевод не найден')));
            } else if (action === 'record') {
                const fact = await createStandaloneFact(attempts, {
                    date, categoryId: chosen!.id, type, factAmount: amount, description: description || undefined,
                    priority: kind, rawInput,
                });
                undo = () => deleteEvent(fact.id);
            } else {
                // План; повтор — только у плана, число — из даты формы. У перевода повтора нет.
                const plan = await createPlanWithFact(attempts, transfer
                    ? { date, type: 'FUND_TRANSFER', targetFundId: fundId, plannedAmount: amount, priority: 'MEDIUM' }
                    : {
                        date, categoryId: chosen!.id, type, plannedAmount: amount, priority: kind,
                        description: description || undefined, rawInput,
                        recurring: repeatOn ? repeatOf(repeat!.frequency, date, repeat!.endDate) : null,
                    });
                undo = () => deleteEvent(plan.id, repeatOn ? 'ALL' : 'THIS');
            }
            const after = afterLine(effectOf(action));
            setWritten({
                text: `${action === 'plan' ? 'Запланировано' : 'Записано'}: ${name}, ${fmtRub(amount)}.${after ? ` ${after}` : ''}`,
                undo,
            });
            onSuccess();
        } catch (err) {
            console.error('Ошибка записи:', err);
            // Отказ копилки называется словами (ANO-169), остальное — «Не записалось» (ANO-170).
            setError(writeFailure(err));
        } finally {
            setLoading(false);
        }
    };

    if (written) {
        return <UndoToast text={written.text} onUndo={() => written.undo().then(onSuccess)} onDone={onClose} />;
    }

    const label = (a: FormAction) => (a === 'plan' ? 'Запланировать' : transfer ? 'Отложить' : 'Записать');
    const kinds = PRIORITY_ORDER.map(p => ({ value: p, label: PRIORITY_DOT_CONFIG[p].name }));
    const shownChips = moreOpen ? all : chosen && !chips.top.includes(chosen) ? [...chips.top, chosen] : chips.top;
    const lineText = effectLine(effect);

    return (
        <FormSheet label="Записать" onClose={onClose}>
            <Choices name="type" label="Расход или доход" value={type}
                options={[{ value: 'EXPENSE', label: 'Расход' }, { value: 'INCOME', label: 'Доход' }]}
                onChange={(v) => { setType(v as 'EXPENSE' | 'INCOME'); setCategoryId(undefined); setKind('MEDIUM'); }} />
            <AmountField value={amountRaw} onChange={setAmountRaw} autoFocus />
            <div className="flex flex-wrap gap-1.5">
                {categories == null
                    ? <span className="px-1 text-sf-note text-sf-muted">Загрузка…</span>
                    : (
                        <Choices name="category" label="Категория" look="chip" className="contents" value={categoryId ?? ''}
                            options={shownChips.map(c => ({ value: c.id, label: c.name }))} onChange={pickCategory} />
                    )}
                {chips.rest.length > 0 && (
                    <Chip aria-expanded={moreOpen} onClick={() => setMoreOpen(o => !o)}>
                        {moreOpen ? 'свернуть ▴' : `ещё ${chips.rest.length} ▾`}
                    </Chip>
                )}
            </div>
            {transfer && (
                <>
                    <div className="flex flex-wrap items-center gap-1.5">
                        <span className="px-1 text-sf-note text-sf-muted">в копилку</span>
                        {funds == null
                            ? <span className="text-sf-note text-sf-muted">Загрузка…</span>
                            : <Choices name="fund" label="В какую копилку" look="chip" className="contents" value={fundId ?? ''}
                                options={openFunds.map(f => ({ value: f.id, label: f.name }))} onChange={setFundId} />}
                    </div>
                    {onAccount > 0 && <p className="-mt-1 px-1 text-sf-label text-sf-muted">{ON_ACCOUNT_NOTE}</p>}
                </>
            )}
            {/* У перевода названия нет (ANO-169): журнал называет его именем копилки. */}
            {!transfer && (
                <Field aria-label="Название транзакции (необязательно)" placeholder="Название транзакции (необязательно)"
                    value={description} onChange={(e) => setDescription(e.target.value)} />
            )}
            <DateChips value={date} today={today} onChange={setDate}>
                {/* Повтор — только у плана: у прошлой даты и у перевода его нет (замечание 2.3). */}
                {!transfer && date >= today && (
                    <Chip variant="outline" aria-label={repeatOn ? undefined : 'Повторять: нет'} aria-expanded={repeatOpen}
                        onClick={() => setRepeatOpen(o => !o)} className={repeatOn ? undefined : 'w-11 px-0'}>
                        <Repeat size={16} strokeWidth={1.8} />
                        {repeatOn && <>{repeatLabel(repeat!.frequency, date)}<ChevronDown size={13} strokeWidth={2} /></>}
                    </Chip>
                )}
            </DateChips>
            {repeatOpen && !transfer && date >= today && (
                <RepeatMenu date={date} value={repeat} onChange={setRepeat} onClose={() => setRepeatOpen(false)} />
            )}
            {!transfer && (
                <div aria-label="Вид">
                    <Choices name="kind" label="Вид" size="tile" value={kind} options={kinds} onChange={(v) => setKind(v as Priority)} />
                </div>
            )}
            {lineText && <span role="status" className="px-1 text-sf-note">{lineText}</span>}
            {error && <p className="px-1 text-sf-note text-sf-error">{error}</p>}
            <div className="flex gap-2">
                {buttons.map((a) => (
                    <Button key={a} variant={a === main ? 'primary' : 'soft'} disabled={loading || !canSubmit}
                        className={a === main ? 'flex-[1.6_1_0]' : 'flex-[1_1_0] px-3'} onClick={() => submit(a)}>
                        {loading && a === main ? 'Сохраняем…' : label(a)}
                    </Button>
                ))}
            </div>
        </FormSheet>
    );
}

/**
 * Запись только что сделанного перевода: ручка перевода отвечает копилкой, а не записью, — запись ищется по копилке,
 * сумме и дню, последняя записанная. Удаление перевода забирает деньги и из копилки (ANO-201).
 */
async function transferEventId(fundId: string, amount: number, date: string): Promise<string | undefined> {
    // ponytail: поиск сразу после записи; такой же перевод из другой вкладки в ту же секунду — ответ ручки без id записи
    const mine = (await fetchEvents(date, date))
        .filter((e: FinancialEvent) => e.type === 'FUND_TRANSFER' && e.targetFundId === fundId && e.factAmount === amount);
    return mine.reduce<FinancialEvent | undefined>((a, e) => (!a || (e.createdAt ?? '') > (a.createdAt ?? '') ? e : a), undefined)?.id;
}

/** Меню повтора (S3-F8): не повторять, каждый месяц или год — число из даты; когда закончить. */
function RepeatMenu({ date, value, onChange, onClose }: {
    date: string;
    value: { frequency: RecurringFrequency; endDate: string | null } | null;
    onChange: (v: { frequency: RecurringFrequency; endDate: string | null } | null) => void;
    onClose: () => void;
}) {
    const yearLater = `${Number(date.slice(0, 4)) + 1}${date.slice(4)}`;
    const endLabel = (iso: string) => `${fmtDayShort(iso)} ${iso.slice(0, 4)}`;
    const pick = (v: string) => {
        if (v === 'none') { onChange(null); onClose(); return; }
        onChange({ frequency: v as RecurringFrequency, endDate: value?.endDate ?? null });
    };
    return (
        <div role="group" aria-label="Повторять" className="rounded-[20px] bg-sf-plate p-2">
            <Choices name="repeat" label="Повторять" look="chip" value={value?.frequency ?? 'none'} className="flex-col items-stretch"
                options={[
                    { value: 'none', label: 'Не повторять' },
                    { value: 'MONTHLY', label: repeatLabel('MONTHLY', date) },
                    { value: 'YEARLY', label: repeatLabel('YEARLY', date) },
                ]} onChange={pick} />
            {value && (
                <div className="mt-2 flex flex-wrap items-center justify-between gap-2 border-t border-sf-rule px-1 pt-2.5">
                    <span className="text-sf-control">Закончить</span>
                    <div className="flex flex-wrap items-center gap-1.5">
                        <Choices name="repeat-end" label="Когда закончить" look="chip" className="contents"
                            value={value.endDate == null ? 'never' : 'date'}
                            options={[{ value: 'never', label: 'никогда' }, { value: 'date', label: endLabel(value.endDate ?? yearLater) }]}
                            onChange={(v) => onChange({ ...value, endDate: v === 'never' ? null : yearLater })} />
                        {value.endDate != null && (
                            <input type="date" aria-label="Дата окончания" min={date} value={value.endDate}
                                onChange={(e) => onChange({ ...value, endDate: e.target.value || yearLater })}
                                className="h-sf-chip rounded-sf-pill bg-sf-chip px-3 text-sf-note" />
                        )}
                    </div>
                </div>
            )}
            <p className="px-2 pb-0.5 pt-1.5 text-sf-label text-sf-muted">день берётся из даты — поменяешь дату, поменяется и он</p>
        </div>
    );
}
