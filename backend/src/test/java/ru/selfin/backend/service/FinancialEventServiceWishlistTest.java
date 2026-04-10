package ru.selfin.backend.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.selfin.backend.model.enums.EventStatus;
import ru.selfin.backend.model.enums.Priority;
import ru.selfin.backend.repository.*;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// NOTE: check FinancialEventService constructor signature before writing this test.
// Pass mocks in the same order as the actual constructor parameters, with Clock last.
@ExtendWith(MockitoExtension.class)
class FinancialEventServiceWishlistTest {

    @Mock FinancialEventRepository eventRepository;
    @Mock CategoryRepository categoryRepository;
    @Mock TargetFundRepository targetFundRepository;
    @Mock CategoryService categoryService;

    // Clock fixed to 2026-04-09
    Clock clock = Clock.fixed(Instant.parse("2026-04-09T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void findWishlist_usesFirstDayOfMonth_notToday() {
        // Inject clock via constructor (after implementation step)
        FinancialEventService service = new FinancialEventService(
                eventRepository, categoryRepository, targetFundRepository, categoryService, clock);

        when(eventRepository.findWishlistItems(
                Priority.LOW, EventStatus.PLANNED,
                LocalDate.of(2026, 4, 1))) // first day of April, not April 9
                .thenReturn(List.of());

        service.findWishlist();

        verify(eventRepository).findWishlistItems(
                Priority.LOW, EventStatus.PLANNED,
                LocalDate.of(2026, 4, 1));
    }
}
