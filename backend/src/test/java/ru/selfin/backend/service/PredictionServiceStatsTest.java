package ru.selfin.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.selfin.backend.dto.strategy.CategoryMonthStats;
import ru.selfin.backend.model.Category;
import ru.selfin.backend.model.EventKind;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.enums.EventStatus;
import ru.selfin.backend.model.enums.EventType;

import ru.selfin.backend.repository.FinancialEventRepository;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Статистика категории: медиана и процентили по истории.
 *
 * <p>ANO-80 поменял знаменатель. Раньше в ряд попадали только месяцы, где трата была, и
 * {@code monthsOfHistory} означал «месяцев с тратой в этой категории». Теперь ряд строится
 * по МЕСЯЦАМ НАБЛЮДЕНИЯ — полным месяцам от первого факта пользователя до конца прошлого
 * месяца, — а месяц без траты в категории даёт полноценный ноль.
 *
 * <p>Часы фиксированы: окно наблюдения зависит от календаря, и тест на системных часах
 * доказывал бы в разные дни разное.
 */
class PredictionServiceStatsTest {

    /** «Сегодня» = 14 сентября 2026, значит последний полный месяц — август. */
    private static final Clock FIXED = Clock.fixed(
            LocalDate.of(2026, 9, 14).atStartOfDay(ZoneId.systemDefault()).toInstant(),
            ZoneId.systemDefault());

    private FinancialEventRepository eventRepo;
    private PredictionService service;
    private Category cat;

    @BeforeEach
    void setUp() {
        eventRepo = mock(FinancialEventRepository.class);
        service = new PredictionService(eventRepo, FIXED);
        cat = Category.builder().id(UUID.randomUUID()).name("Продукты").build();
    }

    /** Первый факт пользователя — начало наблюдения; его месяц отбрасывается как неполный. */
    private void observingSince(LocalDate firstFact) {
        when(eventRepo.findFirstFactDate()).thenReturn(firstFact);
    }

    private void facts(FinancialEvent... events) {
        when(eventRepo.findFactsByDateRange(any(), any())).thenReturn(List.of(events));
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
        observingSince(LocalDate.of(2026, 2, 15));   // наблюдение с марта
        facts(
                factEvent(LocalDate.of(2026, 3, 15), "20000"),
                factEvent(LocalDate.of(2026, 4, 15), "25000"),
                factEvent(LocalDate.of(2026, 5, 15), "30000"),
                factEvent(LocalDate.of(2026, 6, 15), "35000"),
                factEvent(LocalDate.of(2026, 7, 15), "40000"),
                factEvent(LocalDate.of(2026, 8, 15), "45000"));

        CategoryMonthStats stats = service.getStatsForCategory(cat, 6);

        assertThat(stats.categoryId()).isEqualTo(cat.getId());
        assertThat(stats.monthsOfHistory()).isEqualTo(6);
        assertThat(stats.median()).isEqualByComparingTo("32500");
        assertThat(stats.p25()).isEqualByComparingTo("26250");
        assertThat(stats.p75()).isEqualByComparingTo("38750");
    }

    @Test
    void getStatsForCategory_with_2mo_history_returns_low_history_marker() {
        observingSince(LocalDate.of(2026, 6, 15));   // наблюдение с июля
        facts(
                factEvent(LocalDate.of(2026, 7, 15), "20000"),
                factEvent(LocalDate.of(2026, 8, 15), "30000"));

        CategoryMonthStats stats = service.getStatsForCategory(cat, 6);

        assertThat(stats.monthsOfHistory()).isEqualTo(2);
        assertThat(stats.median()).isEqualByComparingTo("25000");
    }

    @Test
    void getStatsForCategory_with_zero_history_returns_zeros() {
        // findFirstFactDate() не застабан — мок отдаёт null, фактов нет вовсе.
        facts();

        CategoryMonthStats stats = service.getStatsForCategory(cat, 6);

        assertThat(stats.monthsOfHistory()).isZero();
        assertThat(stats.median()).isEqualByComparingTo("0");
        assertThat(stats.p25()).isEqualByComparingTo("0");
        assertThat(stats.p75()).isEqualByComparingTo("0");
    }

