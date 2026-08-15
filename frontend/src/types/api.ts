// Типы данных, соответствующие api_contract.yaml

export type CategoryType = 'INCOME' | 'EXPENSE';
export type EventType = 'INCOME' | 'EXPENSE' | 'FUND_TRANSFER';
export type EventStatus = 'PLANNED' | 'EXECUTED' | 'CANCELLED';
export type FundStatus = 'FUNDING' | 'REACHED';
export type Priority = 'HIGH' | 'MEDIUM' | 'LOW';

export type RecurringFrequency = 'MONTHLY' | 'YEARLY';
export type ScopeEnum = 'THIS' | 'FOLLOWING' | 'ALL';

export interface RecurringConfig {
    frequency: RecurringFrequency;
    dayOfMonth: number;
    monthOfYear?: number | null;
    startDate?: string;       // YYYY-MM-DD; на edit игнорируется
    endDate?: string | null;  // null = бессрочно
}

export interface Category {
    id: string;
    name: string;
    type: CategoryType;
    priority: Priority;
    isSystem: boolean;
    forecastEnabled: boolean;
    /**
     * «Основной доход» — приход по этой категории задаёт горизонт кармашка «до дохода»
     * (ANO-35). Только для INCOME. Пока не отмечена ни одна — берётся любой доход.
     */
    primaryIncome: boolean;
}

export interface FinancialEvent {
    id: string;
    date: string | null;
    categoryId: string;
    categoryName: string;
    type: EventType;
    plannedAmount: number | null;
    factAmount: number | null;
    status: EventStatus;
    priority: Priority;
    mandatory?: boolean;
    description: string | null;
    rawInput: string | null;
    url?: string | null;
    createdAt: string;
    targetFundId?: string;
    targetFundName?: string;
    // Plan/Fact split fields
    eventKind: 'PLAN' | 'FACT';
    parentEventId: string | null;
    linkedFactsCount: number;
    linkedFactsAmount: number | null;
    parentPlanDescription: string | null;
    // Recurring fields
    recurringRuleId?: string | null;
    recurringFrequency?: RecurringFrequency | null;
    recurringDayOfMonth?: number | null;
    recurringMonthOfYear?: number | null;
}

export interface WishlistCreateDto {
    description: string;
    plannedAmount?: number | null;
    url?: string | null;
    /** YYYY-MM-DD; необязателен — хотелка «когда-нибудь». Бэк ставит статус OPEN сам. */
    date?: string | null;
}

export interface FinancialEventCreateDto {
    date: string;
    categoryId?: string;
    type: EventType;
    plannedAmount?: number;
    priority?: Priority;
    mandatory?: boolean;
    description?: string;
    rawInput?: string;
    targetFundId?: string;
    recurring?: RecurringConfig | null;
}

export interface FactCreateDto {
    date: string;
    factAmount: number;
    description?: string;
    priority?: Priority;
    /** Исходный текст суммы, если её ввели выражением («450+1230+890», ANO-33). */
    rawInput?: string;
}

export interface StandaloneFactCreateDto {
    date: string;
    categoryId: string;
    type: EventType;
    factAmount: number;
    description?: string;
    priority?: Priority;
    /** Исходный текст суммы, если её ввели выражением («450+1230+890», ANO-33). */
    rawInput?: string;
}

export interface DailyForecastPoint {
    day: number;           // day-of-month, 1-based
    cumulativeFact: number;
    projectedTotal: number;
}

export interface CategoryProgressBar {
    categoryName: string;
    currentFact: number;
    plannedLimit: number;
    percentage: number;
    // forecast fields
    projectionAmount: number | null;
    forecastEnabled: boolean;
    history: DailyForecastPoint[];
}

/**
 * Дашборд-endpoint после ANO-14: только прогресс-бары. Баланс, горизонты и алерт
 * разрыва живут в кармашке (GET /pocket) — одна истина, много представлений.
 */
export interface DashboardData {
    progressBars: CategoryProgressBar[];
}

export type PurchaseType = 'SAVINGS' | 'CREDIT';

export interface TargetFund {
    id: string;
    name: string;
    targetAmount: number | null;
    /** У копилки с accountId это остаток СЧЁТА, а не собственное поле копилки (ANO-9 §3.3). */
    currentBalance: number;
    /** Счёт, на котором лежат деньги цели; null — виртуальный конверт. */
    accountId: string | null;
    status: FundStatus;
    priority: number;
    targetDate: string | null;
    estimatedCompletionDate: string | null;
    purchaseType: PurchaseType;
    creditRate: number | null;
    creditTermMonths: number | null;
}

