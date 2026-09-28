import { get, post, put, patch, del } from './client';
import type { AttemptKeys } from '../lib/attemptKey';
import type {
    EventWishlistParams,
    FundWishlistParams,
    Account,
    AccountCreateDto,
    AnalyticsReport,
    ForecastReadiness,
    BalanceCheckpoint,
    BalanceCheckpointCreateDto,
    BudgetSnapshot,
    CapitalItem,
    CapitalItemCreateDto,
    CapitalItemKind,
    CapitalItemUpdateDto,
    CapitalRevaluation,
    CapitalRevaluationCreateDto,
    CapitalRevaluationUpdateDto,
    CapitalSummary,
    CapitalTrajectory,
    Category,
    DashboardData,
    FactCreateDto,
    FinancialEvent,
    FinancialEventCreateDto,
    FundPlannerData,
    FundsOverview,
    MultiMonthReport,
    PocketResponse,
    SandboxRequest,
    SandboxResponse,
    PurchaseType,
    ScopeEnum,
    StandaloneFactCreateDto,
    StrategyTimelineDto,
    TargetFund,
    WishlistCreateDto,
    WishlistSimulationDto,
    WishlistThresholds,
    PocketSettings,
    WishlistKind,
    WishlistStatus,
    RecomputeResponse,
    ConvertResponse,
} from '../types/api';

// --- Categories ---

/** Загружает список всех активных категорий. */
export const fetchCategories = () => get<Category[]>('/categories');

/** Создаёт новую категорию доходов или расходов. */
export const createCategory = (body: Omit<Category, 'id' | 'isSystem'>) => post<Category>('/categories', body);

/** Полностью обновляет категорию (имя, тип, обязательность). */
export const updateCategory = (id: string, body: Omit<Category, 'id' | 'isSystem'>) => put<Category>(`/categories/${id}`, body);

/** Удаляет категорию (soft delete). */
export const deleteCategory = (id: string) => del(`/categories/${id}`);

/**
 * Циклически меняет приоритет категории (HIGH → MEDIUM → LOW → HIGH).
 * Использует PATCH без тела — достаточно идентификатора в URL.
 */
export const cycleCategoryPriority = (id: string) => patch<Category>(`/categories/${id}/priority`);

// --- Events ---

/**
 * Загружает финансовые события за период.
 *
 * @param startDate начало периода в формате `YYYY-MM-DD`
 * @param endDate   конец периода в формате `YYYY-MM-DD`
 */
export const fetchEvents = (startDate: string, endDate: string) =>
    get<FinancialEvent[]>(`/events?startDate=${startDate}&endDate=${endDate}`);

/**
 * Запись денег с ключом попытки (ANO-192): повтор после сбоя уходит с тем же ключом, и сервер
 * вернёт уже записанное; после успеха ключ забыт — следующая такая же запись новая.
 * Правило ключа — `lib/attemptKey.ts`; память попыток — у экрана, который пишет (ревью #104).
 */
async function withAttempt<T>(attempts: AttemptKeys, target: string, data: unknown, send: (key: string) => Promise<T>): Promise<T> {
    const key = attempts.keyFor(target, data);
    const result = await send(key);
    attempts.done(target, key);
    return result;
}

/**
 * Создаёт плановое событие и, если деньги уже ушли, факт к нему — одной попыткой (ANO-192).
 *
 * План записан, а ответ на факт потерян — повтор отдаёт план с тем же ключом, сервер
 * возвращает тот же план, и второго плана нет. Поэтому ключ плана забывается только после факта.
 */
export async function createPlanWithFact(attempts: AttemptKeys, dto: FinancialEventCreateDto, fact?: FactCreateDto): Promise<FinancialEvent> {
    const planKey = attempts.keyFor('plan', dto);
    const plan = await post<FinancialEvent>('/events', dto, { 'Idempotency-Key': planKey });
    if (fact) await createLinkedFact(attempts, plan.id, fact);
    attempts.done('plan', planKey);
    return plan;
}

/**
 * Полностью обновляет финансовое событие (все поля).
 * Для ввода только фактической суммы предпочтительнее использовать `patchEventFact`.
 */
export const updateEvent = (id: string, dto: FinancialEventCreateDto, scope: ScopeEnum = 'THIS') =>
    put<FinancialEvent>(`/events/${id}?scope=${scope}`, dto);

/**
 * Частичное обновление события: только фактическая сумма и комментарий.
 * Использует `PATCH /events/{id}/fact` — облегчённый запрос из UI "Бюджет".
 *
 * @param id         идентификатор события
 * @param factAmount фактическая сумма; `undefined` — снять отметку об исполнении
 * @param description необязательный комментарий
 * @param rawInput исходный текст суммы, если её ввели выражением (ANO-33)
 */
