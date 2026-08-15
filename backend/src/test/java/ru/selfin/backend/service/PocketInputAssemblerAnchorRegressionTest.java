package ru.selfin.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.selfin.backend.dto.MonthlyForecastDto;
import ru.selfin.backend.dto.pocket.PocketScope;
import ru.selfin.backend.dto.pocket.PocketSettingsDto;
import ru.selfin.backend.repository.CategoryRepository;
import ru.selfin.backend.repository.FinancialEventRepository;
import ru.selfin.backend.repository.TargetFundRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ЭТАЛОН поведения {@link PocketInputAssembler} для случая «чекпоинта нет вовсе» (ANO-28) —
 * единственный краевой случай источника якоря, который эта правка (ANO-9, ревью Task 2.2)
 * сознательно СОХРАНЯЕТ. Тест обязан быть зелёным и до, и после правки без изменения ожиданий.
 *
 * <p><b>История файла.</b> Изначально здесь фиксировались ДВА случая: этот и «чекпоинт с датой
 * позже {@code asOfDate} становится якорём». Второй был ошибочно объявлен подлежащим сохранению
 * («задача обязана быть неотличимой снаружи при одном счёте») — разбор на реальных данных
 * показал, что checkpoint-из-будущего вообще недостижим через приложение:
 * {@code BalanceCheckpointCreateDto.date} помечен {@code @PastOrPresent}, валидация стоит и на
 * {@code POST}, и на {@code PUT /api/v1/checkpoints/{id}} (оба со {@code @Valid}), а
 * {@code asOfDate} в сборщике всегда равен сегодняшнему дню. Хуже: при этом состоянии окно
 * поиска просрочки переворачивалось (см. удалённый тест
 * {@code legacyBehavior_checkpointFromTheFuture_becomesAnchor}), и старая версия этого файла
 * зафиксировала перевёрнутый диапазон КАК ЭТАЛОН — то есть закрепляла симптом дефекта, а не
 * защищала пользователя. Правильное решение по этому случаю — Task 2.2 переходит на
 * account-scoped якорь ({@code accountBalanceService.anchorAt(defaultAccount, asOfDate)},
 * ограничен {@code date ≤ asOfDate}), и тест на него убран без замены.
 *
 * <p>Случай «чекпоинта нет вовсе» — другое дело: это осознанное поведение ANO-28 (без единого
 * введённого остатка вся история фактов должна остаться в числе, а не обнулиться), и оно
 * действительно достижимо (пользователь просто ничего не вводил). Эта правка его не трогает.
 */
class PocketInputAssemblerAnchorRegressionTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 3, 1);
    private static final PocketScope MONTHS_6 = new PocketScope(PocketScope.Type.MONTHS, 6, null);
    private static final LocalDate EPOCH = LocalDate.of(2000, 1, 1);

    private FinancialEventRepository eventRepository;
    private TargetFundRepository fundRepository;
    private CategoryRepository categoryRepository;
    private AccountBalanceService accountBalanceService;
    private UserSettingsService settingsService;
    private PredictionService predictionService;
    private RecurringRuleService recurringRuleService;

    @BeforeEach
    void setUp() {
        eventRepository = mock(FinancialEventRepository.class);
        fundRepository = mock(TargetFundRepository.class);
        categoryRepository = mock(CategoryRepository.class);
        accountBalanceService = mock(AccountBalanceService.class);
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
        // Этот тест — про checkpointAmount/checkpointDate/диапазон событий, не про суммы
        // прочих счетов (ANO-9 Task 2.2 покрыта отдельно в PocketInputAssemblerTest и
        // PocketInputAssemblerOwnerScenarioTest) — снимок счетов нейтрален.
        when(accountBalanceService.snapshot(any(), any()))
                .thenReturn(new AccountBalanceService.Snapshot(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        // Дефолтного счёта нет (Mockito отдаёт Optional.empty() на незастабленный
        // Optional-метод) — ровно сценарий «чекпоинта нет вовсе», который здесь фиксируется.
    }

    private PocketInputAssembler assembler() {
        return new PocketInputAssembler(eventRepository,
                settingsService, predictionService, recurringRuleService, fundRepository, categoryRepository,
                accountBalanceService);
    }

    @Test
    @DisplayName("ЭТАЛОН: полное отсутствие чекпоинта — события запрашиваются с 2000 года, база ноль (ANO-28)")
    void legacyBehavior_noCheckpointAtAll_queriesEventsFromEpochOnZeroBase() {
        LocalDate horizonEnd = TODAY.plusMonths(6);
        LocalDate trajectoryEnd = PocketEngine.trajectoryEnd(TODAY, horizonEnd);

        PocketInputAssembler.Assembled result = assembler().build(MONTHS_6, TODAY);

        assertThat(result.input().checkpointAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.input().checkpointDate()).isNull();

        // Диапазон событий для баланса и траектории — от EPOCH, а не от даты какого-либо якоря.
        verify(eventRepository).findAllByDeletedFalseAndDateBetween(eq(EPOCH), eq(trajectoryEnd));
        // Просрочка резолвится от той же EPOCH-границы (ANO-28: "без якоря — с начала времён").
        verify(eventRepository).findOverdueMandatoryExpenses(eq(EPOCH), eq(TODAY));
    }
}
