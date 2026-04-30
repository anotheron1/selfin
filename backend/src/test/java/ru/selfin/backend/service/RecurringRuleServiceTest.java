package ru.selfin.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.selfin.backend.dto.RecurringConfigDto;
import ru.selfin.backend.model.Category;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.RecurringRule;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.Priority;
import ru.selfin.backend.model.enums.RecurringFrequency;
import ru.selfin.backend.repository.FinancialEventRepository;
import ru.selfin.backend.repository.RecurringRuleRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RecurringRuleServiceTest {

    private RecurringRuleRepository ruleRepo;
    private FinancialEventRepository eventRepo;
    private RecurringEventGenerator generator;
    private RecurringRuleService service;

    private Category category;

    @BeforeEach
    void setUp() {
        ruleRepo = mock(RecurringRuleRepository.class);
        eventRepo = mock(FinancialEventRepository.class);
        generator = new RecurringEventGenerator();    // real — пусть выдаёт настоящие даты
        service = new RecurringRuleService(ruleRepo, eventRepo, generator);
        category = Category.builder().id(UUID.randomUUID()).build();

        // ruleRepo.save returns its argument with id
        when(ruleRepo.save(any(RecurringRule.class))).thenAnswer(inv -> {
            RecurringRule r = inv.getArgument(0);
            if (r.getId() == null) r.setId(UUID.randomUUID());
            return r;
        });
        when(eventRepo.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void createFromDto_with_endDate_generates_bounded_set() {
        // Use relative dates so the test does not rot when calendar moves past pinned dates.
        // I3: startDate must be today-or-later; we anchor on next month's 15th.
        LocalDate start = LocalDate.now().plusMonths(1).withDayOfMonth(15);
        LocalDate end = start.plusMonths(2);
        RecurringConfigDto cfg = new RecurringConfigDto(
                RecurringFrequency.MONTHLY, 15, null, start, end);

        var result = service.createFromDto(category, EventType.EXPENSE, new BigDecimal("80000"),
                Priority.HIGH, "Ипотека", cfg);

        assertThat(result.rule().getStartDate()).isEqualTo(start);
        ArgumentCaptor<List<FinancialEvent>> cap = ArgumentCaptor.forClass(List.class);
        verify(eventRepo).saveAll(cap.capture());
        assertThat(cap.getValue()).extracting("date").containsExactly(
                start, start.plusMonths(1), start.plusMonths(2));
    }
}
