package ru.selfin.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.selfin.backend.dto.CategoryForecastDto;
import ru.selfin.backend.dto.DashboardDto;
import ru.selfin.backend.dto.MonthlyForecastDto;
import ru.selfin.backend.model.BalanceCheckpoint;
import ru.selfin.backend.model.Category;
import ru.selfin.backend.model.EventKind;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.enums.CategoryType;
import ru.selfin.backend.model.enums.EventStatus;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.Priority;
import ru.selfin.backend.repository.BalanceCheckpointRepository;
import ru.selfin.backend.repository.FinancialEventRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit-тесты ключевой финансовой математики DashboardService.
 * Без подключения к БД (чистый Mockito + список событий).
 *
 * <p>Сценарии тестирования:
 * <ul>
 *   <li>Текущий баланс (с чекпоинтом и без)</li>
 *   <li>Прогноз конца месяца</li>
 *   <li>Обнаружение кассового разрыва</li>
 *   <li>Двойной зарплатный горизонт (balanceBeforeNextSalary / balanceAfterNextSalary / balanceBeforeSecondSalary)</li>
 *   <li>Прогресс-бары категорий</li>
 * </ul>
 */
class DashboardServiceTest {

    private FinancialEventRepository eventRepository;
    private BalanceCheckpointRepository checkpointRepository;
    private PredictionService predictionService;
    private DashboardService dashboardService;

