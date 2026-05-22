package ru.selfin.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.selfin.backend.dto.capital.CapitalTrajectoryDto;
import ru.selfin.backend.dto.strategy.BreakdownDto;
import ru.selfin.backend.dto.strategy.BreakdownItemDto;
import ru.selfin.backend.dto.strategy.CategoryMonthStats;
import ru.selfin.backend.dto.strategy.StrategyPointPhase;
import ru.selfin.backend.dto.strategy.StrategyTimelineDto;
import ru.selfin.backend.dto.strategy.StrategyTimelinePointDto;
import ru.selfin.backend.model.Category;
import ru.selfin.backend.model.EventKind;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.repository.BalanceCheckpointRepository;
import ru.selfin.backend.repository.CategoryRepository;
import ru.selfin.backend.repository.FinancialEventRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Сервис стратегической временной шкалы.
 *
 * <p>Точка входа — {@link #getTimeline(int, boolean)}: собирает прошлые, текущую и будущие точки,
 * обогащает их данными капитала (через {@link CapitalService}) и, при необходимости,
 * разбивкой по категориям (breakdown). Статистика forecast-категорий вычисляется однократно
 * через {@link #computeStatsMap()} и передаётся вниз по цепочке вызовов.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class StrategyTimelineService {

    private final FinancialEventRepository eventRepository;
    private final BalanceCheckpointRepository checkpointRepository;
    private final CategoryRepository categoryRepository;
    private final PredictionService predictionService;
    // ВАЖНО: НЕ добавлять CapitalRevaluationRepository здесь.
    // Доступ к "earliest revaluation" идёт через capitalService.findEarliestRevaluationDate() —
    // CapitalService уже инжектит revRepo и предоставляет публичный метод.
    private final CapitalService capitalService;

    private static final int PREDICTION_WINDOW_MONTHS = 6;
    private static final int MIN_HISTORY_FOR_FAN = 3;
    private static final int MIN_CATEGORIES_FOR_FAN = 3;

    /**
     * Точка входа для GET /api/v1/strategy/timeline.
     *
     * @param horizonMonths  количество будущих месяцев для прогноза
     * @param withBreakdown  включать ли разбивку по категориям в каждую точку
     */
    public StrategyTimelineDto getTimeline(int horizonMonths, boolean withBreakdown) {
        YearMonth firstMonth = firstActivityMonth();
        YearMonth currentMonth = YearMonth.now();
        YearMonth horizonEnd = currentMonth.plusMonths(horizonMonths);

        // Загружаем статистику по forecast-категориям ОДИН РАЗ; ключ — Category, чтобы имя было доступно
        Map<Category, CategoryMonthStats> statsMap = computeStatsMap();

        boolean fanEnabled = statsMap.values().stream()
                .filter(s -> s.monthsOfHistory() >= MIN_HISTORY_FOR_FAN)
                .count() >= MIN_CATEGORIES_FOR_FAN;

        List<StrategyTimelinePointDto> past = buildPastPoints(firstMonth, currentMonth);

        StrategyTimelinePointDto current = buildCurrentPoint(currentMonth);

        List<StrategyTimelinePointDto> future = buildFuturePoints(currentMonth, horizonMonths, statsMap);

        List<StrategyTimelinePointDto> all = new ArrayList<>();
        all.addAll(past);
        all.add(current);
        all.addAll(future);

        all = enrichWithCapital(all);
        if (withBreakdown) {
            all = enrichWithBreakdown(all, statsMap);
        }

        return new StrategyTimelineDto(
                firstMonth,
                currentMonth,
                horizonEnd,
                PREDICTION_WINDOW_MONTHS,
                fanEnabled,
                all
        );
    }

    /**
     * Загружает все forecast-enabled категории и вычисляет статистику для каждой ОДИН РАЗ.
     *
     * <p>Ключ — {@link Category} (а не UUID), чтобы имя категории было доступно в breakdown-методах
     * без дополнительных обращений к репозиторию. LinkedHashMap сохраняет порядок из репозитория.
     */
    private Map<Category, CategoryMonthStats> computeStatsMap() {
        List<Category> forecastCats = categoryRepository.findAllByForecastEnabledTrueAndDeletedFalse();
        Map<Category, CategoryMonthStats> result = new LinkedHashMap<>();
        for (Category cat : forecastCats) {
            result.put(cat, predictionService.getStatsForCategory(cat, PREDICTION_WINDOW_MONTHS));
        }
        return result;
    }

    StrategyTimelinePointDto buildCurrentPoint(YearMonth current) {
        LocalDate today = LocalDate.now();
        LocalDate monthStart = current.atDay(1);

        // Факты с начала месяца до сегодня
        List<FinancialEvent> factsToDate = eventRepository.findFactsByDateRange(monthStart, today).stream()
                .filter(e -> !e.isDeleted())
                .filter(e -> e.getEventKind() == EventKind.FACT)
                .toList();

        BigDecimal incomeToDate = sumByType(factsToDate, EventType.INCOME);
        BigDecimal expenseToDate = sumByType(factsToDate, EventType.EXPENSE);
        BigDecimal nettoFlow = incomeToDate.subtract(expenseToDate);

        // balance для CURRENT — live liquidAt(today), не end-of-month проекция
        BigDecimal balance = capitalService.liquidAt(today);

        return new StrategyTimelinePointDto(
                current,
                StrategyPointPhase.CURRENT,
                balance,
                incomeToDate,
                expenseToDate,
                nettoFlow,
                balance,   // balanceConfirmed = текущий live баланс
                balance,   // balanceLow = balance (нет прогноза накопленного на текущий месяц)
                balance,   // balanceHigh = balance
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                null
        );
    }

    List<StrategyTimelinePointDto> buildPastPoints(YearMonth from, YearMonth currentMonth) {
        List<StrategyTimelinePointDto> points = new ArrayList<>();
        if (from.isAfter(currentMonth.minusMonths(1))) {
            return points; // нет прошлых месяцев
        }

        LocalDate windowStart = from.atDay(1);
        LocalDate windowEnd = currentMonth.minusMonths(1).atEndOfMonth();

        // Один запрос фактов на весь диапазон, потом группируем
        Map<YearMonth, List<FinancialEvent>> factsByMonth = eventRepository
                .findFactsByDateRange(windowStart, windowEnd).stream()
                .filter(e -> !e.isDeleted())
                .filter(e -> e.getEventKind() == EventKind.FACT)
                .collect(Collectors.groupingBy(e -> YearMonth.from(e.getDate())));

        for (YearMonth ym = from; ym.isBefore(currentMonth); ym = ym.plusMonths(1)) {
            List<FinancialEvent> facts = factsByMonth.getOrDefault(ym, List.of());

            BigDecimal income = sumByType(facts, EventType.INCOME);
            BigDecimal expense = sumByType(facts, EventType.EXPENSE);
            BigDecimal nettoFlow = income.subtract(expense);

            BigDecimal balance = capitalService.liquidAt(ym.atEndOfMonth());

            points.add(new StrategyTimelinePointDto(
                    ym,
                    StrategyPointPhase.PAST,
                    balance,
                    income,
                    expense,
                    nettoFlow,
                    null, null, null,                       // balanceConfirmed/Low/High не для PAST
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,  // капитал — заполнится в enrichWithCapital
                    null                                    // breakdown — заполнится в enrichWithBreakdown
            ));
        }
        return points;
    }

    private BigDecimal sumByType(List<FinancialEvent> facts, EventType type) {
        return facts.stream()
                .filter(e -> e.getType() == type)
                .map(e -> e.getFactAmount() != null ? e.getFactAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * @param current        текущий месяц
     * @param horizonMonths  сколько будущих месяцев построить (включая current+1 … current+horizonMonths)
     * @param statsMap       предвычисленная статистика forecast-категорий (category → stats)
     */
    List<StrategyTimelinePointDto> buildFuturePoints(YearMonth current, int horizonMonths,
                                                     Map<Category, CategoryMonthStats> statsMap) {
        List<StrategyTimelinePointDto> points = new ArrayList<>();
        if (horizonMonths <= 0) return points;

        // Шаг 1: вычисляем агрегаты из предвычисленного statsMap
        List<CategoryMonthStats> eligibleStats = statsMap.values().stream()
                .filter(s -> s.monthsOfHistory() >= MIN_HISTORY_FOR_FAN)
                .toList();

        BigDecimal sumMedian = eligibleStats.stream()
                .map(CategoryMonthStats::median)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        double sumHalfIqr = Math.sqrt(eligibleStats.stream()
                .mapToDouble(s -> {
                    double halfIqr = s.p75().subtract(s.p25()).doubleValue() / 2.0;
                    return halfIqr * halfIqr;
                })
                .sum());

        boolean fanEnabled = eligibleStats.size() >= MIN_CATEGORIES_FOR_FAN;

        // Шаг 2: планы (recurring + manual) на будущее — один запрос на весь горизонт
        LocalDate futureStart = current.plusMonths(1).atDay(1);
        LocalDate futureEnd = current.plusMonths(horizonMonths).atEndOfMonth();
        Map<YearMonth, List<FinancialEvent>> plannedByMonth = eventRepository
                .findPlannedEventsByDateRange(futureStart, futureEnd).stream()
                .filter(e -> !e.isDeleted())
                .filter(e -> e.getEventKind() == EventKind.PLAN)
                .collect(Collectors.groupingBy(e -> YearMonth.from(e.getDate())));

        // Шаг 3: построение точек
        BigDecimal balanceConfirmed = capitalService.liquidAt(LocalDate.now());

        for (int k = 1; k <= horizonMonths; k++) {
            YearMonth ym = current.plusMonths(k);
            List<FinancialEvent> planned = plannedByMonth.getOrDefault(ym, List.of());

            BigDecimal confirmedIncome = sumPlannedByType(planned, EventType.INCOME);
            BigDecimal confirmedExpense = sumPlannedByType(planned, EventType.EXPENSE);
            balanceConfirmed = balanceConfirmed.add(confirmedIncome).subtract(confirmedExpense);

            BigDecimal balanceMedian = balanceConfirmed.subtract(sumMedian.multiply(BigDecimal.valueOf(k)));

            BigDecimal balanceLow, balanceHigh;
            if (fanEnabled) {
                double rawHalfIqr = sumHalfIqr * Math.sqrt(k);
                double capCeiling = 2.0 * Math.abs(balanceMedian.doubleValue());
                double accumulatedHalfIqr = Math.min(rawHalfIqr, capCeiling);
                BigDecimal halfIqrBd = BigDecimal.valueOf(accumulatedHalfIqr)
                        .setScale(2, RoundingMode.HALF_UP);
                balanceLow = balanceMedian.subtract(halfIqrBd);
                balanceHigh = balanceMedian.add(halfIqrBd);
            } else {
                balanceLow = balanceMedian;
                balanceHigh = balanceMedian;
            }

            points.add(new StrategyTimelinePointDto(
                    ym,
                    StrategyPointPhase.FUTURE,
                    balanceMedian,
                    confirmedIncome,
                    confirmedExpense.add(sumMedian),    // expense = confirmed + prediction
                    confirmedIncome.subtract(confirmedExpense.add(sumMedian)),  // nettoFlow
                    balanceConfirmed,
                    balanceLow,
                    balanceHigh,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    null
            ));
        }
        return points;
    }

    private BigDecimal sumPlannedByType(List<FinancialEvent> events, EventType type) {
        return events.stream()
                .filter(e -> e.getType() == type)
                .map(e -> e.getPlannedAmount() != null ? e.getPlannedAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Обогащает точки timeline данными капитала (capital, assets, liabilities).
     *
     * <p><b>Контракт:</b> {@code points} ДОЛЖЕН быть полным списком (past + current + future)
     * — диапазон вызова `trajectory(first, last)` определяется крайними точками. При передаче
     * частичного списка trajectory будет вычислена на меньшем интервале, и future-точки могут
     * получить некорректные значения капитала.
     */
    List<StrategyTimelinePointDto> enrichWithCapital(List<StrategyTimelinePointDto> points) {
        if (points.isEmpty()) return points;

        YearMonth first = points.get(0).yearMonth();
        YearMonth last = points.get(points.size() - 1).yearMonth();

        CapitalTrajectoryDto trajectory = capitalService.trajectory(first.atDay(1), last.atEndOfMonth());

        // Маппим точки траектории по YearMonth
        Map<YearMonth, CapitalTrajectoryDto.Point> byMonth = trajectory.points().stream()
                .collect(Collectors.toMap(
                        p -> YearMonth.from(p.date()),
                        p -> p,
                        (a, b) -> b   // если коллизия — берём более поздний
                ));

        // Last known — для пропусков и для будущих точек после последней revaluation
        BigDecimal lastCapital = BigDecimal.ZERO;
        BigDecimal lastAssets = BigDecimal.ZERO;
        BigDecimal lastLiabilities = BigDecimal.ZERO;

        List<StrategyTimelinePointDto> enriched = new ArrayList<>(points.size());
        for (StrategyTimelinePointDto p : points) {
            CapitalTrajectoryDto.Point cap = byMonth.get(p.yearMonth());
            if (cap != null) {
                lastCapital = cap.capital();
                lastAssets = cap.assets();
                lastLiabilities = cap.liabilities();
            }
            enriched.add(new StrategyTimelinePointDto(
                    p.yearMonth(), p.phase(),
                    p.balance(), p.income(), p.expense(), p.nettoFlow(),
                    p.balanceConfirmed(), p.balanceLow(), p.balanceHigh(),
                    lastCapital, lastAssets, lastLiabilities,
                    p.breakdown()
            ));
        }
        return enriched;
    }

    /**
     * Обогащает точки timeline разбивкой по категориям.
     *
     * <p>Факты и планы загружаются ОДНИМ запросом на весь диапазон точек, затем группируются
     * по YearMonth и раздаются в соответствующие breakdown-методы. Статистика forecast-категорий
     * берётся из предвычисленного {@code statsMap} — ни репозитории, ни PredictionService
     * не вызываются повторно.
     *
     * @param points   список точек timeline (может быть любым подмножеством)
     * @param statsMap предвычисленная статистика forecast-категорий (category → stats)
     */
    List<StrategyTimelinePointDto> enrichWithBreakdown(List<StrategyTimelinePointDto> points,
                                                       Map<Category, CategoryMonthStats> statsMap) {
        if (points.isEmpty()) return points;

        // Диапазон для запросов — от первой до последней точки
        YearMonth minYm = points.stream().map(StrategyTimelinePointDto::yearMonth)
                .min(YearMonth::compareTo).orElseThrow();
        YearMonth maxYm = points.stream().map(StrategyTimelinePointDto::yearMonth)
                .max(YearMonth::compareTo).orElseThrow();

        // Один запрос фактов на весь диапазон
        Map<YearMonth, List<FinancialEvent>> factsByMonth = eventRepository
                .findFactsByDateRange(minYm.atDay(1), maxYm.atEndOfMonth()).stream()
                .filter(e -> !e.isDeleted())
                .filter(e -> e.getEventKind() == EventKind.FACT)
                .collect(Collectors.groupingBy(e -> YearMonth.from(e.getDate())));

        // Один запрос планов на весь диапазон
        Map<YearMonth, List<FinancialEvent>> plansByMonth = eventRepository
                .findPlannedEventsByDateRange(minYm.atDay(1), maxYm.atEndOfMonth()).stream()
                .filter(e -> !e.isDeleted())
                .filter(e -> e.getEventKind() == EventKind.PLAN)
                .collect(Collectors.groupingBy(e -> YearMonth.from(e.getDate())));

        List<StrategyTimelinePointDto> enriched = new ArrayList<>(points.size());
        for (StrategyTimelinePointDto p : points) {
            BreakdownDto br = switch (p.phase()) {
                case PAST -> breakdownForPast(p.yearMonth(), factsByMonth);
                case CURRENT -> breakdownForCurrent(p.yearMonth(), factsByMonth, statsMap);
                case FUTURE -> breakdownForFuture(p.yearMonth(), plansByMonth, statsMap);
            };
            enriched.add(withBreakdown(p, br));
        }
        return enriched;
    }

    private BreakdownDto breakdownForPast(YearMonth ym, Map<YearMonth, List<FinancialEvent>> factsByMonth) {
        List<FinancialEvent> facts = factsByMonth.getOrDefault(ym, List.of());
        return new BreakdownDto(
                aggregateByCategory(facts, EventType.INCOME, FinancialEvent::getFactAmount, false, false),
                aggregateByCategory(facts, EventType.EXPENSE, FinancialEvent::getFactAmount, false, false)
        );
    }

    private BreakdownDto breakdownForFuture(YearMonth ym, Map<YearMonth, List<FinancialEvent>> plansByMonth,
                                            Map<Category, CategoryMonthStats> statsMap) {
        List<FinancialEvent> planned = plansByMonth.getOrDefault(ym, List.of());

        List<BreakdownItemDto> incomeItems = aggregatePlannedByCategory(planned, EventType.INCOME);
        List<BreakdownItemDto> expenseItems = aggregatePlannedByCategory(planned, EventType.EXPENSE);

        // Добавляем predicted items для forecast-категорий из предвычисленного statsMap
        for (Map.Entry<Category, CategoryMonthStats> entry : statsMap.entrySet()) {
            if (entry.getValue().median().compareTo(BigDecimal.ZERO) > 0) {
                expenseItems.add(new BreakdownItemDto(entry.getKey().getName(), entry.getValue().median(), false, true));
            }
        }
        return new BreakdownDto(incomeItems, expenseItems);
    }

    private BreakdownDto breakdownForCurrent(YearMonth ym, Map<YearMonth, List<FinancialEvent>> factsByMonth,
                                             Map<Category, CategoryMonthStats> statsMap) {
        // Текущий месяц: факты до сегодня + прогноз остатка
        BreakdownDto past = breakdownForPast(ym, factsByMonth);

        List<BreakdownItemDto> expense = new ArrayList<>(past.expenseItems());

        // Pro-rated прогноз
        LocalDate today = LocalDate.now();
        int daysInMonth = ym.lengthOfMonth();
        int daysRemaining = Math.max(0, daysInMonth - today.getDayOfMonth());
        double fraction = (double) daysRemaining / daysInMonth;

        if (fraction > 0) {
            for (Map.Entry<Category, CategoryMonthStats> entry : statsMap.entrySet()) {
                BigDecimal proRated = entry.getValue().median().multiply(BigDecimal.valueOf(fraction))
                        .setScale(2, RoundingMode.HALF_UP);
                if (proRated.compareTo(BigDecimal.ZERO) > 0) {
                    expense.add(new BreakdownItemDto(entry.getKey().getName(), proRated, false, true));
                }
            }
        }
        return new BreakdownDto(past.incomeItems(), expense);
    }

    /**
     * Агрегирует planned-события заданного типа по категории.
     * Возвращает {@link ArrayList} (а не неизменяемый список), чтобы вызывающий код
     * мог добавлять predicted-элементы после агрегации.
     */
    private List<BreakdownItemDto> aggregatePlannedByCategory(List<FinancialEvent> events, EventType type) {
        return events.stream()
                .filter(e -> e.getType() == type)
                .filter(e -> e.getCategory() != null)
                .collect(Collectors.groupingBy(
                        e -> e.getCategory().getName(),
                        Collectors.collectingAndThen(Collectors.toList(),
                                list -> new BreakdownItemDto(
                                        list.get(0).getCategory().getName(),
                                        list.stream()
                                                .map(e -> e.getPlannedAmount() != null ? e.getPlannedAmount() : BigDecimal.ZERO)
                                                .reduce(BigDecimal.ZERO, BigDecimal::add),
                                        list.stream().anyMatch(e -> e.getRecurringRule() != null),
                                        false
                                ))))
                .values().stream()
                .sorted((a, b) -> b.amount().compareTo(a.amount()))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private List<BreakdownItemDto> aggregateByCategory(List<FinancialEvent> events, EventType type,
                                                       java.util.function.Function<FinancialEvent, BigDecimal> amountFn,
                                                       boolean isRecurring, boolean isPredicted) {
        return events.stream()
                .filter(e -> e.getType() == type)
                .filter(e -> e.getCategory() != null)
                .collect(Collectors.groupingBy(
                        e -> e.getCategory().getName(),
                        Collectors.reducing(BigDecimal.ZERO,
                                e -> amountFn.apply(e) != null ? amountFn.apply(e) : BigDecimal.ZERO,
                                BigDecimal::add)
                ))
                .entrySet().stream()
                .map(en -> new BreakdownItemDto(en.getKey(), en.getValue(), isRecurring, isPredicted))
                .sorted((a, b) -> b.amount().compareTo(a.amount()))    // сортировка по убыванию суммы
                .toList();
    }

    private StrategyTimelinePointDto withBreakdown(StrategyTimelinePointDto p, BreakdownDto br) {
        return new StrategyTimelinePointDto(
                p.yearMonth(), p.phase(),
                p.balance(), p.income(), p.expense(), p.nettoFlow(),
                p.balanceConfirmed(), p.balanceLow(), p.balanceHigh(),
                p.capital(), p.assets(), p.liabilities(),
                br
        );
    }

    /**
     * Самый ранний месяц активности пользователя — минимум из:
     * <ul>
     *   <li>первого FACT-события</li>
     *   <li>первого чекпоинта</li>
     *   <li>первой переоценки капитала</li>
     * </ul>
     * Если данных нет — возвращает предыдущий месяц (условный «старт»).
     *
     * <p>Используется для определения левой границы шкалы и {@code predictionWindowMonths}.
     */
    YearMonth firstActivityMonth() {
        Optional<LocalDate> earliestFact = eventRepository.findEarliestFactDate();
        Optional<LocalDate> earliestCheckpoint = checkpointRepository.findEarliestCheckpointDate();
        Optional<LocalDate> earliestRevaluation = capitalService.findEarliestRevaluationDate();

        Optional<LocalDate> earliest = Stream.of(earliestFact, earliestCheckpoint, earliestRevaluation)
                .flatMap(Optional::stream)
                .min(LocalDate::compareTo);

        return earliest
                .map(YearMonth::from)
                .orElseGet(() -> YearMonth.now().minusMonths(1));
    }
}
