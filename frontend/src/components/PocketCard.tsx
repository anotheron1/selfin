import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { HelpCircle, Pencil, Wallet, FlaskConical } from 'lucide-react';
import { fetchPocket } from '../api';
import { fmtRub as fmtC } from '../lib/format';
import { buildPocketPhrase } from '../lib/pocketPhrase';
import { noExpectationsNote } from '../lib/noExpectationsNote';
import { buildGapMode } from '../lib/gapMode';
import { buildBreakdownView, type BreakdownRow } from '../lib/breakdownRows';
import { buildAgeHint } from '../lib/reanchor';
import ReanchorSheet from './pocket/ReanchorSheet';
import NzSheet from './pocket/NzSheet';
import HelpDialog from './HelpDialog';
import type { PocketResponse } from '../types/api';

/**
 * Режим нехватки (ANO-100) — янтарный, а не красный: карточка сообщает о положении дел, а не
 * выносит приговор (правило 12; цвет различает, а не оценивает — ANO-172).
 */
const GAP_BACKGROUND = 'linear-gradient(135deg, #92400e 0%, #b45309 100%)';

const SCOPES: { key: string | undefined; label: string }[] = [
    { key: undefined, label: 'До дохода' },
    { key: 'SECOND_INCOME', label: '2-й доход' },
    { key: 'MONTHS:3', label: '3 мес' },
    { key: 'MONTHS:6', label: '6 мес' },
];

/**
 * Строка расшифровки (ANO-77): подпись и сумма в одну строку, пояснение серым под ними.
 * Роль видна без чтения: промежуточный итог — пунктир над строкой, итог — сплошная черта
 * и жирный, оговорки бледнее расчёта.
 */
function BreakdownRowView({ row }: { row: BreakdownRow }) {
    const frame = row.kind === 'result' ? 'border-t border-white/35 pt-1.5'
        : row.kind === 'subtotal' ? 'border-t border-dashed border-white/20 pt-1.5'
        : '';
    const text = row.kind === 'result' ? 'text-sm font-bold text-white'
        : row.kind === 'subtotal' ? 'text-white'
        : row.kind === 'caveat' ? 'text-white/75'
        : 'text-white/85';
    const amount = row.kind === 'caveat' ? 'text-white/85' : row.kind === 'item' ? 'text-white/90' : '';
    return (
        <div className={frame}>
            <div className={`flex justify-between gap-2 ${text}`}>
                <span>{row.label}</span>
                <span className={`whitespace-nowrap ${amount}`}>{row.amount}</span>
            </div>
            {row.note && (
                <p className={`text-[11px] mt-px ${row.kind === 'caveat' ? 'text-white/50' : 'text-white/55'}`}>
                    {row.note}
                </p>
            )}
        </div>
    );
}

/**
 * Кармашек: одно число + «почему столько» (breakdown из GET /pocket).
 * Минимальный UI по спеке ANO-12 §8.2; полноценная подача — ANO-13/14.
 */
