package ru.selfin.backend.service;

import org.junit.jupiter.api.Test;
import ru.selfin.backend.model.enums.Priority;
import ru.selfin.backend.repository.CategoryRepository;
import ru.selfin.backend.repository.FinancialEventRepository;
import ru.selfin.backend.repository.TargetFundRepository;

import java.time.Clock;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FinancialEventServicePriorityTest {

    private final FinancialEventRepository eventRepository = mock(FinancialEventRepository.class);
    private final CategoryRepository categoryRepository = mock(CategoryRepository.class);
    private final TargetFundRepository targetFundRepository = mock(TargetFundRepository.class);
    private final CategoryService categoryService = mock(CategoryService.class);

    private final FinancialEventService service;
    {
        service = new FinancialEventService(
                eventRepository, categoryRepository, targetFundRepository,
                categoryService, Clock.systemDefaultZone());
    }

    @Test
    void findByPriority_delegatesToRepository() {
        when(eventRepository.findAllByDeletedFalseAndPriorityOrderByCreatedAtAsc(Priority.LOW))
                .thenReturn(List.of());

        service.findByPriority(Priority.LOW);

        verify(eventRepository).findAllByDeletedFalseAndPriorityOrderByCreatedAtAsc(Priority.LOW);
    }
}
