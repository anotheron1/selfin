package ru.selfin.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.selfin.backend.dto.strategy.StrategyPointPhase;
import ru.selfin.backend.dto.strategy.StrategyTimelinePointDto;
import ru.selfin.backend.model.Category;
import ru.selfin.backend.model.EventKind;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.repository.BalanceCheckpointRepository;
import ru.selfin.backend.repository.CategoryRepository;
import ru.selfin.backend.repository.FinancialEventRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StrategyTimelineServiceTest {

    private FinancialEventRepository eventRepo;
    private BalanceCheckpointRepository checkpointRepo;
    private CategoryRepository categoryRepo;
    private PredictionService predictionService;
    private CapitalService capitalService;
    private StrategyTimelineService service;

    @BeforeEach
    void setUp() {
        eventRepo = mock(FinancialEventRepository.class);
        checkpointRepo = mock(BalanceCheckpointRepository.class);
        categoryRepo = mock(CategoryRepository.class);
        predictionService = mock(PredictionService.class);
        capitalService = mock(CapitalService.class);

        service = new StrategyTimelineService(eventRepo, checkpointRepo, categoryRepo,
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

        assertThat(service.firstActivityMonth()).isEqualTo(YearMonth.of(2024, 3));
    }

    @Test
    void firstActivityMonth_uses_fact_when_earliest() {
        when(eventRepo.findEarliestFactDate())
                .thenReturn(Optional.of(LocalDate.of(2023, 1, 10)));
        when(checkpointRepo.findEarliestCheckpointDate())
                .thenReturn(Optional.of(LocalDate.of(2023, 4, 1)));
        when(capitalService.findEarliestRevaluationDate())
                .thenReturn(Optional.empty());

        assertThat(service.firstActivityMonth()).isEqualTo(YearMonth.of(2023, 1));
    }

    @Test
    void firstActivityMonth_uses_revaluation_when_earliest() {
        when(eventRepo.findEarliestFactDate()).thenReturn(Optional.empty());
        when(checkpointRepo.findEarliestCheckpointDate()).thenReturn(Optional.empty());
        when(capitalService.findEarliestRevaluationDate())
                .thenReturn(Optional.of(LocalDate.of(2022, 11, 1)));

        assertThat(service.firstActivityMonth()).isEqualTo(YearMonth.of(2022, 11));
    }

    @Test
    void firstActivityMonth_returns_previous_month_when_no_data() {
        when(eventRepo.findEarliestFactDate()).thenReturn(Optional.empty());
        when(checkpointRepo.findEarliestCheckpointDate()).thenReturn(Optional.empty());
        when(capitalService.findEarliestRevaluationDate()).thenReturn(Optional.empty());

        YearMonth expected = YearMonth.now().minusMonths(1);
        assertThat(service.firstActivityMonth()).isEqualTo(expected);
    }

    @Test
    void firstActivityMonth_truncates_to_month_ignoring_day() {
        // Дата 15-го числа должна давать тот же месяц, что и 1-е
        when(eventRepo.findEarliestFactDate())
                .thenReturn(Optional.of(LocalDate.of(2024, 8, 15)));
        when(checkpointRepo.findEarliestCheckpointDate()).thenReturn(Optional.empty());
        when(capitalService.findEarliestRevaluationDate()).thenReturn(Optional.empty());

        assertThat(service.firstActivityMonth()).isEqualTo(YearMonth.of(2024, 8));
    }

    @Test
    void firstActivityMonth_with_only_checkpoint() {
        when(eventRepo.findEarliestFactDate()).thenReturn(Optional.empty());
        when(checkpointRepo.findEarliestCheckpointDate()).thenReturn(Optional.of(LocalDate.of(2024, 2, 1)));
        when(capitalService.findEarliestRevaluationDate()).thenReturn(Optional.empty());

        assertThat(service.firstActivityMonth()).isEqualTo(YearMonth.of(2024, 2));
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

        List<StrategyTimelinePointDto> past = service.buildPastPoints(YearMonth.of(2026, 2), current);

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

        // Замокать список forecast-enabled категорий: 3 категории с разной историей
        Category food = Category.builder().id(UUID.randomUUID()).name("Продукты").forecastEnabled(true).build();
        Category transport = Category.builder().id(UUID.randomUUID()).name("Транспорт").forecastEnabled(true).build();
        Category fun = Category.builder().id(UUID.randomUUID()).name("Развлечения").forecastEnabled(true).build();
        when(categoryRepo.findAllByForecastEnabledTrueAndDeletedFalse()).thenReturn(List.of(food, transport, fun));

        // PredictionService возвращает разные статы
        when(predictionService.getStatsForCategory(food, 6)).thenReturn(
                new ru.selfin.backend.dto.strategy.CategoryMonthStats(food.getId(), 6,
                        new BigDecimal("35000"), new BigDecimal("30000"), new BigDecimal("40000"))); // halfIqr=5000
        when(predictionService.getStatsForCategory(transport, 6)).thenReturn(
                new ru.selfin.backend.dto.strategy.CategoryMonthStats(transport.getId(), 6,
                        new BigDecimal("10000"), new BigDecimal("8000"), new BigDecimal("12000"))); // halfIqr=2000
        when(predictionService.getStatsForCategory(fun, 6)).thenReturn(
                new ru.selfin.backend.dto.strategy.CategoryMonthStats(fun.getId(), 6,
                        new BigDecimal("15000"), new BigDecimal("10000"), new BigDecimal("20000"))); // halfIqr=5000

        // sumMedian = 60000, sumHalfIqr = sqrt(25M + 4M + 25M) = sqrt(54M) ≈ 7348

        // Recurring + planned событий на будущие месяцы — пусто (тест на чистый прогноз)
        when(eventRepo.findPlannedEventsByDateRange(any(), any())).thenReturn(List.of());

        List<StrategyTimelinePointDto> future = service.buildFuturePoints(current, 3);

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
}
