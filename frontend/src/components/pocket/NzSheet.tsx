import { useEffect, useState } from 'react';
import { Plus } from 'lucide-react';
import { Button, Chip, Choices, Field } from '@/ds';
import { fetchAccounts, fetchFunds, fetchPocket, updateFund } from '../../api';
import type { Account, PocketResponse, TargetFund } from '../../types/api';
import FormSheet from '../FormSheet';
import AddCardSheet from '../money/AddCardSheet';
import { cardAmount } from '../../lib/moneyCard';
import {
    NZ_SUB, NZ_TITLE, NZ_WHERE_NOTE, reserveAccounts, reserveDto, reserveFreeLine, reserveOf,
} from '../../lib/reserve';
import { writeFailure } from '../../lib/writeFailure';

type Where = 'card' | 'account';

/**
 * Лист НЗ (срез 4, п. 19; доска S4-G2; на 1440 — окном): «Сколько держать», «Где лежит: на основной карте | на своём
 * счёте»; счёта нет — заводится здесь же ответом «накопительная». Заменил прежний лист с одной суммой и раздел
 * «Настроек» (опись 37–38). Открывают «почему столько» на «Сегодня», карточка кармашка и карточка НЗ в «Целях».
 */
export default function NzSheet({ open, onOpenChange, onSuccess, toAccount = false }: {
    open: boolean;
    onOpenChange: (v: boolean) => void;
    onSuccess: () => void;
    /** «Перенести на свой счёт» с карточки НЗ — лист открывается с «На своём счёте». */
    toAccount?: boolean;
}) {
    if (!open) return null;
    return <NzLoader toAccount={toAccount} onClose={() => onOpenChange(false)} onSaved={() => { onOpenChange(false); onSuccess(); }} />;
}

function NzHead() {
    return (
        <div className="flex flex-col gap-1 px-1 pr-10">
            <span className="text-[18px] font-semibold leading-6">{NZ_TITLE}</span>
            <span className="text-sf-label text-sf-muted">{NZ_SUB}</span>
        </div>
    );
}

function NzLoader({ toAccount, onClose, onSaved }: { toAccount: boolean; onClose: () => void; onSaved: () => void }) {
    const [funds, setFunds] = useState<TargetFund[] | null>(null);
    const [accounts, setAccounts] = useState<Account[]>([]);
    const [pocket, setPocket] = useState<PocketResponse | null>(null);
    const [failed, setFailed] = useState(false);
    useEffect(() => {
        Promise.all([fetchFunds(), fetchAccounts()])
            .then(([d, a]) => { setFunds(d.funds); setAccounts(a); })
            .catch(() => setFailed(true));
        fetchPocket().then(setPocket).catch(() => {});
    }, []);
    const nz = funds ? reserveOf(funds) : undefined;
    if (!nz) {
        return (
            <FormSheet label={NZ_TITLE} onClose={onClose}>
                <NzHead />
                <span className="px-1 text-sf-note text-sf-muted">{failed ? 'Не удалось загрузить НЗ — закрой и открой снова' : 'Загрузка…'}</span>
            </FormSheet>
        );
    }
    return <NzForm nz={nz} funds={funds ?? []} accounts={accounts} pocket={pocket} toAccount={toAccount} onClose={onClose}
        onSaved={onSaved} onAccountAdded={(a) => setAccounts(prev => [...prev, a])} />;
}

/** Сам лист — по пришедшему НЗ; отдельно от загрузки, чтобы разметку обоих «Где лежит» видел тест. */
export function NzForm({ nz, funds, accounts, pocket, toAccount, onClose, onSaved, onAccountAdded }: {
    nz: TargetFund;
    funds: TargetFund[];
    accounts: Account[];
    pocket: PocketResponse | null;
    toAccount: boolean;
    onClose: () => void;
    onSaved: () => void;
    onAccountAdded: (a: Account) => void;
}) {
    const [where, setWhere] = useState<Where>(toAccount || nz.accountId ? 'account' : 'card');
    const [accountId, setAccountId] = useState<string | null>(nz.accountId);
    const [keep, setKeep] = useState(nz.targetAmount ? String(nz.targetAmount) : '');
    const [adding, setAdding] = useState(false);
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState<string | null>(null);

    const choices = reserveAccounts(accounts, funds, nz);
    const amount = cardAmount(keep);
    const onAccount = where === 'account';
    const canSave = !busy && (onAccount ? accountId != null : amount == null || Number.isFinite(amount));
    const line = onAccount ? null : reserveFreeLine(pocket);

    const save = async () => {
        if (!canSave) return;
        setBusy(true);
        setError(null);
        try {
            await updateFund(nz.id, reserveDto(nz, { accountId: onAccount ? accountId : null, keep: amount ?? 0 }));
            onSaved();
        } catch (err) {
            setError(writeFailure(err));
        } finally {
            setBusy(false);
        }
    };

    // Лист добавления встаёт вместо листа НЗ: два окна разом не открываются.
    if (adding) {
        return (
            <AddCardSheet purpose="SAVINGS" categories={[]} onClose={() => setAdding(false)}
                onAdded={(a) => { setAdding(false); onAccountAdded(a); setAccountId(a.id); }} />
        );
    }
    return (
        <FormSheet label={NZ_TITLE} onClose={onClose}>
            <NzHead />
            {!onAccount && (
                <div className="flex flex-col gap-1.5">
                    <span className="px-1 text-sf-label text-sf-muted">Сколько держать</span>
                    <Field look="amount" aria-label="Сколько держать" inputMode="decimal" autoComplete="off" placeholder="0"
                        suffix="₽" value={keep} onChange={(e) => setKeep(e.target.value)}
                        invalid={amount != null && !Number.isFinite(amount)} />
                </div>
            )}
            <div className="flex flex-col gap-1.5">
                <span className="px-1 text-sf-label text-sf-muted">Где лежит</span>
                <Choices name="nz-where" label="Где лежит" size="tile" value={where}
                    options={[{ value: 'card', label: 'На основной карте' }, { value: 'account', label: 'На своём счёте' }]}
                    onChange={(v) => setWhere(v as Where)} />
            </div>
            {onAccount && (
                <div className="flex flex-wrap gap-1.5">
                    <Choices name="nz-account" label="Счёт НЗ" look="chip" className="contents" value={accountId ?? ''}
                        options={choices.filter(c => !c.taken).map(c => ({ value: c.id, label: c.label }))}
                        onChange={setAccountId} />
                    {choices.filter(c => c.taken).map(c => <Chip key={c.id} disabled>{c.label}</Chip>)}
                    <Chip onClick={() => setAdding(true)}><Plus size={14} strokeWidth={2} />Новый счёт</Chip>
                </div>
            )}
            <span className="px-1 text-sf-label text-sf-muted">{NZ_WHERE_NOTE}</span>
            {line && <span role="status" className="px-1 text-sf-note">{line}</span>}
            {error && <p className="px-1 text-sf-note text-sf-error">{error}</p>}
            <Button disabled={!canSave} onClick={save}>{busy ? 'Сохраняем…' : 'Сохранить'}</Button>
        </FormSheet>
    );
}
