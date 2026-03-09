# PR 1: Recurring Events

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Allow users to create repeating financial events (mortgage, rent) with "this and all following" edit semantics.

**Architecture:** A `RecurringRule` entity stores the recurrence template (frequency, day, amount, category). When created, it generates 12 months of PLANNED `FinancialEvent` rows linked via `recurring_rule_id` FK. Editing scope is passed as a `?scope=` query parameter on existing event endpoints. EXECUTED events are never modified by rule updates.

**Tech Stack:** Spring Boot, JPA/Hibernate, Flyway, PostgreSQL, React/TypeScript/Shadcn

---

## Task 1: Flyway migration — add recurring_rule table and FK column

**Files:**
- Create: `backend/src/main/resources/db/migration/V5__add_recurring_events.sql`

**Step 1: Write migration**

```sql
-- recurring_rule: template that drives periodic event generation
CREATE TABLE recurring_rule (
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    category_id    UUID        NOT NULL REFERENCES category(id),
    event_type     VARCHAR(20) NOT NULL,           -- INCOME | EXPENSE | FUND_TRANSFER
    target_fund_id UUID        REFERENCES target_fund(id),
    planned_amount NUMERIC(19,2) NOT NULL,
    mandatory      BOOLEAN     NOT NULL DEFAULT FALSE,
    description    VARCHAR(255),
    frequency      VARCHAR(10) NOT NULL,           -- MONTHLY | WEEKLY
    day_of_month   INTEGER,                        -- 1–28; used when frequency=MONTHLY
    day_of_week    VARCHAR(10),                    -- MON..SUN; used when frequency=WEEKLY
    start_date     DATE        NOT NULL,
    end_date       DATE,                           -- null = no end
    deleted        BOOLEAN     NOT NULL DEFAULT FALSE
);

-- Link each generated event back to its rule
ALTER TABLE financial_events
    ADD COLUMN recurring_rule_id UUID REFERENCES recurring_rule(id);
```

**Step 2: Start backend and verify Flyway applies migration**

```bash
cd backend
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw spring-boot:run 2>&1 | grep -E "Flyway|ERROR"
```

Expected: `Successfully applied 1 migration to schema "public"` — no errors.

**Step 3: Commit**

```bash
git add backend/src/main/resources/db/migration/V5__add_recurring_events.sql
git commit -m "feat(db): add recurring_rule table and link column on financial_events"
```

---

## Task 2: Backend model — RecurringFrequency enum + RecurringRule entity

**Files:**
- Create: `backend/src/main/java/ru/selfin/backend/model/enums/RecurringFrequency.java`
- Create: `backend/src/main/java/ru/selfin/backend/model/RecurringRule.java`
- Modify: `backend/src/main/java/ru/selfin/backend/model/FinancialEvent.java`

**Step 1: Create RecurringFrequency enum**

```java
package ru.selfin.backend.model.enums;

public enum RecurringFrequency {
    MONTHLY,
    WEEKLY
}
```

**Step 2: Create RecurringRule entity**

```java
package ru.selfin.backend.model;

import jakarta.persistence.*;
import lombok.*;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.RecurringFrequency;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "recurring_rule")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecurringRule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    private EventType eventType;

    /** Filled only when eventType = FUND_TRANSFER */
    @Column(name = "target_fund_id")
    private UUID targetFundId;

    @Column(name = "planned_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal plannedAmount;

    @Column(name = "mandatory", nullable = false)
    @Builder.Default
    private boolean mandatory = false;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RecurringFrequency frequency;

    /** Day of month (1–28). Used when frequency = MONTHLY. */
    @Column(name = "day_of_month")
    private Integer dayOfMonth;

    /** Day of week. Used when frequency = WEEKLY. */
    @Enumerated(EnumType.STRING)
    @Column(name = "day_of_week")
    private DayOfWeek dayOfWeek;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    /** Null means "forever". */
    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(nullable = false)
    @Builder.Default
    private boolean deleted = false;
}
```

**Step 3: Add recurringRuleId to FinancialEvent**

In `FinancialEvent.java`, add after the `rawInput` field:
```java
/** FK to RecurringRule that generated this event. Null for one-off events. */
@Column(name = "recurring_rule_id")
private UUID recurringRuleId;
```

**Step 4: Build to check compilation**

