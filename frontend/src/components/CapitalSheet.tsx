import { useState, useEffect } from 'react';
import { Sheet, SheetContent, SheetHeader, SheetTitle } from './ui/sheet';
import { Input } from './ui/input';
import { Button } from './ui/button';
import { Badge } from './ui/badge';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter } from './ui/dialog';
import {
    createCapitalItem, updateCapitalItem, deleteCapitalItem,
    addCapitalRevaluation, fetchCapitalItem,
} from '../api';
import type { CapitalItem, CapitalItemKind } from '../types/api';
import CapitalRevaluationHistory from './CapitalRevaluationHistory';

const fmt = (n: number) =>
    new Intl.NumberFormat('ru-RU', { style: 'currency', currency: 'RUB', minimumFractionDigits: 0 }).format(n);

export type CapitalSheetMode =
    | { type: 'create'; kind: CapitalItemKind }
    | { type: 'view'; itemId: string };

interface Props {
    open: boolean;
    mode: CapitalSheetMode | null;
    onClose: () => void;
    onChanged: () => void;
}

export default function CapitalSheet({ open, mode, onClose, onChanged }: Props) {
    const [item, setItem] = useState<CapitalItem | null>(null);
    const [name, setName] = useState('');
    const [description, setDescription] = useState('');
    const [revValue, setRevValue] = useState('');
    const [revDate, setRevDate] = useState(() => new Date().toISOString().slice(0, 10));
    const [revNote, setRevNote] = useState('');
    const [historyTick, setHistoryTick] = useState(0);
    const [loading, setLoading] = useState(false);
    const [hardDeleteOpen, setHardDeleteOpen] = useState(false);
    const [disposalOpen, setDisposalOpen] = useState(false);
    const [disposalDate, setDisposalDate] = useState(() => new Date().toISOString().slice(0, 10));

    useEffect(() => {
        if (mode?.type === 'view') {
            fetchCapitalItem(mode.itemId).then(i => {
                setItem(i);
                setName(i.name);
                setDescription(i.description ?? '');
            });
        } else {
            setItem(null);
            setName('');
            setDescription('');
        }
        setRevValue('');
        setRevNote('');
        setRevDate(new Date().toISOString().slice(0, 10));
    }, [mode]);

    if (!mode) return null;

    const isCreate = mode.type === 'create';
    const kind: CapitalItemKind = isCreate ? mode.kind : item?.kind ?? 'ASSET';
    const kindLabel = kind === 'ASSET' ? 'Актив' : 'Обязательство';

    const handleSaveCreate = async () => {
        if (!name.trim() || !revValue) return;
        setLoading(true);
        try {
            await createCapitalItem({
                kind,
                name: name.trim(),
                description: description || undefined,
                initialValue: Number(revValue),
                initialValuedAt: revDate,
            });
            onChanged();
            onClose();
        } finally { setLoading(false); }
    };

    const handleSaveMetadata = async () => {
        if (!item) return;
        setLoading(true);
        try {
            await updateCapitalItem(item.id, { name: name.trim(), description: description || undefined });
            onChanged();
        } finally { setLoading(false); }
    };

    const handleAddRevaluation = async () => {
        if (!item || !revValue) return;
        setLoading(true);
        try {
            await addCapitalRevaluation(item.id, {
                value: Number(revValue),
                valuedAt: revDate,
                note: revNote || undefined,
            });
            setRevValue('');
            setRevNote('');
            setHistoryTick(t => t + 1);
            const fresh = await fetchCapitalItem(item.id);
            setItem(fresh);
            onChanged();
        } finally { setLoading(false); }
    };

    const handleDisposeConfirm = async () => {
        if (!item || !disposalDate) return;
        setLoading(true);
        try {
            await addCapitalRevaluation(item.id, { value: 0, valuedAt: disposalDate });
            setDisposalOpen(false);
            onChanged();
            onClose();
        } finally { setLoading(false); }
    };

    const handleHardDeleteConfirm = async () => {
        if (!item) return;
        setLoading(true);
        try {
            await deleteCapitalItem(item.id);
            setHardDeleteOpen(false);
            onChanged();
            onClose();
        } finally { setLoading(false); }
    };

    return (
        <Sheet open={open} onOpenChange={o => !o && onClose()}>
            <SheetContent side="right" className="w-full sm:max-w-md overflow-y-auto">
                <SheetHeader>
                    <SheetTitle>
                        {isCreate ? `Новый ${kindLabel.toLowerCase()}` : item?.name}
                        <Badge variant="outline" className="ml-2">{kindLabel}</Badge>
                    </SheetTitle>
                </SheetHeader>

                <div className="mt-4 space-y-4">
                    {/* Имя и описание */}
                    <div className="space-y-2">
                        <Input
                            placeholder="Название (например, Квартира на Ленинской)"
                            value={name}
                            onChange={e => setName(e.target.value)}
                        />
                        <Input
                            placeholder="Заметка (необязательно)"
                            value={description}
                            onChange={e => setDescription(e.target.value)}
                        />
                        {!isCreate && item && (
                            <Button size="sm" variant="outline" onClick={handleSaveMetadata} disabled={loading}>
                                Сохранить заголовок
                            </Button>
                        )}
                    </div>

                    {/* Текущая стоимость */}
                    {!isCreate && item && (
                        <div className="rounded p-3" style={{ background: 'var(--color-bg)' }}>
                            <div className="text-xs uppercase tracking-wide" style={{ color: 'var(--color-text-muted)' }}>
                                Текущая стоимость
                            </div>
                            <div className="text-2xl font-semibold mt-1">{fmt(item.currentValue)}</div>
                            {item.lastValuedAt && (
                                <div className="text-xs mt-1" style={{ color: 'var(--color-text-muted)' }}>
                                    {new Date(item.lastValuedAt).toLocaleDateString('ru-RU')}
                                </div>
                            )}
                        </div>
                    )}

                    {/* Форма переоценки / создания */}
                    <div className="space-y-2">
                        <h3 className="text-sm font-medium">{isCreate ? 'Стоимость' : 'Переоценить'}</h3>
                        <Input
                            type="number"
                            placeholder="Сумма, ₽"
                            value={revValue}
                            onChange={e => setRevValue(e.target.value)}
                        />
                        <Input
                            type="date"
                            value={revDate}
                            max={new Date().toISOString().slice(0, 10)}
                            onChange={e => setRevDate(e.target.value)}
                        />
                        {!isCreate && (
                            <Input
                                placeholder="Заметка (например, по объявлениям ЦИАН)"
                                value={revNote}
                                onChange={e => setRevNote(e.target.value)}
                            />
                        )}
                        <Button
                            onClick={isCreate ? handleSaveCreate : handleAddRevaluation}
                            disabled={loading || !revValue || (isCreate && !name.trim())}
                            className="w-full">
                            {isCreate ? 'Создать' : 'Сохранить переоценку'}
                        </Button>
                    </div>

                    {/* История */}
                    {!isCreate && item && (
                        <div>
                            <h3 className="text-sm font-medium mb-2">История переоценок</h3>
                            <CapitalRevaluationHistory
                                itemId={item.id}
                                refreshSignal={historyTick}
                                onChanged={() => { setHistoryTick(t => t + 1); onChanged(); }}
                            />
                        </div>
                    )}

                    {/* Опасные операции */}
                    {!isCreate && item && (
                        <div className="pt-4 border-t space-y-2" style={{ borderColor: 'var(--color-border)' }}>
                            <h3 className="text-sm font-medium" style={{ color: 'var(--color-text-muted)' }}>
                                Опасные операции
                            </h3>
                            {disposalOpen ? (
                                <div className="rounded p-3 space-y-2" style={{ background: 'var(--color-bg)' }}>
                                    <div className="text-xs">Дата выбытия:</div>
                                    <Input
                                        type="date"
                                        value={disposalDate}
                                        max={new Date().toISOString().slice(0, 10)}
                                        onChange={e => setDisposalDate(e.target.value)} />
                                    <div className="flex gap-2">
                                        <Button size="sm" onClick={handleDisposeConfirm} disabled={loading}>
                                            Подтвердить
                                        </Button>
                                        <Button size="sm" variant="ghost" onClick={() => setDisposalOpen(false)}>
                                            Отмена
                                        </Button>
                                    </div>
                                </div>
                            ) : (
                                <Button variant="outline" size="sm" onClick={() => setDisposalOpen(true)} disabled={loading} className="w-full">
                                    {kind === 'ASSET' ? 'Отметить выбытие (продан / утерян)' : 'Отметить закрытие (кредит погашен)'}
                                </Button>
                            )}
                            <Button variant="destructive" size="sm" onClick={() => setHardDeleteOpen(true)} disabled={loading} className="w-full">
                                Удалить безвозвратно
                            </Button>
                        </div>
                    )}
                </div>

                {/* Подтверждение hard-delete через Dialog (alert-dialog нет в проекте) */}
                <Dialog open={hardDeleteOpen} onOpenChange={setHardDeleteOpen}>
                    <DialogContent className="max-w-sm">
                        <DialogHeader>
                            <DialogTitle>Удалить «{item?.name}» безвозвратно?</DialogTitle>
                        </DialogHeader>
                        <div className="text-sm" style={{ color: 'var(--color-text-muted)' }}>
                            История переоценок и весь вклад в траекторию капитала исчезнут. Это нельзя отменить.
                            Используйте это, если запись создана по ошибке. Если же актив продан или кредит закрыт —
                            лучше «Отметить выбытие», тогда история останется.
                        </div>
                        <DialogFooter className="flex gap-2">
                            <Button variant="ghost" onClick={() => setHardDeleteOpen(false)}>Отмена</Button>
                            <Button variant="destructive" onClick={handleHardDeleteConfirm} disabled={loading}>
                                Удалить
                            </Button>
                        </DialogFooter>
                    </DialogContent>
                </Dialog>
            </SheetContent>
        </Sheet>
    );
}
