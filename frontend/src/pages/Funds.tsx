import { useEffect, useState, useCallback } from 'react';
import { cn } from '@/lib/utils';
import { fetchFunds, createFund, updateFund, deleteFund, transferToFund, fetchAccounts } from '../api';
import { confirmQuestion, needsConfirmation, transferFreeLine } from '../lib/transferConfirm';
import { asksWhereMoney, deleteTogether, writeFailure } from '../lib/writeFailure';
import FundMoneyQuestion from '../components/FundMoneyQuestion';
import {
    WHERE_MAIN_SUB, WHERE_OWN_SUB, WHERE_TITLE, accountChoices, fundAccountHint, onAccountWhere, ownAccountChoices,
    ON_MAIN_WHERE, type AccountChoice,
} from '../lib/fundAccount';
import { NZ_ON_ACCOUNT_NOTE, NZ_TITLE, nzOnCardNote, reserveOf } from '../lib/reserve';
import { fmtRub } from '../lib/format';
import NzSheet from '../components/pocket/NzSheet';
import AddCardSheet from '../components/money/AddCardSheet';
import QuickAddModal from '../components/Fab';
import { openUpdateBalances } from '../components/frame/AppFrame';
import { Chip, Choices } from '@/ds';
import type { Account, FundMoney, FundsOverview, TargetFund, PocketResponse } from '../types/api';
import { Plus, ArrowDownToLine, Pencil, Trash2 } from 'lucide-react';
import PocketCard from '../components/PocketCard';
import { fundTarget } from '../lib/fundTarget';
import { inPlanFirst, outOfPlanNote, paceLine } from '../lib/fundPlan';
import { AttemptKeys } from '../lib/attemptKey';
import { Sheet, SheetContent, SheetHeader, SheetTitle, SheetDescription } from '../components/ui/sheet';
import { AmountInput, amountValue } from "../components/ui/amount-input";
import { Input } from '../components/ui/input';
import { Button } from '../components/ui/button';
import { Progress } from '../components/ui/progress';
import { Badge } from '../components/ui/badge';
import { ScrollArea } from '../components/ui/scroll-area';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '../components/ui/select';
import type { PurchaseType } from '../types/api';

/**
 * Выбор счёта, на котором физически лежат деньги цели (ANO-9 §3.3). Пусто — виртуальный
 * конверт, как раньше: свой баланс, пополняется переводом. Выбран счёт — копилка становится
 * целью и датой поверх чужого остатка: собственный баланс перестаёт быть источником правды,
 * а перевод в неё уводит деньги с основной на этот счёт (срез 4, п. 6).
 *
 * <p>Показывается, только когда счетов больше одного: с единственным счётом выбирать нечего,
 * а лишнее поле в форме — плата за возможность, которой ещё нет.
 *
 * <p>Подсказка говорит, что станет с накопленным (ANO-164): у листа правки она знает, была ли копилка
 * на счёте и лежат ли в ней деньги. Слова — `lib/fundAccount.ts`, те же, что на карточке.
 *
 * <p>Счёт, где лежит другая копилка, виден, но недоступен (ANO-203): раньше сервер отвечал на него 409
 * по-английски. Какие счета годятся — `accountChoices`.
 */
function FundAccountPicker({ accounts, funds, selfId, value, onChange, wasLinked = false, holdsOwnMoney = false }: {
    accounts: Account[];
    /** Копилки: по ним видно, какой счёт уже занят. */
    funds: TargetFund[];
    /** Своя копилка в листе правки — свой счёт ей не занят. */
    selfId?: string;
    value: string;
    onChange: (v: string) => void;
    /** Копилка была на счёте до правки. */
    wasLinked?: boolean;
    /** В копилке лежат деньги. */
    holdsOwnMoney?: boolean;
}) {
    const choices = accountChoices(accounts, funds, selfId);
    // Один подходящий счёт — это основная карта: цель поверх неё означала бы «вся моя
    // наличность и есть эта цель», выбирать там нечего.
    if (choices.length <= 1) return null;
    return (
        <div className="space-y-1">
            <label className="text-xs text-muted-foreground">Деньги лежат на счёте</label>
            <Select value={value || 'NONE'} onValueChange={v => onChange(v === 'NONE' ? '' : v)}>
                <SelectTrigger>
                    <SelectValue />
                </SelectTrigger>
                <SelectContent>
                    <SelectItem value="NONE">Виртуальный конверт</SelectItem>
                    {choices.map(c => (
                        <SelectItem key={c.id} value={c.id} disabled={c.taken}>{c.label}</SelectItem>
                    ))}
                </SelectContent>
            </Select>
            {fundAccountHint({ linked: !!value, wasLinked, holdsOwnMoney }).map(line => (
                <p key={line} className="text-xs text-muted-foreground">{line}</p>
            ))}
        </div>
    );
}