```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw compile -q
```

Expected: `BUILD SUCCESS`

**Step 5: Commit**

```bash
git add backend/src/main/java/ru/selfin/backend/model/
git commit -m "feat(backend): add RecurringRule entity and recurringRuleId on FinancialEvent"
```

---

## Task 3: Repository + DTOs

**Files:**
- Create: `backend/src/main/java/ru/selfin/backend/repository/RecurringRuleRepository.java`
- Create: `backend/src/main/java/ru/selfin/backend/dto/RecurringRuleCreateDto.java`
- Create: `backend/src/main/java/ru/selfin/backend/dto/RecurringRuleDto.java`
- Modify: `backend/src/main/java/ru/selfin/backend/dto/FinancialEventDto.java`
- Add query to: `backend/src/main/java/ru/selfin/backend/repository/FinancialEventRepository.java`

**Step 1: Create RecurringRuleRepository**

```java
package ru.selfin.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.selfin.backend.model.RecurringRule;

import java.util.UUID;

public interface RecurringRuleRepository extends JpaRepository<RecurringRule, UUID> {
}
```

**Step 2: Add queries to FinancialEventRepository**

```java
import ru.selfin.backend.model.enums.EventStatus;
import java.util.Optional;

// Add these method declarations to the interface:

/** All PLANNED events for a rule on or after fromDate — candidates for bulk update */
List<FinancialEvent> findAllByRecurringRuleIdAndDateGreaterThanEqualAndStatusAndDeletedFalse(
        UUID recurringRuleId, LocalDate fromDate, EventStatus status);

/** Latest event date for a rule — used to determine whether to extend the horizon */
@Query("SELECT MAX(e.date) FROM FinancialEvent e WHERE e.recurringRuleId = :ruleId AND e.deleted = false")
Optional<LocalDate> findMaxDateByRecurringRuleId(@Param("ruleId") UUID ruleId);
```

**Step 3: Create RecurringRuleCreateDto**

```java
package ru.selfin.backend.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.RecurringFrequency;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.UUID;

public record RecurringRuleCreateDto(
        @NotNull UUID categoryId,
        @NotNull EventType eventType,
        UUID targetFundId,              // required when eventType = FUND_TRANSFER
        @NotNull @PositiveOrZero BigDecimal plannedAmount,
        Boolean mandatory,
        String description,
        @NotNull RecurringFrequency frequency,
        Integer dayOfMonth,             // 1–28; required when frequency = MONTHLY
        DayOfWeek dayOfWeek,            // required when frequency = WEEKLY
        @NotNull LocalDate startDate,
        LocalDate endDate
) {}
```

**Step 4: Create RecurringRuleDto**

```java
package ru.selfin.backend.dto;

import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.RecurringFrequency;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.UUID;

public record RecurringRuleDto(
        UUID id,
        UUID categoryId,
        String categoryName,
        EventType eventType,
        UUID targetFundId,
        BigDecimal plannedAmount,
        boolean mandatory,
        String description,
        RecurringFrequency frequency,
        Integer dayOfMonth,
        DayOfWeek dayOfWeek,
        LocalDate startDate,
        LocalDate endDate
) {}
```

**Step 5: Add recurringRuleId to FinancialEventDto**

Read current `FinancialEventDto.java` — it's a record. Add `UUID recurringRuleId` as the last field:

```java
public record FinancialEventDto(
        UUID id,
        LocalDate date,
        UUID categoryId,
        String categoryName,
        EventType type,
        BigDecimal plannedAmount,
        BigDecimal factAmount,
        EventStatus status,
        boolean mandatory,
        String description,
        String rawInput,
        LocalDateTime createdAt,
        UUID recurringRuleId        // NEW
) {}
```

Also update `FinancialEventService.toDto()` to pass `e.getRecurringRuleId()` as the last argument.

**Step 6: Build**

```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw compile -q
```

Expected: `BUILD SUCCESS`

**Step 7: Commit**

```bash
git add backend/src/main/java/ru/selfin/backend/
git commit -m "feat(backend): add RecurringRule DTOs, repository queries, and recurringRuleId in FinancialEventDto"
```

---

## Task 4: RecurringRuleService — create + generate + update + delete

**Files:**
- Create: `backend/src/main/java/ru/selfin/backend/service/RecurringRuleService.java`

