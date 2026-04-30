# Recurring Events Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Дать пользователю возможность создавать повторяющиеся финансовые события (ипотека, аренда, зарплата, подписки) одним правилом вместо ручного заведения десятков. Правило порождает материализованные `FinancialEvent` в БД, существующий код (Budget/Dashboard/Analytics/планировщик) работает без изменений. Edit/Delete поддерживают scope (THIS / FOLLOWING / ALL) с инвариантой «EXECUTED-события не трогаются никогда».

**Architecture:** Новая таблица `recurring_rule` + FK `recurring_rule_id` на `financial_events`. Stateless `RecurringEventGenerator` производит даты с clamp'ом (31 → 28/29/30). Координатор `RecurringRuleService` владеет CRUD-операциями над правилами + regenerate'ом и lazy-extend'ом для бессрочных. `FinancialEventService` остаётся фасадом для контроллера. Концурентность защищена партишн-уник-индексом + `@Lock(PESSIMISTIC_WRITE)`. Frontend получает чекбокс «Повторять» в `EventSheet`, inline scope-picker в `EditEventSheet`, модалку scope при удалении и иконку ↻ в Budget.

**Tech Stack:** Spring Boot 4.0.3, Java 21, PostgreSQL 15, Flyway, JUnit 5 + Mockito + Testcontainers, React 18, TypeScript, Vite, Tailwind CSS, Shadcn UI, Lucide React (`Repeat` icon).

**Spec:** `docs/superpowers/specs/2026-04-24-recurring-events-design.md`

**Test commands:**
- All backend: `JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test`
- Single backend test: `JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -Dtest=ClassName`
- Single test method: `JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -Dtest=ClassName#methodName`
- TS check: `cd frontend && npx tsc --noEmit`
- Frontend lint: `cd frontend && npm run lint`

> Use `rtk` prefix on test commands when running them via Bash to keep the output budget small (`rtk JAVA_HOME=... ./mvnw test`).

---

## File Map

### Backend — create

| File | Purpose |
|------|---------|
| `backend/src/main/resources/db/migration/V16__add_recurring_events.sql` | Table `recurring_rule`, FK column on `financial_events`, partial unique index, supporting indexes |
| `backend/src/main/java/ru/selfin/backend/model/RecurringRule.java` | JPA entity, mirrors `recurring_rule` table |
| `backend/src/main/java/ru/selfin/backend/model/enums/RecurringFrequency.java` | Enum: `MONTHLY`, `YEARLY` |
| `backend/src/main/java/ru/selfin/backend/model/enums/ScopeEnum.java` | Enum: `THIS`, `FOLLOWING`, `ALL` |
| `backend/src/main/java/ru/selfin/backend/repository/RecurringRuleRepository.java` | `findIndefiniteActiveIds`, `findForUpdate` |
| `backend/src/main/java/ru/selfin/backend/dto/RecurringConfigDto.java` | Sub-DTO: `frequency`, `dayOfMonth`, `monthOfYear`, `endDate`, `startDate` (read-only on edit) |
| `backend/src/main/java/ru/selfin/backend/service/RecurringEventGenerator.java` | Stateless `@Component`, generates `List<FinancialEvent>` per rule + horizon |
| `backend/src/main/java/ru/selfin/backend/service/RecurringRuleService.java` | Coordinator: createFromDto, regenerate, extendIndefiniteRules, scope-based delete |
| `backend/src/test/java/ru/selfin/backend/service/RecurringEventGeneratorTest.java` | Unit tests for generator (date clamp, edge cases) |
| `backend/src/test/java/ru/selfin/backend/service/RecurringRuleServiceTest.java` | Unit tests for service with mocked repos |
| `backend/src/test/java/ru/selfin/backend/RecurringEventControllerIT.java` | Integration tests via Testcontainers |

### Backend — modify

| File | Why |
|------|-----|
| `backend/src/main/java/ru/selfin/backend/model/FinancialEvent.java` | Add `@ManyToOne RecurringRule recurringRule` |
| `backend/src/main/java/ru/selfin/backend/repository/FinancialEventRepository.java` | Add 5 new query methods (see spec → «Репозиторные методы») |
| `backend/src/main/java/ru/selfin/backend/dto/FinancialEventCreateDto.java` | Add `RecurringConfigDto recurring` (nullable) |
| `backend/src/main/java/ru/selfin/backend/dto/FinancialEventDto.java` | Add `recurringRuleId`, `recurringFrequency`, `recurringDayOfMonth`, `recurringMonthOfYear` |
| `backend/src/main/java/ru/selfin/backend/service/FinancialEventService.java` | Branch on `dto.recurring()`; route update/delete with scope to `RecurringRuleService` |
| `backend/src/main/java/ru/selfin/backend/controller/FinancialEventController.java` | Accept `?scope=` query param on PUT/DELETE |
| `backend/src/main/java/ru/selfin/backend/service/FundPlannerService.java` | Call `ruleService.extendIndefiniteRules(today.plusMonths(36))` first thing in `getPlanner()` |
| `backend/src/test/java/ru/selfin/backend/service/FinancialEventServiceTest.java` | New cases for recurring create, scope-based update, scope-based delete |

### Frontend — create

| File | Purpose |
|------|---------|
| `frontend/src/components/RecurringFields.tsx` | Reusable form section: «Повторять» checkbox + frequency/day/month/end inputs |
| `frontend/src/components/EditEventScopePicker.tsx` | Inline radio group for THIS/FOLLOWING/ALL on edit |
| `frontend/src/components/DeleteRecurringDialog.tsx` | Modal: confirm + scope radio for delete |

### Frontend — modify

| File | Why |
|------|-----|
| `frontend/src/types/api.ts` | New `RecurringConfig`, `RecurringFrequency`, `ScopeEnum`; extend `FinancialEvent`, `FinancialEventCreateDto` |
| `frontend/src/api/index.ts` | `updateEvent` and `deleteEvent` accept optional `scope` param |
| `frontend/src/api/client.ts` | (only if needed: support `?scope=` query) |
| `frontend/src/components/EventSheet.tsx` | Embed `<RecurringFields>` |
| `frontend/src/components/EditEventSheet.tsx` | Embed `<EditEventScopePicker>` if `event.recurringRuleId != null`; pass scope to PUT |
| `frontend/src/components/BudgetStructureSection.tsx` (or wherever budget rows live) | Render ↻ icon next to recurring rows |

### Docs — modify

| File | Why |
|------|-----|
| `C:\Users\Kirill\.claude\projects\C--Users-Kirill-IdeaProjects-selfin\memory\MEMORY.md` | Mark recurring events done; remove from roadmap |

---

## Chunk 1 — Migration + entities + repository skeleton

This chunk lays the database foundation and JPA mapping. After this chunk the project compiles and the new table exists with all constraints, but no behavior is wired yet.

### Task 1.1: Flyway migration V16

**Files:**
- Create: `backend/src/main/resources/db/migration/V16__add_recurring_events.sql`

- [ ] **Step 1: Create migration file**

```sql
-- V16: повторяющиеся финансовые события (recurring rule + FK на events)

CREATE TABLE recurring_rule (
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    category_id    UUID        NOT NULL REFERENCES categories(id),
    event_type     VARCHAR(20) NOT NULL CHECK (event_type IN ('INCOME', 'EXPENSE')),
    planned_amount NUMERIC(19,2) NOT NULL CHECK (planned_amount >= 0),
    priority       VARCHAR(10) NOT NULL DEFAULT 'MEDIUM' CHECK (priority IN ('HIGH', 'MEDIUM', 'LOW')),
    description    VARCHAR(255),
    frequency      VARCHAR(10) NOT NULL CHECK (frequency IN ('MONTHLY', 'YEARLY')),
    day_of_month   INTEGER     NOT NULL CHECK (day_of_month BETWEEN 1 AND 31),
    month_of_year  INTEGER     CHECK (month_of_year BETWEEN 1 AND 12),
    start_date     DATE        NOT NULL,
    end_date       DATE,
    is_deleted     BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMP,
    CONSTRAINT chk_monthly_no_month CHECK (frequency <> 'MONTHLY' OR month_of_year IS NULL),
    CONSTRAINT chk_yearly_has_month CHECK (frequency <> 'YEARLY'  OR month_of_year IS NOT NULL),
    CONSTRAINT chk_end_after_start  CHECK (end_date IS NULL OR end_date >= start_date)
);

CREATE INDEX idx_recurring_rule_not_deleted
    ON recurring_rule (is_deleted) WHERE is_deleted = FALSE;

ALTER TABLE financial_events
    ADD COLUMN recurring_rule_id UUID REFERENCES recurring_rule(id);

CREATE INDEX idx_events_recurring_rule
    ON financial_events (recurring_rule_id)
    WHERE recurring_rule_id IS NOT NULL;

-- Защита от дублирования при lazy-extend (см. spec → Секция 2 Конкурентность)
CREATE UNIQUE INDEX uq_events_rule_date_active
    ON financial_events (recurring_rule_id, date)
    WHERE recurring_rule_id IS NOT NULL AND is_deleted = FALSE;
```

- [ ] **Step 2: Verify migration applies cleanly on a fresh container**

Run: `rtk JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -Dtest=BackendApplicationTests`
Expected: PASS (Flyway runs at context init; failure here = bad SQL).

- [ ] **Step 3: Commit**

```bash
rtk git add backend/src/main/resources/db/migration/V16__add_recurring_events.sql
rtk git commit -m "feat(db): add recurring_rule table and FK on financial_events"
```

---

### Task 1.2: Enums (RecurringFrequency, ScopeEnum)

**Files:**
- Create: `backend/src/main/java/ru/selfin/backend/model/enums/RecurringFrequency.java`
- Create: `backend/src/main/java/ru/selfin/backend/model/enums/ScopeEnum.java`

- [ ] **Step 1: Create RecurringFrequency**

```java
package ru.selfin.backend.model.enums;

/** Частота повторения для recurring rule. См. spec, инвариант I1. */
public enum RecurringFrequency {
    MONTHLY,
    YEARLY
}
```

- [ ] **Step 2: Create ScopeEnum**

```java
package ru.selfin.backend.model.enums;

/**
 * Scope edit/delete операций над recurring-событием.
 *
 * <ul>
 *   <li>{@code THIS} — только этот экземпляр (правило не трогаем)</li>
 *   <li>{@code FOLLOWING} — этот экземпляр и все будущие PLAN'ы</li>
 *   <li>{@code ALL} — всё правило целиком</li>
 * </ul>
 *
 * <p>Применимо только к recurring-событиям (см. инвариант I6).
 */
public enum ScopeEnum {
    THIS,
    FOLLOWING,
    ALL
}
```

- [ ] **Step 3: Compile**

Run: `rtk JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
rtk git add backend/src/main/java/ru/selfin/backend/model/enums/RecurringFrequency.java backend/src/main/java/ru/selfin/backend/model/enums/ScopeEnum.java
rtk git commit -m "feat(model): add RecurringFrequency and ScopeEnum"
```

---

### Task 1.3: RecurringRule entity

**Files:**
- Create: `backend/src/main/java/ru/selfin/backend/model/RecurringRule.java`

- [ ] **Step 1: Create entity following Category/FinancialEvent style**

```java
package ru.selfin.backend.model;

import jakarta.persistence.*;
import lombok.*;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.Priority;
import ru.selfin.backend.model.enums.RecurringFrequency;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Правило повторения для финансовых событий.
 *
 * <p>Правило — источник истины для ненаступивших PLAN-событий (см. spec, I5).
 * EXECUTED-события живут независимо: правило их никогда не модифицирует и не удаляет (I4).
 *
 * <p>Soft-delete: {@code deleted = true} скрывает правило; колонка БД зовётся {@code is_deleted}.
 */
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

    @Column(name = "planned_amount", precision = 19, scale = 2, nullable = false)
    private BigDecimal plannedAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private Priority priority = Priority.MEDIUM;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RecurringFrequency frequency;

    @Column(name = "day_of_month", nullable = false)
    private Integer dayOfMonth;

    /** Только для {@link RecurringFrequency#YEARLY}. NULL для MONTHLY (I1). */
    @Column(name = "month_of_year")
    private Integer monthOfYear;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "is_deleted", nullable = false)
    @Builder.Default
    private boolean deleted = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
```

- [ ] **Step 2: Compile**

Run: `rtk JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
rtk git add backend/src/main/java/ru/selfin/backend/model/RecurringRule.java
rtk git commit -m "feat(model): add RecurringRule entity"
```

---

