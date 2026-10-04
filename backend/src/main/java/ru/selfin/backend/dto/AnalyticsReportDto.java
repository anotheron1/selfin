package ru.selfin.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Агрегированный ответ для страницы Аналитики: отчёт план-факт и разбивка по характеру.
 *
 * <p>Р3 (ANO-47): кассовый календарь с «мостиком» и два соседних раздела — burn rate обязательных
 * трат и дефицит дохода — ушли: их не показывал ни один экран, а «мостик» считал стартовый
 * остаток месяца по своему правилу.
 *
 * @param planFact          отчёт план-факт по категориям
 * @param priorityBreakdown разбивка месяца по характеру строк
 */
public record AnalyticsReportDto(
        PlanFactReport planFact,
        PriorityBreakdown priorityBreakdown) {

    /**
     * Строка отчёта план-факт по одной категории.
     *
     * @param categoryName название категории
     * @param type         тип: {@code INCOME} или {@code EXPENSE}
     * @param planned      суммарный план за период
     * @param fact         суммарный факт за период
     * @param delta        разница {@code fact - planned}; отрицательная = перерасход (EXPENSE) / недобор (INCOME)
     */
    public record CategoryPlanFact(
            String categoryName,
            String type,
            BigDecimal planned,
            BigDecimal fact,
            BigDecimal delta) {
    }

    /**
     * Сводный отчёт план-факт по всем категориям.
     *
     * @param categories           строки по каждой категории
     * @param totalPlannedIncome   суммарный плановый доход
     * @param totalFactIncome      суммарный фактический доход
     * @param totalPlannedExpense  суммарный плановый расход
     * @param totalFactExpense     суммарный фактический расход
     */
    public record PlanFactReport(
            List<CategoryPlanFact> categories,
            BigDecimal totalPlannedIncome,
            BigDecimal totalFactIncome,
            BigDecimal totalPlannedExpense,
            BigDecimal totalFactExpense) {
    }

    /**
     * Разбивка бюджета месяца по приоритетам категорий.
     *
     * @param highPlanned    суммарный план расходов HIGH-приоритета
     * @param highFact       суммарный факт расходов HIGH-приоритета
     * @param mediumPlanned  суммарный план расходов MEDIUM-приоритета
     * @param mediumFact     суммарный факт расходов MEDIUM-приоритета
     * @param lowPlanned     суммарный план расходов LOW-приоритета
     * @param lowFact        суммарный факт расходов LOW-приоритета
     * @param totalIncomeFact суммарный фактический доход за месяц
     */
    public record PriorityBreakdown(
            BigDecimal highPlanned,
            BigDecimal highFact,
            BigDecimal mediumPlanned,
            BigDecimal mediumFact,
            BigDecimal lowPlanned,
            BigDecimal lowFact,
            BigDecimal totalIncomeFact) {
    }
}
