import { useCallback, useEffect, useState } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { CreditCard, List, Plus, RefreshCw, Sun, Target, User } from 'lucide-react';
import { buttonVariants, capsuleItemVariants, Capsule, iconButtonVariants, Mark, Money, navTabVariants, NavTrack, Tile } from '@/ds';
import { cn } from '@/lib/utils';
import { fetchPocket } from '../../api';
import { CHANGED } from '../../api/client';
import ReanchorSheet from '../pocket/ReanchorSheet';
import type { PocketResponse } from '../../types/api';
import { FAR, MID, TREES } from './miniLandscape';
import { SECTIONS, headerLine, sectionOf, shortDate, showsCards, showsHeader } from './sections';

/**
 * Каркас приложения — срез 1 вехи «Дизайн» (спека 2026-11-15-slice1-app-frame-design.md).
 * Телефон — капсула снизу (Zh2-390), от 1024 px — полоса разделов сверху (Zh2-1440-7t, решение 38);
 * шапка Ш1 на мини-пейзаже (Zh2-J8, решение 42) — на всех экранах, кроме «Сегодня».
 */

const ICONS = { today: Sun, journal: List, goals: Target, money: CreditCard } as const;

/** «Обновить остатки» на «Деньгах» открывает лист шапки — до PR 5 среза 4 это прежний лист остатка основной. */
export const REANCHOR = 'selfin:reanchor';

/** Ширина каркаса на компьютере: поля 130 px на 1440 (Zh2-1440-7t). */
const WIDTH = 'mx-auto w-full max-w-[1180px]';

const num = (n: number) => Math.round(n).toLocaleString('ru-RU').replace(/\s/g, ' ');

/** Профиль → настройки (опись № 36); на «Сегодня» на телефоне — в строке «Свободно до …» (Zh2-390). */
export const profileLink = (variant: 'plain' | 'tile', className?: string) => (
    <Link to="/settings" aria-label="Профиль и настройки" className={cn(iconButtonVariants({ variant }), '[&_svg]:size-5', className)}>
        <User strokeWidth={1.6} />
    </Link>
);

/** Разделы — капсула на телефоне и полоса на компьютере; «+» в обеих. */
export function FrameNav({ onAdd }: { onAdd: () => void }) {
    const current = sectionOf(useLocation().pathname)?.key;
    const link = (s: (typeof SECTIONS)[number], look: 'capsule' | 'track') => {
        const Icon = ICONS[s.key];
        const active = s.key === current;
        return (
            <Link key={s.key} to={s.screens[0].to} aria-current={active ? 'page' : undefined}
                className={look === 'capsule' ? capsuleItemVariants({ active }) : navTabVariants({ active })}>
                {look === 'capsule' && <Icon strokeWidth={active ? 1.7 : 1.5} />}
                {s.label}
            </Link>
        );
    };
    return (
        <>
            <div className="hidden h-[76px] items-center justify-between lg:flex">
                <span className="flex-1"><Mark withName /></span>
                <NavTrack aria-label="Разделы">{SECTIONS.map(s => link(s, 'track'))}</NavTrack>
                <span className="flex flex-1 items-center justify-end gap-2.5">
                    <button type="button" onClick={onAdd} className={buttonVariants({ size: 'm' })}>
                        <Plus size={18} strokeWidth={2} />Записать
                    </button>
                    {profileLink('tile')}
                </span>
            </div>
            <div className="fixed inset-x-[14px] bottom-[14px] z-40 lg:hidden">
                <Capsule aria-label="Разделы">
                    {link(SECTIONS[0], 'capsule')}
                    {link(SECTIONS[1], 'capsule')}
                    <button type="button" onClick={onAdd} aria-label="Записать"
                        className={cn(iconButtonVariants({ variant: 'accent', size: 'xl' }), 'mx-1 [&_svg]:size-6')}>
                        <Plus strokeWidth={2} />
                    </button>
                    {link(SECTIONS[2], 'capsule')}
                    {link(SECTIONS[3], 'capsule')}
                </Capsule>
            </div>
        </>
    );
}

/**
 * Шапка Ш1: «Свободно до …» и число — на «Сегодня»; «на картах» — обновить остаток; профиль — на телефоне.
 * На «Деньгах» плитки «на картах» нет — она вела бы туда же (срез 4, решение 55).
 */
