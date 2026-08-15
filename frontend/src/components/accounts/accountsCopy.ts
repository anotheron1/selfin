import type { AccountKind } from '../../types/api';

/**
 * ВСЕ видимые строки экрана счетов — в одном файле.
 *
 * <p>Причина не косметическая. Продуктовое название ещё не выбрано (спека §10: «Счета» точно,
 * но звучит бухгалтерски и работает против идеи, которую продукт продаёт; «Кошельки»,
 * «Корзины», «Карты» ближе к тому, как автор думает про это вслух). Слово решает, читается
 * идея или нет, поэтому смена названия обязана быть правкой ОДНОГО файла, а не поиском по
 * десятку компонентов. Доменные имена (`accounts`, `Account`, `/api/v1/accounts`) при этом
 * не трогаются: технический термин и продуктовое слово могут не совпадать.
 */
export const copy = {
    sectionTitle: '🏦 СЧЕТА',
    sectionHint: 'Счёт — место, где лежат деньги. В форме траты его нет и не будет: '
        + 'операции падают на счёт-приёмник сами.',

    empty: 'Счетов пока нет',
    addButton: 'Добавить счёт',
    moreSettings: 'Ещё настройки',
    lessSettings: 'Свернуть',

    nameLabel: 'Название',
    namePlaceholder: 'Например, основная карта',
    kindLabel: 'Природа',
    trackBalanceLabel: 'Слежу за остатком',
    trackBalanceHint: 'Выключи, если остаток не ведёшь: тогда перевод на этот счёт — '
        + 'обычная трата своей категории, а не перенос денег.',
    purposeLabel: 'Зона ответственности',
    purposeNone: 'Не задана',
    purposeHint: 'Категория, которую обслуживает счёт. Нужна, чтобы показать выделенное за месяц.',
    creditLimitLabel: 'Кредитный лимит',
    floorLabel: 'Планка возврата',
    floorHint: 'Уровень доступного, к которому возвращаешься. Пока не задана — '
        + 'второе число кармашка не показывается.',

    defaultBadge: 'приёмник',
    makeDefault: 'Сделать приёмником',
    defaultHint: 'Сюда падают операции без адреса',

    balanceUnknown: 'Остаток не введён',
    /**
     * «якорь от», а не «на»: у счёта-приёмника показанное число — это остаток НА СЕГОДНЯ
     * (введённый остаток плюс записанные после него факты), а дата — дата последнего
     * введённого остатка. Подпись «на 30 июл.» рядом с сегодняшним числом читалась бы как
     * «столько было 30 июля» и прямо противоречила бы истории ниже, где за 30 июля стоит
     * другая сумма.
     */
    balanceAt: (date: string) => `якорь от ${date}`,
    allocated: 'Выделено за месяц',
    allocatedBy: (category: string) => `по категории «${category}»`,
    allocatedNoCategory: 'Задай зону ответственности, чтобы увидеть выделенное за месяц',
    available: 'Доступно',
    availableOf: (limit: string) => `из ${limit}`,
    debt: 'Долг',
    floor: 'Планка',
    raiseFloor: (to: string) => `Поднять планку до ${to}`,

    deleteConfirm: 'Удалить счёт? Введённые остатки сохранятся, счёт уйдёт из всех сумм.',
    deleteDefaultError: 'Нельзя удалить счёт-приёмник: сначала назначь другой.',

    created: 'Счёт добавлен',
    updated: 'Счёт обновлён',
    deleted: 'Счёт удалён',
    defaultChanged: 'Счёт-приёмник изменён',
    floorRaised: 'Планка поднята',

    // Перенос кредиток из Капитала (спека §7.2, план Task 4.3 — исполняется в чанке 3)
    capitalHintTitle: 'Проверь Капитал',
    capitalHintBody: 'Если это доступный остаток, а не долг, заархивируй запись — '
        + 'счёт теперь считает обязательство сам. Иначе долг посчитается дважды.',
    capitalHintArchive: 'Заархивировать',
    capitalHintSkip: 'Оставить как есть',
    capitalHintArchived: 'Запись в Капитале заархивирована',
} as const;

export const kindLabels: Record<AccountKind, string> = {
    DEBIT: 'Дебетовая карта',
    CREDIT: 'Кредитка',
    DEPOSIT: 'Вклад',
    CASH: 'Наличные',
};

/** Короткая подпись под названием счёта — природа плюс, если есть, зона ответственности. */
export const kindLine = (kind: AccountKind, purpose: string | null): string =>
    purpose ? `${kindLabels[kind]} · ${purpose}` : kindLabels[kind];

export const fmtAmount = (n: number): string =>
    `${n.toLocaleString('ru-RU', { minimumFractionDigits: 0, maximumFractionDigits: 2 })} ₽`;

export const fmtDate = (iso: string): string =>
    new Date(iso + 'T00:00:00').toLocaleDateString('ru-RU', { day: 'numeric', month: 'short' });

/** Может ли счёт быть приёмником безадресных операций (спека §3.1, зеркалит бэкенд). */
export const canBeDefault = (kind: AccountKind, trackBalance: boolean): boolean =>
    trackBalance && (kind === 'DEBIT' || kind === 'CASH');