    @Test
    void getStatsForCategory_ignores_soft_deleted_events() {
        observingSince(LocalDate.of(2026, 6, 15));   // наблюдение с июля
        FinancialEvent dead = factEvent(LocalDate.of(2026, 7, 20), "99999");
        dead.setDeleted(true);
        facts(
                factEvent(LocalDate.of(2026, 7, 15), "30000"),
                dead,
                factEvent(LocalDate.of(2026, 8, 15), "30000"));

        CategoryMonthStats stats = service.getStatsForCategory(cat, 6);

        assertThat(stats.monthsOfHistory()).isEqualTo(2);
        assertThat(stats.median())
                .as("учтись удалённое, июль дал бы 129 999 и медиана уехала бы к 79 999,5")
                .isEqualByComparingTo("30000");
    }

    @Test
    void getStatsForCategory_uses_only_FACT_events() {
        observingSince(LocalDate.of(2026, 6, 15));   // наблюдение с июля
        FinancialEvent plan = FinancialEvent.builder()
                .id(UUID.randomUUID()).category(cat).type(EventType.EXPENSE)
                .date(LocalDate.of(2026, 7, 20))
                .plannedAmount(new BigDecimal("99999"))
                .eventKind(EventKind.PLAN)
                .status(EventStatus.PLANNED)
                .deleted(false)
                .build();
        facts(
                factEvent(LocalDate.of(2026, 7, 15), "30000"),
                plan,
                factEvent(LocalDate.of(2026, 8, 15), "30000"));

        CategoryMonthStats stats = service.getStatsForCategory(cat, 6);

        assertThat(stats.monthsOfHistory()).isEqualTo(2);
        assertThat(stats.median()).isEqualByComparingTo("30000");
    }

    // ── ANO-80 ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ANO-80: месяц наблюдения без траты в категории считается нулём")
    void getStatsForCategory_monthsWithoutSpending_countAsZero() {
        // Учёт с 9 марта → наблюдение апрель..август, пять месяцев. Покупки в трёх из них.
        observingSince(LocalDate.of(2026, 3, 9));
        facts(
                factEvent(LocalDate.of(2026, 5, 10), "9000"),
                factEvent(LocalDate.of(2026, 7, 10), "12000"),
                factEvent(LocalDate.of(2026, 8, 10), "15000"));

        CategoryMonthStats stats = service.getStatsForCategory(cat, 6);

        assertThat(stats.monthsOfHistory())
                .as("месяцев наблюдения пять: апрель..август, март отброшен как неполный")
                .isEqualTo(5);
        assertThat(stats.median())
                .as("ряд 0, 0, 9000, 12000, 15000 — медиана девять тысяч, а не двенадцать")
                .isEqualByComparingTo("9000");
    }

    @Test
    @DisplayName("ANO-80: месяц первого факта отброшен как неполный")
    void getStatsForCategory_monthOfFirstFact_isDropped() {
        observingSince(LocalDate.of(2026, 7, 20));
        facts(
                factEvent(LocalDate.of(2026, 7, 21), "50000"),   // в отброшенном месяце
                factEvent(LocalDate.of(2026, 8, 10), "10000"));

        CategoryMonthStats stats = service.getStatsForCategory(cat, 6);

        assertThat(stats.monthsOfHistory())
                .as("наблюдение начинается с августа: июль неполон")
                .isEqualTo(1);
        assertThat(stats.median())
                .as("июльские 50 000 в ряд не входят")
                .isEqualByComparingTo("10000");
    }

    @Test
    @DisplayName("ANO-80: учёт начат в прошлом месяце — полных месяцев наблюдения ноль")
    void getStatsForCategory_firstFactLastMonth_hasNoFullMonths() {
        observingSince(LocalDate.of(2026, 8, 3));
        facts(factEvent(LocalDate.of(2026, 8, 10), "40000"));

        CategoryMonthStats stats = service.getStatsForCategory(cat, 6);

        assertThat(stats.monthsOfHistory())
                .as("август отброшен как неполный, сентябрь ещё не кончился — считать нечего")
                .isZero();
        assertThat(stats.median()).isEqualByComparingTo("0");
    }
}