type FundPlace = 'account' | 'main';

/**
 * «Где будут лежать деньги» у новой цели (срез 4, п. 20; доска S4-G3). Свой счёт — советуем: в банке на карте будет столько
 * же, сколько в selfin; основная тоже работает — цена одной строкой. Счета — без основной (`ownAccountChoices`); занятый
 * другой копилкой виден, но недоступен; счёта нет — «Новый счёт» заводит накопительный здесь же.
 */
export function FundWhere({ where, onWhere, choices, accountId, onAccount, onAdd }: {
    where: FundPlace;
    onWhere: (w: FundPlace) => void;
    choices: AccountChoice[];
    accountId: string;
    onAccount: (id: string) => void;
    onAdd: () => void;
}) {
    const option = (value: FundPlace, title: string, sub: string, tag?: string) => (
        <label className={cn('flex gap-3 items-start rounded-2xl px-3.5 py-3 cursor-pointer',
            where === value && 'ring-2 ring-[var(--color-accent)]')}
            style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}>
            <input type="radio" name="fund-where" className="mt-1 accent-[var(--color-accent)]"
                checked={where === value} onChange={() => onWhere(value)} />
            <span className="flex flex-col gap-0.5">
                <span className="text-[15px] font-semibold">
                    {title}{tag && <span className="font-normal text-muted-foreground"> — {tag}</span>}
                </span>
                <span className="text-xs text-muted-foreground">{sub}</span>
            </span>
        </label>
    );
    return (
        <div className="space-y-2">
            <p className="text-sm font-semibold">{WHERE_TITLE}</p>
            {option('account', 'На своём счёте', WHERE_OWN_SUB, 'советуем')}
            {where === 'account' && (
                <div className="flex flex-wrap gap-1.5">
                    <Choices name="fund-account" label="Счёт цели" look="chip" className="contents" value={accountId}
                        options={choices.filter(c => !c.taken).map(c => ({ value: c.id, label: c.label }))}
                        onChange={onAccount} />
                    {choices.filter(c => c.taken).map(c => <Chip key={c.id} disabled>{c.label}</Chip>)}
                    <Chip onClick={onAdd}><Plus size={14} strokeWidth={2} />Новый счёт</Chip>
                </div>
            )}
            {option('main', 'На основной карте', WHERE_MAIN_SUB)}
        </div>
    );
}

// ─── Диалог создания фонда ───────────────────────────────────────────────────

