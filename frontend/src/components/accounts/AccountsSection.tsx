import { useState } from 'react';
import { Plus } from 'lucide-react';
import {
    addCapitalRevaluation, createAccount, deleteAccount, fetchCapitalItems,
    makeAccountDefault, updateAccount,
} from '../../api';
import type { Account, AccountCreateDto, CapitalItem, Category } from '../../types/api';
import AccountCard from './AccountCard';
import AccountForm from './AccountForm';
import CapitalLiabilityHint from './CapitalLiabilityHint';
import { copy } from './accountsCopy';

/**
 * Секция «Счета» в Настройках (спека §5.3). Живёт именно там, а не в нижней навигации:
 * счета — продвинутый слой лестницы раскрытия, большинство до него не доходит, а в
 * ежедневном цикле ввода их нет вовсе (§5.1).
 */
export default function AccountsSection({ accounts, categories, onChanged, showToast }: {
    /** Список держит страница: он же нужен выбору счёта в записи остатка ниже. */
    accounts: Account[];
    categories: Category[];
    /** Перечитать счета и всё, что от них зависит (остатки, кармашек, капитал). */
    onChanged: () => Promise<void> | void;
    showToast: (msg: string) => void;
}) {
    const [creating, setCreating] = useState(false);
    const [editing, setEditing] = useState<Account | null>(null);
    const [formError, setFormError] = useState<string | null>(null);

    // Подсказка §7.2 показывается ПОСЛЕ создания кредитного счёта, а не в форме:
    // до сохранения ещё неизвестно, кредитка это или нет.
    const [hintFor, setHintFor] = useState<{ name: string; items: CapitalItem[] } | null>(null);
    const [archivingId, setArchivingId] = useState<string | null>(null);

    const afterChange = async (msg: string) => {
        await onChanged();
        showToast(msg);
    };

    const handleCreate = async (dto: AccountCreateDto) => {
        setFormError(null);
        try {
            await createAccount(dto);
            setCreating(false);
            await afterChange(copy.created);
            if (dto.kind === 'CREDIT') await offerCapitalCleanup(dto.name);
        } catch (e) {
            setFormError((e as Error).message);
        }
    };

    const handleUpdate = async (dto: AccountCreateDto) => {
        if (!editing) return;
        setFormError(null);
        try {
            await updateAccount(editing.id, dto);
            setEditing(null);
            await afterChange(copy.updated);
        } catch (e) {
            setFormError((e as Error).message);
        }
    };

    const handleDelete = async (a: Account) => {
        if (a.isDefault) { showToast(copy.deleteDefaultError); return; }
        if (!confirm(copy.deleteConfirm)) return;
        try {
            await deleteAccount(a.id);
            await afterChange(copy.deleted);
        } catch (e) {
            showToast((e as Error).message);
        }
    };

    const handleMakeDefault = async (a: Account) => {
        try {
            await makeAccountDefault(a.id);
            await afterChange(copy.defaultChanged);
        } catch (e) {
            showToast((e as Error).message);
        }
    };

    /** Планка поднимается до текущего доступного — ровно то, что предлагает подсказка §4.2. */
    const handleRaiseFloor = async (a: Account, to: number) => {
        try {
            await updateAccount(a.id, {
                name: a.name, kind: a.kind, trackBalance: a.trackBalance,
                purposeCategoryId: a.purposeCategoryId,
                creditLimit: a.creditLimit, availableFloor: to,
            });
            await afterChange(copy.floorRaised);
        } catch (e) {
            showToast((e as Error).message);
        }
    };

    const offerCapitalCleanup = async (accountName: string) => {
        try {
            const items = await fetchCapitalItems('LIABILITY');
            const live = items.filter(i => !i.isArchived);
            if (live.length > 0) setHintFor({ name: accountName, items: live });
        } catch {
            // Подсказка необязательна: молчим, счёт уже создан.
        }
    };

    /**
     * Архивация записи капитала = переоценка в ноль: {@code CapitalItem.isArchived} и есть
     * «текущая стоимость равна нулю». Отдельной ручки архивации в API нет, и заводить её
     * ради одного места не стоит — модель капитала уже отвечает на этот вопрос.
     */
    const handleArchive = async (item: CapitalItem) => {
        setArchivingId(item.id);
        try {
            await addCapitalRevaluation(item.id, { value: 0 });
            setHintFor(prev => prev && {
                ...prev,
                items: prev.items.filter(i => i.id !== item.id),
            });
            await onChanged();
            showToast(copy.capitalHintArchived);
        } catch (e) {
            showToast((e as Error).message);
        } finally {
            setArchivingId(null);
        }
    };

    return (
        <div className="rounded-2xl p-5 space-y-2"
            style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}>
            <div className="flex items-center justify-between">
                <h2 className="font-semibold text-sm" style={{ color: 'var(--color-text-muted)' }}>
                    {copy.sectionTitle}
                </h2>
                {!creating && !editing && (
                    <button onClick={() => { setCreating(true); setFormError(null); }}
                        className="rounded-lg px-2 py-1.5"
                        style={{ background: 'var(--color-accent)', color: '#fff' }}>
                        <Plus size={16} />
                    </button>
                )}
            </div>
            <p className="text-xs" style={{ color: 'var(--color-text-muted)' }}>{copy.sectionHint}</p>

            {creating && (
                <AccountForm initial={null} categories={categories} error={formError}
                    onSubmit={handleCreate} onCancel={() => setCreating(false)} />
            )}

            {hintFor && (
                <CapitalLiabilityHint
                    accountName={hintFor.name}
                    items={hintFor.items}
                    busyId={archivingId}
                    onArchive={handleArchive}
                    onSkip={() => setHintFor(null)}
                />
            )}

            {accounts.length === 0 && !creating ? (
                <p className="text-sm py-1" style={{ color: 'var(--color-text-muted)' }}>{copy.empty}</p>
            ) : accounts.map(a => (
                editing?.id === a.id ? (
                    <AccountForm key={a.id} initial={a} categories={categories} error={formError}
                        onSubmit={handleUpdate} onCancel={() => setEditing(null)} />
                ) : (
                    <AccountCard key={a.id} account={a}
                        onEdit={acc => { setEditing(acc); setFormError(null); }}
                        onDelete={handleDelete}
                        onMakeDefault={handleMakeDefault}
                        onRaiseFloor={handleRaiseFloor} />
                )
            ))}
        </div>
    );
}
