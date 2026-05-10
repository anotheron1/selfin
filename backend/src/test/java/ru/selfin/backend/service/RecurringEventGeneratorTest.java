package ru.selfin.backend.service;

import org.junit.jupiter.api.Test;
import ru.selfin.backend.model.Category;
import ru.selfin.backend.model.RecurringRule;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.RecurringFrequency;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RecurringEventGeneratorTest {

    private final RecurringEventGenerator generator = new RecurringEventGenerator();

    private RecurringRule monthlyRule(int day, LocalDate start, LocalDate end) {
        Category cat = Category.builder().id(UUID.randomUUID()).build();
        return RecurringRule.builder()
                .id(UUID.randomUUID())
                .category(cat)
                .eventType(EventType.EXPENSE)
                .plannedAmount(new BigDecimal("80000"))
                .frequency(RecurringFrequency.MONTHLY)
                .dayOfMonth(day)
                .startDate(start)
                .endDate(end)
                .build();
    }

    @Test
    void generates_monthly_15th_for_three_months() {
        var rule = monthlyRule(15, LocalDate.of(2026, 5, 15), LocalDate.of(2026, 7, 31));

        var events = generator.generate(rule, rule.getStartDate(), rule.getEndDate());

        assertThat(events).extracting("date").containsExactly(
                LocalDate.of(2026, 5, 15),
                LocalDate.of(2026, 6, 15),
                LocalDate.of(2026, 7, 15));
        assertThat(events).allSatisfy(e -> {
            assertThat(e.getRecurringRule()).isEqualTo(rule);
            assertThat(e.getCategory()).isEqualTo(rule.getCategory());
            assertThat(e.getType()).isEqualTo(EventType.EXPENSE);
            assertThat(e.getPlannedAmount()).isEqualTo(new BigDecimal("80000"));
        });
    }

    @Test
    void monthly_31st_clamps_to_short_months() {
        var rule = monthlyRule(31, LocalDate.of(2026, 1, 31), LocalDate.of(2026, 5, 31));

        var events = generator.generate(rule, rule.getStartDate(), rule.getEndDate());

        assertThat(events).extracting("date").containsExactly(
                LocalDate.of(2026, 1, 31),
                LocalDate.of(2026, 2, 28),  // не високосный
                LocalDate.of(2026, 3, 31),
                LocalDate.of(2026, 4, 30),
                LocalDate.of(2026, 5, 31));
    }

    @Test
    void monthly_31st_in_leap_year_february_gives_29() {
        var rule = monthlyRule(31, LocalDate.of(2028, 1, 31), LocalDate.of(2028, 3, 31));

        var events = generator.generate(rule, rule.getStartDate(), rule.getEndDate());

        assertThat(events).extracting("date").containsExactly(
                LocalDate.of(2028, 1, 31),
                LocalDate.of(2028, 2, 29),
                LocalDate.of(2028, 3, 31));
    }

    @Test
    void yearly_feb_29_clamps_to_28_in_non_leap_years() {
        Category cat = Category.builder().id(UUID.randomUUID()).build();
        var rule = RecurringRule.builder()
                .id(UUID.randomUUID())
                .category(cat)
                .eventType(EventType.EXPENSE)
                .plannedAmount(new BigDecimal("100"))
                .frequency(RecurringFrequency.YEARLY)
                .monthOfYear(2)
                .dayOfMonth(29)
                .startDate(LocalDate.of(2028, 2, 29))      // високосный
                .endDate(LocalDate.of(2032, 12, 31))       // охватывает 2028,2029,2030,2031,2032
                .build();

        var events = generator.generate(rule, rule.getStartDate(), rule.getEndDate());

        assertThat(events).extracting("date").containsExactly(
                LocalDate.of(2028, 2, 29),
                LocalDate.of(2029, 2, 28),
                LocalDate.of(2030, 2, 28),
                LocalDate.of(2031, 2, 28),
                LocalDate.of(2032, 2, 29));
    }

    @Test
    void start_equals_end_yields_single_event() {
        var rule = monthlyRule(15, LocalDate.of(2026, 5, 15), LocalDate.of(2026, 5, 15));

        var events = generator.generate(rule, rule.getStartDate(), rule.getEndDate());

        assertThat(events).hasSize(1);
        assertThat(events.get(0).getDate()).isEqualTo(LocalDate.of(2026, 5, 15));
    }

    @Test
    void through_between_periods_excludes_after() {
        // 15-го каждого месяца, до 2027-01-10 — последний должен быть 2026-12-15
        var rule = monthlyRule(15, LocalDate.of(2026, 11, 15), LocalDate.of(2027, 1, 10));

        var events = generator.generate(rule, rule.getStartDate(), rule.getEndDate());

        assertThat(events).extracting("date").containsExactly(
                LocalDate.of(2026, 11, 15),
                LocalDate.of(2026, 12, 15));
    }

    @Test
    void start_after_through_returns_empty() {
        var rule = monthlyRule(15, LocalDate.of(2027, 1, 15), null);

        var events = generator.generate(rule, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));

        assertThat(events).isEmpty();
    }
}
