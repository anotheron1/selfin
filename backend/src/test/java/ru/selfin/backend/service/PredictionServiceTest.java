package ru.selfin.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.selfin.backend.dto.CategoryForecastDto;
import ru.selfin.backend.dto.MonthlyForecastDto;
import ru.selfin.backend.model.Category;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.enums.CategoryType;
import ru.selfin.backend.model.enums.Priority;
import ru.selfin.backend.model.EventKind;           // NOTE: EventKind is in model package, NOT model.enums
import ru.selfin.backend.repository.FinancialEventRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PredictionServiceTest {

    @Mock
    private FinancialEventRepository eventRepository;

    @InjectMocks
    private PredictionService predictionService;

    private Category foodCategory;

    @BeforeEach
    void setUp() {
        foodCategory = Category.builder()
                .id(UUID.randomUUID())
                .name("Еда / Продукты")
                .type(CategoryType.EXPENSE)
                .priority(Priority.MEDIUM)
                .forecastEnabled(true)
                .deleted(false)
                .build();
    }

    // ── Hybrid B: plan events present ─────────────────────────────────────────

    @Test
    void forecast_hybridB_factPlusRemainingPlans() {
        // Month: April (30 days). Today = day 8.
        // Plan events: 4 × 10k = 40k total, weeks 1-4
        // Fact: 30k in week 1 (overspend)
        // Remaining PLAN events after today: weeks 2-4 = 30k
        // Expected projection: 30k + 30k = 60k
        LocalDate today = LocalDate.of(2026, 4, 8);
        LocalDate week2 = LocalDate.of(2026, 4, 15);
        LocalDate week3 = LocalDate.of(2026, 4, 22);
        LocalDate week4 = LocalDate.of(2026, 4, 29);

        FinancialEvent plan1 = planEvent(foodCategory, LocalDate.of(2026, 4, 1), new BigDecimal("10000"));
        FinancialEvent plan2 = planEvent(foodCategory, week2, new BigDecimal("10000"));
        FinancialEvent plan3 = planEvent(foodCategory, week3, new BigDecimal("10000"));
        FinancialEvent plan4 = planEvent(foodCategory, week4, new BigDecimal("10000"));
        FinancialEvent fact1 = factEvent(foodCategory, LocalDate.of(2026, 4, 5), new BigDecimal("30000"));

        List<FinancialEvent> events = List.of(plan1, plan2, plan3, plan4, fact1);

        CategoryForecastDto result = predictionService.forecast("Еда / Продукты", events, today);

        assertThat(result.projectionAmount()).isEqualByComparingTo(new BigDecimal("60000"));
        assertThat(result.currentFact()).isEqualByComparingTo(new BigDecimal("30000"));
        assertThat(result.plannedLimit()).isEqualByComparingTo(new BigDecimal("40000"));
    }

    @Test
    void forecast_hybridB_nofutureRemainingPlans_projectionEqualsCurrentFact() {
        // All plan events are in the past, no remaining plans
        // Projection = fact + 0 remaining plans = just the fact
        LocalDate today = LocalDate.of(2026, 4, 28);
        FinancialEvent plan1 = planEvent(foodCategory, LocalDate.of(2026, 4, 1), new BigDecimal("40000"));
        FinancialEvent fact1 = factEvent(foodCategory, LocalDate.of(2026, 4, 5), new BigDecimal("35000"));

        CategoryForecastDto result = predictionService.forecast("Еда / Продукты",
                List.of(plan1, fact1), today);

        assertThat(result.projectionAmount()).isEqualByComparingTo(new BigDecimal("35000"));
    }

    // ── Linear A: no plan events ───────────────────────────────────────────────

    @Test
    void forecast_linearA_extrapolatesFromDailyRate() {
        // No PLAN events. Today = day 10 of 30. Spent 5000.
        // dailyRate = 5000/10 = 500/day. Remaining = 20 days.
        // projection = 5000 + 500*20 = 15000
        LocalDate today = LocalDate.of(2026, 4, 10);
        FinancialEvent fact1 = factEvent(foodCategory, LocalDate.of(2026, 4, 5), new BigDecimal("5000"));

        CategoryForecastDto result = predictionService.forecast("Еда / Продукты",
                List.of(fact1), today);

        assertThat(result.projectionAmount()).isEqualByComparingTo(new BigDecimal("15000"));
    }

    // ── Edge cases ─────────────────────────────────────────────────────────────

    @Test
    void forecast_noFacts_noPlans_projectionIsZero() {
        LocalDate today = LocalDate.of(2026, 4, 5);

        CategoryForecastDto result = predictionService.forecast("Еда / Продукты",
                List.of(), today);

        assertThat(result.projectionAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void forecast_dayOne_noFacts_projectionIsZero() {
        LocalDate today = LocalDate.of(2026, 4, 1);

        CategoryForecastDto result = predictionService.forecast("Еда / Продукты",
                List.of(), today);

        assertThat(result.projectionAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ── netPredictionDelta ─────────────────────────────────────────────────────

    @Test
    void forecastFromEvents_deltaOnlyFromLinearCategories() {
        LocalDate today = LocalDate.of(2026, 4, 10);

        Category catA = makeCategory("Еда / Продукты", true);
        Category catB = makeCategory("Прочее", true);

        // catA: plan-based, fact=30k, remaining plan=30k → projection=60k, delta=0
        FinancialEvent planA = planEvent(catA, LocalDate.of(2026, 4, 1), new BigDecimal("40000"));
        FinancialEvent factA = factEvent(catA, LocalDate.of(2026, 4, 5), new BigDecimal("30000"));

        // catB: linear, fact=5000 over 10 days, dailyRate=500, remaining=20 days → future=10000
        FinancialEvent factB = factEvent(catB, LocalDate.of(2026, 4, 5), new BigDecimal("5000"));

        MonthlyForecastDto result = predictionService.forecastFromEvents(
                List.of(planA, factA, factB), today);

        // Delta = only catB's extrapolated future = 10000
        assertThat(result.netPredictionDelta()).isEqualByComparingTo(new BigDecimal("10000"));
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private FinancialEvent planEvent(Category category, LocalDate date, BigDecimal amount) {
        return FinancialEvent.builder()
                .id(UUID.randomUUID())
                .category(category)
                .date(date)
                .eventKind(EventKind.PLAN)
                .plannedAmount(amount)
                .deleted(false)
                .build();
    }

    private FinancialEvent factEvent(Category category, LocalDate date, BigDecimal amount) {
        return FinancialEvent.builder()
                .id(UUID.randomUUID())
                .category(category)
                .date(date)
                .eventKind(EventKind.FACT)
                .factAmount(amount)
                .deleted(false)
                .build();
    }

    private Category makeCategory(String name, boolean forecastEnabled) {
        return Category.builder()
                .id(UUID.randomUUID())
                .name(name)
                .type(CategoryType.EXPENSE)
                .priority(Priority.MEDIUM)
                .forecastEnabled(forecastEnabled)
                .deleted(false)
                .build();
    }
}
