package ru.selfin.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.selfin.backend.dto.MonthlyForecastDto;
import ru.selfin.backend.dto.pocket.PocketScope;
import ru.selfin.backend.dto.pocket.PocketSettingsDto;
import ru.selfin.backend.model.BalanceCheckpoint;
import ru.selfin.backend.repository.BalanceCheckpointRepository;
import ru.selfin.backend.repository.CategoryRepository;
import ru.selfin.backend.repository.FinancialEventRepository;
import ru.selfin.backend.repository.TargetFundRepository;
import ru.selfin.backend.testsupport.AccountFixtures;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ЭТАЛОН поведения {@link PocketInputAssembler} ДО подмены источника якоря на
 * {@code AccountBalanceService} (ANO-9, Task 2.2, «Поправки после ревью Task 2.1» п.1).
 *
 * <p><b>Это не TDD-тест.</b> Он обязан быть зелёным на сегодняшнем коде без единой правки —
 * фиксирует, а не разрабатывает. {@link PocketMigrationRegressionTest} прикрывает только
 * {@link PocketEngine} (конструирует {@code PocketInput} напрямую), а подмена якоря в Task 2.2
 * происходит в сборщике — ровно там, где этот тест и живёт.
 *
 * <p>Два случая, оба меняются молча при переходе на {@code accountBalanceService.anchorAt(...)}:
 * <ol>
 *   <li>{@code checkpointRepository.findTopByOrderByDateDesc()} НЕ ограничен датой — чекпоинт
 *       с датой ПОЗЖЕ {@code asOfDate} становится якорём. {@code anchorAt(a, t)} ограничен
 *       {@code date ≤ t} и такой чекпоинт отбросит.</li>
 *   <li>При полном отсутствии чекпоинта сборщик запрашивает события с {@code EPOCH (2000-01-01)}
 *       на нулевую базу — движок суммирует их все. {@code balanceAt} без якоря возвращает ноль
 *       и фактов не запрашивает вовсе.</li>
 * </ol>
 *
 * <p>После Task 2.2 оба теста, скорее всего, покраснеют — по прямому указанию задачи оба
 * поведения СОХРАНЯЮТСЯ (см. отчёт реализации), поэтому ожидание — что они останутся зелёными
 * и после перехода на счета. Если один из них покраснел, это осознанное решение поменять
 * семантику якоря, а не молчаливая порча — история коммитов должна это показывать.
 */
class PocketInputAssemblerAnchorRegressionTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 3, 1);
    private static final PocketScope MONTHS_6 = new PocketScope(PocketScope.Type.MONTHS, 6, null);
    private static final LocalDate EPOCH = LocalDate.of(2000, 1, 1);

    private FinancialEventRepository eventRepository;
    private TargetFundRepository fundRepository;
    private CategoryRepository categoryRepository;
    private UserSettingsService settingsService;
    private PredictionService predictionService;
    private RecurringRuleService recurringRuleService;

    @BeforeEach
    void setUp() {
        eventRepository = mock(FinancialEventRepository.class);
        fundRepository = mock(TargetFundRepository.class);
        categoryRepository = mock(CategoryRepository.class);
        settingsService = mock(UserSettingsService.class);
        predictionService = mock(PredictionService.class);
        recurringRuleService = mock(RecurringRuleService.class);

        when(eventRepository.findAllByDeletedFalseAndDateBetween(any(), any())).thenReturn(List.of());
        when(eventRepository.findOverdueMandatoryExpenses(any(), any())).thenReturn(List.of());
        when(eventRepository.findByWishlistStatusInAndDeletedFalse(any())).thenReturn(List.of());
        when(eventRepository.findPlannedIncomeDates(any(), any(), anyBoolean(), any())).thenReturn(List.of());
        when(settingsService.getPocketSettings()).thenReturn(new PocketSettingsDto(BigDecimal.ZERO));
        when(predictionService.forecastFromEvents(any(), any()))
                .thenReturn(new MonthlyForecastDto(List.of(), BigDecimal.ZERO));
        when(fundRepository.findByWishlistStatusAndDeletedFalse(ru.selfin.backend.model.enums.WishlistStatus.FIXED))
                .thenReturn(List.of());
    }

    private PocketInputAssembler assemblerWith(BalanceCheckpointRepository checkpointRepository) {
        return new PocketInputAssembler(eventRepository, checkpointRepository,
                settingsService, predictionService, recurringRuleService, fundRepository, categoryRepository);
    }

    @Test
    @DisplayName("ЭТАЛОН: чекпоинт с датой ПОЗЖЕ asOfDate сейчас становится якорём (findTopByOrderByDateDesc не ограничен датой)")
    void legacyBehavior_checkpointFromTheFuture_becomesAnchor() {
        BalanceCheckpointRepository checkpointRepository = mock(BalanceCheckpointRepository.class);
        LocalDate futureDate = TODAY.plusDays(5); // после asOfDate, до которого anchorAt(a,t) бы не дотянулся
        BalanceCheckpoint futureCheckpoint = BalanceCheckpoint.builder()
                .id(UUID.randomUUID()).date(futureDate).amount(BigDecimal.valueOf(77_000))
                .account(AccountFixtures.defaultAccount())
                .build();
        when(checkpointRepository.findTopByOrderByDateDesc()).thenReturn(Optional.of(futureCheckpoint));

        PocketInputAssembler.Assembled result = assemblerWith(checkpointRepository).build(MONTHS_6, TODAY);

        assertThat(result.input().checkpointAmount()).isEqualByComparingTo(BigDecimal.valueOf(77_000));
        assertThat(result.input().checkpointDate()).isEqualTo(futureDate);

        // Просрочка тоже сейчас резолвится от даты этого «будущего» чекпоинта — тот же источник.
        verify(eventRepository).findOverdueMandatoryExpenses(eq(futureDate), eq(TODAY));
    }

    @Test
    @DisplayName("ЭТАЛОН: полное отсутствие чекпоинта — события запрашиваются с 2000 года, база ноль")
    void legacyBehavior_noCheckpointAtAll_queriesEventsFromEpochOnZeroBase() {
        BalanceCheckpointRepository checkpointRepository = mock(BalanceCheckpointRepository.class);
        when(checkpointRepository.findTopByOrderByDateDesc()).thenReturn(Optional.empty());

        LocalDate horizonEnd = TODAY.plusMonths(6);
        LocalDate trajectoryEnd = PocketEngine.trajectoryEnd(TODAY, horizonEnd);

        PocketInputAssembler.Assembled result = assemblerWith(checkpointRepository).build(MONTHS_6, TODAY);

        assertThat(result.input().checkpointAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.input().checkpointDate()).isNull();

        // Диапазон событий для баланса и траектории — от EPOCH, а не от даты какого-либо якоря.
        verify(eventRepository).findAllByDeletedFalseAndDateBetween(eq(EPOCH), eq(trajectoryEnd));
        // Просрочка резолвится от той же EPOCH-границы (ANO-28: "без якоря — с начала времён").
        verify(eventRepository).findOverdueMandatoryExpenses(eq(EPOCH), eq(TODAY));
    }
}
