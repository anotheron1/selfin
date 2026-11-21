import { forwardRef, useEffect, useState, type ReactNode } from 'react';
import { ChevronDown, Search, X } from 'lucide-react';
import { chipVariants, IconButton, KindMark } from '@/ds';
import { cn } from '@/lib/utils';
import { searchEvents } from '../../api';
import { CHANGED } from '../../api/client';
import type { Category, Priority, SearchResult } from '../../types/api';
import type { FinancialEvent } from '../../types/api';
import {
    foundByMonth, foundHeader, PERIODS, searchKey, searchReady, type Period, type SearchCriteria,
} from '../../lib/journalSearch';
import { PRIORITY_DOT_CONFIG, PRIORITY_ORDER } from '../../lib/priority';
import { iconOf } from './JournalList';

/**
 * Поиск по журналу (срез 3, п. 9, ANO-126; доски S3-S1, S3-W6): поле, чипы, найденное по месяцам. Правила —
 * lib/journalSearch; что подходит — сервер. Нажатие на найденное — карточка (EventCard) у страницы.
 */

const KIND = { HIGH: 'bron', MEDIUM: 'ozh', LOW: 'hot' } as const;
const PAUSE_MS = 250;

/**
 * Найденное по критериям: пока ищется новое, видно прежнее. Запись в этой вкладке или в другой (`selfin:changed`)
 * ищет заново — карточка из найденного видит свежую строку.
 */
export function useJournalSearch(c: SearchCriteria, active: boolean, today: string) {
    const key = searchKey(c, active, today);
    // tick — номер чтения: запись в любой вкладке его сдвигает, и найденное несвежее, пока не перечитано после неё.
    const [state, setState] = useState<{ key: string; tick: number; result: SearchResult | null; failed: boolean }>(
        { key: '', tick: -1, result: null, failed: false });
    const [tick, setTick] = useState(0);
    useEffect(() => {
        const again = () => setTick(t => t + 1);
        window.addEventListener(CHANGED, again);
        return () => window.removeEventListener(CHANGED, again);
    }, []);
    useEffect(() => {
        if (!key) return;
        let live = true;
        const t = setTimeout(() => {
            searchEvents(new URLSearchParams(key)).then(
                result => { if (live) setState({ key, tick, result, failed: false }); },
                () => { if (live) setState(s => ({ key, tick, result: s.result, failed: true })); },
            );
        }, PAUSE_MS);
        return () => { live = false; clearTimeout(t); };
    }, [key, tick]);
    const read = key !== '' && state.key === key && state.tick === tick;
    return {
        result: key ? state.result : null,
        /** Найденное прочитано для этих критериев и после последней записи. */
        fresh: read && !state.failed,
        failed: read && state.failed,
        retry: () => setTick(t => t + 1),
    };
}

/** Поле поиска: на телефоне — после лупы, на 1440 — в строке месяца всегда. Крестик — обратно к месяцу. */
export const SearchField = forwardRef<HTMLInputElement, {
    value: string;
    open: boolean;
    onChange: (q: string) => void;
    onOpen: () => void;
    onClose: () => void;
    className?: string;
}>(({ value, open, onChange, onOpen, onClose, className }, ref) => (
    <label className={cn('flex h-[46px] min-w-0 cursor-text items-center gap-2.5 rounded-sf-pill bg-sf-plate pl-3.5 pr-1 text-sf-body',
        open ? 'shadow-[inset_0_0_0_1.5px_var(--sf-accent)]' : 'shadow-[inset_0_0_0_1.5px_var(--sf-rule)]', className)}>
        <Search size={18} strokeWidth={1.8} className="shrink-0" />
        <input ref={ref} value={value} aria-label="Поиск по журналу" placeholder="Название, категория, сумма"
            enterKeyHint="search" autoComplete="off"
            onChange={e => { onChange(e.target.value); onOpen(); }} onFocus={onOpen}
            onKeyDown={e => { if (e.key === 'Escape') onClose(); }}
            className="h-full w-0 min-w-0 flex-1 bg-transparent outline-none placeholder:text-sf-muted" />
        {open && (
            <IconButton aria-label="Закрыть поиск" onClick={e => { e.preventDefault(); onClose(); }} className="size-9 text-sf-muted">
                <X size={16} strokeWidth={2} />
            </IconButton>
        )}
    </label>
));
SearchField.displayName = 'SearchField';

const chip = cn(chipVariants({ size: 's' }), 'h-sf-chip');
const chipOn = 'bg-sf-accent font-medium text-sf-on-accent hover:bg-sf-accent-hover active:bg-sf-accent-press';

/** Чип с выбором: свой список телефона и компьютера (select), подпись — выбранное. */
function SelectChip({ label, name, on, value, onChange, children }: {
    label: string; name: string; on: boolean; value: string; onChange: (v: string) => void; children: ReactNode;
}) {
    return (
        <label className={cn(chip, 'relative gap-1 pr-2.5 has-[:focus-visible]:shadow-[0_0_0_2px_var(--sf-accent)]', on && chipOn)}>
            <span className="max-w-[180px] truncate">{label}</span><ChevronDown size={13} strokeWidth={2} />
            <select aria-label={name} value={value} onChange={e => onChange(e.target.value)}
                className="absolute inset-0 cursor-pointer appearance-none opacity-0">{children}</select>
        </label>
    );
}

