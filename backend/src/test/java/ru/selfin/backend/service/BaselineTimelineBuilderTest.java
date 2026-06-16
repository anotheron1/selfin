package ru.selfin.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.selfin.backend.dto.capital.CapitalTrajectoryDto;
import ru.selfin.backend.dto.strategy.BreakdownDto;
import ru.selfin.backend.dto.strategy.BreakdownItemDto;
import ru.selfin.backend.dto.strategy.CategoryMonthStats;
import ru.selfin.backend.dto.strategy.StrategyPointPhase;
import ru.selfin.backend.dto.strategy.StrategyTimelineDto;
import ru.selfin.backend.dto.strategy.StrategyTimelinePointDto;
import ru.selfin.backend.model.Category;
import ru.selfin.backend.model.RecurringRule;
import ru.selfin.backend.model.EventKind;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.repository.BalanceCheckpointRepository;
import ru.selfin.backend.repository.CategoryRepository;
import ru.selfin.backend.repository.FinancialEventRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BaselineTimelineBuilderTest {

    private FinancialEventRepository eventRepo;
    private BalanceCheckpointRepository checkpointRepo;
    private CategoryRepository categoryRepo;
    private PredictionService predictionService;
    private CapitalService capitalService;
    private BaselineTimelineBuilder builder;

    @BeforeEach
    void setUp() {
        eventRepo = mock(FinancialEventRepository.class);
        checkpointRepo = mock(BalanceCheckpointRepository.class);
        categoryRepo = mock(CategoryRepository.class);
        predictionService = mock(PredictionService.class);
        capitalService = mock(CapitalService.class);

        builder = new BaselineTimelineBuilder(eventRepo, checkpointRepo, categoryRepo,
                predictionService, capitalService);
    }

    @Test
    void firstActivityMonth_returns_earliest_of_all_three_sources() {
        // checkpoint самый ранний
        when(eventRepo.findEarliestFactDate())
                .thenReturn(Optional.of(LocalDate.of(2024, 6, 15)));
        when(checkpointRepo.findEarliestCheckpointDate())
                .thenReturn(Optional.of(LocalDate.of(2024, 3, 1)));
        when(capitalService.findEarliestRevaluationDate())
                .thenReturn(Optional.of(LocalDate.of(2024, 5, 1)));

        assertThat(builder.firstActivityMonth()).isEqualTo(YearMonth.of(2024, 3));
    }

    @Test
    void firstActivityMonth_uses_fact_when_earliest() {
        when(eventRepo.findEarliestFactDate())
                .thenReturn(Optional.of(LocalDate.of(2023, 1, 10)));
        when(checkpointRepo.findEarliestCheckpointDate())
                .thenReturn(Optional.of(LocalDate.of(2023, 4, 1)));
        when(capitalService.findEarliestRevaluationDate())
                .thenReturn(Optional.empty());

        assertThat(builder.firstActivityMonth()).isEqualTo(YearMonth.of(2023, 1));
    }

    @Test
    void firstActivityMonth_uses_revaluation_when_earliest() {
        when(eventRepo.findEarliestFactDate()).thenReturn(Optional.empty());
        when(checkpointRepo.findEarliestCheckpointDate()).thenReturn(Optional.empty());
        when(capitalService.findEarliestRevaluationDate())
                .thenReturn(Optional.of(LocalDate.of(2022, 11, 1)));

        assertThat(builder.firstActivityMonth()).isEqualTo(YearMonth.of(2022, 11));
    }

    @Test
    void firstActivityMonth_returns_previous_month_when_no_data() {
        when(eventRepo.findEarliestFactDate()).thenReturn(Optional.empty());
        when(checkpointRepo.findEarliestCheckpointDate()).thenReturn(Optional.empty());
        when(capitalService.findEarliestRevaluationDate()).thenReturn(Optional.empty());

        YearMonth expected = YearMonth.now().minusMonths(1);
        assertThat(builder.firstActivityMonth()).isEqualTo(expected);
    }

    @Test
    void firstActivityMonth_truncates_to_month_ignoring_day() {
        // Дата 15-го числа должна давать тот же месяц, что и 1-е
        when(eventRepo.findEarliestFactDate())
                .thenReturn(Optional.of(LocalDate.of(2024, 8, 15)));
        when(checkpointRepo.findEarliestCheckpointDate()).thenReturn(Optional.empty());
        when(capitalService.findEarliestRevaluationDate()).thenReturn(Optional.empty());

        assertThat(builder.firstActivityMonth()).isEqualTo(YearMonth.of(2024, 8));
    }

    @Test
    void firstActivityMonth_with_only_checkpoint() {
        when(eventRepo.findEarliestFactDate()).thenReturn(Optional.empty());
        when(checkpointRepo.findEarliestCheckpointDate()).thenReturn(Optional.of(LocalDate.of(2024, 2, 1)));
        when(capitalService.findEarliestRevaluationDate()).thenReturn(Optional.empty());

        assertThat(builder.firstActivityMonth()).isEqualTo(YearMonth.of(2024, 2));
    }

    @Test
    void buildPastPoints_uses_liquidAt_per_month_and_aggregates_facts() {
        // Готовим прошлое: с марта 2024 по апрель 2026 (currentMonth = май 2026)
        // Для тест-сценария замокаем 3 месяца истории и проверим что точки построены корректно.
        YearMonth current = YearMonth.of(2026, 5);

        // capitalService.liquidAt вызывается для конца каждого прошлого месяца
        when(capitalService.liquidAt(LocalDate.of(2026, 2, 28))).thenReturn(new BigDecimal("100000"));
        when(capitalService.liquidAt(LocalDate.of(2026, 3, 31))).thenReturn(new BigDecimal("150000"));
        when(capitalService.liquidAt(LocalDate.of(2026, 4, 30))).thenReturn(new BigDecimal("180000"));

        // Замокать findFactsByDateRange чтобы вернуть факты по месяцам
        Category catFood = Category.builder().id(UUID.randomUUID()).name("Продукты").build();
        Category catSalary = Category.builder().id(UUID.randomUUID()).name("Зарплата").build();
        when(eventRepo.findFactsByDateRange(any(), any())).thenReturn(List.of(
                FinancialEvent.builder().date(LocalDate.of(2026, 3, 5)).category(catSalary)
                        .type(EventType.INCOME).factAmount(new BigDecimal("200000"))
                        .eventKind(EventKind.FACT).deleted(false).build(),
                FinancialEvent.builder().date(LocalDate.of(2026, 3, 10)).category(catFood)
                        .type(EventType.EXPENSE).factAmount(new BigDecimal("40000"))
                        .eventKind(EventKind.FACT).deleted(false).build(),
                FinancialEvent.builder().date(LocalDate.of(2026, 4, 5)).category(catSalary)
                        .type(EventType.INCOME).factAmount(new BigDecimal("200000"))
                        .eventKind(EventKind.FACT).deleted(false).build(),
                FinancialEvent.builder().date(LocalDate.of(2026, 4, 10)).category(catFood)
                        .type(EventType.EXPENSE).factAmount(new BigDecimal("35000"))
                        .eventKind(EventKind.FACT).deleted(false).build()
        ));

        List<StrategyTimelinePointDto> past = builder.buildPastPoints(YearMonth.of(2026, 2), current);

        assertThat(past).hasSize(3);
        // Февраль — нет фактов в моке, баланс из liquidAt
        assertThat(past.get(0).yearMonth()).isEqualTo(YearMonth.of(2026, 2));
        assertThat(past.get(0).phase()).isEqualTo(StrategyPointPhase.PAST);
        assertThat(past.get(0).balance()).isEqualByComparingTo("100000");
        assertThat(past.get(0).balanceConfirmed()).isNull();
        assertThat(past.get(0).balanceLow()).isNull();

        // Март
        assertThat(past.get(1).income()).isEqualByComparingTo("200000");
        assertThat(past.get(1).expense()).isEqualByComparingTo("40000");
        assertThat(past.get(1).nettoFlow()).isEqualByComparingTo("160000");
        assertThat(past.get(1).balance()).isEqualByComparingTo("150000");

        // Апрель
        assertThat(past.get(2).income()).isEqualByComparingTo("200000");
        assertThat(past.get(2).expense()).isEqualByComparingTo("35000");
        assertThat(past.get(2).balance()).isEqualByComparingTo("180000");
    }

    @Test
    void buildFuturePoints_uses_recurring_planned_and_predicts_with_fan_bounds() {
        YearMonth current = YearMonth.of(2026, 5);

        // Замокать liquidAt(today) — seed для balanceConfirmed[0]
        when(capitalService.liquidAt(LocalDate.now())).thenReturn(new BigDecimal("180000"));

        // Строим statsMap напрямую — buildFuturePoints больше не вызывает categoryRepo/predictionService
        Category food = Category.builder().id(UUID.randomUUID()).name("Продукты").forecastEnabled(true).build();
        Category transport = Category.builder().id(UUID.randomUUID()).name("Транспорт").forecastEnabled(true).build();
        Category fun = Category.builder().id(UUID.randomUUID()).name("Развлечения").forecastEnabled(true).build();

        Map<Category, CategoryMonthStats> statsMap = new LinkedHashMap<>();
        statsMap.put(food, new CategoryMonthStats(food.getId(), 6,
                new BigDecimal("35000"), new BigDecimal("30000"), new BigDecimal("40000"))); // halfIqr=5000
        statsMap.put(transport, new CategoryMonthStats(transport.getId(), 6,
                new BigDecimal("10000"), new BigDecimal("8000"), new BigDecimal("12000"))); // halfIqr=2000
        statsMap.put(fun, new CategoryMonthStats(fun.getId(), 6,
                new BigDecimal("15000"), new BigDecimal("10000"), new BigDecimal("20000"))); // halfIqr=5000

        // sumMedian = 60000, sumHalfIqr = sqrt(25M + 4M + 25M) = sqrt(54M) ≈ 7348

        // Recurring + planned событий на будущие месяцы — пусто (тест на чистый прогноз)
        when(eventRepo.findPlannedEventsByDateRange(any(), any())).thenReturn(List.of());

        List<StrategyTimelinePointDto> future = builder.buildFuturePoints(current, 3, statsMap);

        assertThat(future).hasSize(3);

        // Месяц 1 — k=1
        StrategyTimelinePointDto m1 = future.get(0);
        assertThat(m1.yearMonth()).isEqualTo(YearMonth.of(2026, 6));
        assertThat(m1.phase()).isEqualTo(StrategyPointPhase.FUTURE);
        // balanceConfirmed[1] = 180000 + 0 - 0 = 180000 (нет recurring/planned)
        assertThat(m1.balanceConfirmed()).isEqualByComparingTo("180000");
        // balanceMedian[1] = 180000 - 60000 * 1 = 120000
        assertThat(m1.balance()).isEqualByComparingTo("120000");
        // accumulatedHalfIqr[1] = 7348 * sqrt(1) ≈ 7348
        // balanceLow = 120000 - 7348 ≈ 112652, balanceHigh = 120000 + 7348 ≈ 127348
        assertThat(m1.balanceLow().doubleValue()).isCloseTo(112652, offset(50.0));
        assertThat(m1.balanceHigh().doubleValue()).isCloseTo(127348, offset(50.0));

        // Месяц 3 — k=3
        StrategyTimelinePointDto m3 = future.get(2);
        // balanceMedian[3] = 180000 - 60000 * 3 = 0
        assertThat(m3.balance()).isEqualByComparingTo("0");
        // accumulatedHalfIqr[3] = 7348 * sqrt(3) ≈ 12727; cap=2×|0|=0
        // НО т.к. balanceMedian=0, cap=0 → fan ширина 0
        assertThat(m3.balanceLow()).isEqualByComparingTo("0");
        assertThat(m3.balanceHigh()).isEqualByComparingTo("0");
    }

    @Test
    void enrichWithCapital_fills_capital_assets_liabilities_for_past_and_future() {
        // Подаём 3 точки (2 PAST + 1 FUTURE) с нулевыми капитал-полями
        YearMonth jan = YearMonth.of(2026, 1);
        YearMonth feb = YearMonth.of(2026, 2);
        YearMonth jun = YearMonth.of(2026, 6);
        List<StrategyTimelinePointDto> points = new ArrayList<>(List.of(
                pointWith(jan, StrategyPointPhase.PAST),
                pointWith(feb, StrategyPointPhase.PAST),
                pointWith(jun, StrategyPointPhase.FUTURE)
        ));

        // Замокать trajectory: 2 прошлые точки + 0 будущих в реальности
        CapitalTrajectoryDto trajectory = new CapitalTrajectoryDto(List.of(
                new CapitalTrajectoryDto.Point(LocalDate.of(2026, 1, 31),
                        new BigDecimal("3500000"), new BigDecimal("4000000"),
                        new BigDecimal("4500000"), new BigDecimal("500000")),
                new CapitalTrajectoryDto.Point(LocalDate.of(2026, 2, 28),
                        new BigDecimal("3600000"), new BigDecimal("4100000"),
                        new BigDecimal("4600000"), new BigDecimal("500000"))
        ));
        when(capitalService.trajectory(any(), any())).thenReturn(trajectory);

        List<StrategyTimelinePointDto> result = builder.enrichWithCapital(points);

        assertThat(result.get(0).capital()).isEqualByComparingTo("3500000");
        assertThat(result.get(1).capital()).isEqualByComparingTo("3600000");
        // Future месяц получает last-known: 3600000
        assertThat(result.get(2).capital()).isEqualByComparingTo("3600000");
        assertThat(result.get(2).assets()).isEqualByComparingTo("4600000");
        assertThat(result.get(2).liabilities()).isEqualByComparingTo("500000");
    }

    @Test
    void enrichWithBreakdown_for_past_aggregates_facts_by_category() {
        YearMonth mar = YearMonth.of(2026, 3);
        StrategyTimelinePointDto march = pointWith(mar, StrategyPointPhase.PAST);

        Category salary = Category.builder().id(UUID.randomUUID()).name("Зарплата").build();
        Category food = Category.builder().id(UUID.randomUUID()).name("Продукты").build();

        when(eventRepo.findFactsByDateRange(mar.atDay(1), mar.atEndOfMonth())).thenReturn(List.of(
                FinancialEvent.builder().date(LocalDate.of(2026, 3, 5)).category(salary)
                        .type(EventType.INCOME).factAmount(new BigDecimal("200000"))
                        .eventKind(EventKind.FACT).deleted(false).build(),
                FinancialEvent.builder().date(LocalDate.of(2026, 3, 10)).category(food)
                        .type(EventType.EXPENSE).factAmount(new BigDecimal("40000"))
                        .eventKind(EventKind.FACT).deleted(false).build()
        ));
        // Нет планов в этом диапазоне
        when(eventRepo.findPlannedEventsByDateRange(mar.atDay(1), mar.atEndOfMonth())).thenReturn(List.of());

        // Пустой statsMap — PAST breakdown его не использует
        Map<Category, CategoryMonthStats> statsMap = Map.of();

        List<StrategyTimelinePointDto> result = builder.enrichWithBreakdown(List.of(march), statsMap);

        BreakdownDto br = result.get(0).breakdown();
        assertThat(br).isNotNull();
        assertThat(br.incomeItems()).hasSize(1);
        assertThat(br.incomeItems().get(0).category()).isEqualTo("Зарплата");
        assertThat(br.incomeItems().get(0).amount()).isEqualByComparingTo("200000");
        assertThat(br.expenseItems()).hasSize(1);
        assertThat(br.expenseItems().get(0).category()).isEqualTo("Продукты");
        assertThat(br.expenseItems().get(0).amount()).isEqualByComparingTo("40000");
    }

    @Test
    void enrichWithBreakdown_for_future_includes_recurring_planned_and_predicted() {
        YearMonth jun = YearMonth.of(2026, 6);
        StrategyTimelinePointDto june = pointWith(jun, StrategyPointPhase.FUTURE);

        Category mortgage = Category.builder().id(UUID.randomUUID()).name("Ипотека").build();
        Category food = Category.builder().id(UUID.randomUUID()).name("Продукты").forecastEnabled(true).build();
        RecurringRule rule = RecurringRule.builder().id(UUID.randomUUID()).build();

        // Recurring planned EXPENSE на ипотеку
        when(eventRepo.findPlannedEventsByDateRange(jun.atDay(1), jun.atEndOfMonth())).thenReturn(List.of(
                FinancialEvent.builder().date(LocalDate.of(2026, 6, 15)).category(mortgage)
                        .type(EventType.EXPENSE).plannedAmount(new BigDecimal("80000"))
                        .eventKind(EventKind.PLAN).recurringRule(rule).deleted(false).build()
        ));
        // Нет фактов в этом диапазоне
        when(eventRepo.findFactsByDateRange(jun.atDay(1), jun.atEndOfMonth())).thenReturn(List.of());

        // statsMap с предвычисленной статистикой для food — передаётся напрямую
        Map<Category, CategoryMonthStats> statsMap = new LinkedHashMap<>();
        statsMap.put(food, new CategoryMonthStats(food.getId(), 6,
                new BigDecimal("35000"), new BigDecimal("30000"), new BigDecimal("40000")));

        List<StrategyTimelinePointDto> result = builder.enrichWithBreakdown(List.of(june), statsMap);

        BreakdownDto br = result.get(0).breakdown();
        assertThat(br.expenseItems()).hasSize(2);

        // Recurring ипотека
        BreakdownItemDto mortgageItem = br.expenseItems().stream()
                .filter(i -> i.category().equals("Ипотека"))
                .findFirst().orElseThrow();
        assertThat(mortgageItem.amount()).isEqualByComparingTo("80000");
        assertThat(mortgageItem.isRecurring()).isTrue();
        assertThat(mortgageItem.isPredicted()).isFalse();

        // Predicted продукты — без суффикса " (прогноз остатка)", isPredicted=true — контракт
        BreakdownItemDto foodItem = br.expenseItems().stream()
                .filter(i -> i.category().equals("Продукты"))
                .findFirst().orElseThrow();
        assertThat(foodItem.amount()).isEqualByComparingTo("35000");
        assertThat(foodItem.isRecurring()).isFalse();
        assertThat(foodItem.isPredicted()).isTrue();
    }

    @Test
    void build_assembles_past_current_future_with_capital_and_breakdown() {
        // Замокать минимально, чтобы пройти всю цепочку
        when(eventRepo.findEarliestFactDate()).thenReturn(Optional.of(LocalDate.now().minusMonths(2)));
        when(checkpointRepo.findEarliestCheckpointDate()).thenReturn(Optional.empty());
        when(capitalService.findEarliestRevaluationDate()).thenReturn(Optional.empty());

        when(capitalService.liquidAt(any())).thenReturn(new BigDecimal("100000"));
        when(eventRepo.findFactsByDateRange(any(), any())).thenReturn(List.of());
        when(eventRepo.findPlannedEventsByDateRange(any(), any())).thenReturn(List.of());
        when(categoryRepo.findAllByForecastEnabledTrueAndDeletedFalse()).thenReturn(List.of());
        when(capitalService.trajectory(any(), any())).thenReturn(new CapitalTrajectoryDto(List.of()));

        var snap = builder.build(3, true);

        // 2 past + 1 current + 3 future = 6
        assertThat(snap.points()).hasSize(6);
        assertThat(snap.currentMonth()).isEqualTo(YearMonth.now());
        assertThat(snap.horizonEnd()).isEqualTo(YearMonth.now().plusMonths(3));
        assertThat(snap.predictionWindowMonths()).isEqualTo(6);
        assertThat(snap.fanEnabled()).isFalse(); // нет forecast-категорий
    }

    @Test
    void build_currentMonth_balance_uses_liquidAt_today() {
        when(eventRepo.findEarliestFactDate()).thenReturn(Optional.empty());
        when(checkpointRepo.findEarliestCheckpointDate()).thenReturn(Optional.empty());
        when(capitalService.findEarliestRevaluationDate()).thenReturn(Optional.empty());
        // Fallback firstActivityMonth = today.minusMonths(1) — значит будут PAST + CURRENT + FUTURE
        when(capitalService.liquidAt(LocalDate.now())).thenReturn(new BigDecimal("550000"));
        when(capitalService.liquidAt(any())).thenReturn(new BigDecimal("550000"));
        when(eventRepo.findFactsByDateRange(any(), any())).thenReturn(List.of());
        when(eventRepo.findPlannedEventsByDateRange(any(), any())).thenReturn(List.of());
        when(categoryRepo.findAllByForecastEnabledTrueAndDeletedFalse()).thenReturn(List.of());
        when(capitalService.trajectory(any(), any())).thenReturn(new CapitalTrajectoryDto(List.of()));

        var snap = builder.build(2, false);

        StrategyTimelinePointDto current = snap.points().stream()
                .filter(p -> p.phase() == StrategyPointPhase.CURRENT)
                .findFirst().orElseThrow();
        assertThat(current.balance()).isEqualByComparingTo("550000");
        assertThat(current.yearMonth()).isEqualTo(YearMonth.now());
    }

    // helper-метод pointWith
    private StrategyTimelinePointDto pointWith(YearMonth ym, StrategyPointPhase phase) {
        return new StrategyTimelinePointDto(ym, phase,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                null, null, null,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                null);
    }
}
