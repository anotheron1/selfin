import { useEffect, useState, useCallback } from 'react';
import { fetchFunds, createFund, updateFund, deleteFund, transferToFund } from '../api';
import type { FundsOverview, TargetFund } from '../types/api';
import { Wallet, Plus, ArrowDownToLine, Pencil, Trash2, X } from 'lucide-react';

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
        <div className="fixed inset-0 z-[100] flex items-end justify-center"
            style={{ background: 'rgba(0,0,0,0.6)', backdropFilter: 'blur(4px)' }}
            onClick={onClose}>
            <div className="w-full max-w-2xl rounded-t-2xl p-6 space-y-4"
                style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}
                onClick={e => e.stopPropagation()}>
                <div className="flex items-center justify-between">
                    <h2 className="font-semibold">Новый фонд</h2>
                    <button onClick={onClose}><X size={20} style={{ color: 'var(--color-text-muted)' }} /></button>
                </div>
                <form onSubmit={handleSubmit} className="space-y-3">
                    <input
                        autoFocus
                        placeholder="Название фонда (напр. «Отпуск»)"
                        value={name}
                        onChange={e => setName(e.target.value)}
                        className="w-full rounded-lg px-3 py-2 text-sm"
                        style={{ background: 'var(--color-surface-2)', border: '1px solid var(--color-border)', color: 'var(--color-text)' }}
                    />
                    <input
                        type="number"
                        placeholder="Целевая сумма, ₽ (необязательно)"
                        value={target}
                        onChange={e => setTarget(e.target.value)}
                        className="w-full rounded-lg px-3 py-2 text-sm"
                        style={{ background: 'var(--color-surface-2)', border: '1px solid var(--color-border)', color: 'var(--color-text)' }}
                    />
                    <div className="space-y-1">
                        <label className="text-xs" style={{ color: 'var(--color-text-muted)' }}>Срок достижения (необязательно)</label>
                        <input
                            type="date"
                            value={targetDate}
                            onChange={e => setTargetDate(e.target.value)}
                            className="w-full rounded-lg px-3 py-2 text-sm"
                            style={{ background: 'var(--color-surface-2)', border: '1px solid var(--color-border)', color: 'var(--color-text)' }}
                        />
                    </div>
                    <button
                        type="submit"
                        disabled={loading || !name.trim()}
                        className="w-full py-2.5 rounded-lg text-sm font-semibold text-white"
                        style={{ background: 'var(--color-accent)', opacity: loading ? 0.7 : 1 }}>
                        {loading ? 'Создаём...' : 'Создать фонд'}
                    </button>
                </form>
            </div>
        </div>
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
        <div className="fixed inset-0 z-[100] flex items-end justify-center"
            style={{ background: 'rgba(0,0,0,0.6)', backdropFilter: 'blur(4px)' }}
            onClick={onClose}>
            <div className="w-full max-w-2xl rounded-t-2xl p-6 space-y-4"
                style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}
                onClick={e => e.stopPropagation()}>
                <div className="flex items-center justify-between">
                    <div>
                        <h2 className="font-semibold">Пополнить фонд</h2>
                        <p className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
                            {fund.name} · доступно {fmt(pocketBalance)}
                        </p>
                    </div>
                    <button onClick={onClose}><X size={20} style={{ color: 'var(--color-text-muted)' }} /></button>
                </div>
                <form onSubmit={handleSubmit} className="space-y-3">
                    <input
                        autoFocus
                        type="number"
                        placeholder="Сумма, ₽"
                        max={pocketBalance}
                        value={amount}
                        onChange={e => setAmount(e.target.value)}
                        className="w-full rounded-lg px-3 py-2 text-sm"
                        style={{ background: 'var(--color-surface-2)', border: '1px solid var(--color-border)', color: 'var(--color-text)' }}
                    />
                    <button
                        type="submit"
                        disabled={loading || !amount || Number(amount) <= 0 || Number(amount) > pocketBalance}
                        className="w-full py-2.5 rounded-lg text-sm font-semibold text-white"
                        style={{ background: 'var(--color-accent)', opacity: loading ? 0.7 : 1 }}>
                        {loading ? 'Переводим...' : 'Перевести'}
                    </button>
                </form>
            </div>
        </div>
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
        <div className="fixed inset-0 z-[100] flex items-end justify-center"
            style={{ background: 'rgba(0,0,0,0.6)', backdropFilter: 'blur(4px)' }}
            onClick={onClose}>
            <div className="w-full max-w-2xl rounded-t-2xl p-6 space-y-4"
                style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}
                onClick={e => e.stopPropagation()}>
                <div className="flex items-center justify-between">
                    <h2 className="font-semibold">Редактировать фонд</h2>
                    <button onClick={onClose}><X size={20} style={{ color: 'var(--color-text-muted)' }} /></button>
                </div>
                <form onSubmit={handleSubmit} className="space-y-3">
                    <input
                        autoFocus
                        placeholder="Название фонда"
                        value={name}
                        onChange={e => setName(e.target.value)}
                        className="w-full rounded-lg px-3 py-2 text-sm"
                        style={{ background: 'var(--color-surface-2)', border: '1px solid var(--color-border)', color: 'var(--color-text)' }}
                    />
                    <input
                        type="number"
                        placeholder="Целевая сумма, ₽ (необязательно)"
                        value={target}
                        onChange={e => setTarget(e.target.value)}
                        className="w-full rounded-lg px-3 py-2 text-sm"
                        style={{ background: 'var(--color-surface-2)', border: '1px solid var(--color-border)', color: 'var(--color-text)' }}
                    />
                    <div className="space-y-1">
                        <label className="text-xs" style={{ color: 'var(--color-text-muted)' }}>Срок достижения (необязательно)</label>
                        <input
                            type="date"
                            value={targetDate}
                            onChange={e => setTargetDate(e.target.value)}
                            className="w-full rounded-lg px-3 py-2 text-sm"
                            style={{ background: 'var(--color-surface-2)', border: '1px solid var(--color-border)', color: 'var(--color-text)' }}
                        />
                    </div>
                    <button
                        type="submit"
                        disabled={loading || !name.trim()}
                        className="w-full py-2.5 rounded-lg text-sm font-semibold text-white"
                        style={{ background: 'var(--color-accent)', opacity: loading ? 0.7 : 1 }}>
                        {loading ? 'Сохраняем...' : 'Сохранить'}
                    </button>
                </form>
                <button
                    onClick={handleDelete}
                    disabled={loading}
                    className="w-full py-2.5 rounded-lg text-sm font-semibold flex items-center justify-center gap-2"
                    style={{ background: 'rgba(239,68,68,0.12)', color: 'var(--color-danger)', opacity: loading ? 0.7 : 1 }}>
                    <Trash2 size={15} /> Удалить фонд
                </button>
            </div>
        </div>
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
                    {reached && <span className="text-xs" style={{ color: 'var(--color-success)' }}>✓ Цель достигнута</span>}
                </div>
                <div className="flex items-center gap-1.5">
                    <span className="text-2xl font-bold" style={{ color: 'var(--color-accent)' }}>{pct}%</span>
                    {!reached && pocketBalance > 0 && (
                        <button
                            onClick={() => onTransfer(fund)}
                            title="Пополнить из кармашка"
                            className="p-1.5 rounded-lg"
                            style={{ background: 'rgba(99,102,241,0.15)', color: 'var(--color-accent)' }}>
                            <ArrowDownToLine size={16} />
                        </button>
                    )}
                    <button
                        onClick={() => onEdit(fund)}
                        title="Редактировать"
                        className="p-1.5 rounded-lg"
                        style={{ color: 'var(--color-text-muted)' }}>
                        <Pencil size={15} />
                    </button>
                </div>
            </div>
            {fund.targetAmount && (
                <div className="h-3 rounded-full" style={{ background: 'var(--color-surface-2)' }}>
                    <div className="h-3 rounded-full transition-all"
                        style={{ width: `${pct}%`, background: reached ? 'var(--color-success)' : 'var(--color-accent)' }} />
                </div>
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

    const load = useCallback(() => {
        setError(null);
        return fetchFunds().then(setData).catch((err: Error) => setError(err.message));
    }, []);
    useEffect(() => { load(); }, [load]);

    if (error) return <div className="p-6 text-center text-sm" style={{ color: 'var(--color-danger, #ef4444)' }}>Ошибка: {error}</div>;
    if (!data) return <div className="p-6 text-center animate-pulse" style={{ color: 'var(--color-text-muted)' }}>Загрузка...</div>;

    return (
        <>
            <div className="px-4 py-6 space-y-5">
                {/* Кармашек */}
                <div className="rounded-2xl p-6 flex items-center justify-between"
                    style={{ background: 'linear-gradient(135deg, var(--color-accent) 0%, #9f8cff 100%)' }}>
                    <div className="flex items-center gap-4">
                        <Wallet size={32} color="white" />
                        <div>
                            <p className="text-sm text-white/70">В кармашке 💼</p>
                            <p className="text-3xl font-bold text-white">
                                {new Intl.NumberFormat('ru-RU', { style: 'currency', currency: 'RUB', minimumFractionDigits: 0 })
                                    .format(data.pocketBalance)}
                            </p>
                        </div>
                    </div>
                </div>

                {/* Заголовок с кнопкой создания */}
                <div className="flex items-center justify-between">
                    <h2 className="font-semibold">Копилки</h2>
                    <button
                        onClick={() => setShowCreate(true)}
                        className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-sm font-medium text-white"
                        style={{ background: 'var(--color-accent)' }}>
                        <Plus size={16} /> Создать
                    </button>
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
            </div>

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
