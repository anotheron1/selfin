import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ChevronLeft, Home, Plus, ReceiptText, Smartphone } from 'lucide-react';
import { Button, Field, Flag, IconButton, KindMark, Mark, Money } from '@/ds';
import { cn } from '@/lib/utils';
import { fetchCategories, fetchPocket } from '../api';
import { Landscape, PayFlags } from '../components/today/Landscape';
import { UsualSpendingForm } from '../components/today/UsualSpendingForm';
import { latestAllowedIso, serverTodayIso } from '../lib/factDate';
import {
    balanceAnswer, incomeAnswer, incomeDto, needsFirstEntry, paymentAnswer, paymentCategory, paymentDto,
} from '../lib/firstEntry';
import { FirstEntryWrites } from '../lib/firstEntryWrites';
import { fmtDayShort } from '../lib/format';
import { LatestRequests } from '../lib/latestRequests';
import { termIncome } from '../lib/usualSpending';
import type { Category, PocketResponse } from '../types/api';

const STEPS = 4;
const num = (n: number) => Math.round(n).toLocaleString('ru-RU');

function Steps({ step }: { step: number }) {
    return (
        <span role="img" aria-label={`Вопрос ${step} из ${STEPS}`} className="flex gap-1.5">
            {Array.from({ length: STEPS }, (_, i) => (
                <span key={i} data-step={i + 1 === step ? 'current' : i < step ? 'done' : 'ahead'} className={cn(
                    'h-1.5 rounded-full lg:h-[7px]',
                    i + 1 === step ? 'w-[18px] lg:w-[22px]' : 'w-1.5 lg:w-[7px]',
                    i < step ? 'bg-sf-accent' : 'bg-[color:color-mix(in_srgb,var(--sf-ink)_20%,transparent)]')} />
            ))}
        </span>
    );
}

const Back = ({ onClick, className }: { onClick: () => void; className?: string }) => (
    <IconButton aria-label="Назад" onClick={onClick} className={cn('-ml-3', className)}>
        <ChevronLeft size={22} strokeWidth={1.8} />
    </IconButton>
);

/**
 * Небо первого входа (доски S2-W2a, S2-W2d, S2-W-1440): точки шагов, число и пейзаж — ответ движка. Солнце пунктиром
 * «доход» — пока дохода срока нет; платежи — флажками на обрывах.
 */
export function StartSky({ data, step, onBack }: { data: PocketResponse; step: number; onBack?: () => void }) {
    const income = termIncome(data);
    const land = (size: 'narrow' | 'wide') => (
        <Landscape data={data} size={size} dashedSun={!income} overlay={m => <PayFlags m={m} data={data} />} />
    );
    return (
        <section className="relative bg-[linear-gradient(180deg,var(--sf-sky-top)_0%,var(--sf-sky-mid)_52%,var(--sf-sky-bottom)_100%)]">
            <div className="mx-auto flex max-w-[1180px] flex-col px-sf-gutter pt-[18px] lg:pt-0">
                <div className="flex h-11 items-center gap-3 lg:hidden">
                    {step > 1 && onBack && <Back onClick={onBack} />}
                    <Steps step={step} />
                </div>
                <div className="hidden h-[76px] items-center justify-between lg:flex">
                    <Mark withName />
                    <Steps step={step} />
                </div>
                <span className="text-sf-control text-sf-muted lg:mt-1 lg:text-sf-body">
                    {income ? `Свободно до ${fmtDayShort(data.horizon.endDate)}` : 'Свободно — пока без плана'}
                </span>
                <Money value={num(data.pocket)} className="lg:mt-1.5 lg:text-[104px]" />
            </div>
            <div className="lg:hidden">{land('narrow')}</div>
            <div className="hidden lg:block">{land('wide')}</div>
        </section>
    );
}

type Row = { key: number; name: string; day: string; amount: string };
const PAY_ICON = { 'Жильё и быт': Home, 'Связь и подписки': Smartphone, 'Обязательные платежи': ReceiptText } as const;

