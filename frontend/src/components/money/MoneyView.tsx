import { useState, type ReactNode } from 'react';
import {
    Banknote, ChevronDown, CreditCard, House, Info, Landmark, PiggyBank, Plus, RefreshCw, Wallet, type LucideIcon,
} from 'lucide-react';
import { Button, Money } from '@/ds';
import { cn } from '@/lib/utils';
import type { Account, CapitalItem, CapitalSummary, CapitalTrajectory, TargetFund } from '../../types/api';
import { accountLine, capitalHead, moneyGroups } from '../../lib/moneyGroups';
import { fmtAmount, fmtDayShort, fmtNum, fmtSigned } from '../../lib/format';
import MoneyChart from './MoneyChart';

/**
 * «Деньги» на деталях системы (срез 4, п. 9; S4-W1, на 1440 — S4-W2; новый человек — S4-M4). Что куда — lib/moneyGroups;
 * здесь только вид. Чтения и листы — у страницы (pages/Capital.tsx).
 */

/** Ответ, который «+» в группе уже знает (S4-A3): форма карты открывается с ним. */
export type CardPreset = 'spending' | 'savings' | 'credit';

export interface MoneyViewProps {
    summary: CapitalSummary;
    accounts: Account[];
    funds: TargetFund[];
    points: CapitalTrajectory['points'];
    /** Местная дата, ISO: «записано в ноябре», период графика. */
    today: string;
    onUpdate: () => void;
    onAddCard: (preset?: CardPreset) => void;
    onAccount: (a: Account) => void;
    onItem: (i: CapitalItem) => void;
    onAddItem: () => void;
}

/** Строки «как записывать» (S4-W1). Про «В кредит» и «Накопление» в копилку на счёте — когда появятся в форме «+» (PR 6). */
const HOW = {
    main: 'Сюда приходит доход, отсюда платишь. Кредитная, если есть, — в паре с ней: переводы между ними не записывай.',
    spending: 'Перевёл на карту для бензина — одна трата «Бензин». Снял наличные — одна трата «Наличные». '
        + 'Заправки и покупки по отдельности не нужны.',
    savings: 'Отложил — переведи на счёт в банке и обнови его остаток.',
};

/** Приглашения пустых групп (S4-M4). */
const INVITE = {
    credit: { title: 'Кредитка', text: 'Кредитка — не деньги, а долг: selfin держит, сколько доступно и до скольких возвращаешь.', add: 'Кредитка' },
    spending: { title: 'Для отдельных трат', text: 'Бензин, продукты — отдельной картой: перевёл на неё и записал одну трату вместо десяти.', add: 'Карта для трат' },
    savings: { title: 'Накопительные', text: 'Вклад или накопительный счёт: здесь лежат копилки, в «Свободно» они не входят.', add: 'Накопительный счёт' },
    items: { title: 'Имущество и долги', text: 'Квартира, машина, кредиты — чтобы видеть капитал: что есть минус что должен.', add: 'Добавить' },
};

const iconOf = (a: Account): LucideIcon =>
    a.kind === 'DEPOSIT' ? PiggyBank : a.kind === 'CASH' ? Banknote : a.trackBalance ? CreditCard : Wallet;

