# Plan/Fact Transaction Split — Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split `FinancialEvent` into PLAN/FACT roles via `event_kind` + `parent_event_id` in the same table; wire up the journal UI; fix balance calculations for overdue mandatory plans.

**Architecture:** Single table `financial_events` gains two new columns — `event_kind` (PLAN|FACT) and `parent_event_id` (nullable FK). Records without a parent and `event_kind=PLAN` are plans; records with `event_kind=FACT` are actuals (optionally linked to a plan). Balance services are updated to query FACT records for actuals and deduct overdue mandatory PLANs.

**Tech Stack:** Java 21, Spring Boot, JPA/Hibernate, PostgreSQL 15, Flyway, Lombok, JUnit 5 + Mockito, React 18 + TypeScript, Tailwind + Shadcn

---

## Chunk 1: DB Migration + Entity

**Files:**
- Create: `backend/src/main/resources/db/migration/V12__split_plan_fact.sql`
- Create: `backend/src/main/java/ru/selfin/backend/model/EventKind.java`
- Create: `backend/src/main/java/ru/selfin/backend/model/Transaction.java`
- Modify: `backend/src/main/java/ru/selfin/backend/model/FinancialEvent.java`

---

- [ ] **Step 1.1: Write V12 migration**

Create `backend/src/main/resources/db/migration/V12__split_plan_fact.sql`:

```sql
-- V12: Split plan/fact — add event_kind and parent_event_id columns.
-- Existing events with fact_amount != null are split into:
--   original record  → PLAN  (fact_amount cleared, status EXECUTED)
--   new record       → FACT  (fact_amount copied, parent_event_id = original id)

-- 1. Add columns (nullable first for safe migration)
ALTER TABLE financial_events
    ADD COLUMN event_kind      VARCHAR(10),
    ADD COLUMN parent_event_id UUID REFERENCES financial_events(id);

-- 2. Mark all existing records as PLANs
UPDATE financial_events SET event_kind = 'PLAN';

-- 3. Insert FACT records for all executed events (fact_amount IS NOT NULL)
INSERT INTO financial_events (
    id, idempotency_key, date, category_id, type,
    planned_amount, fact_amount, status, priority,
    description, raw_input, url, target_fund_id,
    is_deleted, created_at, updated_at,
    event_kind, parent_event_id
)
SELECT
    gen_random_uuid(),   -- new id for FACT
    gen_random_uuid(),   -- new idempotency_key (must be unique)
    date, category_id, type,
    NULL,                -- PLANs own the plannedAmount; FACTs have none
    fact_amount,
    'EXECUTED',
    priority, description, raw_input, url, target_fund_id,
    FALSE, NOW(), NOW(),
    'FACT',
    id                   -- link back to PLAN
FROM financial_events
WHERE fact_amount IS NOT NULL
  AND is_deleted = FALSE;

-- 4. Clear fact_amount from original PLAN records
UPDATE financial_events
SET fact_amount = NULL
WHERE fact_amount IS NOT NULL
  AND event_kind = 'PLAN';

-- 5. Make event_kind NOT NULL now that all rows have a value
ALTER TABLE financial_events
    ALTER COLUMN event_kind SET NOT NULL;
```

- [ ] **Step 1.2: Create EventKind enum**

Create `backend/src/main/java/ru/selfin/backend/model/EventKind.java`:

```java
package ru.selfin.backend.model;

public enum EventKind {
    PLAN,
    FACT
}
```

- [ ] **Step 1.3: Create Transaction interface**

Create `backend/src/main/java/ru/selfin/backend/model/Transaction.java`:

```java
package ru.selfin.backend.model;

import ru.selfin.backend.model.enums.EventType;

import java.time.LocalDate;
import java.util.UUID;

/** Common read contract for both PLAN and FACT records. */
public interface Transaction {
    UUID getId();
    LocalDate getDate();
    Category getCategory();
    EventType getType();
    String getDescription();
    EventKind getEventKind();
}
```

- [ ] **Step 1.4: Add fields to FinancialEvent entity**

In `FinancialEvent.java`, add after the `targetFundId` field (before `deleted`):

```java
@Enumerated(EnumType.STRING)
@Column(name = "event_kind", nullable = false)
@Builder.Default
private EventKind eventKind = EventKind.PLAN;

@Column(name = "parent_event_id")
private UUID parentEventId;
```

Also make the class implement `Transaction`:
```java
public class FinancialEvent implements Transaction {
```

Add the missing import:
```java
import ru.selfin.backend.model.EventKind;
import ru.selfin.backend.model.Transaction;
```

- [ ] **Step 1.5: Verify migration compiles and runs**

```bash
cd /c/Users/Kirill/IdeaProjects/selfin/backend
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -Dtest=BackendApplicationTests -pl . 2>&1 | tail -30
```

Expected: BUILD SUCCESS (Flyway runs V12, entity compiles).

- [ ] **Step 1.6: Commit**

```bash
git add backend/src/main/resources/db/migration/V12__split_plan_fact.sql \
        backend/src/main/java/ru/selfin/backend/model/EventKind.java \
        backend/src/main/java/ru/selfin/backend/model/Transaction.java \
        backend/src/main/java/ru/selfin/backend/model/FinancialEvent.java
git commit -m "feat: add event_kind + parent_event_id to financial_events (V12 migration)"
```

---

## Chunk 2: Repository + DTOs

**Files:**
- Create: `backend/src/main/java/ru/selfin/backend/repository/FactAggregateProjection.java`
- Modify: `backend/src/main/java/ru/selfin/backend/repository/FinancialEventRepository.java`
- Modify: `backend/src/main/java/ru/selfin/backend/dto/FinancialEventDto.java`
- Modify: `backend/src/main/java/ru/selfin/backend/dto/FinancialEventCreateDto.java`
- Create: `backend/src/main/java/ru/selfin/backend/dto/FactCreateDto.java`

---

- [ ] **Step 2.1: Create FactAggregateProjection**

Create `backend/src/main/java/ru/selfin/backend/repository/FactAggregateProjection.java`:

