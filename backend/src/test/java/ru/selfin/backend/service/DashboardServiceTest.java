package ru.selfin.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.selfin.backend.dto.DashboardDto;
import ru.selfin.backend.model.BalanceCheckpoint;
import ru.selfin.backend.model.Category;
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
import static org.mockito.Mockito.*;

/**
 * Unit-тесты ключевой финансовой математики DashboardService.
 * Без подключения к БД (чистый Mockito + список событий).
 */
class DashboardServiceTest {

    private FinancialEventRepository eventRepository;
    private BalanceCheckpointRepository checkpointRepository;
    private DashboardService dashboardService;

    private static final LocalDate TODAY = LocalDate.of(2026, 3, 15);

    @BeforeEach
    void setUp() {
        eventRepository = mock(FinancialEventRepository.class);
        checkpointRepository = mock(BalanceCheckpointRepository.class);
        // По умолчанию чекпоинтов нет — поведение как раньше (баланс от нуля)
        when(checkpointRepository.findTopByOrderByDateDesc()).thenReturn(Optional.empty());
        dashboardService = new DashboardService(eventRepository, checkpointRepository);
    }

    // ─── Вспомогательный билдер событий ───────────────────────────────────────

    private FinancialEvent event(EventType type, LocalDate date,
            BigDecimal planned, BigDecimal fact) {
        Category category = Category.builder()
                .id(UUID.randomUUID())
                .name(type == EventType.INCOME ? "Зарплата" : "Еда")
                .type(type == EventType.INCOME ? CategoryType.INCOME : CategoryType.EXPENSE)
                .build();
        return FinancialEvent.builder()
                .id(UUID.randomUUID())
                .date(date)
                .category(category)
                .type(type)
                .plannedAmount(planned)
                .factAmount(fact)
                .status(fact != null ? EventStatus.EXECUTED : EventStatus.PLANNED)
                .priority(Priority.MEDIUM)
                .deleted(false)
                .build();
    }

    private BalanceCheckpoint checkpoint(LocalDate date, long amount) {
        return BalanceCheckpoint.builder()
                .id(UUID.randomUUID())
                .date(date)
                .amount(bd(amount))
                .build();
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

        // Оба события — прошедшие PLANNED (factAmount == null):
        // не входят в currentBalance (не исполнены) и не входят в прогноз (прошлое, уже не случится)
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
                // Прошлое, исполнено — в currentBalance
                event(EventType.INCOME, LocalDate.of(2026, 3, 5), bd(100_000), bd(100_000)),
                // Прошлое, НЕ исполнено — ни в балансе, ни в прогнозе
                event(EventType.EXPENSE, LocalDate.of(2026, 3, 10), bd(5_000), null),
                // Сегодня (15), не исполнено — только в прогнозе
                event(EventType.EXPENSE, TODAY, bd(3_000), null),
                // Будущее, не исполнено — только в прогнозе
                event(EventType.EXPENSE, LocalDate.of(2026, 3, 20), bd(20_000), null));
        when(eventRepository.findAllByDeletedFalseAndDateBetween(monthStart, monthEnd))
                .thenReturn(events);

        DashboardDto dto = dashboardService.getDashboard(TODAY);

        // currentBalance = 100_000 (только исполненный факт)
        assertThat(dto.currentBalance()).isEqualByComparingTo(bd(100_000));
        // forecastDelta = -3_000 (сегодня) - 20_000 (будущее) = -23_000
        // endOfMonthForecast = 100_000 - 23_000 = 77_000
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

        // Событие ДО чекпоинта (5 марта) — не должно входить в расчёт
        // Событие ПОСЛЕ (12 марта) — должно входить
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
        LocalDate monthStart = TODAY.withDayOfMonth(1); // 2026-03-01
        LocalDate monthEnd = TODAY.withDayOfMonth(TODAY.lengthOfMonth());
        LocalDate cpDate = LocalDate.of(2026, 2, 20);

