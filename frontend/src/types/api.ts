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
}

export interface StandaloneFactCreateDto {
    date: string;
    categoryId: string;
    type: EventType;
    factAmount: number;
    description?: string;
    priority?: Priority;
}

export interface CashGapAlert {
    gapDate: string;
    gapAmount: number;
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

export interface DashboardData {
    currentBalance: number;
    /** Прогноз баланса на конец месяца; используется если нет зп-событий. */
    endOfMonthForecast: number;
    /** Дата ближайшей запланированной зп; null если в горизонте 70 дней нет дохода. */
    nextSalaryDate: string | null;
    /** Баланс в последний день перед nextSalaryDate («низшая точка» текущего периода). */
    balanceBeforeNextSalary: number | null;
    /** Баланс в конце дня nextSalaryDate, включая само поступление зп. */
    balanceAfterNextSalary: number | null;
    /** Дата второй ближайшей запланированной зп; null если только одна зп в горизонте. */
    secondSalaryDate: string | null;
    /** Баланс в последний день перед secondSalaryDate («низшая точка» второго периода). */
    balanceBeforeSecondSalary: number | null;
    cashGapAlert: CashGapAlert | null;
    progressBars: CategoryProgressBar[];
}

export type PurchaseType = 'SAVINGS' | 'CREDIT';

export interface TargetFund {
    id: string;
    name: string;
    targetAmount: number | null;
    currentBalance: number;
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
export type PocketScopeType = 'NEXT_INCOME' | 'MONTHS' | 'DATE';
export type BreakdownType =
    | 'STARTING_BALANCE' | 'OVERDUE_RESERVE' | 'PLANNED_EXPENSES' | 'PLANNED_INCOME'
    | 'UNPLANNED_FORECAST' | 'TRAJECTORY_MIN' | 'BUFFER' | 'POCKET' | 'WISHLIST_INFO';

export interface PocketResponse {
    pocket: number;
    currentBalance: number;
    buffer: number;
    horizon: { type: PocketScopeType; endDate: string; label: string; fallback: boolean };
    minPoint: { date: string; balance: number };
    breakdown: { type: BreakdownType; label: string; amount: number; details: string[] }[];
    trajectory: { date: string; balance: number }[];
    wishlistCandidates: {
        id: string; description: string | null;
        plannedAmount: number | null; date: string | null; fixed: boolean;
    }[];
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
    createdAt: string;
}

export interface BalanceCheckpointCreateDto {
    date: string;
    amount: number;
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
    targetDate: string;       // YYYY-MM-DD
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