export const patchEventFact = (
    id: string, factAmount: number | undefined, description?: string, rawInput?: string,
) => patch<FinancialEvent>(`/events/${id}/fact`, { factAmount, description, rawInput });

/** Циклически меняет приоритет события (HIGH → MEDIUM → LOW → HIGH). */
export const cycleEventPriority = (id: string) => patch<FinancialEvent>(`/events/${id}/priority`);

/** Загружает нереализованные хотелки: LOW-priority PLANNED события с датой в прошлом. */
export const fetchWishlist = () => get<FinancialEvent[]>('/events/wishlist');

/** Создаёт новую хотелку вручную. */
export const createWishlistItem = (dto: WishlistCreateDto): Promise<FinancialEvent> =>
    post<FinancialEvent>('/events/wishlist', dto);

/** Удаляет событие (soft delete — физически запись остаётся в БД). */
export const deleteEvent = (id: string, scope: ScopeEnum = 'THIS') => del(`/events/${id}?scope=${scope}`);


/** Создаёт фактическое исполнение (FACT) для планового события (PLAN). Повтор — тот же ключ (ANO-192). */
export const createLinkedFact = (attempts: AttemptKeys, planId: string, dto: FactCreateDto) =>
    withAttempt(attempts, `fact:${planId}`, dto, key =>
        post<FinancialEvent>(`/events/${planId}/facts`, dto, { 'Idempotency-Key': key }));

/** Создаёт внеплановый факт без родительского PLAN. Повтор — тот же ключ (ANO-192). */
export const createStandaloneFact = (attempts: AttemptKeys, dto: StandaloneFactCreateDto) =>
    withAttempt(attempts, 'standaloneFact', dto, key =>
        post<FinancialEvent>('/events/facts', dto, { 'Idempotency-Key': key }));

// --- Analytics ---

/**
 * Загружает данные дашборда: текущий баланс, прогноз, кассовый разрыв, прогресс-бары.
 *
 * @param date дата расчёта в формате `YYYY-MM-DD`; по умолчанию — сегодня
 */
export const fetchDashboard = (date?: string) =>
    get<DashboardData>(`/analytics/dashboard${date ? `?date=${date}` : ''}`);

/**
 * Готовность прогноза (ANO-80): сколько месяцев наблюдения набралось и когда он появится.
 * Нужна экрану категорий, чтобы «галочка стоит, а прогноза нет» не читалось как поломка.
 */
export const fetchForecastReadiness = () =>
    get<ForecastReadiness>('/analytics/forecast-readiness');

/**
 * Загружает расширенный аналитический отчёт за месяц:
 * кассовый календарь, план-факт, burn rate обязательных расходов, дефицит доходов.
 *
 * @param date опорная дата в формате `YYYY-MM-DD`; по умолчанию — сегодня
 */
export const fetchAnalyticsReport = (date?: string) =>
    get<AnalyticsReport>(`/analytics/report${date ? `?date=${date}` : ''}`);

/** Загружает многомесячный отчёт план-факт по категориям. */
export const fetchMultiMonthReport = (startDate: string, endDate: string) =>
    get<MultiMonthReport>(`/analytics/multi-month?startDate=${startDate}&endDate=${endDate}`);

// --- Pocket (ANO-12) ---

/** Кармашек: единый ответ «сколько свободно и почему» на выбранном скоупе. */
export const fetchPocket = (scope?: string) =>
    get<PocketResponse>(`/pocket${scope ? `?scope=${encodeURIComponent(scope)}` : ''}`);

/** Примерка «что если» (ANO-16): движок с подменённым входом, ничего не пишет. */
export const postPocketSandbox = (body: SandboxRequest) =>
    post<SandboxResponse>('/pocket/sandbox', body);

/** НЗ кармашка (ANO-92): сумма, которую кармашек не тратит. */
export const fetchPocketSettings = () => get<PocketSettings>('/settings/pocket');

/** Сохранить НЗ; 0 — НЗ нет (пустого значения сервер не принимает). */
export const updatePocketSettings = (body: PocketSettings) =>
    put<PocketSettings>('/settings/pocket', body);

// --- Funds ---

/** Загружает обзор фондов: список копилок с прогрессом (кармашек — см. fetchPocket). */
export const fetchFunds = () => get<FundsOverview>('/funds');

/** Создаёт новый целевой фонд (копилку). */
export const createFund = (body: { name: string; targetAmount?: number; priority?: number; targetDate?: string; purchaseType?: PurchaseType; creditRate?: number; creditTermMonths?: number; accountId?: string | null }) =>
    post<TargetFund>('/funds', body);

