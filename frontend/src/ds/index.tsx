/**
 * Дизайн-система Selfin — базовые детали лица «Ж2. Пейзаж» (веха «Дизайн», этап 3; решения 34–36).
 *
 * Вид — только из токенов (./tokens.css, ./theme.ts), поэтому каждая деталь сама переходит в ночную тему.
 * Состояния: наведение, нажатие, фокус с клавиатуры, недоступно; у выбора — «выбрано», у галочки — ещё «вопрос»,
 * у поля — «ошибка». Выбор одного из нескольких и галочки стоят на родных radio и checkbox: стрелки, пробел
 * и экранный диктор работают без своего кода.
 * Экраны переходят на эти детали в своих срезах; components/ui не тронут.
 */
import * as React from 'react';
import { cva, type VariantProps } from 'class-variance-authority';
import { Check } from 'lucide-react';
import { cn } from '@/lib/utils';

/** Фокус с клавиатуры — сплошное кольцо акцента с зазором: видно и на акцентной заливке, и на странице. */
const focusRing = 'outline-none focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sf-accent';
const peerFocusRing = 'peer-focus-visible:outline peer-focus-visible:outline-2 peer-focus-visible:outline-offset-2 peer-focus-visible:outline-sf-accent';
const control = `font-sf transition-colors duration-150 disabled:pointer-events-none disabled:opacity-40 ${focusRing}`;

// ---------- Кнопки ----------

const buttonBase = cva(
    `inline-flex items-center justify-center gap-2 whitespace-nowrap rounded-sf-pill font-semibold [&_svg]:shrink-0 ${control}`,
    {
        variants: {
            variant: {
                primary: 'bg-sf-accent text-sf-on-accent hover:bg-sf-accent-hover active:bg-sf-accent-press',
                soft: 'bg-sf-accent-soft text-sf-accent hover:bg-sf-accent-soft-hover active:bg-sf-accent-soft-press active:text-sf-accent-press',
                outline: 'bg-transparent text-sf-accent shadow-[inset_0_0_0_1.5px_var(--sf-rule)] hover:bg-sf-accent-soft active:bg-sf-accent-soft-hover',
                text: 'bg-transparent text-sf-accent underline-offset-4 hover:text-sf-accent-hover hover:underline active:text-sf-accent-press', // «Итоги месяца»
            },
            size: {
                l: 'h-sf-button px-6 text-sf-button',               // кнопки формы
                m: 'h-sf-tile px-5 text-sf-body has-[svg]:pl-4',    // «+ Записать» в полосе 1440
                s: 'h-sf-chip px-3.5 text-sf-note',                 // на плашках: «Сдвинуть», «Обновить»
            },
        },
        compoundVariants: [{ variant: 'text', class: 'px-0.5 has-[svg]:pl-0.5' }],
        defaultVariants: { variant: 'primary', size: 'l' },
    },
);

/** Классы кнопки — и для ссылки, которая выглядит кнопкой. Через cn: у текстовой кнопки отступ перекрывает отступ размера. */
export const buttonVariants = (props?: VariantProps<typeof buttonBase>) => cn(buttonBase(props));

export interface ButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement>, VariantProps<typeof buttonBase> {}

export const Button = React.forwardRef<HTMLButtonElement, ButtonProps>(
    ({ className, variant, size, type = 'button', ...props }, ref) => (
        <button ref={ref} type={type} className={cn(buttonVariants({ variant, size }), className)} {...props} />
    ),
);
Button.displayName = 'Button';

export const iconButtonVariants = cva(`inline-flex shrink-0 items-center justify-center rounded-sf-pill ${control}`, {
    variants: {
        variant: {
            plain: 'bg-transparent text-sf-ink hover:bg-sf-chip active:bg-sf-chip-hover',             // «Закрыть»
            tile: 'bg-sf-tile text-sf-ink hover:bg-sf-tile-hover active:bg-sf-tile-press',           // профиль в шапке
            accent: 'bg-sf-accent text-sf-on-accent hover:bg-sf-accent-hover active:bg-sf-accent-press', // «+» в капсуле
        },
        size: { l: 'size-sf-tile', xl: 'size-sf-button' },
    },
    defaultVariants: { variant: 'plain', size: 'l' },
});