export default function PocketCard({ onData, refreshSignal, onReanchor }: {
    /** Вторым аргументом — выбранный горизонт: «Пополнить фонд» переспрашивает по нему (ANO-88). */
    onData?: (p: PocketResponse, scope: string | undefined) => void;
    refreshSignal?: number;
    /** Зовётся после успешного ре-якоря — страница может обновить свои данные (сторож и т.п.). */
    onReanchor?: () => void;
}) {
    const navigate = useNavigate();
    const [scope, setScope] = useState<string | undefined>(undefined);
    const [data, setData] = useState<PocketResponse | null>(null);
    const [error, setError] = useState<string | null>(null);
    const [showWhy, setShowWhy] = useState(false);
    const [showReanchor, setShowReanchor] = useState(false);
    const [showNz, setShowNz] = useState(false);
    const [showHelp, setShowHelp] = useState(false);

    const load = useCallback(() => {
        fetchPocket(scope)
            .then(p => { setData(p); setError(null); onData?.(p, scope); })
            .catch((e: Error) => setError(e.message));
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [scope]);
    useEffect(() => { load(); }, [load]);
    useEffect(() => { if (refreshSignal) load(); }, [refreshSignal, load]);

    // ANO-100: когда деньги кончаются в пределах срока, у карточки свой режим — про даты.
    // ANO-92: тот же режим, когда денег хватает, но план задевает НЗ.
    const gap = data ? buildGapMode(data) : null;

    return (
        <div className="rounded-2xl p-6"
            style={{ background: gap ? GAP_BACKGROUND : 'linear-gradient(135deg, var(--color-accent) 0%, #9f8cff 100%)' }}>
            <div className="flex items-start gap-4">
                <Wallet size={32} color="white" className="shrink-0 mt-1" />
                <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-1.5">
                        <p className="text-sm text-white/70">{gap ? gap.horizonLabel : 'В кармашке'}</p>
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
                            {gap ? (
                                <>
                                    {/*
                                      ANO-76: отрицательное число под «В кармашке» посторонний
                                      читал как долг в прошлом. Здесь фраза с датой впереди и без
                                      минуса — так её не прочесть как «потратила больше положенного».
                                    */}
                                    <p className="text-2xl font-bold text-white leading-tight mt-0.5">{gap.headline}</p>
                                    {gap.subline && (
                                        <p className="text-sm text-white/85 mt-1 leading-snug">{gap.subline}</p>
                                    )}
                                </>
                            ) : (
                                <p className="text-3xl font-bold text-white">{fmtC(data.pocket)}</p>
                            )}
                            {/*
                              ANO-80: прогноз — предположение, и он не входит в число, которым
                              человек распоряжается. Стоит ВЫШЕ оговорок про карты и вклад: те
                              отвечают на вопрос «если я сделаю то-то», а эта — на тот же вопрос,
                              что главное число, но с другой уверенностью. Слова «прогноз» здесь
                              нет: оно требует объяснения, которого в этом месте не будет, а
                              «обычные траты» — словарь человека (правило 13).
                            */}
                            {data.pocketWithForecast != null && (
                                <p className="text-sm text-white/85 mt-0.5">
                                    {fmtC(data.pocketWithForecast)}
                                    <span className="text-white/60"> — с обычными тратами</span>
                                </p>
                            )}
                            {/*
                              ANO-185: там же, где строка прогноза, и отвечает на тот же вопрос —
                              насколько честно это число. Друг друга они исключают: пока прогноз
                              не научился, об ожиданиях говорит эта строка, потом — он сам.
                            */}
                            {noExpectationsNote(data) && (
                                <p className="text-xs text-white/60 mt-0.5">{noExpectationsNote(data)}</p>
                            )}
                            {/*
                              ANO-100: вместо «займи» — что можно сдвинуть. Только сообщает:
                              перенос одной кнопкой — следующим шагом, если окажется нужен.
                            */}
                            {gap && gap.movable.length > 0 && (
                                <div className="mt-2">
                                    <p className="text-xs text-white/70">{gap.moveHeader}</p>
                                    {gap.movable.map(line => (
                                        <p key={line} className="text-xs text-white/90">{line}</p>
                                    ))}
                                </div>
                            )}
                            {/*
                              Второе и третье числа (ANO-9 §4.2–§4.3). Иерархия размеров —
                              часть смысла: это не три равноправных ответа, а один ответ и две
                              оговорки. Карты возвращают к планке, потому что так меряется
                              обязательство; вклад распечатывают в трудный момент, а не
                              планируют им жить. Оба null, когда оговаривать нечего.
                            */}
                            {data.pocketAfterCreditRestore != null && (
                                <p className="text-sm text-white/85 mt-0.5">
                                    {fmtC(data.pocketAfterCreditRestore)}
                                    <span className="text-white/60"> — если погасить карты до планки</span>
                                </p>
                            )}
                            {data.pocketWithDeposits != null && (
                                <p className="text-[11px] text-white/55 mt-0.5">
                                    {fmtC(data.pocketWithDeposits)} — если распечатать вклад
                                </p>
                            )}
                            {/* Ре-якорь (ANO-15): тап по остатку → шторка с одним полем */}
                            <button onClick={() => setShowReanchor(true)}
                                className="text-xs text-white/60 mt-0.5 flex items-center gap-1 hover:text-white/90 transition-colors"
                                aria-label="Обновить остаток">
                                на счёте {fmtC(data.currentBalance)}
                                <Pencil size={11} />
                            </button>
                            {(() => {
                                const t = new Date();
                                const todayIso = `${t.getFullYear()}-${String(t.getMonth() + 1).padStart(2, '0')}-${String(t.getDate()).padStart(2, '0')}`;
                                const hint = buildAgeHint(data.checkpointDate, todayIso);
                                return hint && (
                                    <button onClick={() => setShowReanchor(true)}
                                        className="text-[11px] text-white/50 hover:text-white/80 transition-colors text-left">
                                        {hint}
                                    </button>
                                );
                            })()}
                            {/* В режиме нехватки то же говорят заголовок и строка под ним, с датой впереди. */}
                            {!gap && (
                                <p className="text-sm text-white/85 mt-2 leading-snug">{buildPocketPhrase(data)}</p>
                            )}

                            <div className="flex gap-1.5 mt-3 items-center flex-wrap">
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
                                <button onClick={() => navigate('/wishlist')}
                                    className="text-xs px-2.5 py-1 rounded-full bg-white/15 text-white/80 hover:bg-white/25 transition-colors flex items-center gap-1 ml-auto"
                                    aria-label="Примерить хотелку">
                                    <FlaskConical size={12} /> Примерить
                                </button>
                            </div>

                            {showWhy && (
                                <div className="mt-3 rounded-xl bg-black/20 px-3 py-2.5 space-y-2 text-xs">
                                    {/*
                                      ANO-77, вариант В владельца: слева подпись, справа сумма, под
                                      строкой — пояснение. Итог назван по состоянию карточки и отделён
                                      чертой; оговорки — своим блоком, в число они не входят.
                                    */}
                                    {(() => {
                                        const view = buildBreakdownView(data);
                                        return (
                                            <>
                                                <p className="text-[10px] uppercase tracking-wider text-white/50">Из чего число</p>
                                                {view.calc.map((row, i) => <BreakdownRowView key={`c${i}`} row={row} />)}
                                                <BreakdownRowView row={view.result} />
                                                {view.caveats.length > 0 && (
                                                    <p className="text-[10px] uppercase tracking-wider text-white/50 pt-1">
                                                        Кроме этого — в число не входит
                                                    </p>
                                                )}
                                                {view.caveats.map((row, i) => <BreakdownRowView key={`k${i}`} row={row} />)}
                                            </>
                                        );
                                    })()}
                                    {/*
                                      ANO-92: НЗ задаётся там же, где видно, из чего число, — как остаток
                                      со строки «на счёте». Первый экран не меняется: расшифровка
                                      скрыта, пока не нажат «?».
                                    */}
                                    <button onClick={() => setShowNz(true)}
                                        className="w-full flex items-center gap-1 pt-1.5 mt-1 border-t border-white/10 text-xs text-white/60 hover:text-white/90 transition-colors"
                                        aria-label="Задать НЗ">
                                        {data.buffer > 0 ? `НЗ ${fmtC(data.buffer)}` : 'НЗ не задан'}
                                        <Pencil size={11} />
                                    </button>
                                    {/*
                                      ANO-186: слова — рядом с числами. «?» у числа уже раскрывает эту
                                      расшифровку, поэтому справка открывается отсюда, а не вторым «?».
                                    */}
                                    <button onClick={() => setShowHelp(true)}
                                        className="w-full flex items-center gap-1 text-xs text-white/60 hover:text-white/90 transition-colors">
                                        <HelpCircle size={11} />
                                        Как это считается
                                    </button>
                                </div>
                            )}
                        </>
                    )}
                </div>
            </div>

            {data && (
                <ReanchorSheet
                    open={showReanchor}
                    onOpenChange={setShowReanchor}
                    currentBalance={data.currentBalance}
                    checkpointDate={data.checkpointDate}
                    onSuccess={() => { load(); onReanchor?.(); }}
                />
            )}
            {data && (
                <NzSheet
                    open={showNz}
                    onOpenChange={setShowNz}
                    buffer={data.buffer}
                    onSuccess={load}
                />
            )}
            <HelpDialog topic="pocket" open={showHelp} onOpenChange={setShowHelp} />
        </div>
    );
}