### Task 1.4: Add `recurringRule` association to FinancialEvent

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/model/FinancialEvent.java` (add field after `targetFundId`, before `eventKind`)

- [ ] **Step 1: Add field**

After the `targetFundId` field (around line 87), insert:

```java
    /**
     * Если событие порождено повторяющимся правилом — ссылка на правило.
     * NULL для одиночных событий. См. spec, инварианты I5, I9.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recurring_rule_id")
    private RecurringRule recurringRule;
```

- [ ] **Step 2: Compile**

Run: `rtk JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Run all existing tests to ensure no regression**

Run: `rtk JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test`
Expected: all PASS (the column is nullable, existing rows untouched).

- [ ] **Step 4: Commit**

```bash
rtk git add backend/src/main/java/ru/selfin/backend/model/FinancialEvent.java
rtk git commit -m "feat(model): link FinancialEvent to RecurringRule via @ManyToOne"
```

---

### Task 1.5: RecurringRuleRepository

**Files:**
- Create: `backend/src/main/java/ru/selfin/backend/repository/RecurringRuleRepository.java`

- [ ] **Step 1: Create repository**

```java
package ru.selfin.backend.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.selfin.backend.model.RecurringRule;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RecurringRuleRepository extends JpaRepository<RecurringRule, UUID> {

    /**
     * «Активное» = не soft-deleted И без end_date. Используется в lazy-extend.
     */
    @Query("SELECT r.id FROM RecurringRule r " +
           "WHERE r.deleted = false AND r.endDate IS NULL")
    List<UUID> findIndefiniteActiveIds();

    /**
     * Pessimistic lock на правиле для безопасного lazy-extend под конкурентным
     * доступом (см. spec → Секция 2 «Конкурентность»).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM RecurringRule r WHERE r.id = :id")
    Optional<RecurringRule> findForUpdate(@Param("id") UUID id);
}
```

- [ ] **Step 2: Compile**

Run: `rtk JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
rtk git add backend/src/main/java/ru/selfin/backend/repository/RecurringRuleRepository.java
rtk git commit -m "feat(repo): add RecurringRuleRepository"
```

---

### Task 1.6: Extend FinancialEventRepository with 5 new methods

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/repository/FinancialEventRepository.java`

- [ ] **Step 1: Add new methods**

Add these methods to the interface (anywhere; group them at the end with a `// --- Recurring ---` comment for clarity):

```java
    // --- Recurring ---

    @Query("SELECT MAX(e.date) FROM FinancialEvent e " +
           "WHERE e.recurringRule.id = :ruleId " +
           "  AND e.deleted = false " +
           "  AND e.status = 'PLANNED'")
    Optional<LocalDate> findMaxActiveDateByRule(@Param("ruleId") UUID ruleId);

    @Modifying
    @Query("UPDATE FinancialEvent e SET e.deleted = true, e.updatedAt = CURRENT_TIMESTAMP " +
           "WHERE e.recurringRule.id = :ruleId " +
           "  AND e.deleted = false " +
           "  AND e.status = 'PLANNED' " +
           "  AND e.date >= :fromDate")
    int softDeletePlanEventsByRuleFromDate(@Param("ruleId") UUID ruleId,
                                           @Param("fromDate") LocalDate fromDate);

    @Query("SELECT e.date FROM FinancialEvent e " +
           "WHERE e.recurringRule.id = :ruleId " +
           "  AND e.deleted = false " +
           "  AND e.status = 'EXECUTED'")
    java.util.Set<LocalDate> findExecutedDatesByRule(@Param("ruleId") UUID ruleId);

    @Query("SELECT MAX(e.date) FROM FinancialEvent e " +
           "WHERE e.recurringRule.id = :ruleId " +
           "  AND e.deleted = false " +
           "  AND e.status = 'EXECUTED'")
    Optional<LocalDate> findMaxExecutedDateByRule(@Param("ruleId") UUID ruleId);

    @Query("SELECT e FROM FinancialEvent e " +
           "WHERE e.recurringRule.id = :ruleId " +
           "  AND e.date = :date " +
           "  AND e.deleted = false")
    Optional<FinancialEvent> findActiveByRuleAndDate(@Param("ruleId") UUID ruleId,
                                                     @Param("date") LocalDate date);
```

Make sure the file imports include `Optional`, `java.util.Set`, `LocalDate`, `UUID`, `org.springframework.data.jpa.repository.Modifying`, `org.springframework.data.jpa.repository.Query`, `org.springframework.data.repository.query.Param`. If `@Modifying` already imported elsewhere, reuse it.

- [ ] **Step 2: Compile**

Run: `rtk JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
rtk git add backend/src/main/java/ru/selfin/backend/repository/FinancialEventRepository.java
rtk git commit -m "feat(repo): add recurring-rule query methods to FinancialEventRepository"
```

---

## Chunk 2 — Generator + RecurringRuleService (core domain logic, TDD)

This chunk implements pure domain logic with the most edge cases. Strict TDD: every behaviour gets its failing test first. After this chunk we can manually `RecurringRuleService.createFromDto()` from a unit test and see correct events come out.

### Task 2.1: RecurringEventGenerator — first failing test (basic monthly)

**Files:**
- Create: `backend/src/test/java/ru/selfin/backend/service/RecurringEventGeneratorTest.java`
- Create: `backend/src/main/java/ru/selfin/backend/service/RecurringEventGenerator.java` (skeleton)

- [ ] **Step 1: Write the first failing test**

```java
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
```

- [ ] **Step 2: Create empty generator skeleton (just enough to fail to compile, then return empty)**

```java
package ru.selfin.backend.service;

import org.springframework.stereotype.Component;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.RecurringRule;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

@Component
public class RecurringEventGenerator {

    public List<FinancialEvent> generate(RecurringRule rule, LocalDate from, LocalDate through) {
        return Collections.emptyList();
    }
}
```

- [ ] **Step 3: Run the test — it must fail (empty list)**

Run: `rtk JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -Dtest=RecurringEventGeneratorTest`
Expected: FAIL, AssertJ reports "Actual was empty".

- [ ] **Step 4: Implement generator (full algorithm — covers all upcoming tests)**

Replace generator body:

```java
package ru.selfin.backend.service;

import org.springframework.stereotype.Component;
import ru.selfin.backend.model.EventKind;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.RecurringRule;
import ru.selfin.backend.model.enums.EventStatus;
import ru.selfin.backend.model.enums.RecurringFrequency;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * Stateless генератор материализованных событий из {@link RecurringRule}.
 *
 * <p>Алгоритм и правила clamp'а — см. spec → Секция 2.
 */
@Component
public class RecurringEventGenerator {

    public List<FinancialEvent> generate(RecurringRule rule, LocalDate from, LocalDate through) {
        if (rule.getStartDate().isAfter(through)) {
            return List.of();
        }
        LocalDate effectiveFrom = from.isBefore(rule.getStartDate()) ? rule.getStartDate() : from;
        LocalDate cursor = firstOnOrAfter(rule, effectiveFrom);
        if (cursor == null || cursor.isAfter(through)) {
            return List.of();
        }
        List<FinancialEvent> result = new ArrayList<>();
        while (!cursor.isAfter(through)) {
            result.add(buildEvent(rule, cursor));
            cursor = next(rule, cursor);
        }
        return result;
    }

    private LocalDate firstOnOrAfter(RecurringRule rule, LocalDate from) {
        if (rule.getFrequency() == RecurringFrequency.MONTHLY) {
            YearMonth ym = YearMonth.from(from);
            int day = Math.min(rule.getDayOfMonth(), ym.lengthOfMonth());
            LocalDate candidate = ym.atDay(day);
            return candidate.isBefore(from) ? next(rule, candidate) : candidate;
        }
        // YEARLY
        YearMonth ym = YearMonth.of(from.getYear(), rule.getMonthOfYear());
        int day = Math.min(rule.getDayOfMonth(), ym.lengthOfMonth());
        LocalDate candidate = ym.atDay(day);
        return candidate.isBefore(from) ? next(rule, candidate) : candidate;
    }

    private LocalDate next(RecurringRule rule, LocalDate cursor) {
        if (rule.getFrequency() == RecurringFrequency.MONTHLY) {
            YearMonth ym = YearMonth.from(cursor).plusMonths(1);
            int day = Math.min(rule.getDayOfMonth(), ym.lengthOfMonth());
            return ym.atDay(day);
        }
        YearMonth ym = YearMonth.of(cursor.getYear() + 1, rule.getMonthOfYear());
        int day = Math.min(rule.getDayOfMonth(), ym.lengthOfMonth());
        return ym.atDay(day);
    }

    private FinancialEvent buildEvent(RecurringRule rule, LocalDate date) {
        return FinancialEvent.builder()
                .date(date)
                .category(rule.getCategory())
                .type(rule.getEventType())
                .plannedAmount(rule.getPlannedAmount())
                .priority(rule.getPriority())
                .description(rule.getDescription())
                .status(EventStatus.PLANNED)
                .eventKind(EventKind.PLAN)
                .recurringRule(rule)
                .build();
    }
}
```

- [ ] **Step 5: Run — should pass**

Run: `rtk JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -Dtest=RecurringEventGeneratorTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
rtk git add backend/src/main/java/ru/selfin/backend/service/RecurringEventGenerator.java backend/src/test/java/ru/selfin/backend/service/RecurringEventGeneratorTest.java
rtk git commit -m "feat(service): RecurringEventGenerator with monthly base case"
```

---

### Task 2.2: Generator edge cases (clamp 31, leap year, indefinite, single-event)

**Files:**
- Modify: `backend/src/test/java/ru/selfin/backend/service/RecurringEventGeneratorTest.java`

Each step adds one test, runs the suite (must pass with the algorithm we already have), and commits.

- [ ] **Step 1: Test — MONTHLY day=31 clamps in February and 30-day months**

Add inside the test class:

```java
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
```

- [ ] **Step 2: Run**

Run: `rtk JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -Dtest=RecurringEventGeneratorTest`
Expected: PASS.

- [ ] **Step 3: Test — MONTHLY 31st in leap year February gives 29**

```java
    @Test
    void monthly_31st_in_leap_year_february_gives_29() {
        var rule = monthlyRule(31, LocalDate.of(2028, 1, 31), LocalDate.of(2028, 3, 31));

        var events = generator.generate(rule, rule.getStartDate(), rule.getEndDate());

        assertThat(events).extracting("date").containsExactly(
                LocalDate.of(2028, 1, 31),
                LocalDate.of(2028, 2, 29),
                LocalDate.of(2028, 3, 31));
    }
```

Run: `rtk JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -Dtest=RecurringEventGeneratorTest`
Expected: PASS.

- [ ] **Step 4: Test — YEARLY 29 февраля clamps to 28 in non-leap years**

```java
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
```

Run: `rtk JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -Dtest=RecurringEventGeneratorTest`
Expected: PASS.

- [ ] **Step 5: Test — start_date == end_date → exactly one event**

```java
    @Test
    void start_equals_end_yields_single_event() {
        var rule = monthlyRule(15, LocalDate.of(2026, 5, 15), LocalDate.of(2026, 5, 15));

        var events = generator.generate(rule, rule.getStartDate(), rule.getEndDate());

        assertThat(events).hasSize(1);
        assertThat(events.get(0).getDate()).isEqualTo(LocalDate.of(2026, 5, 15));
    }
```

Run: same command.

- [ ] **Step 6: Test — `through` falls between two periods, last instance kept inside**

```java
    @Test
    void through_between_periods_excludes_after() {
        // 15-го каждого месяца, до 2027-01-10 — последний должен быть 2026-12-15
        var rule = monthlyRule(15, LocalDate.of(2026, 11, 15), LocalDate.of(2027, 1, 10));

        var events = generator.generate(rule, rule.getStartDate(), rule.getEndDate());

        assertThat(events).extracting("date").containsExactly(
                LocalDate.of(2026, 11, 15),
                LocalDate.of(2026, 12, 15));
    }
```

Run: same command.

- [ ] **Step 7: Test — start_date > through → empty list**

```java
    @Test
    void start_after_through_returns_empty() {
        var rule = monthlyRule(15, LocalDate.of(2027, 1, 15), null);

        var events = generator.generate(rule, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));

        assertThat(events).isEmpty();
    }
