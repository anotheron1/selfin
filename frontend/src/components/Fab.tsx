import { useEffect, useState } from 'react';
import { ChevronDown, Copy, Repeat } from 'lucide-react';
import {
    createPlanWithFact, createStandaloneFact, deleteEvent, fetchAccounts, fetchCategories, fetchEvents, fetchFactPreview, fetchFunds,
    fetchPocket, patchEventFact, transferToFund, updateEvent,
} from '../api';
import type {
    Account, Category, FactPreview, FinancialEvent, FinancialEventCreateDto, Priority, RecurringFrequency, ScopeEnum, TargetFund,
} from '../types/api';
import { Button, Choices, Chip, Field, Flag } from '@/ds';
import { amountRawInput, amountValue } from './ui/amount-input';
import { AmountField, DateChips } from './formParts';
import FormSheet from './FormSheet';
import UndoToast from './UndoToast';
import { openUpdateBalances } from './frame/AppFrame';
import { ApiError } from '../api/client';
import { latestAllowedIso } from '../lib/factDate';
import { fmtDayShort, fmtRub } from '../lib/format';
import { PRIORITY_DOT_CONFIG, PRIORITY_ORDER, WISHLIST_CHARACTER_NOTE, characterLocked } from '../lib/priority';
import { dayLabel, editChips, factEditPatch, planEditDto, seriesOf, withCurrent } from '../lib/eventCard';
import { TrashButton, writeDuplicate } from './journal/EventCard';
import {
    CREDIT_OVER_NOTE, ON_MAIN_NOTE, SAVINGS_ID, afterLine, canSubmitQuickAdd, categoryChips, chipOf, creditForm, creditOffered,
    creditWritten, formButtons, formLine, freeEffect, fundReserved, kindOf, repeatLabel, repeatOf, transferFundChoice,
    type FormAction,
} from '../lib/quickAdd';
import { writeFailure } from '../lib/writeFailure';
import { AttemptKeys } from '../lib/attemptKey';

/** Что записано — для строки снизу и «Отменить» (S3-F3); в кредит — ещё «Обновить кредитку» (S4-K2). */
interface Written { text: string; undo: () => Promise<unknown>; credit?: boolean }

const SCOPES = [
    { value: 'THIS', label: 'Только этот' }, { value: 'FOLLOWING', label: 'Этот и дальше' }, { value: 'ALL', label: 'Всю серию' },
];

/**
 * Форма «+» (срез 3 п. 12, ANO-228; доски Zh2-F8a–c, S3-F1, S3-F4, S3-F8; на 1440 — окном S3-W4). Открывают «+»
 * капсулы, «Записать» полосы (components/frame) и кружок ожидания журнала. Одна сумма; кнопки — по дате; «Накопление» —
 * перевод в копилку. После записи — строка снизу: что записано, что со «Свободно», «Отменить».
 *
 * Правка — та же форма, заполненная (срез 3 п. 16; доски S3-E1a, S3-E1b): у записи — дата, категория и вид (п. 2),
 * корзина, «Ещё раз», «Сохранить»; у плана из серии — повтор и «Изменить: Только этот | Этот и дальше | Всю серию».
 */
