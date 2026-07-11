package ru.selfin.backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.selfin.backend.dto.CategoryForecastDto;
import ru.selfin.backend.dto.DailyForecastPointDto;
import ru.selfin.backend.dto.DashboardDto;
import ru.selfin.backend.dto.MonthlyForecastDto;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.repository.FinancialEventRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Collator;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Дашборд после ANO-14: только прогресс-бары план/факт по расходным категориям.
 *
 * <p>Баланс, зарплатные горизонты и алерт кассового разрыва переехали в кармашек
 * (PocketEngine, {@code GET /pocket}) — одна истина, много представлений.
 * Собственные параллельные расчёты этого сервиса (детект зп по имени категории,
 * detectCashGap, calcBalanceBefore/After) удалены — кусок ANO-23.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DashboardService {

    private final FinancialEventRepository eventRepository;
    private final PredictionService predictionService;

    /**
     * Прогресс-бары план/факт по категориям расходов за месяц даты {@code asOfDate}.
     *
     * @param asOfDate дата расчёта; обычно «сегодня»
     * @return DTO дашборда (только progressBars)
     */
    public DashboardDto getDashboard(LocalDate asOfDate) {
        LocalDate monthStart = asOfDate.withDayOfMonth(1);
        LocalDate monthEnd = asOfDate.withDayOfMonth(asOfDate.lengthOfMonth());
        List<FinancialEvent> monthEvents = eventRepository
                .findAllByDeletedFalseAndDateBetween(monthStart, monthEnd);
        return new DashboardDto(buildProgressBars(monthEvents, asOfDate));
    }

    /**
     * Строит прогресс-бары план/факт по категориям расходов за месяц.
     *
     * <p>Для каждой категории суммируются все плановые и фактические суммы событий
     * типа {@link EventType#EXPENSE} за месяц. Процент = {@code fact / plan * 100},
     * может превышать 100 (перерасход). Список сортируется по имени категории
     * в русском алфавитном порядке. Для категорий с включённым прогнозом
     * ({@code forecastEnabled}) добавляются данные из {@link PredictionService}.
     *
     * @param events  события текущего месяца (могут включать INCOME — они фильтруются)
     * @param today   дата расчёта (обычно «сегодня»), передаётся в PredictionService
     * @return список прогресс-баров, отсортированных по имени категории
     */
    List<DashboardDto.CategoryProgressBar> buildProgressBars(List<FinancialEvent> events, LocalDate today) {
        MonthlyForecastDto forecast = predictionService.forecastFromEvents(events, today);
        Map<String, CategoryForecastDto> forecastByCategory = forecast.categories().stream()
                .collect(Collectors.toMap(CategoryForecastDto::categoryName, f -> f));

        Map<String, List<FinancialEvent>> byCategory = events.stream()
                .filter(e -> e.getType() == EventType.EXPENSE)
                .collect(Collectors.groupingBy(e -> e.getCategory().getName()));

        Collator collator = Collator.getInstance(new Locale("ru", "RU"));
        return byCategory.entrySet().stream()
                .sorted((a, b) -> collator.compare(a.getKey(), b.getKey()))
                .map(entry -> {
                    List<FinancialEvent> catEvents = entry.getValue();
                    BigDecimal planned = catEvents.stream()
                            .map(e -> e.getPlannedAmount() != null ? e.getPlannedAmount() : BigDecimal.ZERO)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    BigDecimal fact = catEvents.stream()
                            .map(e -> e.getFactAmount() != null ? e.getFactAmount() : BigDecimal.ZERO)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    int pct = planned.compareTo(BigDecimal.ZERO) == 0 ? 0
                            : fact.multiply(BigDecimal.valueOf(100))
                                  .divide(planned, 0, RoundingMode.HALF_UP).intValue();

                    CategoryForecastDto catForecast = forecastByCategory.get(entry.getKey());
                    boolean forecastEnabled = catForecast != null;
                    BigDecimal projection = forecastEnabled ? catForecast.projectionAmount() : null;
                    List<DailyForecastPointDto> history = forecastEnabled
                            ? catForecast.history() : List.of();

                    return new DashboardDto.CategoryProgressBar(
                            entry.getKey(), fact, planned, pct,
                            projection, forecastEnabled, history);
                })
                .toList();
    }
}