export function CreateFundModal({ accounts, funds, onClose, onSuccess, onAccountAdded }: {
    accounts: Account[];
    funds: TargetFund[];
    onClose: () => void;
    onSuccess: () => void;
    /** «Новый счёт» в «Где будут лежать деньги» завёл накопительный — он нужен и в списке страницы. */
    onAccountAdded: (a: Account) => void;
}) {
    const [name, setName] = useState('');
    const [target, setTarget] = useState('');
    const [targetDate, setTargetDate] = useState('');
    const [purchaseType, setPurchaseType] = useState<PurchaseType>('SAVINGS');
    const [creditRate, setCreditRate] = useState('');
    const [creditTermMonths, setCreditTermMonths] = useState('');
    const choices = ownAccountChoices(accounts, funds);
    const [where, setWhere] = useState<FundPlace>('account');
    const [accountId, setAccountId] = useState(() => choices.find(c => !c.taken)?.id ?? '');
    const [adding, setAdding] = useState(false);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        if (purchaseType !== 'CREDIT') {
            setCreditRate('');
            setCreditTermMonths('');
        }
    }, [purchaseType]);

    const handleSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        if (!name.trim()) return;
        setLoading(true);
        setError(null);
        try {
            await createFund({
                name: name.trim(),
                targetAmount: target ? (amountValue(target) ?? undefined) : undefined,   // ANO-33
                targetDate: targetDate || undefined,
                purchaseType,
                creditRate: purchaseType === 'CREDIT' && creditRate ? Number(creditRate) : undefined,
                creditTermMonths: purchaseType === 'CREDIT' && creditTermMonths ? Number(creditTermMonths) : undefined,
                accountId: where === 'account' ? accountId || null : null,
            });
            onSuccess();
            onClose();
        } catch (err) {
            // ANO-203: текст сервера — по-английски; экран говорит, что знает наверняка.
            setError(writeFailure(err));
        } finally { setLoading(false); }
    };

    // Лист добавления встаёт вместо листа цели: два окна разом не открываются, поля цели остаются.
    if (adding) {
        return (
            <AddCardSheet purpose="SAVINGS" categories={[]} onClose={() => setAdding(false)}
                onAdded={(a) => { setAdding(false); onAccountAdded(a); setAccountId(a.id); }} />
        );
    }
    return (
        <Sheet open onOpenChange={open => !open && onClose()}>
            <SheetContent side="bottom" className="max-w-2xl mx-auto rounded-t-2xl">
                <SheetHeader>
                    <SheetTitle>Новый фонд</SheetTitle>
                </SheetHeader>
                <form onSubmit={handleSubmit} className="space-y-3 mt-4">
                    <Input
                        autoFocus
                        placeholder="Название фонда (напр. «Отпуск»)"
                        value={name}
                        onChange={e => setName(e.target.value)}
                    />
                    <AmountInput
                        placeholder="Целевая сумма, ₽ (необязательно)"
                        value={target}
                        onChange={setTarget}
                    />
                    <div className="space-y-1">
                        <label className="text-xs text-muted-foreground">Срок достижения (необязательно)</label>
                        <Input
                            type="date"
                            value={targetDate}
                            onChange={e => setTargetDate(e.target.value)}
                        />
                    </div>
                    <div className="space-y-1">
                        <label className="text-xs text-muted-foreground">Тип покупки</label>
                        <Select value={purchaseType} onValueChange={v => setPurchaseType(v as PurchaseType)}>
                            <SelectTrigger>
                                <SelectValue />
                            </SelectTrigger>
                            <SelectContent>
                                <SelectItem value="SAVINGS">Накопление</SelectItem>
                                <SelectItem value="CREDIT">Кредит</SelectItem>
                            </SelectContent>
                        </Select>
                    </div>
                    {purchaseType === 'CREDIT' && (
                        <>
                            <Input
                                type="number"
                                min="0.01"
                                max="99.99"
                                step="0.01"
                                placeholder="Процентная ставка, % (необязательно)"
                                value={creditRate}
                                onChange={e => setCreditRate(e.target.value)}
                            />
                            <Input
                                type="number"
                                min="1"
                                max="360"
                                step="1"
                                placeholder="Срок кредита, мес. (необязательно)"
                                value={creditTermMonths}
                                onChange={e => setCreditTermMonths(e.target.value)}
                            />
                        </>
                    )}
                    <FundWhere where={where} onWhere={setWhere} choices={choices} accountId={accountId}
                        onAccount={setAccountId} onAdd={() => setAdding(true)} />
                    {error && <p className="text-sm" style={{ color: 'var(--color-danger)' }}>{error}</p>}
                    <Button
                        type="submit"
                        className="w-full"
                        disabled={loading || !name.trim() || (where === 'account' && !accountId)}>
                        {loading ? 'Создаём...' : 'Создать фонд'}
                    </Button>
                </form>
            </SheetContent>
        </Sheet>
    );
}

// ─── Диалог пополнения фонда ─────────────────────────────────────────────────

/**
 * ANO-88, решение владельца 26.09 (вариант А): то же число и то же слово, что на карточке
 * кармашка, — «свободно». Раньше здесь стоял остаток нулевого дня под словом «доступно»,
 * и диалог разрешал вдвое больше, чем карточка называла свободным.
 */