function PayRow({ row, onChange, onSettle }: { row: Row; onChange: (r: Row) => void; onSettle: (r: Row) => void }) {
    const Icon = PAY_ICON[paymentCategory(row.name.trim())];
    return (
        <div className="flex min-h-14 items-center gap-3 border-b border-sf-rule py-1.5 lg:min-h-[60px] lg:gap-3.5">
            <KindMark kind="bron" icon={<Icon strokeWidth={1.7} />} />
            <input value={row.name} placeholder="Название" aria-label="Что за платёж"
                onChange={e => onChange({ ...row, name: e.target.value })} onBlur={() => onSettle(row)}
                className="h-11 min-w-0 flex-1 bg-transparent font-sf text-sf-button text-sf-ink outline-none placeholder:text-sf-muted" />
            <select value={row.day} aria-label="Какого числа"
                onChange={e => { const r = { ...row, day: e.target.value }; onChange(r); onSettle(r); }}
                className="h-sf-chip shrink-0 rounded-sf-pill bg-sf-chip px-3 font-sf text-sf-note text-sf-ink outline-none focus:shadow-[inset_0_0_0_2px_var(--sf-accent)] lg:text-sf-control">
                <option value="">число</option>
                {Array.from({ length: 31 }, (_, i) => <option key={i} value={i + 1}>{i + 1}-го</option>)}
            </select>
            <Field className="w-[108px] shrink-0 lg:w-[120px]" inputMode="decimal" placeholder="0" suffix="₽" aria-label="Сумма, ₽"
                value={row.amount} onChange={e => onChange({ ...row, amount: e.target.value })} onBlur={() => onSettle(row)} />
        </div>
    );
}

/**
 * Первый вход (спека среза 2, пп. 11, 16; ANO-250): четыре вопроса по одному, без навигации, как вход и согласие.
 * Ответ пишется, когда шаг пройден, строка платежа — когда в ней есть число и сумма; после каждой записи — `/pocket`
 * заново. «Назад» правит записанное (`firstEntryWrites.ts`). Вопросы 1 и 2 обязательны, 3 и 4 — с «Пропустить»;
 * после 4 — «Сегодня». Человек с данными сюда не попадает: уходит на «Сегодня».
 */