    /** Тестовая «сегодня» — 15 марта 2026, середина месяца. */
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 15);

    @BeforeEach
    void setUp() {
        eventRepository = mock(FinancialEventRepository.class);
        checkpointRepository = mock(BalanceCheckpointRepository.class);
        predictionService = mock(PredictionService.class);
        // По умолчанию чекпоинтов нет — баланс от нуля
        when(checkpointRepository.findTopByOrderByDateDesc()).thenReturn(Optional.empty());
        // По умолчанию просроченных обязательных планов нет
        when(eventRepository.sumOverdueMandatoryExpenses(any(), any())).thenReturn(BigDecimal.ZERO);
        // По умолчанию прогноз пустой
        when(predictionService.forecastFromEvents(any(), any()))
                .thenReturn(new MonthlyForecastDto(List.of(), BigDecimal.ZERO));
        dashboardService = new DashboardService(eventRepository, checkpointRepository, predictionService);
    }

    // ─── Вспомогательный билдер событий ───────────────────────────────────────

    /**
     * Создаёт тестовое событие, корректно моделирующее V12 plan-fact split:
     * если fact задан — FACT-запись (eventKind=FACT, factAmount=fact);
     * если fact == null — PLAN-запись (eventKind=PLAN, plannedAmount=planned).
     */
    private FinancialEvent event(EventType type, LocalDate date,
            BigDecimal planned, BigDecimal fact) {
        Category category = Category.builder()
                .id(UUID.randomUUID())
                .name(type == EventType.INCOME ? "Зарплата" : "Еда")
                .type(type == EventType.INCOME ? CategoryType.INCOME : CategoryType.EXPENSE)
                .build();
        if (fact != null) {
            // FACT-запись: factAmount задан, plannedAmount = null (V12)
            return FinancialEvent.builder()
                    .id(UUID.randomUUID())
                    .date(date)
                    .category(category)
                    .type(type)
                    .eventKind(EventKind.FACT)
                    .plannedAmount(null)
                    .factAmount(fact)
                    .status(EventStatus.EXECUTED)
                    .priority(Priority.MEDIUM)
                    .deleted(false)
                    .build();
        } else {
            // PLAN-запись: plannedAmount задан, factAmount = null (V12)
            return FinancialEvent.builder()
                    .id(UUID.randomUUID())
                    .date(date)
                    .category(category)
                    .type(type)
                    .eventKind(EventKind.PLAN)
                    .plannedAmount(planned)
                    .factAmount(null)
                    .status(EventStatus.PLANNED)
                    .priority(Priority.MEDIUM)
                    .deleted(false)
                    .build();
        }
    }

    private BalanceCheckpoint checkpoint(LocalDate date, long amount) {
        return BalanceCheckpoint.builder()
                .id(UUID.randomUUID())
                .date(date)
                .amount(bd(amount))
                .build();
    }

    /**
     * Настраивает mock репозитория для стандартного сценария двух зарплат:
     * сегодня 15.03, месячные события заданы, горизонтные события заданы.
     */
    private void stubTwoSalaryScenario(List<FinancialEvent> monthEvents, List<FinancialEvent> horizonEvents) {
        LocalDate monthStart = TODAY.withDayOfMonth(1);
        LocalDate monthEnd = TODAY.withDayOfMonth(TODAY.lengthOfMonth());
        LocalDate horizonEnd = TODAY.plusDays(DashboardService.FORECAST_HORIZON_DAYS);

        when(eventRepository.findAllByDeletedFalseAndDateBetween(monthStart, monthEnd))
                .thenReturn(monthEvents);
        when(eventRepository.findAllByDeletedFalseAndDateBetween(TODAY, horizonEnd))
                .thenReturn(horizonEvents);
    }

    // ─── currentBalance (без чекпоинта) ───────────────────────────────────────

    @Test
    @DisplayName("Текущий баланс = факт доходов − факт расходов (до сегодня включительно)")
    void currentBalance_usesFactAmount() {
        LocalDate monthStart = TODAY.withDayOfMonth(1);
        LocalDate monthEnd = TODAY.withDayOfMonth(TODAY.lengthOfMonth());

        List<FinancialEvent> events = List.of(
                event(EventType.INCOME, LocalDate.of(2026, 3, 5), bd(100_000), bd(100_000)),
                event(EventType.EXPENSE, LocalDate.of(2026, 3, 10), bd(20_000), bd(18_000)), // сэкономил
                event(EventType.EXPENSE, LocalDate.of(2026, 3, 20), bd(15_000), null) // будущий — не влияет
        );
        when(eventRepository.findAllByDeletedFalseAndDateBetween(monthStart, monthEnd))
                .thenReturn(events);

        DashboardDto dto = dashboardService.getDashboard(TODAY);

        // 100_000 - 18_000 = 82_000
        assertThat(dto.currentBalance()).isEqualByComparingTo(bd(82_000));
    }

    @Test
    @DisplayName("Текущий баланс: PLANNED события (без factAmount) не учитываются — ни в балансе, ни в прогнозе (прошедшие)")
    void currentBalance_plannedEventsIgnored() {
        LocalDate monthStart = TODAY.withDayOfMonth(1);
        LocalDate monthEnd = TODAY.withDayOfMonth(TODAY.lengthOfMonth());

        List<FinancialEvent> events = List.of(
                event(EventType.INCOME, LocalDate.of(2026, 3, 1), bd(50_000), null),
                event(EventType.EXPENSE, LocalDate.of(2026, 3, 5), bd(10_000), null));
        when(eventRepository.findAllByDeletedFalseAndDateBetween(monthStart, monthEnd))
                .thenReturn(events);

        DashboardDto dto = dashboardService.getDashboard(TODAY);

        assertThat(dto.currentBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(dto.endOfMonthForecast()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("Прогноз EOM включает сегодняшние и будущие PLANNED события, но не прошлые")
    void endOfMonthForecast_includesOnlyFutureAndTodayPlanned() {
        LocalDate monthStart = TODAY.withDayOfMonth(1);
        LocalDate monthEnd = TODAY.withDayOfMonth(TODAY.lengthOfMonth());

        List<FinancialEvent> events = List.of(
                event(EventType.INCOME, LocalDate.of(2026, 3, 5), bd(100_000), bd(100_000)),
                event(EventType.EXPENSE, LocalDate.of(2026, 3, 10), bd(5_000), null),
                event(EventType.EXPENSE, TODAY, bd(3_000), null),
                event(EventType.EXPENSE, LocalDate.of(2026, 3, 20), bd(20_000), null));
        when(eventRepository.findAllByDeletedFalseAndDateBetween(monthStart, monthEnd))
                .thenReturn(events);

        DashboardDto dto = dashboardService.getDashboard(TODAY);

        assertThat(dto.currentBalance()).isEqualByComparingTo(bd(100_000));
        // forecastDelta = -3_000 (сегодня) - 20_000 (будущее) = -23_000
        assertThat(dto.endOfMonthForecast()).isEqualByComparingTo(bd(77_000));
    }

    @Test
    @DisplayName("Текущий баланс равен нулю при отсутствии событий и чекпоинтов")
    void currentBalance_emptyEvents() {
        LocalDate monthStart = TODAY.withDayOfMonth(1);
        LocalDate monthEnd = TODAY.withDayOfMonth(TODAY.lengthOfMonth());
        when(eventRepository.findAllByDeletedFalseAndDateBetween(monthStart, monthEnd))
                .thenReturn(List.of());

        DashboardDto dto = dashboardService.getDashboard(TODAY);

        assertThat(dto.currentBalance()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ─── currentBalance (с чекпоинтом) ────────────────────────────────────────

    @Test
    @DisplayName("Чекпоинт в текущем месяце: баланс = чекпоинт + события от даты чекпоинта до сегодня")
    void currentBalance_withCheckpointInCurrentMonth() {
        LocalDate monthStart = TODAY.withDayOfMonth(1);
        LocalDate monthEnd = TODAY.withDayOfMonth(TODAY.lengthOfMonth());
        LocalDate cpDate = LocalDate.of(2026, 3, 10);

        when(checkpointRepository.findTopByOrderByDateDesc())
                .thenReturn(Optional.of(checkpoint(cpDate, 50_000)));

        List<FinancialEvent> events = List.of(
                event(EventType.EXPENSE, LocalDate.of(2026, 3, 5), bd(10_000), bd(10_000)),
                event(EventType.EXPENSE, LocalDate.of(2026, 3, 12), bd(5_000), bd(5_000)));
        when(eventRepository.findAllByDeletedFalseAndDateBetween(monthStart, monthEnd))
                .thenReturn(events);

        DashboardDto dto = dashboardService.getDashboard(TODAY);

        // 50_000 (checkpoint) - 5_000 (event 12.03) = 45_000
        assertThat(dto.currentBalance()).isEqualByComparingTo(bd(45_000));
    }

    @Test
    @DisplayName("Чекпоинт до текущего месяца: мостик прибавляется к стартовому балансу")
    void currentBalance_withCheckpointBeforeCurrentMonth() {
        LocalDate monthStart = TODAY.withDayOfMonth(1);
        LocalDate monthEnd = TODAY.withDayOfMonth(TODAY.lengthOfMonth());
        LocalDate cpDate = LocalDate.of(2026, 2, 20);

        when(checkpointRepository.findTopByOrderByDateDesc())
                .thenReturn(Optional.of(checkpoint(cpDate, 40_000)));

        when(eventRepository.findAllByDeletedFalseAndDateBetween(cpDate, monthStart.minusDays(1)))
                .thenReturn(List.of(
                        event(EventType.INCOME, LocalDate.of(2026, 2, 25), bd(20_000), bd(20_000))));

        when(eventRepository.findAllByDeletedFalseAndDateBetween(monthStart, monthEnd))
                .thenReturn(List.of(
                        event(EventType.EXPENSE, LocalDate.of(2026, 3, 5), bd(10_000), bd(10_000))));

        DashboardDto dto = dashboardService.getDashboard(TODAY);

        // 40_000 + 20_000 (bridge) - 10_000 (март) = 50_000
        assertThat(dto.currentBalance()).isEqualByComparingTo(bd(50_000));
    }

    @Test
    @DisplayName("Чекпоинт без событий: баланс равен сумме чекпоинта")
    void currentBalance_onlyCheckpoint_noEvents() {
        LocalDate monthStart = TODAY.withDayOfMonth(1);
        LocalDate monthEnd = TODAY.withDayOfMonth(TODAY.lengthOfMonth());

        when(checkpointRepository.findTopByOrderByDateDesc())
                .thenReturn(Optional.of(checkpoint(LocalDate.of(2026, 3, 1), 99_000)));
        when(eventRepository.findAllByDeletedFalseAndDateBetween(monthStart, monthEnd))
                .thenReturn(List.of());

        DashboardDto dto = dashboardService.getDashboard(TODAY);

        assertThat(dto.currentBalance()).isEqualByComparingTo(bd(99_000));
    }

    // ─── endOfMonthForecast ────────────────────────────────────────────────────

    @Test
    @DisplayName("Прогноз EOM = текущий баланс + план будущих событий")
    void endOfMonthForecast_addsPlannedFuture() {
        LocalDate monthStart = TODAY.withDayOfMonth(1);
        LocalDate monthEnd = TODAY.withDayOfMonth(TODAY.lengthOfMonth());

        List<FinancialEvent> events = List.of(
                event(EventType.INCOME, LocalDate.of(2026, 3, 5), bd(80_000), bd(80_000)),
                event(EventType.EXPENSE, LocalDate.of(2026, 3, 20), bd(30_000), null),
                event(EventType.INCOME, LocalDate.of(2026, 3, 25), bd(10_000), null)
        );
        when(eventRepository.findAllByDeletedFalseAndDateBetween(monthStart, monthEnd))
                .thenReturn(events);

        DashboardDto dto = dashboardService.getDashboard(TODAY);

        // currentBalance = 80_000; forecastDelta = -30_000 + 10_000 = -20_000; EOM = 60_000
        assertThat(dto.endOfMonthForecast()).isEqualByComparingTo(bd(60_000));
    }

    @Test
    @DisplayName("Прогноз конца месяца: вычитает просроченные обязательные планы")
    void endOfMonthForecast_deductsOverdueMandatoryExpenses() {
        LocalDate asOfDate = TODAY;
        LocalDate monthStart = asOfDate.withDayOfMonth(1);
        LocalDate monthEnd = asOfDate.withDayOfMonth(asOfDate.lengthOfMonth());

        when(eventRepository.findAllByDeletedFalseAndDateBetween(monthStart, monthEnd))
                .thenReturn(List.of());
        when(eventRepository.sumOverdueMandatoryExpenses(monthStart, asOfDate))
                .thenReturn(new BigDecimal("2000"));

        DashboardDto result = dashboardService.getDashboard(asOfDate);

        // currentBalance = 0 (no events), forecastDelta = 0, overdueMandate = -2000
        assertThat(result.endOfMonthForecast()).isEqualByComparingTo(new BigDecimal("-2000"));
    }

    // ─── detectCashGap ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("Кассовый разрыв: обнаруживает первый день с отрицательным балансом")
    void cashGap_detectsFirstNegativeDay() {
        List<FinancialEvent> monthEvents = List.of(
                event(EventType.INCOME, LocalDate.of(2026, 3, 1), bd(5_000), bd(5_000))
        );
        List<FinancialEvent> horizonEvents = List.of(
                event(EventType.EXPENSE, LocalDate.of(2026, 3, 17), bd(10_000), null)
        );
        stubTwoSalaryScenario(monthEvents, horizonEvents);

        DashboardDto dto = dashboardService.getDashboard(TODAY);

        assertThat(dto.cashGapAlert()).isNotNull();
        assertThat(dto.cashGapAlert().gapDate()).isEqualTo(LocalDate.of(2026, 3, 17));
        assertThat(dto.cashGapAlert().gapAmount()).isNegative();
    }

    @Test
    @DisplayName("Кассовый разрыв: null если баланс всегда положительный")
    void cashGap_nullWhenNoGap() {
        List<FinancialEvent> monthEvents = List.of(
                event(EventType.INCOME, LocalDate.of(2026, 3, 1), bd(100_000), bd(100_000))
        );
        List<FinancialEvent> horizonEvents = List.of(
                event(EventType.EXPENSE, LocalDate.of(2026, 3, 20), bd(50_000), null)
        );
        stubTwoSalaryScenario(monthEvents, horizonEvents);

        DashboardDto dto = dashboardService.getDashboard(TODAY);

        assertThat(dto.cashGapAlert()).isNull();
    }

    // ─── Двойной зарплатный горизонт ──────────────────────────────────────────

    /**
     * Базовый сценарий двух зарплат.
     * Сегодня 15.03, текущий баланс 20 000 ₽.
     * Расходы 16-27 марта: 15 000 ₽.
     * Зп 28 марта: 50 000 ₽. Расходы 28 марта: 3 000 ₽.
     * Расходы 29 марта - 14 апреля: 40 000 ₽.
     * Вторая зп 15 апреля: 50 000 ₽.
     *
     * Ожидаем:
     *   balanceBeforeNextSalary = 20_000 - 15_000 = 5_000
     *   balanceAfterNextSalary  = 5_000 + 50_000 - 3_000 = 52_000
     *   balanceBeforeSecondSalary = 52_000 - 40_000 = 12_000
     */
    @Test
    @DisplayName("Два зарплатных горизонта: balanceBeforeNextSalary / balanceAfterNextSalary / balanceBeforeSecondSalary")
    void twoSalaryHorizons_basicScenario() {
        LocalDate mar28 = LocalDate.of(2026, 3, 28);
        LocalDate apr15 = LocalDate.of(2026, 4, 15);

        // Выполненные события — в месячный блок (март)
        List<FinancialEvent> monthEvents = List.of(
                event(EventType.INCOME, LocalDate.of(2026, 3, 1), bd(20_000), bd(20_000)) // стартовый доход
        );
        // Горизонтные события — всё будущее
        List<FinancialEvent> horizonEvents = List.of(
                event(EventType.EXPENSE, LocalDate.of(2026, 3, 20), bd(15_000), null),  // расход до зп
                event(EventType.INCOME, mar28, bd(50_000), null),                        // первая зп
                event(EventType.EXPENSE, mar28, bd(3_000), null),                        // расход в день зп
                event(EventType.EXPENSE, LocalDate.of(2026, 4, 10), bd(40_000), null),  // расход между зп
                event(EventType.INCOME, apr15, bd(50_000), null)                         // вторая зп
        );
        stubTwoSalaryScenario(monthEvents, horizonEvents);

        DashboardDto dto = dashboardService.getDashboard(TODAY);

        assertThat(dto.nextSalaryDate()).isEqualTo(mar28);
        assertThat(dto.secondSalaryDate()).isEqualTo(apr15);
        assertThat(dto.balanceBeforeNextSalary()).isEqualByComparingTo(bd(5_000));
        assertThat(dto.balanceAfterNextSalary()).isEqualByComparingTo(bd(52_000));
        assertThat(dto.balanceBeforeSecondSalary()).isEqualByComparingTo(bd(12_000));
    }

    @Test
    @DisplayName("balanceBeforeNextSalary: событие в точный день зп не включается в 'до'")
    void balanceBeforeNextSalary_excludesSalaryDayEvents() {
        LocalDate mar28 = LocalDate.of(2026, 3, 28);

        // Текущий баланс = 0, только событие точно в день зп
        List<FinancialEvent> monthEvents = List.of();
        List<FinancialEvent> horizonEvents = List.of(
                event(EventType.INCOME, mar28, bd(50_000), null)
        );
        stubTwoSalaryScenario(monthEvents, horizonEvents);

        DashboardDto dto = dashboardService.getDashboard(TODAY);

        // До зп (27 марта) = 0, нет расходов между 15 и 27 марта
        assertThat(dto.balanceBeforeNextSalary()).isEqualByComparingTo(BigDecimal.ZERO);
        // После зп = 0 + 50_000
        assertThat(dto.balanceAfterNextSalary()).isEqualByComparingTo(bd(50_000));
    }

    @Test
    @DisplayName("balanceAfterNextSalary: включает все события в день зп — и доход, и расходы")
    void balanceAfterNextSalary_includesAllSameDayEvents() {
        LocalDate mar28 = LocalDate.of(2026, 3, 28);

        List<FinancialEvent> monthEvents = List.of();
        List<FinancialEvent> horizonEvents = List.of(
                event(EventType.INCOME,  mar28, bd(50_000), null),
                event(EventType.EXPENSE, mar28, bd(10_000), null),  // аренда в день зп
                event(EventType.EXPENSE, mar28, bd(5_000),  null)   // ещё расход
        );
        stubTwoSalaryScenario(monthEvents, horizonEvents);

        DashboardDto dto = dashboardService.getDashboard(TODAY);

        // После = 0 + 50_000 - 10_000 - 5_000 = 35_000
        assertThat(dto.balanceAfterNextSalary()).isEqualByComparingTo(bd(35_000));
    }

    @Test
    @DisplayName("Если только одна зп в горизонте: secondSalaryDate и balanceBeforeSecondSalary — null")
    void twoSalaryHorizons_onlyOneSalary_secondIsNull() {
        LocalDate mar28 = LocalDate.of(2026, 3, 28);

        List<FinancialEvent> monthEvents = List.of();
        List<FinancialEvent> horizonEvents = List.of(
                event(EventType.INCOME, mar28, bd(50_000), null)
        );
        stubTwoSalaryScenario(monthEvents, horizonEvents);

        DashboardDto dto = dashboardService.getDashboard(TODAY);

        assertThat(dto.nextSalaryDate()).isEqualTo(mar28);
        assertThat(dto.secondSalaryDate()).isNull();
        assertThat(dto.balanceBeforeSecondSalary()).isNull();
    }

    @Test
    @DisplayName("Если нет зп в горизонте: все зп-поля null, используется endOfMonthForecast")
    void twoSalaryHorizons_noSalary_allNull() {
        List<FinancialEvent> monthEvents = List.of();
        List<FinancialEvent> horizonEvents = List.of();
        stubTwoSalaryScenario(monthEvents, horizonEvents);

        DashboardDto dto = dashboardService.getDashboard(TODAY);

        assertThat(dto.nextSalaryDate()).isNull();
        assertThat(dto.balanceBeforeNextSalary()).isNull();
        assertThat(dto.balanceAfterNextSalary()).isNull();
        assertThat(dto.secondSalaryDate()).isNull();
        assertThat(dto.balanceBeforeSecondSalary()).isNull();
    }

    @Test
    @DisplayName("Кассовый разрыв обнаруживается между первой и второй зп (расширенный горизонт)")
    void cashGap_detectedBetweenTwoSalaries() {
        LocalDate mar28 = LocalDate.of(2026, 3, 28);
        LocalDate apr15 = LocalDate.of(2026, 4, 15);

        // Баланс сейчас = 0, зп 28 марта 5000, апрельские расходы превысят зп
        List<FinancialEvent> monthEvents = List.of();
        List<FinancialEvent> horizonEvents = List.of(
                event(EventType.INCOME, mar28, bd(5_000), null),
                event(EventType.EXPENSE, LocalDate.of(2026, 4, 5), bd(8_000), null), // уходим в минус
                event(EventType.INCOME, apr15, bd(50_000), null)
        );
        stubTwoSalaryScenario(monthEvents, horizonEvents);

        DashboardDto dto = dashboardService.getDashboard(TODAY);

        // Кассовый разрыв 5.04: 0 + 5_000 (зп 28.03) - 8_000 (05.04) = -3_000
        assertThat(dto.cashGapAlert()).isNotNull();
        assertThat(dto.cashGapAlert().gapDate()).isEqualTo(LocalDate.of(2026, 4, 5));
        assertThat(dto.cashGapAlert().gapAmount()).isNegative();
    }

    @Test
    @DisplayName("Сегодняшние неисполненные расходы входят в balanceBeforeNextSalary")
    void balanceBeforeNextSalary_includesTodayUnexecutedExpenses() {
        LocalDate mar28 = LocalDate.of(2026, 3, 28);

        // Баланс сейчас: 10_000 (исполненный доход в начале месяца)
        List<FinancialEvent> monthEvents = List.of(
                event(EventType.INCOME, LocalDate.of(2026, 3, 1), bd(10_000), bd(10_000))
        );
        // Неисполненный расход сегодня — должен войти в balanceBeforeNextSalary
        List<FinancialEvent> horizonEvents = List.of(
                event(EventType.EXPENSE, TODAY, bd(2_000), null),  // сегодня, не исполнено
                event(EventType.INCOME, mar28, bd(50_000), null)
        );
        stubTwoSalaryScenario(monthEvents, horizonEvents);

        DashboardDto dto = dashboardService.getDashboard(TODAY);

        // balanceBeforeNextSalary = 10_000 - 2_000 = 8_000
        assertThat(dto.balanceBeforeNextSalary()).isEqualByComparingTo(bd(8_000));
    }

    // ─── progressBars ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("Прогресс-бары: процент = факт/план × 100 по категории расходов")
    void progressBars_calculatesPercentage() {
        LocalDate monthStart = TODAY.withDayOfMonth(1);
        LocalDate monthEnd = TODAY.withDayOfMonth(TODAY.lengthOfMonth());

        // V12: PLAN (planned=20000) + FACT (fact=15000) = два отдельных события
        List<FinancialEvent> events = List.of(
                event(EventType.EXPENSE, LocalDate.of(2026, 3, 5), bd(20_000), null),
                event(EventType.EXPENSE, LocalDate.of(2026, 3, 5), null, bd(15_000)));
        when(eventRepository.findAllByDeletedFalseAndDateBetween(monthStart, monthEnd))
                .thenReturn(events);

        DashboardDto dto = dashboardService.getDashboard(TODAY);

        assertThat(dto.progressBars()).hasSize(1);
        DashboardDto.CategoryProgressBar bar = dto.progressBars().get(0);
        assertThat(bar.plannedLimit()).isEqualByComparingTo(bd(20_000));
        assertThat(bar.currentFact()).isEqualByComparingTo(bd(15_000));
        assertThat(bar.percentage()).isEqualTo(75);
    }

    // ─── forecast integration ──────────────────────────────────────────────────

    @Test
    void buildProgressBars_forecastEnabledCategory_includesProjection() {
        LocalDate today = LocalDate.of(2026, 4, 8);

        Category food = Category.builder()
                .id(UUID.randomUUID()).name("Еда").type(CategoryType.EXPENSE)
                .priority(Priority.MEDIUM).forecastEnabled(true).deleted(false).build();

        FinancialEvent plan = FinancialEvent.builder()
                .id(UUID.randomUUID()).category(food).date(LocalDate.of(2026, 4, 15))
                .type(EventType.EXPENSE)
                .eventKind(EventKind.PLAN).plannedAmount(new BigDecimal("20000")).deleted(false).build();
        FinancialEvent fact = FinancialEvent.builder()
                .id(UUID.randomUUID()).category(food).date(LocalDate.of(2026, 4, 5))
                .type(EventType.EXPENSE)
                .eventKind(EventKind.FACT).factAmount(new BigDecimal("15000")).deleted(false).build();

        CategoryForecastDto catForecast = new CategoryForecastDto(
                "Еда", new BigDecimal("15000"), new BigDecimal("20000"),
                new BigDecimal("35000"), List.of());
        when(predictionService.forecastFromEvents(any(), eq(today)))
                .thenReturn(new MonthlyForecastDto(List.of(catForecast), BigDecimal.ZERO));

        List<DashboardDto.CategoryProgressBar> bars = dashboardService.buildProgressBars(
                List.of(plan, fact), today);

        assertThat(bars).hasSize(1);
        DashboardDto.CategoryProgressBar bar = bars.get(0);
        assertThat(bar.forecastEnabled()).isTrue();
        assertThat(bar.projectionAmount()).isEqualByComparingTo(new BigDecimal("35000"));
    }

    @Test
    void buildProgressBars_forecastDisabledCategory_noProjection() {
        LocalDate today = LocalDate.of(2026, 4, 8);

        Category rent = Category.builder()
                .id(UUID.randomUUID()).name("Аренда").type(CategoryType.EXPENSE)
                .priority(Priority.HIGH).forecastEnabled(false).deleted(false).build();

        FinancialEvent plan = FinancialEvent.builder()
                .id(UUID.randomUUID()).category(rent).date(LocalDate.of(2026, 4, 1))
                .type(EventType.EXPENSE)
                .eventKind(EventKind.PLAN).plannedAmount(new BigDecimal("30000")).deleted(false).build();

        when(predictionService.forecastFromEvents(any(), eq(today)))
                .thenReturn(new MonthlyForecastDto(List.of(), BigDecimal.ZERO));

        List<DashboardDto.CategoryProgressBar> bars = dashboardService.buildProgressBars(
                List.of(plan), today);

        assertThat(bars).hasSize(1);
        DashboardDto.CategoryProgressBar bar = bars.get(0);
        assertThat(bar.forecastEnabled()).isFalse();
        assertThat(bar.projectionAmount()).isNull();
        assertThat(bar.history()).isEmpty();
    }

    // ─── helpers ───────────────────────────────────────────────────────────────

    private FinancialEvent eventWithCategory(EventType type, String categoryName,
            BigDecimal planned, BigDecimal fact) {
        Category category = Category.builder()
                .id(UUID.randomUUID())
                .name(categoryName)
                .type(CategoryType.EXPENSE)
                .build();
        if (fact != null) {
            return FinancialEvent.builder()
                    .id(UUID.randomUUID())
                    .date(TODAY.withDayOfMonth(5))
                    .category(category)
                    .type(type)
                    .eventKind(EventKind.FACT)
                    .factAmount(fact)
                    .status(EventStatus.EXECUTED)
                    .priority(Priority.MEDIUM)
                    .deleted(false)
                    .build();
        } else {
            return FinancialEvent.builder()
                    .id(UUID.randomUUID())
                    .date(TODAY.withDayOfMonth(5))
                    .category(category)
                    .type(type)
                    .eventKind(EventKind.PLAN)
                    .plannedAmount(planned)
                    .status(EventStatus.PLANNED)
                    .priority(Priority.MEDIUM)
                    .deleted(false)
                    .build();
        }
    }

    @Test
    @DisplayName("Прогресс-бары: сортировка по имени категории в русском алфавитном порядке")
    void progressBars_sortedAlphabetically() {
        LocalDate monthStart = TODAY.withDayOfMonth(1);
        LocalDate monthEnd = TODAY.withDayOfMonth(TODAY.lengthOfMonth());

        // V12: для каждой категории — PLAN (planned) + FACT (fact)
        List<FinancialEvent> events = List.of(
                eventWithCategory(EventType.EXPENSE, "Еда",     bd(10_000), null),
                eventWithCategory(EventType.EXPENSE, "Еда",     null,       bd(8_000)),
                eventWithCategory(EventType.EXPENSE, "Аренда",  bd(30_000), null),
                eventWithCategory(EventType.EXPENSE, "Аренда",  null,       bd(30_000)),
                eventWithCategory(EventType.EXPENSE, "Бензин",  bd(5_000),  null),
                eventWithCategory(EventType.EXPENSE, "Бензин",  null,       bd(3_300))
        );
        when(eventRepository.findAllByDeletedFalseAndDateBetween(monthStart, monthEnd))
                .thenReturn(events);

        DashboardDto dto = dashboardService.getDashboard(TODAY);

        List<String> names = dto.progressBars().stream()
                .map(DashboardDto.CategoryProgressBar::categoryName)
                .toList();
        assertThat(names).containsExactly("Аренда", "Бензин", "Еда");
    }

    private static BigDecimal bd(long value) {
        return BigDecimal.valueOf(value);
    }
}
