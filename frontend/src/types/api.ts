// Типы данных, соответствующие api_contract.yaml

export type CategoryType = 'INCOME' | 'EXPENSE';
export type EventType = 'INCOME' | 'EXPENSE' | 'FUND_TRANSFER';
export type EventStatus = 'PLANNED' | 'EXECUTED' | 'CANCELLED';
export type FundStatus = 'FUNDING' | 'REACHED';
export type Priority = 'HIGH' | 'MEDIUM' | 'LOW';

export interface Category {
    id: string;
    name: string;
    type: CategoryType;
    priority: Priority;
}

export interface FinancialEvent {
    id: string;
    date: string;
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
    factAmount?: number;
    priority?: Priority;
    mandatory?: boolean;
    description?: string;
    rawInput?: string;
    targetFundId?: string;
}

export interface CashGapAlert {
    gapDate: string;
    gapAmount: number;
}

export interface CategoryProgressBar {
    categoryName: string;
    currentFact: number;
    plannedLimit: number;
    percentage: number;
}

export interface DashboardData {
    currentBalance: number;
    endOfMonthForecast: number;
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
    pocketBalance: number;
    funds: TargetFund[];
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

export interface AnalyticsReport {
    cashFlow: CashFlowDay[];
    planFact: PlanFactReport;
    mandatoryBurn: MandatoryBurnRate;
    incomeGap: IncomeGap;
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
}

export interface FundPlannerData {
    months: FundPlannerMonth[];
}