export interface IconButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement>, VariantProps<typeof iconButtonVariants> {
    /** Текста у кнопки нет — имя обязательно: «Закрыть», «Записать», «Профиль и настройки». */
    'aria-label': string;
}

export const IconButton = React.forwardRef<HTMLButtonElement, IconButtonProps>(
    ({ className, variant, size, type = 'button', ...props }, ref) => (
        <button ref={ref} type={type} className={cn(iconButtonVariants({ variant, size }), className)} {...props} />
    ),
);
IconButton.displayName = 'IconButton';

// ---------- Чипы и выбор ----------

export const chipVariants = cva(`inline-flex shrink-0 items-center justify-center gap-1.5 whitespace-nowrap rounded-sf-pill ${control}`, {
    variants: {
        variant: {
            fill: 'bg-sf-chip text-sf-ink hover:bg-sf-chip-hover active:bg-sf-chip-press',                                     // «ещё 8 ▾»
            outline: 'bg-transparent text-sf-ink shadow-[inset_0_0_0_1.5px_var(--sf-rule)] hover:bg-sf-chip active:bg-sf-chip-hover', // «Повторять»
        },
        size: {
            s: 'h-sf-chip px-3 text-sf-note',                          // в форме
            m: 'h-sf-control pl-4 pr-3.5 text-sf-body font-medium',    // выбор месяца «Ноябрь 2026 ▾»
        },
    },
    defaultVariants: { variant: 'fill', size: 's' },
});

export interface ChipProps extends React.ButtonHTMLAttributes<HTMLButtonElement>, VariantProps<typeof chipVariants> {}

/** Чип-кнопка — действие на месте: «ещё 8 ▾», «Повторять», «Ноябрь 2026 ▾». Выбор одного из ряда чипов — Choices с look="chip". */
export const Chip = React.forwardRef<HTMLButtonElement, ChipProps>(
    ({ className, variant, size, type = 'button', ...props }, ref) => (
        <button ref={ref} type={type} className={cn(chipVariants({ variant, size }), className)} {...props} />
    ),
);
Chip.displayName = 'Chip';

export interface ChoiceOption {
    value: string;
    label: React.ReactNode;
}

export interface ChoicesProps {
    /** Имя группы radio, своё у каждой группы на экране. */
    name: string;
    /** Имя группы для экранного диктора: «Расход или доход», «Категория», «Дата», «Вид». */
    label: string;
    options: readonly ChoiceOption[];
    value: string;
    onChange?: (value: string) => void;
    /** segment — переключатель на дорожке; chip — ряд чипов с переносом. */
    look?: 'segment' | 'chip';
    /** Высота: chip 36, control 40, tile 44. Без неё сегмент — 40, чип — 36. */
    size?: 'chip' | 'control' | 'tile';
    disabled?: boolean;
    className?: string;
}

const HEIGHT = { chip: 'h-sf-chip', control: 'h-sf-control', tile: 'h-sf-tile' } as const;

const choiceItem = {
    segment: `flex flex-1 cursor-pointer items-center justify-center gap-1.5 rounded-sf-pill px-2.5 text-sf-control text-sf-muted transition-colors hover:text-sf-ink peer-checked:bg-sf-switch-on peer-checked:font-semibold peer-checked:text-sf-ink peer-checked:shadow-sf-raised peer-disabled:cursor-default peer-disabled:opacity-40 ${peerFocusRing}`,
    chip: `inline-flex cursor-pointer items-center rounded-sf-pill bg-sf-chip text-sf-note text-sf-ink transition-colors hover:bg-sf-chip-hover active:bg-sf-chip-press peer-checked:bg-sf-accent peer-checked:font-medium peer-checked:text-sf-on-accent peer-checked:hover:bg-sf-accent-hover peer-disabled:cursor-default peer-disabled:opacity-40 ${peerFocusRing}`,
};

