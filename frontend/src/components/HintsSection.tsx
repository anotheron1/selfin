import { useEffect, useState } from 'react';
import { fetchHintsSettings } from '../api';
import { saveHints, switchHints } from '../lib/hints';
import type { HintsSettings } from '../types/api';

/** Строка с переключателем; `on` null — настройки ещё не пришли. */
export function HintsRow({ on, onChange }: { on: boolean | null; onChange: (on: boolean) => void }) {
    return (
        <label className="flex min-h-[44px] cursor-pointer select-none items-center justify-between gap-3 has-[:disabled]:cursor-default">
            <span className="text-sm">Подсказки</span>
            <input type="checkbox" role="switch" className="peer sr-only" checked={on ?? false} disabled={on == null}
                onChange={e => onChange(e.target.checked)} />
            <span aria-hidden="true"
                className="relative h-6 w-10 shrink-0 rounded-full bg-[var(--color-border)] transition-colors after:absolute after:left-0.5 after:top-0.5 after:h-5 after:w-5 after:rounded-full after:bg-white after:transition-transform peer-checked:bg-[var(--color-accent)] peer-checked:after:translate-x-4 peer-focus-visible:ring-2 peer-focus-visible:ring-[var(--color-accent)] peer-disabled:opacity-40" />
        </label>
    );
}

/**
 * Выключатель подсказок первого дня (срез 2, п. 12; ANO-252) — сюда ведёт профиль. Хранится на сервере, как и
 * закрытые крестиком: телефон и компьютер видят одно. Включил — закрытые показываются снова.
 */
export default function HintsSection() {
    const [hints, setHints] = useState<HintsSettings | null>(null);
    useEffect(() => { fetchHintsSettings().then(setHints).catch(() => setHints(null)); }, []);
    const change = (on: boolean) => {
        if (!hints) return;
        const next = switchHints(hints, on);
        setHints(next);
        saveHints(next);
    };
    return (
        <div className="rounded-2xl px-5 py-3"
            style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}>
            <HintsRow on={hints ? !hints.off : null} onChange={change} />
        </div>
    );
}
