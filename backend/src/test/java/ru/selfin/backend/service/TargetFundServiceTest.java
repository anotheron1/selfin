package ru.selfin.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.selfin.backend.dto.CategoryForecastDto;
import ru.selfin.backend.dto.FundsOverviewDto;
import ru.selfin.backend.dto.MonthlyForecastDto;
import ru.selfin.backend.model.BalanceCheckpoint;
import ru.selfin.backend.model.TargetFund;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.FundStatus;
import ru.selfin.backend.repository.BalanceCheckpointRepository;
import ru.selfin.backend.repository.CategoryRepository;
import ru.selfin.backend.repository.FinancialEventRepository;
import ru.selfin.backend.repository.FundTransactionRepository;
import ru.selfin.backend.repository.TargetFundRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TargetFundServiceTest {

    private TargetFundRepository fundRepository;
    private FundTransactionRepository transactionRepository;
    private FinancialEventRepository eventRepository;
    private BalanceCheckpointRepository checkpointRepository;
    private CategoryRepository categoryRepository;
    private PredictionService predictionService;
    private TargetFundService service;

    @BeforeEach
    void setUp() {
        fundRepository = mock(TargetFundRepository.class);
        transactionRepository = mock(FundTransactionRepository.class);
        eventRepository = mock(FinancialEventRepository.class);
        checkpointRepository = mock(BalanceCheckpointRepository.class);
        categoryRepository = mock(CategoryRepository.class);
        predictionService = mock(PredictionService.class);
        service = new TargetFundService(
                fundRepository, transactionRepository,
                eventRepository, checkpointRepository, categoryRepository,
                predictionService);
        // По умолчанию просроченных обязательных планов нет
        when(eventRepository.sumOverdueMandatoryExpenses(any(), any())).thenReturn(BigDecimal.ZERO);
        // По умолчанию переводов в копилки нет (Variant B: FUND_TRANSFER = расход)
        when(eventRepository.sumAllFactByType(EventType.FUND_TRANSFER)).thenReturn(BigDecimal.ZERO);
        when(eventRepository.sumAllFactByTypeFromDate(any(), any())).thenReturn(BigDecimal.ZERO);
        when(eventRepository.findAllByDeletedFalseAndDateBetween(any(), any())).thenReturn(List.of());
        when(predictionService.forecastFromEvents(any(), any()))
                .thenReturn(new MonthlyForecastDto(List.of(), BigDecimal.ZERO));
    }

    @Test
    @DisplayName("Кармашек (Variant B): без чекпоинта = доходы − расходы − переводы в копилки (FUND_TRANSFER)")
    void pocketBalance_noCheckpoint_usesExecutedOnly() {
        when(checkpointRepository.findTopByOrderByDateDesc()).thenReturn(Optional.empty());
        when(fundRepository.findAllByDeletedFalseOrderByPriorityAsc()).thenReturn(List.of());

        when(eventRepository.sumFactExecutedByType(EventType.INCOME)).thenReturn(bd(100_000));
        when(eventRepository.sumFactExecutedByType(EventType.EXPENSE)).thenReturn(bd(30_000));
        when(eventRepository.sumAllFactByType(EventType.FUND_TRANSFER)).thenReturn(bd(5_000));

        FundsOverviewDto overview = service.getOverview();

        // 100_000 - 30_000 - 5_000 = 65_000
        assertThat(overview.pocketBalance()).isEqualByComparingTo(bd(65_000));
    }

    @Test
    @DisplayName("Кармашек (Variant B): с чекпоинтом = чекпоинт + доходы − расходы − переводы (балансы фондов НЕ вычитаются)")
    void pocketBalance_withCheckpoint_usesExecutedFromDate() {
        LocalDate cpDate = LocalDate.of(2026, 3, 1);
        BalanceCheckpoint cp = BalanceCheckpoint.builder()
                .id(UUID.randomUUID())
                .date(cpDate)
                .amount(bd(50_000))
                .build();
        when(checkpointRepository.findTopByOrderByDateDesc()).thenReturn(Optional.of(cp));

        // Фонд есть, но его баланс НЕ вычитается в Variant B
        TargetFund fund = TargetFund.builder()
                .id(UUID.randomUUID())
                .name("Отпуск")
                .currentBalance(bd(10_000))
                .targetAmount(bd(100_000))
                .priority(1)
                .status(FundStatus.FUNDING)
                .deleted(false)
                .build();
        when(fundRepository.findAllByDeletedFalseOrderByPriorityAsc()).thenReturn(List.of(fund));
        when(transactionRepository.findByFundIdAndDeletedFalseAndTransactionDateAfter(any(), any()))
                .thenReturn(List.of());

        when(eventRepository.sumFactExecutedByTypeFromDate(EventType.INCOME, cpDate)).thenReturn(bd(75_000));
        when(eventRepository.sumFactExecutedByTypeFromDate(EventType.EXPENSE, cpDate)).thenReturn(bd(20_000));
        when(eventRepository.sumAllFactByTypeFromDate(EventType.FUND_TRANSFER, cpDate)).thenReturn(bd(10_000));

        FundsOverviewDto overview = service.getOverview();

        // 50_000 + 75_000 - 20_000 - 10_000 (transfers) = 95_000
        assertThat(overview.pocketBalance()).isEqualByComparingTo(bd(95_000));
    }

    @Test
    @DisplayName("Кармашек: PLANNED события не учитываются — sumEffectiveByType не вызывается")
    void pocketBalance_doesNotCallSumEffective() {
        when(checkpointRepository.findTopByOrderByDateDesc()).thenReturn(Optional.empty());
        when(fundRepository.findAllByDeletedFalseOrderByPriorityAsc()).thenReturn(List.of());
        when(eventRepository.sumFactExecutedByType(any())).thenReturn(BigDecimal.ZERO);
        when(eventRepository.sumAllFactByType(any())).thenReturn(BigDecimal.ZERO);

        service.getOverview();

        verify(eventRepository, never()).sumEffectiveByType(any());
        verify(eventRepository, never()).sumEffectiveByTypeFromDate(any(), any());
    }

    @Test
    @DisplayName("Кармашек: вычитает просроченные обязательные планы текущего месяца")
    void pocketBalance_deductsOverdueMandatoryExpenses() {
        when(checkpointRepository.findTopByOrderByDateDesc()).thenReturn(Optional.empty());
        when(fundRepository.findAllByDeletedFalseOrderByPriorityAsc()).thenReturn(List.of());
        when(eventRepository.sumFactExecutedByType(EventType.INCOME)).thenReturn(bd(100_000));
        when(eventRepository.sumFactExecutedByType(EventType.EXPENSE)).thenReturn(bd(30_000));
        when(eventRepository.sumAllFactByType(EventType.FUND_TRANSFER)).thenReturn(BigDecimal.ZERO);
        when(eventRepository.sumOverdueMandatoryExpenses(any(), any())).thenReturn(bd(5_000));

        FundsOverviewDto overview = service.getOverview();

        // 100_000 - 30_000 - 0 (transfers) - 5_000 (overdue) = 65_000
        assertThat(overview.pocketBalance()).isEqualByComparingTo(bd(65_000));
    }

    @Test
    @DisplayName("Кармашек: linear category extrapolation adds predictionAdjustedPocket when delta >= 100")
    void getOverview_withLinearCategory_includesPredictionAdjustedPocket() {
        when(checkpointRepository.findTopByOrderByDateDesc()).thenReturn(Optional.empty());
        when(fundRepository.findAllByDeletedFalseOrderByPriorityAsc()).thenReturn(List.of());
        when(eventRepository.sumFactExecutedByType(EventType.INCOME)).thenReturn(bd(100_000));
        when(eventRepository.sumFactExecutedByType(EventType.EXPENSE)).thenReturn(bd(30_000));
        when(eventRepository.sumAllFactByType(EventType.FUND_TRANSFER)).thenReturn(BigDecimal.ZERO);

        // Month events: empty list → afterAllExpenses = pocketBalance + 0 - 0 = 70_000
        when(eventRepository.findAllByDeletedFalseAndDateBetween(any(), any())).thenReturn(List.of());

        // Linear category contributes delta = 10_000
        CategoryForecastDto linearCat = new CategoryForecastDto(
                "Прочее", new BigDecimal("5000"), BigDecimal.ZERO,
                new BigDecimal("15000"), List.of());
        when(predictionService.forecastFromEvents(any(), any()))
                .thenReturn(new MonthlyForecastDto(List.of(linearCat), new BigDecimal("10000")));

        FundsOverviewDto overview = service.getOverview();

        // pocketBalance = 70_000; afterAllExpenses = 70_000 (no future planned events)
        // adjustedPocket = 70_000 - 10_000 = 60_000
        assertThat(overview.predictionAdjustedPocket()).isNotNull();
        assertThat(overview.predictionAdjustedPocket()).isEqualByComparingTo(new BigDecimal("60000"));
        assertThat(overview.forecastContributors()).contains("Прочее (+10к)");
    }

    @Test
    @DisplayName("Кармашек: delta < 100 → predictionAdjustedPocket is null")
    void getOverview_allPlanBased_predictionAdjustedPocketIsNull() {
        when(checkpointRepository.findTopByOrderByDateDesc()).thenReturn(Optional.empty());
        when(fundRepository.findAllByDeletedFalseOrderByPriorityAsc()).thenReturn(List.of());
        when(eventRepository.sumFactExecutedByType(any())).thenReturn(BigDecimal.ZERO);
        when(eventRepository.sumAllFactByType(any())).thenReturn(BigDecimal.ZERO);
        when(eventRepository.findAllByDeletedFalseAndDateBetween(any(), any())).thenReturn(List.of());

        when(predictionService.forecastFromEvents(any(), any()))
                .thenReturn(new MonthlyForecastDto(List.of(), BigDecimal.ZERO));

        FundsOverviewDto overview = service.getOverview();

        assertThat(overview.predictionAdjustedPocket()).isNull();
        assertThat(overview.forecastContributors()).isEmpty();
    }

    private static BigDecimal bd(long value) {
        return BigDecimal.valueOf(value);
    }
}
