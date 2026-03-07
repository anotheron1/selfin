import { get, post, put, patch, del, generateUUID } from './client';
import type {
    BudgetSnapshot,
    Category,
    DashboardData,
    FinancialEvent,
    FinancialEventCreateDto,
    FundsOverview,
    TargetFund,
} from '../types/api';

// --- Categories ---
export const fetchCategories = () => get<Category[]>('/categories');
export const createCategory = (body: Omit<Category, 'id'>) => post<Category>('/categories', body);
export const toggleMandatory = (id: string) => patch<Category>(`/categories/${id}/mandatory`);

// --- Events ---
export const fetchEvents = (startDate: string, endDate: string) =>
    get<FinancialEvent[]>(`/events?startDate=${startDate}&endDate=${endDate}`);

export const createEvent = (dto: FinancialEventCreateDto) =>
    post<FinancialEvent>('/events', dto, { 'Idempotency-Key': generateUUID() });

export const updateEvent = (id: string, dto: FinancialEventCreateDto) =>
    put<FinancialEvent>(`/events/${id}`, dto);

export const deleteEvent = (id: string) => del(`/events/${id}`);

// --- Analytics ---
export const fetchDashboard = (date?: string) =>
    get<DashboardData>(`/analytics/dashboard${date ? `?date=${date}` : ''}`);

// --- Funds ---
export const fetchFunds = () => get<FundsOverview>('/funds');
export const createFund = (body: { name: string; targetAmount?: number; priority?: number }) =>
    post<TargetFund>('/funds', body);
export const transferToFund = (fundId: string, amount: number) =>
    post<TargetFund>(`/funds/${fundId}/transfer`, { amount }, { 'Idempotency-Key': generateUUID() });

// --- Snapshots ---
export const fetchSnapshots = () => get<BudgetSnapshot[]>('/snapshots');
export const createSnapshot = (date?: string) =>
    post<BudgetSnapshot>(`/snapshots${date ? `?date=${date}` : ''}`, {});