/** Один из нескольких: «Расход | Доход», вид платежа, вид графика, период, категория, дата. */
export function Choices({ name, label, options, value, onChange, look = 'segment', size, disabled, className }: ChoicesProps) {
    const h = size ?? (look === 'segment' ? 'control' : 'chip');
    return (
        <div role="radiogroup" aria-label={label}
            className={cn('font-sf', look === 'segment' ? cn('flex rounded-sf-pill bg-sf-chip p-[3px]', HEIGHT[h]) : 'flex flex-wrap gap-1.5', className)}>
            {options.map((o) => (
                <label key={o.value} className={cn('relative flex', look === 'segment' ? 'flex-1' : HEIGHT[h])}>
                    <input type="radio" name={name} value={o.value} checked={o.value === value} disabled={disabled}
                        onChange={() => onChange?.(o.value)} className="peer sr-only" />
                    {/* Чип периода (40) шире по бокам, чем чип категории (36), — как на досках. */}
                    <span className={cn(choiceItem[look], look === 'chip' && (h === 'chip' ? 'px-3' : 'px-[18px]'))}>{o.label}</span>
                </label>
            ))}
        </div>
    );
}

export interface FlagProps extends Omit<React.InputHTMLAttributes<HTMLInputElement>, 'type' | 'size'> {
    /** Вопрос вместо отметки: сумма больше, чем на картах, — «В кредит?» (решение 30). Слова с вопросом даёт вызывающий. */
    ask?: boolean;
    children: React.ReactNode;
}

/** Галочка-чип: «В кредит», «В долг». Каждая сама по себе — четыре сочетания без особой детали. */
export const Flag = React.forwardRef<HTMLInputElement, FlagProps>(({ ask = false, children, className, ...props }, ref) => (
    <label className={cn(
        'inline-flex h-sf-control shrink-0 cursor-pointer select-none items-center gap-[7px] whitespace-nowrap rounded-sf-pill pl-[9px] pr-[11px] font-sf text-sf-note text-sf-ink transition-colors has-[:disabled]:cursor-default has-[:disabled]:opacity-40',
        ask ? 'bg-sf-plate font-semibold shadow-[inset_0_0_0_2px_var(--sf-accent)]' : 'bg-sf-chip font-medium hover:bg-sf-chip-hover has-[:checked]:bg-sf-accent-soft',
        className)}>
        <input ref={ref} type="checkbox" className="peer sr-only" {...props} />
        <span aria-hidden="true" className={cn(
            'flex size-[18px] shrink-0 items-center justify-center rounded-sf-box border-2 bg-sf-page text-transparent transition-colors peer-checked:border-sf-accent peer-checked:bg-sf-accent peer-checked:text-sf-on-accent',
            ask ? 'border-sf-accent' : 'border-sf-muted', peerFocusRing)}>
            <Check size={12} strokeWidth={3} />
        </span>
        {children}
    </label>
));
Flag.displayName = 'Flag';

// ---------- Поля ----------

export interface FieldProps extends Omit<React.InputHTMLAttributes<HTMLInputElement>, 'size'> {
    /** text — строка 44 («Название»); amount — сумма 64, крупным светлым. */
    look?: 'text' | 'amount';
    /** Хвост в поле: «₽» у суммы. */
    suffix?: React.ReactNode;
    /** Ошибка ввода: обводка цветом ошибки и aria-invalid. Подсказку под полем даёт вызывающий. */
    invalid?: boolean;
}

export const Field = React.forwardRef<HTMLInputElement, FieldProps>(({ look = 'text', suffix, invalid = false, className, ...props }, ref) => (
    <label className={cn(
        'flex min-w-0 cursor-text items-center gap-1 bg-sf-plate font-sf text-sf-ink transition-shadow has-[:disabled]:cursor-default has-[:disabled]:opacity-40',
        look === 'amount' ? 'h-sf-bar rounded-sf-plate px-4 text-sf-number-m font-light tabular-nums' : 'h-sf-tile rounded-sf-field px-3.5 text-sf-body',
        invalid ? 'shadow-[inset_0_0_0_2px_var(--sf-error)]' : 'has-[:focus]:shadow-[inset_0_0_0_2px_var(--sf-accent)]',
        className)}>
        <input ref={ref} aria-invalid={invalid || undefined}
            className="h-full w-0 min-w-0 flex-1 bg-transparent outline-none placeholder:text-sf-muted" {...props} />
        {suffix && <span className={cn('shrink-0 text-sf-muted', look === 'amount' && 'text-[.6em] tracking-normal')}>{suffix}</span>}
    </label>
));
Field.displayName = 'Field';