export interface FundsOverview {
    funds: TargetFund[];
}

// ── Pocket (ANO-12) ─────────────────────────────────────────────────────────
export type PocketScopeType = 'NEXT_INCOME' | 'SECOND_INCOME' | 'MONTHS' | 'DATE';
export type BreakdownType =
    | 'STARTING_BALANCE' | 'OVERDUE_RESERVE' | 'PLANNED_EXPENSES' | 'SAVINGS_CONTRIBUTIONS'
    | 'PLANNED_INCOME'
    | 'UNPLANNED_FORECAST' | 'TRAJECTORY_MIN' | 'BUFFER' | 'POCKET'
    // После POCKET — информационные строки: в инвариант кармашка не входят.
    | 'CREDIT_RESTORE' | 'WISHLIST_INFO';

export interface PocketResponse {
    pocket: number;
    currentBalance: number;
    buffer: number;
    /** Дата последнего якоря остатка; null — якоря ещё не было (ANO-15). */
    checkpointDate: string | null;
    horizon: { type: PocketScopeType; endDate: string; label: string; fallback: boolean };
    minPoint: { date: string; balance: number; drivenBy: string | null };
    breakdown: { type: BreakdownType; label: string; amount: number; details: string[] }[];
    trajectory: { date: string; balance: number; income: number; expense: number }[];
    wishlistCandidates: {
        id: string; description: string | null;
        plannedAmount: number | null; date: string | null; fixed: boolean;
    }[];
    /**
     * «Свободно, если вернуть карты к планке» (ANO-9 §4.2) = pocket − резерв возврата.
     * null — возвращать нечего: планок нет либо доступное уже выше них. Может быть
     * отрицательным: если на возврат не хватает, это и есть ответ.
     */
    pocketAfterCreditRestore: number | null;
    /**
     * «Свободно, если распечатать вклад» (ANO-9 §4.3) = pocket + полу-ликвид.
     * null — вкладов нет. Мелким шрифтом: это сноска, а не третий равноправный ответ.
     */
    pocketWithDeposits: number | null;
}

// ── Pocket sandbox (ANO-16) ──────────────────────────────────────────────────
export type SandboxRefType = 'EVENT' | 'FUND';
export interface SandboxRef { type: SandboxRefType; id: string; }

export interface SandboxTryOn {
    ref: SandboxRef | null;      // null = ad-hoc «а если трата»
    amount: number;
    date: string;                // обязательна, строго в будущем
    stretchMonths?: number | null;   // 0/absent = разовая; n ≥ 1 = растяжка
    creditRate?: number | null;      // только кредит (взаимоисключимо с растяжкой)
    creditTermMonths?: number | null;
}

export interface SandboxRequest {
    scope?: string;              // тот же парсер, что GET /pocket
    tryOn: SandboxTryOn[];
    exclude: SandboxRef[];       // FIXED-элементы, выключенные из baseline
}

export interface SandboxDayDelta { date: string; delta: number; }
export interface SandboxItemDelta { ref: SandboxRef | null; days: SandboxDayDelta[]; }

/** Элемент списка окна примерки с дефолтными параметрами (бэк §4). */
export interface SandboxItem {
    ref: SandboxRef;
    kind: 'WISHLIST' | 'SAVINGS' | 'CREDIT';
    name: string;
    amount: number | null;
    date: string | null;
    stretchMonthsMax: number | null;
    stretchMonthsDefault: number | null;
    creditRate: number | null;
    creditTermMonths: number | null;
    wishlistStatus: string | null;
    inBaseline: boolean;         // операционально «сидит в baseline» (§9)
}

export interface SandboxResponse {
    baseline: PocketResponse;
    fitted: PocketResponse;
    itemDeltas: SandboxItemDelta[];
    items: SandboxItem[];
}

export interface BudgetSnapshot {
    id: string;
    periodStart: string;
    periodEnd: string;
    snapshotDate: string;
}

// --- Analytics ---

export interface CashFlowDay {
    date: string;
    dailyIncome: number;
    dailyExpense: number;
    runningBalance: number;
    isFuture: boolean;
    isGap: boolean;
}

export interface CategoryPlanFact {
    categoryName: string;
    type: 'INCOME' | 'EXPENSE';
    planned: number;
    fact: number;
    delta: number;
}