/** Обновляет целевой фонд (название, целевую сумму, срок достижения). */
export const updateFund = (id: string, body: { name: string; targetAmount?: number; priority?: number; targetDate?: string; purchaseType?: PurchaseType; creditRate?: number; creditTermMonths?: number; accountId?: string | null }) =>
    put<TargetFund>(`/funds/${id}`, body);

/** Загружает данные планировщика копилок: доходы и расходы по месяцам. */
export const fetchPlannerData = (): Promise<FundPlannerData> =>
    get('/funds/planner');

/** Удаляет целевой фонд (soft delete). */
export const deleteFund = (id: string) => del(`/funds/${id}`);

/**
 * Пополняет целевой фонд на указанную сумму.
 * Повтор после сбоя уходит с тем же `Idempotency-Key` — второго зачисления нет (ANO-192).
 * Подтверждение — другие данные, у него свой ключ: первый запрос без подтверждения ничего не записал.
 *
 * @param fundId  идентификатор фонда
 * @param amount  сумма пополнения; знаковая — отрицательная забирает обратно (ANO-87)
 * @param confirm человек увидел предупреждение и настаивает (ANO-87 §4.2). До ANO-157 флаг
 *                не передавался никогда, и обещанное подтверждение было недостижимо
 */
/**
 * @param scope горизонт карточки кармашка (ANO-88): сервер переспрашивает по тому же числу,
 *              что человек видит; не задан — «до дохода»
 * @param date  день перевода, YYYY-MM-DD (ANO-169): быстрый ввод записывает перевод, который
 *              уже сделан; не задан — сегодня, как у кнопки «Пополнить»
 */
export const transferToFund = (attempts: AttemptKeys, fundId: string, amount: number, confirm?: boolean, scope?: string, date?: string) => {
    const transfer = {
        amount,
        ...(scope === undefined ? {} : { scope }),
        ...(date === undefined ? {} : { date }),
    };
    // Подтверждение — разрешение, а не другая запись: ключ попытки берётся без него. Иначе
    // повтор после потерянного ответа на подтверждённый перевод начинался бы запросом без
    // подтверждения с новым ключом — и деньги ушли бы второй раз.
    return withAttempt(attempts, `transfer:${fundId}`, transfer, key =>
        post<TargetFund>(`/funds/${fundId}/transfer`,
            { ...transfer, ...(confirm === undefined ? {} : { confirm }) },
            { 'Idempotency-Key': key }));
};

// --- Snapshots ---

/** Загружает список снимков бюджета за последние 12 месяцев. */
export const fetchSnapshots = () => get<BudgetSnapshot[]>('/snapshots');

/**
 * Создаёт снимок бюджета для указанного месяца.
 * Идемпотентен: повторный вызов вернёт существующий снимок без дублирования.
 *
 * @param date любая дата внутри нужного месяца в формате `YYYY-MM-DD`; по умолчанию — сегодня
 */
export const createSnapshot = (date?: string) =>
    post<BudgetSnapshot>(`/snapshots${date ? `?date=${date}` : ''}`, {});

// --- Balance Checkpoints ---

/** Загружает историю чекпоинтов баланса, от свежих к старым. */
export const fetchCheckpoints = () => get<BalanceCheckpoint[]>('/balance-checkpoints');

/** Фиксирует реальный остаток на счёте на указанную дату. */
export const createCheckpoint = (dto: BalanceCheckpointCreateDto) =>
    post<BalanceCheckpoint>('/balance-checkpoints', dto);

/** Исправляет дату или сумму существующего чекпоинта. */
export const updateCheckpoint = (id: string, dto: BalanceCheckpointCreateDto) =>
    put<BalanceCheckpoint>(`/balance-checkpoints/${id}`, dto);

/** Удаляет чекпоинт. */
export const deleteCheckpoint = (id: string) => del(`/balance-checkpoints/${id}`);

// --- Счета (ANO-9) ---

export const fetchAccounts = () => get<Account[]>('/accounts');

export const createAccount = (dto: AccountCreateDto) => post<Account>('/accounts', dto);

export const updateAccount = (id: string, dto: AccountCreateDto) =>
    put<Account>(`/accounts/${id}`, dto);

export const deleteAccount = (id: string) => del(`/accounts/${id}`);

/** Отдельная ручка: смена приёмника меняет два счёта сразу, в одной транзакции. */
export const makeAccountDefault = (id: string) => patch<Account>(`/accounts/${id}/default`);

// --- Capital ---

export const fetchCapitalItems = (kind?: CapitalItemKind, includeArchived = false) => {
    const params = new URLSearchParams();
    if (kind) params.set('kind', kind);
    if (includeArchived) params.set('includeArchived', 'true');
    const qs = params.toString();
    return get<CapitalItem[]>(`/capital/items${qs ? '?' + qs : ''}`);
};

export const fetchCapitalItem = (id: string) =>
    get<CapitalItem>(`/capital/items/${id}`);

