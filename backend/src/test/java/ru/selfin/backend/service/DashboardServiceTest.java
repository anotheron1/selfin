package ru.selfin.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.selfin.backend.dto.DashboardDto;
import ru.selfin.backend.model.Category;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.enums.CategoryType;
import ru.selfin.backend.model.enums.EventStatus;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.repository.FinancialEventRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit-тесты ключевой финансовой математики DashboardService.
 * Без подключения к БД (чистый Mockito + список событий).
 */
class DashboardServiceTest {

    private FinancialEventRepository eventRepository;
    private DashboardService dashboardService;

    private static final LocalDate TODAY = LocalDate.of(2026, 3, 15);

    @BeforeEach
    void setUp() {
        eventRepository = mock(FinancialEventRepository.class);
        dashboardService = new DashboardService(eventRepository);
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
                .mandatory(false)
                .deleted(false)
                .build();
    }

    // ─── currentBalance ────────────────────────────────────────────────────────

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

        // 100_000 - 18_000 = 82_000 (событие 20-го марта в будущем — не включается)
        assertThat(dto.currentBalance()).isEqualByComparingTo(bd(82_000));
    }

    @Test
    @DisplayName("Текущий баланс: если факт отсутствует — используется плановая сумма")
    void currentBalance_fallsBackToPlanned() {
        LocalDate monthStart = TODAY.withDayOfMonth(1);
        LocalDate monthEnd = TODAY.withDayOfMonth(TODAY.lengthOfMonth());

        List<FinancialEvent> events = List.of(
                event(EventType.INCOME, LocalDate.of(2026, 3, 1), bd(50_000), null), // план без факта
                event(EventType.EXPENSE, LocalDate.of(2026, 3, 5), bd(10_000), null));
        when(eventRepository.findAllByDeletedFalseAndDateBetween(monthStart, monthEnd))
                .thenReturn(events);

        DashboardDto dto = dashboardService.getDashboard(TODAY);

        assertThat(dto.currentBalance()).isEqualByComparingTo(bd(40_000));
    }

    @Test
    @DisplayName("Текущий баланс равен нулю при отсутствии событий")
    void currentBalance_emptyEvents() {
        LocalDate monthStart = TODAY.withDayOfMonth(1);
        LocalDate monthEnd = TODAY.withDayOfMonth(TODAY.lengthOfMonth());
        when(eventRepository.findAllByDeletedFalseAndDateBetween(monthStart, monthEnd))
                .thenReturn(List.of());

        DashboardDto dto = dashboardService.getDashboard(TODAY);

        assertThat(dto.currentBalance()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ─── endOfMonthForecast ────────────────────────────────────────────────────

    @Test
    @DisplayName("Прогноз EOM = текущий баланс + план будущих событий")
    void endOfMonthForecast_addsPlannedFuture() {
        LocalDate monthStart = TODAY.withDayOfMonth(1);
        LocalDate monthEnd = TODAY.withDayOfMonth(TODAY.lengthOfMonth());

        List<FinancialEvent> events = List.of(
                event(EventType.INCOME, LocalDate.of(2026, 3, 5), bd(80_000), bd(80_000)), // прошлое
                event(EventType.EXPENSE, LocalDate.of(2026, 3, 20), bd(30_000), null), // будущий расход
                event(EventType.INCOME, LocalDate.of(2026, 3, 25), bd(10_000), null) // будущий доход
        );
        when(eventRepository.findAllByDeletedFalseAndDateBetween(monthStart, monthEnd))
                .thenReturn(events);

        DashboardDto dto = dashboardService.getDashboard(TODAY);

        // currentBalance = 80_000
        // forecastDelta = -30_000 + 10_000 = -20_000
        // EOM = 80_000 - 20_000 = 60_000
        assertThat(dto.endOfMonthForecast()).isEqualByComparingTo(bd(60_000));
    }

    // ─── detectCashGap ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("Кассовый разрыв: обнаруживает первый день с отрицательным балансом")
    void cashGap_detectsFirstNegativeDay() {
        // Сценарий: баланс сегодня 5_000, через 2 дня расход 10_000 → разрыв
        LocalDate monthStart = TODAY.withDayOfMonth(1);
        LocalDate monthEnd = TODAY.withDayOfMonth(TODAY.lengthOfMonth());

        List<FinancialEvent> events = List.of(
                event(EventType.INCOME, LocalDate.of(2026, 3, 1), bd(5_000), bd(5_000)),
                event(EventType.EXPENSE, LocalDate.of(2026, 3, 17), bd(10_000), null) // будущий расход > баланса
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
        assertThat(bar.percentage()).isEqualTo(75); // 15_000 / 20_000 = 75%
    }

    // ─── helpers ───────────────────────────────────────────────────────────────

    private static BigDecimal bd(long value) {
        return BigDecimal.valueOf(value);
    }
}
