import { useEffect, useState } from 'react';
import {
    fetchCategories,
    fetchCheckpoints, createCheckpoint, updateCheckpoint, deleteCheckpoint,
    fetchAccounts,
    fetchForecastReadiness,
} from '../api';
import type { Account, BalanceCheckpoint, Category, ForecastReadiness } from '../types/api';
import CategoriesSection from '../components/CategoriesSection';
import HintsSection from '../components/HintsSection';
import SignOutSection from '../components/SignOutSection';
import { Plus, Pencil, Trash2, Check, X } from 'lucide-react';
import { ScrollArea } from '../components/ui/scroll-area';
import { AmountInput, amountValue } from '../components/ui/amount-input';
import { latestAllowedIso } from '../lib/factDate';

const inputStyle = {
    background: 'var(--color-surface-2)',
    border: '1px solid var(--color-border)',
    color: 'var(--color-text)',
};
const today = () => latestAllowedIso(new Date());

export default function Settings() {
    // --- Categories ---
    const [categories, setCategories] = useState<Category[]>([]);
    /** ANO-80: когда прогноз станет доступен — чтобы «включил, а пусто» не читалось как поломка. */
    const [readiness, setReadiness] = useState<ForecastReadiness | null>(null);

    // --- Accounts (ANO-9) ---
    const [accounts, setAccounts] = useState<Account[]>([]);

    // --- Balance Checkpoints ---
    const [checkpoints, setCheckpoints] = useState<BalanceCheckpoint[]>([]);
    const [cpDate, setCpDate] = useState(today());
    const [cpAmount, setCpAmount] = useState('');
    const [cpAccountId, setCpAccountId] = useState('');
    const [editingId, setEditingId] = useState<string | null>(null);
    const [editDate, setEditDate] = useState('');
    const [editAmount, setEditAmount] = useState('');

    // --- Toast ---
    const [toastMsg, setToastMsg] = useState<string | null>(null);
    const showToast = (msg: string) => {
        setToastMsg(msg);
        setTimeout(() => setToastMsg(null), 3000);
    };

    const load = () => fetchCategories().then(setCategories);
    const loadReadiness = () => fetchForecastReadiness().then(setReadiness);
    const loadCheckpoints = () => fetchCheckpoints().then(setCheckpoints);
    const loadAccounts = () => fetchAccounts().then(setAccounts);

    // Счета — только выбор в записи остатка ниже; сами карты — на «Деньгах» (срез 4, задача 3.5).
    useEffect(() => { load(); loadCheckpoints(); loadAccounts(); loadReadiness(); }, []);

    // --- Checkpoint handlers ---
    const handleCreateCheckpoint = async (e: React.FormEvent) => {
        e.preventDefault();
        const amount = amountValue(cpAmount) ?? NaN;   // ANO-33
        if (!cpDate || isNaN(amount) || amount < 0) return;
        // Пустой cpAccountId уходит как undefined: бэкенд посадит остаток на счёт-приёмник.
        await createCheckpoint({ date: cpDate, amount, accountId: cpAccountId || undefined });
        setCpDate(today());
        setCpAmount('');
        loadCheckpoints();
        showToast('Баланс сохранён');
    };

    const startEdit = (cp: BalanceCheckpoint) => {
        setEditingId(cp.id);
        setEditDate(cp.date);
        setEditAmount(String(cp.amount));
    };

    const cancelEdit = () => setEditingId(null);

    const handleUpdate = async (id: string) => {
        const amount = amountValue(editAmount) ?? NaN;   // ANO-33
        if (!editDate || isNaN(amount) || amount < 0) return;
        await updateCheckpoint(id, { date: editDate, amount });
        setEditingId(null);
        loadCheckpoints();
        showToast('Запись обновлена');
    };

    const handleDelete = async (id: string) => {
        await deleteCheckpoint(id);
        loadCheckpoints();
        showToast('Запись удалена');
    };

    const fmtDate = (d: string) =>
        new Date(d + 'T00:00:00').toLocaleDateString('ru-RU', { day: 'numeric', month: 'short', year: 'numeric' });

    const fmtAmount = (n: number) =>
        n.toLocaleString('ru-RU', { minimumFractionDigits: 0, maximumFractionDigits: 2 });

    return (
        <>
        <ScrollArea>
            <div className="pl-4 pr-5 py-6 space-y-6">
                <h1 className="text-xl font-bold">Настройки</h1>

                {/* Подсказки первого дня на «Сегодня» (срез 2, п. 12): выключатель и «Скрыть все» — одно. */}
                <HintsSection />

                {/* Баланс счёта */}
                <div className="rounded-2xl p-5 space-y-4"
                    style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}>
                    <h2 className="font-semibold text-sm" style={{ color: 'var(--color-text-muted)' }}>💰 БАЛАНС СЧЁТА</h2>
                    <p className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
                        Зафиксируй реальный остаток на счёте — от этой точки считается текущий баланс и прогноз
                    </p>

                    {/* Форма добавления */}
                    <form onSubmit={handleCreateCheckpoint} className="space-y-2">
                        <div className="flex gap-2">
                            <input
                                type="date"
                                value={cpDate}
                                onChange={e => setCpDate(e.target.value)}
                                max={today()}
                                className="rounded-lg px-3 py-2 text-sm"
                                style={inputStyle}
                            />
                            <AmountInput
                                value={cpAmount}
                                onChange={setCpAmount}
                                placeholder="Сумма, ₽"
                                className="flex-1 rounded-lg px-3 py-2 text-sm h-auto border-0"
                                style={inputStyle}
                            />
                            <button type="submit"
                                className="rounded-lg px-3 py-2"
                                style={{ background: 'var(--color-accent)', color: '#fff' }}>
                                <Plus size={18} />
                            </button>
                        </div>
                        {/* Счёт спрашиваем только когда их больше одного: с одним выбирать нечего,
                            и лишнее поле сломало бы обещание «ввод остатка — один клик». */}
                        {accounts.length > 1 && (
                            <select
                                value={cpAccountId}
                                onChange={e => setCpAccountId(e.target.value)}
                                className="w-full rounded-lg px-3 py-2 text-sm"
                                style={inputStyle}>
                                <option value="">Счёт по умолчанию</option>
                                {accounts.map(a => (
                                    <option key={a.id} value={a.id}>
                                        {a.name}{a.kind === 'CREDIT' ? ' — доступный остаток' : ''}
                                    </option>
                                ))}
                            </select>
                        )}
                    </form>

                    {/* История */}
                    {checkpoints.length === 0 ? (
                        <p className="text-sm py-1" style={{ color: 'var(--color-text-muted)' }}>
                            Записей пока нет
                        </p>
                    ) : checkpoints.map(cp => (
                        <div key={cp.id}>
                            {editingId === cp.id ? (
                                /* Inline-редактирование */
                                <div className="flex gap-2 py-1.5">
                                    <input
                                        type="date"
                                        value={editDate}
                                        onChange={e => setEditDate(e.target.value)}
                                        max={today()}
                                        className="rounded-lg px-3 py-1.5 text-sm"
                                        style={inputStyle}
                                    />
                                    <input
                                        type="number"
                                        value={editAmount}
                                        onChange={e => setEditAmount(e.target.value)}
                                        min="0"
                                        step="0.01"
                                        className="flex-1 rounded-lg px-3 py-1.5 text-sm"
                                        style={inputStyle}
                                    />
                                    <button onClick={() => handleUpdate(cp.id)}
                                        className="p-1.5 rounded-lg"
                                        style={{ background: 'var(--color-accent)', color: '#fff' }}>
                                        <Check size={16} />
                                    </button>
                                    <button onClick={cancelEdit}
                                        className="p-1.5 rounded-lg"
                                        style={{ background: 'var(--color-surface-2)', color: 'var(--color-text-muted)' }}>
                                        <X size={16} />
                                    </button>
                                </div>
                            ) : (
                                /* Строка просмотра */
                                <div className="flex items-center justify-between py-2.5 px-1"
                                    style={{ borderBottom: '1px solid var(--color-border)' }}>
                                    <div>
                                        <span className="text-sm font-medium">
                                            {fmtAmount(cp.amount)} ₽
                                        </span>
                                        <span className="text-xs ml-2" style={{ color: 'var(--color-text-muted)' }}>
                                            на {fmtDate(cp.date)}
                                        </span>
                                        {/* Имя счёта — только когда счетов больше одного:
                                            иначе это шум, повторяющий одно и то же в каждой строке. */}
                                        {accounts.length > 1 && (
                                            <span className="text-xs ml-2" style={{ color: 'var(--color-text-muted)' }}>
                                                · {cp.accountName}
                                            </span>
                                        )}
                                        {/* Дрейф интервала (ANO-15): незаписанные потоки с прошлого якоря */}
                                        {cp.drift != null && (
                                            <span className="text-xs ml-2 px-1.5 py-0.5 rounded-full"
                                                title={`selfin насчитал ${fmtAmount(cp.computedBalance ?? 0)} ₽ от прошлого якоря`}
                                                style={{
                                                    // ANO-123: дрейф — мера учёта, а не приговор; красный
                                                    // на нём говорил бы «ты не записал» (правила 5, 12).
                                                    color: 'var(--color-text-muted)',
                                                    background: 'var(--color-surface-2)',
                                                    fontSize: '10px',
                                                }}>
                                                дрейф {cp.drift > 0 ? '+' : ''}{fmtAmount(cp.drift)} ₽
                                            </span>
                                        )}
                                    </div>
                                    <div className="flex gap-1">
                                        <button onClick={() => startEdit(cp)}
                                            className="p-1.5 rounded-lg"
                                            style={{ color: 'var(--color-text-muted)' }}>
                                            <Pencil size={14} />
                                        </button>
                                        <button onClick={() => handleDelete(cp.id)}
                                            className="p-1.5 rounded-lg"
                                            style={{ color: 'var(--color-danger)' }}>
                                            <Trash2 size={14} />
                                        </button>
                                    </div>
                                </div>
                            )}
                        </div>
                    ))}
                </div>

                {/* Категории (срез 3, п. 15): «Расходы | Доходы», прогноз галочкой, правило у «Новая категория». */}
                <CategoriesSection categories={categories} readiness={readiness} onChanged={load} showToast={showToast} />

                <SignOutSection />
            </div>
        </ScrollArea>

        {/* Toast */}
        {toastMsg && (
            <div className="fixed bottom-24 left-1/2 -translate-x-1/2 px-4 py-2 rounded-xl text-sm font-medium text-white z-[200] animate-pulse"
                style={{ background: 'var(--color-accent)', boxShadow: '0 4px 16px rgba(0,0,0,0.4)' }}>
                {toastMsg}
            </div>
        )}
        </>
    );
}