export default function QuickAddModal({ onClose, onSuccess, preset, edit }: {
    onClose: () => void;
    onSuccess: () => void;
    /** Кружок ожидания с «+» и «Записать» в листе — форма с категорией ожидания (срез 3, п. 10). */
    preset?: Pick<FinancialEventCreateDto, 'type' | 'categoryId' | 'targetFundId'>;
    /** Правка строки; scope — с чем открыта серия: «Изменить этот», «Изменить серию», строка карточки. */
    edit?: { event: FinancialEvent; scope: ScopeEnum };
}) {
    const ev = edit?.event;
    const fact = ev?.eventKind === 'FACT';
    // ANO-155 + ревью #45: умолчание и граница «сегодня» — из одного источника, местной датой, а не UTC.
    // ANO-235: и не позже даты сервера — восточнее Москвы ночью местное «сегодня» для него завтра, и запись получала 400.
    const today = latestAllowedIso(new Date());
    const from = ev?.type ?? preset?.type;
    const [type, setType] = useState<'EXPENSE' | 'INCOME'>(from === 'INCOME' ? 'INCOME' : 'EXPENSE');
    const [categoryId, setCategoryId] = useState<string | undefined>(
        ev ? ev.categoryId : preset?.type === 'FUND_TRANSFER' ? SAVINGS_ID : preset?.categoryId);
    const [fundId, setFundId] = useState<string | undefined>(ev?.targetFundId ?? preset?.targetFundId);
    // ANO-33: сумма — сырой текст: в ней может быть выражение, число — парсером при отправке.
    const [amountRaw, setAmountRaw] = useState(() => {
        const a = fact ? ev?.factAmount : ev?.plannedAmount;
        // Выражение суммы — в поле, если оно и даёт сумму: правка даты или названия его не стирает.
        if (a != null && ev?.rawInput && amountValue(ev.rawInput) === a) return ev.rawInput;
        return a != null ? String(a) : '';
    });
    const [description, setDescription] = useState(ev?.description ?? '');
    const [date, setDate] = useState(ev?.date ?? today);
    // Из кружка ожидания — ожидание: запись встаёт к его строке. Иначе — от категории.
    const [kind, setKind] = useState<Priority>(ev?.priority ?? 'MEDIUM');
    const [repeat, setRepeat] = useState<{ frequency: RecurringFrequency; endDate: string | null } | null>(
        ev?.recurringRuleId && ev.recurringFrequency ? { frequency: ev.recurringFrequency, endDate: ev.recurringEndDate ?? null } : null);
    const [scope, setScope] = useState<ScopeEnum>(edit?.scope ?? 'FOLLOWING');
    const [repeatOpen, setRepeatOpen] = useState(false);
    const [moreOpen, setMoreOpen] = useState(false);
    const [categories, setCategories] = useState<Category[] | null>(null);
    const [funds, setFunds] = useState<TargetFund[] | null>(null);
    const [accounts, setAccounts] = useState<Account[]>([]);
    const [pocket, setPocket] = useState<{
        checkpointDate: string | null; horizon: { endDate: string; label: string }; trajectory: { date: string; balance: number }[];
        /** «На картах» — то же число, что в плитке шапки: больше него — вопрос «Это в кредит?» (п. 17). */
        currentBalance: number;
    } | null>(null);
    // «В кредит» (срез 4, п. 17): у правки — отметка записи.
    const [onCredit, setOnCredit] = useState(ev?.onCredit ?? false);
    const [line, setLine] = useState<{ key: string; value: FactPreview | null } | null>(null);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [written, setWritten] = useState<Written | null>(null);
    // ANO-192, ревью #104: попытка живёт, пока открыт быстрый ввод — повтор после сбоя тот же.
    const [attempts] = useState(() => new AttemptKeys());

    useEffect(() => { fetchCategories().then(setCategories).catch(() => setCategories([])); }, []);
    // Для строки о «Свободно»: дата сверки и до какого дня оно считается. Не пришло — строки нет.
    useEffect(() => {
        fetchPocket().then(p => setPocket({
            checkpointDate: p.checkpointDate, horizon: p.horizon, trajectory: p.trajectory, currentBalance: Number(p.currentBalance),
        })).catch(() => {});
    }, []);

    // Правка — свои чипы (lib/eventCard editChips): у записи к брони и у перевода на виду только своя категория.
    const chips = ev ? editChips(categoryChips(categories ?? [], type), ev, type, categories != null) : categoryChips(categories ?? [], type);
    const all = [...chips.top, ...chips.rest];
    const chosen = chipOf(all, categoryId);
    const transfer = chosen?.transfer ?? false;
    const category = categories?.find(c => c.id === categoryId);
    useEffect(() => {
        if (transfer && funds == null) Promise.all([fetchFunds(), fetchAccounts().catch(() => [])])
            .then(([d, all]) => { setAccounts(all); setFunds(d.funds); })
            .catch(() => setFunds([]));
    }, [transfer, funds]);
    // Копилка на основной перевода не принимает — в списке её нет, под списком строка почему.
    const { choices: openFunds, onMain } = transferFundChoice(funds ?? [], accounts.find(a => a.isDefault)?.id);
    // Правка перевода в копилку, которой в списке нет (достигнута), — она остаётся выбранной.
    const fundChoices = ev ? withCurrent(openFunds, funds ?? [], fundId) : openFunds;
    const fund = fundChoices.find(f => f.id === fundId);
    // Копилка на своём счёте (п. 20, S4-G4): деньги уходят с основной на её счёт.
    const fundAccount = fund?.accountId ? accounts.find(a => a.id === fund.accountId) : undefined;

    const amount = amountValue(amountRaw);
    // У правки повтор — только у серии и не у «Только этот»: план повтором не становится (PUT правила не заводит).
    const series = ev?.recurringRuleId != null && !fact;
    const repeatOn = ev ? series && scope !== 'THIS' : repeat != null && !transfer && date >= today;
    const buttons = formButtons(date, today, repeatOn);
    const main: FormAction = buttons[buttons.length - 1];
    // «В кредит» — у расхода без плана; у новой записи действует только на «Записать» (п. 17).
    const creditShown = creditOffered({ type, transfer, buttons, edit: ev });
    const { credit, asks, over: overCards } = creditForm({
        offered: creditShown, isNew: !ev, record: main === 'record', onCredit, amount, cards: pocket?.currentBalance ?? null,
    });

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
    const canSubmit = canSubmitQuickAdd({ amount, categoryId: chosen && !transfer ? chosen.id : undefined, transfer,
        // У записи-перевода копилка не правится (PATCH её не берёт) и может быть уже удалена — выбор ей не нужен.
        fundId: fact ? fundId : fund?.id });
    // Строка — когда форма собрана: без категории или копилки сказать нечего (стенд PR 4). Над кнопками — по главной,
    // после записи — по нажатой: «Запланировать» рядом с «Записать» держит сумму, строка ожидания ему ни при чём.
    // У правки строки нет: что изменится со «Свободно», в спеке не задано.
    const effectOf = (action: FormAction) => !ev && pocket && canSubmit
        && (transfer || action === 'plan' || previewKey == null || line?.key === previewKey)
        ? freeEffect({
            action: transfer && action === 'record' ? 'transfer' : action, type, kind, amount, date, today,
            checkpointDate: pocket.checkpointDate, horizon: pocket.horizon, line: action === 'record' ? lineNow : null,
            fundName: fund?.name, fundReserved: fundReserved(fund), trajectory: pocket.trajectory, repeat: repeatOn,
        })
        : null;
    const effect = effectOf(main);

    const pickCategory = (id: string) => {
        setCategoryId(id);
        // Вид — от категории; у прошлой даты он остаётся тем, что выбран (S3-F1). У правки вид — строки: смена
        // категории его не трогает, как в прежнем листе правки.
        if (!ev) setKind(kindOf(categories?.find(c => c.id === id)));
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
                // Перевод уже записан: сбой поиска не отправляет форму на повтор — иначе второй перевод (ревью Codex #235).
                const id = await transferEventId(fundId!, amount, date).catch(() => undefined);
                undo = () => (id ? deleteEvent(id) : Promise.reject(new Error('перевод не найден')));
            } else if (action === 'record') {
                const made = await createStandaloneFact(attempts, {
                    date, categoryId: chosen!.id, type, factAmount: amount, description: description || undefined,
                    priority: kind, rawInput, onCredit: credit || undefined,
                });
                undo = () => deleteEvent(made.id);
                // S4-K2: «Свободно» не изменилось, «Обновить кредитку», «Отменить».
                if (credit) {
                    setWritten({ text: creditWritten(name, amount), undo, credit: true });
                    onSuccess();
                    return;
                }
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

    // Правка (пп. 2, 16): запись — PATCH с датой, категорией и видом; план — PUT со своим scope.
    const save = async () => {
        if (!ev || !canSubmit || amount == null) return;
        setLoading(true);
        setError(null);
        try {
            const rawInput = amountRawInput(amountRaw);
            if (fact) {
                await patchEventFact(ev.id, factEditPatch(ev, {
                    amount, description, rawInput, date, categoryId: categoryId!, kind, onCredit: creditShown ? onCredit : undefined,
                }));
            } else {
                await updateEvent(ev.id, planEditDto(ev, {
                    date, categoryId: categoryId!, type, amount, kind, description, rawInput, fundId,
                    endDate: repeat?.endDate ?? null, scope,
                }), series ? scope : 'THIS');
            }
            onSuccess();
            onClose();
        } catch (err) {
            console.error('Ошибка правки:', err);
            setError(writeFailure(err));
        } finally {
            setLoading(false);
        }
    };
    // «Ещё раз» (S3-E1a) — копия записи сегодняшним числом, как «Ещё раз сегодня» в карточке.
    const again = async () => {
        if (!ev) return;
        setLoading(true);
        setError(null);
        try {
            setWritten(await writeDuplicate(ev, today, attempts));
            onSuccess();
        } catch (err) {
            console.error('Ошибка записи:', err);
            setError(writeFailure(err));
        } finally {
            setLoading(false);
        }
    };

    if (written) {
        // 404 — запись уже снята: повтор после потерянного ответа — тоже отмена (ревью Codex #235).
        const undo = () => written.undo().catch(e => { if (!(e instanceof ApiError && e.status === 404)) throw e; });
        return <UndoToast text={written.text} onUndo={() => undo().then(onSuccess)} onDone={onClose}
            onRefresh={written.credit ? () => { openUpdateBalances(); onClose(); } : undefined} />;
    }

    const label = (a: FormAction) => (a === 'plan' ? 'Запланировать' : transfer ? 'Отложить' : 'Записать');
    const kinds = PRIORITY_ORDER.map(p => ({ value: p, label: PRIORITY_DOT_CONFIG[p].name }));
    const shownChips = moreOpen ? all : chosen && !chips.top.includes(chosen) ? [...chips.top, chosen] : chips.top;
    const lineText = formLine({
        isNew: !ev, record: main === 'record', credit, asks, transfer, amount, effect,
        fund: fund && { name: fund.name, account: fundAccount },
    });

    return (
        <FormSheet label={ev ? `${fact ? 'Запись' : 'План'} «${ev.description || ev.categoryName}»` : 'Записать'} onClose={onClose}>
            {ev && (
                <span className="px-1 pr-10 text-sf-note font-semibold text-sf-muted">
                    {fact ? 'Запись' : 'План'} · {series ? 'повторяется' : ev.date ? dayLabel(ev.date, today) : ''}
                </span>
            )}
            {/* Тип у записи и у перевода не правится: PATCH его не берёт, перевод остаётся переводом. */}
            {(!(ev && (fact || ev.type === 'FUND_TRANSFER')) || creditShown) && (
                <div className="flex items-center gap-1.5">
                    {!(ev && (fact || ev.type === 'FUND_TRANSFER')) && (
                        <Choices name="type" label="Расход или доход" value={type} className="min-w-0 flex-1"
                            options={[{ value: 'EXPENSE', label: 'Расход' }, { value: 'INCOME', label: 'Доход' }]}
                            onChange={(v) => { setType(v as 'EXPENSE' | 'INCOME'); setCategoryId(undefined); if (!ev) setKind('MEDIUM'); }} />
                    )}
                    {/* «В кредит» (S4-K1) — справа от «Расход | Доход»; сумма больше, чем на картах, — вопрос, одно касание. */}
                    {creditShown && (
                        <Flag ask={asks} checked={onCredit} onChange={(e) => setOnCredit(e.target.checked)}>
                            {asks ? 'В кредит?' : 'В кредит'}
                        </Flag>
                    )}
                </div>
            )}
            <AmountField value={amountRaw} onChange={setAmountRaw} autoFocus note={overCards ? CREDIT_OVER_NOTE : undefined} />
            <div className="flex flex-wrap gap-1.5">
                {categories == null
                    ? <span className="px-1 text-sf-note text-sf-muted">Загрузка…</span>
                    : (
                        <Choices name="category" label="Категория" look="chip" className="contents" value={chosen?.id ?? ''}
                            options={shownChips.map(c => ({ value: c.id, label: c.name }))} onChange={pickCategory} />
                    )}
                {chips.rest.length > 0 && (
                    <Chip aria-expanded={moreOpen} onClick={() => setMoreOpen(o => !o)}>
                        {moreOpen ? 'свернуть ▴' : `ещё ${chips.rest.length} ▾`}
                    </Chip>
                )}
            </div>
            {transfer && !fact && (
                <>
                    <div className="flex flex-wrap items-center gap-1.5">
                        <span className="px-1 text-sf-note text-sf-muted">в копилку</span>
                        {funds == null
                            ? <span className="text-sf-note text-sf-muted">Загрузка…</span>
                            : <Choices name="fund" label="В какую копилку" look="chip" className="contents" value={fundId ?? ''}
                                options={fundChoices.map(f => ({ value: f.id, label: f.name }))} onChange={setFundId} />}
                    </div>
                    {onMain > 0 && <p className="-mt-1 px-1 text-sf-label text-sf-muted">{ON_MAIN_NOTE}</p>}
                </>
            )}
            {/* У перевода названия нет (ANO-169): журнал называет его именем копилки. */}
            {!transfer && (
                <Field aria-label="Название транзакции (необязательно)" placeholder="Название транзакции (необязательно)"
                    value={description} onChange={(e) => setDescription(e.target.value)} />
            )}
            {/* Дата записи — не позже сегодня (ANO-155); у плана — любая. */}
            <DateChips value={date} today={today} onChange={setDate} max={fact ? today : undefined}>
                {/* Повтор — только у плана: у прошлой даты и у перевода его нет (замечание 2.3); у правки — только у серии. */}
                {(ev ? repeatOn : !transfer && date >= today) && (
                    <Chip variant="outline" aria-label={repeatOn ? undefined : 'Повторять: нет'} aria-expanded={repeatOpen}
                        onClick={() => setRepeatOpen(o => !o)} className={repeatOn ? undefined : 'w-11 px-0'}>
                        <Repeat size={16} strokeWidth={1.8} />
                        {repeatOn && <>{ev && date === ev.date ? seriesOf(ev)!.title : repeatLabel(repeat!.frequency, date)}<ChevronDown size={13} strokeWidth={2} /></>}
                    </Chip>
                )}
            </DateChips>
            {repeatOpen && (ev ? repeatOn : !transfer && date >= today) && (
                <RepeatMenu date={date} value={repeat} onChange={setRepeat} onClose={() => setRepeatOpen(false)} fixed={ev != null} />
            )}
            {/* Вид у перевода-плана правится, как раньше в листе правки; у строки с экрана «Хотелки» — нет (ANO-183). */}
            {(!transfer || (ev && !fact)) && (
                <div aria-label="Вид">
                    <Choices name="kind" label="Вид" size="tile" value={kind} options={kinds} disabled={ev != null && characterLocked(ev)}
                        onChange={(v) => setKind(v as Priority)} />
                    {/* Различие объясняется там, где его выбирают (требование 2.5, ANO-173) — как в листе правки. */}
                    {ev && (
                        <p className="mt-1 px-1 text-sf-label text-sf-muted">
                            {characterLocked(ev) ? WISHLIST_CHARACTER_NOTE : PRIORITY_DOT_CONFIG[kind].hint}
                        </p>
                    )}
                </div>
            )}
            {series && (
                <div className="flex flex-col gap-1.5">
                    <span className="px-1 text-sf-label text-sf-muted">Изменить</span>
                    <Choices name="scope" label="Изменить" value={scope} options={SCOPES} onChange={(v) => setScope(v as ScopeEnum)} />
                </div>
            )}
            {lineText && <span role="status" className="px-1 text-sf-note">{lineText}</span>}
            {error && <p className="px-1 text-sf-note text-sf-error">{error}</p>}
            {ev ? (
                <div className="flex gap-2">
                    <TrashButton event={ev} label={fact ? 'Удалить запись' : 'Удалить план'} disabled={loading}
                        onDeleted={() => { onSuccess(); onClose(); }} onError={setError} />
                    {fact && (
                        <Button variant="soft" className="flex-[1_1_0] px-3" disabled={loading} onClick={again}>
                            <Copy size={17} strokeWidth={1.9} />Ещё раз
                        </Button>
                    )}
                    <Button className="flex-[1.3_1_0]" disabled={loading || !canSubmit} onClick={save}>
                        {loading ? 'Сохраняем…' : 'Сохранить'}
                    </Button>
                </div>
            ) : (
            <div className="flex gap-2">
                {buttons.map((a) => (
                    <Button key={a} variant={a === main ? 'primary' : 'soft'} disabled={loading || !canSubmit}
                        className={a === main ? 'flex-[1.6_1_0]' : 'flex-[1_1_0] px-3'} onClick={() => submit(a)}>
                        {loading && a === main ? 'Сохраняем…' : label(a)}
                    </Button>
                ))}
            </div>
            )}
        </FormSheet>
    );
}

/**
 * Запись только что сделанного перевода: ручка перевода отвечает копилкой, а не записью, — запись ищется по копилке,
 * сумме и дню, последняя записанная. Удаление перевода забирает деньги и из копилки (ANO-201).
 */
export async function transferEventId(fundId: string, amount: number, date: string): Promise<string | undefined> {
    // ponytail: поиск сразу после записи; такой же перевод из другой вкладки в ту же секунду — ответ ручки без id записи
    const mine = (await fetchEvents(date, date))
        .filter((e: FinancialEvent) => e.type === 'FUND_TRANSFER' && e.targetFundId === fundId && e.factAmount === amount);
    return mine.reduce<FinancialEvent | undefined>((a, e) => (!a || (e.createdAt ?? '') > (a.createdAt ?? '') ? e : a), undefined)?.id;
}

/** Меню повтора (S3-F8): не повторять, каждый месяц или год — число из даты; когда закончить. */
function RepeatMenu({ date, value, onChange, onClose, fixed }: {
    date: string;
    value: { frequency: RecurringFrequency; endDate: string | null } | null;
    onChange: (v: { frequency: RecurringFrequency; endDate: string | null } | null) => void;
    onClose: () => void;
    /** Правка серии: частоту и «не повторять» сервер не меняет (applyDtoToRule) — на выбор только конец. */
    fixed?: boolean;
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
                ].filter(o => !fixed || o.value === value?.frequency)} onChange={pick} />
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
