import type { Priority } from '../types/api';

export const PRIORITY_DOT_CONFIG: Record<Priority, { color: string; title: string }> = {
    HIGH:   { color: '#f87171', title: 'обязательно' },
    MEDIUM: { color: '#facc15', title: 'по плану' },
    LOW:    { color: '#60a5fa', title: 'хотелка' },
};

export const PRIORITY_ORDER: Priority[] = ['HIGH', 'MEDIUM', 'LOW'];
