import { useEffect, useState, useCallback } from 'react';
import { cn } from '@/lib/utils';
import { fetchFunds, createFund, updateFund, deleteFund, transferToFund, fetchAccounts } from '../api';
import type { Account, FundsOverview, TargetFund, PocketResponse } from '../types/api';
import { Plus, ArrowDownToLine, Pencil, Trash2 } from 'lucide-react';
import PocketCard from '../components/PocketCard';
import { fmtRub } from '../lib/format';
import { Sheet, SheetContent, SheetHeader, SheetTitle, SheetDescription } from '../components/ui/sheet';
import { AmountInput, amountValue } from "../components/ui/amount-input";
import { Input } from '../components/ui/input';
import { Button } from '../components/ui/button';
import { Progress } from '../components/ui/progress';
import { Badge } from '../components/ui/badge';
import { ScrollArea } from '../components/ui/scroll-area';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '../components/ui/select';
import type { PurchaseType } from '../types/api';

const fmt = (n: number | null) => (n != null ? fmtRub(n) : '∞');

/**
 * Выбор счёта, на котором физически лежат деньги цели (ANO-9 §3.3). Пусто — виртуальный
 * конверт, как раньше: свой баланс, пополняется переводом. Выбран счёт — копилка становится
 * целью и датой поверх чужого остатка: собственный баланс перестаёт быть источником правды,
 * а перевод в неё запрещён, потому что деньги двигаются на самом счёте.
 *
 * <p>Показывается, только когда счетов больше одного: с единственным счётом выбирать нечего,
 * а лишнее поле в форме — плата за возможность, которой ещё нет.
 */
function FundAccountPicker({ accounts, value, onChange }: {
    accounts: Account[];
    value: string;
    onChange: (v: string) => void;
}) {
    // Кредитка и конверт без слежения отсеиваются: на кредитке «накоплено» показало бы
    // неизрасходованный лимит, а у конверта остаток не подтверждён ничем. Бэкенд обе
    // привязки отвергает 400 — не предлагаем то, что заведомо не сохранится.
    const eligible = accounts.filter(a => a.trackBalance && a.kind !== 'CREDIT');
    // Один подходящий счёт — это основная карта: цель поверх неё означала бы «вся моя
    // наличность и есть эта цель», выбирать там нечего.
    if (eligible.length <= 1) return null;
    return (
        <div className="space-y-1">
            <label className="text-xs text-muted-foreground">Деньги лежат на счёте</label>
            <Select value={value || 'NONE'} onValueChange={v => onChange(v === 'NONE' ? '' : v)}>
                <SelectTrigger>
                    <SelectValue />
                </SelectTrigger>
                <SelectContent>
                    <SelectItem value="NONE">Виртуальный конверт</SelectItem>
                    {eligible.map(a => (
                        <SelectItem key={a.id} value={a.id}>{a.name}</SelectItem>
                    ))}
                </SelectContent>
            </Select>
            <p className="text-xs text-muted-foreground">
                {value
                    ? 'Накопленное берётся с остатка счёта. Пополнять переводом нельзя — двигай деньги на счёте и обновляй его остаток.'
                    : 'Копилка держит свой баланс и пополняется переводом из кармашка.'}
            </p>
        </div>
    );
}

// ─── Диалог создания фонда ───────────────────────────────────────────────────

function CreateFundModal({ accounts, onClose, onSuccess }: {
    accounts: Account[];
    onClose: () => void;
    onSuccess: () => void;
}) {
    const [name, setName] = useState('');
    const [target, setTarget] = useState('');
    const [targetDate, setTargetDate] = useState('');
    const [purchaseType, setPurchaseType] = useState<PurchaseType>('SAVINGS');
    const [creditRate, setCreditRate] = useState('');
    const [creditTermMonths, setCreditTermMonths] = useState('');
    const [accountId, setAccountId] = useState('');
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
                accountId: accountId || null,
            });
            onSuccess();
            onClose();
        } catch (err) {
            setError((err as Error).message);
        } finally { setLoading(false); }
    };

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
                    <FundAccountPicker accounts={accounts} value={accountId} onChange={setAccountId} />
                    {error && <p className="text-sm" style={{ color: 'var(--color-danger)' }}>{error}</p>}
                    <Button
                        type="submit"
                        className="w-full"
                        disabled={loading || !name.trim()}>
                        {loading ? 'Создаём...' : 'Создать фонд'}
                    </Button>
                </form>
            </SheetContent>
        </Sheet>
    );
}

// ─── Диалог пополнения фонда ─────────────────────────────────────────────────

