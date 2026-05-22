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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Сервис стратегической временной шкалы.
 *
 * <p>Точка входа — {@link #getTimeline(int, int, boolean)}, реализуемая в следующих чанках.
 * Вспомогательные методы (firstActivityMonth и др.) реализуются по TDD в Chunk 2.
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

    /**
     * Точка входа для GET /api/v1/strategy/timeline.
     * Реализуется в следующих чанках.
     */
    public StrategyTimelineDto getTimeline(int historyMonths, int horizonMonths, boolean withBreakdown) {
        throw new UnsupportedOperationException("Not yet implemented");
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

    private static final int PREDICTION_WINDOW_MONTHS = 6;
    private static final int MIN_HISTORY_FOR_FAN = 3;
    private static final int MIN_CATEGORIES_FOR_FAN = 3;

    /**
     * @param current        текущий месяц
     * @param horizonMonths  сколько будущих месяцев построить (включая current+1 … current+horizonMonths)
     */
    List<StrategyTimelinePointDto> buildFuturePoints(YearMonth current, int horizonMonths) {
        List<StrategyTimelinePointDto> points = new ArrayList<>();
        if (horizonMonths <= 0) return points;

        // Шаг 1: forecast-категории и их статы
        List<Category> forecastCats = categoryRepository.findAllByForecastEnabledTrueAndDeletedFalse();
        List<CategoryMonthStats> allStats = forecastCats.stream()
                .map(c -> predictionService.getStatsForCategory(c, PREDICTION_WINDOW_MONTHS))
                .toList();
        List<CategoryMonthStats> eligibleStats = allStats.stream()
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

        // Шаг 2: планы (recurring + manual) на будущее
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