```

Run: same command.

- [ ] **Step 8: Commit all edge-case tests**

```bash
rtk git add backend/src/test/java/ru/selfin/backend/service/RecurringEventGeneratorTest.java
rtk git commit -m "test(service): cover generator edge cases (clamp, leap, single, horizon)"
```

---

### Task 2.3: RecurringRuleService — skeleton + createFromDto

**Files:**
- Create: `backend/src/main/java/ru/selfin/backend/dto/RecurringConfigDto.java`
- Create: `backend/src/main/java/ru/selfin/backend/service/RecurringRuleService.java` (skeleton)
- Create: `backend/src/test/java/ru/selfin/backend/service/RecurringRuleServiceTest.java`

- [ ] **Step 1: Create RecurringConfigDto**

```java
package ru.selfin.backend.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import ru.selfin.backend.model.enums.RecurringFrequency;

import java.time.LocalDate;

/**
 * Под-DTO для recurring-блока в FinancialEventCreateDto / EventUpdateDto.
 * Поле {@code startDate} трактуется как read-only после создания (см. spec, I8).
 */
public record RecurringConfigDto(
        @NotNull RecurringFrequency frequency,
        @NotNull @Min(1) @Max(31) Integer dayOfMonth,
        @Min(1) @Max(12) Integer monthOfYear,   // только для YEARLY
        LocalDate startDate,                    // на edit игнорируется/отвергается
        LocalDate endDate                       // null = бессрочно
) {}
```

- [ ] **Step 2: Failing test — createFromDto saves rule and generates events for end_date horizon**

```java
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
```

- [ ] **Step 3: Skeleton service that compiles but fails the test**

```java
package ru.selfin.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.selfin.backend.dto.RecurringConfigDto;
import ru.selfin.backend.model.Category;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.RecurringRule;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.Priority;
import ru.selfin.backend.repository.FinancialEventRepository;
import ru.selfin.backend.repository.RecurringRuleRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class RecurringRuleService {

    private final RecurringRuleRepository ruleRepo;
    private final FinancialEventRepository eventRepo;
    private final RecurringEventGenerator generator;

    public record CreateResult(RecurringRule rule, List<FinancialEvent> events) {}

    @Transactional
    public CreateResult createFromDto(Category category, EventType type, BigDecimal plannedAmount,
                                      Priority priority, String description, RecurringConfigDto cfg) {
        throw new UnsupportedOperationException("not yet");
    }
}
```

- [ ] **Step 4: Run, expect failure**

Run: `rtk JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -Dtest=RecurringRuleServiceTest`
Expected: FAIL with `UnsupportedOperationException`.

- [ ] **Step 5: Implement createFromDto**

Replace method body:

```java
    @Transactional
    public CreateResult createFromDto(Category category, EventType type, BigDecimal plannedAmount,
                                      Priority priority, String description, RecurringConfigDto cfg) {
        validateConfig(cfg);
        RecurringRule rule = RecurringRule.builder()
                .category(category)
                .eventType(type)
                .plannedAmount(plannedAmount)
                .priority(priority != null ? priority : Priority.MEDIUM)
                .description(description)
                .frequency(cfg.frequency())
                .dayOfMonth(cfg.dayOfMonth())
                .monthOfYear(cfg.monthOfYear())
                .startDate(cfg.startDate())
                .endDate(cfg.endDate())
                .build();
        rule = ruleRepo.save(rule);

        LocalDate horizonEnd = cfg.endDate() != null
                ? cfg.endDate()
                : LocalDate.now().plusMonths(36);
        List<FinancialEvent> events = generator.generate(rule, cfg.startDate(), horizonEnd);
        eventRepo.saveAll(events);
        return new CreateResult(rule, events);
    }

    private void validateConfig(RecurringConfigDto cfg) {
        if (cfg.startDate() == null) {
            throw new IllegalArgumentException("startDate is required");
        }
        if (cfg.startDate().isBefore(LocalDate.now())) {
            throw new IllegalArgumentException("startDate must be today or later (I3)");
        }
        if (cfg.frequency() == ru.selfin.backend.model.enums.RecurringFrequency.YEARLY
                && cfg.monthOfYear() == null) {
            throw new IllegalArgumentException("monthOfYear required for YEARLY (I1)");
        }
        if (cfg.frequency() == ru.selfin.backend.model.enums.RecurringFrequency.MONTHLY
                && cfg.monthOfYear() != null) {
            throw new IllegalArgumentException("monthOfYear must be null for MONTHLY (I1)");
        }
        if (cfg.endDate() != null && cfg.endDate().isBefore(cfg.startDate())) {
            throw new IllegalArgumentException("endDate must be >= startDate (I2)");
        }
    }
```

- [ ] **Step 6: Run, expect PASS**

Run: same command.

- [ ] **Step 7: Commit**

```bash
rtk git add backend/src/main/java/ru/selfin/backend/dto/RecurringConfigDto.java backend/src/main/java/ru/selfin/backend/service/RecurringRuleService.java backend/src/test/java/ru/selfin/backend/service/RecurringRuleServiceTest.java
rtk git commit -m "feat(service): RecurringRuleService.createFromDto with validation"
```

---

### Task 2.4: RecurringRuleService — regenerate (FOLLOWING/ALL)

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/service/RecurringRuleService.java`
- Modify: `backend/src/test/java/ru/selfin/backend/service/RecurringRuleServiceTest.java`

- [ ] **Step 1: Failing test — regenerate from cutoff soft-deletes future PLAN, keeps EXECUTED dates**

Add to test class:

```java
    @Test
    void regenerate_softDeletes_planEvents_from_cutoff_and_skips_executed_dates() {
        UUID ruleId = UUID.randomUUID();
        RecurringRule rule = RecurringRule.builder()
                .id(ruleId)
                .category(category)
                .eventType(EventType.EXPENSE)
                .plannedAmount(new BigDecimal("80000"))
                .frequency(RecurringFrequency.MONTHLY)
                .dayOfMonth(15)
                .startDate(LocalDate.of(2026, 5, 15))
                .endDate(LocalDate.of(2026, 8, 15))
                .build();

        // 2026-06-15 уже EXECUTED — генерация должна его пропустить
        when(eventRepo.findExecutedDatesByRule(ruleId))
                .thenReturn(java.util.Set.of(LocalDate.of(2026, 6, 15)));

        service.regenerate(rule, LocalDate.of(2026, 6, 15));

        verify(eventRepo).softDeletePlanEventsByRuleFromDate(ruleId, LocalDate.of(2026, 6, 15));

        ArgumentCaptor<List<FinancialEvent>> cap = ArgumentCaptor.forClass(List.class);
        verify(eventRepo).saveAll(cap.capture());
        assertThat(cap.getValue()).extracting("date").containsExactly(
                // 2026-06-15 пропущен (EXECUTED)
                LocalDate.of(2026, 7, 15),
                LocalDate.of(2026, 8, 15));
    }

    @Test
    void regenerate_indefinite_uses_now_plus_36_months_horizon() {
        // endDate==null path: horizon = LocalDate.now().plusMonths(36)
        // Use a startDate in the future and expect the generator stops before now+36mo.
        UUID ruleId = UUID.randomUUID();
        LocalDate start = LocalDate.now().plusMonths(1).withDayOfMonth(15);
        RecurringRule rule = RecurringRule.builder()
                .id(ruleId)
                .category(category)
                .eventType(EventType.EXPENSE)
                .plannedAmount(new BigDecimal("80000"))
                .frequency(RecurringFrequency.MONTHLY)
                .dayOfMonth(15)
                .startDate(start)
                .endDate(null)               // бессрочно
                .build();

        when(eventRepo.findExecutedDatesByRule(ruleId)).thenReturn(java.util.Set.of());

        service.regenerate(rule, start);

        ArgumentCaptor<List<FinancialEvent>> cap = ArgumentCaptor.forClass(List.class);
        verify(eventRepo).saveAll(cap.capture());
        // Все сгенерированные даты должны быть <= now+36mo.
        LocalDate horizonCap = LocalDate.now().plusMonths(36);
        assertThat(cap.getValue())
                .isNotEmpty()
                .allSatisfy(e -> assertThat(e.getDate()).isBeforeOrEqualTo(horizonCap));
        // И первая дата = startDate.
        assertThat(cap.getValue().get(0).getDate()).isEqualTo(start);
    }
```

- [ ] **Step 2: Run, expect FAIL (regenerate not implemented)**

Run: `rtk JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -Dtest=RecurringRuleServiceTest#regenerate_softDeletes_planEvents_from_cutoff_and_skips_executed_dates`
Expected: FAIL with method-not-found / NullPointer.

- [ ] **Step 3: Implement regenerate**

Add to `RecurringRuleService`:

```java
    @Transactional
    public void regenerate(RecurringRule rule, LocalDate from) {
        LocalDate horizonEnd = rule.getEndDate() != null
                ? rule.getEndDate()
                : LocalDate.now().plusMonths(36);

        eventRepo.softDeletePlanEventsByRuleFromDate(rule.getId(), from);
        java.util.Set<LocalDate> executed = eventRepo.findExecutedDatesByRule(rule.getId());

        List<FinancialEvent> fresh = generator.generate(rule, from, horizonEnd).stream()
                .filter(e -> !executed.contains(e.getDate()))
                .toList();
        eventRepo.saveAll(fresh);
    }
```

- [ ] **Step 4: Run, expect PASS**

Run: same command (or whole class).

- [ ] **Step 5: Commit**

```bash
rtk git add backend/src/main/java/ru/selfin/backend/service/RecurringRuleService.java backend/src/test/java/ru/selfin/backend/service/RecurringRuleServiceTest.java
rtk git commit -m "feat(service): regenerate(rule, from) preserves EXECUTED dates"
```

---

### Task 2.5: RecurringRuleService — extendIndefiniteRules

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/service/RecurringRuleService.java`
- Modify: `backend/src/test/java/ru/selfin/backend/service/RecurringRuleServiceTest.java`

- [ ] **Step 1: Failing test — extendIndefiniteRules adds events from maxExisting+1 up to requiredThrough**

Add to test class:

```java
    @Test
    void extendIndefiniteRules_appends_only_missing_dates() {
        UUID ruleId = UUID.randomUUID();
        RecurringRule rule = RecurringRule.builder()
                .id(ruleId)
                .category(category)
                .eventType(EventType.EXPENSE)
                .plannedAmount(new BigDecimal("80000"))
                .frequency(RecurringFrequency.MONTHLY)
                .dayOfMonth(15)
                .startDate(LocalDate.of(2026, 5, 15))
                .endDate(null)               // бессрочно
                .build();

        when(ruleRepo.findIndefiniteActiveIds()).thenReturn(List.of(ruleId));
        when(ruleRepo.findForUpdate(ruleId)).thenReturn(java.util.Optional.of(rule));
        when(eventRepo.findMaxActiveDateByRule(ruleId))
                .thenReturn(java.util.Optional.of(LocalDate.of(2026, 6, 15)));

        service.extendIndefiniteRules(LocalDate.of(2026, 8, 15));

        ArgumentCaptor<List<FinancialEvent>> cap = ArgumentCaptor.forClass(List.class);
        verify(eventRepo).saveAll(cap.capture());
        assertThat(cap.getValue()).extracting("date").containsExactly(
                LocalDate.of(2026, 7, 15),
                LocalDate.of(2026, 8, 15));
    }

    @Test
    void extendIndefiniteRules_noop_when_already_covered() {
        UUID ruleId = UUID.randomUUID();
        RecurringRule rule = RecurringRule.builder()
                .id(ruleId)
                .category(category)
                .eventType(EventType.EXPENSE)
                .plannedAmount(new BigDecimal("80000"))
                .frequency(RecurringFrequency.MONTHLY)
                .dayOfMonth(15)
                .startDate(LocalDate.of(2026, 5, 15))
                .endDate(null)
                .build();
        when(ruleRepo.findIndefiniteActiveIds()).thenReturn(List.of(ruleId));
        when(ruleRepo.findForUpdate(ruleId)).thenReturn(java.util.Optional.of(rule));
        when(eventRepo.findMaxActiveDateByRule(ruleId))
                .thenReturn(java.util.Optional.of(LocalDate.of(2027, 1, 15)));   // уже за горизонтом

        service.extendIndefiniteRules(LocalDate.of(2026, 12, 31));

        verify(eventRepo, never()).saveAll(any());
    }