export interface PlanFactReport {
    categories: CategoryPlanFact[];
    totalPlannedIncome: number;
    totalFactIncome: number;
    totalPlannedExpense: number;
    totalFactExpense: number;
}

export interface WeekBurnRate {
    weekNumber: number;
    weekStart: string;
    weekEnd: string;
    planned: number;
    fact: number;
}

export interface MandatoryBurnRate {
    totalPlanned: number;
    totalFact: number;
    byWeek: WeekBurnRate[];
}

export interface IncomeGap {
    plannedIncome: number;
    factIncome: number;
    delta: number;
}

export interface PriorityBreakdown {
    highPlanned: number;
    highFact: number;
    mediumPlanned: number;
    mediumFact: number;
    lowPlanned: number;
    lowFact: number;
    totalIncomeFact: number;
}

export interface AnalyticsReport {
    cashFlow: CashFlowDay[];
    planFact: PlanFactReport;
    mandatoryBurn: MandatoryBurnRate;
    incomeGap: IncomeGap;
    priorityBreakdown: PriorityBreakdown;
}

// --- Multi-month Analytics ---

export type MultiMonthRowType =
    | 'TOTAL_INCOME'
    | 'TOTAL_EXPENSE'
    | 'TOTAL_FUND_TRANSFER'
    | 'CATEGORY'
    | 'BALANCE';

export interface MonthValue {
    month: string;
    planned: number;
    actual: number | null;
}

export interface MultiMonthRow {
    type: MultiMonthRowType;
    label: string;
    categoryType: CategoryType | null;
    values: MonthValue[];
}

export interface MultiMonthReport {
    months: string[];
    rows: MultiMonthRow[];
}

// --- Balance Checkpoints ---

export interface BalanceCheckpoint {
    id: string;
    date: string;        // YYYY-MM-DD
    amount: number;
    /** Чей остаток зафиксирован (ANO-9): история всех счетов лежит одним списком. */
    accountId: string;
    accountName: string;
    createdAt: string;
    /** Что selfin насчитал на эту дату от предыдущего якоря; null у самого раннего (ANO-15). */
    computedBalance: number | null;
    /** amount − computedBalance: незаписанные потоки интервала; null у самого раннего. */
    drift: number | null;
}

export interface BalanceCheckpointCreateDto {
    date: string;
    amount: number;
    /** Необязательный: без него остаток садится на счёт-приёмник (ANO-9 Task 3.3). */
    accountId?: string;
}

// --- Счета (ANO-9) ---
// Рабочее слово на экране — «Счета». Продуктовое название ещё обсуждается (спека §10),
// поэтому все видимые строки собраны в accountsCopy.ts: смена слова = правка одного файла.

export type AccountKind = 'DEBIT' | 'CREDIT' | 'DEPOSIT' | 'CASH';

export interface Account {
    id: string;
    name: string;
    kind: AccountKind;
    /** Главный тумблер: решает и участие в свободных деньгах, и смысл пополнения (§5.2). */
    trackBalance: boolean;
    purposeCategoryId: string | null;
    purposeCategoryName: string | null;
    creditLimit: number | null;
    /** Планка возврата: уровень доступного, к которому возвращаешься (§4.2). */
    availableFloor: number | null;
    isDefault: boolean;
    sortOrder: number;
    /**
     * Остаток на сегодня. Для CREDIT — ДОСТУПНЫЙ остаток, не долг (§3.2).
     * null — за остатком не следят либо чекпоинтов нет: ноль читался бы как «денег нет».
     */
    balance: number | null;
    balanceDate: string | null;
    /** creditLimit − доступно; null для некредитных, без лимита или без чекпоинта. */
    debt: number | null;
    /** «Выделено за месяц» по категории-зоне — только у счетов БЕЗ слежения (§5.3). */
    allocatedThisMonth: number | null;
    /** Доступное выше планки — предложить поднять планку до этого уровня (§4.2). */
    floorSuggestion: number | null;
}

export interface AccountCreateDto {
    name: string;
    kind: AccountKind;
    trackBalance?: boolean | null;
    purposeCategoryId?: string | null;
    creditLimit?: number | null;
    availableFloor?: number | null;
    sortOrder?: number | null;
}

// --- Fund Planner ---

export interface FundPlannerMonth {
    yearMonth: string;
    plannedIncome: number;
    mandatoryExpenses: number;
    allPlannedExpenses: number;
    factExpenses: number | null;
    factIncome: number | null;
}

export interface FundPlannerData {
    months: FundPlannerMonth[];
}

// --- Capital ---