```java
package ru.selfin.backend.repository;

import java.math.BigDecimal;
import java.util.UUID;

/** Spring Data projection for fact aggregates per parent plan. */
public interface FactAggregateProjection {
    UUID getParentEventId();
    Long getCount();
    BigDecimal getTotalAmount();
}
```

- [ ] **Step 2.2: Update repository — add new queries + fix effective sum**

Replace the body of `FinancialEventRepository.java` with:

```java
package ru.selfin.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.selfin.backend.model.EventKind;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.enums.EventStatus;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.Priority;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FinancialEventRepository extends JpaRepository<FinancialEvent, UUID> {

    List<FinancialEvent> findAllByDeletedFalseAndDateBetweenOrderByDateAsc(
            LocalDate start, LocalDate end);

    List<FinancialEvent> findAllByDeletedFalseAndDateBetween(LocalDate start, LocalDate end);

    Optional<FinancialEvent> findByIdempotencyKey(UUID idempotencyKey);

    /** Хотелки: LOW-priority PLANNED события без даты ИЛИ с датой раньше сегодня. */
    @Query("SELECT e FROM FinancialEvent e WHERE e.deleted = false " +
           "AND e.priority = :priority AND e.status = :status " +
           "AND e.eventKind = ru.selfin.backend.model.EventKind.PLAN " +
           "AND (e.date IS NULL OR e.date < :today) " +
           "ORDER BY e.createdAt ASC")
    List<FinancialEvent> findWishlistItems(
        @Param("priority") Priority priority,
        @Param("status") EventStatus status,
        @Param("today") LocalDate today);

    /**
     * Сумма эффективных сумм по типу для расчёта баланса кармашка (без привязки к дате).
     * После миграции:
     *   FACT.factAmount              → реально потраченное/полученное
     *   PLAN(PLANNED).plannedAmount  → запланированное (ещё не исполненное)
     *   PLAN(EXECUTED).planned       → 0 (исполнение уже учтено через FACT-записи)
     */
    @Query("""
        SELECT COALESCE(SUM(CASE
            WHEN e.eventKind = ru.selfin.backend.model.EventKind.FACT
                THEN e.factAmount
            WHEN e.eventKind = ru.selfin.backend.model.EventKind.PLAN
                 AND e.status <> ru.selfin.backend.model.enums.EventStatus.EXECUTED
                THEN e.plannedAmount
            ELSE 0
        END), 0)
        FROM FinancialEvent e WHERE e.type = :type AND e.deleted = false
        """)
    BigDecimal sumEffectiveByType(@Param("type") EventType type);

    @Query("""
        SELECT COALESCE(SUM(CASE
            WHEN e.eventKind = ru.selfin.backend.model.EventKind.FACT
                THEN e.factAmount
            WHEN e.eventKind = ru.selfin.backend.model.EventKind.PLAN
                 AND e.status <> ru.selfin.backend.model.enums.EventStatus.EXECUTED
                THEN e.plannedAmount
            ELSE 0
        END), 0)
        FROM FinancialEvent e WHERE e.type = :type AND e.deleted = false AND e.date >= :fromDate
        """)
    BigDecimal sumEffectiveByTypeFromDate(@Param("type") EventType type, @Param("fromDate") LocalDate fromDate);

    /**
     * Сумма фактических (только FACT-записи) по типу.
     * После миграции factAmount гарантированно есть только у FACT-записей.
     */
    @Query("""
        SELECT COALESCE(SUM(e.factAmount), 0) FROM FinancialEvent e
        WHERE e.type = :type
          AND e.eventKind = ru.selfin.backend.model.EventKind.FACT
          AND e.deleted = false
        """)
    BigDecimal sumFactExecutedByType(@Param("type") EventType type);

    @Query("""
        SELECT COALESCE(SUM(e.factAmount), 0) FROM FinancialEvent e
        WHERE e.type = :type
          AND e.eventKind = ru.selfin.backend.model.EventKind.FACT
          AND e.deleted = false AND e.date >= :fromDate
        """)
    BigDecimal sumFactExecutedByTypeFromDate(@Param("type") EventType type, @Param("fromDate") LocalDate fromDate);

    /** Планировщик фондов: все не-удалённые PLANы с любым статусом кроме CANCELLED */
    @Query("SELECT e FROM FinancialEvent e WHERE e.deleted = false " +
           "AND e.eventKind = ru.selfin.backend.model.EventKind.PLAN " +
           "AND e.status <> :excludeStatus")
    List<FinancialEvent> findAllByDeletedFalseAndStatusNot(@Param("excludeStatus") EventStatus excludeStatus);

    /**
     * Агрегаты фактов по родительским планам для обогащения DTO.
     * Возвращает (parentEventId, count, totalAmount) для каждого уникального parentEventId из списка.
     */
    @Query("""
        SELECT e.parentEventId as parentEventId, COUNT(e) as count, SUM(e.factAmount) as totalAmount
        FROM FinancialEvent e
        WHERE e.parentEventId IN :planIds
          AND e.deleted = false
          AND e.eventKind = ru.selfin.backend.model.EventKind.FACT
        GROUP BY e.parentEventId
        """)
    List<FactAggregateProjection> findFactAggregatesByPlanIds(@Param("planIds") List<UUID> planIds);

    /**
     * Просроченные обязательные (HIGH) расходы текущего месяца, которые ещё не исполнены.
     * Используется для резервирования в балансе кармашка и прогнозах.
     */
    @Query("""
        SELECT COALESCE(SUM(e.plannedAmount), 0) FROM FinancialEvent e
        WHERE e.eventKind = ru.selfin.backend.model.EventKind.PLAN
          AND e.type = ru.selfin.backend.model.enums.EventType.EXPENSE
          AND e.priority = ru.selfin.backend.model.enums.Priority.HIGH
          AND e.status = ru.selfin.backend.model.enums.EventStatus.PLANNED
          AND e.date >= :monthStart
          AND e.date < :today
          AND e.deleted = false
        """)
    BigDecimal sumOverdueMandatoryExpenses(
        @Param("monthStart") LocalDate monthStart,
        @Param("today") LocalDate today);
}
```