```

- [ ] **Step 1a: Run — must fail (`extendIndefiniteRules` does not exist yet, compile error)**

Run: `rtk JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -Dtest=RecurringRuleServiceTest`
Expected: COMPILE FAIL ("cannot find symbol: method extendIndefiniteRules"). If it compiles by mistake, the new tests must FAIL.

- [ ] **Step 2: Implement extendIndefiniteRules**

Add to service:

```java
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void extendIndefiniteRules(LocalDate requiredThrough) {
        for (java.util.UUID ruleId : ruleRepo.findIndefiniteActiveIds()) {
            // Per spec section 2 "Lazy-расширение бессрочных" — error handling:
            // "Если расширение упало — не заваливаем весь запрос планировщика,
            // логируем и читаем то, что есть." So wrap each rule independently.
            try {
                RecurringRule rule = ruleRepo.findForUpdate(ruleId)
                        .orElseThrow(() -> new IllegalStateException("rule disappeared: " + ruleId));
                LocalDate maxExisting = eventRepo.findMaxActiveDateByRule(ruleId).orElse(null);
                LocalDate from = (maxExisting != null) ? maxExisting.plusDays(1) : rule.getStartDate();
                if (from.isAfter(requiredThrough)) continue;
                List<FinancialEvent> extra = generator.generate(rule, from, requiredThrough);
                if (!extra.isEmpty()) {
                    eventRepo.saveAll(extra);
                }
            } catch (Exception e) {
                log.warn("Failed to extend indefinite rule {}: {}", ruleId, e.getMessage());
            }
        }
    }
```

- [ ] **Step 3: Run**

Run: `rtk JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -Dtest=RecurringRuleServiceTest`
Expected: all PASS.

- [ ] **Step 4: Commit**

```bash
rtk git add backend/src/main/java/ru/selfin/backend/service/RecurringRuleService.java backend/src/test/java/ru/selfin/backend/service/RecurringRuleServiceTest.java
rtk git commit -m "feat(service): extendIndefiniteRules with pessimistic lock"
```

---

### Task 2.6: RecurringRuleService — scope-based delete

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/service/RecurringRuleService.java`
- Modify: `backend/src/test/java/ru/selfin/backend/service/RecurringRuleServiceTest.java`

- [ ] **Step 1: Failing test — FOLLOWING delete sets end_date and soft-deletes future PLAN**

```java
    @Test
    void deleteScope_FOLLOWING_setsEndDate_and_softDeletesPlan() {
        UUID ruleId = UUID.randomUUID();
        RecurringRule rule = RecurringRule.builder()
                .id(ruleId)
                .category(category)
                .eventType(EventType.EXPENSE)
                .plannedAmount(new BigDecimal("80000"))
                .frequency(RecurringFrequency.MONTHLY)
                .dayOfMonth(15)
                .startDate(LocalDate.of(2026, 5, 15))
                .endDate(LocalDate.of(2027, 5, 15))
                .build();

        FinancialEvent triggerEvent = FinancialEvent.builder()
                .id(UUID.randomUUID())
                .date(LocalDate.of(2026, 7, 15))
                .recurringRule(rule)
                .build();

        service.deleteScope(triggerEvent, ru.selfin.backend.model.enums.ScopeEnum.FOLLOWING);

        verify(eventRepo).softDeletePlanEventsByRuleFromDate(ruleId, LocalDate.of(2026, 7, 15));
        assertThat(rule.getEndDate()).isEqualTo(LocalDate.of(2026, 7, 14));
        assertThat(rule.isDeleted()).isFalse();
    }

    @Test
    void deleteScope_ALL_marksRuleDeleted_and_endsOnLastExecuted() {
        UUID ruleId = UUID.randomUUID();
        RecurringRule rule = RecurringRule.builder()
                .id(ruleId)
                .category(category)
                .eventType(EventType.EXPENSE)
                .plannedAmount(new BigDecimal("80000"))
                .frequency(RecurringFrequency.MONTHLY)
                .dayOfMonth(15)
                .startDate(LocalDate.of(2026, 5, 15))
                .endDate(LocalDate.of(2027, 5, 15))
                .build();

        FinancialEvent triggerEvent = FinancialEvent.builder()
                .id(UUID.randomUUID())
                .date(LocalDate.of(2026, 7, 15))
                .recurringRule(rule)
                .build();

        when(eventRepo.findMaxExecutedDateByRule(ruleId))
                .thenReturn(java.util.Optional.of(LocalDate.of(2026, 6, 15)));

        service.deleteScope(triggerEvent, ru.selfin.backend.model.enums.ScopeEnum.ALL);

        verify(eventRepo).softDeletePlanEventsByRuleFromDate(ruleId, rule.getStartDate());
        assertThat(rule.isDeleted()).isTrue();
        assertThat(rule.getEndDate()).isEqualTo(LocalDate.of(2026, 6, 15));
    }

    @Test
    void deleteScope_ALL_with_no_executed_events_endsBeforeStart() {
        UUID ruleId = UUID.randomUUID();
        RecurringRule rule = RecurringRule.builder()
                .id(ruleId)
                .category(category)
                .eventType(EventType.EXPENSE)
                .plannedAmount(new BigDecimal("80000"))
                .frequency(RecurringFrequency.MONTHLY)
                .dayOfMonth(15)
                .startDate(LocalDate.of(2026, 5, 15))
                .endDate(LocalDate.of(2027, 5, 15))
                .build();

        FinancialEvent triggerEvent = FinancialEvent.builder()
                .id(UUID.randomUUID())
                .date(LocalDate.of(2026, 7, 15))
                .recurringRule(rule)
                .build();

        when(eventRepo.findMaxExecutedDateByRule(ruleId)).thenReturn(java.util.Optional.empty());

        service.deleteScope(triggerEvent, ru.selfin.backend.model.enums.ScopeEnum.ALL);

        assertThat(rule.isDeleted()).isTrue();
        assertThat(rule.getEndDate()).isEqualTo(LocalDate.of(2026, 5, 14)); // startDate - 1
    }
```

- [ ] **Step 1a: Run — must fail (`deleteScope` does not exist yet, compile error)**

Run: `rtk JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -Dtest=RecurringRuleServiceTest`
Expected: COMPILE FAIL ("cannot find symbol: method deleteScope"). If it compiles by mistake, the new tests must FAIL.

- [ ] **Step 2: Implement deleteScope**

```java
    @Transactional
    public void deleteScope(FinancialEvent triggerEvent, ru.selfin.backend.model.enums.ScopeEnum scope) {
        RecurringRule rule = triggerEvent.getRecurringRule();
        if (rule == null) {
            throw new IllegalArgumentException("Scope requires recurring event");
        }
        LocalDate cutoff = (scope == ru.selfin.backend.model.enums.ScopeEnum.FOLLOWING)
                ? triggerEvent.getDate()
                : rule.getStartDate();
        eventRepo.softDeletePlanEventsByRuleFromDate(rule.getId(), cutoff);

        if (scope == ru.selfin.backend.model.enums.ScopeEnum.FOLLOWING) {
            rule.setEndDate(triggerEvent.getDate().minusDays(1));
        } else {
            rule.setDeleted(true);
            LocalDate lastExec = eventRepo.findMaxExecutedDateByRule(rule.getId()).orElse(null);
            rule.setEndDate(lastExec != null ? lastExec : rule.getStartDate().minusDays(1));
        }
        ruleRepo.save(rule);
    }
```

- [ ] **Step 3: Run**

Run: `rtk JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -Dtest=RecurringRuleServiceTest`
Expected: all PASS.

- [ ] **Step 4: Commit**

```bash
rtk git add backend/src/main/java/ru/selfin/backend/service/RecurringRuleService.java backend/src/test/java/ru/selfin/backend/service/RecurringRuleServiceTest.java
rtk git commit -m "feat(service): scope-based delete (FOLLOWING/ALL)"
```

---

## Chunk 3 — FinancialEventService glue + controller scope params + DTO + planner hook

### Task 3.1: Extend FinancialEventCreateDto with optional `recurring`

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/dto/FinancialEventCreateDto.java`

- [ ] **Step 1: Add field**

```java
package ru.selfin.backend.dto;

