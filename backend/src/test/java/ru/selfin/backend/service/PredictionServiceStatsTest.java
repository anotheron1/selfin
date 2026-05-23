package ru.selfin.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.selfin.backend.dto.strategy.CategoryMonthStats;
import ru.selfin.backend.model.Category;
import ru.selfin.backend.model.EventKind;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.enums.EventStatus;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.repository.FinancialEventRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PredictionServiceStatsTest {

    private FinancialEventRepository eventRepo;
    private PredictionService service;
    private Category cat;

    @BeforeEach
    void setUp() {
        eventRepo = mock(FinancialEventRepository.class);
        // PredictionService имеет одну зависимость: FinancialEventRepository (проверено в коде).
        service = new PredictionService(eventRepo);
        cat = Category.builder().id(UUID.randomUUID()).name("Продукты").build();
    }

    private FinancialEvent factEvent(LocalDate date, String amount) {
        return FinancialEvent.builder()
                .id(UUID.randomUUID())
                .category(cat)
                .type(EventType.EXPENSE)
                .date(date)
                .factAmount(new BigDecimal(amount))
                .eventKind(EventKind.FACT)
                .status(EventStatus.EXECUTED)
                .deleted(false)
                .build();
    }

    @Test
    void getStatsForCategory_with_6mo_history_returns_correct_percentiles() {
        LocalDate today = LocalDate.now();
        List<FinancialEvent> facts = List.of(
                factEvent(today.minusMonths(6).withDayOfMonth(15), "20000"),
                factEvent(today.minusMonths(5).withDayOfMonth(15), "25000"),
                factEvent(today.minusMonths(4).withDayOfMonth(15), "30000"),
                factEvent(today.minusMonths(3).withDayOfMonth(15), "35000"),
                factEvent(today.minusMonths(2).withDayOfMonth(15), "40000"),
                factEvent(today.minusMonths(1).withDayOfMonth(15), "45000")
        );
        when(eventRepo.findFactsByDateRange(any(), any())).thenReturn(facts);

        CategoryMonthStats stats = service.getStatsForCategory(cat, 6);

        assertThat(stats.categoryId()).isEqualTo(cat.getId());
        assertThat(stats.monthsOfHistory()).isEqualTo(6);
        assertThat(stats.median()).isEqualByComparingTo("32500");
        assertThat(stats.p25()).isEqualByComparingTo("26250");
        assertThat(stats.p75()).isEqualByComparingTo("38750");
    }

    @Test
    void getStatsForCategory_with_2mo_history_returns_low_history_marker() {
        LocalDate today = LocalDate.now();
        List<FinancialEvent> facts = List.of(
                factEvent(today.minusMonths(2).withDayOfMonth(15), "20000"),
                factEvent(today.minusMonths(1).withDayOfMonth(15), "30000")
        );
        when(eventRepo.findFactsByDateRange(any(), any())).thenReturn(facts);

        CategoryMonthStats stats = service.getStatsForCategory(cat, 6);

        assertThat(stats.monthsOfHistory()).isEqualTo(2);
        assertThat(stats.median()).isEqualByComparingTo("25000");
    }

    @Test
    void getStatsForCategory_with_zero_history_returns_zeros() {
        when(eventRepo.findFactsByDateRange(any(), any())).thenReturn(List.of());

        CategoryMonthStats stats = service.getStatsForCategory(cat, 6);

        assertThat(stats.monthsOfHistory()).isZero();
        assertThat(stats.median()).isEqualByComparingTo("0");
        assertThat(stats.p25()).isEqualByComparingTo("0");
        assertThat(stats.p75()).isEqualByComparingTo("0");
    }

    @Test
    void getStatsForCategory_ignores_soft_deleted_events() {
        LocalDate today = LocalDate.now();
        FinancialEvent live = factEvent(today.minusMonths(1).withDayOfMonth(15), "30000");
        FinancialEvent dead = factEvent(today.minusMonths(2).withDayOfMonth(15), "99999");
        dead.setDeleted(true);
        when(eventRepo.findFactsByDateRange(any(), any())).thenReturn(List.of(live, dead));

        CategoryMonthStats stats = service.getStatsForCategory(cat, 6);

        assertThat(stats.monthsOfHistory()).isEqualTo(1);
        assertThat(stats.median()).isEqualByComparingTo("30000");
    }

    @Test
    void getStatsForCategory_uses_only_FACT_events() {
        LocalDate today = LocalDate.now();
        FinancialEvent fact = factEvent(today.minusMonths(1).withDayOfMonth(15), "30000");
        FinancialEvent plan = FinancialEvent.builder()
                .id(UUID.randomUUID()).category(cat).type(EventType.EXPENSE)
                .date(today.minusMonths(2).withDayOfMonth(15))
                .plannedAmount(new BigDecimal("99999"))
                .eventKind(EventKind.PLAN)
                .status(EventStatus.PLANNED)
                .deleted(false)
                .build();
        when(eventRepo.findFactsByDateRange(any(), any())).thenReturn(List.of(fact, plan));

        CategoryMonthStats stats = service.getStatsForCategory(cat, 6);

        assertThat(stats.monthsOfHistory()).isEqualTo(1);
        assertThat(stats.median()).isEqualByComparingTo("30000");
    }
}