**Step 1: Write the service**

```java
package ru.selfin.backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.selfin.backend.dto.RecurringRuleCreateDto;
import ru.selfin.backend.dto.RecurringRuleDto;
import ru.selfin.backend.exception.ResourceNotFoundException;
import ru.selfin.backend.model.Category;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.RecurringRule;
import ru.selfin.backend.model.enums.EventStatus;
import ru.selfin.backend.model.enums.RecurringFrequency;
import ru.selfin.backend.repository.CategoryRepository;
import ru.selfin.backend.repository.FinancialEventRepository;
import ru.selfin.backend.repository.RecurringRuleRepository;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecurringRuleService {

    private static final int GENERATION_HORIZON_MONTHS = 12;

    private final RecurringRuleRepository ruleRepository;
    private final FinancialEventRepository eventRepository;
    private final CategoryRepository categoryRepository;

    /** Create a rule and generate 12 months of PLANNED events. */
    @Transactional
    public RecurringRuleDto createRule(RecurringRuleCreateDto dto) {
        Category category = categoryRepository.findById(dto.categoryId())
                .filter(c -> !c.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("Category", dto.categoryId()));

        RecurringRule rule = RecurringRule.builder()
                .category(category)
                .eventType(dto.eventType())
                .targetFundId(dto.targetFundId())
                .plannedAmount(dto.plannedAmount())
                .mandatory(Boolean.TRUE.equals(dto.mandatory()))
                .description(dto.description())
                .frequency(dto.frequency())
                .dayOfMonth(dto.dayOfMonth())
                .dayOfWeek(dto.dayOfWeek())
                .startDate(dto.startDate())
                .endDate(dto.endDate())
                .build();
        rule = ruleRepository.save(rule);

        LocalDate horizon = dto.startDate().plusMonths(GENERATION_HORIZON_MONTHS);
        if (dto.endDate() != null && dto.endDate().isBefore(horizon)) {
            horizon = dto.endDate();
        }
        generateEvents(rule, dto.startDate(), horizon, category);

        return toDto(rule);
    }

    /**
     * Update rule fields and all PLANNED events from fromDate onward.
     * EXECUTED events are never touched.
     */
    @Transactional
    public void updateThisAndFollowing(UUID ruleId, LocalDate fromDate, RecurringRuleCreateDto dto) {
        RecurringRule rule = ruleRepository.findById(ruleId)
                .filter(r -> !r.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("RecurringRule", ruleId));

        Category category = categoryRepository.findById(dto.categoryId())
                .filter(c -> !c.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("Category", dto.categoryId()));

        // Update rule
        rule.setCategory(category);
        rule.setPlannedAmount(dto.plannedAmount());
        rule.setMandatory(Boolean.TRUE.equals(dto.mandatory()));
        rule.setDescription(dto.description());
        rule.setDayOfMonth(dto.dayOfMonth());
        rule.setDayOfWeek(dto.dayOfWeek());
        ruleRepository.save(rule);

        // Update all PLANNED events in the series from fromDate forward
        List<FinancialEvent> toUpdate = eventRepository
                .findAllByRecurringRuleIdAndDateGreaterThanEqualAndStatusAndDeletedFalse(
                        ruleId, fromDate, EventStatus.PLANNED);

        boolean dayChanged = !java.util.Objects.equals(dto.dayOfMonth(), rule.getDayOfMonth());

        if (dayChanged) {
            // Soft-delete existing events and regenerate with new day
            toUpdate.forEach(e -> e.setDeleted(true));
            eventRepository.saveAll(toUpdate);

            LocalDate horizon = fromDate.plusMonths(GENERATION_HORIZON_MONTHS);
            generateEvents(rule, fromDate, horizon, category);
        } else {
            // In-place update
            toUpdate.forEach(e -> {
                e.setCategory(category);
                e.setPlannedAmount(dto.plannedAmount());
                e.setMandatory(Boolean.TRUE.equals(dto.mandatory()));
                e.setDescription(dto.description());
            });
            eventRepository.saveAll(toUpdate);
        }
    }

    /**
     * Soft-delete all PLANNED events in the series from fromDate onward.
     * If fromDate == rule.startDate, also soft-delete the rule itself.
     */
    @Transactional
    public void deleteThisAndFollowing(UUID ruleId, LocalDate fromDate) {
        RecurringRule rule = ruleRepository.findById(ruleId)
                .filter(r -> !r.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("RecurringRule", ruleId));

        List<FinancialEvent> toDelete = eventRepository
                .findAllByRecurringRuleIdAndDateGreaterThanEqualAndStatusAndDeletedFalse(
                        ruleId, fromDate, EventStatus.PLANNED);
        toDelete.forEach(e -> e.setDeleted(true));
        eventRepository.saveAll(toDelete);

        if (!fromDate.isAfter(rule.getStartDate())) {
            rule.setDeleted(true);
            ruleRepository.save(rule);
        }
    }

    /**
     * Ensure events exist up to upToDate. Called lazily when user views a far-future month.
     */
    @Transactional
    public void extendIfNeeded(UUID ruleId, LocalDate upToDate) {
        RecurringRule rule = ruleRepository.findById(ruleId).orElse(null);
        if (rule == null || rule.isDeleted()) return;

        LocalDate lastGenerated = eventRepository.findMaxDateByRecurringRuleId(ruleId)
                .orElse(rule.getStartDate().minusDays(1));

        if (!lastGenerated.isBefore(upToDate)) return;

        LocalDate generateFrom = nextOccurrenceAfter(rule, lastGenerated);
        LocalDate horizon = upToDate.plusMonths(1); // generate a bit beyond request

        Category category = rule.getCategory();
        generateEvents(rule, generateFrom, horizon, category);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void generateEvents(RecurringRule rule, LocalDate from, LocalDate until, Category category) {
        List<LocalDate> dates = computeDates(rule, from, until);
        List<FinancialEvent> events = new ArrayList<>();
        for (LocalDate date : dates) {
            events.add(FinancialEvent.builder()
                    .date(date)
                    .category(category)
                    .type(rule.getEventType())
                    .plannedAmount(rule.getPlannedAmount())
                    .mandatory(rule.isMandatory())
                    .description(rule.getDescription())
                    .recurringRuleId(rule.getId())
                    .build());
        }
        eventRepository.saveAll(events);
    }

    /**
     * Compute all occurrence dates for the rule within [from, until).
     * dayOfMonth is clamped to the actual month length (e.g., 31 in Feb → 28/29).
     */
    private List<LocalDate> computeDates(RecurringRule rule, LocalDate from, LocalDate until) {
        List<LocalDate> dates = new ArrayList<>();
        if (rule.getFrequency() == RecurringFrequency.MONTHLY) {
            int dom = rule.getDayOfMonth();
            YearMonth ym = YearMonth.from(from);
            YearMonth last = YearMonth.from(until);
            while (!ym.isAfter(last)) {
                int clampedDay = Math.min(dom, ym.lengthOfMonth());
                LocalDate date = ym.atDay(clampedDay);
                if (!date.isBefore(from) && date.isBefore(until)) {
                    if (rule.getEndDate() == null || !date.isAfter(rule.getEndDate())) {
                        dates.add(date);
                    }
                }
                ym = ym.plusMonths(1);
            }
        } else { // WEEKLY
            LocalDate cursor = from;
            while (cursor.isBefore(until)) {
                if (cursor.getDayOfWeek() == rule.getDayOfWeek()) {
                    if (rule.getEndDate() == null || !cursor.isAfter(rule.getEndDate())) {
                        dates.add(cursor);
                    }
                }
                cursor = cursor.plusDays(1);
            }
        }
        return dates;
    }

    private LocalDate nextOccurrenceAfter(RecurringRule rule, LocalDate after) {
        if (rule.getFrequency() == RecurringFrequency.MONTHLY) {
            YearMonth next = YearMonth.from(after).plusMonths(1);
            int dom = Math.min(rule.getDayOfMonth(), next.lengthOfMonth());
            return next.atDay(dom);
        } else {
            LocalDate cursor = after.plusDays(1);
            while (cursor.getDayOfWeek() != rule.getDayOfWeek()) {
                cursor = cursor.plusDays(1);
            }
            return cursor;
        }
    }

    public RecurringRuleDto toDto(RecurringRule r) {
        return new RecurringRuleDto(
                r.getId(), r.getCategory().getId(), r.getCategory().getName(),
                r.getEventType(), r.getTargetFundId(), r.getPlannedAmount(),
                r.isMandatory(), r.getDescription(), r.getFrequency(),
                r.getDayOfMonth(), r.getDayOfWeek(), r.getStartDate(), r.getEndDate());
    }
}
```

