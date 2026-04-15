import { useEffect, useState, useCallback } from 'react';
import { cn } from '@/lib/utils';
import { fetchFunds, createFund, updateFund, deleteFund, transferToFund, fetchEvents } from '../api';
import type { FundsOverview, TargetFund, FinancialEvent } from '../types/api';
import { Wallet, Plus, ArrowDownToLine, Pencil, Trash2, HelpCircle } from 'lucide-react';
import { Sheet, SheetContent, SheetHeader, SheetTitle, SheetDescription } from '../components/ui/sheet';
import { Input } from '../components/ui/input';
import { Button } from '../components/ui/button';
import { Progress } from '../components/ui/progress';
import { Badge } from '../components/ui/badge';
import { ScrollArea } from '../components/ui/scroll-area';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '../components/ui/select';
import WishlistSection from '../components/WishlistSection';
import SavingsStrategySection from '../components/funds/SavingsStrategySection';
import type { PurchaseType } from '../types/api';

const fmt = (n: number | null) =>
    n != null
        ? new Intl.NumberFormat('ru-RU', { style: 'currency', currency: 'RUB', minimumFractionDigits: 0 }).format(n)
        : '∞';

// ─── Диалог создания фонда ───────────────────────────────────────────────────

function CreateFundModal({ onClose, onSuccess }: { onClose: () => void; onSuccess: () => void }) {
    const [name, setName] = useState('');
    const [target, setTarget] = useState('');
    const [targetDate, setTargetDate] = useState('');
    const [purchaseType, setPurchaseType] = useState<PurchaseType>('SAVINGS');
    const [creditRate, setCreditRate] = useState('');
    const [creditTermMonths, setCreditTermMonths] = useState('');
    const [loading, setLoading] = useState(false);

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
        try {
            await createFund({
                name: name.trim(),
                targetAmount: target ? Number(target) : undefined,
                targetDate: targetDate || undefined,
                purchaseType,
                creditRate: purchaseType === 'CREDIT' && creditRate ? Number(creditRate) : undefined,
                creditTermMonths: purchaseType === 'CREDIT' && creditTermMonths ? Number(creditTermMonths) : undefined,
            });
            onSuccess();
            onClose();
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
                    <Input
                        type="number"
                        placeholder="Целевая сумма, ₽ (необязательно)"
                        value={target}
                        onChange={e => setTarget(e.target.value)}
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
        const num = Number(amount);
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
                    <Input
                        autoFocus
                        type="number"
                        placeholder="Сумма, ₽"
                        max={pocketBalance}
                        value={amount}
                        onChange={e => setAmount(e.target.value)}
                    />
                    <Button
                        type="submit"
                        className="w-full"
                        disabled={loading || !amount || Number(amount) <= 0 || Number(amount) > pocketBalance}>
                        {loading ? 'Переводим...' : 'Перевести'}
                    </Button>
                </form>
            </SheetContent>
        </Sheet>
    );
}

// ─── Диалог редактирования фонда ─────────────────────────────────────────────

