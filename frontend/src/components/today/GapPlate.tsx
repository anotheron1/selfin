import { useState } from 'react';
import { Calendar, ShoppingBag } from 'lucide-react';
import { Button, Plate } from '@/ds';
import { fmtDayShort, fmtRub } from '../../lib/format';
import type { GapMode } from '../../lib/gapMode';
import { moveLater } from '../../lib/moveLater';
import type { WatchdogView } from '../../lib/tightPoint';

/**
 * Сторож до 2-го дохода — песочная плашка (Zh2-G7a): разрыв по плану или впритык за краем графика.
 * Не красная и без «!»: цвет не приговор (правило 12); значок — цветом ожидания.
 */
export function WatchdogPlate({ view, onShowSecond }: {
    view: Extract<WatchdogView, { kind: 'plate' }>;
    onShowSecond: () => void;
}) {
    const Icon = view.tone === 'plan' ? Calendar : ShoppingBag;
    return (
        <Plate className="flex gap-3" data-plate="watchdog">
            <span className="mt-px flex shrink-0 text-sf-kind-ozh"><Icon size={22} strokeWidth={1.7} aria-hidden="true" /></span>
            <div className="flex min-w-0 flex-1 flex-col gap-1">
                <span className="text-sf-body font-semibold">{view.title}</span>
                <span className="text-sf-note">{view.body}</span>
                {view.small && <span className="text-sf-label text-sf-muted">{view.small}</span>}
                {view.showSecond && (
                    <Button variant="soft" size="s" onClick={onShowSecond} className="mt-1 self-start">Показать до 2-го дохода</Button>
                )}
            </div>
        </Plate>
    );
}

/**
 * «Можно сдвинуть на после …» (Zh2-G7b): ожидания и хотелки до узкого дня, крупные первыми, у каждой — «Сдвинуть»
 * на следующий день после конца срока (`moveLater`). Внизу — обновить остаток. Экран перечитывается сам: удачная
 * запись шлёт `selfin:changed`.
 */
export function MovePlate({ gap, horizonEnd, onReanchor }: { gap: GapMode; horizonEnd: string; onReanchor: () => void }) {
    const [busy, setBusy] = useState<string | null>(null);
    const [failed, setFailed] = useState<string | null>(null);
    const move = (id: string, run: () => Promise<void>) => {
        if (busy) return;
        setBusy(id);
        setFailed(null);
        run().catch(err => { console.error(err); setFailed(id); }).finally(() => setBusy(null));
    };
    return (
        <Plate className="flex flex-col pb-1.5 pt-3" data-plate="move">
            <span className="text-sf-body font-semibold">Можно сдвинуть на после {fmtDayShort(horizonEnd)}</span>
            <span className="pb-2 text-sf-label text-sf-muted">Брони не двигаются — их дата известна заранее.</span>
            {gap.movable.map(r => {
                const id = r.item.id!;
                return (
                    <div key={id} className="flex items-center gap-3 border-t border-sf-rule py-2.5">
                        <div className="flex min-w-0 flex-1 flex-col">
                            <span className="text-sf-control">
                                {r.label} <span className="text-sf-muted">· {fmtDayShort(r.item.date)} · {fmtRub(r.item.amount)}</span>
                            </span>
                            <span className="text-sf-label text-sf-muted">→ {r.outcome}</span>
                            {failed === id && <span className="text-sf-label text-sf-muted">Не сдвинулось — попробуй ещё раз</span>}
                        </div>
                        <Button variant="soft" size="s" disabled={busy != null} onClick={() => move(id, () => moveLater(r.item, horizonEnd))}
                            aria-label={`Сдвинуть: ${r.label} на после ${fmtDayShort(horizonEnd)}`}>
                            Сдвинуть
                        </Button>
                    </div>
                );
            })}
            <div className="flex items-center gap-3 border-t border-sf-rule pb-2 pt-2.5">
                <span className="min-w-0 flex-1 text-sf-label text-sf-muted">Или обнови остаток — может, на картах больше, чем по записям.</span>
                <Button variant="outline" size="s" onClick={onReanchor}>Обновить</Button>
            </div>
        </Plate>
    );
}
