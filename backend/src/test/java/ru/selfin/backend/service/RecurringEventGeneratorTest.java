package ru.selfin.backend.service;

import org.junit.jupiter.api.Test;
import ru.selfin.backend.model.Category;
import ru.selfin.backend.model.RecurringRule;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.RecurringFrequency;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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
}