import jakarta.validation.Valid;
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
                UUID targetFundId,
                @Valid RecurringConfigDto recurring) {
}
```

- [ ] **Step 2: Compile + run all tests (existing tests must still pass — `recurring` is nullable)**

Run: `rtk JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test`
Expected: existing tests PASS. Some tests pass DTOs positionally — verify those didn't break (they will if positional).

> ⚠️ Use `JOIN`-grep first: search for `new FinancialEventCreateDto(` in tests; if any caller uses positional args, add `null` for the recurring slot. Same for `FinancialEventCreateDto.builder()` if Lombok-style.

```bash
rtk grep "new FinancialEventCreateDto" backend/src/test
```

If any usages don't compile after recompile, fix by appending `, null` (or named arg). Re-run tests.

- [ ] **Step 3: Commit**

```bash
rtk git add backend/src/main/java/ru/selfin/backend/dto/FinancialEventCreateDto.java backend/src/test
rtk git commit -m "feat(dto): add optional recurring config to FinancialEventCreateDto"
```

---

### Task 3.2: Extend FinancialEventDto with recurring fields (response)

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/dto/FinancialEventDto.java`
- Modify: `backend/src/main/java/ru/selfin/backend/service/FinancialEventService.java` (toDto only)

- [ ] **Step 1: Add fields to record**

Add `recurringRuleId`, `recurringFrequency` (enum or String), `recurringDayOfMonth` (`Integer`), `recurringMonthOfYear` (`Integer`) to the record. Use the same record-positional or builder style the existing code uses; check the file before editing.

- [ ] **Step 2: Update `toDto` mapping in `FinancialEventService`**

Wherever `toDto(event, ...)` constructs `FinancialEventDto`, add:

```java
UUID recurringRuleId = event.getRecurringRule() != null ? event.getRecurringRule().getId() : null;
RecurringFrequency frequency = event.getRecurringRule() != null ? event.getRecurringRule().getFrequency() : null;
Integer dayOfMonth = event.getRecurringRule() != null ? event.getRecurringRule().getDayOfMonth() : null;
Integer monthOfYear = event.getRecurringRule() != null ? event.getRecurringRule().getMonthOfYear() : null;
```

Pass them into the DTO. (Use `@ManyToOne(fetch = LAZY)` — accessing inside a `@Transactional` method is fine.)

- [ ] **Step 3: Run all backend tests**

Run: `rtk JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
rtk git add backend/src/main/java/ru/selfin/backend/dto/FinancialEventDto.java backend/src/main/java/ru/selfin/backend/service/FinancialEventService.java
rtk git commit -m "feat(dto): expose recurring fields in FinancialEventDto"
```

---

### Task 3.3: FinancialEventService.create — branch on `recurring`

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/service/FinancialEventService.java`
- Modify: `backend/src/main/java/ru/selfin/backend/service/RecurringRuleService.java` (signature extension)
- Modify: `backend/src/test/java/ru/selfin/backend/service/FinancialEventServiceTest.java`

> **Atomicity**: both `createIdempotent` (already `@Transactional`) and `RecurringRuleService.createFromDto` (also `@Transactional`, default `Propagation.REQUIRED`) join the same outer transaction. Any failure (rule save, event save, idempotency-key conflict) rolls back the whole batch — single rule + N events are written all-or-nothing per spec §3.
>
> **Head event ordering**: `RecurringEventGenerator.generate(rule, from, through)` produces events in ascending date order (it walks a cursor monotonically forward via `next()`). So `result.events().get(0)` is the earliest date. The `CreateResult` Javadoc must state this contract explicitly (added below).

- [ ] **Step 0 (refactor `CreateResult` contract)**: Open `RecurringRuleService.java` and add a Javadoc to `CreateResult`:

```java
    /**
     * @param rule   created and persisted rule (id assigned)
     * @param events generated PLAN events in ascending date order;
     *               {@code events.get(0)} is the earliest occurrence (head event).
     */
    public record CreateResult(RecurringRule rule, List<FinancialEvent> events) {}
```

- [ ] **Step 0b (extend `createFromDto` signature)**: The current `createFromDto(category, type, plannedAmount, priority, description, cfg)` does not accept `targetFundId` or `rawInput`. The recurring-create path must propagate `targetFundId` to every generated event (required for FUND_TRANSFER rules) and `rawInput` only to the head event (raw NL text is per-creation, not per-occurrence). Add overload:

```java
    @Transactional
    public CreateResult createFromDto(Category category, EventType type, BigDecimal plannedAmount,
                                      Priority priority, String description, UUID targetFundId,
                                      String headRawInput, RecurringConfigDto cfg) {
        validateConfig(cfg);
        RecurringRule rule = RecurringRule.builder()
                .category(category)
                .eventType(type)
                .plannedAmount(plannedAmount)
                .priority(priority != null ? priority : Priority.MEDIUM)
                .description(description)
                .frequency(cfg.frequency())
                .dayOfMonth(cfg.dayOfMonth())
                .monthOfYear(cfg.monthOfYear())
                .startDate(cfg.startDate())
                .endDate(cfg.endDate())
                .build();
        rule = ruleRepo.save(rule);

        LocalDate horizonEnd = cfg.endDate() != null
                ? cfg.endDate()
                : LocalDate.now().plusMonths(36);
        List<FinancialEvent> events = generator.generate(rule, cfg.startDate(), horizonEnd);
        // Propagate targetFundId to ALL events (FUND_TRANSFER recurring needs it on every row).
        events.forEach(e -> e.setTargetFundId(targetFundId));
        // rawInput only on head event — represents user's original NL input at creation.
        if (!events.isEmpty() && headRawInput != null) {
            events.get(0).setRawInput(headRawInput);
        }
        eventRepo.saveAll(events);
        return new CreateResult(rule, events);
    }

    // Keep the 6-arg form delegating to the new 8-arg form for back-compat with Chunk-2 tests.
    @Transactional
    public CreateResult createFromDto(Category category, EventType type, BigDecimal plannedAmount,
                                      Priority priority, String description, RecurringConfigDto cfg) {
        return createFromDto(category, type, plannedAmount, priority, description, null, null, cfg);
    }
```

- [ ] **Step 1: Failing test — createIdempotent with recurring config delegates to RecurringRuleService**

Add a Mockito test (mock `RecurringRuleService`) verifying:
1. When `dto.recurring() != null`, `ruleService.createFromDto(category, type, plannedAmount, priority, description, targetFundId, rawInput, cfg)` is called once with matching args.
2. The returned head event has `recurringRule` set, `idempotencyKey` set to the request key, and is the first event by date.
3. When `dto.recurring() == null`, `ruleService` is NOT called (existing flow).
4. When the head event's `eventRepository.save(head)` fails (mock throws), the transaction rolls back — verify by mocking `ruleService.createFromDto` succeeded but no event has the idempotency key persisted (this is verified at the IT level, mention in test comment).

(Keep test concise; pattern matches existing `FinancialEventServiceTest`.)

- [ ] **Step 1a: Run — must fail**

Run: `rtk JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -Dtest=FinancialEventServiceTest`
Expected: FAIL — branch not implemented yet, mock verification fails.

- [ ] **Step 2: Inject `RecurringRuleService` into `FinancialEventService`**

Add a private final `RecurringRuleService ruleService;` field; Lombok `@RequiredArgsConstructor` picks it up automatically.

- [ ] **Step 3: Branch in `createIdempotent`**

In `FinancialEventService.createIdempotent`, after the idempotency-key short-circuit and after `category` is resolved, but BEFORE the existing single-event builder, insert:

```java
if (dto.recurring() != null) {
    Priority effectivePriority = dto.priority() != null ? dto.priority() : category.getPriority();
    var result = ruleService.createFromDto(
            category, dto.type(), dto.plannedAmount(),
            effectivePriority, dto.description(),
            dto.targetFundId(), dto.rawInput(),
            dto.recurring());
    // Head event = events[0] per CreateResult contract (earliest date, ascending order).
    FinancialEvent head = result.events().get(0);
    head.setIdempotencyKey(idempotencyKey);
    eventRepository.save(head);
    // Tail events have null idempotency_key per spec §3.
    return toDto(head, null, null);
}
```

- [ ] **Step 4: Run tests**

Run: `rtk JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -Dtest=FinancialEventServiceTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
rtk git add backend/src/main/java/ru/selfin/backend/service/FinancialEventService.java backend/src/main/java/ru/selfin/backend/service/RecurringRuleService.java backend/src/test/java/ru/selfin/backend/service/FinancialEventServiceTest.java
rtk git commit -m "feat(service): create with recurring config delegates to RecurringRuleService"
```

---

### Task 3.4a: RecurringRuleService — `applyDtoToRule` helper

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/service/RecurringRuleService.java`
- Modify: `backend/src/test/java/ru/selfin/backend/service/RecurringRuleServiceTest.java`

- [ ] **Step 1: Failing test**

Add to `RecurringRuleServiceTest`:

```java
    @Test
    void applyDtoToRule_updatesAmountAndDescription_butNeverStartDateOrType() {
        RecurringRule rule = RecurringRule.builder()
                .id(UUID.randomUUID())
                .category(category)
                .eventType(EventType.EXPENSE)
                .plannedAmount(new BigDecimal("80000"))
                .priority(Priority.MEDIUM)
                .description("Старое описание")
                .frequency(RecurringFrequency.MONTHLY)
                .dayOfMonth(15)
                .startDate(LocalDate.of(2026, 5, 15))
                .endDate(LocalDate.of(2027, 5, 15))
                .build();

        var dto = new ru.selfin.backend.dto.FinancialEventCreateDto(
                LocalDate.of(2026, 7, 15),  // ignored — date is per-event, not per-rule
                category.getId(),
                EventType.EXPENSE,           // type cannot change (I8); same value is fine
                new BigDecimal("90000"),
                Priority.HIGH,
                "Новое описание",
                null, null,
                new RecurringConfigDto(RecurringFrequency.MONTHLY, 15, null,
                        null,                 // startDate must be null at edit-time
                        LocalDate.of(2027, 12, 31)));

        service.applyDtoToRule(rule, dto);

        assertThat(rule.getPlannedAmount()).isEqualByComparingTo("90000");
        assertThat(rule.getPriority()).isEqualTo(Priority.HIGH);
        assertThat(rule.getDescription()).isEqualTo("Новое описание");
        assertThat(rule.getEndDate()).isEqualTo(LocalDate.of(2027, 12, 31));
        // Immutable per I8:
        assertThat(rule.getStartDate()).isEqualTo(LocalDate.of(2026, 5, 15));
        assertThat(rule.getEventType()).isEqualTo(EventType.EXPENSE);
    }

    @Test
    void applyDtoToRule_rejectsStartDateChange() {
        RecurringRule rule = RecurringRule.builder()
                .id(UUID.randomUUID())
                .startDate(LocalDate.of(2026, 5, 15))
                .frequency(RecurringFrequency.MONTHLY).dayOfMonth(15)
                .build();
        var dto = new ru.selfin.backend.dto.FinancialEventCreateDto(
                LocalDate.of(2026, 6, 15), null, EventType.EXPENSE, BigDecimal.ONE,
                null, null, null, null,
                new RecurringConfigDto(RecurringFrequency.MONTHLY, 15, null,
                        LocalDate.of(2026, 6, 1),  // ← non-null = attempt to change start
                        null));

        assertThatThrownBy(() -> service.applyDtoToRule(rule, dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("start_date is immutable");
    }
```

Add imports as needed (`org.assertj.core.api.Assertions.assertThatThrownBy`).

- [ ] **Step 1a: Run — must fail (`applyDtoToRule` does not exist)**

Run: `rtk JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -Dtest=RecurringRuleServiceTest`
Expected: COMPILE FAIL.

- [ ] **Step 2: Implement `applyDtoToRule`**

Add to `RecurringRuleService`:

```java
    /**
     * Применяет редактируемые поля из FinancialEventCreateDto к правилу.
     * Запрещено: смена startDate, type, frequency (см. spec, I8). Эти поля игнорируются
     * для frequency/type, но изменение startDate вызывает 400.
     */
    public void applyDtoToRule(RecurringRule rule, ru.selfin.backend.dto.FinancialEventCreateDto dto) {
        if (dto.recurring() != null && dto.recurring().startDate() != null
                && !dto.recurring().startDate().equals(rule.getStartDate())) {
            throw new IllegalArgumentException(
                    "start_date is immutable; delete the rule and create a new one (I8)");
        }
        // Editable per-rule fields:
        rule.setPlannedAmount(dto.plannedAmount());
        rule.setPriority(dto.priority() != null ? dto.priority() : rule.getPriority());
        rule.setDescription(dto.description());
        if (dto.recurring() != null) {
            // dayOfMonth / monthOfYear are conceptually editable; spec §3 leaves this as a
            // contract decision. We allow updating these — they only affect future regenerated
            // dates; existing EXECUTED dates are preserved by regenerate().
            rule.setDayOfMonth(dto.recurring().dayOfMonth());
            rule.setMonthOfYear(dto.recurring().monthOfYear());
            rule.setEndDate(dto.recurring().endDate());
        }
    }
```

- [ ] **Step 3: Run, expect PASS; Commit**

```bash
rtk JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -Dtest=RecurringRuleServiceTest
rtk git add backend/src/main/java/ru/selfin/backend/service/RecurringRuleService.java backend/src/test/java/ru/selfin/backend/service/RecurringRuleServiceTest.java
rtk git commit -m "feat(service): applyDtoToRule helper (rule-level edit)"
```

---

### Task 3.4b: FinancialEventService.update — accept scope

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/service/FinancialEventService.java`
- Modify: `backend/src/main/java/ru/selfin/backend/controller/FinancialEventController.java` (positional caller fix)
- Modify: `backend/src/test/java/ru/selfin/backend/service/FinancialEventServiceTest.java`

> **Existing contract preserved**: the current `update()` (FinancialEventService.java lines 178–215) rejects FACT records (line 183–186) and resolves `category` (lines 188–195). Both behaviors must survive the refactor. Exception class to use: `org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "...")` — already used in the existing method at line 184.

- [ ] **Step 1: Failing tests**

Add to `FinancialEventServiceTest`:

```java
    @Test
    void update_THIS_on_recurring_event_updates_only_that_event() { /* ... */ }

    @Test
    void update_THIS_on_FACT_throws_400() {
        // mirrors existing FACT-rejection test in current update() — must survive refactor.
    }

    @Test
    void update_FOLLOWING_on_non_recurring_event_throws_400() { /* ... */ }

    @Test
    void update_FOLLOWING_with_changed_startDate_throws_400() { /* ... */ }

    @Test
    void update_FOLLOWING_calls_applyDtoToRule_then_regenerate_from_event_date() { /* mock verify */ }

    @Test
    void update_ALL_calls_regenerate_from_rule_startDate() { /* mock verify */ }
```

For each, write the smallest assertion that pins behavior. Use Mockito `verify(...)` for rule-service interactions.

- [ ] **Step 1a: Run — must fail (new signature does not exist)**

Run: `rtk JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -Dtest=FinancialEventServiceTest`
Expected: COMPILE FAIL.

- [ ] **Step 2: Refactor `update()` to scope-aware signature**

Replace the existing `update(UUID id, FinancialEventCreateDto dto)` (lines 178–215) with the version below. The extracted `applyDto` helper preserves existing setter logic verbatim — same behavior as before for THIS scope on a non-recurring event.

```java
    @Transactional
    public FinancialEventDto update(UUID id, ScopeEnum scope, FinancialEventCreateDto dto) {
        FinancialEvent event = eventRepository.findById(id)
                .filter(e -> !e.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("FinancialEvent", id));

        // Existing guard: FACT records are not editable via PUT (preserved from old method).
        if (event.getEventKind() == EventKind.FACT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Cannot update a FACT record via PUT — use the fact-specific endpoint");
        }

        RecurringRule rule = event.getRecurringRule();
        if (scope != ScopeEnum.THIS && rule == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Scope FOLLOWING/ALL requires a recurring event");
        }

        if (scope == ScopeEnum.THIS) {
            Category category = resolveCategoryForUpdate(dto);
            applyDto(event, dto, category);
            return toDto(eventRepository.save(event), null, null);
        }

        // FOLLOWING / ALL
        ruleService.applyDtoToRule(rule, dto);   // throws 400 if startDate change attempted
        LocalDate regenerateFrom = (scope == ScopeEnum.FOLLOWING) ? event.getDate() : rule.getStartDate();
        ruleService.regenerate(rule, regenerateFrom);

        // Re-fetch the event at the same date — regenerate may have replaced the row identity.
        return eventRepository.findActiveByRuleAndDate(rule.getId(), event.getDate())
                .map(e -> toDto(e, null, null))
                .orElseThrow(() -> new IllegalStateException(
                        "Regenerate dropped the trigger date " + event.getDate() + " for rule " + rule.getId()));
    }

    /** Extracted from old update() — verbatim category resolution. */
    private Category resolveCategoryForUpdate(FinancialEventCreateDto dto) {
        if (dto.type() == EventType.FUND_TRANSFER && dto.categoryId() == null) {
            return targetFundService.getOrCreateFundTransferCategory();
        }
        return categoryRepository.findById(dto.categoryId())
                .filter(c -> !c.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("Category", dto.categoryId()));
    }

    /** Extracted from old update() — verbatim setter sequence. */
    private void applyDto(FinancialEvent event, FinancialEventCreateDto dto, Category category) {
        BigDecimal oldFact = event.getFactAmount();
        event.setDate(dto.date());
        event.setCategory(category);
        event.setType(dto.type());
        event.setPlannedAmount(dto.plannedAmount());
        event.setPriority(dto.priority() != null ? dto.priority() : category.getPriority());
        event.setDescription(dto.description());
        event.setRawInput(dto.rawInput());
        event.setTargetFundId(dto.targetFundId());
        if (oldFact != null) {
            log.info("plan_update event_id={} category={} planned={} (fact retained on FACT record)",
                    event.getId(), category.getName(), dto.plannedAmount());
        }
    }