// ---------- Плитки, плашки, кружок вида ----------

export interface TileProps {
    icon?: React.ReactNode;
    /** Значение: «48 800 ₽». */
    value: React.ReactNode;
    /** Подпись мелко под значением: «12 нояб.». */
    caption?: React.ReactNode;
    /** Есть — плитка становится кнопкой (на картах → обновить остаток); нет — только показывает. */
    onClick?: React.MouseEventHandler<HTMLButtonElement>;
    /** Имя плитки-кнопки целиком: «На картах 48 800 ₽, обновлён 12 ноября. Обновить». */
    'aria-label'?: string;
    className?: string;
}

/** Плитка поверх неба и пейзажа: «На картах», «С обычными тратами», «Самый низкий остаток». */
export function Tile({ icon, value, caption, onClick, className, 'aria-label': ariaLabel }: TileProps) {
    const body = (
        <>
            {icon && <span className="flex shrink-0 [&_svg]:size-4">{icon}</span>}
            <span className="flex min-w-0 flex-col">
                <span className="truncate text-sf-control font-medium tabular-nums">{value}</span>
                {caption && <span className="flex items-center gap-[3px] truncate text-sf-caption text-sf-muted [&_svg]:size-2.5">{caption}</span>}
            </span>
        </>
    );
    const base = cn('flex h-sf-tile min-w-0 items-center gap-1.5 rounded-sf-field bg-sf-tile pl-[9px] pr-3 text-left font-sf text-sf-ink', className);
    return onClick
        ? <button type="button" onClick={onClick} aria-label={ariaLabel}
            className={cn(base, `transition-colors hover:bg-sf-tile-hover active:bg-sf-tile-press ${focusRing}`)}>{body}</button>
        : <div className={base}>{body}</div>;
}

export const plateVariants = cva('rounded-sf-plate bg-sf-plate px-4 py-3.5 font-sf text-sf-ink', {
    variants: { interactive: { true: `w-full text-left transition-colors hover:bg-sf-plate-hover ${focusRing}`, false: '' } },
    defaultVariants: { interactive: false },
});

export type PlateProps = React.HTMLAttributes<HTMLDivElement>;

/** Плашка: «Из чего число», сторож разрыва, «Можно сдвинуть». Раскрывающаяся — кнопка с plateVariants({ interactive: true }). */
export function Plate({ className, ...props }: PlateProps) {
    return <div className={cn(plateVariants(), className)} {...props} />;
}

const KIND = {
    bron: { word: 'бронь', fg: 'text-sf-kind-bron', bg: 'bg-sf-kind-bron-bg' },
    ozh: { word: 'ожидание', fg: 'text-sf-kind-ozh', bg: 'bg-sf-kind-ozh-bg' },
    hot: { word: 'хотелка', fg: 'text-sf-kind-hot', bg: 'bg-sf-kind-hot-bg' },
} as const;

export type Kind = keyof typeof KIND;

export interface KindMarkProps {
    kind: Kind;
    /** Запись (было) — кружок залит; план (будет) — обводка. */
    fact?: boolean;
    /** Значок категории; рисуется в 20 px. */
    icon: React.ReactNode;
    /** Есть — кружок становится кнопкой смены вида (решение 32). */
    onClick?: React.MouseEventHandler<HTMLButtonElement>;
    className?: string;
}

/** Кружок вида в строке журнала: цвет — бронь, ожидание или хотелка; заливка — запись, обводка — план. */
export function KindMark({ kind, fact = false, icon, onClick, className }: KindMarkProps) {
    const k = KIND[kind];
    const base = cn('inline-flex size-sf-control shrink-0 items-center justify-center rounded-sf-pill [&_svg]:size-5', k.fg,
        fact ? k.bg : 'border-[1.5px] border-[color:color-mix(in_srgb,currentColor_60%,transparent)]', className);
    return onClick
        ? <button type="button" onClick={onClick} aria-label={`Вид: ${k.word}. Нажми — сменить`}
            className={cn(base, `transition-colors hover:border-current ${focusRing}`)}>{icon}</button>
        : <span aria-hidden="true" className={base}>{icon}</span>;
}