function TransferModal({ fund, pocket, scope, onClose, onSuccess }: {
    fund: TargetFund;
    /** Кармашек с карточки: строка «сколько отложить» и «сегодня» для вопроса; null, пока его нет. */
    pocket: PocketResponse | null;
    /** Горизонт, выбранный на карточке: сервер переспрашивает по нему. */
    scope: string | undefined;
    onClose: () => void;
    onSuccess: () => void;
}) {
    const [amount, setAmount] = useState('');
    const [loading, setLoading] = useState(false);
    /** Отказ перевода словами экрана; лист остаётся открытым, сумма — на месте (ANO-205). */
    const [failure, setFailure] = useState<string | null>(null);
    // ANO-192, ревью #104: попытка живёт, пока лист открыт — повтор после сбоя тот же.
    const [attempts] = useState(() => new AttemptKeys());
    const freeLine = transferFreeLine(pocket);

    const handleSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        const num = amountValue(amount);   // ANO-33: сумма может быть выражением
        // ANO-157: по сумме кармашка больше не отказываем. Достаточно ли денег — знает
        // бэкенд, у которого есть и якоря, и факты; здесь остаётся только «сумма введена».
        if (!num || num <= 0) return;
        setLoading(true);
        setFailure(null);
        try {
            try {
                await transferToFund(attempts, fund.id, num, undefined, scope);
            } catch (err) {
                // Подтверждаемый отказ — единственный, который мы предлагаем отменить.
                // Безусловный («снять больше накопленного») пробрасываем: повтор с confirm
                // упёрся бы в тот же отказ, а вопрос был бы враньём.
                if (!needsConfirmation(err)) throw err;
                if (!confirm(confirmQuestion(err, pocket))) return;
                await transferToFund(attempts, fund.id, num, true, scope);
            }
            onSuccess();
            onClose();
        } catch (err) {
            // ANO-205: раньше лист молчал — кнопка снова активна, и ни слова, записалось ли.
            setFailure(writeFailure(err));
        } finally { setLoading(false); }
    };

    return (
        <Sheet open onOpenChange={open => !open && onClose()}>
            <SheetContent side="bottom" className="max-w-2xl mx-auto rounded-t-2xl">
                <SheetHeader>
                    <SheetTitle>Пополнить фонд</SheetTitle>
                    <SheetDescription>{fund.name}{freeLine ? ` · ${freeLine}` : ''}</SheetDescription>
                </SheetHeader>
                <form onSubmit={handleSubmit} className="space-y-3 mt-4">
                    <AmountInput
                        autoFocus
                        placeholder="Сумма, ₽"
                        value={amount}
                        onChange={setAmount}
                    />
                    {failure && <p className="text-sm" style={{ color: 'var(--color-danger)' }}>{failure}</p>}
                    <Button
                        type="submit"
                        className="w-full"
                        disabled={loading || !amountValue(amount)}>
                        {loading ? 'Переводим...' : 'Перевести'}
                    </Button>
                </form>
            </SheetContent>
        </Sheet>
    );
}

// ─── Диалог редактирования фонда ─────────────────────────────────────────────

