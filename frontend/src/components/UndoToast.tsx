import { useEffect, useRef, useState } from 'react';
import { RefreshCw, Undo2 } from 'lucide-react';

/** Сколько строка стоит, если её не трогать. */
export const UNDO_TOAST_MS = 8000;

/**
 * Строка снизу после записи (S3-F3): что записано и что со «Свободно», «Отменить» удаляет только что записанное.
 * Сама уходит через несколько секунд. На телефоне — над капсулой, на 1440 — внизу экрана.
 *
 * После записи в кредит (S4-K2) — ещё «Обновить»: лист «Обновить остатки», чтобы долг стал виден на кредитке. Тогда
 * «Отменить» — значком, как на доске: двум словам в строке на 390 места нет.
 */
export default function UndoToast({ text, onUndo, onDone, onRefresh }: {
    text: string;
    onUndo: () => Promise<unknown>;
    onDone: () => void;
    /** «Обновить кредитку» (S4-K2). */
    onRefresh?: () => void;
}) {
    const [busy, setBusy] = useState(false);
    const [failed, setFailed] = useState(false);
    // Родитель перерисовывается от перечитанных данных — срок строки от этого не начинается заново.
    const done = useRef(onDone);
    done.current = onDone;
    // Неудача срок не отменяет, а начинает заново: строка не повисает навсегда и не держит «+» закрытым (ревью Codex #235).
    useEffect(() => {
        if (busy) return;
        const t = setTimeout(() => done.current(), UNDO_TOAST_MS);
        return () => clearTimeout(t);
    }, [busy, failed]);
    const undo = () => {
        setBusy(true);
        onUndo().then(onDone, () => { setBusy(false); setFailed(true); });
    };
    const pill = 'flex h-9 shrink-0 items-center gap-1.5 rounded-[18px] bg-white/15 px-3.5 text-sf-note font-semibold disabled:opacity-50';
    return (
        <div role="status" className="fixed inset-x-4 bottom-[98px] z-50 mx-auto flex min-h-[52px] max-w-[480px] items-center gap-2.5 rounded-[18px] bg-sf-ink py-2 pl-4 pr-2 font-sf text-sf-page shadow-sf-raised lg:bottom-6">
            <span className="min-w-0 flex-1 text-sf-note">{failed ? 'Не отменилось — попробуйте ещё раз' : text}</span>
            {onRefresh && (
                <button type="button" disabled={busy} onClick={onRefresh} aria-label="Обновить кредитку" className={pill}>
                    <RefreshCw size={15} strokeWidth={2} />Обновить
                </button>
            )}
            <button type="button" disabled={busy} onClick={undo} aria-label={onRefresh ? 'Отменить' : undefined}
                className={onRefresh ? `${pill} px-2.5` : pill}>
                <Undo2 size={15} strokeWidth={2} />{!onRefresh && 'Отменить'}
            </button>
        </div>
    );
}
