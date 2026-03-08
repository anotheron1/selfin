import { get, post, put, patch, del, generateUUID } from './client';
import type {
    AnalyticsReport,
    BalanceCheckpoint,
    BalanceCheckpointCreateDto,
    BudgetSnapshot,
    Category,
    DashboardData,
    FinancialEvent,
    FinancialEventCreateDto,
    FundsOverview,
    TargetFund,
} from '../types/api';

// --- Categories ---

/** Загружает список всех активных категорий. */
export const fetchCategories = () => get<Category[]>('/categories');

/** Создаёт новую категорию доходов или расходов. */
export const createCategory = (body: Omit<Category, 'id'>) => post<Category>('/categories', body);

/**
 * Инвертирует флаг `mandatory` у категории.
 * Использует PATCH без тела — достаточно идентификатора в URL.
 */
export const toggleMandatory = (id: string) => patch<Category>(`/categories/${id}/mandatory`);

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
 * Создаёт финансовое событие.
 * Автоматически генерирует `Idempotency-Key` для защиты от дублирования при ретраях.
 */
export const createEvent = (dto: FinancialEventCreateDto) =>
    post<FinancialEvent>('/events', dto, { 'Idempotency-Key': generateUUID() });

/**
 * Полностью обновляет финансовое событие (все поля).
 * Для ввода только фактической суммы предпочтительнее использовать `patchEventFact`.
 */
export const updateEvent = (id: string, dto: FinancialEventCreateDto) =>
    put<FinancialEvent>(`/events/${id}`, dto);

/**
 * Частичное обновление события: только фактическая сумма и комментарий.
 * Использует `PATCH /events/{id}/fact` — облегчённый запрос из UI "Бюджет".
 *
 * @param id         идентификатор события
 * @param factAmount фактическая сумма; `undefined` — снять отметку об исполнении
 * @param description необязательный комментарий
 */
export const patchEventFact = (id: string, factAmount: number | undefined, description?: string) =>
    patch<FinancialEvent>(`/events/${id}/fact`, { factAmount, description });

/** Удаляет событие (soft delete — физически запись остаётся в БД). */
export const deleteEvent = (id: string) => del(`/events/${id}`);

// --- Analytics ---

/**
 * Загружает данные дашборда: текущий баланс, прогноз, кассовый разрыв, прогресс-бары.
 *
 * @param date дата расчёта в формате `YYYY-MM-DD`; по умолчанию — сегодня
 */
export const fetchDashboard = (date?: string) =>
    get<DashboardData>(`/analytics/dashboard${date ? `?date=${date}` : ''}`);

/**
 * Загружает расширенный аналитический отчёт за месяц:
 * кассовый календарь, план-факт, burn rate обязательных расходов, дефицит доходов.
 *
 * @param date опорная дата в формате `YYYY-MM-DD`; по умолчанию — сегодня
 */
export const fetchAnalyticsReport = (date?: string) =>
    get<AnalyticsReport>(`/analytics/report${date ? `?date=${date}` : ''}`);

// --- Funds ---

/** Загружает обзор фондов: баланс кармашка и список копилок с прогрессом. */
export const fetchFunds = () => get<FundsOverview>('/funds');

/** Создаёт новый целевой фонд (копилку). */
export const createFund = (body: { name: string; targetAmount?: number; priority?: number }) =>
    post<TargetFund>('/funds', body);

/**
 * Пополняет целевой фонд на указанную сумму.
 * Автоматически генерирует `Idempotency-Key` для защиты от двойного зачисления.
 *
 * @param fundId идентификатор фонда
 * @param amount положительная сумма пополнения
 */
export const transferToFund = (fundId: string, amount: number) =>
    post<TargetFund>(`/funds/${fundId}/transfer`, { amount }, { 'Idempotency-Key': generateUUID() });

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
