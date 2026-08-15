import { useEffect, useState } from 'react';
import type { Account, AccountCreateDto, AccountKind, Category } from '../../types/api';
import { AmountInput, amountValue } from '../ui/amount-input';
import { canBeDefault, copy, kindLabels } from './accountsCopy';

const inputStyle = {
    background: 'var(--color-surface-2)',
    border: '1px solid var(--color-border)',
    color: 'var(--color-text)',
};

/** Дефолт тумблера по природе (спека §3.1). Зеркалит AccountService.defaultTracking. */
const defaultTracking = (kind: AccountKind) => kind !== 'CASH';

/**
 * Форма счёта: имя плюс природа, всё остальное — за кнопкой «Ещё настройки» (спека §5.3,
 * «в начале настраивать нечего»). Кредитные поля появляются только у кредитки, потому что
 * на других счетах база их всё равно отвергнет.
 */
export default function AccountForm({ initial, categories, onSubmit, onCancel, error }: {
    initial: Account | null;
    categories: Category[];
    onSubmit: (dto: AccountCreateDto) => void;
    onCancel: () => void;
    error: string | null;
}) {
    const [name, setName] = useState('');
    const [kind, setKind] = useState<AccountKind>('DEBIT');
    const [trackBalance, setTrackBalance] = useState(true);
    const [purposeCategoryId, setPurposeCategoryId] = useState('');
    const [creditLimit, setCreditLimit] = useState('');
    const [availableFloor, setAvailableFloor] = useState('');
    const [expanded, setExpanded] = useState(false);

    useEffect(() => {
        setName(initial?.name ?? '');
        setKind(initial?.kind ?? 'DEBIT');
        setTrackBalance(initial ? initial.trackBalance : true);
        setPurposeCategoryId(initial?.purposeCategoryId ?? '');
        setCreditLimit(initial?.creditLimit != null ? String(initial.creditLimit) : '');
        setAvailableFloor(initial?.availableFloor != null ? String(initial.availableFloor) : '');
        setExpanded(initial != null);
    }, [initial]);

    /** Смена природы переставляет тумблер на её дефолт — но только при создании: у живого
     *  счёта пользователь свой выбор уже сделал, и перетирать его молча нельзя. */
    const changeKind = (next: AccountKind) => {
        setKind(next);
        if (initial == null) setTrackBalance(defaultTracking(next));
        if (next === 'DEPOSIT') setTrackBalance(true);
        if (next !== 'CREDIT') { setCreditLimit(''); setAvailableFloor(''); }
    };

    const submit = (e: React.FormEvent) => {
        e.preventDefault();
        if (!name.trim()) return;
        onSubmit({
            name: name.trim(),
            kind,
            trackBalance,
            purposeCategoryId: purposeCategoryId || null,
            creditLimit: kind === 'CREDIT' ? amountValue(creditLimit) ?? null : null,
            availableFloor: kind === 'CREDIT' ? amountValue(availableFloor) ?? null : null,
        });
    };

    const trackingLocked = kind === 'DEPOSIT'
        || (initial?.isDefault === true && canBeDefault(kind, true));

    return (
        <form onSubmit={submit} className="space-y-3 py-2">
            <div className="flex gap-2">
                <input
                    autoFocus
                    value={name}
                    onChange={e => setName(e.target.value)}
                    placeholder={copy.namePlaceholder}
                    className="flex-1 rounded-lg px-3 py-2 text-sm min-w-0"
                    style={inputStyle}
                />
                <select
                    value={kind}
                    onChange={e => changeKind(e.target.value as AccountKind)}
                    className="rounded-lg px-2 py-2 text-sm"
                    style={inputStyle}>
                    {(Object.keys(kindLabels) as AccountKind[]).map(k => (
                        <option key={k} value={k}>{kindLabels[k]}</option>
                    ))}
                </select>
            </div>

            {!expanded && (
                <button type="button" onClick={() => setExpanded(true)}
                    className="text-xs underline" style={{ color: 'var(--color-text-muted)' }}>
                    {copy.moreSettings}
                </button>
            )}

            {expanded && (
                <div className="space-y-3">
                    <label className="flex items-start gap-2 cursor-pointer select-none">
                        <input
                            type="checkbox"
                            checked={trackBalance}
                            disabled={trackingLocked}
                            onChange={e => setTrackBalance(e.target.checked)}
                            className="accent-[var(--color-accent)] w-3.5 h-3.5 mt-0.5"
                        />
                        <span className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
                            <span style={{ color: 'var(--color-text)' }}>{copy.trackBalanceLabel}</span>
                            <br />{copy.trackBalanceHint}
                        </span>
                    </label>

                    <div>
                        <select
                            value={purposeCategoryId}
                            onChange={e => setPurposeCategoryId(e.target.value)}
                            className="w-full rounded-lg px-3 py-2 text-sm"
                            style={inputStyle}>
                            <option value="">{copy.purposeLabel}: {copy.purposeNone}</option>
                            {categories.filter(c => c.type === 'EXPENSE').map(c => (
                                <option key={c.id} value={c.id}>{c.name}</option>
                            ))}
                        </select>
                        <p className="text-[11px] mt-1" style={{ color: 'var(--color-text-muted)' }}>
                            {copy.purposeHint}
                        </p>
                    </div>

                    {kind === 'CREDIT' && (
                        <div className="space-y-2">
                            <AmountInput
                                value={creditLimit}
                                onChange={setCreditLimit}
                                placeholder={copy.creditLimitLabel}
                                className="w-full rounded-lg px-3 py-2 text-sm h-auto border-0"
                                style={inputStyle}
                            />
                            <AmountInput
                                value={availableFloor}
                                onChange={setAvailableFloor}
                                placeholder={copy.floorLabel}
                                className="w-full rounded-lg px-3 py-2 text-sm h-auto border-0"
                                style={inputStyle}
                            />
                            <p className="text-[11px]" style={{ color: 'var(--color-text-muted)' }}>
                                {copy.floorHint}
                            </p>
                        </div>
                    )}

                    <button type="button" onClick={() => setExpanded(false)}
                        className="text-xs underline" style={{ color: 'var(--color-text-muted)' }}>
                        {copy.lessSettings}
                    </button>
                </div>
            )}

            {error && (
                <p className="text-sm" style={{ color: 'var(--color-danger)' }}>{error}</p>
            )}

            <div className="flex gap-2">
                <button type="submit" disabled={!name.trim()}
                    className="rounded-lg px-3 py-2 text-sm font-medium disabled:opacity-50"
                    style={{ background: 'var(--color-accent)', color: '#fff' }}>
                    {initial ? 'Сохранить' : copy.addButton}
                </button>
                <button type="button" onClick={onCancel}
                    className="rounded-lg px-3 py-2 text-sm"
                    style={{ background: 'var(--color-surface-2)', color: 'var(--color-text-muted)' }}>
                    Отмена
                </button>
            </div>
        </form>
    );
}