export function FrameHeader({ pocket, onReanchor, cards: showCards = true }: {
    pocket: PocketResponse | null; onReanchor: () => void; cards?: boolean;
}) {
    const line = pocket && headerLine(pocket);
    const cards = pocket && num(pocket.currentBalance);
    const updated = pocket?.checkpointDate ? shortDate(pocket.checkpointDate) : null;
    // «12 нояб.» уже с точкой, «15 мая» — без: точка перед «Обновить» одна.
    const cardsLabel = `На картах ${cards} ₽${updated ? `, обновлён ${updated}` : ''}`.replace(/\.?$/, '. Обновить');
    return (
        <div className="flex min-h-[54px] items-center gap-2 pb-4 pt-2.5">
            <Link to="/" className="flex min-w-0 flex-1 flex-col justify-center rounded-sf-field">
                {line && <>
                    <span className="truncate text-sf-label text-sf-muted">{line.label}</span>
                    <Money value={num(line.amount)} size="s" />
                </>}
            </Link>
            {pocket && showCards && (
                <Tile icon={<CreditCard strokeWidth={1.7} />} value={`${cards} ₽`} onClick={onReanchor}
                    caption={updated && <><RefreshCw strokeWidth={2.4} />{updated}</>}
                    aria-label={cardsLabel} />
            )}
            {profileLink('tile', 'lg:hidden')}
        </div>
    );
}

/**
 * Экраны внутри раздела, где их больше одного: «Журнал | Итоги месяца», «Цели | Примерить покупку | На годы вперёд».
 * Стоит в колонке экрана (App.tsx, Main), чтобы на 1440 начинаться с края содержимого; на 390 три вкладки — кеглем 13.
 */
export function SectionTrack() {
    const path = useLocation().pathname;
    const section = sectionOf(path);
    if (!section || section.screens.length < 2) return null;
    return (
        <div className="overflow-x-auto px-4 pt-3 scrollbar-none">
            <NavTrack aria-label="Экраны раздела" className="w-max bg-sf-chip">
                {section.screens.map(s => (
                    <Link key={s.to} to={s.to} aria-current={s.to === path ? 'page' : undefined}
                        className={cn(navTabVariants({ active: s.to === path }), 'px-3 text-sf-note sm:px-[18px] sm:text-sf-control')}>{s.label}</Link>
                ))}
            </NavTrack>
        </div>
    );
}

function MiniLandscape() {
    return (
        <svg aria-hidden="true" viewBox="0 0 390 104" preserveAspectRatio="none"
            className="pointer-events-none absolute inset-x-0 bottom-0 block h-[104px] w-full">
            <polygon points={FAR} fill="var(--sf-land-far)" />
            <polygon points={MID} fill="var(--sf-land-mid)" />
            <path d={TREES} fill="var(--sf-land-tree)" />
        </svg>
    );
}

export default function AppFrame({ children, onAdd, onChanged, refreshSignal }: {
    children: React.ReactNode;
    onAdd: () => void;
    /**
     * Остаток обновлён из шапки — перечитать и экран (ревью Codex на #225): «Журнал» берёт из даты остатка границу
     * быстрой записи, и со старой датой вчерашняя бронь закрылась бы вчерашним числом, уже вошедшим в новый остаток.
     */
    onChanged: () => void;
    /** Растёт после записи из «+» — шапка перечитывает число. */
    refreshSignal: number;
}) {
    const path = useLocation().pathname;
    const header = showsHeader(path);
    const [pocket, setPocket] = useState<PocketResponse | null>(null);
    const [reanchor, setReanchor] = useState(false);

    const load = useCallback(() => { fetchPocket().then(setPocket).catch(() => setPocket(null)); }, []);
    // По каждому переходу и после любой удачной записи любым экраном (CHANGED): журнал, копилка, НЗ меняют число.
    useEffect(() => { if (header) load(); }, [header, path, refreshSignal, load]);
    useEffect(() => {
        if (!header) return;
        window.addEventListener(CHANGED, load);
        return () => window.removeEventListener(CHANGED, load);
    }, [header, load]);
    useEffect(() => {
        if (!pocket) { setReanchor(false); return; }
        const open = () => setReanchor(true);
        window.addEventListener(REANCHOR, open);
        return () => window.removeEventListener(REANCHOR, open);
    }, [pocket]);

    return (
        <div className="min-h-dvh bg-sf-page font-sf text-sf-ink" style={{ paddingBottom: 'var(--nav-height)' }}>
            {/* На «Сегодня» небо со своим числом и пейзажем рисует экран (срез 2): здесь только полоса 1440 цветом верха неба. */}
            <div className={cn('relative', header
                ? 'bg-[linear-gradient(180deg,var(--sf-sky-top)_0%,var(--sf-sky-mid)_52%,var(--sf-sky-bottom)_100%)]'
                : 'bg-[var(--sf-sky-top)]')}>
                {header && <MiniLandscape />}
                <div className={cn(WIDTH, 'relative px-sf-gutter', header && 'pb-3.5')}>
                    <FrameNav onAdd={onAdd} />
                    {header && <FrameHeader pocket={pocket} onReanchor={() => setReanchor(true)} cards={showsCards(path)} />}
                </div>
            </div>
            <div className={cn('relative', header && '-mt-3.5 rounded-t-sf-page bg-sf-page')}>
                <div className={header ? WIDTH : undefined}>{children}</div>
            </div>
            {pocket && (
                <ReanchorSheet open={reanchor} onOpenChange={setReanchor}
                    currentBalance={pocket.currentBalance} checkpointDate={pocket.checkpointDate}
                    onSuccess={onChanged} />
            )}
        </div>
    );
}
