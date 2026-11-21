import { useState } from 'react';
import { Pencil, Plus, Trash2 } from 'lucide-react';
import { Button, Choices, Field, Flag, IconButton } from '@/ds';
import { cn } from '@/lib/utils';
import { createCategory, cycleCategoryPriority, deleteCategory, updateCategory } from '../api';
import type { Category, CategoryType, ForecastReadiness } from '../types/api';
import FormSheet from './FormSheet';
import { CATEGORY_RULE, CATEGORY_SPLIT, CATEGORY_SPLIT_LEAD, CATEGORY_SPLIT_TAIL, CATEGORY_SPLIT_TITLE } from '../lib/categoryRule';
import { PRIORITY_DOT_CONFIG, priorityTitle } from '../lib/priority';
import { DELETE_FAILED, WRITE_FAILED } from '../lib/writeFailure';

/**
 * «Категории» в «Настройках» (срез 3, п. 15, ANO-180; доска S3-F5 без перетаскивания и черты «на виду»):
 * «Расходы | Доходы», список в порядке сервера (п. 14), кружок вида — сменить вид, галочка «прогноз», правка и
 * удаление. Над «Новая категория» — правило категории и лист «Как делить категории» (lib/categoryRule).
 */

const MONTHS_GENITIVE = ['января', 'февраля', 'марта', 'апреля', 'мая', 'июня',
    'июля', 'августа', 'сентября', 'октября', 'ноября', 'декабря'];

/** '2026-11' → 'ноября'. Родительный падеж: подставляется в «с ноября». */
const monthLabel = (yearMonth: string) => MONTHS_GENITIVE[Number(yearMonth.slice(5, 7)) - 1] ?? yearMonth;

/**
 * ANO-80. Три факта, о которые иначе спотыкаются: откуда берётся прогноз, почему может
 * не появиться сразу и что главное число кармашка он не трогает. Подписью, а не модалкой:
 * модалка читается как предупреждение о риске, а предупреждать тут не о чем.
 */
const FORECAST_HINT = 'Считает, сколько обычно уходит в эту категорию за месяц — по последним '
    + 'месяцам, включая те, когда трат не было. Нужно три месяца наблюдений. На свободные деньги '
    + 'не влияет: показывается отдельным числом рядом.';

const DOT = {
    HIGH: 'bg-sf-kind-bron-bg text-sf-kind-bron',
    MEDIUM: 'bg-sf-kind-ozh-bg text-sf-kind-ozh',
    LOW: 'bg-sf-kind-hot-bg text-sf-kind-hot',
} as const;

const TYPES = [{ value: 'EXPENSE', label: 'Расходы' }, { value: 'INCOME', label: 'Доходы' }] as const;

type Draft = { id: string | null; name: string; type: CategoryType; priority: Category['priority']; forecast: boolean; primary: boolean };

const body = (d: Draft) => ({
    name: d.name.trim(), type: d.type, priority: d.priority,
    forecastEnabled: d.type === 'EXPENSE' ? d.forecast : false,
    primaryIncome: d.type === 'INCOME' ? d.primary : false,
});

/** Новая категория и правка — одна форма листом: имя, расход или доход, прогноз или «основной доход». */
function CategorySheet({ start, onClose, onSaved }: { start: Draft; onClose: () => void; onSaved: (created: boolean) => void }) {
    const [d, setD] = useState(start);
    const [saving, setSaving] = useState(false);
    const [failed, setFailed] = useState(false);
    const save = (e: React.FormEvent) => {
        e.preventDefault();
        if (!d.name.trim() || saving) return;
        setSaving(true);
        setFailed(false);
        (d.id ? updateCategory(d.id, body(d)) : createCategory(body(d)))
            .then(() => onSaved(d.id == null), () => { setFailed(true); setSaving(false); });
    };
    return (
        <FormSheet label={d.id ? 'Изменить категорию' : 'Новая категория'} onClose={onClose}>
            <form onSubmit={save} className="flex flex-col gap-3">
                <h2 className="text-sf-button font-semibold">{d.id ? 'Изменить категорию' : 'Новая категория'}</h2>
                <Field aria-label="Название категории" placeholder="Название категории" value={d.name} autoFocus
                    onChange={e => setD({ ...d, name: e.target.value })} />
                <Choices name="category-type" label="Расход или доход" value={d.type}
                    options={[{ value: 'EXPENSE', label: 'Расход' }, { value: 'INCOME', label: 'Доход' }]}
                    onChange={v => setD({ ...d, type: v as CategoryType })} />
                {d.type === 'EXPENSE' ? (
                    <div className="flex flex-col gap-1.5">
                        <Flag checked={d.forecast} onChange={e => setD({ ...d, forecast: e.target.checked })} className="self-start">
                            Отслеживать прогноз
                        </Flag>
                        <p className="px-1 text-sf-label text-sf-muted">{FORECAST_HINT}</p>
                    </div>
                ) : (
                    <Flag checked={d.primary} onChange={e => setD({ ...d, primary: e.target.checked })} className="self-start">
                        Основной доход (до него считаются свободные деньги)
                    </Flag>
                )}
                {failed && <p className="text-sf-label text-sf-error">{WRITE_FAILED}</p>}
                <Button type="submit" disabled={!d.name.trim() || saving}>{saving ? 'Сохраняем…' : 'Сохранить'}</Button>
            </form>
        </FormSheet>
    );
}

