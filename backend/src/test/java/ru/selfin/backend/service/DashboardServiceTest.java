package ru.selfin.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.selfin.backend.dto.CategoryForecastDto;
import ru.selfin.backend.dto.DashboardDto;
import ru.selfin.backend.dto.MonthlyForecastDto;
import ru.selfin.backend.model.Category;
import ru.selfin.backend.model.EventKind;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.enums.CategoryType;
import ru.selfin.backend.model.enums.EventStatus;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.Priority;
import ru.selfin.backend.repository.FinancialEventRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit-тесты DashboardService после ANO-14: сервис отвечает ТОЛЬКО за прогресс-бары.
 * Баланс/зарплатные горизонты/кассовый разрыв — территория PocketEngine (см. PocketEngineTest).
 */
class DashboardServiceTest {

    private FinancialEventRepository eventRepository;
    private PredictionService predictionService;
    private DashboardService dashboardService;

    /** Тестовая «сегодня» — 15 марта 2026, середина месяца. */
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 15);

    @BeforeEach
    void setUp() {
        eventRepository = mock(FinancialEventRepository.class);
        predictionService = mock(PredictionService.class);
        when(predictionService.forecastFromEvents(any(), any()))
                .thenReturn(new MonthlyForecastDto(List.of(), BigDecimal.ZERO));
        dashboardService = new DashboardService(eventRepository, predictionService);
    }

    // ─── Вспомогательный билдер событий (V12 plan-fact split) ─────────────────

    private FinancialEvent event(EventType type, LocalDate date,
            BigDecimal planned, BigDecimal fact) {
        return eventWithCategory(type, type == EventType.INCOME ? "Зарплата" : "Еда",
                date, planned, fact);
    }

    private FinancialEvent eventWithCategory(EventType type, String categoryName,
            LocalDate date, BigDecimal planned, BigDecimal fact) {
        Category category = Category.builder()
                .id(UUID.randomUUID())
                .name(categoryName)
                .type(type == EventType.INCOME ? CategoryType.INCOME : CategoryType.EXPENSE)
                .build();
        if (fact != null) {
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
        }
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

    @Test
    @DisplayName("Прогресс-бары: сортировка по имени категории в русском алфавитном порядке")
    void progressBars_sortedAlphabetically() {
        LocalDate monthStart = TODAY.withDayOfMonth(1);
        LocalDate monthEnd = TODAY.withDayOfMonth(TODAY.lengthOfMonth());
        LocalDate d = TODAY.withDayOfMonth(5);

        List<FinancialEvent> events = List.of(
                eventWithCategory(EventType.EXPENSE, "Еда", d, bd(10_000), null),
                eventWithCategory(EventType.EXPENSE, "Еда", d, null, bd(8_000)),
                eventWithCategory(EventType.EXPENSE, "Аренда", d, bd(30_000), null),
                eventWithCategory(EventType.EXPENSE, "Аренда", d, null, bd(30_000)),
                eventWithCategory(EventType.EXPENSE, "Бензин", d, bd(5_000), null),
                eventWithCategory(EventType.EXPENSE, "Бензин", d, null, bd(3_300)));
        when(eventRepository.findAllByDeletedFalseAndDateBetween(monthStart, monthEnd))
                .thenReturn(events);

        DashboardDto dto = dashboardService.getDashboard(TODAY);

        List<String> names = dto.progressBars().stream()
                .map(DashboardDto.CategoryProgressBar::categoryName)
                .toList();
        assertThat(names).containsExactly("Аренда", "Бензин", "Еда");
    }

    @Test
    @DisplayName("Доходы не попадают в прогресс-бары")
    void progressBars_incomeExcluded() {
        LocalDate monthStart = TODAY.withDayOfMonth(1);
        LocalDate monthEnd = TODAY.withDayOfMonth(TODAY.lengthOfMonth());
        when(eventRepository.findAllByDeletedFalseAndDateBetween(monthStart, monthEnd))
                .thenReturn(List.of(event(EventType.INCOME, TODAY, bd(100_000), null)));

        assertThat(dashboardService.getDashboard(TODAY).progressBars()).isEmpty();
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

    private static BigDecimal bd(long value) {
        return BigDecimal.valueOf(value);
    }
}
