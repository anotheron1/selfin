import { useState } from 'react';
import { Button, Choices, Chip, Field, Plate } from '@/ds';
import { createAccount } from '../../api';
import type { Account, AccountPurpose, Category } from '../../types/api';
import FormSheet from '../FormSheet';
import {
    ANSWERS, WORDS, canSaveCard, cardFormProblem, createDto, creditDebtLine, purposeOf, spendCategories, type CardFormValues,
} from '../../lib/moneyCard';
import { writeFailure } from '../../lib/writeFailure';

/**
 * «Добавить карту» (срез 4, п. 13; S4-A1, S4-A3, S4-A4): один вопрос «Для чего эта карта?», поля — по ответу, остаток —
 * сразу; «+» в группе уже знает ответ и не спрашивает. Запрос — `POST /accounts` с ответом (п. 4). Заменил AccountForm:
 * опись 20–23. Та же форма — «Изменить» в листе карты (CardSheet).
 */

/** «+» в группе (S4-A3): заголовок листа вместо вопроса. */
const PRESET: Record<AccountPurpose, { title: string; sub: string; name: string }> = {
    SPEND: { title: 'Карта для отдельных трат', sub: 'остаток не нужен — selfin его не ведёт', name: 'например, карта для бензина' },
    SAVINGS: { title: 'Накопительный счёт', sub: 'вклад или накопительный счёт: здесь лежит копилка, в «Свободно» не входит', name: 'например, вклад' },
    CREDIT: { title: 'Кредитка', sub: 'не деньги, а долг', name: 'например, кредитка Т-Банка' },
};

/** Сколько категорий видно сразу; остальные — «ещё N». */
const TOP = 6;

export default function AddCardSheet({ purpose, categories, onClose, onAdded }: {
    /** Ответ «+» в группе; без него — вопрос. */
    purpose?: AccountPurpose;
    categories: Category[];
    onClose: () => void;
    /** Карта заведена: лист закрывает страница, экран перечитается сам (CHANGED). */
    onAdded: (account: Account) => void;
}) {
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState<string | null>(null);
    return (
        <FormSheet label={purpose ? PRESET[purpose].title : 'Новая карта'} onClose={onClose}>
            <CardForm account={null} purpose={purpose} categories={categories} busy={busy} error={error}
                onSave={async v => {
                    // ANO-245: то, что сервер отверг бы, лист говорит до отправки; текст сервера — по-английски.
                    const problem = cardFormProblem(v);
                    if (problem || v.purpose == null) { setError(problem); return; }
                    setBusy(true);
                    setError(null);
                    try {
                        onAdded(await createAccount(createDto({ ...v, purpose: v.purpose })));
                    } catch (e) {
                        setError(writeFailure(e));
                        setBusy(false);
                    }
                }} />
        </FormSheet>
    );
}

export type CardFormSave = CardFormValues & { balance: string };

/**
 * Поля карты. Новая — вопрос или ответ группы, остаток сразу; правка — название, ответ заново (кроме основной), категория,
 * лимит и «возвращаю до». Остаток при правке не вводится: это обновление в листе карты.
 */
