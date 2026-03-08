package ru.selfin.backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.selfin.backend.dto.AnalyticsReportDto;
import ru.selfin.backend.dto.AnalyticsReportDto.*;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.repository.FinancialEventRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.WeekFields;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Сервис аналитики: строит агрегированный отчёт по событиям текущего месяца.
 * <p>
 * Отчёт включает четыре раздела:
 * <ol>
 *   <li>Кассовый календарь — нарастающий баланс по дням месяца</li>
 *   <li>Отчёт план-факт — по категориям с группировкой INCOME / EXPENSE</li>
 *   <li>Burn rate обязательных трат — с разбивкой по неделям</li>
 *   <li>Дефицит цели дохода — план vs факт по всем доходным событиям</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class AnalyticsService {

    private final FinancialEventRepository eventRepository;

    /**
     * Формирует полный аналитический отчёт за месяц, в котором находится {@code asOfDate}.
     * Прошлые дни используют фактические суммы (при наличии), будущие — плановые.
     *
     * @param asOfDate опорная дата расчёта (обычно сегодня)
     * @return агрегированный {@link AnalyticsReportDto}
     */
    @Transactional(readOnly = true)
    public AnalyticsReportDto getReport(LocalDate asOfDate) {
        LocalDate monthStart = asOfDate.withDayOfMonth(1);
        LocalDate monthEnd = asOfDate.withDayOfMonth(asOfDate.lengthOfMonth());

        List<FinancialEvent> events = eventRepository.findAllByDeletedFalseAndDateBetween(monthStart, monthEnd);

        return new AnalyticsReportDto(
                buildCashFlow(events, monthStart, monthEnd, asOfDate),
                buildPlanFact(events),
                buildMandatoryBurnRate(events, monthStart, monthEnd),
                buildIncomeGap(events));
    }

    /**
     * Строит кассовый календарь — список дней месяца с нарастающим балансом.
     * <p>
     * Для каждого дня вычисляется {@code dailyIncome} и {@code dailyExpense}:
     * прошлые дни используют {@code factAmount} (при отсутствии — {@code plannedAmount}),
     * будущие — только {@code plannedAmount}.
     *
     * @param events     события месяца (без удалённых)
     * @param monthStart первый день месяца
     * @param monthEnd   последний день месяца
     * @param today      сегодняшняя дата (граница прошлое / будущее)
     * @return упорядоченный список {@link CashFlowDay} на каждый день месяца
     */
    private List<CashFlowDay> buildCashFlow(List<FinancialEvent> events,
                                             LocalDate monthStart, LocalDate monthEnd,
                                             LocalDate today) {
        // Группируем события по дате
        Map<LocalDate, List<FinancialEvent>> byDate = events.stream()
                .collect(Collectors.groupingBy(FinancialEvent::getDate));

        List<CashFlowDay> days = new ArrayList<>();
        BigDecimal running = BigDecimal.ZERO;

        for (LocalDate d = monthStart; !d.isAfter(monthEnd); d = d.plusDays(1)) {
            boolean isFuture = d.isAfter(today);
            List<FinancialEvent> dayEvents = byDate.getOrDefault(d, List.of());

            BigDecimal income = BigDecimal.ZERO;
            BigDecimal expense = BigDecimal.ZERO;

            for (FinancialEvent e : dayEvents) {
                BigDecimal amount = effectiveAmount(e, isFuture);
                if (amount == null) continue;
                if (e.getType() == EventType.INCOME) {
                    income = income.add(amount);
                } else {
                    expense = expense.add(amount);
                }
            }

            running = running.add(income).subtract(expense);
            days.add(new CashFlowDay(d, income, expense, running, isFuture, running.compareTo(BigDecimal.ZERO) < 0));
        }

        return days;
    }

    /**
     * Строит отчёт план-факт по категориям.
     * <p>
     * Агрегирует суммы по {@code categoryName + type},
     * delta = fact - planned (отрицательная = перерасход/недобор).
     *
     * @param events события месяца
     * @return {@link PlanFactReport} с итогами по INCOME и EXPENSE
     */
    private PlanFactReport buildPlanFact(List<FinancialEvent> events) {
        // Ключ: categoryName + "|" + type
        record Key(String name, EventType type) {}

        Map<Key, BigDecimal[]> acc = new LinkedHashMap<>();
        for (FinancialEvent e : events) {
            Key key = new Key(e.getCategory().getName(), e.getType());
            BigDecimal[] sums = acc.computeIfAbsent(key, k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            sums[0] = sums[0].add(orZero(e.getPlannedAmount()));
            sums[1] = sums[1].add(orZero(e.getFactAmount()));
        }

        List<CategoryPlanFact> categories = new ArrayList<>();
        BigDecimal totalPlannedIncome = BigDecimal.ZERO;
        BigDecimal totalFactIncome = BigDecimal.ZERO;
        BigDecimal totalPlannedExpense = BigDecimal.ZERO;
        BigDecimal totalFactExpense = BigDecimal.ZERO;

        for (Map.Entry<Key, BigDecimal[]> entry : acc.entrySet()) {
            BigDecimal planned = entry.getValue()[0];
            BigDecimal fact = entry.getValue()[1];
            BigDecimal delta = fact.subtract(planned);
            categories.add(new CategoryPlanFact(entry.getKey().name(), entry.getKey().type().name(), planned, fact, delta));

            if (entry.getKey().type() == EventType.INCOME) {
                totalPlannedIncome = totalPlannedIncome.add(planned);
                totalFactIncome = totalFactIncome.add(fact);
            } else {
                totalPlannedExpense = totalPlannedExpense.add(planned);
                totalFactExpense = totalFactExpense.add(fact);
            }
        }

        return new PlanFactReport(categories, totalPlannedIncome, totalFactIncome, totalPlannedExpense, totalFactExpense);
    }

    /**
     * Строит burn rate обязательных расходов с разбивкой по неделям текущего месяца.
     * <p>
     * Учитываются только события с {@code mandatory = true} и {@code type = EXPENSE}.
     * Месяц делится на недели по ISO-правилу (понедельник — начало недели),
     * но границы обрезаются по первому и последнему дню месяца.
     *
     * @param events     события месяца
     * @param monthStart первый день месяца
     * @param monthEnd   последний день месяца
     * @return {@link MandatoryBurnRate} с общими итогами и разбивкой по неделям
     */
    private MandatoryBurnRate buildMandatoryBurnRate(List<FinancialEvent> events,
                                                      LocalDate monthStart, LocalDate monthEnd) {
        List<FinancialEvent> mandatory = events.stream()
                .filter(e -> e.isMandatory() && e.getType() == EventType.EXPENSE)
                .toList();

        // Разбиваем месяц на недели (пн–вс), обрезая по границам месяца
        List<WeekBurnRate> byWeek = new ArrayList<>();
        WeekFields wf = WeekFields.ISO;
        LocalDate weekStart = monthStart;
        int weekNum = 1;

        while (!weekStart.isAfter(monthEnd)) {
            // Конец недели = воскресенье текущей недели, но не позже конца месяца
            LocalDate weekEnd = weekStart.with(wf.dayOfWeek(), 7);
            if (weekEnd.isAfter(monthEnd)) weekEnd = monthEnd;

            final LocalDate ws = weekStart;
            final LocalDate we = weekEnd;

            BigDecimal planned = BigDecimal.ZERO;
            BigDecimal fact = BigDecimal.ZERO;
            for (FinancialEvent e : mandatory) {
                if (!e.getDate().isBefore(ws) && !e.getDate().isAfter(we)) {
                    planned = planned.add(orZero(e.getPlannedAmount()));
                    fact = fact.add(orZero(e.getFactAmount()));
                }
            }

            byWeek.add(new WeekBurnRate(weekNum, ws, we, planned, fact));
            weekNum++;
            weekStart = weekEnd.plusDays(1);
        }

        BigDecimal totalPlanned = mandatory.stream().map(e -> orZero(e.getPlannedAmount())).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalFact = mandatory.stream().map(e -> orZero(e.getFactAmount())).reduce(BigDecimal.ZERO, BigDecimal::add);

        return new MandatoryBurnRate(totalPlanned, totalFact, byWeek);
    }

    /**
     * Строит анализ дефицита доходов.
     * <p>
     * Суммирует все плановые и фактические доходы за месяц.
     * delta = factIncome - plannedIncome; отрицательная дельта = недобор.
     *
     * @param events события месяца
     * @return {@link IncomeGap}
     */
    private IncomeGap buildIncomeGap(List<FinancialEvent> events) {
        BigDecimal planned = BigDecimal.ZERO;
        BigDecimal fact = BigDecimal.ZERO;

        for (FinancialEvent e : events) {
            if (e.getType() == EventType.INCOME) {
                planned = planned.add(orZero(e.getPlannedAmount()));
                fact = fact.add(orZero(e.getFactAmount()));
            }
        }

        return new IncomeGap(planned, fact, fact.subtract(planned));
    }

    /**
     * Возвращает эффективную сумму события: для прошлых дней — факт (или план при отсутствии),
     * для будущих — только план.
     *
     * @param event    финансовое событие
     * @param isFuture {@code true} если дата события в будущем
     * @return сумма или {@code null} если ни один из вариантов недоступен
     */
    private BigDecimal effectiveAmount(FinancialEvent event, boolean isFuture) {
        if (isFuture) return event.getPlannedAmount();
        return event.getFactAmount() != null ? event.getFactAmount() : event.getPlannedAmount();
    }

    /** Возвращает {@code BigDecimal.ZERO} если значение {@code null}. */
    private BigDecimal orZero(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
