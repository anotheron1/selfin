import { useCallback, useEffect, useRef, useState } from 'react';
import {
    addCapitalRevaluation, fetchAccounts, fetchCapitalSummary, fetchCapitalTrajectory, fetchCategories, fetchCheckpoints, fetchFunds,
} from '../api';
import type {
    Account, AccountPurpose, BalanceCheckpoint, CapitalItem, CapitalSummary, CapitalTrajectory, Category, TargetFund,
} from '../types/api';
import { Button } from '@/ds';
import MoneyView from '../components/money/MoneyView';
import CapitalItemSheet, { type ItemSheetMode } from '../components/money/CapitalItemSheet';
import CardSheet, { type RecordPreset } from '../components/money/CardSheet';
import AddCardSheet from '../components/money/AddCardSheet';
import QuickAddModal from '../components/Fab';
import { openUpdateBalances } from '../components/frame/AppFrame';
import { CHANGED } from '../api/client';
import { todayIso } from '../lib/factDate';
import { cardHistory, creditCheckItems, liveCheckItems } from '../lib/moneyCard';
import { writeFailure } from '../lib/writeFailure';

/**
 * «Деньги» (срез 4, п. 9). Адрес /capital прежний, страница новая: группы карт, капитал, имущество и долги, график.
 * Вид — components/money/MoneyView; здесь чтения и листы. «Обновить остатки» — лист каркаса (`openUpdateBalances`,
 * AppFrame), тот же, что у плитки «на картах». Лист карты — CardSheet, «Добавить карту» — AddCardSheet (PR 4).
 */
interface Data {
    summary: CapitalSummary;
    accounts: Account[];
    funds: TargetFund[];
    points: CapitalTrajectory['points'];
    categories: Category[];
    checkpoints: BalanceCheckpoint[];
}

export default function Capital({ refreshSignal }: { refreshSignal?: number }) {
    const [data, setData] = useState<Data | null>(null);
    const [loadFailed, setLoadFailed] = useState(false);
    const [itemSheet, setItemSheet] = useState<ItemSheetMode | null>(null);
    /** Лист карты — по id: после записи экран перечитывается, и лист показывает свежие числа. */
    const [cardId, setCardId] = useState<string | null>(null);
    /** «Добавить карту»: 'ask' — с вопросом, ответ — «+» в группе. */
    const [adding, setAdding] = useState<AccountPurpose | 'ask' | null>(null);
    const [record, setRecord] = useState<RecordPreset | null>(null);
    /** «Не та же ли это кредитка?» — новая кредитка и живые ручные долги, о которых ещё не спросили. */
    const [check, setCheck] = useState<{ accountId: string; items: CapitalItem[]; busy: boolean } | null>(null);
    const [toast, setToast] = useState<string | null>(null);

    // Две записи подряд — два перечитывания; ответ первого может прийти последним (ревью #241).
    const seq = useRef(0);
    const loaded = useRef(false);
    const reload = useCallback(() => {
        const n = ++seq.current;
        setLoadFailed(false);
        Promise.all([fetchCapitalSummary(), fetchAccounts(), fetchFunds(), fetchCapitalTrajectory(), fetchCategories(), fetchCheckpoints()])
            .then(([summary, accounts, funds, trajectory, categories, checkpoints]) => {
                if (n !== seq.current) return;
                loaded.current = true;
                setData({ summary, accounts, funds: funds.funds, points: trajectory.points, categories, checkpoints });
            })
            .catch(() => {
                if (n !== seq.current) return;
                // Фоновое перечитывание не стирает экран с открытым листом: остаются прежние числа.
                if (!loaded.current) setLoadFailed(true);
            });
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

    const today = todayIso(new Date());
    // Удалённой карты в свежем списке нет — лист закрывается сам.
    const card = cardId ? data.accounts.find(a => a.id === cardId) : undefined;
    // Ревью Gemini на #243: долг погасили в другом листе — вопрос о нём уходит.
    const asked = check ? liveCheckItems(check.items, data.summary.items) : [];

    return (
        <>
            <MoneyView summary={data.summary} accounts={data.accounts} funds={data.funds} points={data.points}
                today={today}
                onUpdate={openUpdateBalances}
                onAddCard={purpose => setAdding(purpose ?? 'ask')}
                onAccount={account => setCardId(account.id)}
                onItem={item => setItemSheet({ type: 'view', item })}
                onAddItem={() => setItemSheet({ type: 'create' })}
                creditCheck={check && asked.length > 0 ? {
                    accountId: check.accountId, item: asked[0], busy: check.busy,
                    // «Убрать» — в «Архивные»: оценка ноль, как «Погасил» (п. 15); история остаётся.
                    onRemove: () => {
                        setCheck({ ...check, busy: true });
                        addCapitalRevaluation(asked[0].id, { value: 0 })
                            .then(() => { setCheck(null); setToast('Запись — в «Архивных»: долг посчитан один раз.'); })
                            .catch(e => { setCheck({ ...check, busy: false }); setToast(writeFailure(e)); });
                    },
                    onOther: () => setCheck(asked.length > 1 ? { ...check, items: asked.slice(1) } : null),
                } : null} />

            {itemSheet && <CapitalItemSheet key={itemSheet.type === 'view' ? itemSheet.item.id : 'new'} mode={itemSheet}
                onClose={() => setItemSheet(null)} />}
            {card && <CardSheet key={card.id} account={card} history={cardHistory(data.checkpoints, card.id)} funds={data.funds}
                categories={data.categories} today={today} onClose={() => setCardId(null)}
                onRecord={preset => { setCardId(null); setRecord(preset); }}
                onDone={msg => { setCardId(null); setToast(msg); }} />}
            {adding && <AddCardSheet purpose={adding === 'ask' ? undefined : adding} categories={data.categories}
                onClose={() => setAdding(null)}
                onAdded={account => {
                    setAdding(null);
                    setToast(account.kind === 'CREDIT' ? 'Кредитка добавлена.' : 'Карта добавлена.');
                    // П. 14: долг, записанный вручную раньше, посчитался бы второй раз рядом с долгом карты.
                    const items = account.kind === 'CREDIT' ? creditCheckItems(data.summary.items, account.name) : [];
                    setCheck(items.length ? { accountId: account.id, items, busy: false } : null);
                }} />}
            {record && <QuickAddModal preset={record} onClose={() => setRecord(null)} onSuccess={() => {}} />}

            {toast && (
                <div role="status" className="fixed inset-x-4 bottom-[96px] z-40 mx-auto max-w-[420px] rounded-sf-plate bg-sf-ink px-4 py-3 text-center font-sf text-sf-note text-sf-page lg:bottom-6">
                    {toast}
                </div>
            )}
        </>
    );
}