export default function MoneyView(p: MoneyViewProps) {
    const g = moneyGroups(p.accounts, p.summary.items);
    const head = capitalHead(p.summary);
    const [archivedOpen, setArchivedOpen] = useState(false);
    const card = (a: Account, paired = false) => (
        <AccountRow key={a.id} account={a} funds={p.funds} today={p.today} paired={paired} onClick={() => p.onAccount(a)} />
    );
    const itemRow = (i: CapitalItem) => <ItemRow key={i.id} item={i} onClick={() => p.onItem(i)} />;
    const hasItems = p.summary.items.length > 0;

    return (
        <div className="px-4 pb-8 pt-3.5 font-sf text-sf-ink lg:grid lg:grid-cols-[minmax(0,1fr)_420px] lg:grid-rows-[auto_1fr] lg:gap-x-14 lg:px-sf-gutter lg:pt-5">
            <section className="flex flex-col gap-0.5 lg:col-start-2 lg:row-start-1">
                <span className="text-sf-label text-sf-muted">Капитал — что есть минус что должен</span>
                <Money value={fmtNum(p.summary.total)} size="m" />
                <span className="mt-2 text-sf-note">
                    <span className="font-medium">{fmtSigned(p.summary.deltas.month)}</span> за месяц
                </span>
                <span className="text-sf-label text-sf-muted">{`есть ${fmtNum(head.has)} · должен ${fmtNum(head.owes)}`}</span>
                <div className="mt-4 flex gap-2.5">
                    <Button variant="soft" size="m" className="min-w-0 flex-1 gap-1.5 px-2 text-sf-control has-[svg]:pl-2" onClick={p.onUpdate}>
                        <RefreshCw size={16} strokeWidth={2} />Обновить остатки
                    </Button>
                    <Button variant="soft" size="m" className="min-w-0 flex-1 gap-1.5 px-2 text-sf-control has-[svg]:pl-2" onClick={() => p.onAddCard()}>
                        <Plus size={16} strokeWidth={2} />Добавить карту
                    </Button>
                </div>
            </section>

            <div className="mt-2 min-w-0 lg:col-start-1 lg:row-span-2 lg:row-start-1 lg:mt-0">
                <Group title="Основная" note="от неё считается «Свободно»">
                    {g.primary && card(g.primary)}
                    {g.credits.map(a => card(a, true))}
                    {g.credits.length === 0 && <Invite {...INVITE.credit} paired onAdd={() => p.onAddCard('credit')} />}
                    {g.together.map(a => card(a))}
                    <How text={HOW.main} />
                </Group>

                {g.spending.length > 0 ? (
                    <Group title="Для отдельных трат">
                        {g.spending.map(a => card(a))}
                        <How text={HOW.spending} />
                    </Group>
                ) : <Invite {...INVITE.spending} onAdd={() => p.onAddCard('spending')} />}

                {g.savings.length > 0 ? (
                    <Group title="Накопительные" note="в «Свободно» не входят">
                        {g.savings.map(a => card(a))}
                        <How text={HOW.savings} />
                    </Group>
                ) : <Invite {...INVITE.savings} onAdd={() => p.onAddCard('savings')} />}

                {g.assets.length > 0 && (
                    <Group title="Имущество" note={fmtAmount(g.assets.reduce((s, i) => s + i.currentValue, 0))}>
                        {g.assets.map(itemRow)}
                    </Group>
                )}
                {g.debts.length > 0 && (
                    <Group title="Долги" note={fmtAmount(g.debts.reduce((s, i) => s + i.currentValue, 0))}>
                        {g.debts.map(itemRow)}
                    </Group>
                )}
                {hasItems ? (
                    <div className="mt-3">
                        <div className="flex items-center justify-between gap-3">
                            <Button variant="text" size="s" onClick={p.onAddItem}><Plus size={16} strokeWidth={2} />Имущество или долг</Button>
                            {g.archived.length > 0 && (
                                <button type="button" aria-expanded={archivedOpen} onClick={() => setArchivedOpen(o => !o)}
                                    className="flex h-sf-control items-center gap-1 text-sf-control text-sf-muted hover:text-sf-ink">
                                    {`Архивные · ${g.archived.length}`}
                                    <ChevronDown size={14} className={cn('transition-transform', archivedOpen && 'rotate-180')} />
                                </button>
                            )}
                        </div>
                        {archivedOpen && <div className="opacity-70">{g.archived.map(itemRow)}</div>}
                    </div>
                ) : <Invite {...INVITE.items} onAdd={p.onAddItem} />}
            </div>

            <div className="mt-7 lg:col-start-2 lg:row-start-2 lg:mt-7">
                <MoneyChart points={p.points} today={p.today} />
            </div>
        </div>
    );
}