export function CardForm({ account, purpose: preset, categories, busy, error, onSave, onCancel }: {
    /** Правка этой карты; null — новая. */
    account: Account | null;
    purpose?: AccountPurpose;
    categories: Category[];
    busy: boolean;
    error: string | null;
    onSave: (v: CardFormSave) => void;
    /** Только у правки. */
    onCancel?: () => void;
}) {
    const editing = account != null;
    const [name, setName] = useState(account?.name ?? '');
    const [purpose, setPurpose] = useState<AccountPurpose | null>(preset ?? (account ? purposeOf(account) : null));
    const [categoryId, setCategoryId] = useState<string | null>(account?.purposeCategoryId ?? null);
    const [limit, setLimit] = useState(account?.creditLimit != null ? String(account.creditLimit) : '');
    const [floor, setFloor] = useState(account?.availableFloor != null ? String(account.availableFloor) : '');
    const [balance, setBalance] = useState('');
    const [more, setMore] = useState(false);

    const ask = preset == null && !account?.isDefault;
    const spend = spendCategories(categories);
    const shown = more ? spend : spend.filter((c, i) => i < TOP || c.id === categoryId);
    const category = spend.find(c => c.id === categoryId)?.name;
    const ready = canSaveCard({ name, purpose, categoryId, limit }, editing);
    const head = editing ? { title: 'Изменить карту', sub: '' } : preset ? PRESET[preset] : { title: 'Новая карта', sub: '' };
    const debtLine = editing ? null : creditDebtLine(limit, balance);

    return (
        <form className="flex flex-col gap-3" onSubmit={e => {
            e.preventDefault();
            if (ready) onSave({ name, purpose, categoryId, limit, floor, balance });
        }}>
            <div className="flex flex-col gap-0.5 pr-10">
                <h2 className="text-[20px] font-semibold">{head.title}</h2>
                {head.sub && <span className="text-sf-note text-sf-muted">{head.sub}</span>}
            </div>
            <Labeled label="Название">
                <Field value={name} onChange={e => setName(e.target.value)} placeholder={preset ? PRESET[preset].name : 'например, кредитка Т-Банка'} />
            </Labeled>

            {ask && (
                <div className="flex flex-col">
                    <span className="text-sf-note font-semibold text-sf-muted">Для чего эта карта?</span>
                    <div role="radiogroup" aria-label="Для чего эта карта?" className="flex flex-col">
                        {ANSWERS.map(o => (
                            <label key={o.value} className="flex cursor-pointer items-start gap-3 border-b border-sf-rule py-2.5">
                                <input type="radio" name="card-purpose" value={o.value} checked={purpose === o.value}
                                    onChange={() => setPurpose(o.value)} className="peer sr-only" />
                                <span aria-hidden="true" className="mt-0.5 flex size-[22px] shrink-0 items-center justify-center rounded-full border-2 border-sf-muted peer-checked:border-sf-accent peer-checked:[&>span]:bg-sf-accent peer-focus-visible:ring-2 peer-focus-visible:ring-sf-accent">
                                    <span className="size-2.5 rounded-full" />
                                </span>
                                <span className="flex flex-col peer-checked:font-semibold">
                                    <span className="text-sf-body">{o.label}</span>
                                    <span className="text-sf-label font-normal leading-[1.35] text-sf-muted">{o.hint}</span>
                                </span>
                            </label>
                        ))}
                    </div>
                </div>
            )}

            {purpose === 'SPEND' && (
                <div className="flex flex-col gap-1.5">
                    <span className="text-sf-note text-sf-muted">Для каких трат</span>
                    <div className="flex flex-wrap gap-1.5">
                        <Choices name="card-category" label="Для каких трат" look="chip" value={categoryId ?? ''}
                            options={shown.map(c => ({ value: c.id, label: c.name }))} onChange={setCategoryId} />
                        {!more && spend.length > shown.length && (
                            <Chip onClick={() => setMore(true)}>{`ещё ${spend.length - shown.length} ▾`}</Chip>
                        )}
                    </div>
                    {!editing && (
                        <Plate className="text-sf-note">
                            {`Перевёл на неё 5 000 ₽ — запиши одну трату ${category ? `«${category}»` : 'её категории'} на 5 000 ₽. Покупки по отдельности не нужны.`}
                        </Plate>
                    )}
                </div>
            )}

            {!editing && purpose === 'SAVINGS' && (
                <Labeled label={WORDS.savingsNow}>
                    <Field inputMode="decimal" value={balance} onChange={e => setBalance(e.target.value)} placeholder="0" suffix="₽" />
                </Labeled>
            )}

            {purpose === 'CREDIT' && (
                <>
                    <div className="flex gap-2.5">
                        <Labeled label={WORDS.limit}>
                            <Field inputMode="decimal" value={limit} onChange={e => setLimit(e.target.value)} placeholder="0" suffix="₽" />
                        </Labeled>
                        {!editing && (
                            <Labeled label={WORDS.available}>
                                <Field inputMode="decimal" value={balance} onChange={e => setBalance(e.target.value)} placeholder="0" suffix="₽" />
                            </Labeled>
                        )}
                    </div>
                    <Labeled label={WORDS.floor}>
                        <Field inputMode="decimal" value={floor} onChange={e => setFloor(e.target.value)} placeholder="не задано" suffix="₽" />
                    </Labeled>
                    <span className="px-1 text-sf-label text-sf-muted">{editing ? WORDS.floorNote : WORDS.floorAddNote}</span>
                    {debtLine && <span role="status" className="px-1 text-sf-note">{debtLine}</span>}
                </>
            )}

            {error && <p role="alert" className="text-sf-note text-sf-error">{error}</p>}
            {editing ? (
                <div className="flex gap-2">
                    <Button type="submit" disabled={busy || !ready}>Сохранить</Button>
                    <Button variant="text" onClick={onCancel}>Отмена</Button>
                </div>
            ) : <Button type="submit" disabled={busy || !ready}>Добавить</Button>}
            {!editing && preset && <span className="px-1 text-sf-label text-sf-muted">{WORDS.wrongGroup}</span>}
        </form>
    );
}

export function Labeled({ label, children }: { label: string; children: React.ReactNode }) {
    return <label className="flex min-w-0 flex-1 flex-col gap-1.5"><span className="text-sf-note text-sf-muted">{label}</span>{children}</label>;
}