**Step 2: Build**

```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw compile -q
```

Expected: `BUILD SUCCESS`

**Step 3: Commit**

```bash
git add backend/src/main/java/ru/selfin/backend/service/RecurringRuleService.java
git commit -m "feat(backend): implement RecurringRuleService — create, update, delete, extend"
```

---

## Task 5: RecurringRuleController + extend FinancialEventController

**Files:**
- Create: `backend/src/main/java/ru/selfin/backend/controller/RecurringRuleController.java`
- Modify: `backend/src/main/java/ru/selfin/backend/controller/FinancialEventController.java`
- Modify: `backend/src/main/java/ru/selfin/backend/service/FinancialEventService.java`

**Step 1: Create RecurringRuleController**

```java
package ru.selfin.backend.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import ru.selfin.backend.dto.RecurringRuleCreateDto;
import ru.selfin.backend.dto.RecurringRuleDto;
import ru.selfin.backend.service.RecurringRuleService;

@RestController
@RequestMapping("/api/v1/recurring-rules")
@RequiredArgsConstructor
public class RecurringRuleController {

    private final RecurringRuleService ruleService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RecurringRuleDto create(@Valid @RequestBody RecurringRuleCreateDto dto) {
        return ruleService.createRule(dto);
    }
}
```

**Step 2: Add `scope` parameter to FinancialEventController.update() and delete()**