        when(checkpointRepository.findTopByOrderByDateDesc())
                .thenReturn(Optional.of(checkpoint(cpDate, 40_000)));

        // Мостик: события с 20.02 по 28.02
        when(eventRepository.findAllByDeletedFalseAndDateBetween(cpDate, monthStart.minusDays(1)))
                .thenReturn(List.of(
                        event(EventType.INCOME, LocalDate.of(2026, 2, 25), bd(20_000), bd(20_000))));

        // Март: расход 10_000
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
        LocalDate cpDate = LocalDate.of(2026, 3, 1);

        when(checkpointRepository.findTopByOrderByDateDesc())
                .thenReturn(Optional.of(checkpoint(cpDate, 99_000)));
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

    // ─── detectCashGap ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("Кассовый разрыв: обнаруживает первый день с отрицательным балансом")
    void cashGap_detectsFirstNegativeDay() {
        LocalDate monthStart = TODAY.withDayOfMonth(1);
        LocalDate monthEnd = TODAY.withDayOfMonth(TODAY.lengthOfMonth());

        List<FinancialEvent> events = List.of(
                event(EventType.INCOME, LocalDate.of(2026, 3, 1), bd(5_000), bd(5_000)),
                event(EventType.EXPENSE, LocalDate.of(2026, 3, 17), bd(10_000), null)
        );
        when(eventRepository.findAllByDeletedFalseAndDateBetween(monthStart, monthEnd))
                .thenReturn(events);

        DashboardDto dto = dashboardService.getDashboard(TODAY);

        assertThat(dto.cashGapAlert()).isNotNull();
        assertThat(dto.cashGapAlert().gapDate()).isEqualTo(LocalDate.of(2026, 3, 17));
        assertThat(dto.cashGapAlert().gapAmount()).isNegative();
    }

    @Test
    @DisplayName("Кассовый разрыв: null если баланс всегда положительный")
    void cashGap_nullWhenNoGap() {
        LocalDate monthStart = TODAY.withDayOfMonth(1);
        LocalDate monthEnd = TODAY.withDayOfMonth(TODAY.lengthOfMonth());

        List<FinancialEvent> events = List.of(
                event(EventType.INCOME, LocalDate.of(2026, 3, 1), bd(100_000), bd(100_000)),
                event(EventType.EXPENSE, LocalDate.of(2026, 3, 20), bd(50_000), null));
        when(eventRepository.findAllByDeletedFalseAndDateBetween(monthStart, monthEnd))
                .thenReturn(events);

        DashboardDto dto = dashboardService.getDashboard(TODAY);

        assertThat(dto.cashGapAlert()).isNull();
    }

    // ─── progressBars ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("Прогресс-бары: процент = факт/план × 100 по категории расходов")
    void progressBars_calculatesPercentage() {
        LocalDate monthStart = TODAY.withDayOfMonth(1);
        LocalDate monthEnd = TODAY.withDayOfMonth(TODAY.lengthOfMonth());

        List<FinancialEvent> events = List.of(
                event(EventType.EXPENSE, LocalDate.of(2026, 3, 5), bd(20_000), bd(15_000)));
        when(eventRepository.findAllByDeletedFalseAndDateBetween(monthStart, monthEnd))
                .thenReturn(events);

        DashboardDto dto = dashboardService.getDashboard(TODAY);

        assertThat(dto.progressBars()).hasSize(1);
        DashboardDto.CategoryProgressBar bar = dto.progressBars().get(0);
        assertThat(bar.plannedLimit()).isEqualByComparingTo(bd(20_000));
        assertThat(bar.currentFact()).isEqualByComparingTo(bd(15_000));
        assertThat(bar.percentage()).isEqualTo(75);
    }

    // ─── helpers ───────────────────────────────────────────────────────────────

    private static BigDecimal bd(long value) {
        return BigDecimal.valueOf(value);
    }
}
