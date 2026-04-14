package ru.selfin.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.selfin.backend.dto.FactCreateDto;
import ru.selfin.backend.dto.FinancialEventDto;
import ru.selfin.backend.exception.ResourceNotFoundException;
import ru.selfin.backend.model.*;
import ru.selfin.backend.model.enums.*;
import ru.selfin.backend.model.enums.Priority;
import ru.selfin.backend.repository.CategoryRepository;
import ru.selfin.backend.repository.FactAggregateProjection;
import ru.selfin.backend.repository.FinancialEventRepository;
import ru.selfin.backend.repository.TargetFundRepository;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FinancialEventServiceTest {

    private final FinancialEventRepository eventRepository = mock(FinancialEventRepository.class);
    private final CategoryRepository categoryRepository = mock(CategoryRepository.class);
    private final TargetFundRepository targetFundRepository = mock(TargetFundRepository.class);
    private final CategoryService categoryService = mock(CategoryService.class);
    private final TargetFundService targetFundService = mock(TargetFundService.class);

    // Build service and inject the @Lazy TargetFundService via reflection
    private final FinancialEventService service;
    {
        service = new FinancialEventService(eventRepository, categoryRepository, targetFundRepository, categoryService, Clock.systemDefaultZone());
        try {
            var field = FinancialEventService.class.getDeclaredField("targetFundService");
            field.setAccessible(true);
            field.set(service, targetFundService);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("createLinkedFact: бросает ResourceNotFoundException если plan не найден")
    void createLinkedFact_planNotFound_throws() {
        when(eventRepository.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                service.createLinkedFact(UUID.randomUUID(),
                        new FactCreateDto(LocalDate.now(), BigDecimal.TEN, null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("createLinkedFact: бросает ResourceNotFoundException если запись — FACT, не PLAN")
    void createLinkedFact_notAPlan_throws() {
        UUID factId = UUID.randomUUID();
        FinancialEvent fact = aFact(factId);
        when(eventRepository.findById(factId)).thenReturn(Optional.of(fact));

        assertThatThrownBy(() ->
                service.createLinkedFact(factId,
                        new FactCreateDto(LocalDate.now(), BigDecimal.TEN, null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("createLinkedFact: создаёт FACT запись и переводит план в EXECUTED")
    void createLinkedFact_success_createsFact() {
        UUID planId = UUID.randomUUID();
        Category cat = category();
        FinancialEvent plan = aPlan(planId, cat, EventStatus.PLANNED);

        when(eventRepository.findById(planId)).thenReturn(Optional.of(plan));
        when(targetFundRepository.findById(any())).thenReturn(Optional.empty());

        FinancialEvent savedFact = FinancialEvent.builder()
                .id(UUID.randomUUID())
                .eventKind(EventKind.FACT)
                .parentEventId(planId)
                .date(LocalDate.now())
                .category(cat)
                .type(EventType.EXPENSE)
                .factAmount(BigDecimal.TEN)
                .status(EventStatus.EXECUTED)
                .build();
        // first save = fact, second save = plan status update
        when(eventRepository.save(any())).thenReturn(savedFact, plan);
        when(eventRepository.findFactAggregatesByPlanIds(any())).thenReturn(Collections.emptyList());

        FinancialEventDto result = service.createLinkedFact(planId,
                new FactCreateDto(LocalDate.now(), BigDecimal.TEN, "оплатил", null));

        assertThat(result.eventKind()).isEqualTo(EventKind.FACT);
        assertThat(result.parentEventId()).isEqualTo(planId);
        assertThat(result.factAmount()).isEqualByComparingTo(BigDecimal.TEN);
        assertThat(plan.getStatus()).isEqualTo(EventStatus.EXECUTED);
        verify(eventRepository, times(2)).save(any());
    }

    @Test
    @DisplayName("createLinkedFact: без priority в DTO — наследует от плана")
    void createLinkedFact_noPriority_inheritsPlanPriority() {
        UUID planId = UUID.randomUUID();
        Category cat = category(); // returns Priority.HIGH
        FinancialEvent plan = aPlan(planId, cat, EventStatus.PLANNED);

        when(eventRepository.findById(planId)).thenReturn(Optional.of(plan));
        when(targetFundRepository.findById(any())).thenReturn(Optional.empty());

        FinancialEvent[] saved = new FinancialEvent[1];
        when(eventRepository.save(any())).thenAnswer(inv -> {
            FinancialEvent e = inv.getArgument(0);
            if (e.getEventKind() == EventKind.FACT) saved[0] = e;
            return e;
        });
        when(eventRepository.findFactAggregatesByPlanIds(any())).thenReturn(Collections.emptyList());

        service.createLinkedFact(planId,
                new FactCreateDto(LocalDate.now(), BigDecimal.TEN, null, null));

        assertThat(saved[0].getPriority()).isEqualTo(Priority.HIGH);
    }

    @Test
    @DisplayName("createLinkedFact: priority в DTO — использует его, не наследует")
    void createLinkedFact_withPriority_usesDtoPriority() {
        UUID planId = UUID.randomUUID();
        Category cat = category(); // returns Priority.HIGH
        FinancialEvent plan = aPlan(planId, cat, EventStatus.PLANNED);

        when(eventRepository.findById(planId)).thenReturn(Optional.of(plan));
        when(targetFundRepository.findById(any())).thenReturn(Optional.empty());

        FinancialEvent[] saved = new FinancialEvent[1];
        when(eventRepository.save(any())).thenAnswer(inv -> {
            FinancialEvent e = inv.getArgument(0);
            if (e.getEventKind() == EventKind.FACT) saved[0] = e;
            return e;
        });
        when(eventRepository.findFactAggregatesByPlanIds(any())).thenReturn(Collections.emptyList());

        service.createLinkedFact(planId,
                new FactCreateDto(LocalDate.now(), BigDecimal.TEN, null, Priority.LOW));

        assertThat(saved[0].getPriority()).isEqualTo(Priority.LOW);
    }

    @Test
    @DisplayName("softDelete: бросает 409 при попытке удалить PLAN с привязанными FACTs")
    void softDelete_planWithFacts_throws409() {
        UUID planId = UUID.randomUUID();
        FinancialEvent plan = aPlan(planId, category(), EventStatus.EXECUTED);
        when(eventRepository.findById(planId)).thenReturn(Optional.of(plan));

        FactAggregateProjection agg = mock(FactAggregateProjection.class);
        when(agg.getCount()).thenReturn(1L);
        when(eventRepository.findFactAggregatesByPlanIds(List.of(planId))).thenReturn(List.of(agg));

        assertThatThrownBy(() -> service.softDelete(planId))
                .hasMessageContaining("Cannot delete PLAN");
    }

    @Test
    @DisplayName("softDelete: удаление FACT переводит родительский PLAN обратно в PLANNED")
    void softDelete_lastFact_revertsPlanToPlanned() {
        UUID planId = UUID.randomUUID();
        UUID factId = UUID.randomUUID();
        Category cat = category();
        FinancialEvent plan = aPlan(planId, cat, EventStatus.EXECUTED);
        FinancialEvent fact = FinancialEvent.builder()
                .id(factId)
                .eventKind(EventKind.FACT)
                .parentEventId(planId)
                .category(cat)
                .type(EventType.EXPENSE)
                .factAmount(BigDecimal.TEN)
                .status(EventStatus.EXECUTED)
                .deleted(false)
                .build();

        when(eventRepository.findById(factId)).thenReturn(Optional.of(fact));
        when(eventRepository.findById(planId)).thenReturn(Optional.of(plan));
        // After deletion, no remaining facts
        when(eventRepository.findFactAggregatesByPlanIds(List.of(planId)))
                .thenReturn(Collections.emptyList());
        when(eventRepository.save(any())).thenReturn(fact);
        doNothing().when(eventRepository).flush();

        service.softDelete(factId);

        assertThat(plan.getStatus()).isEqualTo(EventStatus.PLANNED);
        verify(eventRepository, times(2)).save(any()); // deleted fact + reverted plan
    }

    // --- helpers ---

    private Category category() {
        return Category.builder()
                .id(UUID.randomUUID()).name("Коммуналка")
                .type(CategoryType.EXPENSE).priority(Priority.HIGH).build();
    }

    private FinancialEvent aPlan(UUID id, Category cat, EventStatus status) {
        return FinancialEvent.builder()
                .id(id).eventKind(EventKind.PLAN).category(cat)
                .type(EventType.EXPENSE).plannedAmount(new BigDecimal("5000"))
                .status(status).priority(Priority.HIGH).date(LocalDate.now()).build();
    }

    private FinancialEvent aFact(UUID id) {
        return FinancialEvent.builder()
                .id(id).eventKind(EventKind.FACT).category(category())
                .type(EventType.EXPENSE).factAmount(BigDecimal.TEN)
                .status(EventStatus.EXECUTED).build();
    }
}