function EditFundModal({ fund, onClose, onSuccess }: {
    fund: TargetFund;
    onClose: () => void;
    onSuccess: () => void;
}) {
    const [name, setName] = useState(fund.name);
    const [target, setTarget] = useState(fund.targetAmount != null ? String(fund.targetAmount) : '');
    const [targetDate, setTargetDate] = useState(fund.targetDate ?? '');
    const [purchaseType, setPurchaseType] = useState<PurchaseType>(fund.purchaseType ?? 'SAVINGS');
    const [creditRate, setCreditRate] = useState(fund.creditRate != null ? String(fund.creditRate) : '');
    const [creditTermMonths, setCreditTermMonths] = useState(fund.creditTermMonths != null ? String(fund.creditTermMonths) : '');
    const [loading, setLoading] = useState(false);

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
        try {
            await updateFund(fund.id, {
                name: name.trim(),
                targetAmount: target ? Number(target) : undefined,
                targetDate: targetDate || undefined,
                purchaseType,
                creditRate: purchaseType === 'CREDIT' && creditRate ? Number(creditRate) : undefined,
                creditTermMonths: purchaseType === 'CREDIT' && creditTermMonths ? Number(creditTermMonths) : undefined,
            });
            onSuccess();
            onClose();
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
                    <Input
                        type="number"
                        placeholder="Целевая сумма, ₽ (необязательно)"
                        value={target}
                        onChange={e => setTarget(e.target.value)}
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

function FundCard({ fund, pocketBalance, onTransfer, onEdit }: {
    fund: TargetFund;
    pocketBalance: number;
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
                </div>
                <div className="flex items-center gap-1.5">
                    <span className="text-2xl font-bold" style={{ color: 'var(--color-accent)' }}>{pct}%</span>
                    {!reached && pocketBalance > 0 && (
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
    type ProjectionSet = {
        endOfMonth: number;
        end3Month: number;
        end6Month: number;
        endOfPlans: number | null;
        lastPlanDate: string | null;
    };
    const [projections, setProjections] = useState<{
        mandatory: ProjectionSet; // факт + все доходы − только обязательные расходы
        full: ProjectionSet;      // факт + все доходы − все расходы
    } | null>(null);
    const [showHelp, setShowHelp] = useState(false);
    const [showFunds, setShowFunds] = useState(true);

    const load = useCallback(() => {
        setError(null);
        return fetchFunds().then(setData).catch((err: Error) => setError(err.message));
    }, []);
    useEffect(() => { load(); }, [load]);

    // Фоновое обновление при добавлении через FAB
    useEffect(() => {
        if (refreshSignal) load();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [refreshSignal]);

    useEffect(() => {
        if (!data) return;
        const today = new Date();
        const localDate = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
        const todayStr = localDate(today);
        const monthEnd = localDate(new Date(today.getFullYear(), today.getMonth() + 1, 0));
        const farFuture = localDate(new Date(today.getTime() + 730 * 24 * 60 * 60 * 1000));

        const month3End = localDate(new Date(today.getFullYear(), today.getMonth() + 3, 0));
        const month6End = localDate(new Date(today.getFullYear(), today.getMonth() + 6, 0));

        fetchEvents(todayStr, farFuture).then(allFutureEvts => {
            const planned = allFutureEvts.filter(e => e.status === 'PLANNED');

            // Обязательные = высокий приоритет или явный флаг mandatory
            const isMandatory = (e: FinancialEvent) =>
                e.mandatory === true || e.priority === 'HIGH';

            // Факт + все доходы − все расходы (включая необязательные и копилки)
            const projectFull = (cutoff: string) =>
                planned
                    .filter(e => e.date != null && e.date <= cutoff)
                    .reduce((bal, e) => {
                        const amt = e.plannedAmount ?? 0;
                        return e.type === 'INCOME' ? bal + amt : bal - amt;
                    }, data.pocketBalance);

            // Факт + все доходы − только обязательные расходы (необязательные и копилки пропускаем)
            const projectMandatory = (cutoff: string) =>
                planned
                    .filter(e => e.date != null && e.date <= cutoff)
                    .reduce((bal, e) => {
                        const amt = e.plannedAmount ?? 0;
                        if (e.type === 'INCOME') return bal + amt;
                        if (e.type === 'EXPENSE' && isMandatory(e)) return bal - amt;
                        return bal;
                    }, data.pocketBalance);

            const lastPlanDate = planned.length > 0
                ? (planned.map(e => e.date).filter(Boolean).sort().at(-1) ?? null)
                : null;

            setProjections({
                mandatory: {
                    endOfMonth: projectMandatory(monthEnd),
                    end3Month: projectMandatory(month3End),
                    end6Month: projectMandatory(month6End),
                    endOfPlans: lastPlanDate ? projectMandatory(farFuture) : null,
                    lastPlanDate,
                },
                full: {
                    endOfMonth: projectFull(monthEnd),
                    end3Month: projectFull(month3End),
                    end6Month: projectFull(month6End),
                    endOfPlans: lastPlanDate ? projectFull(farFuture) : null,
                    lastPlanDate,
                },
            });
        }).catch(() => {
            // Projection fetch failed — leave projections null (hidden)
        });
    }, [data]);

    if (error) return <div className="p-6 text-center text-sm" style={{ color: 'var(--color-danger, #ef4444)' }}>Ошибка: {error}</div>;
    if (!data) return <div className="p-6 text-center animate-pulse" style={{ color: 'var(--color-text-muted)' }}>Загрузка...</div>;

    const fmtDate = (s: string) =>
        new Date(s + 'T00:00:00').toLocaleDateString('ru-RU', { day: 'numeric', month: 'short', year: 'numeric' });

    return (
        <>
            <ScrollArea className="h-[calc(100dvh-var(--nav-height))]">
            <div className="px-4 py-6 space-y-5">
                {/* Кармашек */}
                <div className="rounded-2xl p-6 flex items-center justify-between"
                    style={{ background: 'linear-gradient(135deg, var(--color-accent) 0%, #9f8cff 100%)' }}>
                    <div className="flex items-start gap-4 flex-1 min-w-0">
                        <Wallet size={32} color="white" className="shrink-0 mt-1" />
                        <div className="min-w-0 flex-1">
                            <div className="flex items-center gap-1.5">
                                <p className="text-sm text-white/70">В кармашке</p>
                                <button
                                    onClick={() => setShowHelp(h => !h)}
                                    className="text-white/50 hover:text-white/90 transition-colors"
                                    aria-label="Что такое кармашек">
                                    <HelpCircle size={14} />
                                </button>
                            </div>
                            {showHelp && (
                                <div className="mt-1 mb-2 text-xs text-white/70 leading-relaxed rounded-xl bg-black/20 px-3 py-2 space-y-2">
                                    <div>
                                        <b className="text-white/90">Сейчас (факт)</b> — реальные свободные деньги прямо сейчас.<br />
                                        Формула: <span className="text-white/90">баланс счёта</span> (чекпоинт + все исполненные доходы − все исполненные расходы) <span className="text-white/90">− сумма во всех копилках</span>.<br />
                                        Если отрицательный — в копилках зарезервировано больше, чем есть на счету.
                                    </div>
                                    <div>
                                        <b className="text-white/90">После обязательных</b> — прогноз с учётом только обязательных событий: все плановые доходы придут, но из расходов вычитаются только высокоприоритетные (ипотека, коммуналка и т.д.). Необязательные расходы и переводы в копилки не учитываются — это и есть максимум, который ты теоретически можешь пустить в копилки.
                                    </div>
                                    <div>
                                        <b className="text-white/90">После всех расходов</b> — прогноз при полном выполнении плана: все доходы, все расходы, все переводы в копилки. Это минимум, который останется, если всё пойдёт по плану.
                                    </div>
                                </div>
                            )}
                            {/* Факт */}
                            <p className="text-xs text-white/60 mt-1">Сейчас (факт)</p>
                            <p className="text-3xl font-bold text-white">
                                {new Intl.NumberFormat('ru-RU', { style: 'currency', currency: 'RUB', minimumFractionDigits: 0 })
                                    .format(data.pocketBalance)}
                            </p>
                            {projections && (() => {
                                const fmtC = (n: number) => new Intl.NumberFormat('ru-RU', { style: 'currency', currency: 'RUB', minimumFractionDigits: 0 }).format(n);
                                const row = (label: string, p: typeof projections.full) => (
                                    <div key={label} className="mt-2">
                                        <p className="text-white/50 text-xs mb-0.5">{label}</p>
                                        <div className="flex flex-wrap gap-x-3 gap-y-0.5 text-xs text-white/80">
                                            <span>Конец мес: <b className="text-white">{fmtC(p.endOfMonth)}</b></span>
                                            <span>3 мес: <b className="text-white">{fmtC(p.end3Month)}</b></span>
                                            <span>6 мес: <b className="text-white">{fmtC(p.end6Month)}</b></span>
                                            {p.endOfPlans != null && p.lastPlanDate && (
                                                <span>Конец планов: <b className="text-white">{fmtC(p.endOfPlans)}</b>
                                                    <span className="text-white/50"> · до {fmtDate(p.lastPlanDate)}</span>
                                                </span>
                                            )}
                                        </div>
                                    </div>
                                );
                                return (
                                    <>
                                        {row('После обязательных расходов', projections.mandatory)}
                                        {row('После всех расходов', projections.full)}
                                        {data.predictionAdjustedPocket != null && (
                                            <div className="mt-2 p-2.5 bg-secondary/30 border border-primary/20 rounded-lg">
                                                <div className="flex justify-between items-start">
                                                    <span className="text-sm text-primary">По текущему темпу</span>
                                                    <span className={`text-sm font-semibold ml-3 ${
                                                        data.predictionAdjustedPocket < data.pocketBalance
                                                            ? 'text-destructive'
                                                            : 'text-green-400'
                                                    }`}>
                                                        {fmt(data.predictionAdjustedPocket)}
                                                    </span>
                                                </div>
                                                {data.forecastContributors.length > 0 && (
                                                    <p className="text-xs text-muted-foreground mt-1">
                                                        {data.forecastContributors.join(', ')}
                                                    </p>
                                                )}
                                            </div>
                                        )}
                                    </>
                                );
                            })()}
                        </div>
                    </div>
                </div>

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
                            pocketBalance={data.pocketBalance}
                            onTransfer={setTransferFund}
                            onEdit={setEditFund}
                        />
                    ))
                ))}

                {/* Хотелки */}
                <WishlistSection />

                {/* Разделитель секций */}
                <div style={{ height: 1, background: 'var(--color-border)', margin: '8px 0' }} />

                {/* Планировщик копилок */}
                <SavingsStrategySection
                    funds={data.funds}
                    onFundUpdated={load}
                />
            </div>
            </ScrollArea>

            {showCreate && (
                <CreateFundModal
                    onClose={() => setShowCreate(false)}
                    onSuccess={() => { setShowCreate(false); load(); }}
                />
            )}

            {transferFund && (
                <TransferModal
                    fund={transferFund}
                    pocketBalance={data.pocketBalance}
                    onClose={() => setTransferFund(null)}
                    onSuccess={() => { setTransferFund(null); load(); }}
                />
            )}

            {editFund && (
                <EditFundModal
                    fund={editFund}
                    onClose={() => setEditFund(null)}
                    onSuccess={() => { setEditFund(null); load(); }}
                />
            )}
        </>
    );
}