function EditFundModal({ fund, accounts, funds, onClose, onSuccess }: {
    fund: TargetFund;
    accounts: Account[];
    funds: TargetFund[];
    onClose: () => void;
    onSuccess: () => void;
}) {
    const [name, setName] = useState(fund.name);
    const [target, setTarget] = useState(fund.targetAmount != null ? String(fund.targetAmount) : '');
    const [targetDate, setTargetDate] = useState(fund.targetDate ?? '');
    const [purchaseType, setPurchaseType] = useState<PurchaseType>(fund.purchaseType ?? 'SAVINGS');
    const [creditRate, setCreditRate] = useState(fund.creditRate != null ? String(fund.creditRate) : '');
    const [creditTermMonths, setCreditTermMonths] = useState(fund.creditTermMonths != null ? String(fund.creditTermMonths) : '');
    const [accountId, setAccountId] = useState(fund.accountId ?? '');
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);
    /** Сервер отказал удалить копилку с деньгами — спрашиваем, что с ними (ANO-198). */
    const [asksMoney, setAsksMoney] = useState(false);
    const [deleteError, setDeleteError] = useState<string | null>(null);

    useEffect(() => {
        if (purchaseType !== 'CREDIT') {
            setCreditRate('');
            setCreditTermMonths('');
        }
    }, [purchaseType]);

    const handleSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        if (!name.trim()) return;
        setLoading(true);
        setError(null);
        try {
            await updateFund(fund.id, {
                name: name.trim(),
                targetAmount: target ? (amountValue(target) ?? undefined) : undefined,   // ANO-33
                targetDate: targetDate || undefined,
                purchaseType,
                creditRate: purchaseType === 'CREDIT' && creditRate ? Number(creditRate) : undefined,
                creditTermMonths: purchaseType === 'CREDIT' && creditTermMonths ? Number(creditTermMonths) : undefined,
                accountId: accountId || null,
            });
            onSuccess();
            onClose();
        } catch (err) {
            // ANO-203: текст сервера — по-английски; экран говорит, что знает наверняка.
            setError(writeFailure(err));
        } finally { setLoading(false); }
    };

    /**
     * ANO-198. Первая попытка — без ответа про деньги: у копилки со счётом своих денег нет, у пустой
     * спрашивать не о чем, и решает это сервер. Копилка с деньгами отвечает 409 — тогда вопрос, а
     * ответ повторяет удаление с ним. Раньше отказ не ловился вовсе: лист молчал.
     *
     * @param money ответ на вопрос «что с деньгами»; нет — первая попытка
     */
    const handleDelete = async (money?: FundMoney) => {
        if (!money && !confirm(`Удалить копилку «${fund.name}»? Это действие нельзя отменить.`)) return;
        setLoading(true);
        setDeleteError(null);
        const failure = await deleteTogether([{ kind: 'FUND', run: () => deleteFund(fund.id, money) }]);
        setLoading(false);
        if (!money && asksWhereMoney(failure)) {
            setAsksMoney(true);
            return;
        }
        if (failure) {
            setDeleteError(failure);
            return;
        }
        onSuccess();
        onClose();
    };

    return (
        <Sheet open onOpenChange={open => !open && onClose()}>
            <SheetContent side="bottom" className="max-w-2xl mx-auto rounded-t-2xl">
                <SheetHeader>
                    <SheetTitle>Редактировать фонд</SheetTitle>
                </SheetHeader>
                <form onSubmit={handleSubmit} className="space-y-3 mt-4">
                    <Input
                        autoFocus
                        placeholder="Название фонда"
                        value={name}
                        onChange={e => setName(e.target.value)}
                    />
                    <AmountInput
                        placeholder="Целевая сумма, ₽ (необязательно)"
                        value={target}
                        onChange={setTarget}
                    />
                    <div className="space-y-1">
                        <label className="text-xs text-muted-foreground">Срок достижения (необязательно)</label>
                        <Input
                            type="date"
                            value={targetDate}
                            onChange={e => setTargetDate(e.target.value)}
                        />
                    </div>
                    <div className="space-y-1">
                        <label className="text-xs text-muted-foreground">Тип покупки</label>
                        <Select value={purchaseType} onValueChange={v => setPurchaseType(v as PurchaseType)}>
                            <SelectTrigger>
                                <SelectValue />
                            </SelectTrigger>
                            <SelectContent>
                                <SelectItem value="SAVINGS">Накопление</SelectItem>
                                <SelectItem value="CREDIT">Кредит</SelectItem>
                            </SelectContent>
                        </Select>
                    </div>
                    {purchaseType === 'CREDIT' && (
                        <>
                            <Input
                                type="number"
                                min="0.01"
                                max="99.99"
                                step="0.01"
                                placeholder="Процентная ставка, % (необязательно)"
                                value={creditRate}
                                onChange={e => setCreditRate(e.target.value)}
                            />
                            <Input
                                type="number"
                                min="1"
                                max="360"
                                step="1"
                                placeholder="Срок кредита, мес. (необязательно)"
                                value={creditTermMonths}
                                onChange={e => setCreditTermMonths(e.target.value)}
                            />
                        </>
                    )}
                    <FundAccountPicker accounts={accounts} funds={funds} selfId={fund.id} value={accountId} onChange={setAccountId}
                        wasLinked={fund.accountId != null} holdsOwnMoney={fund.currentBalance !== 0} />
                    {error && <p className="text-sm" style={{ color: 'var(--color-danger)' }}>{error}</p>}
                    <Button
                        type="submit"
                        className="w-full"
                        disabled={loading || !name.trim()}>
                        {loading ? 'Сохраняем...' : 'Сохранить'}
                    </Button>
                </form>
                {asksMoney ? (
                    <div className="mt-4">
                        <FundMoneyQuestion
                            busy={loading}
                            onAnswer={money => handleDelete(money)}
                            onKeep={() => setAsksMoney(false)} />
                    </div>
                ) : (
                    <Button
                        variant="ghost"
                        className="w-full mt-2 text-destructive hover:text-destructive flex items-center gap-2"
                        onClick={() => handleDelete()}
                        disabled={loading}>
                        <Trash2 size={15} /> Удалить фонд
                    </Button>
                )}
                {deleteError && (
                    <p className="text-sm mt-2" style={{ color: 'var(--color-danger)' }}>{deleteError}</p>
                )}
            </SheetContent>
        </Sheet>
    );
}