export type CapitalItemKind = 'ASSET' | 'LIABILITY';

export interface CapitalItem {
    id: string;
    kind: CapitalItemKind;
    name: string;
    description: string | null;
    createdAt: string;       // ISO instant
    currentValue: number;
    lastValuedAt: string | null;  // ISO date
    isArchived: boolean;
}

export interface CapitalItemCreateDto {
    kind: CapitalItemKind;
    name: string;
    description?: string;
    initialValue: number;
    initialValuedAt?: string;
}

export interface CapitalItemUpdateDto {
    name?: string;
    description?: string;
}

export interface CapitalRevaluation {
    id: string;
    itemId: string;
    value: number;
    valuedAt: string;
    note: string | null;
    createdAt: string;
}

export interface CapitalRevaluationCreateDto {
    value: number;
    valuedAt?: string;
    note?: string;
}

export interface CapitalRevaluationUpdateDto {
    value?: number;
    valuedAt?: string;
    note?: string;
}

export interface CapitalSummary {
    total: number;
    liquid: number;
    assetsTotal: number;
    liabilitiesTotal: number;
    items: CapitalItem[];
    deltas: {
        month: number;
        quarter: number;
        year: number;
    };
}

export interface CapitalTrajectory {
    points: Array<{
        date: string;          // ISO date
        capital: number;
        liquid: number;
        assets: number;
        liabilities: number;
    }>;
}

// ─── Strategy ─────────────────────────────────────────────────────────────────

export type StrategyPointPhase = 'PAST' | 'CURRENT' | 'FUTURE';

export interface BreakdownItemDto {
    category: string;
    amount: number;
    isRecurring: boolean;
    isPredicted: boolean;
}

export interface BreakdownDto {
    incomeItems: BreakdownItemDto[];
    expenseItems: BreakdownItemDto[];
}

export interface StrategyTimelinePointDto {
    yearMonth: string;          // YYYY-MM (Jackson serialises YearMonth as such)
    phase: StrategyPointPhase;
    balance: number;
    income: number;
    expense: number;
    nettoFlow: number;
    balanceConfirmed: number | null;
    balanceLow: number | null;
    balanceHigh: number | null;
    capital: number;
    assets: number;
    liabilities: number;
    breakdown: BreakdownDto | null;
}

export interface StrategyTimelineDto {
    firstActivityMonth: string;   // YYYY-MM
    currentMonth: string;
    horizonEnd: string;
    predictionWindowMonths: number;
    fanEnabled: boolean;
    points: StrategyTimelinePointDto[];
}

// --- Wishlist planning ---

export type WishlistStatus = 'OPEN' | 'FIXED' | 'DISMISSED';
export type WishlistKind = 'WISHLIST' | 'SAVINGS' | 'CREDIT';

export interface MonthDelta {
    monthIndex: number;
    accountDelta: number;
    capitalDelta: number;
    fundDelta?: number | null;
    liabilityDelta?: number | null;
}

export interface ConvertedTo { kind: 'EVENT' | 'FUND'; id: string; }

export interface WishlistItem {
    id: string;
    kind: WishlistKind;
    name: string;
    amount: number;
    /** YYYY-MM-DD; null — дата не задана (хотелка/копилка без срока). */
    targetDate: string | null;
    status: WishlistStatus;
    convertedTo: ConvertedTo | null;
    delta: MonthDelta[];
    categoryId?: string | null;
    monthlyContribution?: number | null;
    rate?: number | null;
    termMonths?: number | null;
    monthlyPMT?: number | null;
}

export interface WishlistThresholds {
    capitalThresholdRub: number | null;
    cashBufferMonths: number;
}

export interface WishlistConstraints {
    monthlyExpensesAvg: number;
    monthlyIncomeAvg: number;
    currentCapital: number;
    maxWishlistAmount: number;
    maxCreditAmount: number;
}

export interface WishlistSimulationDto {
    baseline: StrategyTimelineDto;   // existing type
    items: WishlistItem[];
    thresholds: WishlistThresholds;
    constraints: WishlistConstraints;
}

export interface RecomputeResponse {
    delta: MonthDelta[];
    monthlyContribution?: number | null;
    monthlyPMT?: number | null;
}

export interface ConvertResponse {
    wishlistItemId: string;
    newStatus: WishlistStatus;
    convertedTo: ConvertedTo;
    artifactKind: 'PLAN_EVENT' | 'FUND' | 'FUND_WITH_CREDIT';
    recurringRuleId?: string | null;
}
