import { useEffect, useState } from 'react';
import { fetchCategories, createCategory, toggleMandatory, fetchSnapshots, createSnapshot } from '../api';
import type { BudgetSnapshot, Category, CategoryType } from '../types/api';
import { Plus, ShieldCheck, Shield, Camera } from 'lucide-react';

export default function Settings() {
    const [categories, setCategories] = useState<Category[]>([]);
    const [name, setName] = useState('');
    const [type, setType] = useState<CategoryType>('EXPENSE');
    const [toastMsg, setToastMsg] = useState<string | null>(null);
    const [snapshots, setSnapshots] = useState<BudgetSnapshot[]>([]);
    const [snapshotLoading, setSnapshotLoading] = useState(false);

    const showToast = (msg: string) => {
        setToastMsg(msg);
        setTimeout(() => setToastMsg(null), 3000);
    };

    const load = () => fetchCategories().then(setCategories);
    const loadSnapshots = () => fetchSnapshots().then(setSnapshots);

    useEffect(() => { load(); loadSnapshots(); }, []);

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

    const handleCreate = async (e: React.FormEvent) => {
        e.preventDefault();
        if (!name.trim()) return;
        await createCategory({ name: name.trim(), type, mandatory: false });
        setName('');
        load();
        showToast('Категория добавлена');
    };

    const handleToggleMandatory = async (id: string, currentMandatory: boolean) => {
        await toggleMandatory(id);
        load();
        showToast(currentMandatory ? 'Убрано из обязательных' : 'Отмечено как обязательное');
    };

    const grouped = {
        EXPENSE: categories.filter(c => c.type === 'EXPENSE'),
        INCOME: categories.filter(c => c.type === 'INCOME'),
    };

    return (
        <>
            <div className="px-4 py-6 space-y-6">
                <h1 className="text-xl font-bold">Настройки</h1>

                {/* Создание категории */}
                <div className="rounded-2xl p-5 space-y-4"
                    style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}>
                    <h2 className="font-semibold text-sm" style={{ color: 'var(--color-text-muted)' }}>ДОБАВИТЬ КАТЕГОРИЮ</h2>
                    <form onSubmit={handleCreate} className="flex gap-2">
                        <input
                            value={name}
                            onChange={e => setName(e.target.value)}
                            placeholder="Название категории"
                            className="flex-1 rounded-lg px-3 py-2 text-sm"
                            style={{ background: 'var(--color-surface-2)', border: '1px solid var(--color-border)', color: 'var(--color-text)' }}
                        />
                        <select
                            value={type}
                            onChange={e => setType(e.target.value as CategoryType)}
                            className="rounded-lg px-3 py-2 text-sm"
                            style={{ background: 'var(--color-surface-2)', border: '1px solid var(--color-border)', color: 'var(--color-text)' }}>
                            <option value="EXPENSE">Расход</option>
                            <option value="INCOME">Доход</option>
                        </select>
                        <button type="submit"
                            className="rounded-lg px-3 py-2"
                            style={{ background: 'var(--color-accent)', color: '#fff' }}>
                            <Plus size={18} />
                        </button>
                    </form>
                </div>

                {/* Список категорий по типам */}
                {(['EXPENSE', 'INCOME'] as CategoryType[]).map(t => (
                    <div key={t} className="rounded-2xl p-5 space-y-2"
                        style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}>
                        <h2 className="font-semibold text-sm mb-3" style={{ color: 'var(--color-text-muted)' }}>
                            {t === 'EXPENSE' ? '📤 РАСХОДЫ' : '📥 ДОХОДЫ'}
                        </h2>
                        {grouped[t].length === 0 ? (
                            <p className="text-sm" style={{ color: 'var(--color-text-muted)' }}>Нет категорий</p>
                        ) : grouped[t].map(c => (
                            <div key={c.id} className="flex items-center justify-between py-2.5 px-1"
                                style={{ borderBottom: '1px solid var(--color-border)' }}>
                                <div className="flex items-center gap-2">
                                    <span className="text-sm">{c.name}</span>
                                    {c.mandatory && (
                                        <span className="text-xs px-1.5 py-0.5 rounded"
                                            style={{ background: 'rgba(239,68,68,0.12)', color: 'var(--color-danger)' }}>
                                            обяз
                                        </span>
                                    )}
                                </div>
                                <button
                                    onClick={() => handleToggleMandatory(c.id, c.mandatory)}
                                    title={c.mandatory ? 'Убрать из обязательных' : 'Сделать обязательной'}
                                    className="p-1.5 rounded-lg transition-colors"
                                    style={{
                                        color: c.mandatory ? 'var(--color-danger)' : 'var(--color-text-muted)',
                                        background: c.mandatory ? 'rgba(239,68,68,0.1)' : 'transparent'
                                    }}>
                                    {c.mandatory ? <ShieldCheck size={16} /> : <Shield size={16} />}
                                </button>
                            </div>
                        ))}
                    </div>
                ))}
            </div>

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

            {/* Toast-уведомление */}
            {toastMsg && (
                <div className="fixed bottom-24 left-1/2 -translate-x-1/2 px-4 py-2 rounded-xl text-sm font-medium text-white z-[200] animate-pulse"
                    style={{ background: 'var(--color-accent)', boxShadow: '0 4px 16px rgba(0,0,0,0.4)' }}>
                    {toastMsg}
                </div>
            )}
        </>
    );
}