```

- [ ] **Step 3: Update positional callers**

Search for callers of the old 2-arg signature and add `ScopeEnum.THIS`:

```bash
rtk grep "eventService.update(" backend/src
rtk grep "\.update(" backend/src/main/java/ru/selfin/backend/controller/FinancialEventController.java
```

The controller PUT handler will be re-written in Task 3.5 anyway. Other callers (if any in tests) must be updated to pass `ScopeEnum.THIS` explicitly.

- [ ] **Step 4: Run, expect PASS; Commit**

```bash
rtk JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test
rtk git add backend/src/main/java/ru/selfin/backend/service/FinancialEventService.java backend/src/test/java/ru/selfin/backend/service/FinancialEventServiceTest.java
rtk git commit -m "feat(service): update accepts ScopeEnum (THIS|FOLLOWING|ALL)"
```

---

### Task 3.4c: FinancialEventService.delete — accept scope

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/service/FinancialEventService.java`
- Modify: `backend/src/test/java/ru/selfin/backend/service/FinancialEventServiceTest.java`

- [ ] **Step 1: Failing tests**

```java
    @Test
    void delete_THIS_softDeletes_only_event() { /* ... */ }

    @Test
    void delete_FOLLOWING_on_non_recurring_throws_400() { /* ... */ }

    @Test
    void delete_FOLLOWING_delegates_to_ruleService_deleteScope() { /* mock verify */ }

    @Test
    void delete_ALL_delegates_to_ruleService_deleteScope() { /* mock verify */ }
```

- [ ] **Step 1a: Run — must fail**

Run: `rtk JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -Dtest=FinancialEventServiceTest`
Expected: COMPILE FAIL.

- [ ] **Step 2: Implement scope-aware delete**

Replace existing `delete(UUID id)` (find current signature in `FinancialEventService` near the end of the class) with:

```java
    @Transactional
    public void delete(UUID id, ScopeEnum scope) {
        FinancialEvent event = eventRepository.findById(id)
                .filter(e -> !e.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("FinancialEvent", id));

        if (scope != ScopeEnum.THIS && event.getRecurringRule() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Scope FOLLOWING/ALL requires a recurring event");
        }

        if (scope == ScopeEnum.THIS) {
            event.setDeleted(true);
            eventRepository.save(event);
            return;
        }
        ruleService.deleteScope(event, scope);
    }
```

- [ ] **Step 3: Update positional callers**

```bash
rtk grep "eventService.delete(" backend/src
```

Add `ScopeEnum.THIS` to any 1-arg call. Controller will be rewritten in Task 3.5.

- [ ] **Step 4: Run, expect PASS; Commit**

```bash
rtk JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test
rtk git add backend/src/main/java/ru/selfin/backend/service/FinancialEventService.java backend/src/test/java/ru/selfin/backend/service/FinancialEventServiceTest.java
rtk git commit -m "feat(service): delete accepts ScopeEnum (THIS|FOLLOWING|ALL)"
```

---

### Task 3.5: Controller — `?scope=` query param on PUT/DELETE

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/controller/FinancialEventController.java`

- [ ] **Step 1: Update `PUT /{id}` and `DELETE /{id}` signatures**

Replace existing PUT and DELETE handlers:

```java
@PutMapping("/{id}")
public FinancialEventDto update(
        @PathVariable UUID id,
        @RequestParam(required = false, defaultValue = "THIS") ScopeEnum scope,
        @Valid @RequestBody FinancialEventCreateDto dto) {
    return eventService.update(id, scope, dto);
}

@DeleteMapping("/{id}")
public ResponseEntity<Void> delete(
        @PathVariable UUID id,
        @RequestParam(required = false, defaultValue = "THIS") ScopeEnum scope) {
    eventService.delete(id, scope);
    return ResponseEntity.noContent().build();
}
```

- [ ] **Step 2: Run controller-level tests / IT**

Run: `rtk JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
rtk git add backend/src/main/java/ru/selfin/backend/controller/FinancialEventController.java
rtk git commit -m "feat(controller): accept ?scope= on PUT/DELETE /events/{id}"
```

---

### Task 3.6: Hook into FundPlannerService

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/service/FundPlannerService.java`

- [ ] **Step 1: Inject `RecurringRuleService` and add `@Slf4j`**

Add `private final RecurringRuleService recurringRuleService;` to the service. Also annotate the class with `@Slf4j` (Lombok) and add `import lombok.extern.slf4j.Slf4j;` — the catch block in Step 2 uses `log.warn(...)` and `FundPlannerService` does not currently have a logger.

- [ ] **Step 2: Call `extendIndefiniteRules` first thing in `getPlanner()`**

Per spec §2: "Если расширение упало — не заваливаем весь запрос планировщика, логируем и читаем то, что есть." So wrap the call in try/catch.

```java
@Transactional(readOnly = true)
public FundPlannerDto getPlanner() {
    // Ленивое расширение бессрочных правил (REQUIRES_NEW; см. spec Секция 2).
    // Errors must NOT fail the read — log and continue with whatever events exist.
    try {
        recurringRuleService.extendIndefiniteRules(LocalDate.now().plusMonths(36));
    } catch (Exception e) {
        log.warn("Lazy-extend of indefinite rules failed; continuing with current events: {}",
                e.getMessage());
    }

    // ... existing aggregation ...
}
```

- [ ] **Step 3: Run tests**

Run: `rtk JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -Dtest=FundPlannerServiceTest`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
rtk git add backend/src/main/java/ru/selfin/backend/service/FundPlannerService.java
rtk git commit -m "feat(planner): lazy-extend indefinite recurring rules on getPlanner"
```

---

### Task 3.7: FACT propagation — copy `recurring_rule_id` from parent PLAN

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/service/FinancialEventService.java` (`createLinkedFact`, lines 227–254)
- Modify: `backend/src/test/java/ru/selfin/backend/service/FinancialEventServiceTest.java`

> **Scope**: only `createLinkedFact()` (which builds a fresh FACT row tied to a PLAN). The legacy `updateFact()` path (around line 267) merges `factAmount` onto an existing PLAN row — it does NOT create a new row, so no `recurringRule` propagation is needed there. Confirmed via the spec §3 line stating "FACT наследует rule на CREATE."

- [ ] **Step 1: Failing test — `createLinkedFact(planId, factDto)` produces FACT with same `recurringRule` as parent**

Add to `FinancialEventServiceTest`:

```java
    @Test
    void createLinkedFact_inherits_recurringRule_from_parent_plan() {
        UUID planId = UUID.randomUUID();
        Category cat = Category.builder().id(UUID.randomUUID()).build();
        RecurringRule rule = RecurringRule.builder().id(UUID.randomUUID()).build();
        FinancialEvent plan = FinancialEvent.builder()
                .id(planId)
                .eventKind(EventKind.PLAN)
                .category(cat)
                .type(EventType.EXPENSE)
                .recurringRule(rule)
                .priority(Priority.HIGH)
                .status(EventStatus.PLANNED)
                .build();

        when(eventRepository.findById(planId)).thenReturn(java.util.Optional.of(plan));
        when(eventRepository.save(any(FinancialEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        var factDto = new ru.selfin.backend.dto.FactCreateDto(
                LocalDate.now(), new BigDecimal("90000"), "Оплачено", null);

        service.createLinkedFact(planId, factDto);

        ArgumentCaptor<FinancialEvent> cap = ArgumentCaptor.forClass(FinancialEvent.class);
        verify(eventRepository, atLeastOnce()).save(cap.capture());
        // First saved entity is the new FACT row.
        FinancialEvent fact = cap.getAllValues().get(0);
        assertThat(fact.getEventKind()).isEqualTo(EventKind.FACT);
        assertThat(fact.getRecurringRule()).isSameAs(rule);
    }
```

- [ ] **Step 1a: Run — must fail**

Run: `rtk JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -Dtest=FinancialEventServiceTest#createLinkedFact_inherits_recurringRule_from_parent_plan`
Expected: FAIL — `fact.getRecurringRule()` is null because `createLinkedFact` does not yet propagate the rule.

- [ ] **Step 2: Wire it in the service**

In `FinancialEventService.createLinkedFact` (lines 227–254), in the `FinancialEvent.builder()` block (line 233–244), add `.recurringRule(plan.getRecurringRule())` after `.priority(...)`:

```java
        FinancialEvent fact = FinancialEvent.builder()
                .idempotencyKey(UUID.randomUUID())
                .eventKind(EventKind.FACT)
                .parentEventId(planId)
                .date(dto.date())
                .category(plan.getCategory())
                .type(plan.getType())
                .factAmount(dto.factAmount())
                .priority(dto.priority() != null ? dto.priority() : plan.getPriority())
                .recurringRule(plan.getRecurringRule())   // ← inherit (null if parent is non-recurring)
                .status(EventStatus.EXECUTED)
                .description(dto.description())
                .build();
```

- [ ] **Step 3: Run, expect PASS; Commit**

```bash
rtk JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -Dtest=FinancialEventServiceTest
rtk git add backend/src/main/java/ru/selfin/backend/service/FinancialEventService.java backend/src/test/java/ru/selfin/backend/service/FinancialEventServiceTest.java
rtk git commit -m "feat(service): FACT inherits recurring_rule_id from parent PLAN"
```

---

## Chunk 4 — Integration tests (HTTP-level, Testcontainers)

### Task 4.1: New IT class scaffold

**Files:**
- Create: `backend/src/test/java/ru/selfin/backend/RecurringEventControllerIT.java`

- [ ] **Step 1: Bootstrap class mirroring `FinancialEventControllerIT`**

Same `@SpringBootTest`/`@AutoConfigureMockMvc`/`@Testcontainers` annotations. Copy the `getFirstCategoryId()` helper.

- [ ] **Step 2: First IT — create monthly with end_date generates 12 events**

```java
@Test
void createRecurring_monthly_with_endDate_generates_full_horizon() throws Exception {
    String catId = getFirstCategoryId();
    String idem = UUID.randomUUID().toString();
    String startDate = LocalDate.now().plusDays(1).toString();   // I3 — будущее
    String endDate = LocalDate.now().plusMonths(11).plusDays(1).toString();

    String body = """
        {
          "date": "%s",
          "categoryId": "%s",
          "type": "EXPENSE",
          "plannedAmount": 80000,
          "priority": "HIGH",
          "description": "Ипотека",
          "recurring": {
            "frequency": "MONTHLY",
            "dayOfMonth": %d,
            "endDate": "%s"
          }
        }
        """.formatted(startDate, catId, LocalDate.now().plusDays(1).getDayOfMonth(), endDate);

    mockMvc.perform(post("/api/v1/events")
                    .header("Idempotency-Key", idem)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.recurringRuleId").exists());

    // Список событий по периоду должен содержать 12 шт. с тем же ruleId
    String list = mockMvc.perform(get("/api/v1/events")
                    .param("startDate", startDate)
                    .param("endDate", endDate))
            .andReturn().getResponse().getContentAsString();
    var arr = objectMapper.readValue(list, java.util.List.class);
    assertThat(arr).hasSize(12);
}
```

- [ ] **Step 3: Run**