- [ ] **Step 2.3: Update FinancialEventDto record**

Replace `FinancialEventDto.java` with:

```java
package ru.selfin.backend.dto;

import ru.selfin.backend.model.EventKind;
import ru.selfin.backend.model.enums.EventStatus;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.Priority;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

public record FinancialEventDto(
        UUID id,
        LocalDate date,
        UUID categoryId,
        String categoryName,
        EventType type,
        BigDecimal plannedAmount,
        BigDecimal factAmount,
        EventStatus status,
        Priority priority,
        String description,
        String rawInput,
        LocalDateTime createdAt,
        UUID targetFundId,
        String targetFundName,
        String url,
        // Plan/Fact split fields
        EventKind eventKind,
        UUID parentEventId,
        int linkedFactsCount,
        BigDecimal linkedFactsAmount,
        String parentPlanDescription) {
}
```

- [ ] **Step 2.4: Update FinancialEventCreateDto — remove factAmount**

Replace `FinancialEventCreateDto.java` with:

```java
package ru.selfin.backend.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.Priority;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record FinancialEventCreateDto(
                @NotNull LocalDate date,
                UUID categoryId,
                @NotNull EventType type,
                @PositiveOrZero BigDecimal plannedAmount,
                Priority priority,
                String description,
                String rawInput,
                UUID targetFundId) {
}
```

- [ ] **Step 2.5: Create FactCreateDto**

Create `backend/src/main/java/ru/selfin/backend/dto/FactCreateDto.java`:

```java
package ru.selfin.backend.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;

public record FactCreateDto(
        @NotNull LocalDate date,
        @NotNull @Positive BigDecimal factAmount,
        String description) {
}
```

- [ ] **Step 2.6: Compile check**

```bash
cd /c/Users/Kirill/IdeaProjects/selfin/backend
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw compile -pl . 2>&1 | tail -40
```

Expected: BUILD SUCCESS. Fix any compilation errors from the removed `factAmount` field in CreateDto (the `update()` and `createIdempotent()` methods in FinancialEventService still reference it — those will be fixed in Chunk 3).

- [ ] **Step 2.7: Commit**

```bash
git add backend/src/main/java/ru/selfin/backend/repository/ \
        backend/src/main/java/ru/selfin/backend/dto/
git commit -m "feat: update repository queries for PLAN/FACT model + new DTOs"
```

---

