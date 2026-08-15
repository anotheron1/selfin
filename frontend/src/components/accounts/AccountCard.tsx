import { Pencil, Trash2 } from 'lucide-react';
import type { Account } from '../../types/api';
import { canBeDefault, copy, fmtAmount, fmtDate, kindLine } from './accountsCopy';

/**
 * Карточка счёта (спека §5.3). Числа взаимоисключающие и выбираются тумблером «слежу за
 * остатком»: со слежением — остаток и дата якоря, без — «выделено за месяц» по категории-зоне.
 *
 * <p>Подпись именно «Выделено за месяц», а НЕ «Осталось»: если пользователь пишет траты той же
 * категории и с других карт, число смешает выделенное с потраченным мимо конверта. Остаток мы
 * обещать не можем и не должны.
 */
export default function AccountCard({ account, onEdit, onDelete, onMakeDefault, onRaiseFloor }: {
    account: Account;
    onEdit: (a: Account) => void;
    onDelete: (a: Account) => void;
    onMakeDefault: (a: Account) => void;
    onRaiseFloor: (a: Account, to: number) => void;
}) {
    const a = account;
    const credit = a.kind === 'CREDIT';

    return (
        <div className="py-3 px-1" style={{ borderBottom: '1px solid var(--color-border)' }}>
            <div className="flex items-start justify-between gap-2">
                <div className="min-w-0">
                    <div className="flex items-center gap-1.5 flex-wrap">
                        <span className="text-sm font-medium">{a.name}</span>
                        {a.isDefault && (
                            <span className="text-[10px] px-1.5 py-0.5 rounded-full"
                                title={copy.defaultHint}
                                style={{ background: 'var(--color-surface-2)', color: 'var(--color-accent)' }}>
                                {copy.defaultBadge}
                            </span>
                        )}
                    </div>
                    <div className="text-xs mt-0.5" style={{ color: 'var(--color-text-muted)' }}>
                        {kindLine(a.kind, a.purposeCategoryName)}
                    </div>
                </div>
                <div className="flex gap-1 shrink-0">
                    <button onClick={() => onEdit(a)} title="Редактировать"
                        className="p-1.5 rounded-lg" style={{ color: 'var(--color-text-muted)' }}>
                        <Pencil size={14} />
                    </button>
                    <button onClick={() => onDelete(a)} title="Удалить"
                        className="p-1.5 rounded-lg" style={{ color: 'var(--color-danger)' }}>
                        <Trash2 size={14} />
                    </button>
                </div>
            </div>

            <div className="mt-2 text-sm">
                {credit ? (
                    <CreditNumbers account={a} onRaiseFloor={onRaiseFloor} />
                ) : a.trackBalance ? (
                    a.balance == null ? (
                        <span style={{ color: 'var(--color-text-muted)' }}>{copy.balanceUnknown}</span>
                    ) : (
                        <span>
                            {fmtAmount(a.balance)}
                            {a.balanceDate && (
                                <span className="text-xs ml-2" style={{ color: 'var(--color-text-muted)' }}>
                                    {copy.balanceAt(fmtDate(a.balanceDate))}
                                </span>
                            )}
                        </span>
                    )
                ) : a.purposeCategoryName == null ? (
                    <span className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
                        {copy.allocatedNoCategory}
                    </span>
                ) : (
                    <span>
                        <span style={{ color: 'var(--color-text-muted)' }}>{copy.allocated}: </span>
                        {fmtAmount(a.allocatedThisMonth ?? 0)}
                        <span className="text-xs ml-2" style={{ color: 'var(--color-text-muted)' }}>
                            {copy.allocatedBy(a.purposeCategoryName)}
                        </span>
                    </span>
                )}
            </div>

            {!a.isDefault && canBeDefault(a.kind, a.trackBalance) && (
                <button onClick={() => onMakeDefault(a)}
                    className="mt-2 text-xs underline"
                    style={{ color: 'var(--color-text-muted)' }}>
                    {copy.makeDefault}
                </button>
            )}
        </div>
    );
}

/**
 * Числа кредитки. Показываем ДОСТУПНОЕ и рассчитанный долг — именно так пользователь думает
 * про карту (принцип №4 подхода: обязательство меряется планкой возврата, а не суммой долга).
 * Без чекпоинта счёт молчит: ни доступного, ни долга — считать нечем.
 */
function CreditNumbers({ account, onRaiseFloor }: {
    account: Account;
    onRaiseFloor: (a: Account, to: number) => void;
}) {
    const a = account;
    if (a.balance == null) {
        return <span style={{ color: 'var(--color-text-muted)' }}>{copy.balanceUnknown}</span>;
    }
    return (
        <div className="space-y-1">
            <div>
                <span style={{ color: 'var(--color-text-muted)' }}>{copy.available}: </span>
                {fmtAmount(a.balance)}
                {a.creditLimit != null && (
                    <span className="text-xs ml-1" style={{ color: 'var(--color-text-muted)' }}>
                        {copy.availableOf(fmtAmount(a.creditLimit))}
                    </span>
                )}
                {a.balanceDate && (
                    <span className="text-xs ml-2" style={{ color: 'var(--color-text-muted)' }}>
                        {copy.balanceAt(fmtDate(a.balanceDate))}
                    </span>
                )}
            </div>
            <div className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
                {a.debt != null && (
                    <span style={{ color: a.debt > 0 ? 'var(--color-danger)' : undefined }}>
                        {copy.debt}: {fmtAmount(a.debt)}
                    </span>
                )}
                {a.availableFloor != null && (
                    <span className="ml-2">{copy.floor}: {fmtAmount(a.availableFloor)}</span>
                )}
            </div>
            {a.floorSuggestion != null && (
                <button onClick={() => onRaiseFloor(a, a.floorSuggestion!)}
                    className="text-xs underline" style={{ color: 'var(--color-accent)' }}>
                    {copy.raiseFloor(fmtAmount(a.floorSuggestion))}
                </button>
            )}
        </div>
    );
}