Read `FinancialEventController.java` first (it currently has `PUT /events/{id}` and `DELETE /events/{id}`).

In the `PUT /{id}` method, add `@RequestParam(defaultValue = "THIS") String scope` and pass it to the service:

```java
@PutMapping("/{id}")
public FinancialEventDto update(
        @PathVariable UUID id,
        @Valid @RequestBody FinancialEventCreateDto dto,
        @RequestParam(defaultValue = "THIS") String scope) {
    return eventService.update(id, dto, scope);
}
```

In the `DELETE /{id}` method:

```java
@DeleteMapping("/{id}")
@ResponseStatus(HttpStatus.NO_CONTENT)
public void delete(
        @PathVariable UUID id,
        @RequestParam(defaultValue = "THIS") String scope) {
    eventService.softDelete(id, scope);
}
```

**Step 3: Update FinancialEventService to handle scope**

Change `update(UUID id, FinancialEventCreateDto dto)` signature to `update(UUID id, FinancialEventCreateDto dto, String scope)`.

Add at the start of the method:
```java
if ("THIS_AND_FOLLOWING".equals(scope)) {
    FinancialEvent event = eventRepository.findById(id)
            .filter(e -> !e.isDeleted())
            .orElseThrow(() -> new ResourceNotFoundException("FinancialEvent", id));
    if (event.getRecurringRuleId() != null) {
        recurringRuleService.updateThisAndFollowing(event.getRecurringRuleId(), event.getDate(), dto);
        return toDto(event); // return current event (it was updated in the bulk call)
    }
    // Fallthrough: no rule, treat as THIS
}
// scope = THIS: detach from rule and update only this event
FinancialEvent event = eventRepository.findById(id)...
event.setRecurringRuleId(null); // detach from series
// ... rest of existing update logic
```

Change `softDelete(UUID id)` to `softDelete(UUID id, String scope)`:
```java
@Transactional
public void softDelete(UUID id, String scope) {
    FinancialEvent event = eventRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("FinancialEvent", id));
    if ("THIS_AND_FOLLOWING".equals(scope) && event.getRecurringRuleId() != null) {
        recurringRuleService.deleteThisAndFollowing(event.getRecurringRuleId(), event.getDate());
        return;
    }
    event.setDeleted(true);
    eventRepository.save(event);
}
```

Also inject `RecurringRuleService recurringRuleService` into `FinancialEventService`.

**Step 4: Build and run**

```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw compile -q
```

Expected: `BUILD SUCCESS`

**Step 5: Smoke test with curl**