## Chunk 3: FinancialEventService

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/service/FinancialEventService.java`
- Modify: `backend/src/test/java/ru/selfin/backend/service/` (any existing test that creates `FinancialEventCreateDto` with `factAmount`)

---

- [ ] **Step 3.1: Write failing service unit tests for createLinkedFact**

Create `backend/src/test/java/ru/selfin/backend/service/FinancialEventServiceTest.java`:

```java
package ru.selfin.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.selfin.backend.dto.FactCreateDto;
import ru.selfin.backend.dto.FinancialEventDto;
import ru.selfin.backend.exception.ResourceNotFoundException;
import ru.selfin.backend.model.*;
import ru.selfin.backend.model.enums.*;
import ru.selfin.backend.repository.CategoryRepository;
import ru.selfin.backend.repository.FinancialEventRepository;
import ru.selfin.backend.repository.TargetFundRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FinancialEventServiceTest {

    private final FinancialEventRepository eventRepository = mock(FinancialEventRepository.class);
    private final CategoryRepository categoryRepository = mock(CategoryRepository.class);
    private final TargetFundRepository targetFundRepository = mock(TargetFundRepository.class);
    private final TargetFundService targetFundService = mock(TargetFundService.class);

    private final FinancialEventService service =
            new FinancialEventService(eventRepository, categoryRepository, targetFundRepository);

    @Test
    @DisplayName("createLinkedFact: бросает исключение если plan не найден")
    void createLinkedFact_planNotFound_throws() {
        when(eventRepository.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                service.createLinkedFact(UUID.randomUUID(),
                        new FactCreateDto(LocalDate.now(), BigDecimal.TEN, null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("createLinkedFact: бросает исключение если запись не является PLAN")
    void createLinkedFact_notAPlan_throws() {
        UUID factId = UUID.randomUUID();
        FinancialEvent fact = aFact(factId);
        when(eventRepository.findById(factId)).thenReturn(Optional.of(fact));

        assertThatThrownBy(() ->
                service.createLinkedFact(factId,
                        new FactCreateDto(LocalDate.now(), BigDecimal.TEN, null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("createLinkedFact: создаёт FACT и переводит план в EXECUTED")
    void createLinkedFact_success_createsFact() {
        UUID planId = UUID.randomUUID();
        Category cat = category();
        FinancialEvent plan = aPlan(planId, cat, EventStatus.PLANNED);
        when(eventRepository.findById(planId)).thenReturn(Optional.of(plan));

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
        when(eventRepository.save(any())).thenReturn(savedFact, plan);

        FinancialEventDto result = service.createLinkedFact(planId,
                new FactCreateDto(LocalDate.now(), BigDecimal.TEN, "оплатил"));

        assertThat(result.eventKind()).isEqualTo(EventKind.FACT);
        assertThat(result.parentEventId()).isEqualTo(planId);
        assertThat(result.factAmount()).isEqualByComparingTo(BigDecimal.TEN);
        assertThat(plan.getStatus()).isEqualTo(EventStatus.EXECUTED);
        verify(eventRepository, times(2)).save(any());
    }

    // --- helpers ---

    private Category category() {
        return Category.builder().id(UUID.randomUUID()).name("Коммуналка")
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
```

Note: `FinancialEventService` requires the `@Lazy TargetFundService` to be set via a setter after construction; the test must call `service.setTargetFundService(targetFundService)` if the method exists, otherwise use `@Autowired`/reflection — see Step 3.3.

- [ ] **Step 3.2: Run tests to verify they fail**

```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test \
    -Dtest=FinancialEventServiceTest -pl backend 2>&1 | tail -20
```

Expected: compilation failure (method `createLinkedFact` not found yet).

- [ ] **Step 3.3: Update FinancialEventService**

Replace the full content of `FinancialEventService.java` with the updated version below. Key changes:
1. `createIdempotent` — remove `factAmount` from builder, always sets `eventKind = PLAN`
2. `update()` — accepts updated `FinancialEventCreateDto` (no factAmount), removes status auto-compute from factAmount
3. `updateFact()` — **keep as-is for now** (will be removed in Chunk 4 when the new endpoint is live)
4. New `createLinkedFact()` method
5. `softDelete()` — reject deletion of PLAN that has linked FACTs; recompute PLAN status when a FACT is deleted
6. `toDto()` — includes `eventKind`, `parentEventId`, and enrichment fields (with lazy defaults)
7. Updated `findByPeriod()` — enriches PLANs with `linkedFactsCount` and `linkedFactsAmount`

```java
package ru.selfin.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ru.selfin.backend.dto.*;
import ru.selfin.backend.exception.ResourceNotFoundException;
import ru.selfin.backend.model.*;
import ru.selfin.backend.model.enums.*;
import ru.selfin.backend.repository.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FinancialEventService {

    private final FinancialEventRepository eventRepository;
    private final CategoryRepository categoryRepository;
    private final TargetFundRepository targetFundRepository;

    @Autowired @Lazy
    private TargetFundService targetFundService;

    public List<FinancialEventDto> findByPeriod(LocalDate start, LocalDate end) {
        List<FinancialEvent> events =
                eventRepository.findAllByDeletedFalseAndDateBetweenOrderByDateAsc(start, end);

        // Build fact-aggregate map for PLAN enrichment
        List<UUID> planIds = events.stream()
                .filter(e -> e.getEventKind() == EventKind.PLAN)
                .map(FinancialEvent::getId)
                .toList();

        Map<UUID, FactAggregateProjection> aggByPlan = planIds.isEmpty()
                ? Collections.emptyMap()
                : eventRepository.findFactAggregatesByPlanIds(planIds).stream()
                        .collect(Collectors.toMap(FactAggregateProjection::getParentEventId, p -> p));

        // Build parent-plan lookup for FACT enrichment
        Set<UUID> parentIds = events.stream()
                .filter(e -> e.getEventKind() == EventKind.FACT && e.getParentEventId() != null)
                .map(FinancialEvent::getParentEventId)
                .collect(Collectors.toSet());

        Map<UUID, FinancialEvent> parentById = parentIds.isEmpty()
                ? Collections.emptyMap()
                : eventRepository.findAllById(parentIds).stream()
                        .collect(Collectors.toMap(FinancialEvent::getId, e -> e));

        return events.stream()
                .map(e -> toDto(e, aggByPlan.get(e.getId()), parentById.get(e.getParentEventId())))
                .toList();
    }

    @Transactional
    public FinancialEventDto createIdempotent(UUID idempotencyKey, FinancialEventCreateDto dto) {
        return eventRepository.findByIdempotencyKey(idempotencyKey)
                .map(e -> toDto(e, null, null))
                .orElseGet(() -> {
                    Category category;
                    if (dto.type() == EventType.FUND_TRANSFER && dto.categoryId() == null) {
                        category = targetFundService.getOrCreateFundTransferCategory();
                    } else {
                        category = categoryRepository.findById(dto.categoryId())
                                .filter(c -> !c.isDeleted())
                                .orElseThrow(() -> new ResourceNotFoundException("Category", dto.categoryId()));
                    }
                    FinancialEvent event = FinancialEvent.builder()
                            .idempotencyKey(idempotencyKey)
                            .eventKind(EventKind.PLAN)
                            .date(dto.date())
                            .category(category)
                            .type(dto.type())
                            .plannedAmount(dto.plannedAmount())
                            .priority(dto.priority() != null ? dto.priority() : category.getPriority())
                            .description(dto.description())
                            .rawInput(dto.rawInput())
                            .targetFundId(dto.targetFundId())
                            .build();
                    return toDto(eventRepository.save(event), null, null);
                });
    }

    @Transactional
    public FinancialEventDto update(UUID id, FinancialEventCreateDto dto) {
        FinancialEvent event = eventRepository.findById(id)
                .filter(e -> !e.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("FinancialEvent", id));

        if (event.getEventKind() == EventKind.FACT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Use PATCH /events/{id}/fact to update a FACT record");
        }

        Category category = categoryRepository.findById(dto.categoryId())
                .filter(c -> !c.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("Category", dto.categoryId()));

        event.setDate(dto.date());
        event.setCategory(category);
        event.setType(dto.type());
        event.setPlannedAmount(dto.plannedAmount());
        event.setPriority(dto.priority() != null ? dto.priority() : category.getPriority());
        event.setDescription(dto.description());
        event.setRawInput(dto.rawInput());
        event.setTargetFundId(dto.targetFundId());

        return toDto(eventRepository.save(event), null, null);
    }

    @Transactional
    public FinancialEventDto createLinkedFact(UUID planId, FactCreateDto dto) {
        FinancialEvent plan = eventRepository.findById(planId)
                .filter(e -> !e.isDeleted() && e.getEventKind() == EventKind.PLAN)
                .orElseThrow(() -> new ResourceNotFoundException("FinancialEvent (PLAN)", planId));

        FinancialEvent fact = FinancialEvent.builder()
                .idempotencyKey(UUID.randomUUID())
                .eventKind(EventKind.FACT)
                .parentEventId(planId)
                .date(dto.date())
                .category(plan.getCategory())
                .type(plan.getType())
                .factAmount(dto.factAmount())
                .priority(Priority.MEDIUM)
                .status(EventStatus.EXECUTED)
                .description(dto.description())
                .build();

        FinancialEvent savedFact = eventRepository.save(fact);

        if (plan.getStatus() == EventStatus.PLANNED) {
            plan.setStatus(EventStatus.EXECUTED);
            eventRepository.save(plan);
        }

        return toDto(savedFact, null, plan);
    }

    @Transactional
    public FinancialEventDto updateFact(UUID id, FinancialEventUpdateFactDto dto) {
        FinancialEvent event = eventRepository.findById(id)
                .filter(e -> !e.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("FinancialEvent", id));

        BigDecimal oldFact = event.getFactAmount();
        event.setFactAmount(dto.factAmount());
        if (dto.description() != null) event.setDescription(dto.description());

        if (dto.factAmount() != null && event.getStatus() == EventStatus.PLANNED)
            event.setStatus(EventStatus.EXECUTED);
        else if (dto.factAmount() == null && event.getStatus() == EventStatus.EXECUTED)
            event.setStatus(EventStatus.PLANNED);

        if (event.getType() == EventType.FUND_TRANSFER
                && event.getTargetFundId() != null
                && dto.factAmount() != null
                && oldFact == null
                && event.getIdempotencyKey() != null) {
            targetFundService.doTransferForEvent(
                    event.getTargetFundId(), dto.factAmount(), event.getIdempotencyKey());
        }

        BigDecimal delta = (dto.factAmount() != null ? dto.factAmount() : BigDecimal.ZERO)
                .subtract(oldFact != null ? oldFact : BigDecimal.ZERO);
        log.info("fact_patch event_id={} category={} fact_old={} fact_new={} delta={}",
                id, event.getCategory().getName(), oldFact, dto.factAmount(), delta);

        return toDto(eventRepository.save(event), null, null);
    }

    @Transactional
    public FinancialEventDto cyclePriority(UUID id) {
        FinancialEvent event = eventRepository.findById(id)
                .filter(e -> !e.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("FinancialEvent", id));
        event.setPriority(nextPriority(event.getPriority()));
        return toDto(eventRepository.save(event), null, null);
    }

    private Priority nextPriority(Priority current) {
        return switch (current) {
            case HIGH -> Priority.MEDIUM;
            case MEDIUM -> Priority.LOW;
            case LOW -> Priority.HIGH;
        };
    }

    public List<FinancialEventDto> findWishlist() {
        return eventRepository.findWishlistItems(Priority.LOW, EventStatus.PLANNED, LocalDate.now())
                .stream().map(e -> toDto(e, null, null)).toList();
    }

    @Transactional
    public void softDelete(UUID id) {
        FinancialEvent event = eventRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("FinancialEvent", id));

        if (event.getEventKind() == EventKind.PLAN) {
            List<FactAggregateProjection> aggs =
                    eventRepository.findFactAggregatesByPlanIds(List.of(id));
            if (!aggs.isEmpty() && aggs.get(0).getCount() > 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Cannot delete PLAN with linked FACTs — delete FACTs first");
            }
        }

        event.setDeleted(true);
        eventRepository.save(event);

        // If deleting a FACT, revert parent PLAN to PLANNED if no other FACTs exist
        if (event.getEventKind() == EventKind.FACT && event.getParentEventId() != null) {
            eventRepository.findById(event.getParentEventId()).ifPresent(plan -> {
                List<FactAggregateProjection> aggs =
                        eventRepository.findFactAggregatesByPlanIds(List.of(plan.getId()));
                if (aggs.isEmpty() || aggs.get(0).getCount() == 0) {
                    plan.setStatus(EventStatus.PLANNED);
                    eventRepository.save(plan);
                }
            });
        }
    }

    public FinancialEventDto toDto(FinancialEvent e) {
        return toDto(e, null, null);
    }

    public FinancialEventDto toDto(FinancialEvent e,
                                   FactAggregateProjection agg,
                                   FinancialEvent parentPlan) {
        String fundName = null;
        if (e.getTargetFundId() != null) {
            fundName = targetFundRepository.findById(e.getTargetFundId())
                    .map(f -> f.getName()).orElse(null);
        }

        int linkedFactsCount = agg != null ? agg.getCount().intValue() : 0;
        BigDecimal linkedFactsAmount = agg != null ? agg.getTotalAmount() : null;

        String parentPlanDescription = null;
        if (parentPlan != null) {
            parentPlanDescription = parentPlan.getDescription() != null
                    ? parentPlan.getDescription()
                    : parentPlan.getCategory().getName();
        }

        return new FinancialEventDto(
                e.getId(), e.getDate(),
                e.getCategory().getId(), e.getCategory().getName(),
                e.getType(), e.getPlannedAmount(), e.getFactAmount(),
                e.getStatus(), e.getPriority(), e.getDescription(),
                e.getRawInput(), e.getCreatedAt(),
                e.getTargetFundId(), fundName, e.getUrl(),
                e.getEventKind(), e.getParentEventId(),
                linkedFactsCount, linkedFactsAmount, parentPlanDescription);
    }

    @Transactional
    public FinancialEventDto createWishlistItem(WishlistCreateDto dto) {
        Category category = categoryRepository.findByNameAndDeletedFalse("Хотелки")
                .orElseGet(() -> categoryRepository.save(
                        Category.builder()
                                .name("Хотелки")
                                .type(CategoryType.EXPENSE)
                                .build()));

        FinancialEvent event = FinancialEvent.builder()
                .eventKind(EventKind.PLAN)
                .category(category)
                .type(EventType.EXPENSE)
                .priority(Priority.LOW)
                .status(EventStatus.PLANNED)
                .description(dto.description())
                .plannedAmount(dto.plannedAmount())
                .url(dto.url())
                .build();

        return toDto(eventRepository.save(event), null, null);
    }
}
```

- [ ] **Step 3.4: Run service unit tests**

```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test \
    -Dtest=FinancialEventServiceTest -pl backend 2>&1 | tail -20
```

Expected: all 3 tests PASS.

- [ ] **Step 3.5: Run full test suite to catch regressions**

```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -pl backend 2>&1 | tail -40
```

Fix any failures (likely: existing service tests still pass factAmount in CreateDto — update those constructor calls to drop the `factAmount` arg).

- [ ] **Step 3.6: Commit**

```bash
git add backend/src/main/java/ru/selfin/backend/service/FinancialEventService.java \
        backend/src/test/java/ru/selfin/backend/service/FinancialEventServiceTest.java
git commit -m "feat: createLinkedFact, enriched findByPeriod, PLAN-safe softDelete"
```

---

## Chunk 4: Controller + IT Tests

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/controller/FinancialEventController.java`
- Modify: `backend/src/test/java/ru/selfin/backend/FinancialEventControllerIT.java`

---

- [ ] **Step 4.1: Add new endpoint to controller**

In `FinancialEventController.java`, add after the existing `PATCH /{id}/fact` mapping:

```java
@PostMapping("/{planId}/facts")
public FinancialEventDto createLinkedFact(
        @PathVariable UUID planId,
        @Valid @RequestBody FactCreateDto dto) {
    return eventService.createLinkedFact(planId, dto);
}
```

Add the import: `import ru.selfin.backend.dto.FactCreateDto;`

- [ ] **Step 4.2: Write IT test for createLinkedFact**

In `FinancialEventControllerIT.java`, add the test method:

```java
@Test
void createLinkedFact_success_createsFact() throws Exception {
    // Create parent PLAN via POST /events
    UUID planKey = UUID.randomUUID();
    String planBody = """
            {"date":"2026-03-19","categoryId":null,"type":"EXPENSE",
             "plannedAmount":5000,"priority":"HIGH","description":"Коммуналка"}
            """;
    // Note: categoryId must be a real UUID from seeded data. Use a known seeded category.
    // Alternatively, create category first. For simplicity, find category id via GET /categories.
    // Implementation note: adjust planBody with valid categoryId before running.

    String planResp = mockMvc.perform(post("/api/v1/events")
                    .header("Idempotency-Key", planKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(planBody))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

    String planId = objectMapper.readTree(planResp).get("id").asText();
    String eventKind = objectMapper.readTree(planResp).get("eventKind").asText();
    assertThat(eventKind).isEqualTo("PLAN");

    // Create linked FACT
    String factBody = """
            {"date":"2026-03-20","factAmount":4850,"description":"оплатил онлайн"}
            """;
    String factResp = mockMvc.perform(post("/api/v1/events/" + planId + "/facts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(factBody))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

    var factTree = objectMapper.readTree(factResp);
    assertThat(factTree.get("eventKind").asText()).isEqualTo("FACT");
    assertThat(factTree.get("parentEventId").asText()).isEqualTo(planId);
    assertThat(factTree.get("factAmount").decimalValue())
            .isEqualByComparingTo(new BigDecimal("4850"));

    // Verify plan is now EXECUTED
    String planResp2 = mockMvc.perform(get("/api/v1/events")
                    .param("startDate", "2026-03-19")
                    .param("endDate", "2026-03-20"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

    var events = objectMapper.readTree(planResp2);
    var plan = StreamSupport.stream(events.spliterator(), false)
            .filter(n -> n.get("id").asText().equals(planId))
            .findFirst().orElseThrow();
    assertThat(plan.get("status").asText()).isEqualTo("EXECUTED");
    assertThat(plan.get("linkedFactsCount").asInt()).isEqualTo(1);
}

@Test
void deletePlanWithLinkedFacts_returns409() throws Exception {
    // Create PLAN then FACT, then try to delete PLAN → expect 409
    // ... (similar setup as above, abbreviated for plan readability)
    // Arrange: create plan, create linked fact
    // Act: DELETE /api/v1/events/{planId}
    // Assert: status().isConflict()
}
```

- [ ] **Step 4.3: Run IT tests**

```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test \
    -Dtest=FinancialEventControllerIT -pl backend 2>&1 | tail -30
```

Expected: all tests PASS (including newly added ones).

- [ ] **Step 4.4: Commit**

```bash
git add backend/src/main/java/ru/selfin/backend/controller/FinancialEventController.java \
        backend/src/test/java/ru/selfin/backend/FinancialEventControllerIT.java
git commit -m "feat: POST /events/{planId}/facts endpoint + IT tests"
```

---

## Chunk 5: Balance Fix — TargetFundService, FundPlannerService, DashboardService

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/service/TargetFundService.java`
- Modify: `backend/src/main/java/ru/selfin/backend/service/FundPlannerService.java`
- Modify: `backend/src/main/java/ru/selfin/backend/service/DashboardService.java`
- Modify: `backend/src/test/java/ru/selfin/backend/service/TargetFundServiceTest.java`
- Modify: `backend/src/test/java/ru/selfin/backend/service/FundPlannerServiceTest.java`
- Modify: `backend/src/test/java/ru/selfin/backend/service/DashboardServiceTest.java`

---

- [ ] **Step 5.1: Read the three service files**

Read each file to understand current `calcPocketBalance`, `getPlanner`, and `getDashboard` methods before making changes.

```
backend/src/main/java/ru/selfin/backend/service/TargetFundService.java
backend/src/main/java/ru/selfin/backend/service/FundPlannerService.java
backend/src/main/java/ru/selfin/backend/service/DashboardService.java
```

- [ ] **Step 5.2: Write failing test — pocket balance deducts overdue mandatory plans**

In `TargetFundServiceTest.java`, add:

```java
@Test
@DisplayName("calcPocketBalance: вычитает просроченные обязательные планы")
void pocketBalance_deductsOverdueMandatoryExpenses() {
    // Arrange: checkpoint + fact income - fact expense - funds = 10000
    // + overdue mandatory plan 3000 → expected pocket = 7000
    when(eventRepository.sumOverdueMandatoryExpenses(any(), any()))
            .thenReturn(new BigDecimal("3000"));
    // ... mock other required calls per existing test patterns
    // Assert: pocketBalance == 10000 - 3000 = 7000
}
```

Study existing tests in `TargetFundServiceTest.java` for the exact mock setup pattern.

- [ ] **Step 5.3: Update TargetFundService.calcPocketBalance()**

Find the `calcPocketBalance` method. At the end of the balance calculation (after subtracting fundBalances), add:

```java
// Subtract overdue HIGH-priority PLANNED expenses from current month
LocalDate today = LocalDate.now();
BigDecimal overdueMandate = eventRepository.sumOverdueMandatoryExpenses(
        today.withDayOfMonth(1), today);
pocketBalance = pocketBalance.subtract(overdueMandate);
```

- [ ] **Step 5.4: Write failing test — planner month-0 includes overdue mandatory plans**

In `FundPlannerServiceTest.java`, add:

```java
@Test
@DisplayName("getPlanner: mandatoryExpenses месяца 0 включает просроченные HIGH планы")
void planner_month0_includesOverdueHighPlans() {
    // Arrange: mock sumOverdueMandatoryExpenses to return 2000
    when(eventRepository.sumOverdueMandatoryExpenses(any(), any()))
            .thenReturn(new BigDecimal("2000"));
    // Arrange: no future mandatory events (all dates < today)
    // Act
    FundPlannerDto planner = service.getPlanner();
    // Assert: mandatoryExpenses for month 0 includes the 2000
    assertThat(planner.months().get(0).mandatoryExpenses())
            .isGreaterThanOrEqualTo(new BigDecimal("2000"));
}
```

- [ ] **Step 5.5: Update FundPlannerService — month-0 logic**

In `FundPlannerService.getPlanner()`, for the current month (index 0), after computing `mandatoryExpenses` from future events, add the overdue mandatory sum:

```java
// For month 0: add overdue mandatory plans (past date, still PLANNED, HIGH priority)
if (i == 0) {
    BigDecimal overdueMandate = eventRepository.sumOverdueMandatoryExpenses(
            today.withDayOfMonth(1), today);
    mandatoryExpenses = mandatoryExpenses.add(overdueMandate);
}
```

- [ ] **Step 5.6: Write failing test — dashboard end-of-month forecast includes overdue plans**

In `DashboardServiceTest.java`, add:

```java
@Test
@DisplayName("getDashboard: прогноз на конец месяца включает просроченные обязательные планы")
void dashboard_endOfMonth_includesOverdueMandate() {
    when(eventRepository.sumOverdueMandatoryExpenses(any(), any()))
            .thenReturn(new BigDecimal("1500"));
    // ... mock existing calls
    // Assert: endOfMonthForecast is reduced by 1500 compared to baseline
}
```

- [ ] **Step 5.7: Update DashboardService.getDashboard()**

In the end-of-month forecast calculation, add:

```java
LocalDate monthStart = asOfDate.withDayOfMonth(1);
BigDecimal overdueMandate = eventRepository.sumOverdueMandatoryExpenses(monthStart, asOfDate);
// Subtract overdue mandatory plans from the forecast
endOfMonthForecast = endOfMonthForecast.subtract(overdueMandate);
```

- [ ] **Step 5.8: Run all balance-related tests**

```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test \
    -Dtest=TargetFundServiceTest,FundPlannerServiceTest,DashboardServiceTest \
    -pl backend 2>&1 | tail -30
```

Expected: all pass.

- [ ] **Step 5.9: Run full test suite**

```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -pl backend 2>&1 | tail -30
```

Expected: BUILD SUCCESS.

- [ ] **Step 5.10: Commit**

```bash
git add backend/src/main/java/ru/selfin/backend/service/TargetFundService.java \
        backend/src/main/java/ru/selfin/backend/service/FundPlannerService.java \
        backend/src/main/java/ru/selfin/backend/service/DashboardService.java \
        backend/src/test/java/ru/selfin/backend/service/
git commit -m "fix: deduct overdue mandatory planned expenses from pocket balance and forecasts"
```

---

## Chunk 6: Frontend

**Files:**
- Modify: `frontend/src/types/api.ts`
- Modify: `frontend/src/api/client.ts`
- Modify: `frontend/src/pages/Budget.tsx`
- Create: `frontend/src/components/FactCreateSheet.tsx`
- Modify: `frontend/src/components/EventSheet.tsx` (or the equivalent edit form component)

---

- [ ] **Step 6.1: Update api types**

In `frontend/src/types/api.ts`, update the `FinancialEvent` interface to add:

```typescript
eventKind: 'PLAN' | 'FACT';
parentEventId: string | null;
linkedFactsCount: number;
linkedFactsAmount: number | null;
parentPlanDescription: string | null;
```

Remove `factAmount` from the create DTO type if it has one:

```typescript
export interface FinancialEventCreateDto {
  date: string;
  categoryId: string;
  type: 'INCOME' | 'EXPENSE' | 'FUND_TRANSFER';
  plannedAmount: number;
  priority?: 'HIGH' | 'MEDIUM' | 'LOW';
  description?: string;
  rawInput?: string;
  targetFundId?: string;
}

export interface FactCreateDto {
  date: string;
  factAmount: number;
  description?: string;
}
```

- [ ] **Step 6.2: Add createLinkedFact to client.ts**

In `frontend/src/api/client.ts`, add:

```typescript
export async function createLinkedFact(planId: string, dto: FactCreateDto): Promise<FinancialEvent> {
  const res = await fetch(`${BASE_URL}/events/${planId}/facts`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(dto),
  });
  if (!res.ok) throw new Error(`createLinkedFact failed: ${res.status}`);
  return res.json();
}
```

- [ ] **Step 6.3: Create FactCreateSheet component**

Create `frontend/src/components/FactCreateSheet.tsx`:

```tsx
import { useState } from 'react';
import { Sheet, SheetContent, SheetHeader, SheetTitle } from './ui/sheet';
import { Button } from './ui/button';
import { Input } from './ui/input';
import { createLinkedFact } from '../api/client';
import type { FactCreateDto } from '../types/api';

interface Props {
  planId: string;
  planDescription: string;
  open: boolean;
  onClose: () => void;
  onCreated: () => void;
}

export function FactCreateSheet({ planId, planDescription, open, onClose, onCreated }: Props) {
  const today = new Date().toISOString().slice(0, 10);
  const [date, setDate] = useState(today);
  const [amount, setAmount] = useState('');
  const [description, setDescription] = useState('');
  const [loading, setLoading] = useState(false);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!amount) return;
    setLoading(true);
    try {
      const dto: FactCreateDto = {
        date,
        factAmount: parseFloat(amount),
        description: description || undefined,
      };
      await createLinkedFact(planId, dto);
      onCreated();
      onClose();
    } finally {
      setLoading(false);
    }
  }

  return (
    <Sheet open={open} onOpenChange={onClose}>
      <SheetContent>
        <SheetHeader>
          <SheetTitle>Записать факт</SheetTitle>
          <p className="text-sm text-muted-foreground">{planDescription}</p>
        </SheetHeader>
        <form onSubmit={handleSubmit} className="mt-4 flex flex-col gap-4">
          <div>
            <label className="text-sm font-medium">Дата</label>
            <Input type="date" value={date} onChange={e => setDate(e.target.value)} required />
          </div>
          <div>
            <label className="text-sm font-medium">Сумма факта</label>
            <Input
              type="number"
              min="0.01"
              step="0.01"
              value={amount}
              onChange={e => setAmount(e.target.value)}
              required
            />
          </div>
          <div>
            <label className="text-sm font-medium">Комментарий</label>
            <Input value={description} onChange={e => setDescription(e.target.value)} />
          </div>
          <Button type="submit" disabled={loading}>
            {loading ? 'Сохраняю...' : 'Сохранить факт'}
          </Button>
        </form>
      </SheetContent>
    </Sheet>
  );
}
```

- [ ] **Step 6.4: Update Budget.tsx — sort PLANs before FACTs within a day**

In `Budget.tsx`, find the code that groups events by day. Within each day's event list, sort so PLANs come first:

```typescript
// Inside the day rendering block, before mapping events to cards:
const sortedEvents = [...dayEvents].sort((a, b) => {
  if (a.eventKind === b.eventKind) return 0;
  return a.eventKind === 'PLAN' ? -1 : 1;
});
```

Add a separator between PLANs and FACTs in the day if both exist:

```tsx
const plansInDay = sortedEvents.filter(e => e.eventKind === 'PLAN');
const factsInDay = sortedEvents.filter(e => e.eventKind === 'FACT');
const hasBoth = plansInDay.length > 0 && factsInDay.length > 0;

// Render: plansInDay, then {hasBoth && <hr className="border-dashed my-1" />}, then factsInDay
```

- [ ] **Step 6.5: Update Budget.tsx — PLAN card additions**

On each PLAN event card, add:
- Badge showing `linkedFactsCount` if > 0: `{event.linkedFactsCount > 0 && <span className="text-xs text-muted-foreground">{event.linkedFactsCount} факт{event.linkedFactsCount === 1 ? '' : 'а'}</span>}`
- "Записать факт" button (for PLANNED status plans):

```tsx
{event.eventKind === 'PLAN' && event.status === 'PLANNED' && (
  <Button
    variant="ghost"
    size="sm"
    onClick={() => setFactSheetPlanId(event.id)}
  >
    Записать факт
  </Button>
)}
```

Add state: `const [factSheetPlanId, setFactSheetPlanId] = useState<string | null>(null);`

Render `FactCreateSheet` conditionally:
```tsx
{factSheetPlanId && (
  <FactCreateSheet
    planId={factSheetPlanId}
    planDescription={events.find(e => e.id === factSheetPlanId)?.description ?? 'План'}
    open={!!factSheetPlanId}
    onClose={() => setFactSheetPlanId(null)}
    onCreated={() => { refetch(); setFactSheetPlanId(null); }}
  />
)}
```

- [ ] **Step 6.6: Update Budget.tsx — FACT card additions**

On each FACT event card, if `parentPlanDescription` is set, show it:

```tsx
{event.eventKind === 'FACT' && event.parentPlanDescription && (
  <span className="text-xs text-muted-foreground">← {event.parentPlanDescription}</span>
)}
```

- [ ] **Step 6.7: Update edit form (EventSheet)**

Find the edit sheet/dialog component for events. Add conditional field rendering based on `eventKind`:
- If `eventKind === 'PLAN'`: show `plannedAmount`, `priority`, `categoryId`, `type`, `date`, `description`
- If `eventKind === 'FACT'`: show `factAmount`, `date`, `description` only (no plannedAmount, no priority selector)

Look at the existing EventSheet (or equivalent) component path, read it, then add:
```tsx
{event.eventKind !== 'FACT' && (
  <div>
    <label>Плановая сумма</label>
    <Input ... value={plannedAmount} ... />
  </div>
)}

{event.eventKind === 'FACT' && (
  <div>
    <label>Фактическая сумма</label>
    <Input ... value={factAmount} ... />
  </div>
)}

{event.eventKind !== 'FACT' && (
  <PrioritySelector ... />
)}
```

- [ ] **Step 6.8: Build frontend**

```bash
cd /c/Users/Kirill/IdeaProjects/selfin/frontend
npm run build 2>&1 | tail -30
```

Expected: build succeeds with no TypeScript errors. Fix any type errors.

- [ ] **Step 6.9: Commit**

```bash
git add frontend/src/
git commit -m "feat: journal plan/fact split UI — sort, fact card, FactCreateSheet, conditional edit form"
```

---

## Verification

1. **Migration runs cleanly:** `BackendApplicationTests` passes (V1–V12 applied)
2. **Existing events split correctly:** `GET /api/v1/events?startDate=...&endDate=...` for a month that had executed events returns both PLAN records (`eventKind=PLAN`, `status=EXECUTED`, `linkedFactsCount=1`) and FACT records (`eventKind=FACT`, `parentEventId` set)
3. **Create linked fact:** `POST /api/v1/events/{planId}/facts` → returns FACT DTO, plan becomes EXECUTED
4. **Reject delete of plan with facts:** `DELETE /api/v1/events/{planId}` → 409 Conflict
5. **Pocket balance:** Add a HIGH/EXPENSE PLAN with `date=yesterday`, no fact → `GET /api/v1/funds/overview` shows pocket balance reduced by plannedAmount
6. **Planner month-0:** Same plan → month-0 `mandatoryExpenses` increases
7. **Journal UI:** Refresh Budget page — PLANs appear before FACTs within each day; PLAN card shows "Записать факт" button; clicking it opens FactCreateSheet; submitting creates a linked FACT
8. **Edit form:** Opening a PLAN's edit form shows plannedAmount/priority; opening a FACT's edit form shows factAmount only
9. **All tests pass:** `JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -pl backend`