function TransferModal({ fund, pocketBalance, onClose, onSuccess }: {
    fund: TargetFund;
    pocketBalance: number;
    onClose: () => void;
    onSuccess: () => void;
}) {
    const [amount, setAmount] = useState('');
    const [loading, setLoading] = useState(false);

    const handleSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        const num = amountValue(amount);   // ANO-33: сумма может быть выражением
        if (!num || num <= 0 || num > pocketBalance) return;
        setLoading(true);
        try {
            await transferToFund(fund.id, num);
            onSuccess();
            onClose();
        } finally { setLoading(false); }
    };

    return (
        <Sheet open onOpenChange={open => !open && onClose()}>
            <SheetContent side="bottom" className="max-w-2xl mx-auto rounded-t-2xl">
                <SheetHeader>
                    <SheetTitle>Пополнить фонд</SheetTitle>
                    <SheetDescription>{fund.name} · доступно {fmt(pocketBalance)}</SheetDescription>
                </SheetHeader>
                <form onSubmit={handleSubmit} className="space-y-3 mt-4">
                    <AmountInput
                        autoFocus
                        placeholder="Сумма, ₽"
                        value={amount}
                        onChange={setAmount}
                    />
                    <Button
                        type="submit"
                        className="w-full"
                        disabled={loading || !amountValue(amount)
                            || (amountValue(amount) ?? 0) > pocketBalance}>
                        {loading ? 'Переводим...' : 'Перевести'}
                    </Button>
                </form>
            </SheetContent>
        </Sheet>
    );
}

// ─── Диалог редактирования фонда ─────────────────────────────────────────────

function EditFundModal({ fund, accounts, onClose, onSuccess }: {
    fund: TargetFund;
    accounts: Account[];
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
            setError((err as Error).message);
        } finally { setLoading(false); }
    };

    const handleDelete = async () => {
        if (!confirm(`Удалить копилку «${fund.name}»? Это действие нельзя отменить.`)) return;
        setLoading(true);
        try {
            await deleteFund(fund.id);
            onSuccess();
            onClose();
        } finally { setLoading(false); }
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
                    <FundAccountPicker accounts={accounts} value={accountId} onChange={setAccountId} />
                    {error && <p className="text-sm" style={{ color: 'var(--color-danger)' }}>{error}</p>}
                    <Button
                        type="submit"
                        className="w-full"
                        disabled={loading || !name.trim()}>
                        {loading ? 'Сохраняем...' : 'Сохранить'}
                    </Button>
                </form>
                <Button
                    variant="ghost"
                    className="w-full mt-2 text-destructive hover:text-destructive flex items-center gap-2"
                    onClick={handleDelete}
                    disabled={loading}>
                    <Trash2 size={15} /> Удалить фонд
                </Button>
            </SheetContent>
        </Sheet>
    );
}

// ─── Карточка фонда ──────────────────────────────────────────────────────────