/** «За всё время ▾», «Категория ▾», «Вид ▾», «Только записи». */
export function SearchChips({ criteria: c, categories, onChange }: {
    criteria: SearchCriteria;
    /** Порядок сервера (п. 14): частые первыми. */
    categories: Category[];
    onChange: (next: SearchCriteria) => void;
}) {
    const category = categories.find(x => x.id === c.categoryId);
    const group = (type: Category['type'], label: string) => (
        <optgroup label={label}>
            {categories.filter(x => x.type === type).map(x => <option key={x.id} value={x.id}>{x.name}</option>)}
        </optgroup>
    );
    return (
        <div className="-mx-4 flex gap-1.5 overflow-x-auto px-4 [scrollbar-width:none] lg:mx-0 lg:px-0">
            <SelectChip name="Период" label={PERIODS[c.period].label} on={c.period !== 'all'} value={c.period}
                onChange={v => onChange({ ...c, period: v as Period })}>
                {(Object.keys(PERIODS) as Period[]).map(p => <option key={p} value={p}>{PERIODS[p].label}</option>)}
            </SelectChip>
            <SelectChip name="Категория" label={category?.name ?? 'Категория'} on={category != null} value={c.categoryId ?? ''}
                onChange={v => onChange({ ...c, categoryId: v || null })}>
                <option value="">Все категории</option>
                {group('EXPENSE', 'Расходы')}
                {group('INCOME', 'Доходы')}
            </SelectChip>
            <SelectChip name="Вид" label={c.priority ? PRIORITY_DOT_CONFIG[c.priority].name : 'Вид'} on={c.priority != null}
                value={c.priority ?? ''} onChange={v => onChange({ ...c, priority: (v || null) as Priority | null })}>
                <option value="">Любой вид</option>
                {PRIORITY_ORDER.map(p => <option key={p} value={p}>{PRIORITY_DOT_CONFIG[p].name}</option>)}
            </SelectChip>
            <button type="button" aria-pressed={c.factsOnly} onClick={() => onChange({ ...c, factsOnly: !c.factsOnly })}
                className={cn(chip, c.factsOnly && chipOn)}>
                Только записи
            </button>
        </div>
    );
}

/** «Нашлось N записей · сумма», найденное по месяцам; нажатие — карточка. */
export function SearchResults({ criteria, result, fresh, failed, onRetry, onRow }: {
    criteria: SearchCriteria;
    result: SearchResult | null;
    fresh: boolean;
    failed: boolean;
    onRetry: () => void;
    onRow: (e: FinancialEvent) => void;
}) {
    if (!searchReady(criteria)) {
        return <p className="px-1 py-4 text-sf-note text-sf-muted">Название, категория или сумма — по всем месяцам.</p>;
    }
    if (failed) {
        return (
            <p className="py-6 text-center text-sf-note text-sf-muted">
                Поиск не удался —{' '}
                <button type="button" className="underline" onClick={onRetry}>повторить</button>
            </p>
        );
    }
    if (!result) return <p className="animate-pulse py-6 text-center text-sf-note text-sf-muted">Загрузка…</p>;
    const head = foundHeader(result, criteria);
    return (
        <div className={cn('font-sf text-sf-ink transition-opacity', !fresh && 'opacity-60')} aria-busy={!fresh}>
            <p className="px-1 pt-1 text-sf-note">
                {head.found ? <>Нашлось <b className="font-semibold">{head.found}</b> {head.tail}</> : <>Ничего не нашлось {head.tail}</>}
            </p>
            {head.more && <p className="px-1 text-sf-label text-sf-muted">{head.more}</p>}
            {foundByMonth(result.rows).map(m => (
                <section key={m.label}>
                    <h3 className="px-2 pb-1 pt-4 text-sf-label font-semibold text-sf-muted">{m.label}</h3>
                    <div className="divide-y divide-sf-rule">
                        {m.rows.map(r => {
                            const Icon = iconOf(r.event);
                            const fact = r.event.factAmount != null;
                            return (
                                <div key={r.event.id}>
                                    <button type="button" onClick={() => onRow(r.event)}
                                        className="flex w-full items-center gap-3 rounded-sf-field px-2 py-2.5 text-left transition-colors hover:bg-sf-plate">
                                        <KindMark kind={KIND[r.event.priority]} fact={fact} icon={<Icon strokeWidth={1.7} />} />
                                        <span className="min-w-0 flex-1">
                                            <span className="block truncate text-sf-body">{r.name}</span>
                                            <span className="block truncate text-sf-label text-sf-muted">{r.sub}</span>
                                        </span>
                                        <span className={cn('shrink-0 text-sf-body tabular-nums', fact && 'font-medium')}>{r.amount}</span>
                                    </button>
                                </div>
                            );
                        })}
                    </div>
                </section>
            ))}
        </div>
    );
}