export default function CategoriesSection({ categories, readiness, onChanged, showToast }: {
    /** Порядок сервера (п. 14): частые первыми. */
    categories: Category[];
    /** ANO-80: когда прогноз станет доступен — чтобы «включил, а пусто» не читалось как поломка. */
    readiness: ForecastReadiness | null;
    onChanged: () => unknown;
    showToast: (text: string) => void;
}) {
    const [type, setType] = useState<CategoryType>('EXPENSE');
    const [sheet, setSheet] = useState<Draft | null>(null);
    const [howTo, setHowTo] = useState(false);
    const [failed, setFailed] = useState<string | null>(null);
    const list = categories.filter(c => c.type === type);

    const act = (p: Promise<unknown>, done: string | null, failure: string) => {
        setFailed(null);
        p.then(() => { onChanged(); if (done) showToast(done); }, () => setFailed(failure));
    };
    const remove = (c: Category) => {
        if (!confirm('Удалить категорию? Связанные события сохранятся.')) return;
        act(deleteCategory(c.id), 'Категория удалена', DELETE_FAILED);
    };
    const edit = (c: Category) => setSheet({
        id: c.id, name: c.name, type: c.type, priority: c.priority, forecast: c.forecastEnabled, primary: c.primaryIncome,
    });

    return (
        <section className="flex flex-col gap-2.5 font-sf text-sf-ink">
            <h2 className="text-sf-button font-semibold">Категории</h2>
            <Choices name="categories-type" label="Расходы или доходы" options={TYPES} value={type}
                onChange={v => setType(v as CategoryType)} />
            {list.length === 0
                ? <p className="py-2 text-sf-note text-sf-muted">Нет категорий</p>
                : (
                    <ul className="flex flex-col">
                        {list.map(c => {
                            const sub = [
                                c.isSystem ? 'системная' : null,
                                c.forecastEnabled && readiness?.readyFrom ? `прогноз — с ${monthLabel(readiness.readyFrom)}` : null,
                                c.primaryIncome ? 'основной доход' : null,
                            ].filter(Boolean).join(' · ');
                            return (
                                <li key={c.id} className="flex min-h-[52px] items-center gap-1 border-b border-sf-rule">
                                    <button type="button" title={priorityTitle(c.priority)}
                                        aria-label={`Вид: ${PRIORITY_DOT_CONFIG[c.priority].name.toLowerCase()}. Нажми — сменить`}
                                        onClick={() => act(cycleCategoryPriority(c.id), null, WRITE_FAILED)}
                                        className="-ml-3 flex size-11 shrink-0 items-center justify-center rounded-sf-pill hover:bg-sf-chip">
                                        <span aria-hidden="true" className={cn('size-3 rounded-full shadow-[inset_0_0_0_1.5px_currentColor]', DOT[c.priority])} />
                                    </button>
                                    <span className="min-w-0 flex-1">
                                        <span className="block truncate text-sf-body">{c.name}</span>
                                        {sub && <span className="block truncate text-sf-label text-sf-muted">{sub}</span>}
                                    </span>
                                    {c.type === 'EXPENSE' && !c.isSystem && (
                                        <label title={FORECAST_HINT} className="flex min-h-[44px] cursor-pointer select-none items-center gap-1.5 px-1 text-sf-label text-sf-muted">
                                            <input type="checkbox" checked={c.forecastEnabled} className="size-4 accent-[var(--sf-accent)]"
                                                onChange={e => act(updateCategory(c.id, body({
                                                    id: c.id, name: c.name, type: c.type, priority: c.priority,
                                                    forecast: e.target.checked, primary: c.primaryIncome,
                                                })), null, WRITE_FAILED)} />
                                            прогноз
                                        </label>
                                    )}
                                    {!c.isSystem && <>
                                        <IconButton aria-label={`Изменить «${c.name}»`} onClick={() => edit(c)} className="size-9 text-sf-muted">
                                            <Pencil size={15} />
                                        </IconButton>
                                        <IconButton aria-label={`Удалить «${c.name}»`} onClick={() => remove(c)} className="size-9 text-sf-muted">
                                            <Trash2 size={15} />
                                        </IconButton>
                                    </>}
                                </li>
                            );
                        })}
                    </ul>
                )}
            {failed && <p className="text-sf-label text-sf-error">{failed}</p>}
            <div className="mt-1 flex flex-col gap-2 rounded-sf-plate bg-sf-plate px-3.5 py-3">
                <p className="text-sf-note leading-snug">{CATEGORY_RULE}</p>
                <div className="flex flex-wrap items-center justify-between gap-2">
                    <button type="button" onClick={() => setHowTo(true)} className="text-sf-note font-semibold text-sf-accent">
                        {CATEGORY_SPLIT_TITLE}
                    </button>
                    <Button size="s" className="h-10 px-4" onClick={() => setSheet({ id: null, name: '', type, priority: 'MEDIUM', forecast: false, primary: false })}>
                        <Plus size={15} strokeWidth={2.4} />Новая категория
                    </Button>
                </div>
            </div>
            {sheet && (
                <CategorySheet start={sheet} onClose={() => setSheet(null)}
                    onSaved={created => { setSheet(null); onChanged(); showToast(created ? 'Категория добавлена' : 'Категория обновлена'); }} />
            )}
            {howTo && (
                <FormSheet label={CATEGORY_SPLIT_TITLE} onClose={() => setHowTo(false)}>
                    <h2 className="text-sf-button font-semibold">{CATEGORY_SPLIT_TITLE}</h2>
                    <p className="text-sf-body">{CATEGORY_SPLIT_LEAD}</p>
                    <dl className="flex flex-col gap-2.5">
                        {CATEGORY_SPLIT.map(s => (
                            <div key={s.question}>
                                <dt className="text-sf-body font-semibold">{s.question}</dt>
                                <dd className="text-sf-note text-sf-muted">{s.answer}</dd>
                            </div>
                        ))}
                    </dl>
                    <p className="text-sf-note">{CATEGORY_SPLIT_TAIL}</p>
                </FormSheet>
            )}
        </section>
    );
}
