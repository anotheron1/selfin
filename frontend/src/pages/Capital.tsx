import { useCallback, useEffect, useState } from 'react';
import { fetchAccounts, fetchCapitalSummary, fetchCapitalTrajectory, fetchCategories, fetchFunds } from '../api';
import type { Account, CapitalSummary, CapitalTrajectory, Category, TargetFund } from '../types/api';
import { Button } from '@/ds';
import MoneyView from '../components/money/MoneyView';
import CapitalItemSheet, { type ItemSheetMode } from '../components/money/CapitalItemSheet';
import AccountSheet, { type AccountSheetMode } from '../components/accounts/AccountSheet';
import { REANCHOR } from '../components/frame/AppFrame';
import { CHANGED } from '../api/client';
import { todayIso } from '../lib/factDate';

/**
 * «Деньги» (срез 4, п. 9). Адрес /capital прежний, страница новая: группы карт, капитал, имущество и долги, график.
 * Вид — components/money/MoneyView; здесь чтения и листы. «Обновить остатки» до PR 5 открывает прежний лист остатка
 * основной — тот же, что плитка «на картах» в шапке (`REANCHOR`, AppFrame), «Добавить карту» и лист карты до PR 4 — прежние формы «Счетов» (`AccountSheet`).
 */
interface Data {
    summary: CapitalSummary;
    accounts: Account[];
    funds: TargetFund[];
    points: CapitalTrajectory['points'];
    categories: Category[];
}

export default function Capital({ refreshSignal }: { refreshSignal?: number }) {
    const [data, setData] = useState<Data | null>(null);
    const [loadFailed, setLoadFailed] = useState(false);
    const [itemSheet, setItemSheet] = useState<ItemSheetMode | null>(null);
    const [cardSheet, setCardSheet] = useState<AccountSheetMode | null>(null);
    const [toast, setToast] = useState<string | null>(null);

    const reload = useCallback(() => {
        setLoadFailed(false);
        Promise.all([fetchCapitalSummary(), fetchAccounts(), fetchFunds(), fetchCapitalTrajectory(), fetchCategories()])
            .then(([summary, accounts, funds, trajectory, categories]) =>
                setData({ summary, accounts, funds: funds.funds, points: trajectory.points, categories }))
            .catch(() => setLoadFailed(true));
    }, []);

    useEffect(() => { reload(); }, [reload, refreshSignal]);
    // Любая удачная запись — листы этой страницы, остаток, «+» — меняет капитал и карты (событие api/client).
    useEffect(() => {
        window.addEventListener(CHANGED, reload);
        return () => window.removeEventListener(CHANGED, reload);
    }, [reload]);
    useEffect(() => {
        if (!toast) return;
        const t = setTimeout(() => setToast(null), 3000);
        return () => clearTimeout(t);
    }, [toast]);

    // ANO-245: «Ошибка: Error: API error: …» — текст сервера по-английски; что сделать, не говорил.
    if (loadFailed) return (
        <div className="p-6 text-center font-sf text-sf-ink">
            <p className="mb-3 text-sf-body">Не удалось загрузить капитал</p>
            <Button size="s" onClick={() => reload()}>Повторить</Button>
        </div>
    );
    if (!data) return <div className="p-4 font-sf text-sf-note text-sf-muted">Загрузка…</div>;


    return (
        <>
            <MoneyView summary={data.summary} accounts={data.accounts} funds={data.funds} points={data.points}
                today={todayIso(new Date())}
                onUpdate={() => window.dispatchEvent(new Event(REANCHOR))}
                onAddCard={preset => setCardSheet({ type: 'create', preset })}
                onAccount={account => setCardSheet({ type: 'view', account })}
                onItem={item => setItemSheet({ type: 'view', item })}
                onAddItem={() => setItemSheet({ type: 'create' })} />

            {itemSheet && <CapitalItemSheet key={itemSheet.type === 'view' ? itemSheet.item.id : 'new'} mode={itemSheet}
                onClose={() => setItemSheet(null)} />}
            {cardSheet && <AccountSheet key={cardSheet.type === 'view' ? cardSheet.account.id : 'new'} mode={cardSheet}
                categories={data.categories} onClose={() => setCardSheet(null)} onChanged={setToast} />}

            {toast && (
                <div role="status" className="fixed inset-x-4 bottom-[96px] z-40 mx-auto max-w-[420px] rounded-sf-plate bg-sf-ink px-4 py-3 text-center font-sf text-sf-note text-sf-page lg:bottom-6">
                    {toast}
                </div>
            )}
        </>
    );
}
