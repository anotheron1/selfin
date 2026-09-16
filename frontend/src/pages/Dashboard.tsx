import { useCallback, useEffect, useState } from 'react';
import { fetchEvents, fetchPocket } from '../api';
import type { FinancialEvent, PocketResponse } from '../types/api';
import { AlertTriangle, Info } from 'lucide-react';
import { Badge } from '../components/ui/badge';
import PocketCard from '../components/PocketCard';
import PocketTrajectoryChart from '../components/pocket/PocketTrajectoryChart';
import UpcomingList from '../components/dashboard/UpcomingList';
import { buildWatchdogAlert } from '../lib/watchdogAlert';
import { fmtRub } from '../lib/format';

const fmt = fmtRub;

const fmtAmt = (n: number | null) => n != null ? fmt(n) : '—';

/** «14 июля» из ISO-строки БЕЗ UTC-парсинга (new Date('YYYY-MM-DD') сдвигает день в западных TZ). */
const fmtLocalDate = (iso: string) => {
    const [y, m, d] = iso.split('-').map(Number);
    return new Date(y, m - 1, d).toLocaleDateString('ru-RU', { day: 'numeric', month: 'long' });
};


export default function Dashboard({ refreshSignal }: { refreshSignal?: number }) {
    const _today = new Date();
    const todayStr = `${_today.getFullYear()}-${String(_today.getMonth() + 1).padStart(2, '0')}-${String(_today.getDate()).padStart(2, '0')}`;

    const [pocket, setPocket] = useState<PocketResponse | null>(null);
    // Сторожевой скоуп (ANO-14 §5): алерт разрыва всегда смотрит до 2-го дохода,
    // независимо от скоупа PocketCard. Ошибка сторожа не роняет страницу и НЕ стирает
    // ранее показанный алерт (транзиентный сбой не должен молча снять предупреждение).
    const [watchdog, setWatchdog] = useState<PocketResponse | null>(null);
    const [watchdogFailed, setWatchdogFailed] = useState(false);
    const [todayEvents, setTodayEvents] = useState<FinancialEvent[]>([]);
    const [error, setError] = useState<string | null>(null);
    /** ANO-119: просьба к PocketCard перечитать кармашек — после записи факта из списка. */
    const [pocketReload, setPocketReload] = useState(0);

    const loadAll = useCallback(() => {
        // ANO-119: события месяца больше не нужны — блок «план/факт» уехал на «Аналитику»,
        // а список «осталось потратить» приходит вместе с кармашком, одним ответом.
        fetchEvents(todayStr, todayStr)
            .then(setTodayEvents)
            .catch(e => setError(e.message));

        fetchPocket('SECOND_INCOME')
            .then(wd => { setWatchdog(wd); setWatchdogFailed(false); })
            .catch(() => setWatchdogFailed(true)); // прежний watchdog-стейт сохраняем
    }, [todayStr]);

    useEffect(() => { loadAll(); }, [loadAll]);

    // Фоновое обновление при добавлении через FAB
    useEffect(() => {
        if (refreshSignal) loadAll();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [refreshSignal]);

    if (error) return (
        <div className="p-6 text-center" style={{ color: 'var(--color-danger)' }}>Ошибка: {error}</div>
    );

    // Прогноз конца дня: остаток кармашка + плановые суммы ещё не исполненных событий сегодня
    const unexecutedToday = todayEvents.filter(e => e.status === 'PLANNED');
    const endOfDayForecast = unexecutedToday.reduce((bal, e) => {
        const amt = e.plannedAmount ?? 0;
        return e.type === 'INCOME' ? bal + amt : bal - amt;
    }, pocket?.currentBalance ?? 0);
    const hasUnexecutedToday = unexecutedToday.length > 0;

    const incomeToday = todayEvents.filter(e => e.type === 'INCOME');
    const expenseToday = todayEvents.filter(e => e.type === 'EXPENSE' || e.type === 'FUND_TRANSFER');

    const watchdogAlert = buildWatchdogAlert(watchdog, pocket?.horizon.endDate ?? null);

    return (
        <div className="overflow-y-auto overflow-x-hidden scrollbar-none" style={{ height: 'calc(100dvh - var(--nav-height))' }}>
        <div className="pl-4 pr-5 py-6 space-y-5">
            {/* Hero: кармашек — единый ответ из GET /pocket (ANO-12/13).
                Старые «Текущий баланс» + зарплатные горизонты DashboardService удалены:
                расчёт расходился с кармашком (см. флаг в ANO-12). */}
            <PocketCard onData={setPocket} refreshSignal={(refreshSignal ?? 0) + pocketReload}
                        onReanchor={loadAll} />

            {/* События сегодня */}
            {todayEvents.length > 0 && (
                <div className="rounded-2xl p-5 space-y-3"
                    style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}>
                    <div className="space-y-2">
                        <p className="text-xs font-semibold uppercase" style={{ color: 'var(--color-text-muted)' }}>
                            Сегодня
                        </p>

                        {/* Доходы */}
                        {incomeToday.map(e => (
                            <div key={e.id} className="flex items-center justify-between gap-2 text-sm">
                                <div className="flex items-center gap-2 min-w-0">
                                    <span className="truncate">{e.description || e.categoryName || 'Без названия'}</span>
                                    {e.mandatory && (
                                        <Badge variant="outline" className="text-xs border-destructive/60 text-destructive px-1.5 py-0 shrink-0">обяз</Badge>
                                    )}
                                    {e.status === 'EXECUTED' && (
                                        <span className="text-xs shrink-0" style={{ color: 'var(--color-success)' }}>✓</span>
                                    )}
                                </div>
                                <span className="font-medium shrink-0" style={{ color: 'var(--color-success)' }}>
                                    +{fmtAmt(e.factAmount ?? e.plannedAmount)}
                                </span>
                            </div>
                        ))}

                        {/* Расходы и переводы в копилку */}
                        {expenseToday.map(e => (
                            <div key={e.id} className="flex items-center justify-between gap-2 text-sm">
                                <div className="flex items-center gap-2 min-w-0">
                                    <span className="truncate">
                                        {e.type === 'FUND_TRANSFER'
                                            ? `↪ ${e.targetFundName ?? 'Копилка'}`
                                            : e.description || e.categoryName || 'Без названия'}
                                    </span>
                                    {e.mandatory && (
                                        <Badge variant="outline" className="text-xs border-destructive/60 text-destructive px-1.5 py-0 shrink-0">обяз</Badge>
                                    )}
                                    {e.status === 'EXECUTED' && (
                                        <span className="text-xs shrink-0" style={{ color: 'var(--color-success)' }}>✓</span>
                                    )}
                                </div>
                                <span className="font-medium shrink-0" style={{ color: 'var(--color-text-muted)' }}>
                                    -{fmtAmt(e.factAmount ?? e.plannedAmount)}
                                </span>
                            </div>
                        ))}

                        {/* Прогноз конца дня — только если есть неисполненные */}
                        {hasUnexecutedToday && pocket && (
                            <div className="flex justify-between items-center pt-2 border-t text-sm font-semibold"
                                style={{ borderColor: 'var(--color-border)' }}>
                                <span style={{ color: 'var(--color-text-muted)' }}>Прогноз конца дня</span>
                                <span style={{ color: endOfDayForecast >= 0 ? 'var(--color-success)' : 'var(--color-danger)' }}>
                                    {fmt(endOfDayForecast)}
                                </span>
                            </div>
                        )}
                    </div>
                </div>
            )}

            {/* Алерт кассового разрыва — из minPoint сторожевого скоупа SECOND_INCOME (ANO-14 §5) */}
            {watchdogAlert && watchdogAlert.kind === 'PLAN' && (
                <div className="rounded-xl p-4 flex gap-3 items-start"
                    style={{ background: 'rgba(239,68,68,0.12)', border: '1px solid var(--color-danger)' }}>
                    <AlertTriangle size={18} style={{ color: 'var(--color-danger)', flexShrink: 0, marginTop: 2 }} />
                    <div>
                        <p className="font-semibold text-sm" style={{ color: 'var(--color-danger)' }}>Кассовый разрыв!</p>
                        <p className="text-sm" style={{ color: 'var(--color-text)' }}>
                            {fmtLocalDate(watchdogAlert.date)} — ожидается дефицит{' '}
                            <b>{fmt(watchdogAlert.deficit)}</b>
                            {watchdogAlert.drivenBy && <> («{watchdogAlert.drivenBy}»)</>}
                        </p>
                        {watchdogAlert.forecastNote && (
                            // Оговорка показывается по признаку «ГЛУБЖЕ», а не «раньше»: на
                            // эталонном стенде прогнозный минимум оказался позже планового
                            // (14.10 против 12.10), и слово «раньше» было бы неправдой.
                            <p className="text-xs mt-1" style={{ color: 'var(--color-text-muted)' }}>
                                С обычными тратами — глубже: {fmt(watchdogAlert.forecastNote.deficit)}
                                {' '}к {fmtLocalDate(watchdogAlert.forecastNote.date)}
                            </p>
                        )}
                        {watchdogAlert.beyondChart && (
                            <p className="text-xs mt-1" style={{ color: 'var(--color-text-muted)' }}>
                                Разрыв за пределами графика — переключись на «2-й доход»
                            </p>
                        )}
                    </div>
                </div>
            )}

            {/*
              ANO-80: предупреждение по прогнозу — другого рода, чем по плану, и оттенком
              формулировки это не передать. Слово «дефицит» занято твёрдым случаем и здесь
              не используется. «Около» и «может» стоят не для мягкости, а потому что это
              правда о том, чем продукт располагает (правило 3). Речь о деньгах, не о
              человеке (правило 12).
            */}
            {watchdogAlert && watchdogAlert.kind === 'FORECAST' && (
                <div className="rounded-xl p-4 flex gap-3 items-start"
                    style={{ background: 'rgba(239,159,39,0.10)', border: '1px solid #EF9F27' }}>
                    <Info size={18} style={{ color: '#EF9F27', flexShrink: 0, marginTop: 2 }} />
                    <div>
                        <p className="text-sm" style={{ color: 'var(--color-text)' }}>
                            До {fmtLocalDate(watchdogAlert.date)} по планам хватает. С обычными
                            тратами — впритык: около <b>{fmt(watchdogAlert.deficit)}</b> может не хватить.
                        </p>
                        {watchdogAlert.beyondChart && (
                            <p className="text-xs mt-1" style={{ color: 'var(--color-text-muted)' }}>
                                Это за пределами графика — переключись на «2-й доход»
                            </p>
                        )}
                    </div>
                </div>
            )}


            {/* ANO-119: «осталось потратить» — платёжный календарь по категориям на
                горизонте кармашка. Пришёл тем же ответом, что и само число, поэтому
                объясняет именно его, а не живёт своей жизнью. */}
            {pocket && (
                <UpcomingList
                    items={pocket.upcoming}
                    horizonEnd={pocket.horizon.endDate}
                    onRecorded={() => { setPocketReload(n => n + 1); loadAll(); }}
                />
            )}

            {/* Сторож недоступен и данных нет — честная пометка вместо тишины */}
            {watchdogFailed && !watchdog && (
                <p className="text-xs text-center" style={{ color: 'var(--color-text-muted)' }}>
                    Не удалось проверить кассовый разрыв — обнови страницу
                </p>
            )}

            {/* Совсем пустое состояние: якоря нет, значит человек ещё ничего не вводил.
                У того, кто уже пользуется, пустой горизонт объясняет сам блок выше. */}
            {pocket && !pocket.checkpointDate && pocket.upcoming.length === 0
                && todayEvents.length === 0 && (
                <div className="text-center py-8 text-sm" style={{ color: 'var(--color-text-muted)' }}>
                    Добавь первую трату кнопкой «+»
                </div>
            )}

            {/* Кассовый календарь — близнец кармашка: та же trajectory из GET /pocket,
                нарисованная графиком; горизонт = скоуп PocketCard (ANO-6, ANO-14) */}
            {pocket && (
                <PocketTrajectoryChart data={pocket} />
            )}
        </div>
        </div>
    );
}
