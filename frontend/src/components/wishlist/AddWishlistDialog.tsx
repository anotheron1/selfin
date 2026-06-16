import { useEffect, useState } from 'react';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter } from '../ui/dialog';
import { Button } from '../ui/button';
import { Input } from '../ui/input';
import {
    createEvent, createFund, fetchCategories,
    setEventWishlistStatus, setFundWishlistStatus,
} from '../../api';
import type { Category, WishlistKind } from '../../types/api';

interface Props {
    open: boolean;
    onClose: () => void;
    /** Вызывается после успешного создания — родитель делает refetch(). */
    onCreated: () => void;
}

const KIND_LABEL: Record<WishlistKind, string> = {
    WISHLIST: 'Хотелка',
    SAVINGS: 'Копилка',
    CREDIT: 'Кредит',
};

const SYSTEM_WISHLIST_CATEGORY = 'Хотелки';

/**
 * Диалог создания нового item'а: тип (хотелка/копилка/кредит) + название/сумма/дата,
 * для кредита — ставка и срок.
 *
 * <p><b>Двухшаговый сетевой флоу + режим отказа:</b> создание — это ДВА последовательных
 * запроса: (1) POST сущности (event/fund), (2) PATCH wishlist-status=OPEN. Если POST прошёл,
 * а PATCH упал, сущность остаётся с {@code wishlist_status=NULL} и НЕ видна на /wishlist
 * («осиротевшая» сущность). Допустимое поведение для этого PR: показать ошибку и инициировать
 * refetch() родителя, чтобы пользователь мог увидеть/повторить; осиротевшая сущность терпима
 * (fund виден на /funds, event — это просто недатированная LOW-хотелка). Компенсирующее
 * удаление НЕ делаем — иначе риск удалить чужую запись при гонке. Оба вызова обёрнуты в
 * try/catch; при падении PATCH показываем ошибку и оставляем диалог открытым для ретрая.
 */
export default function AddWishlistDialog({ open, onClose, onCreated }: Props) {
    const [kind, setKind] = useState<WishlistKind>('WISHLIST');
    const [name, setName] = useState('');
    const [amount, setAmount] = useState('');
    const [targetDate, setTargetDate] = useState('');
    const [rate, setRate] = useState('');
    const [term, setTerm] = useState('');
    const [categories, setCategories] = useState<Category[]>([]);
    const [saving, setSaving] = useState(false);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        if (!open) return;
        setKind('WISHLIST');
        setName('');
        setAmount('');
        setTargetDate('');
        setRate('');
        setTerm('');
        setError(null);
        fetchCategories().then(setCategories).catch(() => {/* категория резолвится при сабмите */});
    }, [open]);

    const amountNum = amount.trim() === '' ? NaN : Number(amount);
    const canSubmit = name.trim() !== '' && !Number.isNaN(amountNum) && amountNum > 0 && targetDate !== ''
        && (kind !== 'CREDIT' || (rate.trim() !== '' && term.trim() !== ''));

    const handleSubmit = async () => {
        if (!canSubmit) return;
        setSaving(true);
        setError(null);
        try {
            if (kind === 'WISHLIST') {
                // Категория «Хотелки» (EXPENSE) — по соглашению из FinancialEventService.createWishlistItem.
                const cat = categories.find(c => c.name === SYSTEM_WISHLIST_CATEGORY && c.type === 'EXPENSE')
                    ?? categories.find(c => c.type === 'EXPENSE');
                if (!cat) {
                    setError('Нет ни одной категории расходов для хотелки');
                    setSaving(false);
                    return;
                }
                // ШАГ 1: POST события (LOW, дата = целевая).
                const created = await createEvent({
                    date: targetDate,
                    categoryId: cat.id,
                    type: 'EXPENSE',
                    priority: 'LOW',
                    plannedAmount: amountNum,
                    description: name.trim(),
                });
                // ШАГ 2: PATCH wishlist-status=OPEN. При падении — см. javadoc (без компенсации).
                await setEventWishlistStatus(created.id, 'OPEN');
            } else {
                // ШАГ 1: POST копилки/кредита.
                const created = await createFund({
                    name: name.trim(),
                    targetAmount: amountNum,
                    targetDate,
                    purchaseType: kind === 'CREDIT' ? 'CREDIT' : 'SAVINGS',
                    creditRate: kind === 'CREDIT' ? Number(rate) : undefined,
                    creditTermMonths: kind === 'CREDIT' ? Number(term) : undefined,
                });
                // ШАГ 2: PATCH wishlist-status=OPEN.
                await setFundWishlistStatus(created.id, 'OPEN');
            }
            onCreated();
            onClose();
        } catch (e) {
            // Покрывает обе фазы. Если упал PATCH — сущность осиротела (терпимо), даём ретрай.
            setError(e instanceof Error ? e.message : 'Не удалось создать. Попробуйте ещё раз.');
            onCreated(); // refetch, чтобы подтянуть актуальное состояние
        } finally {
            setSaving(false);
        }
    };

    const kinds: WishlistKind[] = ['WISHLIST', 'SAVINGS', 'CREDIT'];

    return (
        <Dialog open={open} onOpenChange={o => !o && onClose()}>
            <DialogContent>
                <DialogHeader>
                    <DialogTitle>Новая хотелка</DialogTitle>
                </DialogHeader>
                <div className="space-y-3 py-2">
                    <div className="flex gap-2">
                        {kinds.map(k => (
                            <Button
                                key={k}
                                type="button"
                                size="sm"
                                className="flex-1"
                                variant={kind === k ? 'default' : 'secondary'}
                                onClick={() => setKind(k)}>
                                {KIND_LABEL[k]}
                            </Button>
                        ))}
                    </div>

                    <Input
                        placeholder="Название"
                        value={name}
                        onChange={e => setName(e.target.value)}
                    />
                    <Input
                        type="number"
                        min="0"
                        placeholder="Сумма, ₽"
                        value={amount}
                        onChange={e => setAmount(e.target.value)}
                    />
                    <div>
                        <label className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
                            Целевая дата
                        </label>
                        <Input
                            type="date"
                            value={targetDate}
                            onChange={e => setTargetDate(e.target.value)}
                        />
                    </div>

                    {kind === 'CREDIT' && (
                        <div className="flex gap-2">
                            <div className="flex-1">
                                <label className="text-xs" style={{ color: 'var(--color-text-muted)' }}>Ставка, %</label>
                                <Input
                                    type="number"
                                    min="0.01"
                                    step="0.01"
                                    placeholder="напр. 18.5"
                                    value={rate}
                                    onChange={e => setRate(e.target.value)}
                                />
                            </div>
                            <div className="flex-1">
                                <label className="text-xs" style={{ color: 'var(--color-text-muted)' }}>Срок, мес.</label>
                                <Input
                                    type="number"
                                    min="1"
                                    step="1"
                                    placeholder="напр. 36"
                                    value={term}
                                    onChange={e => setTerm(e.target.value)}
                                />
                            </div>
                        </div>
                    )}

                    {error && <p className="text-sm text-destructive">{error}</p>}
                </div>
                <DialogFooter>
                    <Button variant="ghost" onClick={onClose}>Отмена</Button>
                    <Button onClick={handleSubmit} disabled={!canSubmit || saving}>
                        {saving ? 'Создаём...' : 'Создать'}
                    </Button>
                </DialogFooter>
            </DialogContent>
        </Dialog>
    );
}
