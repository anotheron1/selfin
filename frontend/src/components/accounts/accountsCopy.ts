import type { Account, AccountCreateDto, AccountKind } from '../../types/api';
import { DELETE_FAILED, writeFailure } from '../../lib/writeFailure';

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
        + 'строка «если погасить карты до планки» на дашборде не показывается.',

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

    // Отказы словами экрана (ANO-245): повтор в них не поможет, поэтому не «попробуйте ещё раз».
    deleteHoldsGoal: 'Не удалилось: на счёте лежит копилка — сначала перенеси её на другой счёт',
    floorAboveLimit: 'Планка — уровень доступного, а доступного больше лимита не бывает: планка не выше лимита.',
    defaultKindOnly: 'Приёмник — только дебетовая карта или наличные. Чтобы сменить природу, '
        + 'сначала сделай приёмником другой счёт.',
    kindAcrossCredit: 'Не записалось: у кредитки остаток — это доступное, у других счетов — деньги на счёте. '
        + 'Записанные остатки поменяли бы смысл, поэтому заведи новый счёт.',

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

/**
 * Что форма счёта знает сама и говорит до отправки (ANO-245): сервер отверг бы это 400, а повтор упёрся бы
 * в тот же отказ (`AccountService.validate`).
 *
 * @param initial счёт до правки; у нового — null
 * @returns слова экрана или null — можно отправлять
 */
export function accountFormProblem(dto: AccountCreateDto, initial: Pick<Account, 'isDefault'> | null): string | null {
    if (dto.creditLimit != null && dto.availableFloor != null && dto.availableFloor > dto.creditLimit) {
        return copy.floorAboveLimit;
    }
    if (initial?.isDefault && !canBeDefault(dto.kind, dto.trackBalance ?? true)) return copy.defaultKindOnly;
    return null;
}

/**
 * Отказ сохранения счёта. Смену природы через кредитку при записанных остатках сервер отвергает 400
 * (`rejectCreditBoundaryChange`), а есть ли остатки, экран наверняка не знает: у счёта без слежения их не видно.
 * Остальные 400 этой формы отсеяны до отправки (`accountFormProblem`) — значит, 400 на таком переходе — он.
 */
export function accountWriteFailure(err: unknown, dto: AccountCreateDto, initial: Pick<Account, 'kind'> | null): string {
    const crossed = initial != null && (initial.kind === 'CREDIT') !== (dto.kind === 'CREDIT');
    return crossed && statusOf(err) === 400 ? copy.kindAcrossCredit : writeFailure(err);
}

/** Отказ удаления: 409 у удаления счёта, кроме приёмника (его экран не шлёт), один — на нём лежит копилка. */
export function accountDeleteFailure(err: unknown): string {
    return statusOf(err) === 409 ? copy.deleteHoldsGoal : DELETE_FAILED;
}

const statusOf = (err: unknown): unknown => (err as { status?: unknown } | null | undefined)?.status;
