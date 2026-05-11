import { useEffect, useState, useCallback } from 'react';
import { HelpCircle } from 'lucide-react';
import { fetchCapitalSummary } from '../api';
import type { CapitalSummary, CapitalItem, CapitalItemKind } from '../types/api';
import CapitalSummaryCard from '../components/CapitalSummaryCard';
import CapitalItemList from '../components/CapitalItemList';
import CapitalSheet, { type CapitalSheetMode } from '../components/CapitalSheet';
import CapitalTrajectoryChart from '../components/CapitalTrajectoryChart';
import CapitalTheoryDialog from '../components/CapitalTheoryDialog';
import { Button } from '../components/ui/button';

interface Props {
    refreshSignal?: number;
}

export default function Capital({ refreshSignal }: Props) {
    const [summary, setSummary] = useState<CapitalSummary | null>(null);
    const [error, setError] = useState<string | null>(null);
    const [sheetMode, setSheetMode] = useState<CapitalSheetMode | null>(null);
    const [theoryOpen, setTheoryOpen] = useState(false);
    const [refreshTick, setRefreshTick] = useState(0);

    const reload = useCallback(() => {
        fetchCapitalSummary()
            .then(setSummary)
            .catch(e => setError(String(e)));
    }, []);

    useEffect(() => { reload(); }, [reload, refreshSignal, refreshTick]);

    const onChanged = () => setRefreshTick(t => t + 1);
    const openCreate = (kind: CapitalItemKind) => setSheetMode({ type: 'create', kind });
    const openView = (item: CapitalItem) => setSheetMode({ type: 'view', itemId: item.id });

    if (error) return <div className="p-4 text-sm text-red-400">Ошибка: {error}</div>;
    if (!summary) return <div className="p-4 text-sm" style={{ color: 'var(--color-text-muted)' }}>Загрузка…</div>;

    const isEmpty = summary.items.length === 0;

    return (
        <div className="max-w-2xl mx-auto p-4 space-y-4 pb-24">
            <header className="flex items-center justify-between">
                <h1 className="text-xl font-semibold">Капитал</h1>
                <Button variant="ghost" size="icon" aria-label="Что такое капитал?" onClick={() => setTheoryOpen(true)}>
                    <HelpCircle size={20} />
                </Button>
            </header>

            {isEmpty ? (
                <EmptyState onCreate={openCreate} onTheory={() => setTheoryOpen(true)} />
            ) : (
                <>
                    <CapitalSummaryCard summary={summary} />
                    <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                        <CapitalItemList
                            kind="ASSET"
                            items={summary.items.filter(i => i.kind === 'ASSET')}
                            onItemClick={openView}
                            onCreate={() => openCreate('ASSET')} />
                        <CapitalItemList
                            kind="LIABILITY"
                            items={summary.items.filter(i => i.kind === 'LIABILITY')}
                            onItemClick={openView}
                            onCreate={() => openCreate('LIABILITY')} />
                    </div>
                    <CapitalTrajectoryChart refreshSignal={refreshTick} />
                </>
            )}

            <CapitalSheet
                open={sheetMode !== null}
                mode={sheetMode}
                onClose={() => setSheetMode(null)}
                onChanged={onChanged}
            />
            <CapitalTheoryDialog open={theoryOpen} onClose={() => setTheoryOpen(false)} />
        </div>
    );
}

function EmptyState({ onCreate, onTheory }: { onCreate: (k: CapitalItemKind) => void; onTheory: () => void }) {
    return (
        <div className="text-center py-16 px-4">
            <h2 className="text-lg font-semibold mb-2">Здесь будет ваш капитал</h2>
            <p className="text-sm mb-6 max-w-md mx-auto" style={{ color: 'var(--color-text-muted)' }}>
                Добавьте первый актив (квартира, авто, валюта, ценное имущество) или обязательство (ипотека, кредит).
                Деньги на счетах и копилки уже считаются автоматически.
            </p>
            <div className="flex gap-2 justify-center flex-wrap">
                <Button onClick={() => onCreate('ASSET')}>+ Добавить актив</Button>
                <Button variant="outline" onClick={() => onCreate('LIABILITY')}>+ Добавить обязательство</Button>
                <Button variant="ghost" onClick={onTheory}>Что такое капитал? →</Button>
            </div>
        </div>
    );
}