```bash
# Create a recurring rule (MONTHLY, day 1, starting today)
TODAY=$(date +%Y-%m-%d)
curl -s -X POST http://localhost:8080/api/v1/recurring-rules \
  -H "Content-Type: application/json" \
  -d "{\"categoryId\":\"<any-category-uuid>\",\"eventType\":\"EXPENSE\",\"plannedAmount\":50000,\"mandatory\":true,\"frequency\":\"MONTHLY\",\"dayOfMonth\":1,\"startDate\":\"$TODAY\"}" | jq .
```

Expected: returns a `RecurringRuleDto` JSON. Then:

```bash
# Verify 12 events were generated for the first of each month
curl -s "http://localhost:8080/api/v1/events?startDate=$TODAY&endDate=$(date -d '+13 months' +%Y-%m-%d)" | jq 'length'
```

Expected: `12` (or 13 if crossing a year boundary month-edge case — either is acceptable).

**Step 6: Commit**

```bash
git add backend/src/main/java/ru/selfin/backend/
git commit -m "feat(backend): add RecurringRuleController and scope-aware update/delete on events"
```

---

## Task 6: Frontend — recurring toggle in QuickAddModal + scope dialog in Budget

**Files:**
- Modify: `frontend/src/components/Fab.tsx`
- Modify: `frontend/src/pages/Budget.tsx`
- Modify: `frontend/src/api/index.ts`
- Modify: `frontend/src/types/api.ts`

**Step 1: Add types to api.ts**

```typescript
export type RecurringFrequency = 'MONTHLY' | 'WEEKLY';

export interface RecurringRuleCreateDto {
    categoryId: string;
    eventType: 'INCOME' | 'EXPENSE' | 'FUND_TRANSFER';
    targetFundId?: string;
    plannedAmount: number;
    mandatory?: boolean;
    description?: string;
    frequency: RecurringFrequency;
    dayOfMonth?: number;
    dayOfWeek?: string;
    startDate: string;
    endDate?: string;
}

export interface RecurringRuleDto {
    id: string;
    categoryId: string;
    categoryName: string;
    frequency: RecurringFrequency;
    dayOfMonth?: number;
    dayOfWeek?: string;
    startDate: string;
    endDate?: string;
}
```

Also add `recurringRuleId?: string` to `FinancialEvent` interface.

**Step 2: Add API functions to api/index.ts**

```typescript
export async function createRecurringRule(dto: RecurringRuleCreateDto): Promise<RecurringRuleDto> {
    const res = await fetch(`${BASE_URL}/recurring-rules`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(dto),
    });
    if (!res.ok) throw new Error('Failed to create recurring rule');
    return res.json();
}

// Update updateEvent to support scope parameter
export async function updateEvent(
    id: string,
    dto: FinancialEventCreateDto,
    scope: 'THIS' | 'THIS_AND_FOLLOWING' = 'THIS'
): Promise<FinancialEvent> {
    const res = await fetch(`${BASE_URL}/events/${id}?scope=${scope}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(dto),
    });
    if (!res.ok) throw new Error('Failed to update event');
    return res.json();
}

export async function deleteEvent(
    id: string,
    scope: 'THIS' | 'THIS_AND_FOLLOWING' = 'THIS'
): Promise<void> {
    const res = await fetch(`${BASE_URL}/events/${id}?scope=${scope}`, { method: 'DELETE' });
    if (!res.ok) throw new Error('Failed to delete event');
}
```

**Step 3: Add recurring toggle to Fab.tsx / QuickAddModal**

In `QuickAddModal`, add state:
```typescript
const [recurring, setRecurring] = useState(false);
const [frequency, setFrequency] = useState<'MONTHLY' | 'WEEKLY'>('MONTHLY');
const [dayOfMonth, setDayOfMonth] = useState(1);
```

Add a toggle section after the type buttons:
```tsx
{/* Repeating toggle */}
<div className="flex items-center gap-3">
    <span className="text-sm text-muted-foreground">Повторяется</span>
    <button
        type="button"
        className={`relative w-10 h-5 rounded-full transition-colors ${recurring ? 'bg-primary' : 'bg-secondary'}`}
        onClick={() => setRecurring(r => !r)}
    >
        <span className={`absolute top-0.5 w-4 h-4 rounded-full bg-white transition-transform ${recurring ? 'translate-x-5' : 'translate-x-0.5'}`} />
    </button>
