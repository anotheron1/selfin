import type { CapitalItem } from '../../types/api';
import { copy, fmtAmount } from './accountsCopy';

/**
 * Перенос кредиток из Капитала (спека §7.2). Автоматической миграции нет СОЗНАТЕЛЬНО: из
 * данных невозможно установить, записан там долг или доступный остаток, а ошибка сдвинет
 * капитал на десятки тысяч в любую сторону. Решение принимает человек, один раз.
 *
 * <p>Подсказка живёт в чанке 3, а не в чанке 4, хотя относится к нему: кредитный счёт можно
 * создать уже сейчас, и до появления подсказки обязательство считалось бы дважды — сначала
 * ручной записью в Капитале, потом вычисленным долгом счёта.
 */
export default function CapitalLiabilityHint({ accountName, items, onArchive, onSkip, busyId }: {
    accountName: string;
    items: CapitalItem[];
    onArchive: (item: CapitalItem) => void;
    onSkip: () => void;
    busyId: string | null;
}) {
    if (items.length === 0) return null;
    const ranked = [...items].sort((x, y) =>
        Number(namesLookAlike(y.name, accountName)) - Number(namesLookAlike(x.name, accountName)));

    return (
        <div className="rounded-xl p-3 space-y-2 mt-2"
            style={{ background: 'var(--color-surface-2)', border: '1px solid var(--color-border)' }}>
            <div className="text-sm font-medium">{copy.capitalHintTitle}</div>
            <p className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
                {copy.capitalHintBody}
            </p>
            {ranked.map(item => (
                <div key={item.id} className="flex items-center justify-between gap-2">
                    <span className="text-xs min-w-0 truncate">
                        «{item.name}» на {fmtAmount(item.currentValue)}
                    </span>
                    <button onClick={() => onArchive(item)} disabled={busyId === item.id}
                        className="text-xs underline shrink-0 disabled:opacity-50"
                        style={{ color: 'var(--color-accent)' }}>
                        {copy.capitalHintArchive}
                    </button>
                </div>
            ))}
            <button onClick={onSkip} className="text-xs underline"
                style={{ color: 'var(--color-text-muted)' }}>
                {copy.capitalHintSkip}
            </button>
        </div>
    );
}

/**
 * Похожи ли имена — только для порядка в списке, не для фильтрации. Отфильтровать значило бы
 * спрятать запись, которую пользователь назвал иначе, чем счёт, а именно она чаще всего и
 * задваивает долг. Поэтому показываем ВСЕ живые обязательства, а похожие поднимаем наверх.
 */
function namesLookAlike(a: string, b: string): boolean {
    const tokens = (s: string) => new Set(
        s.toLowerCase().replace(/[^\p{L}\p{N}]+/gu, ' ').split(' ').filter(t => t.length >= 3));
    const left = tokens(a);
    return [...tokens(b)].some(t => left.has(t));
}