// ---------- Разделы ----------

export type CapsuleProps = React.HTMLAttributes<HTMLElement>;

/** Капсула разделов внизу экрана телефона: «Сегодня», «Журнал», «+», «Цели», «Деньги». */
export function Capsule({ className, ...props }: CapsuleProps) {
    return <nav className={cn('flex h-sf-bar items-center rounded-sf-pill bg-sf-capsule px-1.5 font-sf shadow-sf-capsule', className)} {...props} />;
}

/** Раздел в капсуле — ссылка или кнопка: значок над подписью. Текущий — тёмная подпись и значок акцентом. */
export const capsuleItemVariants = cva(
    `flex h-sf-button flex-1 flex-col items-center justify-center gap-[3px] rounded-sf-pill text-sf-caption transition-colors [&_svg]:size-[22px] ${focusRing}`,
    {
        variants: { active: { true: 'font-semibold text-sf-ink [&_svg]:text-sf-accent', false: 'text-sf-muted hover:text-sf-ink' } },
        defaultVariants: { active: false },
    },
);

export type NavTrackProps = React.HTMLAttributes<HTMLElement>;

/** Дорожка разделов в верхней полосе на 1440 (В1). */
export function NavTrack({ className, ...props }: NavTrackProps) {
    return <nav className={cn('flex rounded-sf-pill bg-sf-tile p-[3px] font-sf', className)} {...props} />;
}

/** Раздел на дорожке; текущий — светлый сегмент с тенью, как выбранное в переключателе. */
export const navTabVariants = cva(
    `flex h-sf-control items-center rounded-sf-pill px-[18px] text-sf-control text-sf-ink transition-colors ${focusRing}`,
    {
        variants: { active: { true: 'bg-sf-switch-on font-semibold shadow-sf-raised', false: 'hover:bg-sf-tile-hover' } },
        defaultVariants: { active: false },
    },
);

// ---------- Деньги и знак ----------

export interface MoneyProps {
    /** Сумма, уже записанная по-русски: «17 000», «13 800». Пробелы станут неразрывными. */
    value: string;
    /** l — «Свободно» на «Сегодня», 68; m — поле суммы, 36; s — шапка, 27. */
    size?: 'l' | 'm' | 's';
    className?: string;
}

const MONEY = { l: 'text-sf-number-l', m: 'text-sf-number-m', s: 'text-sf-number-s' } as const;

/** Крупная сумма: тонкое начертание, плотный набор, «₽» на 60 % кегля, не отрывается от числа. */
export function Money({ value, size = 'l', className }: MoneyProps) {
    return (
        <span className={cn('whitespace-nowrap font-sf font-light tabular-nums', MONEY[size], className)}>
            {value.replace(/ /g, ' ')}
            <span className="text-[.6em] tracking-normal">{' ₽'}</span>
        </span>
    );
}

export interface MarkProps {
    /** Сторона знака, px: 28 — полоса 1440. */
    size?: number;
    /** Подписать знак словом «Selfin». */
    withName?: boolean;
    className?: string;
}

/** Знак Selfin — буква S на плашке акцента; со словом — как в полосе 1440. */
export function Mark({ size = 28, withName = false, className }: MarkProps) {
    const sign = (
        <span aria-hidden="true" className="inline-flex shrink-0 items-center justify-center bg-sf-accent font-semibold leading-none text-sf-on-accent"
            style={{ width: size, height: size, borderRadius: Math.round(size * 0.3), fontSize: Math.round(size * 0.58) }}>S</span>
    );
    return withName
        ? <span className={cn('inline-flex items-center gap-2.5 font-sf text-[20px] font-semibold tracking-[-0.02em] text-sf-ink', className)}>{sign}Selfin</span>
        : <span role="img" aria-label="Selfin" className={cn('inline-flex font-sf', className)}>{sign}</span>;
}