// ─── Карточка НЗ ──────────────────────────────────────────────────────────────

/**
 * НЗ — первой в «Целях» (срез 4, п. 19; доски S4-G1, S4-G2). На основной карте — «сколько держать», «Изменить» и мягкое
 * «Перенести на свой счёт»; на своём счёте — остаток счёта, «Отложить» и «Обновить остаток», как у копилки на счёте, а
 * карандаш открывает тот же лист НЗ. Не удаляется — правка «Где лежит» и «Сколько держать» только листом НЗ.
 */
export function ReserveCard({ nz, account, onSheet, onSave }: {
    nz: TargetFund;
    account: Account | undefined;
    /** Лист НЗ; true — открыть сразу с «На своём счёте». */
    onSheet: (toAccount: boolean) => void;
    onSave: (f: TargetFund) => void;
}) {
    const onCard = nz.accountId == null;
    const sum = onCard ? Number(nz.targetAmount ?? 0) : Number(nz.currentBalance);
    return (
        <div className="rounded-2xl p-5 space-y-2"
            style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}>
            <div className="flex justify-between items-start gap-3">
                <h3 className="font-semibold">{NZ_TITLE}</h3>
                <span className="font-semibold">{fmtRub(sum)}</span>
            </div>
            <p className="text-xs" style={{ color: 'var(--color-text-muted)' }}>{onCard ? ON_MAIN_WHERE : onAccountWhere(account)}</p>
            <p className="text-xs" style={{ color: 'var(--color-text-muted)' }}>{onCard ? nzOnCardNote(sum) : NZ_ON_ACCOUNT_NOTE}</p>
            <div className="flex flex-wrap gap-2 pt-1">
                {onCard ? (
                    <>
                        <Button size="sm" variant="outline" onClick={() => onSheet(false)}>Изменить</Button>
                        <Button size="sm" variant="outline" onClick={() => onSheet(true)}>Перенести на свой счёт</Button>
                    </>
                ) : (
                    <>
                        <Button size="sm" onClick={() => onSave(nz)}>Отложить</Button>
                        <Button size="sm" variant="outline" onClick={openUpdateBalances}>Обновить остаток</Button>
                        <Button size="sm" variant="ghost" onClick={() => onSheet(false)} title="Изменить" aria-label="Изменить НЗ">
                            <Pencil size={15} />
                        </Button>
                    </>
                )}
            </div>
        </div>
    );
}

// ─── Карточка фонда ──────────────────────────────────────────────────────────

