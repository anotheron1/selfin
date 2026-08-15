import { useEffect, useState } from 'react';
import {
    fetchCategories, createCategory, updateCategory, deleteCategory, cycleCategoryPriority,
    fetchSnapshots, createSnapshot,
    fetchCheckpoints, createCheckpoint, updateCheckpoint, deleteCheckpoint,
    fetchAccounts,
} from '../api';
import type { Account, BalanceCheckpoint, BudgetSnapshot, Category, CategoryType } from '../types/api';
import PriorityButton from '../components/PriorityButton';
import AccountsSection from '../components/accounts/AccountsSection';
import { Plus, Camera, Pencil, Trash2, Check, X } from 'lucide-react';
import { ScrollArea } from '../components/ui/scroll-area';
import { AmountInput, amountValue } from '../components/ui/amount-input';

const inputStyle = {
    background: 'var(--color-surface-2)',
    border: '1px solid var(--color-border)',
    color: 'var(--color-text)',
};
const today = () => new Date().toISOString().slice(0, 10);

export default function Settings() {
    // --- Categories ---
    const [categories, setCategories] = useState<Category[]>([]);
    const [name, setName] = useState('');
    const [type, setType] = useState<CategoryType>('EXPENSE');
    const [editCatId, setEditCatId] = useState<string | null>(null);
    const [editCatName, setEditCatName] = useState('');
    const [editCatType, setEditCatType] = useState<CategoryType>('EXPENSE');
    const [editCatForecast, setEditCatForecast] = useState(false);
    const [createForecast, setCreateForecast] = useState(false);
    const [editCatPrimaryIncome, setEditCatPrimaryIncome] = useState(false);
    const [createPrimaryIncome, setCreatePrimaryIncome] = useState(false);

    // --- Snapshots ---
    const [snapshots, setSnapshots] = useState<BudgetSnapshot[]>([]);
    const [snapshotLoading, setSnapshotLoading] = useState(false);

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
    const loadSnapshots = () => fetchSnapshots().then(setSnapshots);
    const loadCheckpoints = () => fetchCheckpoints().then(setCheckpoints);
    const loadAccounts = () => fetchAccounts().then(setAccounts);

    useEffect(() => { load(); loadSnapshots(); loadCheckpoints(); loadAccounts(); }, []);

    /**
     * Правка счетов меняет и историю остатков: удалённый счёт уходит из выбора, переименованный
     * меняет подпись у своих записей. Поэтому после любой правки перечитываются оба списка.
     */
    const reloadAccounts = async () => { await loadAccounts(); await loadCheckpoints(); };

    // --- Snapshot handlers ---
    const handleSnapshot = async () => {
        setSnapshotLoading(true);
        try {
            await createSnapshot();
            await loadSnapshots();
            showToast('План зафиксирован');
        } catch {
            showToast('Ошибка создания снимка');
        } finally {
            setSnapshotLoading(false);
        }
    };

    // --- Category handlers ---
    const handleCreate = async (e: React.FormEvent) => {
        e.preventDefault();
        if (!name.trim()) return;
        await createCategory({
            name: name.trim(),
            type,
            priority: 'MEDIUM',
            forecastEnabled: type === 'EXPENSE' ? createForecast : false,
            primaryIncome: type === 'INCOME' ? createPrimaryIncome : false,
        });
        setName('');
        setCreateForecast(false);
        setCreatePrimaryIncome(false);
        load();
        showToast('Категория добавлена');
    };

    const handleCyclePriority = async (id: string) => {
        await cycleCategoryPriority(id);
        load();
    };

    const startEditCat = (cat: Category) => {
        setEditCatId(cat.id);
        setEditCatName(cat.name);
        setEditCatType(cat.type);
        setEditCatForecast(cat.forecastEnabled);
        setEditCatPrimaryIncome(cat.primaryIncome);
    };

    const cancelEditCat = () => setEditCatId(null);

    const handleUpdateCategory = async (cat: Category) => {
        if (!editCatName.trim()) return;
        await updateCategory(cat.id, {
            name: editCatName.trim(),
            type: editCatType,
            priority: cat.priority,
            forecastEnabled: editCatType === 'EXPENSE' ? editCatForecast : false,
            primaryIncome: editCatType === 'INCOME' ? editCatPrimaryIncome : false,
        });
        setEditCatId(null);
        load();
        showToast('Категория обновлена');
    };

    const handleDeleteCategory = async (id: string) => {
        if (!confirm('Удалить категорию? Связанные события сохранятся.')) return;
        await deleteCategory(id);
        load();
        showToast('Категория удалена');
    };

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

    const grouped = {
        EXPENSE: categories.filter(c => c.type === 'EXPENSE'),
        INCOME: categories.filter(c => c.type === 'INCOME'),
    };

    const fmtDate = (d: string) =>
        new Date(d + 'T00:00:00').toLocaleDateString('ru-RU', { day: 'numeric', month: 'short', year: 'numeric' });

    const fmtAmount = (n: number) =>
        n.toLocaleString('ru-RU', { minimumFractionDigits: 0, maximumFractionDigits: 2 });

    return (
        <>
        <ScrollArea className="h-[calc(100dvh-var(--nav-height))]">
            <div className="pl-4 pr-5 py-6 space-y-6">
                <h1 className="text-xl font-bold">Настройки</h1>

                <AccountsSection
                    accounts={accounts}
                    categories={categories}
                    onChanged={reloadAccounts}
                    showToast={showToast}
                />

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
                                                    color: cp.drift < 0 ? 'var(--color-danger)'
                                                        : cp.drift > 0 ? 'var(--color-success)'
                                                        : 'var(--color-text-muted)',
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

                {/* Создание категории */}
                <div className="rounded-2xl p-5 space-y-4"
                    style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}>
                    <h2 className="font-semibold text-sm" style={{ color: 'var(--color-text-muted)' }}>ДОБАВИТЬ КАТЕГОРИЮ</h2>
                    <form onSubmit={handleCreate} className="flex flex-col gap-2">
                        <div className="flex gap-2">
                            <input
                                value={name}
                                onChange={e => setName(e.target.value)}
                                placeholder="Название категории"
                                className="flex-1 rounded-lg px-3 py-2 text-sm"
                                style={inputStyle}
                            />
                            <select
                                value={type}
                                onChange={e => setType(e.target.value as CategoryType)}
                                className="rounded-lg px-3 py-2 text-sm"
                                style={inputStyle}>
                                <option value="EXPENSE">Расход</option>
                                <option value="INCOME">Доход</option>
                            </select>
                            <button type="submit"
                                className="rounded-lg px-3 py-2"
                                style={{ background: 'var(--color-accent)', color: '#fff' }}>
                                <Plus size={18} />
                            </button>
                        </div>
                        {type === 'EXPENSE' && (
                            <label className="flex items-center gap-2 px-1 cursor-pointer select-none"
                                style={{ color: 'var(--color-text-muted)' }}>
                                <input
                                    type="checkbox"
                                    checked={createForecast}
                                    onChange={e => setCreateForecast(e.target.checked)}
                                    className="accent-[var(--color-accent)] w-3.5 h-3.5"
                                />
                                <span className="text-xs">Отслеживать прогноз</span>
                            </label>
                        )}
                        {type === 'INCOME' && (
                            <label className="flex items-center gap-2 px-1 cursor-pointer select-none"
                                style={{ color: 'var(--color-text-muted)' }}>
                                <input
                                    type="checkbox"
                                    checked={createPrimaryIncome}
                                    onChange={e => setCreatePrimaryIncome(e.target.checked)}
                                    className="accent-[var(--color-accent)] w-3.5 h-3.5"
                                />
                                <span className="text-xs">Основной доход (задаёт горизонт кармашка)</span>
                            </label>
                        )}
                    </form>
                </div>

                {/* Список категорий */}
                {(['EXPENSE', 'INCOME'] as CategoryType[]).map(t => (
                    <div key={t} className="rounded-2xl p-5 space-y-2"
                        style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}>
                        <h2 className="font-semibold text-sm mb-3" style={{ color: 'var(--color-text-muted)' }}>
                            {t === 'EXPENSE' ? '📤 РАСХОДЫ' : '📥 ДОХОДЫ'}
                        </h2>
                        {grouped[t].length === 0 ? (
                            <p className="text-sm" style={{ color: 'var(--color-text-muted)' }}>Нет категорий</p>
                        ) : grouped[t].map(c => (
                            <div key={c.id}>
                                {editCatId === c.id ? (
                                    <div className="flex flex-col gap-2 py-1.5">
                                        <div className="flex gap-2">
                                            <input
                                                autoFocus
                                                value={editCatName}
                                                onChange={e => setEditCatName(e.target.value)}
                                                className="flex-1 rounded-lg px-3 py-1.5 text-sm"
                                                style={inputStyle}
                                            />
                                            <select
                                                value={editCatType}
                                                onChange={e => setEditCatType(e.target.value as CategoryType)}
                                                className="rounded-lg px-3 py-1.5 text-sm"
                                                style={inputStyle}>
                                                <option value="EXPENSE">Расход</option>
                                                <option value="INCOME">Доход</option>
                                            </select>
                                            <button onClick={() => handleUpdateCategory(c)}
                                                className="p-1.5 rounded-lg"
                                                style={{ background: 'var(--color-accent)', color: '#fff' }}>
                                                <Check size={16} />
                                            </button>
                                            <button onClick={cancelEditCat}
                                                className="p-1.5 rounded-lg"
                                                style={{ background: 'var(--color-surface-2)', color: 'var(--color-text-muted)' }}>
                                                <X size={16} />
                                            </button>
                                        </div>
                                        {editCatType === 'EXPENSE' && (
                                            <label className="flex items-center gap-2 px-1 cursor-pointer select-none"
                                                style={{ color: 'var(--color-text-muted)' }}>
                                                <input
                                                    type="checkbox"
                                                    checked={editCatForecast}
                                                    onChange={e => setEditCatForecast(e.target.checked)}
                                                    className="accent-[var(--color-accent)] w-3.5 h-3.5"
                                                />
                                                <span className="text-xs">Отслеживать прогноз</span>
                                            </label>
                                        )}
                                        {editCatType === 'INCOME' && (
                                            <label className="flex items-center gap-2 px-1 cursor-pointer select-none"
                                                style={{ color: 'var(--color-text-muted)' }}>
                                                <input
                                                    type="checkbox"
                                                    checked={editCatPrimaryIncome}
                                                    onChange={e => setEditCatPrimaryIncome(e.target.checked)}
                                                    className="accent-[var(--color-accent)] w-3.5 h-3.5"
                                                />
                                                <span className="text-xs">Основной доход (задаёт горизонт кармашка)</span>
                                            </label>
                                        )}
                                    </div>
                                ) : (
                                    <div className="flex items-center justify-between py-2.5 px-1"
                                        style={{ borderBottom: '1px solid var(--color-border)' }}>
                                        <div className="flex items-center gap-2">
                                            {c.isSystem ? (
                                                <span className="text-sm" style={{ color: 'var(--color-text-muted)' }}>
                                                    {c.name}
                                                    <span className="text-xs ml-1" style={{ color: 'var(--color-text-muted)' }}>· системная</span>
                                                </span>
                                            ) : (
                                                <span className="text-sm">
                                                    {c.name}
                                                    {c.forecastEnabled && (
                                                        <span className="text-xs ml-1.5" style={{ color: 'var(--color-accent)', opacity: 0.7 }}>· прогноз</span>
                                                    )}
                                                    {c.primaryIncome && (
                                                        <span className="text-xs ml-1.5" style={{ color: 'var(--color-accent)', opacity: 0.7 }}>· основной</span>
                                                    )}
                                                </span>
                                            )}
                                            <PriorityButton
                                                priority={c.priority}
                                                onCycle={() => handleCyclePriority(c.id)}
                                            />
                                        </div>
                                        <div className="flex gap-1">
                                            {!c.isSystem && (
                                                <button
                                                    onClick={() => startEditCat(c)}
                                                    title="Редактировать"
                                                    className="p-1.5 rounded-lg"
                                                    style={{ color: 'var(--color-text-muted)' }}>
                                                    <Pencil size={14} />
                                                </button>
                                            )}
                                            {!c.isSystem && (
                                                <button
                                                    onClick={() => handleDeleteCategory(c.id)}
                                                    title="Удалить"
                                                    className="p-1.5 rounded-lg"
                                                    style={{ color: 'var(--color-danger)' }}>
                                                    <Trash2 size={14} />
                                                </button>
                                            )}
                                        </div>
                                    </div>
                                )}
                            </div>
                        ))}
                    </div>
                ))}
                {/* Снимки бюджета */}
                <div className="rounded-2xl p-5 space-y-3"
                    style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}>
                    <div className="flex items-center justify-between">
                        <h2 className="font-semibold text-sm" style={{ color: 'var(--color-text-muted)' }}>📸 СНИМКИ БЮДЖЕТА</h2>
                        <button
                            onClick={handleSnapshot}
                            disabled={snapshotLoading}
                            className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-sm font-medium text-white"
                            style={{ background: 'var(--color-accent)', opacity: snapshotLoading ? 0.7 : 1 }}>
                            <Camera size={14} />
                            {snapshotLoading ? 'Фиксируем...' : 'Зафиксировать план'}
                        </button>
                    </div>
                    <p className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
                        Снимок сохраняет текущий план месяца для сравнения «план изначальный vs факт»
                    </p>
                    {snapshots.length === 0 ? (
                        <p className="text-sm py-2" style={{ color: 'var(--color-text-muted)' }}>Снимков пока нет</p>
                    ) : snapshots.map(s => (
                        <div key={s.id} className="flex items-center justify-between py-2 px-1"
                            style={{ borderBottom: '1px solid var(--color-border)' }}>
                            <span className="text-sm">
                                {new Date(s.periodStart).toLocaleDateString('ru-RU', { month: 'long', year: 'numeric' })}
                            </span>
                            <span className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
                                {new Date(s.snapshotDate).toLocaleDateString('ru-RU', { day: 'numeric', month: 'short' })}
                            </span>
                        </div>
                    ))}
                </div>
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
