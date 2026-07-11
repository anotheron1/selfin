import { useCallback, useEffect, useState } from 'react';
import { HelpCircle, Wallet } from 'lucide-react';
import { fetchPocket } from '../api';
import { fmtRub as fmtC } from '../lib/format';
import { buildPocketPhrase } from '../lib/pocketPhrase';
import type { PocketResponse } from '../types/api';

const SCOPES: { key: string | undefined; label: string }[] = [
    { key: undefined, label: 'До дохода' },
    { key: 'SECOND_INCOME', label: '2-й доход' },
    { key: 'MONTHS:3', label: '3 мес' },
    { key: 'MONTHS:6', label: '6 мес' },
];

/**
 * Кармашек: одно число + «почему столько» (breakdown из GET /pocket).
 * Минимальный UI по спеке ANO-12 §8.2; полноценная подача — ANO-13/14.
 */
export default function PocketCard({ onData, refreshSignal }: {
    onData?: (p: PocketResponse) => void;
    refreshSignal?: number;
}) {
    const [scope, setScope] = useState<string | undefined>(undefined);
    const [data, setData] = useState<PocketResponse | null>(null);
    const [error, setError] = useState<string | null>(null);
    const [showWhy, setShowWhy] = useState(false);

    const load = useCallback(() => {
        fetchPocket(scope)
            .then(p => { setData(p); setError(null); onData?.(p); })
            .catch((e: Error) => setError(e.message));
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [scope]);
    useEffect(() => { load(); }, [load]);
    useEffect(() => { if (refreshSignal) load(); }, [refreshSignal, load]);

    return (
        <div className="rounded-2xl p-6"
            style={{ background: 'linear-gradient(135deg, var(--color-accent) 0%, #9f8cff 100%)' }}>
            <div className="flex items-start gap-4">
                <Wallet size={32} color="white" className="shrink-0 mt-1" />
                <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-1.5">
                        <p className="text-sm text-white/70">В кармашке</p>
                        <button onClick={() => setShowWhy(v => !v)}
                            className="text-white/50 hover:text-white/90 transition-colors"
                            aria-label="Почему столько">
                            <HelpCircle size={14} />
                        </button>
                    </div>

                    {error && <p className="text-sm text-white/80 mt-1">Ошибка: {error}</p>}
                    {!data && !error && <p className="text-sm text-white/60 mt-1 animate-pulse">Загрузка…</p>}

                    {data && (
                        <>
                            <p className="text-3xl font-bold text-white">{fmtC(data.pocket)}</p>
                            <p className="text-xs text-white/60 mt-0.5">на счёте {fmtC(data.currentBalance)}</p>
                            <p className="text-sm text-white/85 mt-2 leading-snug">{buildPocketPhrase(data)}</p>

                            <div className="flex gap-1.5 mt-3">
                                {SCOPES.map(s => (
                                    <button key={s.label}
                                        onClick={() => setScope(s.key)}
                                        className={`text-xs px-2.5 py-1 rounded-full transition-colors ${
                                            scope === s.key
                                                ? 'bg-white/90 text-black font-semibold'
                                                : 'bg-white/15 text-white/80 hover:bg-white/25'
                                        }`}>
                                        {s.label}
                                    </button>
                                ))}
                            </div>

                            {showWhy && (
                                <div className="mt-3 rounded-xl bg-black/20 px-3 py-2 space-y-1">
                                    {data.breakdown.map((line, i) => (
                                        <div key={i}>
                                            <div className="flex justify-between text-xs">
                                                <span className={line.type === 'POCKET'
                                                    ? 'text-white font-semibold' : 'text-white/70'}>
                                                    {line.label}
                                                </span>
                                                <span className={line.type === 'POCKET'
                                                    ? 'text-white font-semibold' : 'text-white/90'}>
                                                    {line.amount > 0 && line.type !== 'STARTING_BALANCE'
                                                        && line.type !== 'TRAJECTORY_MIN'
                                                        && line.type !== 'POCKET'
                                                        && line.type !== 'WISHLIST_INFO' ? '+' : ''}
                                                    {fmtC(line.amount)}
                                                </span>
                                            </div>
                                            {line.details.length > 0 && (
                                                <p className="text-[11px] text-white/50">{line.details.join(', ')}</p>
                                            )}
                                        </div>
                                    ))}
                                </div>
                            )}
                        </>
                    )}
                </div>
            </div>
        </div>
    );
}