export function FundCard({ fund, account, onTransfer, onSave, onEdit }: {
    fund: TargetFund;
    /** Счёт, если копилка лежит на нём; нет — виртуальный конверт (ANO-9 §3.3). */
    account: Account | undefined;
    onTransfer: (f: TargetFund) => void;
    /** «Отложить» в копилку на своём счёте — форма «+» с этой копилкой (S4-G4). */
    onSave: (f: TargetFund) => void;
    onEdit: (f: TargetFund) => void;
}) {
    // ANO-199: цель необязательна — без цели процента и полосы нет, «Накоплено» — одна сумма.
    const { pct, amountLine } = fundTarget(fund);
    const reached = fund.status === 'REACHED';
    const note = outOfPlanNote(fund);
    return (
        <div className="rounded-2xl p-5 space-y-3"
            style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}>
            <div className="flex justify-between items-start">
                <div>
                    <h3 className="font-semibold">{fund.name}</h3>
                    {note && (
                        <p className="text-xs" style={{ color: 'var(--color-text-muted)' }}>{note}</p>
                    )}
                    {reached && (
                        <Badge variant="outline" className="text-xs border-green-500/60 text-green-500">Цель достигнута</Badge>
                    )}
                    {fund.accountId && (
                        <p className="text-xs" style={{ color: 'var(--color-text-muted)' }}>{onAccountWhere(account)}</p>
                    )}
                </div>
                <div className="flex items-center gap-1.5">
                    {pct != null && (
                        <span className="text-2xl font-bold" style={{ color: 'var(--color-accent)' }}>{pct}%</span>
                    )}
                    {/* У копилки на счёте перевод — «Отложить» внизу карточки (срез 4, п. 20).
                        По сумме кармашка кнопку НЕ прячем (ANO-157): ноль означает и «денег
                        нет», и «мы про них ничего не знаем», а различает это только бэкенд —
                        у него есть и якоря, и факты. Спрятанная кнопка была стеной без
                        объяснения: человек, ничего не вводивший, не мог отложить вообще. */}
                    {!reached && !fund.accountId && (
                        <Button
                            size="sm"
                            variant="outline"
                            onClick={() => onTransfer(fund)}
                            title="Пополнить из свободных денег">
                            <ArrowDownToLine size={16} />
                        </Button>
                    )}
                    <Button
                        size="sm"
                        variant="ghost"
                        onClick={() => onEdit(fund)}
                        title="Редактировать">
                        <Pencil size={15} />
                    </Button>
                </div>
            </div>
            {pct != null && (
                <Progress
                    value={pct}
                    className={cn("h-2 mt-2", reached && "[&>div]:bg-[var(--color-success)]")}
                />
            )}
            <div className="flex justify-between text-sm">
                <span style={{ color: 'var(--color-text-muted)' }}>Накоплено</span>
                <span className="font-medium">{amountLine}</span>
            </div>
            {fund.targetDate && (
                <p className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
                    Срок: {new Date(fund.targetDate).toLocaleDateString('ru-RU', { day: 'numeric', month: 'long', year: 'numeric' })}
                </p>
            )}
            {fund.estimatedCompletionDate && (
                <p className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
                    {paceLine(fund.estimatedCompletionDate)}
                </p>
            )}
            {/* Копилка на своём счёте (срез 4, п. 20; S4-G1): «Отложить» уводит деньги с основной на её счёт, остаток
                счёта — листом «Обновить остатки». */}
            {fund.accountId && (
                <div className="flex flex-wrap gap-2">
                    {!reached && <Button size="sm" onClick={() => onSave(fund)}>Отложить</Button>}
                    <Button size="sm" variant="outline" onClick={openUpdateBalances}>Обновить остаток</Button>
                </div>
            )}
        </div>
    );
}

// ─── Страница Funds ───────────────────────────────────────────────────────────