Run: `rtk JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -Dtest=RecurringEventControllerIT`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
rtk git add backend/src/test/java/ru/selfin/backend/RecurringEventControllerIT.java
rtk git commit -m "test(it): create recurring monthly with end_date"
```

---

### Task 4.2: IT — scope=FOLLOWING update halfway

- [ ] **Step 1: Test**

Add `@Test scopeFollowing_update_amount_changes_only_future`: create 12 monthly events, PUT 6th with `scope=FOLLOWING` and a new amount; expect first 5 untouched, last 7 with new amount, all retain ruleId.

- [ ] **Step 2: Run + commit**

---

### Task 4.3: IT — FACT preserved during scope=ALL edit

- [ ] **Step 1: Test** — create rule, PATCH-fact one event (turning it EXECUTED), PUT scope=ALL with new amount. Assert: EXECUTED event unchanged (by date and amount), all other PLAN events have new amount.

- [ ] **Step 2: Run + commit**

---

### Task 4.4: IT — scope=ALL delete

- [ ] **Step 1: Test** — create rule, DELETE one event with scope=ALL. Assert: rule.is_deleted=true (verify via lookup of any remaining event — none should be active), no PLAN events left, FACT events still queryable if any.

- [ ] **Step 2: Run + commit**

---

### Task 4.5: IT — bessрочное + lazy-extend through getPlanner

- [ ] **Step 1: Test** — create rule with `endDate=null`, then GET /api/v1/funds/planner. Assert: planner contains entries reflecting future months (specific assertion depends on PlannerDto schema; consult spec/DTO).

Direct check: query DB via repo (autowire `FinancialEventRepository`) — count events for the rule before and after planner call. After should be ≥36 instances.

- [ ] **Step 2: Run + commit**

---

### Task 4.6: IT — retroactive create rejected (I3)

- [ ] **Step 1: Test** — POST with `recurring.startDate = today.minusDays(1)` → 400. Verify zero rows in `recurring_rule` and `financial_events` (filter by ruleId or count delta).

- [ ] **Step 2: Run + commit**

---

### Task 4.7: IT — YEARLY 29 февраля clamp at create

- [ ] **Step 1: Test** — create YEARLY with day=29, monthOfYear=2, startDate non-leap (e.g. 2027-02-28). Assert first generated date is 2027-02-28, then 2028-02-29, etc.

- [ ] **Step 2: Run + commit**

---

### Task 4.8: IT — start_date immutability via PUT (I8)

- [ ] **Step 1: Test** — create recurring rule, PUT scope=ALL with body containing `recurring.startDate = futureDate`; expect 400. Verify rule.start_date unchanged.

- [ ] **Step 2: Run + commit**

---

### Task 4.9: IT — FACT inherits recurring_rule_id

- [ ] **Step 1: Test** — create recurring rule, PATCH `/events/{planId}/fact` to attach factAmount; GET that event. Assert `recurringRuleId` is present and equal to parent's.

- [ ] **Step 2: Run + commit**

---

### Task 4.10: IT — invalid `recurring.frequency=WEEKLY` → 400

- [ ] **Step 1: Test** — POST with frequency=WEEKLY (or any unknown value) → 400 + ErrorResponse JSON. (Spring will reject deserialization, returning 400 from `GlobalExceptionHandler`.)

- [ ] **Step 2: Run + commit**

---

## Chunk 5 — Frontend types, API, EventSheet, EditEventSheet

### Task 5.1: Extend `frontend/src/types/api.ts`

**Files:**
- Modify: `frontend/src/types/api.ts`

- [ ] **Step 1: Add types**

```ts
export type RecurringFrequency = 'MONTHLY' | 'YEARLY';
export type ScopeEnum = 'THIS' | 'FOLLOWING' | 'ALL';

export interface RecurringConfig {
    frequency: RecurringFrequency;
    dayOfMonth: number;
    monthOfYear?: number | null;
    startDate?: string;       // YYYY-MM-DD; на edit игнорируется
    endDate?: string | null;  // null = бессрочно
}
```

Extend `FinancialEvent`:

```ts
recurringRuleId?: string | null;
recurringFrequency?: RecurringFrequency | null;
recurringDayOfMonth?: number | null;
recurringMonthOfYear?: number | null;
```

Extend `FinancialEventCreateDto`:

```ts
recurring?: RecurringConfig | null;
```

- [ ] **Step 2: Type-check**

Run: `cd frontend && npx tsc --noEmit`
Expected: clean.

- [ ] **Step 3: Commit**

```bash
rtk git add frontend/src/types/api.ts
rtk git commit -m "feat(frontend): types for recurring events"
```

---

### Task 5.2: Update API helpers — pass scope on PUT/DELETE

**Files:**
- Modify: `frontend/src/api/index.ts`

> **Verified `client.ts` contract**: `put(path, body)` and `del(path)` are thin wrappers that pass `path` directly to `fetch(BASE_URL + path, ...)` — they do NOT touch the query string. Appending `?scope=...` to the path string is the correct mechanism (already used by `getEvents` for `?startDate=...&endDate=...`).

- [ ] **Step 1: Refactor `updateEvent` and `deleteEvent`**

```ts
export const updateEvent = (id: string, dto: FinancialEventCreateDto, scope: ScopeEnum = 'THIS') =>
    put<FinancialEvent>(`/events/${id}?scope=${scope}`, dto);

export const deleteEvent = (id: string, scope: ScopeEnum = 'THIS') =>
    del(`/events/${id}?scope=${scope}`);
