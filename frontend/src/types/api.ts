// Типы данных, соответствующие api_contract.yaml

export type CategoryType = 'INCOME' | 'EXPENSE';
export type EventType = 'INCOME' | 'EXPENSE' | 'FUND_TRANSFER';
export type EventStatus = 'PLANNED' | 'EXECUTED' | 'CANCELLED';
export type FundStatus = 'FUNDING' | 'REACHED';

export interface Category {
    id: string;
    name: string;
    type: CategoryType;
    mandatory: boolean;
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
    mandatory: boolean;
    description: string | null;
    rawInput: string | null;
    createdAt: string;
}

export interface FinancialEventCreateDto {
    date: string;
    categoryId: string;
    type: EventType;
    plannedAmount?: number;
    factAmount?: number;
    mandatory?: boolean;
    description?: string;
    rawInput?: string;
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

export interface TargetFund {
    id: string;
    name: string;
    targetAmount: number | null;
    currentBalance: number;
    status: FundStatus;
    priority: number;
    estimatedCompletionDate: string | null;
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