export default function Funds({ refreshSignal }: { refreshSignal?: number }) {
    const [data, setData] = useState<FundsOverview | null>(null);
    const [loadFailed, setLoadFailed] = useState(false);
    const [showCreate, setShowCreate] = useState(false);
    const [transferFund, setTransferFund] = useState<TargetFund | null>(null);
    const [editFund, setEditFund] = useState<TargetFund | null>(null);
    const [pocket, setPocket] = useState<PocketResponse | null>(null);
    /** Горизонт карточки кармашка: «Пополнить фонд» переспрашивает по нему (ANO-88). */
    const [pocketScope, setPocketScope] = useState<string | undefined>(undefined);
    // Локальный инкремент для перезагрузки PocketCard после перевода в копилку
    const [pocketBump, setPocketBump] = useState(0);
    const [showFunds, setShowFunds] = useState(true);
    const [accounts, setAccounts] = useState<Account[]>([]);
    /** Лист НЗ: открыт ли и с «На своём счёте» ли (S4-G2). */
    const [nzSheet, setNzSheet] = useState<{ toAccount: boolean } | null>(null);
    /** «Отложить» в копилку на счёте — форма «+» с ней (S4-G4). */
    const [saveTo, setSaveTo] = useState<TargetFund | null>(null);

    const load = useCallback(() => {
        setLoadFailed(false);
        return fetchFunds().then(setData).catch(() => setLoadFailed(true));
    }, []);
    useEffect(() => { load(); }, [load]);
    // Счета нужны, только чтобы предложить копилке лечь на реальный счёт (§3.3) и
    // подписать её карточку — страница без них работает, поэтому сбой не ломает экран.
    // Перечитываются с экраном: «Обновить остаток» меняет день обновления на карточке.
    const loadAccounts = useCallback(() => fetchAccounts().then(setAccounts).catch(() => setAccounts([])), []);
    useEffect(() => { loadAccounts(); }, [loadAccounts, refreshSignal]);

    // Фоновое обновление при добавлении через FAB
    useEffect(() => {
        if (refreshSignal) load();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [refreshSignal]);

    // ANO-203: как в «Стратегии» — что случилось и что сделать; «Ошибка: Failed to fetch» не говорил ни того, ни другого.
    if (loadFailed) return (
        <div className="p-6 text-center">
            <p className="text-sm mb-3">Не удалось загрузить цели</p>
            <Button onClick={() => load()} size="sm">Повторить</Button>
        </div>
    );
    if (!data) return <div className="p-6 text-center animate-pulse" style={{ color: 'var(--color-text-muted)' }}>Загрузка...</div>;
    const nz = reserveOf(data.funds);
    const goals = data.funds.filter(f => !f.reserve);
    const changed = () => { load(); setPocketBump(b => b + 1); };


    return (
        <>
            <ScrollArea>
            <div className="px-4 py-6 space-y-5">
                {/* Кармашек — единый расчёт из GET /pocket (ANO-12) */}
                <PocketCard onData={(p, s) => { setPocket(p); setPocketScope(s); }}
                    refreshSignal={(refreshSignal ?? 0) + pocketBump} />

                {/* Заголовок с кнопкой создания */}
                <div className="flex items-center justify-between">
                    <button
                        onClick={() => setShowFunds(v => !v)}
                        className="flex items-center gap-2 font-semibold">
                        <span>Копилки</span>
                        <span style={{ color: 'var(--color-text-muted)', fontSize: '12px' }}>{showFunds ? '▲' : '▼'}</span>
                    </button>
                    <Button
                        onClick={() => setShowCreate(true)}
                        size="sm"
                        className="flex items-center gap-1.5">
                        <Plus size={16} /> Создать
                    </Button>
                </div>

                {/* НЗ — первой (срез 4, п. 19) */}
                {showFunds && nz && (
                    <ReserveCard nz={nz} account={accounts.find(a => a.id === nz.accountId)}
                        onSheet={toAccount => setNzSheet({ toAccount })} onSave={setSaveTo} />
                )}

                {/* Карточки фондов */}
                {showFunds && (goals.length === 0 ? (
                    <div className="text-center py-10 text-sm" style={{ color: 'var(--color-text-muted)' }}>
                        Нет целевых фондов.<br />Нажми «Создать», чтобы открыть первую копилку!
                    </div>
                ) : (
                    inPlanFirst(goals).map(fund => (
                        <FundCard
                            key={fund.id}
                            fund={fund}
                            account={accounts.find(a => a.id === fund.accountId)}
                            onTransfer={setTransferFund}
                            onSave={setSaveTo}
                            onEdit={setEditFund}
                        />
                    ))
                ))}

            </div>
            </ScrollArea>

            {showCreate && (
                <CreateFundModal
                    accounts={accounts}
                    funds={data.funds}
                    onClose={() => setShowCreate(false)}
                    onSuccess={() => { setShowCreate(false); load(); }}
                    onAccountAdded={a => setAccounts(prev => [...prev, a])}
                />
            )}

            <NzSheet open={nzSheet != null} toAccount={nzSheet?.toAccount} onOpenChange={v => { if (!v) setNzSheet(null); }}
                onSuccess={() => { changed(); loadAccounts(); }} />

            {saveTo && (
                <QuickAddModal preset={{ type: 'FUND_TRANSFER', targetFundId: saveTo.id }}
                    onClose={() => setSaveTo(null)} onSuccess={changed} />
            )}

            {transferFund && (
                <TransferModal
                    fund={transferFund}
                    pocket={pocket}
                    scope={pocketScope}
                    onClose={() => setTransferFund(null)}
                    onSuccess={() => { setTransferFund(null); load(); setPocketBump(b => b + 1); }}
                />
            )}

            {editFund && (
                <EditFundModal
                    fund={editFund}
                    accounts={accounts}
                    funds={data.funds}
                    onClose={() => setEditFund(null)}
                    // ANO-198: удаление с «Вернуть в свободные» двигает свободные деньги, а правка цели
                    // и срока — взносы в копилку. Карточка наверху перечитывается, как после «Пополнить».
                    onSuccess={() => { setEditFund(null); load(); setPocketBump(b => b + 1); }}
                />
            )}
        </>
    );
}