</div>

{recurring && (
    <div className="flex gap-2">
        <Select value={frequency} onValueChange={val => setFrequency(val as 'MONTHLY' | 'WEEKLY')}>
            <SelectTrigger className="flex-1">
                <SelectValue />
            </SelectTrigger>
            <SelectContent>
                <SelectItem value="MONTHLY">Ежемесячно</SelectItem>
                <SelectItem value="WEEKLY">Еженедельно</SelectItem>
            </SelectContent>
        </Select>
        {frequency === 'MONTHLY' && (
            <Input
                type="number"
                min={1} max={28}
                className="w-20"
                placeholder="день"
                value={dayOfMonth}
                onChange={e => setDayOfMonth(Number(e.target.value))}
            />
        )}
    </div>
)}
```

In `handleSubmit`, if `recurring` is true:
```typescript
if (recurring) {
    await createRecurringRule({
        categoryId: form.categoryId!,
        eventType: form.type as 'INCOME' | 'EXPENSE',
        plannedAmount: form.plannedAmount!,
        mandatory: form.mandatory,
        description: form.description,
        frequency,
        dayOfMonth: frequency === 'MONTHLY' ? dayOfMonth : undefined,
        startDate: form.date!,
    });
} else {
    await createEvent(form as FinancialEventCreateDto);
}
```

**Step 4: Add recurring badge + scope dialog to Budget.tsx**

Import the `RefreshCw` icon from lucide-react.

In the event row, add after the existing badges:
```tsx
{event.recurringRuleId && (
    <Badge variant="outline" className="text-xs border-primary/50 text-primary px-1.5 py-0">↻</Badge>
)}
```

In `EditEventSheet.tsx`, add a scope selector that shows only when `event.recurringRuleId` is set:

Add a prop `onScopeConfirm?: (scope: 'THIS' | 'THIS_AND_FOLLOWING') => void` — OR handle this inside `Budget.tsx` with a small inline dialog.

Simplest approach: in `Budget.tsx`, when clicking a recurring event, show a small two-button dialog:
```tsx
const [scopeDialogEvent, setScopeDialogEvent] = useState<FinancialEvent | null>(null);
const [selectedEvent, setSelectedEvent] = useState<FinancialEvent | null>(null);
const [deleteScope, setDeleteScope] = useState<'THIS' | 'THIS_AND_FOLLOWING'>('THIS');

// When tapping an event:
onClick={() => {
    if (event.recurringRuleId) {
        setScopeDialogEvent(event);
    } else {
        setSelectedEvent(event);
    }
}}

// Scope dialog (shown when scopeDialogEvent != null):
{scopeDialogEvent && !selectedEvent && (
    <Sheet open onOpenChange={open => !open && setScopeDialogEvent(null)}>
        <SheetContent side="bottom" className="max-w-2xl mx-auto rounded-t-2xl">
            <SheetHeader>
                <SheetTitle>Изменить событие</SheetTitle>
                <SheetDescription>Это повторяющееся событие. Что изменить?</SheetDescription>
            </SheetHeader>
            <div className="flex flex-col gap-3 mt-4">
                <Button variant="secondary" className="w-full" onClick={() => { setSelectedEvent(scopeDialogEvent); setScopeDialogEvent(null); }}>
                    Только это событие
                </Button>
                <Button className="w-full" onClick={() => {
                    // pass scope=THIS_AND_FOLLOWING via a ref or state
                    setDeleteScope('THIS_AND_FOLLOWING');
                    setSelectedEvent(scopeDialogEvent);
                    setScopeDialogEvent(null);
                }}>
                    Это и все следующие
                </Button>
            </div>
        </SheetContent>
    </Sheet>
)}
```

Pass `scope` down to `EditEventSheet` which passes it through `updateEvent(id, dto, scope)` and `deleteEvent(id, scope)`.

**Step 5: Test**

1. Create recurring MONTHLY event via FAB → verify 12 events appear in Budget across months
2. Click a recurring event → scope dialog appears
3. Select "Только это" → edit saves only that event (others unchanged)
4. Select "Это и следующие" → edit updates all future PLANNED events

**Step 6: Commit**

```bash
git add frontend/src/
git commit -m "feat(frontend): add recurring toggle in QuickAddModal and scope dialog in Budget"
```
