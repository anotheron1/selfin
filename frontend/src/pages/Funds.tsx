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
import WishlistSection from '../components/WishlistSection';

const fmt = (n: number | null) =>
    n != null
        ? new Intl.NumberFormat('ru-RU', { style: 'currency', currency: 'RUB', minimumFractionDigits: 0 }).format(n)
        : '∞';

// ─── Диалог создания фонда ───────────────────────────────────────────────────

function CreateFundModal({ onClose, onSuccess }: { onClose: () => void; onSuccess: () => void }) {
    const [name, setName] = useState('');
    const [target, setTarget] = useState('');
    const [targetDate, setTargetDate] = useState('');
    const [loading, setLoading] = useState(false);

    const handleSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        if (!name.trim()) return;
        setLoading(true);
        try {
            await createFund({
                name: name.trim(),
                targetAmount: target ? Number(target) : undefined,
                targetDate: targetDate || undefined,
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
    const [loading, setLoading] = useState(false);

    const handleSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        if (!name.trim()) return;
        setLoading(true);
        try {
            await updateFund(fund.id, {
                name: name.trim(),
                targetAmount: target ? Number(target) : undefined,
                targetDate: targetDate || undefined,
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

export default function Funds() {
    const [data, setData] = useState<FundsOverview | null>(null);
    const [error, setError] = useState<string | null>(null);
    const [showCreate, setShowCreate] = useState(false);
    const [transferFund, setTransferFund] = useState<TargetFund | null>(null);
    const [editFund, setEditFund] = useState<TargetFund | null>(null);
    const [projections, setProjections] = useState<{
        endOfMonth: number;
        end3Month: number;
        end6Month: number;
        endOfPlans: number | null;
        lastPlanDate: string | null;
    } | null>(null);
    const [showHelp, setShowHelp] = useState(false);

    const load = useCallback(() => {
        setError(null);
        return fetchFunds().then(setData).catch((err: Error) => setError(err.message));
    }, []);
    useEffect(() => { load(); }, [load]);

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
            const project = (cutoff: string) =>
                planned
                    .filter(e => e.date <= cutoff)
                    .reduce((bal, e) => {
                        const amt = e.plannedAmount ?? 0;
                        return e.type === 'INCOME' ? bal + amt : bal - amt;
                    }, data.pocketBalance);

            let endOfPlans: number | null = null;
            let lastPlanDate: string | null = null;
            if (planned.length > 0) {
                endOfPlans = project(farFuture);
                lastPlanDate = planned.map(e => e.date).sort().at(-1) ?? null;
            }
            setProjections({
                endOfMonth: project(monthEnd),
                end3Month: project(month3End),
                end6Month: project(month6End),
                endOfPlans,
                lastPlanDate,
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
                                <div className="mt-1 mb-2 text-xs text-white/70 leading-relaxed rounded-xl bg-black/20 px-3 py-2">
                                    <b className="text-white/90">Кармашек</b> — свободные деньги на счету, не зарезервированные ни в одной копилке.<br />
                                    Формула: <span className="text-white/90">баланс счёта − сумма копилок</span>.<br />
                                    Если отрицательный — вы зарезервировали в копилках больше, чем сейчас на счету.<br />
                                    Прогнозы учитывают все запланированные доходы и расходы.
                                </div>
                            )}
                            <p className="text-3xl font-bold text-white">
                                {new Intl.NumberFormat('ru-RU', { style: 'currency', currency: 'RUB', minimumFractionDigits: 0 })
                                    .format(data.pocketBalance)}
                            </p>
                            {projections && (
                                <div className="mt-2 space-y-0.5 text-xs text-white/80">
                                    <p>Конец месяца:{' '}
                                        <b className="text-white">{new Intl.NumberFormat('ru-RU', { style: 'currency', currency: 'RUB', minimumFractionDigits: 0 }).format(projections.endOfMonth)}</b>
                                    </p>
                                    <p>Через 3 месяца:{' '}
                                        <b className="text-white">{new Intl.NumberFormat('ru-RU', { style: 'currency', currency: 'RUB', minimumFractionDigits: 0 }).format(projections.end3Month)}</b>
                                    </p>
                                    <p>Через 6 месяцев:{' '}
                                        <b className="text-white">{new Intl.NumberFormat('ru-RU', { style: 'currency', currency: 'RUB', minimumFractionDigits: 0 }).format(projections.end6Month)}</b>
                                    </p>
                                    {projections.endOfPlans != null && projections.lastPlanDate && (
                                        <p>Конец планов:{' '}
                                            <b className="text-white">{new Intl.NumberFormat('ru-RU', { style: 'currency', currency: 'RUB', minimumFractionDigits: 0 }).format(projections.endOfPlans)}</b>
                                            <span className="text-white/60"> · до {fmtDate(projections.lastPlanDate)}</span>
                                        </p>
                                    )}
                                </div>
                            )}
                        </div>
                    </div>
                </div>

                {/* Заголовок с кнопкой создания */}
                <div className="flex items-center justify-between">
                    <h2 className="font-semibold">Копилки</h2>
                    <Button
                        onClick={() => setShowCreate(true)}
                        size="sm"
                        className="flex items-center gap-1.5">
                        <Plus size={16} /> Создать
                    </Button>
                </div>

                {/* Карточки фондов */}
                {data.funds.length === 0 ? (
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
                )}

                {/* Хотелки */}
                <WishlistSection />
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