function FundCard({ fund, pocketBalance, accountName, onTransfer, onEdit }: {
    fund: TargetFund;
    pocketBalance: number;
    /** Имя счёта, если копилка лежит на нём; null — виртуальный конверт (ANO-9 §3.3). */
    accountName: string | null;
    onTransfer: (f: TargetFund) => void;
    onEdit: (f: TargetFund) => void;
}) {
    const pct = fund.targetAmount
        ? Math.min(Math.round((fund.currentBalance / fund.targetAmount) * 100), 100)
        : 100;
    const reached = fund.status === 'REACHED';
    return (
        <div className="rounded-2xl p-5 space-y-3"
            style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}>
            <div className="flex justify-between items-start">
                <div>
                    <h3 className="font-semibold">{fund.name}</h3>
                    {reached && (
                        <Badge variant="outline" className="text-xs border-green-500/60 text-green-500">Цель достигнута</Badge>
                    )}
                    {fund.accountId && (
                        <p className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
                            Лежит на счёте{accountName ? `: ${accountName}` : ''}
                        </p>
                    )}
                </div>
                <div className="flex items-center gap-1.5">
                    <span className="text-2xl font-bold" style={{ color: 'var(--color-accent)' }}>{pct}%</span>
                    {/* У копилки на счёте перевода нет: деньги двигаются на самом счёте, а
                        перевод создал бы вторую запись за те же рубли (бэкенд вернёт 400). */}
                    {!reached && !fund.accountId && pocketBalance > 0 && (
                        <Button
                            size="sm"
                            variant="outline"
                            onClick={() => onTransfer(fund)}
                            title="Пополнить из кармашка">
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
            {fund.targetAmount && (
                <Progress
                    value={Math.min(100, (fund.currentBalance / fund.targetAmount) * 100)}
                    className={cn("h-2 mt-2", reached && "[&>div]:bg-[var(--color-success)]")}
                />
            )}
            <div className="flex justify-between text-sm">
                <span style={{ color: 'var(--color-text-muted)' }}>Накоплено</span>
                <span className="font-medium">{fmt(fund.currentBalance)} / {fmt(fund.targetAmount)}</span>
            </div>
            {fund.targetDate && (
                <p className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
                    Срок: {new Date(fund.targetDate).toLocaleDateString('ru-RU', { day: 'numeric', month: 'long', year: 'numeric' })}
                </p>
            )}
            {fund.estimatedCompletionDate && (
                <p className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
                    Прогноз: {new Date(fund.estimatedCompletionDate).toLocaleDateString('ru-RU', { month: 'long', year: 'numeric' })}
                </p>
            )}
        </div>
    );
}

// ─── Страница Funds ───────────────────────────────────────────────────────────

export default function Funds({ refreshSignal }: { refreshSignal?: number }) {
    const [data, setData] = useState<FundsOverview | null>(null);
    const [error, setError] = useState<string | null>(null);
    const [showCreate, setShowCreate] = useState(false);
    const [transferFund, setTransferFund] = useState<TargetFund | null>(null);
    const [editFund, setEditFund] = useState<TargetFund | null>(null);
    const [pocket, setPocket] = useState<PocketResponse | null>(null);
    // Локальный инкремент для перезагрузки PocketCard после перевода в копилку
    const [pocketBump, setPocketBump] = useState(0);
    const [showFunds, setShowFunds] = useState(true);
    const [accounts, setAccounts] = useState<Account[]>([]);

    const load = useCallback(() => {
        setError(null);
        return fetchFunds().then(setData).catch((err: Error) => setError(err.message));
    }, []);
    useEffect(() => { load(); }, [load]);
    // Счета нужны, только чтобы предложить копилке лечь на реальный счёт (§3.3) и
    // подписать её карточку — страница без них работает, поэтому сбой не ломает экран.
    useEffect(() => { fetchAccounts().then(setAccounts).catch(() => setAccounts([])); }, []);

    // Фоновое обновление при добавлении через FAB
    useEffect(() => {
        if (refreshSignal) load();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [refreshSignal]);

    if (error) return <div className="p-6 text-center text-sm" style={{ color: 'var(--color-danger, #ef4444)' }}>Ошибка: {error}</div>;
    if (!data) return <div className="p-6 text-center animate-pulse" style={{ color: 'var(--color-text-muted)' }}>Загрузка...</div>;

    // «Доступно сейчас» для переводов = день 0 траектории кармашка:
    // деньги, не занятые прямо сейчас (баланс − просрочка − плановые расходы сегодня)
    const availableNow = pocket?.trajectory[0]?.balance ?? 0;

    return (
        <>
            <ScrollArea className="h-[calc(100dvh-var(--nav-height))]">
            <div className="px-4 py-6 space-y-5">
                {/* Кармашек — единый расчёт из GET /pocket (ANO-12) */}
                <PocketCard onData={setPocket} refreshSignal={(refreshSignal ?? 0) + pocketBump} />

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

                {/* Карточки фондов */}
                {showFunds && (data.funds.length === 0 ? (
                    <div className="text-center py-10 text-sm" style={{ color: 'var(--color-text-muted)' }}>
                        Нет целевых фондов.<br />Нажми «Создать», чтобы открыть первую копилку!
                    </div>
                ) : (
                    data.funds.map(fund => (
                        <FundCard
                            key={fund.id}
                            fund={fund}
                            pocketBalance={availableNow}
                            accountName={accounts.find(a => a.id === fund.accountId)?.name ?? null}
                            onTransfer={setTransferFund}
                            onEdit={setEditFund}
                        />
                    ))
                ))}

            </div>
            </ScrollArea>

            {showCreate && (
                <CreateFundModal
                    accounts={accounts}
                    onClose={() => setShowCreate(false)}
                    onSuccess={() => { setShowCreate(false); load(); }}
                />
            )}

            {transferFund && (
                <TransferModal
                    fund={transferFund}
                    pocketBalance={availableNow}
                    onClose={() => setTransferFund(null)}
                    onSuccess={() => { setTransferFund(null); load(); setPocketBump(b => b + 1); }}
                />
            )}

            {editFund && (
                <EditFundModal
                    fund={editFund}
                    accounts={accounts}
                    onClose={() => setEditFund(null)}
                    onSuccess={() => { setEditFund(null); load(); }}
                />
            )}
        </>
    );
}
