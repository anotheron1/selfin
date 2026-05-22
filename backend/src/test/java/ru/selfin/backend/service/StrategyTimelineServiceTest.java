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
}