function Group({ title, note, children }: { title: string; note?: string; children: ReactNode }) {
    return (
        <section className="pt-[18px]">
            <div className="flex items-baseline justify-between gap-2.5 pb-0.5">
                <h2 className="text-sf-body font-semibold">{title}</h2>
                {note && <span className="whitespace-nowrap text-sf-label text-sf-muted">{note}</span>}
            </div>
            {children}
        </section>
    );
}

function How({ text }: { text: string }) {
    return (
        <p className="flex gap-[7px] pb-0.5 pt-2 text-sf-label leading-[1.4] text-sf-muted">
            <Info size={13} strokeWidth={1.8} className="mt-px shrink-0" />{text}
        </p>
    );
}

function Invite({ title, text, add, paired = false, onAdd }: { title: string; text: string; add: string; paired?: boolean; onAdd: () => void }) {
    return (
        <div className={cn('mt-3 flex flex-col gap-1.5 rounded-sf-plate border-[1.5px] border-dashed border-sf-rule px-3.5 py-3', paired && 'mt-2')}>
            <span className="text-sf-body font-semibold">{title}</span>
            <span className="text-sf-note text-sf-muted">{text}</span>
            <Button variant="soft" size="s" className="self-start pl-2.5" onClick={onAdd}><Plus size={16} strokeWidth={2} />{add}</Button>
        </div>
    );
}

/** Строка группы: кружок, название и строки под ним, сумма справа. */
function Row({ icon: Icon, name, sub, value, caption, paired = false, onClick, mark }: {
    icon: LucideIcon; name: string; sub: ReactNode[]; value: ReactNode; caption?: string | null; paired?: boolean;
    onClick: () => void; mark?: ReactNode;
}) {
    return (
        <button type="button" onClick={onClick}
            className="relative flex min-h-[60px] w-full items-center gap-3 border-b border-sf-rule py-2 text-left transition-colors hover:bg-sf-chip/40">
            {/* Пара основной и кредитки — пунктир между кружками (S4-W1). */}
            {paired && <span aria-hidden="true" className="absolute -top-2.5 left-5 h-4 border-l border-dotted border-sf-muted" />}
            <span aria-hidden="true" className="flex size-10 shrink-0 items-center justify-center rounded-full bg-sf-chip text-sf-ink">
                <Icon size={20} strokeWidth={1.6} />
            </span>
            <span className="flex min-w-0 flex-1 flex-col gap-px">
                <span className="truncate text-sf-body">{name}</span>
                {sub.map((s, i) => (
                    <span key={i} className="flex items-center gap-1 truncate text-sf-label leading-4 text-sf-muted">{i === 0 && mark}<span className="min-w-0 truncate">{s}</span></span>
                ))}
            </span>
            <span className="flex shrink-0 flex-col items-end">
                {value != null && <span className="whitespace-nowrap text-sf-body tabular-nums">{value}</span>}
                {caption && <span className="text-sf-caption text-sf-muted">{caption}</span>}
            </span>
        </button>
    );
}

function AccountRow({ account, funds, today, paired, onClick }: {
    account: Account; funds: TargetFund[]; today: string; paired: boolean; onClick: () => void;
}) {
    const l = accountLine(account, funds, today);
    return (
        <Row icon={iconOf(account)} name={account.name} sub={l.sub} value={l.value} caption={l.caption} paired={paired}
            onClick={onClick} mark={l.updated && <RefreshCw size={10} strokeWidth={2.4} className="shrink-0" />} />
    );
}

function ItemRow({ item, onClick }: { item: CapitalItem; onClick: () => void }) {
    const debt = item.kind === 'LIABILITY';
    return (
        <Row icon={debt ? Landmark : House} name={item.name} onClick={onClick}
            sub={[[item.isArchived ? (debt ? 'погашен' : 'продано') : 'оценка', item.lastValuedAt && fmtDayShort(item.lastValuedAt)].filter(Boolean).join(' ')]}
            value={item.isArchived ? null : debt ? fmtSigned(-item.currentValue) : fmtAmount(item.currentValue)} />
    );
}
