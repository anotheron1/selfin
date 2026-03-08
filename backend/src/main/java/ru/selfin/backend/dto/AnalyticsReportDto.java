package ru.selfin.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Агрегированный ответ для страницы Аналитики.
 * Содержит четыре раздела: кассовый календарь, отчёт план-факт,
 * burn rate обязательных трат и анализ дефицита доходов.
 *
 * @param cashFlow      нарастающий баланс по дням месяца
 * @param planFact      отчёт план-факт по категориям
 * @param mandatoryBurn burn rate обязательных расходов
 * @param incomeGap     план vs факт по доходам
 */
public record AnalyticsReportDto(
        List<CashFlowDay> cashFlow,
        PlanFactReport planFact,
        MandatoryBurnRate mandatoryBurn,
        IncomeGap incomeGap) {

    /**
     * Один день кассового календаря.
     *
     * @param date           дата
     * @param dailyIncome    сумма доходов за день (факт или план для будущих)
     * @param dailyExpense   сумма расходов за день (факт или план для будущих)
     * @param runningBalance нарастающий баланс на конец дня
     * @param isFuture       {@code true} если день ещё не наступил (используется план)
     * @param isGap          {@code true} если нарастающий баланс отрицательный (кассовый разрыв)
     */
    public record CashFlowDay(
            LocalDate date,
            BigDecimal dailyIncome,
            BigDecimal dailyExpense,
            BigDecimal runningBalance,
            boolean isFuture,
            boolean isGap) {
    }

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
     * Burn rate обязательных расходов за одну неделю месяца.
     *
     * @param weekNumber номер недели в месяце (1–5)
     * @param weekStart  начало недели (не раньше первого дня месяца)
     * @param weekEnd    конец недели (не позже последнего дня месяца)
     * @param planned    суммарный план обязательных расходов за неделю
     * @param fact       суммарный факт обязательных расходов за неделю
     */
    public record WeekBurnRate(
            int weekNumber,
            LocalDate weekStart,
            LocalDate weekEnd,
            BigDecimal planned,
            BigDecimal fact) {
    }

    /**
     * Сводный burn rate обязательных расходов за месяц.
     *
     * @param totalPlanned суммарный план обязательных расходов
     * @param totalFact    суммарный факт обязательных расходов
     * @param byWeek       разбивка по неделям месяца
     */
    public record MandatoryBurnRate(
            BigDecimal totalPlanned,
            BigDecimal totalFact,
            List<WeekBurnRate> byWeek) {
    }

    /**
     * Анализ дефицита цели дохода за месяц.
     *
     * @param plannedIncome  суммарный плановый доход
     * @param factIncome     суммарный фактический доход
     * @param delta          разница {@code factIncome - plannedIncome}; отрицательная = недобор
     */
    public record IncomeGap(
            BigDecimal plannedIncome,
            BigDecimal factIncome,
            BigDecimal delta) {
    }
}
