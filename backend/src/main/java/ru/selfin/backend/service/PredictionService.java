package ru.selfin.backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.selfin.backend.dto.CategoryForecastDto;
import ru.selfin.backend.dto.DailyForecastPointDto;
import ru.selfin.backend.dto.MonthlyForecastDto;
import ru.selfin.backend.dto.strategy.CategoryMonthStats;
import ru.selfin.backend.model.Category;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.EventKind;
import ru.selfin.backend.repository.FinancialEventRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PredictionService {

    private final FinancialEventRepository eventRepository;

    /**
     * Compute forecast for a single named category using already-fetched events.
     * Events for other categories are ignored.
     */
    public CategoryForecastDto forecast(String categoryName,
                                        List<FinancialEvent> monthEvents,
                                        LocalDate today) {
        List<FinancialEvent> catEvents = monthEvents.stream()
                .filter(e -> categoryName.equals(e.getCategory().getName()))
                .filter(e -> !e.isDeleted())
                .toList();

        int daysInMonth = today.lengthOfMonth();
        int daysElapsed = today.getDayOfMonth(); // 1-based: day 1 = 1 day elapsed

        BigDecimal currentFact = sumFacts(catEvents);
        BigDecimal plannedLimit = sumAllPlans(catEvents);
        boolean hasPlans = catEvents.stream().anyMatch(e -> e.getEventKind() == EventKind.PLAN);

        BigDecimal projection = computeProjection(catEvents, currentFact,
                hasPlans, daysElapsed, daysInMonth, today);

        List<DailyForecastPointDto> history = buildHistory(catEvents, hasPlans, daysElapsed, daysInMonth, today);

        return new CategoryForecastDto(categoryName, currentFact, plannedLimit, projection, history);
    }

    /**
     * Compute forecasts for all forecast_enabled EXPENSE categories in already-fetched events.
     * Use this from DashboardService and TargetFundService to avoid double-fetching events.
     */
    public MonthlyForecastDto forecastFromEvents(List<FinancialEvent> monthEvents, LocalDate today) {
        Map<String, List<FinancialEvent>> byCategory = monthEvents.stream()
                .filter(e -> !e.isDeleted())
                .filter(e -> e.getCategory().isForecastEnabled())
                .collect(Collectors.groupingBy(e -> e.getCategory().getName()));

        List<CategoryForecastDto> forecasts = new ArrayList<>();
        BigDecimal netDelta = BigDecimal.ZERO;

        for (Map.Entry<String, List<FinancialEvent>> entry : byCategory.entrySet()) {
            CategoryForecastDto cat = forecast(entry.getKey(), entry.getValue(), today);
            forecasts.add(cat);

            // Delta contribution: only linear categories (no PLAN events in month)
            boolean hasPlans = entry.getValue().stream()
                    .anyMatch(e -> e.getEventKind() == EventKind.PLAN);
            if (!hasPlans && cat.projectionAmount().compareTo(cat.currentFact()) > 0) {
                BigDecimal extrapolatedFuture = cat.projectionAmount().subtract(cat.currentFact());
                netDelta = netDelta.add(extrapolatedFuture);
            }
        }

        return new MonthlyForecastDto(forecasts, netDelta);
    }

    /**
     * Compute forecasts fetching events from DB. Use for standalone /forecast endpoint.
     */
    public MonthlyForecastDto forecastMonth(YearMonth month, LocalDate today) {
        LocalDate start = month.atDay(1);
        LocalDate end = month.atEndOfMonth();
        List<FinancialEvent> events = eventRepository.findAllByDeletedFalseAndDateBetween(start, end);
        return forecastFromEvents(events, today);
    }

    /**
     * Стата трат категории за последние {@code historyWindowMonths} полных месяцев.
     * Используется StrategyTimelineService для построения fan chart.
     *
     * <p>Фильтр событий — тот же что в {@link #sumFacts}: {@code eventKind = FACT, deleted = false}.
     * {@code EventStatus} не учитывается (все FACT-события — учётные транзакции).
     *
     * <p>Если {@code monthsOfHistory < 3}, caller (StrategyTimelineService) не должен учитывать
     * категорию в расчёте конуса неопределённости — но median всё равно вычисляется.
     *
     * <p>Percentile-вычисление — линейная интерполяция между соседними точками отсортированного массива.
     */
    public CategoryMonthStats getStatsForCategory(Category cat, int historyWindowMonths) {
        LocalDate today = LocalDate.now();
        LocalDate from = today.minusMonths(historyWindowMonths).withDayOfMonth(1);
        LocalDate to = today.withDayOfMonth(1).minusDays(1);   // конец предыдущего месяца

        List<FinancialEvent> events = eventRepository.findFactsByDateRange(from, to).stream()
                .filter(e -> !e.isDeleted())
                .filter(e -> e.getEventKind() == EventKind.FACT)
                .filter(e -> e.getCategory() != null && cat.getId().equals(e.getCategory().getId()))
                .toList();

        Map<YearMonth, BigDecimal> monthlyTotals = events.stream()
                .collect(Collectors.groupingBy(
                        e -> YearMonth.from(e.getDate()),
                        Collectors.reducing(BigDecimal.ZERO,
                                e -> e.getFactAmount() != null ? e.getFactAmount() : BigDecimal.ZERO,
                                BigDecimal::add)
                ));

        if (monthlyTotals.isEmpty()) {
            return new CategoryMonthStats(cat.getId(), 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }

        List<BigDecimal> sorted = monthlyTotals.values().stream()
                .sorted()
                .toList();

        return new CategoryMonthStats(
                cat.getId(),
                sorted.size(),
                percentile(sorted, 0.50),
                percentile(sorted, 0.25),
                percentile(sorted, 0.75)
        );
    }

    /**
     * Линейная интерполяция percentile из отсортированного списка.
     */
    private BigDecimal percentile(List<BigDecimal> sorted, double q) {
        if (sorted.isEmpty()) return BigDecimal.ZERO;
        if (sorted.size() == 1) return sorted.get(0);

        double position = q * (sorted.size() - 1);
        int lowerIdx = (int) Math.floor(position);
        int upperIdx = (int) Math.ceil(position);
        if (lowerIdx == upperIdx) return sorted.get(lowerIdx);

        double frac = position - lowerIdx;
        BigDecimal lower = sorted.get(lowerIdx);
        BigDecimal upper = sorted.get(upperIdx);
        BigDecimal diff = upper.subtract(lower);
        return lower.add(diff.multiply(BigDecimal.valueOf(frac)));
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private BigDecimal computeProjection(List<FinancialEvent> catEvents,
                                          BigDecimal currentFact,
                                          boolean hasPlans,
                                          int daysElapsed,
                                          int daysInMonth,
                                          LocalDate today) {
        if (hasPlans) {
            BigDecimal remainingPlans = catEvents.stream()
                    .filter(e -> e.getEventKind() == EventKind.PLAN)
                    .filter(e -> e.getDate() != null && e.getDate().isAfter(today))
                    .map(e -> e.getPlannedAmount() != null ? e.getPlannedAmount() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            return currentFact.add(remainingPlans);
        }

        // Linear A — guard against daysElapsed=0 or no facts
        if (daysElapsed == 0 || currentFact.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        int remainingDays = daysInMonth - daysElapsed;
        BigDecimal dailyRate = currentFact.divide(
                BigDecimal.valueOf(daysElapsed), 4, RoundingMode.HALF_UP);
        return currentFact.add(dailyRate.multiply(BigDecimal.valueOf(remainingDays)))
                .setScale(0, RoundingMode.HALF_UP);
    }

    private List<DailyForecastPointDto> buildHistory(List<FinancialEvent> catEvents,
                                                      boolean hasPlans,
                                                      int daysElapsed,
                                                      int daysInMonth,
                                                      LocalDate today) {
        List<DailyForecastPointDto> points = new ArrayList<>();
        LocalDate monthStart = today.withDayOfMonth(1);

        for (int d = 1; d <= daysElapsed; d++) {
            LocalDate dayDate = monthStart.withDayOfMonth(d);

            BigDecimal factOnDay = catEvents.stream()
                    .filter(e -> e.getEventKind() == EventKind.FACT)
                    .filter(e -> e.getDate() != null && !e.getDate().isAfter(dayDate))
                    .map(e -> e.getFactAmount() != null ? e.getFactAmount() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal projOnDay;
            if (hasPlans) {
                BigDecimal remainingAfterD = catEvents.stream()
                        .filter(e -> e.getEventKind() == EventKind.PLAN)
                        .filter(e -> e.getDate() != null && e.getDate().isAfter(dayDate))
                        .map(e -> e.getPlannedAmount() != null ? e.getPlannedAmount() : BigDecimal.ZERO)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                projOnDay = factOnDay.add(remainingAfterD);
            } else if (factOnDay.compareTo(BigDecimal.ZERO) == 0) {
                projOnDay = BigDecimal.ZERO;
            } else {
                int remaining = daysInMonth - d;
                BigDecimal dailyRate = factOnDay.divide(
                        BigDecimal.valueOf(d), 4, RoundingMode.HALF_UP);
                projOnDay = factOnDay.add(dailyRate.multiply(BigDecimal.valueOf(remaining)))
                        .setScale(0, RoundingMode.HALF_UP);
            }

            points.add(new DailyForecastPointDto(d, factOnDay, projOnDay));
        }

        return points;
    }

    private BigDecimal sumFacts(List<FinancialEvent> events) {
        return events.stream()
                .filter(e -> e.getEventKind() == EventKind.FACT)
                .map(e -> e.getFactAmount() != null ? e.getFactAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal sumAllPlans(List<FinancialEvent> events) {
        return events.stream()
                .filter(e -> e.getEventKind() == EventKind.PLAN)
                .map(e -> e.getPlannedAmount() != null ? e.getPlannedAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
