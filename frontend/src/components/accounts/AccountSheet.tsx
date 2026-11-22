import { useState } from 'react';
import {
    addCapitalRevaluation, createAccount, deleteAccount, fetchCapitalItems, makeAccountDefault, updateAccount,
} from '../../api';
import type { Account, AccountCreateDto, AccountKind, CapitalItem, Category } from '../../types/api';
import FormSheet from '../FormSheet';
import AccountCard from './AccountCard';
import AccountForm from './AccountForm';
import CapitalLiabilityHint from './CapitalLiabilityHint';
import { accountDeleteFailure, accountFormProblem, accountWriteFailure, copy } from './accountsCopy';
import { writeFailure } from '../../lib/writeFailure';
import type { CardPreset } from '../money/MoneyView';

/**
 * Листы карты на «Деньгах» до PR 4 среза 4 (задача 3.5): прежние карточка и форма из «Настроек» — правка, удаление,
 * «основная», планка, добавление и «Проверь Капитал» (опись 20–34). Лист карты по доскам S4-C* и вопрос
 * «Для чего эта карта?» их заменят в PR 4.
 */
export type AccountSheetMode = { type: 'create'; preset?: CardPreset } | { type: 'view'; account: Account };

/** Ответ «+» в группе (S4-A3) — природа и слежение формы. */
export const PRESETS: Record<CardPreset, { kind: AccountKind; trackBalance: boolean }> = {
    spending: { kind: 'DEBIT', trackBalance: false },
    savings: { kind: 'DEPOSIT', trackBalance: true },
    credit: { kind: 'CREDIT', trackBalance: true },
};

export default function AccountSheet({ mode, categories, onClose, onChanged }: {
    mode: AccountSheetMode;
    categories: Category[];
    onClose: () => void;
    /** Запись прошла — строка внизу экрана; экран перечитается сам по событию удачной записи (api/client). */
    onChanged: (msg: string) => void;
}) {
    const [editing, setEditing] = useState(mode.type === 'create');
    const [formError, setFormError] = useState<string | null>(null);
    const [notice, setNotice] = useState<string | null>(null);
    // Подсказка §7.2 — после создания кредитки, а не в форме: до сохранения неизвестно, кредитка это или нет.
    const [hintFor, setHintFor] = useState<{ name: string; items: CapitalItem[] } | null>(null);
    const [archivingId, setArchivingId] = useState<string | null>(null);
    const account = mode.type === 'view' ? mode.account : null;

    const done = (msg: string) => { onChanged(msg); onClose(); };

    // ANO-245: текст сервера на экран не идёт — он по-английски. То, что сервер отверг бы, форма говорит до отправки.
    const handleCreate = async (dto: AccountCreateDto) => {
        setFormError(null);
        const problem = accountFormProblem(dto, null);
        if (problem) { setFormError(problem); return; }
        try {
            await createAccount(dto);
        } catch (e) {
            setFormError(writeFailure(e));
            return;
        }
        if (dto.kind === 'CREDIT' && await offerCapitalCleanup(dto.name)) { onChanged(copy.created); return; }
        done(copy.created);
    };

    const handleUpdate = async (dto: AccountCreateDto) => {
        if (!account) return;
        setFormError(null);
        const problem = accountFormProblem(dto, account);
        if (problem) { setFormError(problem); return; }
        try {
            await updateAccount(account.id, dto);
            done(copy.updated);
        } catch (e) {
            setFormError(accountWriteFailure(e, dto, account));
        }
    };

    const handleDelete = async (a: Account) => {
        if (a.isDefault) { setNotice(copy.deleteDefaultError); return; }
        if (!confirm(copy.deleteConfirm)) return;
        try {
            await deleteAccount(a.id);
            done(copy.deleted);
        } catch (e) {
            setNotice(accountDeleteFailure(e));
        }
    };

    const handleMakeDefault = async (a: Account) => {
        try {
            await makeAccountDefault(a.id);
            done(copy.defaultChanged);
        } catch (e) {
            setNotice(writeFailure(e));
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
            done(copy.floorRaised);
        } catch (e) {
            setNotice(writeFailure(e));
        }
    };

    /** Есть живые долги в «Капитале» — показать подсказку и не закрывать лист. */
    const offerCapitalCleanup = async (accountName: string): Promise<boolean> => {
        try {
            const live = (await fetchCapitalItems('LIABILITY')).filter(i => !i.isArchived);
            if (live.length === 0) return false;
            setHintFor({ name: accountName, items: live });
            return true;
        } catch {
            return false; // Подсказка необязательна: счёт уже создан.
        }
    };

    /** Архивация записи капитала = переоценка в ноль: отдельной ручки нет, модель капитала уже отвечает на этот вопрос. */
    const handleArchive = async (item: CapitalItem) => {
        setArchivingId(item.id);
        try {
            await addCapitalRevaluation(item.id, { value: 0 });
            // Последний долг убран — подсказке нечего показывать, лист закрывается, а не висит пустым (ревью Gemini на #241).
            const left = hintFor?.items.filter(i => i.id !== item.id) ?? [];
            if (left.length === 0) { done(copy.capitalHintArchived); return; }
            setHintFor(prev => prev && { ...prev, items: left });
            onChanged(copy.capitalHintArchived);
        } catch (e) {
            setNotice(writeFailure(e));
        } finally {
            setArchivingId(null);
        }
    };

    return (
        <FormSheet label={account ? account.name : 'Добавить карту'} onClose={onClose}>
            <h2 className="pr-10 text-[20px] font-semibold">{account ? account.name : 'Добавить карту'}</h2>
            {hintFor ? (
                <CapitalLiabilityHint accountName={hintFor.name} items={hintFor.items} busyId={archivingId}
                    onArchive={handleArchive} onSkip={onClose} />
            ) : editing ? (
                <AccountForm initial={account} preset={mode.type === 'create' && mode.preset ? PRESETS[mode.preset] : undefined}
                    categories={categories} error={formError}
                    onSubmit={account ? handleUpdate : handleCreate}
                    onCancel={account ? () => setEditing(false) : onClose} />
            ) : account && (
                <AccountCard account={account}
                    onEdit={() => { setEditing(true); setFormError(null); setNotice(null); }}
                    onDelete={handleDelete}
                    onMakeDefault={handleMakeDefault}
                    onRaiseFloor={handleRaiseFloor} />
            )}
            {notice && <p role="alert" className="text-sf-note text-sf-error">{notice}</p>}
        </FormSheet>
    );
}
