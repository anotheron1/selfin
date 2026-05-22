package ru.selfin.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.selfin.backend.repository.BalanceCheckpointRepository;
import ru.selfin.backend.repository.CategoryRepository;
import ru.selfin.backend.repository.FinancialEventRepository;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
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
}
