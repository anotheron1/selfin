import type { AnalyticsReport, Priority } from '../types/api';
import { PRIORITY_DOT_CONFIG, PRIORITY_ORDER } from './priority';

/** Строка «Структуры месяца»: сколько потрачено по характеру и сколько на него запланировано. */
export interface MonthStructureRow {
    priority: Priority;
    name: string;
    fact: number;
    plan: number;
}

/**
 * «Структура месяца» — только числа (ANO-165, вариант А владельца 28.09). Раньше блок ставил
 * оценки: «сэкономил» по неоплаченным броням, «% бюджета» от дохода, которого ещё нет (делитель
 * подменялся на 1 — «500000%»), «перерасход», счётчик «N из M выполнена». Канон, «Граница по
 * запрету 1»: сумма по характеру за период в разборе — да; оценка и счётчик соблюдения — нет.
 */
export function monthStructureRows(b: AnalyticsReport['priorityBreakdown']): MonthStructureRow[] {
    const byPriority: Record<Priority, { fact: number; plan: number }> = {
        HIGH: { fact: b.highFact, plan: b.highPlanned },
        MEDIUM: { fact: b.mediumFact, plan: b.mediumPlanned },
        LOW: { fact: b.lowFact, plan: b.lowPlanned },
    };
    return PRIORITY_ORDER.map((priority) => ({
        priority,
        name: PRIORITY_DOT_CONFIG[priority].plural,
        ...byPriority[priority],
    }));
}