export default function Start() {
    const navigate = useNavigate();
    const [data, setData] = useState<PocketResponse | null>(null);
    const [cats, setCats] = useState<Category[] | null>(null);
    const [loadFailed, setLoadFailed] = useState(false);
    const [step, setStep] = useState(1);
    const [balance, setBalance] = useState('');
    const [income, setIncome] = useState({ date: '', amount: '', monthly: true });
    const [rows, setRows] = useState<Row[]>([{ key: 1, name: '', day: '', amount: '' }]);
    const [error, setError] = useState<string | null>(null);
    const [writes] = useState(() => new FirstEntryWrites());
    const [reads] = useState(() => new LatestRequests());

    useEffect(() => {
        fetchPocket()
            .then(p => { if (needsFirstEntry(p)) setData(p); else navigate('/', { replace: true }); })
            .catch(() => setLoadFailed(true));
        fetchCategories().then(setCats).catch(() => setLoadFailed(true));
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    /** Записать и перечитать число; `then` — шаг после удачной записи. */
    const save = async (write: () => Promise<boolean | boolean[]>, then?: number) => {
        setError(null);
        try {
            if ([await write()].flat().some(Boolean)) {
                const n = reads.start('pocket');
                const p = await fetchPocket();
                if (reads.isLatest('pocket', n)) setData(p);
            }
            if (then) setStep(then);
        } catch (err) {
            console.error(err);
            setError('Не записалось — попробуй ещё раз');
        }
    };

    if (!data || !cats) {
        return (
            <div className="flex min-h-dvh flex-col items-center justify-center gap-3 bg-sf-page font-sf text-sf-control text-sf-muted">
                {loadFailed ? 'Не удалось загрузить' : 'Загрузка…'}
                {loadFailed && <Button size="s" onClick={() => window.location.reload()}>Повторить</Button>}
            </div>
        );
    }

    const today = serverTodayIso(new Date());
    const rowDto = (r: Row) => {
        const a = paymentAnswer(r);
        return a && paymentDto(cats, a, today);
    };
    const settle = (r: Row) => save(() => writes.plan(`pay:${r.key}`, rowDto(r)));
    const back = () => { setError(null); setStep(s => s - 1); };
    const finish = () => navigate('/', { replace: true });
    const until = fmtDayShort(termIncome(data)?.date ?? data.horizon.endDate);

    const onCard = balanceAnswer(balance);
    const nextIncome = incomeAnswer(income, today);
    const QUESTIONS: Record<1 | 2 | 3, { title: string; sub: string; ready: boolean; next: () => void; body: React.ReactNode }> = {
        1: {
            title: 'Сколько сейчас на основной карте?',
            sub: 'Посмотри в приложении банка. Кредитный лимит не считай — только свои деньги.',
            ready: onCard != null,
            next: () => save(() => writes.balance({ date: latestAllowedIso(new Date()), amount: onCard! }), 2),
            body: <>
                <Field look="amount" autoFocus inputMode="decimal" placeholder="0" suffix="₽" aria-label="Остаток, ₽"
                    value={balance} onChange={e => setBalance(e.target.value)} />
                <span className="text-sf-note text-sf-muted">Склон наверху — это твой остаток. Доход и платежи дальше покажут, куда он пойдёт.</span>
            </>,
        },
        2: {
            title: 'Когда и сколько следующий доход?',
            sub: 'До него и считается «Свободно».',
            ready: nextIncome != null,
            next: () => save(() => writes.plan('income', incomeDto(cats, nextIncome!)), 3),
            body: <>
                <Field type="date" min={today} aria-label="Когда придёт" value={income.date}
                    onChange={e => setIncome(v => ({ ...v, date: e.target.value }))} />
                <Field look="amount" inputMode="decimal" placeholder="0" suffix="₽" aria-label="Сколько придёт, ₽"
                    value={income.amount} onChange={e => setIncome(v => ({ ...v, amount: e.target.value }))} />
                <Flag className="self-start" checked={income.monthly}
                    onChange={e => setIncome(v => ({ ...v, monthly: e.target.checked }))}>каждый месяц</Flag>
            </>,
        },
        3: {
            title: `Что точно уйдёт до ${until}?`,
            sub: 'Аренда, ипотека, связь — что не отменишь. Можно пропустить.',
            ready: true,
            next: () => save(() => Promise.all(rows.map(r => writes.plan(`pay:${r.key}`, rowDto(r)))), 4),
            body: <>
                <div className="flex flex-col">
                    {rows.map(r => (
                        <PayRow key={r.key} row={r} onSettle={settle}
                            onChange={next => setRows(rs => rs.map(x => (x.key === next.key ? next : x)))} />
                    ))}
                </div>
                <button type="button" onClick={() => setRows(rs => [...rs, { key: rs[rs.length - 1].key + 1, name: '', day: '', amount: '' }])}
                    className="flex h-12 items-center justify-center gap-1.5 rounded-2xl border-[1.5px] border-dashed border-sf-rule font-sf text-sf-body font-semibold text-sf-accent">
                    <Plus size={18} strokeWidth={2} aria-hidden="true" />Ещё платёж
                </button>
            </>,
        },
    };

    return (
        <div className="flex min-h-dvh flex-col bg-sf-page font-sf text-sf-ink">
            <StartSky data={data} step={step} onBack={back} />
            <section className="relative -mt-6 flex flex-1 flex-col rounded-t-[28px] bg-sf-page lg:-mt-7 lg:rounded-t-[32px]">
                <div className="mx-auto flex w-full max-w-[600px] flex-1 flex-col px-sf-gutter pb-5 pt-[18px] lg:px-0 lg:pb-[60px] lg:pt-7">
                    {step === 4
                        ? <>
                            <Back onClick={back} className="mb-1 hidden lg:inline-flex" />
                            <UsualSpendingForm data={data} onDone={finish} onSkip={finish} />
                        </>
                        : (() => {
                            const q = QUESTIONS[step as 1 | 2 | 3];
                            return (
                                <form className="flex flex-1 flex-col gap-3" onSubmit={e => { e.preventDefault(); if (q.ready) q.next(); }}>
                                    <div className="flex items-center gap-2">
                                        {step > 1 && <Back onClick={back} className="hidden lg:inline-flex" />}
                                        <h1 className="text-[22px] font-medium leading-tight lg:text-[26px]">{q.title}</h1>
                                    </div>
                                    <p className="-mt-1 text-sf-control text-sf-muted lg:text-sf-body">{q.sub}</p>
                                    {q.body}
                                    {error && <p role="alert" className="text-sf-label text-sf-muted">{error}</p>}
                                    <div className="mt-auto flex flex-col gap-1 pt-6 lg:mt-3 lg:flex-row-reverse lg:justify-start lg:gap-3 lg:pt-0">
                                        <Button type="submit" disabled={!q.ready} className="w-full lg:w-auto lg:px-10">Дальше</Button>
                                        {step === 3 && <Button type="button" variant="text" onClick={() => setStep(4)} className="lg:px-6">Пропустить</Button>}
                                    </div>
                                </form>
                            );
                        })()}
                </div>
            </section>
        </div>
    );
}