```

`?scope=THIS|FOLLOWING|ALL` is URL-safe (no encoding needed).

- [ ] **Step 2: Type-check**

Run: `cd frontend && npx tsc --noEmit`
Expected: clean.

- [ ] **Step 3: Update existing callers**

Search for `updateEvent(` and `deleteEvent(` across `frontend/src`. Existing single-event flows can omit `scope` (default `THIS`). The `EditEventSheet` and `BudgetStructureSection` callers will be updated in Task 5.4 / 5.6.

- [ ] **Step 4: Type-check + commit**

```bash
rtk git add frontend/src/api/index.ts
rtk git commit -m "feat(frontend-api): support scope on update/delete event"
```

---

### Task 5.3: Component `<RecurringFields>`

**Files:**
- Create: `frontend/src/components/RecurringFields.tsx`

- [ ] **Step 1: Component**

```tsx
import type { RecurringConfig, RecurringFrequency } from '../types/api';
import { Input } from './ui/input';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from './ui/select';

interface Props {
    enabled: boolean;
    value: RecurringConfig;
    eventDate: string;
    onToggle: (enabled: boolean) => void;
    onChange: (next: RecurringConfig) => void;
}

const RU_MONTHS = ['Январь','Февраль','Март','Апрель','Май','Июнь',
                   'Июль','Август','Сентябрь','Октябрь','Ноябрь','Декабрь'];

/**
 * Универсальный блок «Повторять» для формы создания/редактирования события.
 * Автоподставляет dayOfMonth / monthOfYear из eventDate при включении.
 */
export default function RecurringFields({ enabled, value, eventDate, onToggle, onChange }: Props) {
    const handleToggle = (e: React.ChangeEvent<HTMLInputElement>) => {
        const isOn = e.target.checked;
        if (isOn && eventDate) {
            const d = new Date(eventDate + 'T00:00:00');
            onChange({
                frequency: value.frequency ?? 'MONTHLY',
                dayOfMonth: value.dayOfMonth ?? d.getDate(),
                monthOfYear: value.frequency === 'YEARLY' ? (value.monthOfYear ?? d.getMonth() + 1) : null,
                endDate: value.endDate ?? null,
                startDate: eventDate,
            });
        }
        onToggle(isOn);
    };

    return (
        <div className="space-y-2">
            <label className="flex items-center gap-2 text-sm">
                <input type="checkbox" checked={enabled} onChange={handleToggle} />
                Повторять
            </label>

            {enabled && (
                <div className="space-y-2 pl-5 border-l-2 border-muted">
                    <div className="flex items-center gap-2">
                        <span className="text-xs w-20">Частота</span>
                        <Select
                            value={value.frequency}
                            onValueChange={(v: RecurringFrequency) => onChange({ ...value, frequency: v, monthOfYear: v === 'YEARLY' ? (value.monthOfYear ?? 1) : null })}
                        >
                            <SelectTrigger className="h-8 w-40 text-xs"><SelectValue /></SelectTrigger>
                            <SelectContent>
                                <SelectItem value="MONTHLY">Ежемесячно</SelectItem>
                                <SelectItem value="YEARLY">Ежегодно</SelectItem>
                            </SelectContent>
                        </Select>
                    </div>

                    <div className="flex items-center gap-2">
                        <span className="text-xs w-20">День</span>
                        <Input
                            type="number"
                            min={1} max={31}
                            value={value.dayOfMonth}
                            onChange={e => onChange({ ...value, dayOfMonth: Number(e.target.value) })}
                            className="h-8 w-20 text-xs"
                        />
                    </div>

                    {value.frequency === 'YEARLY' && (
                        <div className="flex items-center gap-2">
                            <span className="text-xs w-20">Месяц</span>
                            <Select
                                value={String(value.monthOfYear ?? 1)}
                                onValueChange={v => onChange({ ...value, monthOfYear: Number(v) })}
                            >
                                <SelectTrigger className="h-8 w-40 text-xs"><SelectValue /></SelectTrigger>
                                <SelectContent>
                                    {RU_MONTHS.map((name, idx) => (
                                        <SelectItem key={idx} value={String(idx + 1)}>{name}</SelectItem>
                                    ))}
                                </SelectContent>
                            </Select>
                        </div>
                    )}

                    <div className="flex items-center gap-2">
                        <span className="text-xs w-20">Окончание</span>
                        <label className="text-xs flex items-center gap-1">
                            <input type="radio" name="endMode"
                                checked={value.endDate == null}
                                onChange={() => onChange({ ...value, endDate: null })} />
                            Бессрочно
                        </label>
                        <label className="text-xs flex items-center gap-1">
                            <input type="radio" name="endMode"
                                checked={value.endDate != null}
                                onChange={() => onChange({ ...value, endDate: eventDate })} />
                            До даты
                        </label>
                        {value.endDate != null && (
                            <Input type="date"
                                value={value.endDate}
                                onChange={e => onChange({ ...value, endDate: e.target.value })}
                                className="h-8 text-xs" />
                        )}
                    </div>
                </div>
            )}
        </div>
    );
}
```

- [ ] **Step 2: Type-check + commit**

```bash
rtk git add frontend/src/components/RecurringFields.tsx
rtk git commit -m "feat(frontend): RecurringFields component (form section)"
```

---

### Task 5.4: Embed `<RecurringFields>` into `Fab` (the create-event form)

**Files:**
- Modify: `frontend/src/components/Fab.tsx`

> **Verified location**: the create-event form lives in `frontend/src/components/Fab.tsx` (a floating Action Button that opens an inline form). There is no `EventSheet.tsx` in this codebase. `Fab.tsx` imports `createEvent` and renders the form inline.

- [ ] **Step 1: Open `Fab.tsx`** and locate (a) the `useState` block where `date`, `amount`, `category` etc. are tracked and (b) the `handleSubmit` (or similar) callback that calls `createEvent(...)`. Add the new state alongside the existing ones:

```tsx
const [recurringEnabled, setRecurringEnabled] = useState(false);
const [recurring, setRecurring] = useState<RecurringConfig>({
    frequency: 'MONTHLY',
    dayOfMonth: 1,
    monthOfYear: null,
    endDate: null,
});
```

- [ ] **Step 2: Render `<RecurringFields>`**

Insert `<RecurringFields enabled={recurringEnabled} value={recurring} eventDate={date} onToggle={setRecurringEnabled} onChange={setRecurring} />` after the date/amount fields and before the submit button. Do NOT remove or alter any existing inputs (priority cycling, mandatory toggle, fund picker, etc.).

- [ ] **Step 3: Pass through to API on submit**

In the `createEvent` call site, add the `recurring` field to the DTO:

```tsx
const dto: FinancialEventCreateDto = {
    // ... existing fields unchanged ...
    recurring: recurringEnabled ? { ...recurring, startDate: date } : null,
};
```

- [ ] **Step 4: Type-check + manual smoke**

```bash
rtk npm --prefix frontend run typecheck
rtk npm --prefix frontend run dev
```

Smoke (manual): open the FAB, fill the form with a date one month ahead, toggle the recurring block, set frequency=MONTHLY, dayOfMonth=15, endDate=3 months out, submit. Confirm Budget shows 4 instances (one per month, current + 3).

- [ ] **Step 5: Commit**

```bash
rtk git add frontend/src/components/Fab.tsx
rtk git commit -m "feat(frontend): expose Recurring block in Fab create-event form"
```

---

### Task 5.5: Component `<EditEventScopePicker>`

**Files:**
- Create: `frontend/src/components/EditEventScopePicker.tsx`

- [ ] **Step 1: Component**

```tsx
import type { ScopeEnum } from '../types/api';

interface Props {
    value: ScopeEnum;
    onChange: (next: ScopeEnum) => void;
}

/** Inline scope-picker для recurring-события в форме редактирования. */
export default function EditEventScopePicker({ value, onChange }: Props) {
    return (
        <div className="rounded-md border p-3 space-y-2"
             style={{ background: 'var(--color-surface)', borderColor: 'var(--color-border)' }}>
            <div className="text-xs flex items-center gap-1" style={{ color: 'var(--color-text-muted)' }}>
                <span style={{ color: 'var(--color-primary)' }}>↻</span>
                Это повторяющееся событие. Изменения применить к:
            </div>
            <div className="flex flex-col gap-1.5 text-sm">
                <label className="flex items-center gap-2">
                    <input type="radio" checked={value === 'THIS'} onChange={() => onChange('THIS')} />
                    Только к этому
                </label>
                <label className="flex items-center gap-2">
                    <input type="radio" checked={value === 'FOLLOWING'} onChange={() => onChange('FOLLOWING')} />
                    К этому и следующим
                </label>
                <label className="flex items-center gap-2">
                    <input type="radio" checked={value === 'ALL'} onChange={() => onChange('ALL')} />
                    Ко всем
                </label>
            </div>
        </div>
    );
}
```

- [ ] **Step 2: Type-check + commit**

```bash
rtk git add frontend/src/components/EditEventScopePicker.tsx
rtk git commit -m "feat(frontend): EditEventScopePicker component"
```

---

### Task 5.6: Embed scope picker into `EditEventSheet`

**Files:**
- Modify: `frontend/src/components/EditEventSheet.tsx`

- [ ] **Step 1: Local state**

```tsx
const [scope, setScope] = useState<ScopeEnum>('FOLLOWING');
```

- [ ] **Step 2: Conditional render**

Right above the form action buttons. The picker only renders for PLAN events tied to a rule — FACT records inherit `recurringRuleId` (per backend Task 3.7) but are edited via `patchEventFact`, not `updateEvent`, so showing the scope picker on FACT edit would be misleading:

```tsx
{event.recurringRuleId && event.eventKind === 'PLAN' && (
    <EditEventScopePicker value={scope} onChange={setScope} />
)}
```

- [ ] **Step 3: Pass to `updateEvent`**

```tsx
const effectiveScope = event.recurringRuleId && event.eventKind === 'PLAN' ? scope : 'THIS';
await updateEvent(event.id, dto, effectiveScope);
```

- [ ] **Step 4: Type-check + commit**

```bash
rtk git add frontend/src/components/EditEventSheet.tsx
rtk git commit -m "feat(frontend): inline scope picker for recurring edit"
```

---

### Task 5.7: Component `<DeleteRecurringDialog>` + wire up

**Files:**
- Create: `frontend/src/components/DeleteRecurringDialog.tsx`
- Modify: `frontend/src/components/EditEventSheet.tsx` (the only caller of `deleteEvent` for events that can be recurring)

> **Verified callers of `deleteEvent`** in this codebase: `frontend/src/components/EditEventSheet.tsx` and `frontend/src/components/WishlistItem.tsx`. Wishlist items are LOW-priority, single-occurrence "хотелки" — they are NOT recurring (the wishlist UI does not surface recurring fields, and the spec keeps wishlist outside this feature). So no change is required in `WishlistItem.tsx`. The dialog wire-up applies ONLY to `EditEventSheet.tsx`.

- [ ] **Step 1: Component**

```tsx
import { useState } from 'react';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter } from './ui/dialog';
import { Button } from './ui/button';
import type { ScopeEnum } from '../types/api';

interface Props {
    open: boolean;
    onClose: () => void;
    onConfirm: (scope: ScopeEnum) => void;
}

export default function DeleteRecurringDialog({ open, onClose, onConfirm }: Props) {
    const [scope, setScope] = useState<ScopeEnum>('FOLLOWING');

    return (
        <Dialog open={open} onOpenChange={(o) => !o && onClose()}>
            <DialogContent>
                <DialogHeader>
                    <DialogTitle>Удалить повторяющееся событие?</DialogTitle>
                </DialogHeader>
                <div className="space-y-2 py-2">
                    <label className="flex items-center gap-2 text-sm">
                        <input type="radio" checked={scope === 'THIS'} onChange={() => setScope('THIS')} />
                        Только это
                    </label>
                    <label className="flex items-center gap-2 text-sm">
                        <input type="radio" checked={scope === 'FOLLOWING'} onChange={() => setScope('FOLLOWING')} />
                        Это и все следующие
                    </label>
                    <label className="flex items-center gap-2 text-sm">
                        <input type="radio" checked={scope === 'ALL'} onChange={() => setScope('ALL')} />
                        Все (правило будет удалено)
                    </label>
                    <p className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
                        Исполненные события прошлого не удаляются.
                    </p>
                </div>
                <DialogFooter>
                    <Button variant="ghost" onClick={onClose}>Отмена</Button>
                    <Button onClick={() => onConfirm(scope)} variant="destructive">Удалить</Button>
                </DialogFooter>
            </DialogContent>
        </Dialog>
    );
}
```

- [ ] **Step 2: Wire into `EditEventSheet.tsx`**

Modify `EditEventSheet.tsx`:

1. Add local state: `const [deleteDialogOpen, setDeleteDialogOpen] = useState(false);`
2. Replace the existing direct `await deleteEvent(event.id)` call in the delete-button handler with a branch:
   - If `!event.recurringRuleId` → keep existing behavior: `await deleteEvent(event.id)` (defaults to scope=THIS).
   - Else → `setDeleteDialogOpen(true)` (show modal).
3. Render at the end of the sheet's JSX:

```tsx
<DeleteRecurringDialog
    open={deleteDialogOpen}
    onClose={() => setDeleteDialogOpen(false)}
    onConfirm={async (scope) => {
        await deleteEvent(event.id, scope);
        setDeleteDialogOpen(false);
        onClose();   // close the EditEventSheet itself; matches existing close-on-delete behavior
    }}
/>
```

- [ ] **Step 3: Type-check + commit**

```bash
rtk git add frontend/src/components/DeleteRecurringDialog.tsx frontend/src/components/EditEventSheet.tsx
rtk git commit -m "feat(frontend): scope-aware delete confirmation for recurring"
```

---

### Task 5.8: Recurring icon ↻ in Budget row

**Files:**
- Modify: `frontend/src/pages/Budget.tsx` (rows are rendered inline in this page around lines 260–290; verified — no separate row component exists)

> **Verified location**: `Budget.tsx` renders event rows inline within its JSX (no `EventRow.tsx` or similar child component). The recurring icon must be inserted in the same `.map(event => …)` block where the row markup lives, near the `displayTitle` / `event.priority` references.

> **Note**: `BudgetStructureSection.tsx` exists but renders the wishlist visualization, not the per-day budget rows.

- [ ] **Step 1: Render**

```tsx
import { Repeat } from 'lucide-react';
// ...
{event.recurringRuleId && (
    <span title={recurringTooltip(event)}>
        <Repeat size={12} style={{ color: 'var(--color-text-muted)' }} />
    </span>
)}
```

Helper:

```tsx
function recurringTooltip(e: FinancialEvent): string {
    if (e.recurringFrequency === 'MONTHLY' && e.recurringDayOfMonth != null) {
        return `Повторяется ежемесячно ${e.recurringDayOfMonth}-го числа`;
    }
    if (e.recurringFrequency === 'YEARLY' && e.recurringDayOfMonth != null && e.recurringMonthOfYear != null) {
        const months = ['января','февраля','марта','апреля','мая','июня','июля','августа','сентября','октября','ноября','декабря'];
        return `Повторяется ${e.recurringDayOfMonth} ${months[e.recurringMonthOfYear - 1]} каждого года`;
    }
    return 'Повторяющееся событие';
}
```

- [ ] **Step 2: Type-check + manual smoke (icon shows up only on recurring rows)**

- [ ] **Step 3: Commit**

```bash
rtk git add frontend/src/pages/Budget.tsx
rtk git commit -m "feat(frontend): show ↻ icon on recurring events in Budget"
```

---

## Chunk 6 — Polish + finishing

### Task 6.1: Run full backend test suite once more

- [ ] **Step 1**

Run: `rtk JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test`
Expected: PASS.

- [ ] **Step 2**

If anything fails, debug using @superpowers:systematic-debugging.

---

### Task 6.2: Frontend build + typecheck

- [ ] **Step 1**

Run: `cd frontend && rtk npm run build`
Expected: BUILD SUCCESS.

- [ ] **Step 2**

Run: `cd frontend && rtk npm run lint`
Expected: clean.

---

### Task 6.3: Manual smoke matrix

- [ ] **Создание + ↻ icon**: Создать MONTHLY-правило с end_date 12 мес → в Budget видно 12 строк, у каждой ↻ icon.
- [ ] **Tooltip text**: Hover ↻ icon — tooltip показывает «Повторяется ежемесячно 15-го числа» (или соответствующий день).
- [ ] **Edit FOLLOWING**: Редактировать 6-ю строку из 12 c scope=FOLLOWING, изменить amount → первые 5 со старой суммой, 6-я и далее с новой.
- [ ] **Edit ALL**: Редактировать любую строку c scope=ALL → все 12 со новой суммой; сохранены EXECUTED факты, если были.
- [ ] **Edit THIS**: Редактировать одну строку c scope=THIS → меняется только она, остальные нетронуты.
- [ ] **Delete modal — все три варианта**: Кликнуть ❌ на recurring-строке → появляется `DeleteRecurringDialog`; проверить (а) все три radio-кнопки кликаются, (б) «Отмена» закрывает диалог без действия, (в) подтверждение с scope=ALL — правило исчезло, FACT исторические остались.
- [ ] **FACT inheritance UI**: Создать recurring PLAN → внести FACT (через `FactCreateSheet`) → в Budget у FACT-строки тоже показан ↻ icon (бэк наследует `recurringRuleId` через `createLinkedFact`).
- [ ] **Бессрочное правило**: Создать MONTHLY без end_date → планировщик `/api/v1/funds/planner` показывает события на 36 мес вперёд.
- [ ] **Past start_date validation**: Попытка задать start_date в прошлом → форма / бэк отвергает с понятным сообщением (HTTP 400 от валидации `validateConfig`, I3).

---

### Task 6.4: Update MEMORY.md

**Files:**
- Modify: `C:\Users\Kirill\.claude\projects\C--Users-Kirill-IdeaProjects-selfin\memory\MEMORY.md`

- [ ] **Step 1: Update the `## Roadmap` block**

In MEMORY.md, find the `## Roadmap` section. Remove the line "PR 1: Recurring events ...". Add a new entry under `## What's done (blockers fixed)` (renumbering as needed):

> ```
> Recurring events: RecurringRule entity + Flyway V16 (table + partial unique index uq_events_rule_date_active);
> 12-month bounded generation with day-of-month clamp; lazy-extend for indefinite rules in FundPlannerService;
> scope=THIS|FOLLOWING|ALL semantics on PUT/DELETE /events/{id}; FACT inherits recurring_rule_id from parent PLAN;
> ↻ icon in Budget rows with tooltip; DeleteRecurringDialog for scope-aware deletion.
> ```

- [ ] **Step 2: Update the `## What's NOT yet implemented` block**

Same file: change "PR 1-3 above" to "PR 2-3 above" (PR 1 is now done).

- [ ] **Step 3: No commit needed**

MEMORY.md is in `~/.claude/projects/...` (auto-memory), NOT in the project git repo. Save the file; nothing to commit.

---

### Task 6.5: Final commit + branch hygiene

- [ ] **Step 1: Verify clean working tree**

Run: `rtk git status`
Expected: nothing to commit.

- [ ] **Step 2: Review commit log for narrative readability**

Run: `rtk git log --oneline -30`

Verify: commits should narrate the build chronologically (V16 migration → entities → repos → generator → service → controller → IT → frontend → polish). If the log is noisy or out of order, no automated rebase action is taken in this plan — flag the noise to the user. Interactive rebase (`git rebase -i`) is forbidden in this environment; if cleanup is required the user will do it manually.

- [ ] **Step 3: If working in a worktree, hand off via @superpowers:finishing-a-development-branch.**

---

## Out of scope (per spec §7, do NOT touch in this PR)

- Отдельный экран «Регулярные платежи» (CRUD списка правил) — добавим, когда правил станет 15+.
- FUND_TRANSFER recurring — отдельная задача.
- Метод прогноза для обязательных платежей (trimmed mean, P50/P75, линия прогноза) — следующая brainstorming-сессия после этого PR.
- Редизайн стратегического графика (fan chart, ghost trajectory, start-from-first-tracking-month) — ждёт capital/net-worth feature.
- Retroactive-создание правил (start_date в прошлом) — отвергаем 400.
- WEEKLY/BIWEEKLY частоты.
- Scheduled job для lazy-extend (cron) — сейчас on-demand при каждом `getPlanner()`. Cron станет нужен, если планировщик начнут вызывать редко.
- Per-instance preservation при scope=FOLLOWING (см. Future work #8 в spec §7).