export const createCapitalItem = (dto: CapitalItemCreateDto) =>
    post<CapitalItem>('/capital/items', dto);

export const updateCapitalItem = (id: string, dto: CapitalItemUpdateDto) =>
    put<CapitalItem>(`/capital/items/${id}`, dto);

export const deleteCapitalItem = (id: string) =>
    del(`/capital/items/${id}`);

export const fetchCapitalHistory = (itemId: string) =>
    get<CapitalRevaluation[]>(`/capital/items/${itemId}/revaluations`);

export const addCapitalRevaluation = (itemId: string, dto: CapitalRevaluationCreateDto) =>
    post<CapitalRevaluation>(`/capital/items/${itemId}/revaluations`, dto);

export const updateCapitalRevaluation = (id: string, dto: CapitalRevaluationUpdateDto) =>
    put<CapitalRevaluation>(`/capital/revaluations/${id}`, dto);

export const deleteCapitalRevaluation = (id: string) =>
    del(`/capital/revaluations/${id}`);

export const fetchCapitalSummary = () =>
    get<CapitalSummary>('/capital/summary');

export const fetchCapitalTrajectory = (from?: string, to?: string) => {
    const params = new URLSearchParams();
    if (from) params.set('from', from);
    if (to) params.set('to', to);
    const qs = params.toString();
    return get<CapitalTrajectory>(`/capital/trajectory${qs ? '?' + qs : ''}`);
};

// --- Strategy ---

export const fetchStrategyTimeline = (params?: {
    horizonMonths?: number;
    withBreakdown?: boolean;
}) => {
    const qs = new URLSearchParams();
    if (params?.horizonMonths !== undefined) qs.set('horizonMonths', String(params.horizonMonths));
    if (params?.withBreakdown !== undefined) qs.set('withBreakdown', String(params.withBreakdown));
    const query = qs.toString();
    return get<StrategyTimelineDto>(`/strategy/timeline${query ? '?' + query : ''}`);
};

// --- Wishlist ---

export const fetchWishlistSimulation = (horizonMonths = 36) =>
    get<WishlistSimulationDto>(`/wishlist/simulation?horizonMonths=${horizonMonths}`);

export const recomputeWishlistItem = (body: {
    kind: WishlistKind; amount: number; targetDate: string; rate?: number; termMonths?: number;
}) => post<RecomputeResponse>('/wishlist/simulation/recompute', body);

export const convertWishlistItem = (itemId: string, body: {
    sourceKind: WishlistKind; target: 'PLAN_EVENT' | 'FUND' | 'FUND_WITH_CREDIT';
    /** ANO-104: только для FUND_WITH_CREDIT — у остальных целей сервер отвечает 400. */
    createRecurringPayments?: boolean;
    /** ANO-16 §8: дата цели создаваемой копилки (фиксация растянутой примерки). */
    fundTargetDate?: string;
    /** ANO-138: срок создаваемого плана; без него бэкенд отвечает 400, а не молчит. */
    planDate?: string;
}) => post<ConvertResponse>(`/wishlist/items/${itemId}/convert`, body);

/**
 * ANO-34 §1: «Зафиксировать» из окна примерки — переносит подкрученные параметры
 * в реальный план и ставит FIXED одной транзакцией.
 *
 * Две вещи считает СЕРВЕР, и повторять их здесь нельзя: `amount` у копилки — это
 * остаток (сервер добавит уже накопленное), а дату цели при `stretchMonths` ≥ 1
 * он выводит из ползунка сам.
 */
export const fixSandboxItem = (itemId: string, body: {
    sourceKind: WishlistKind;
    amount: number;
    date?: string | null;
    stretchMonths?: number;
    creditRate?: number | null;
    creditTermMonths?: number | null;
}) => post<ConvertResponse>(`/wishlist/items/${itemId}/fix`, body);

export const setEventWishlistStatus = (id: string, status: WishlistStatus) =>
    patch<void>(`/events/${id}/wishlist-status`, { status });

export const setFundWishlistStatus = (id: string, status: WishlistStatus) =>
    patch<void>(`/funds/${id}/wishlist-status`, { status });

/**
 * Параметры примерки — отдельной записью, а не полной перезаписью (ANO-162): сервер пишет только
 * их. Через `PUT` «Что с капиталом» отвязывал копилку от счёта и стирал исходный текст хотелки.
 */
export const setEventWishlistParams = (id: string, body: EventWishlistParams) =>
    patch<void>(`/events/${id}/wishlist-params`, body);

export const setFundWishlistParams = (id: string, body: FundWishlistParams) =>
    patch<void>(`/funds/${id}/wishlist-params`, body);

export const updateWishlistSettings = (body: WishlistThresholds) =>
    put<WishlistThresholds>('/settings/wishlist', body);
