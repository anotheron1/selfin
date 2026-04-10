# Technical Debt & UX Improvements (9.1–9.5 + Analytics) Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix five zones of technical debt and UX problems: priority labels, category isSystem flag, checkpoint validation, wishlist filter, Budget plan-fact hover, and replace the Analytics burn-rate block with a budget structure summary.

**Architecture:** Backend changes first (Tasks 1–7), then frontend changes (Tasks 8–13). Tasks 1–4 are independent and can run in parallel. Task 8 (WishlistSection refactor) depends on Task 2 (SystemCategory constant). All tests use JUnit 5 + Mockito; frontend is React 18 + TypeScript + Tailwind.

**Tech Stack:** Spring Boot 4, Java 21, JPA/Hibernate, Flyway, React 18, TypeScript, Tailwind CSS, Shadcn UI

**Test command (backend):** `JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test`
**Dev server (frontend):** `cd frontend && npm run dev`

---

## Chunk 1: Backend foundations — SystemCategory, isSystem, CategoryDto, nextPriority dedup, checkpoint validation

### Task 1: SystemCategory constant + Flyway V14

**Files:**
- Create: `backend/src/main/java/ru/selfin/backend/model/enums/SystemCategory.java`
- Create: `backend/src/main/resources/db/migration/V14__add_is_system_to_categories.sql`

- [ ] **Step 1: Create SystemCategory constant class**

```java
// backend/src/main/java/ru/selfin/backend/model/enums/SystemCategory.java
package ru.selfin.backend.model.enums;

/** Names of system-managed categories that cannot be renamed or deleted. */
public final class SystemCategory {
    private SystemCategory() {}

    public static final String WISHLIST_NAME = "Хотелки";
}
```

- [ ] **Step 2: Create Flyway migration V14**

```sql
-- backend/src/main/resources/db/migration/V14__add_is_system_to_categories.sql
ALTER TABLE categories ADD COLUMN is_system BOOLEAN NOT NULL DEFAULT false;

-- Mark the existing "Хотелки" row as system if it exists
UPDATE categories SET is_system = true WHERE name = 'Хотелки';
```

- [ ] **Step 3: Run backend tests to confirm migration applies cleanly**

```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -pl backend
```

Expected: BUILD SUCCESS (Flyway applies V14 in test context without errors)

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/ru/selfin/backend/model/enums/SystemCategory.java \
        backend/src/main/resources/db/migration/V14__add_is_system_to_categories.sql
git commit -m "feat(categories): add SystemCategory constant and is_system DB column (V14)"
```

---

### Task 2: Category model + CategoryDto + CategoryService (isSystem)

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/model/Category.java`
- Modify: `backend/src/main/java/ru/selfin/backend/dto/CategoryDto.java`
- Modify: `backend/src/main/java/ru/selfin/backend/service/CategoryService.java`
- Modify: `backend/src/main/java/ru/selfin/backend/repository/FinancialEventRepository.java`

- [ ] **Step 1: Write failing tests for isSystem and type-change protection**

Create or fully replace `backend/src/test/java/ru/selfin/backend/service/CategoryServiceTest.java` with this version. The class uses `@InjectMocks` throughout — this replaces any existing direct `new CategoryService(repo)` call that would fail to compile once `FinancialEventRepository` is added as a second dependency in Step 6:

```java
// CategoryServiceTest.java
package ru.selfin.backend.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;
import ru.selfin.backend.dto.CategoryCreateDto;
import ru.selfin.backend.model.Category;
import ru.selfin.backend.model.enums.CategoryType;
import ru.selfin.backend.model.enums.Priority;
import ru.selfin.backend.repository.CategoryRepository;
import ru.selfin.backend.repository.FinancialEventRepository;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    @Mock CategoryRepository categoryRepository;
    @Mock FinancialEventRepository eventRepository;
    @InjectMocks CategoryService categoryService;

    @Test
    void update_systemCategory_throwsWhenRenameAttempted() {
        UUID id = UUID.randomUUID();
        Category system = Category.builder()
                .id(id).name("Хотелки").type(CategoryType.EXPENSE)
                .priority(Priority.LOW).isSystem(true).build();
        when(categoryRepository.findById(id)).thenReturn(Optional.of(system));

        CategoryCreateDto dto = new CategoryCreateDto("Новое имя", CategoryType.EXPENSE, Priority.LOW);

        assertThatThrownBy(() -> categoryService.update(id, dto))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("System categories cannot be renamed");
    }

    @Test
    void update_typeChange_withActiveEvents_throws() {
        UUID id = UUID.randomUUID();
        Category cat = Category.builder()
                .id(id).name("Еда").type(CategoryType.EXPENSE)
                .priority(Priority.HIGH).isSystem(false).build();
        when(categoryRepository.findById(id)).thenReturn(Optional.of(cat));
        when(eventRepository.existsByCategoryIdAndDeletedFalse(id)).thenReturn(true);

        CategoryCreateDto dto = new CategoryCreateDto("Еда", CategoryType.INCOME, Priority.HIGH);

        assertThatThrownBy(() -> categoryService.update(id, dto))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Cannot change type");
    }

    @Test
    void update_typeChange_noActiveEvents_succeeds() {
        UUID id = UUID.randomUUID();
        Category cat = Category.builder()
                .id(id).name("Еда").type(CategoryType.EXPENSE)
                .priority(Priority.HIGH).isSystem(false).build();
        when(categoryRepository.findById(id)).thenReturn(Optional.of(cat));
        when(eventRepository.existsByCategoryIdAndDeletedFalse(id)).thenReturn(false);
        when(categoryRepository.save(cat)).thenReturn(cat);

        // No exception expected
        categoryService.update(id, new CategoryCreateDto("Еда", CategoryType.INCOME, Priority.HIGH));
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -pl backend -Dtest=CategoryServiceTest
```

Expected: FAIL — `isSystem` field not found, `existsByCategoryIdAndDeletedFalse` not found

- [ ] **Step 3: Add `isSystem` field to Category model**

```java
// Category.java — add after the `deleted` field:
@Column(name = "is_system", nullable = false)
@Builder.Default
private boolean isSystem = false;
```

- [ ] **Step 4: Add `isSystem` to CategoryDto**

```java
// CategoryDto.java
public record CategoryDto(
        UUID id,
        String name,
        CategoryType type,
        Priority priority,
        boolean isSystem) {
}
```

- [ ] **Step 5: Add `existsByCategoryIdAndDeletedFalse` to FinancialEventRepository**

```java
// FinancialEventRepository.java — add after existing methods:
boolean existsByCategoryIdAndDeletedFalse(UUID categoryId);
```

- [ ] **Step 6: Update CategoryService**

Add `FinancialEventRepository` dependency and update `update()` and `toDto()`:

```java
// CategoryService.java — add field:
private final FinancialEventRepository eventRepository;

// update() method — replace body with:
@Transactional
public CategoryDto update(UUID id, CategoryCreateDto dto) {
    Category category = categoryRepository.findById(id)
            .filter(c -> !c.isDeleted())
            .orElseThrow(() -> new ResourceNotFoundException("Category", id));

    if (category.isSystem() && !category.getName().equals(dto.name())) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "System categories cannot be renamed");
    }
    if (!category.getType().equals(dto.type())
            && eventRepository.existsByCategoryIdAndDeletedFalse(id)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Cannot change type: category has active events");
    }

    category.setName(dto.name());
    category.setType(dto.type());
    category.setPriority(dto.priority() != null ? dto.priority() : Priority.MEDIUM);
    return toDto(categoryRepository.save(category));
}

// toDto() method — update to include isSystem:
public CategoryDto toDto(Category c) {
    return new CategoryDto(c.getId(), c.getName(), c.getType(), c.getPriority(), c.isSystem());
}
```

Also update `create()` to set `isSystem = true` when name matches `SystemCategory.WISHLIST_NAME`:

```java
@Transactional
public CategoryDto create(CategoryCreateDto dto) {
    boolean isSystem = SystemCategory.WISHLIST_NAME.equals(dto.name());
    Category category = Category.builder()
            .name(dto.name())
            .type(dto.type())
            .priority(dto.priority() != null ? dto.priority() : Priority.MEDIUM)
            .isSystem(isSystem)
            .build();
    return toDto(categoryRepository.save(category));
}
```

Add `import org.springframework.http.HttpStatus;` and `import org.springframework.web.server.ResponseStatusException;` if not present.

- [ ] **Step 7: Run tests to confirm they pass**

```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -pl backend -Dtest=CategoryServiceTest
```

Expected: BUILD SUCCESS, 3 tests pass

- [ ] **Step 8: Run full test suite**

```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -pl backend
```

Expected: BUILD SUCCESS

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/ru/selfin/backend/model/Category.java \
        backend/src/main/java/ru/selfin/backend/dto/CategoryDto.java \
        backend/src/main/java/ru/selfin/backend/service/CategoryService.java \
        backend/src/main/java/ru/selfin/backend/repository/FinancialEventRepository.java \
        backend/src/test/java/ru/selfin/backend/service/CategoryServiceTest.java
git commit -m "feat(categories): add isSystem flag, rename/type-change protection (9.2)"
```

---

### Task 3: FinancialEventService — remove duplicate nextPriority, use SystemCategory constant

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/service/FinancialEventService.java`

- [ ] **Step 1: Remove duplicate `nextPriority()` from FinancialEventService**

Find the private method `nextPriority(Priority current)` in `FinancialEventService.java` (around line 300–307) and delete it entirely.

- [ ] **Step 1.5: Add `CategoryService` dependency to `FinancialEventService`**

`FinancialEventService` does not currently depend on `CategoryService`. Add it as a constructor-injected field so the delegate call in Step 2 compiles:

```java
// FinancialEventService.java — add field alongside the existing repository fields:
private final CategoryService categoryService;
```

`@RequiredArgsConstructor` wires it automatically. Dependency direction is safe: `FinancialEventService → CategoryService → FinancialEventRepository` (no cycle).

- [ ] **Step 2: Update `cyclePriority()` in FinancialEventService to delegate to CategoryService**

Find the `cyclePriority(UUID id)` method in `FinancialEventService` and update the internal `nextPriority` call to use `CategoryService`:

```java
// FinancialEventService.java — in cyclePriority():
// Before: event.setPriority(nextPriority(event.getPriority()));
// After: event.setPriority(categoryService.nextPriority(event.getPriority()));
```

Make `nextPriority()` in `CategoryService` package-private (remove `private`, keep no modifier or use `package`) so it's accessible:

```java
// CategoryService.java — change:
private Priority nextPriority(Priority current) {
// To:
Priority nextPriority(Priority current) {
```

- [ ] **Step 3: Replace hardcoded "Хотелки" string with SystemCategory.WISHLIST_NAME**

In `FinancialEventService`, find the `createWishlistItem()` method. Replace the string literal `"Хотелки"` with `SystemCategory.WISHLIST_NAME`. Add import if needed:

```java
import ru.selfin.backend.model.enums.SystemCategory;
```

- [ ] **Step 4: Run tests**

```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -pl backend
```

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/ru/selfin/backend/service/FinancialEventService.java \
        backend/src/main/java/ru/selfin/backend/service/CategoryService.java
git commit -m "refactor(events): remove duplicate nextPriority, use SystemCategory constant (9.1/9.2)"
```

---

### Task 4: Checkpoint date validation — @PastOrPresent

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/dto/BalanceCheckpointCreateDto.java`
- Modify: `backend/src/main/java/ru/selfin/backend/controller/BalanceCheckpointController.java` (verify `@Valid`)

- [ ] **Step 1: Write a failing validation test**

Create `backend/src/test/java/ru/selfin/backend/dto/BalanceCheckpointCreateDtoTest.java`:

```java
package ru.selfin.backend.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BalanceCheckpointCreateDtoTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void futureDate_failsValidation() {
        var dto = new BalanceCheckpointCreateDto(
                LocalDate.now().plusDays(1),
                BigDecimal.valueOf(10000));

        Set<ConstraintViolation<BalanceCheckpointCreateDto>> violations = validator.validate(dto);

        assertThat(violations).isNotEmpty();
        assertThat(violations.stream().map(v -> v.getPropertyPath().toString()))
                .contains("date");
    }

    @Test
    void todayDate_passesValidation() {
        var dto = new BalanceCheckpointCreateDto(
                LocalDate.now(),
                BigDecimal.valueOf(10000));

        assertThat(validator.validate(dto)).isEmpty();
    }

    @Test
    void pastDate_passesValidation() {
        var dto = new BalanceCheckpointCreateDto(
                LocalDate.now().minusDays(1),
                BigDecimal.valueOf(10000));

        assertThat(validator.validate(dto)).isEmpty();
    }
}
```

- [ ] **Step 2: Run to confirm test fails**

```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -pl backend -Dtest=BalanceCheckpointCreateDtoTest
```

Expected: FAIL — `futureDate_failsValidation` passes (no violation yet)

- [ ] **Step 3: Add `@PastOrPresent` to BalanceCheckpointCreateDto**

```java
// BalanceCheckpointCreateDto.java
// @NotNull was already present on date; add @PastOrPresent alongside it:
import jakarta.validation.constraints.PastOrPresent;

public record BalanceCheckpointCreateDto(
        @NotNull @PastOrPresent LocalDate date,
        @NotNull @PositiveOrZero BigDecimal amount
) {}
```

- [ ] **Step 4: Verify `@Valid` on BalanceCheckpointController**

Open `BalanceCheckpointController.java`. Check that the POST and PUT endpoints have `@Valid` on `@RequestBody`. If missing, add it.

- [ ] **Step 5: Run tests**

```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -pl backend -Dtest=BalanceCheckpointCreateDtoTest
```

Expected: 3 tests pass

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/ru/selfin/backend/dto/BalanceCheckpointCreateDto.java \
        backend/src/main/java/ru/selfin/backend/controller/BalanceCheckpointController.java \
        backend/src/test/java/ru/selfin/backend/dto/BalanceCheckpointCreateDtoTest.java
git commit -m "feat(checkpoint): add @PastOrPresent validation on date (9.3)"
```

---

## Chunk 2: Backend — Clock injection, wishlist filter fix, bridge-events tests, priority query param

### Task 5: Clock injection + wishlist filter fix (9.4)

**Files:**
- Create: `backend/src/main/java/ru/selfin/backend/config/AppConfig.java`
- Modify: `backend/src/main/java/ru/selfin/backend/service/FinancialEventService.java`
- Modify: `backend/src/main/java/ru/selfin/backend/repository/FinancialEventRepository.java`

- [ ] **Step 1: Write failing test for wishlist filter**

Add to `FinancialEventServiceTest.java` (create if not exists):

```java
package ru.selfin.backend.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// NOTE: check FinancialEventService constructor signature before writing this test.
// Pass mocks in the same order as the actual constructor parameters, with Clock last.
@ExtendWith(MockitoExtension.class)
class FinancialEventServiceWishlistTest {

    @Mock FinancialEventRepository eventRepository;
    @Mock CategoryRepository categoryRepository;
    @Mock TargetFundRepository targetFundRepository;

    // Clock fixed to 2026-04-09
    Clock clock = Clock.fixed(Instant.parse("2026-04-09T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void findWishlist_usesFirstDayOfMonth_notToday() {
        // Inject clock via constructor (after implementation step)
        FinancialEventService service = new FinancialEventService(
                eventRepository, categoryRepository, targetFundRepository, clock);

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
```

- [ ] **Step 2: Run to confirm test fails**

```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -pl backend -Dtest=FinancialEventServiceWishlistTest
```

Expected: FAIL — constructor doesn't accept Clock yet

- [ ] **Step 3: Create AppConfig.java with Clock bean**

```java
// backend/src/main/java/ru/selfin/backend/config/AppConfig.java
package ru.selfin.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class AppConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
```

- [ ] **Step 4: Add Clock dependency to FinancialEventService**

In `FinancialEventService.java`:
1. Add `private final Clock clock;` field (via `@RequiredArgsConstructor` — add it to the field list)
2. In `findWishlist()`, replace `LocalDate.now()` with `LocalDate.now(clock).withDayOfMonth(1)`

```java
// FinancialEventService.java — add field after targetFundRepository:
private final Clock clock;

// findWishlist() — change:
// Before: eventRepository.findWishlistItems(Priority.LOW, EventStatus.PLANNED, LocalDate.now())
// After:
public List<FinancialEventDto> findWishlist() {
    LocalDate cutoff = LocalDate.now(clock).withDayOfMonth(1);
    return eventRepository.findWishlistItems(Priority.LOW, EventStatus.PLANNED, cutoff)
            .stream().map(e -> toDto(e, null, null)).toList();
}
```

- [ ] **Step 5: Run tests**

```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -pl backend -Dtest=FinancialEventServiceWishlistTest
```

Expected: PASS

- [ ] **Step 6: Run full suite**

```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -pl backend
```

Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/ru/selfin/backend/config/AppConfig.java \
        backend/src/main/java/ru/selfin/backend/service/FinancialEventService.java \
        backend/src/test/java/ru/selfin/backend/service/FinancialEventServiceWishlistTest.java
git commit -m "fix(wishlist): use first day of month as cutoff, inject Clock bean (9.4)"
```

---

### Task 6: Bridge-events tests (9.3)

**Files:**
- Modify: `backend/src/test/java/ru/selfin/backend/service/AnalyticsServiceTest.java`

- [ ] **Step 1: Read existing AnalyticsServiceTest to understand test setup**

Open `backend/src/test/java/ru/selfin/backend/service/AnalyticsServiceTest.java` and note the existing mocks and helper methods.

- [ ] **Step 2: Add bridge-events test cases**

Add the following test methods to `AnalyticsServiceTest`:

```java
// Tests for calcStartBalance() via getReport(asOfDate).cashFlow[0].runningBalance
// NOTE: use `service` not `analyticsService` — the existing field in this test class is named `service`.
// Add this import if missing: import ru.selfin.backend.model.BalanceCheckpoint;

@Test
void getReport_noCheckpoint_startBalanceIsZero() {
    when(checkpointRepository.findTopByOrderByDateDesc()).thenReturn(Optional.empty());
    when(eventRepository.findAllByDeletedFalseAndDateBetween(any(), any())).thenReturn(List.of());

    AnalyticsReportDto report = service.getReport(LocalDate.of(2026, 4, 9));

    // No checkpoint → startBalance = 0, first cash flow day has runningBalance = 0
    assertThat(report.cashFlow()).isNotNull();
    assertThat(report.cashFlow().get(0).runningBalance()).isEqualByComparingTo(BigDecimal.ZERO);
}

@Test
void getReport_checkpointInCurrentMonth_nobridge() {
    LocalDate checkpointDate = LocalDate.of(2026, 4, 5);
    BalanceCheckpoint cp = new BalanceCheckpoint();
    cp.setDate(checkpointDate);
    cp.setAmount(BigDecimal.valueOf(50000));
    when(checkpointRepository.findTopByOrderByDateDesc()).thenReturn(Optional.of(cp));
    // Stub the main-month query explicitly so the verify below is unambiguous
    when(eventRepository.findAllByDeletedFalseAndDateBetween(
            eq(LocalDate.of(2026, 4, 1)), eq(LocalDate.of(2026, 4, 30)))).thenReturn(List.of());

    AnalyticsReportDto report = service.getReport(LocalDate.of(2026, 4, 9));

    // Checkpoint in same month → startBalance = checkpoint amount, no bridge call
    assertThat(report.cashFlow()).isNotNull();
    assertThat(report.cashFlow().get(0).runningBalance()).isEqualByComparingTo(BigDecimal.valueOf(50000));
    // Verify bridge NOT called for previous month
    verify(eventRepository, never()).findAllByDeletedFalseAndDateBetween(
            eq(checkpointDate), eq(LocalDate.of(2026, 3, 31)));
}

@Test
void getReport_checkpointInPreviousMonth_bridgeEventsApplied() {
    LocalDate checkpointDate = LocalDate.of(2026, 3, 20);
    BalanceCheckpoint cp = new BalanceCheckpoint();
    cp.setDate(checkpointDate);
    cp.setAmount(BigDecimal.valueOf(30000));
    when(checkpointRepository.findTopByOrderByDateDesc()).thenReturn(Optional.of(cp));
    when(eventRepository.findAllByDeletedFalseAndDateBetween(any(), any())).thenReturn(List.of());

    service.getReport(LocalDate.of(2026, 4, 9));

    // Bridge called for [checkpointDate, March 31]
    verify(eventRepository).findAllByDeletedFalseAndDateBetween(
            eq(checkpointDate), eq(LocalDate.of(2026, 3, 31)));
}

@Test
void getReport_checkpointTwoMonthsAgo_bridgeEventsApplied() {
    LocalDate checkpointDate = LocalDate.of(2026, 2, 15);
    BalanceCheckpoint cp = new BalanceCheckpoint();
    cp.setDate(checkpointDate);
    cp.setAmount(BigDecimal.valueOf(20000));
    when(checkpointRepository.findTopByOrderByDateDesc()).thenReturn(Optional.of(cp));
    when(eventRepository.findAllByDeletedFalseAndDateBetween(any(), any())).thenReturn(List.of());

    service.getReport(LocalDate.of(2026, 4, 9));

    // Bridge called for [checkpointDate, March 31] (day before April 1)
    verify(eventRepository).findAllByDeletedFalseAndDateBetween(
            eq(checkpointDate), eq(LocalDate.of(2026, 3, 31)));
}
```

Add any missing imports: `org.mockito.Mockito.never`, `org.mockito.Mockito.eq`.

- [ ] **Step 3: Run the tests**

```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -pl backend -Dtest=AnalyticsServiceTest
```

Expected: BUILD SUCCESS, all new tests pass (the logic already exists in `calcStartBalance()`)

- [ ] **Step 4: Commit**

```bash
git add backend/src/test/java/ru/selfin/backend/service/AnalyticsServiceTest.java
git commit -m "test(analytics): add bridge-events test coverage for calcStartBalance (9.3)"
```

---

### Task 7: GET /events?priority query param (9.4/Analytics)

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/controller/FinancialEventController.java`
- Modify: `backend/src/main/java/ru/selfin/backend/service/FinancialEventService.java`
- Modify: `backend/src/main/java/ru/selfin/backend/repository/FinancialEventRepository.java`

- [ ] **Step 0: Write a failing service-level test for the priority route**

Create `backend/src/test/java/ru/selfin/backend/service/FinancialEventServicePriorityTest.java`:

```java
package ru.selfin.backend.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.selfin.backend.model.enums.Priority;
import ru.selfin.backend.repository.CategoryRepository;
import ru.selfin.backend.repository.FinancialEventRepository;
import ru.selfin.backend.repository.TargetFundRepository;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FinancialEventServicePriorityTest {

    @Mock FinancialEventRepository eventRepository;
    @Mock CategoryRepository categoryRepository;
    @Mock TargetFundRepository targetFundRepository;
    @InjectMocks FinancialEventService service;

    @Test
    void findByPriority_delegatesToRepository() {
        when(eventRepository.findAllByDeletedFalseAndPriorityOrderByCreatedAtAsc(Priority.LOW))
                .thenReturn(List.of());

        service.findByPriority(Priority.LOW);

        verify(eventRepository).findAllByDeletedFalseAndPriorityOrderByCreatedAtAsc(Priority.LOW);
    }
}
```

Run:
```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -pl backend -Dtest=FinancialEventServicePriorityTest
```
Expected: FAIL — `findByPriority` method not found.

- [ ] **Step 1: Add repository method for priority filter**

```java
// FinancialEventRepository.java — add:
/** All non-deleted events with given priority, ordered by createdAt. */
List<FinancialEvent> findAllByDeletedFalseAndPriorityOrderByCreatedAtAsc(Priority priority);
```

- [ ] **Step 2: Add service method**

```java
// FinancialEventService.java — add:
public List<FinancialEventDto> findByPriority(Priority priority) {
    return eventRepository.findAllByDeletedFalseAndPriorityOrderByCreatedAtAsc(priority)
            .stream().map(e -> toDto(e, null, null)).toList();
}
```

- [ ] **Step 3: Update controller to accept optional `priority` param**

```java
// FinancialEventController.java — update getByPeriod:
@GetMapping
public List<FinancialEventDto> getByPeriod(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
        @RequestParam(required = false) ru.selfin.backend.model.enums.Priority priority) {
    if (priority != null && startDate == null && endDate == null) {
        return eventService.findByPriority(priority);
    }
    if (startDate == null || endDate == null) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "startDate and endDate are required when priority is not specified");
    }
    return eventService.findByPeriod(startDate, endDate);
}
```

Add imports: `org.springframework.http.HttpStatus`, `org.springframework.web.server.ResponseStatusException`.

- [ ] **Step 4: Run tests**

```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -pl backend
```

Expected: BUILD SUCCESS

- [ ] **Step 5: Run priority test to confirm it passes**

```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -pl backend -Dtest=FinancialEventServicePriorityTest
```
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/ru/selfin/backend/controller/FinancialEventController.java \
        backend/src/main/java/ru/selfin/backend/service/FinancialEventService.java \
        backend/src/main/java/ru/selfin/backend/repository/FinancialEventRepository.java \
        backend/src/test/java/ru/selfin/backend/service/FinancialEventServicePriorityTest.java
git commit -m "feat(events): add optional priority query param to GET /events (Analytics)"
```

---

## Chunk 3: Frontend — PriorityButton, Settings sort + isSystem UI, WishlistSection refactor, Budget hover

### Task 8: PriorityButton — fix MEDIUM label (9.1)

**Files:**
- Modify: `frontend/src/components/PriorityButton.tsx`

- [ ] **Step 1: Replace the MEDIUM dot with a readable label**

```typescript
// PriorityButton.tsx — replace the MEDIUM (last) return:

// Before:
return (
    <button
        onClick={handleClick}
        title="Необязательный — нажмите для смены приоритета"
        disabled={disabled}
        className="shrink-0 w-4 h-4 flex items-center justify-center rounded-full opacity-30 hover:opacity-60 transition-opacity"
        style={{ color: 'var(--color-text-muted)' }}
    >
        ·
    </button>
);

// After:
return (
    <button
        onClick={handleClick}
        title="Плановый — нажмите для смены приоритета"
        disabled={disabled}
        className="shrink-0 text-xs px-1.5 py-0 rounded border leading-5 font-normal opacity-50 hover:opacity-80 transition-opacity"
        style={{ color: 'var(--color-text-muted)', borderColor: 'var(--color-border)' }}
    >
        план
    </button>
);
```

- [ ] **Step 2: Verify in browser**

```bash
cd frontend && npm run dev
```

Open Budget page, find an event with MEDIUM priority — confirm the badge now shows `"план"` instead of `·`.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/components/PriorityButton.tsx
git commit -m "fix(ui): replace invisible MEDIUM dot with readable 'план' label (9.1)"
```

---

### Task 9: Settings — category sort by priority + isSystem UI (9.1/9.2)

**Files:**
- Modify: `frontend/src/pages/Settings.tsx`
- Modify: `frontend/src/types/api.ts`

- [ ] **Step 1: Add `isSystem` to Category type**

```typescript
// frontend/src/types/api.ts — update Category interface:
export interface Category {
    id: string;
    name: string;
    type: 'INCOME' | 'EXPENSE';
    priority: Priority;
    isSystem: boolean;
}
```

- [ ] **Step 2: Remove frontend `localeCompare` sort in Settings.tsx**

Find the sort call in `Settings.tsx` (around line 141–142) that uses `.sort((a, b) => a.name.localeCompare(b.name, 'ru'))` and remove it. The backend now returns categories already sorted.

- [ ] **Step 3: Add priority-based sort to CategoryService.findAll() on backend**

In `CategoryService.java`, update `findAll()` to sort by type, then priority, then name:

```java
public List<CategoryDto> findAll() {
    Collator collator = Collator.getInstance(new Locale("ru", "RU"));
    List<Integer> priorityOrder = List.of(
            Priority.HIGH.ordinal(), Priority.MEDIUM.ordinal(), Priority.LOW.ordinal());
    return categoryRepository.findAllByDeletedFalse().stream()
            .sorted(Comparator
                    .comparing((Category c) -> c.getType().name())
                    .thenComparingInt(c -> priorityOrder.indexOf(c.getPriority().ordinal()))
                    .thenComparing(Category::getName, collator::compare))
            .map(this::toDto)
            .toList();
}
```

- [ ] **Step 4a: Read the category edit section in Settings.tsx**

Open `frontend/src/pages/Settings.tsx`. Find the section where individual category rows are rendered — look for the delete button and the name input field. Note the exact line numbers and the component/state variable names used (e.g. `editingId`, `editName`, etc.).

- [ ] **Step 4b: Apply isSystem guards to the category row**

In the category list render, wrap the delete button and name input with `isSystem` conditionals. Use the actual variable names from Step 4a:

```typescript
// Delete button — hide for system categories:
{!category.isSystem && (
    <button onClick={() => handleDeleteCategory(category.id)}>×</button>
)}

// Name field — render read-only for system categories:
{category.isSystem ? (
    <span className="text-sm" style={{ color: 'var(--color-text-muted)' }}>
        {category.name}
        <span className="text-xs ml-1" style={{ color: 'var(--color-text-muted)' }}>· системная</span>
    </span>
) : (
    // Keep the existing <input> exactly as-is, just wrapped in the else branch:
    <input value={editName} onChange={e => setEditName(e.target.value)} /* preserve all existing props */ />
)}
```

Preserve all existing props on the `<input>` — only wrap it in the conditional.

- [ ] **Step 5: Run backend tests**

```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -pl backend
```

Expected: BUILD SUCCESS

- [ ] **Step 6: Verify in browser**

Start dev server, open Settings → Categories. Verify:
- HIGH categories appear before MEDIUM, MEDIUM before LOW within each type
- "Хотелки" has `· системная` suffix, delete button hidden, name not editable

- [ ] **Step 7: Commit**

```bash
git add frontend/src/types/api.ts \
        frontend/src/pages/Settings.tsx \
        backend/src/main/java/ru/selfin/backend/service/CategoryService.java
git commit -m "feat(categories): sort by priority, show isSystem indicator, protect system category (9.1/9.2)"
```

---

### Task 10: WishlistSection refactor — split into WishlistItem + WishlistForm (9.4)

**Files:**
- Create: `frontend/src/components/WishlistItem.tsx`
- Create: `frontend/src/components/WishlistForm.tsx`
- Modify: `frontend/src/components/WishlistSection.tsx`

- [ ] **Step 1: Create WishlistForm.tsx**

```typescript
// frontend/src/components/WishlistForm.tsx
import { useState } from 'react';
import type { WishlistCreateDto } from '../types/api';

interface WishlistFormProps {
    initialValues?: Partial<WishlistCreateDto & { date?: string }>;
    onSubmit: (dto: WishlistCreateDto) => Promise<void>;
    onCancel: () => void;
}

export default function WishlistForm({ initialValues = {}, onSubmit, onCancel }: WishlistFormProps) {
    const [description, setDescription] = useState(initialValues.description ?? '');
    const [plannedAmount, setPlannedAmount] = useState(
        initialValues.plannedAmount != null ? String(initialValues.plannedAmount) : '');
    const [url, setUrl] = useState(initialValues.url ?? '');
    const [submitting, setSubmitting] = useState(false);
    const [error, setError] = useState<string | null>(null);

    async function handleSubmit(e: React.FormEvent) {
        e.preventDefault();
        if (!description.trim()) return;
        setSubmitting(true);
        setError(null);
        try {
            await onSubmit({
                description: description.trim(),
                plannedAmount: plannedAmount ? Number(plannedAmount) : null,
                url: url.trim() || null,
            });
        } catch {
            setError('Не удалось сохранить. Попробуйте ещё раз.');
        } finally {
            setSubmitting(false);
        }
    }

    const inputStyle = {
        background: 'var(--color-surface)',
        border: '1px solid var(--color-border)',
        color: 'var(--color-text)',
    };

    return (
        <form
            onSubmit={handleSubmit}
            className="flex flex-col gap-1.5 p-2 rounded"
            style={{ background: 'var(--color-bg)', border: '1px solid var(--color-border)' }}
        >
            <input type="text" required placeholder="Название *" value={description}
                onChange={e => setDescription(e.target.value)}
                className="rounded px-2 py-1 text-sm w-full outline-none" style={inputStyle} />
            <input type="number" min="0" step="0.01" placeholder="Цена (необязательно)"
                value={plannedAmount} onChange={e => setPlannedAmount(e.target.value)}
                className="rounded px-2 py-1 text-sm w-full outline-none" style={inputStyle} />
            <input type="url" placeholder="Ссылка (необязательно)" value={url}
                onChange={e => setUrl(e.target.value)}
                className="rounded px-2 py-1 text-sm w-full outline-none" style={inputStyle} />
            {error && <p className="text-xs" style={{ color: '#ef4444' }}>{error}</p>}
            <div className="flex gap-2 justify-end">
                <button type="button" onClick={onCancel}
                    className="text-xs px-2 py-1 rounded"
                    style={{ color: 'var(--color-text-muted)' }}>Отмена</button>
                <button type="submit" disabled={submitting}
                    className="text-xs px-3 py-1 rounded"
                    style={{ background: 'var(--color-primary)', color: '#fff' }}>
                    {submitting ? '...' : 'Сохранить'}
                </button>
            </div>
        </form>
    );
}
```

- [ ] **Step 2: Create WishlistItem.tsx**

```typescript
// frontend/src/components/WishlistItem.tsx
import { useState } from 'react';
import { ExternalLink, Pencil } from 'lucide-react';
import type { FinancialEvent, WishlistCreateDto } from '../types/api';
import { Button } from './ui/button';
import { Input } from './ui/input';
import { updateEvent, deleteEvent } from '../api';
import WishlistForm from './WishlistForm';

interface WishlistItemProps {
    item: FinancialEvent;
    onReload: () => void;
}

const fmt = (n: number | null) =>
    n != null
        ? new Intl.NumberFormat('ru-RU', { minimumFractionDigits: 0 }).format(n) + ' ₽'
        : '—';

function formatPeriod(dateStr: string): string {
    const d = new Date(dateStr + 'T00:00:00');
    return d.toLocaleDateString('ru-RU', { month: 'long', year: 'numeric' });
}

export default function WishlistItem({ item, onReload }: WishlistItemProps) {
    const [rescheduling, setRescheduling] = useState(false);
    const [newDate, setNewDate] = useState('');
    const [editing, setEditing] = useState(false);

    async function handleDelete() {
        await deleteEvent(item.id);
        onReload();
    }

    async function handleReschedule() {
        if (!newDate) return;
        await updateEvent(item.id, {
            date: newDate,
            categoryId: item.categoryId,
            type: item.type,
            plannedAmount: item.plannedAmount ?? undefined,
            priority: item.priority,
            description: item.description ?? undefined,
        });
        setRescheduling(false);
        setNewDate('');
        onReload();
    }

    async function handleEditSubmit(dto: WishlistCreateDto) {
        const today = new Date().toISOString().slice(0, 10);
        await updateEvent(item.id, {
            date: item.date || today,
            categoryId: item.categoryId,
            type: item.type,
            plannedAmount: dto.plannedAmount ?? undefined,
            priority: item.priority,
            description: dto.description,
        });
        setEditing(false);
        onReload();
    }

    return (
        <div className="px-5 py-3 space-y-2" style={{ borderBottom: '1px solid var(--color-border)' }}>
            <div className="flex items-start justify-between gap-3">
                <div className="flex-1 min-w-0">
                    <div className="font-medium text-sm">
                        {item.description || item.categoryName || 'Без названия'}
                    </div>
                    {item.description && (
                        <div className="text-xs truncate" style={{ color: 'var(--color-text-muted)' }}>
                            {item.categoryName}
                        </div>
                    )}
                    <div className="text-xs mt-0.5 flex items-center" style={{ color: 'var(--color-text-muted)' }}>
                        план {fmt(item.plannedAmount)}{item.date ? ` · ${formatPeriod(item.date)}` : ''}
                        {item.url && (
                            <a href={item.url} target="_blank" rel="noreferrer"
                                className="ml-1 inline-flex items-center"
                                style={{ color: 'var(--color-text-muted)' }}
                                onClick={e => e.stopPropagation()}>
                                <ExternalLink size={11} />
                            </a>
                        )}
                    </div>
                </div>
                <div className="flex items-center gap-1.5 shrink-0">
                    <Button size="sm" variant="outline" className="text-xs h-7 px-2"
                        onClick={() => { setRescheduling(v => !v); setNewDate(''); }}>
                        Запланировать
                    </Button>
                    <Button size="sm" variant="ghost" className="h-7 w-7 p-0"
                        style={{ color: 'var(--color-text-muted)' }}
                        onClick={() => setEditing(v => !v)}>
                        <Pencil size={13} />
                    </Button>
                    <Button size="sm" variant="ghost" className="text-xs h-7 w-7 p-0"
                        style={{ color: 'var(--color-text-muted)' }}
                        onClick={handleDelete}>
                        ×
                    </Button>
                </div>
            </div>
            {rescheduling && (
                <div className="flex gap-2 items-center">
                    <Input type="date" value={newDate} onChange={e => setNewDate(e.target.value)}
                        className="h-7 text-xs flex-1" />
                    <Button size="sm" className="h-7 text-xs px-3" disabled={!newDate}
                        onClick={handleReschedule}>ОК</Button>
                </div>
            )}
            {editing && (
                <WishlistForm
                    initialValues={{
                        description: item.description ?? '',
                        plannedAmount: item.plannedAmount ?? undefined,
                        url: item.url ?? '',
                    }}
                    onSubmit={handleEditSubmit}
                    onCancel={() => setEditing(false)}
                />
            )}
        </div>
    );
}
```

- [ ] **Step 3: Refactor WishlistSection.tsx**

Replace the full contents of `WishlistSection.tsx` with the orchestrator-only version:

```typescript
// frontend/src/components/WishlistSection.tsx
import { useEffect, useState, useCallback } from 'react';
import { Plus } from 'lucide-react';
import { fetchWishlist, createWishlistItem } from '../api';
import type { FinancialEvent, WishlistCreateDto } from '../types/api';
import WishlistItem from './WishlistItem';
import WishlistForm from './WishlistForm';

export default function WishlistSection() {
    const [items, setItems] = useState<FinancialEvent[]>([]);
    const [loading, setLoading] = useState(true);
    const [showAddForm, setShowAddForm] = useState(false);
    const [isOpen, setIsOpen] = useState(true);

    const load = useCallback(() => {
        setLoading(true);
        fetchWishlist()
            .then(setItems)
            .finally(() => setLoading(false));
    }, []);

    useEffect(() => { load(); }, [load]);

    async function handleAdd(dto: WishlistCreateDto) {
        await createWishlistItem(dto);
        setShowAddForm(false);
        await load();
    }

    if (loading) return (
        <div className="text-sm text-center animate-pulse py-4" style={{ color: 'var(--color-text-muted)' }}>
            Загрузка хотелок...
        </div>
    );

    return (
        <div className="rounded-2xl overflow-hidden"
            style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}>
            <div className="flex items-center justify-between px-5 py-3">
                <button onClick={() => setIsOpen(v => !v)}
                    className="flex items-center gap-2 font-semibold text-sm flex-1 text-left">
                    <span>Хотелки</span>
                    <span style={{ color: 'var(--color-text-muted)', fontSize: '12px' }}>
                        {isOpen ? '▲' : '▼'}
                    </span>
                </button>
                {isOpen && (
                    <button onClick={() => setShowAddForm(v => !v)}
                        className="w-6 h-6 rounded flex items-center justify-center transition-colors"
                        style={{ color: 'var(--color-text-muted)' }} title="Добавить хотелку">
                        <Plus size={14} />
                    </button>
                )}
            </div>
            {isOpen && (
                <div className="px-5 pb-4 space-y-3">
                    {showAddForm && (
                        <WishlistForm
                            onSubmit={handleAdd}
                            onCancel={() => setShowAddForm(false)}
                        />
                    )}
                    {items.length === 0 ? (
                        <div className="py-2 text-sm" style={{ color: 'var(--color-text-muted)' }}>
                            Нереализованных хотелок нет
                        </div>
                    ) : (
                        <div className="divide-y" style={{ borderColor: 'var(--color-border)' }}>
                            {items.map(item => (
                                <WishlistItem key={item.id} item={item} onReload={load} />
                            ))}
                        </div>
                    )}
                </div>
            )}
        </div>
    );
}
```

- [ ] **Step 4: Verify in browser**

Open the Funds page (or wherever WishlistSection renders). Verify:
- Wishlist loads and renders items
- Add form works
- Edit, reschedule, delete work
- No console errors

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/WishlistSection.tsx \
        frontend/src/components/WishlistItem.tsx \
        frontend/src/components/WishlistForm.tsx
git commit -m "refactor(wishlist): split God Component into WishlistSection + WishlistItem + WishlistForm (9.4)"
```

---

### Task 11: Budget — plan-fact hover groups + factSum in plan row (9.5)

**Files:**
- Modify: `frontend/src/pages/Budget.tsx`
- Modify: `frontend/src/index.css`

- [ ] **Step 1: Add CSS classes for hover groups**

In `frontend/src/index.css`, add at the end:

```css
/* ── Plan-Fact hover groups (Budget) ── */
.pf-hovered {
  background: rgba(108, 99, 255, 0.07) !important;
}
.pf-hovered.pf-is-plan {
  border-left: 2px solid var(--color-accent) !important;
  padding-left: 12px !important;
}
.pf-hovered.pf-is-fact {
  border-left: 2px solid rgba(108, 99, 255, 0.35) !important;
  padding-left: 12px !important;
}
```

- [ ] **Step 2: Add hover handler utility to Budget.tsx**

At the top of `Budget.tsx` (outside the component), add:

```typescript
function createPlanFactHandlers() {
  const handleMouseEnter = (groupId: string) => {
    document.querySelectorAll(`[data-group="${groupId}"]`)
      .forEach(el => el.classList.add('pf-hovered'));
  };
  const handleMouseLeave = (groupId: string) => {
    document.querySelectorAll(`[data-group="${groupId}"]`)
      .forEach(el => el.classList.remove('pf-hovered'));
  };
  return { handleMouseEnter, handleMouseLeave };
}

const pfHandlers = createPlanFactHandlers();
```

- [ ] **Step 3: Add `data-group` and fact sum to PLAN event rows**

In `Budget.tsx`, find where `EventKind.PLAN` rows are rendered. Add:

1. `data-group={event.id}` on the row element
2. `className="... pf-is-plan"` on the row element
3. `onMouseEnter={() => pfHandlers.handleMouseEnter(event.id)}` and `onMouseLeave={() => pfHandlers.handleMouseLeave(event.id)}`
4. Inside the row, display `linkedFactsAmount` below the plan amount:

```typescript
// In the amount column of a PLAN row:
<div className="flex flex-col items-end gap-0.5">
  <span style={{ color: 'var(--color-text-muted)', fontSize: '12px' }}>
    {fmt(event.plannedAmount)}
  </span>
  {event.linkedFactsAmount != null ? (
    <span style={{
      fontSize: '11px',
      fontWeight: 600,
      color: event.linkedFactsAmount <= (event.plannedAmount ?? Infinity)
        ? 'var(--color-success)'
        : 'var(--color-danger)',
    }}>
      факт {fmt(event.linkedFactsAmount)}
    </span>
  ) : (
    <span style={{ fontSize: '11px', color: 'var(--color-text-muted)', fontStyle: 'italic' }}>
      нет факта
    </span>
  )}
</div>
```

- [ ] **Step 4: Add `data-group` to FACT event rows**

Find where `EventKind.FACT` rows are rendered. Add:

1. `data-group={event.parentEventId ?? event.id}` on the row element
2. `className="... pf-is-fact"` on the row element
3. Same `onMouseEnter`/`onMouseLeave` handlers using `event.parentEventId ?? event.id`
4. If the FACT row has a `parentEventId`, show the parent plan name as a subtitle. The date-differs check is omitted because `parentPlanDate` is not in `FinancialEventDto` — the subtitle is shown whenever a parent link exists:

```typescript
// In the subtitle of a FACT row:
{event.parentEventId && event.parentPlanDescription && (
  <div className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
    → план «{event.parentPlanDescription}»
  </div>
)}
```

If the spec is ever updated to require the date-differs condition, `parentPlanDate: string | null` should be added to `FinancialEventDto` at that time. For now, always show the subtitle when the link exists.

- [ ] **Step 5: Verify in browser**

Open Budget page. Verify:
- PLAN rows show fact sum in green (≤ plan) or red (> plan), or "нет факта" in italic
- Hovering a PLAN row highlights it and all linked FACT rows
- Hovering a FACT row highlights it and its parent PLAN (even if in a different day)

- [ ] **Step 6: Commit**

```bash
git add frontend/src/pages/Budget.tsx frontend/src/index.css
git commit -m "feat(budget): show fact sum in plan row, add cross-day hover groups (9.5)"
```

---

## Chunk 4: Analytics — PriorityBreakdown backend + BudgetStructureSection frontend

### Task 12: AnalyticsReportDto + AnalyticsService.buildPriorityBreakdown

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/dto/AnalyticsReportDto.java`
- Modify: `backend/src/main/java/ru/selfin/backend/service/AnalyticsService.java`

- [ ] **Step 0: Write a failing test for buildPriorityBreakdown**

Add to `backend/src/test/java/ru/selfin/backend/service/AnalyticsServiceTest.java`:

```java
@Test
void buildPriorityBreakdown_aggregatesByPriorityAndKind() {
    // Two PLAN events and their linked FACT events
    FinancialEvent highPlan = makeEvent(EventKind.PLAN, Priority.HIGH, CategoryType.EXPENSE,
            BigDecimal.valueOf(10000), null);
    FinancialEvent highFact = makeEvent(EventKind.FACT, Priority.HIGH, CategoryType.EXPENSE,
            null, BigDecimal.valueOf(9000));
    FinancialEvent medPlan = makeEvent(EventKind.PLAN, Priority.MEDIUM, CategoryType.EXPENSE,
            BigDecimal.valueOf(5000), null);
    FinancialEvent incFact = makeEvent(EventKind.FACT, Priority.MEDIUM, CategoryType.INCOME,
            null, BigDecimal.valueOf(80000));

    // Use the existing getReport() path: stub the repo to return these events
    when(checkpointRepository.findTopByOrderByDateDesc()).thenReturn(Optional.empty());
    when(eventRepository.findAllByDeletedFalseAndDateBetween(any(), any()))
            .thenReturn(List.of(highPlan, highFact, medPlan, incFact));

    AnalyticsReportDto report = service.getReport(LocalDate.of(2026, 4, 9));

    AnalyticsReportDto.PriorityBreakdown b = report.priorityBreakdown();
    assertThat(b.highPlanned()).isEqualByComparingTo(BigDecimal.valueOf(10000));
    assertThat(b.highFact()).isEqualByComparingTo(BigDecimal.valueOf(9000));
    assertThat(b.totalIncomeFact()).isEqualByComparingTo(BigDecimal.valueOf(80000));
}
// makeEvent() helper — adapt to actual FinancialEvent builder in this codebase
```

Run:
```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -pl backend -Dtest=AnalyticsServiceTest#buildPriorityBreakdown_aggregatesByPriorityAndKind
```
Expected: FAIL — `priorityBreakdown()` accessor not found.

- [ ] **Step 1: Add PriorityBreakdown nested record to AnalyticsReportDto**

Open `AnalyticsReportDto.java`. Add the nested record alongside existing ones (e.g. after `IncomeGap`):

```java
public record PriorityBreakdown(
    BigDecimal highPlanned,
    BigDecimal highFact,
    BigDecimal mediumPlanned,
    BigDecimal mediumFact,
    BigDecimal lowPlanned,
    BigDecimal lowFact,
    BigDecimal totalIncomeFact
) {}
```

Then add `PriorityBreakdown priorityBreakdown` as a field to the main `AnalyticsReportDto` record.

- [ ] **Step 2: Implement buildPriorityBreakdown in AnalyticsService**

Add a private method to `AnalyticsService`:

```java
private PriorityBreakdown buildPriorityBreakdown(List<FinancialEvent> events) {
    // IMPORTANT: monthEvents contains both PLAN and FACT records.
    // To avoid double-counting: read plannedAmount only from PLAN records,
    // factAmount only from FACT records. Follow the same pattern as buildMandatoryBurnRate().
    BigDecimal highPlanned = BigDecimal.ZERO, highFact = BigDecimal.ZERO;
    BigDecimal mediumPlanned = BigDecimal.ZERO, mediumFact = BigDecimal.ZERO;
    BigDecimal lowPlanned = BigDecimal.ZERO, lowFact = BigDecimal.ZERO;
    BigDecimal totalIncomeFact = BigDecimal.ZERO;

    for (FinancialEvent e : events) {
        boolean isPlan = e.getKind() == EventKind.PLAN;
        boolean isFact = e.getKind() == EventKind.FACT;

        if (e.getType() == EventType.INCOME) {
            if (isFact) totalIncomeFact = totalIncomeFact.add(orZero(e.getFactAmount()));
            continue; // income not broken down by priority for expense cards
        }
        BigDecimal planned = isPlan ? orZero(e.getPlannedAmount()) : BigDecimal.ZERO;
        BigDecimal fact    = isFact ? orZero(e.getFactAmount())    : BigDecimal.ZERO;
        switch (e.getPriority()) {
            case HIGH   -> { highPlanned = highPlanned.add(planned); highFact = highFact.add(fact); }
            case MEDIUM -> { mediumPlanned = mediumPlanned.add(planned); mediumFact = mediumFact.add(fact); }
            case LOW    -> { lowPlanned = lowPlanned.add(planned); lowFact = lowFact.add(fact); }
        }
    }
    return new AnalyticsReportDto.PriorityBreakdown(
            highPlanned, highFact, mediumPlanned, mediumFact,
            lowPlanned, lowFact, totalIncomeFact);
}
// Note: EventKind import — check what the existing AnalyticsService already imports (e.g. buildMandatoryBurnRate uses EventKind)
```

- [ ] **Step 3: Call buildPriorityBreakdown in getReport()**

In `getReport()`, add the call and include it in the returned `AnalyticsReportDto`:

```java
return new AnalyticsReportDto(
        buildCashFlow(...),
        buildPlanFact(monthEvents),
        buildMandatoryBurnRate(monthEvents, monthStart, monthEnd),
        buildIncomeGap(monthEvents),
        buildPriorityBreakdown(monthEvents));   // ← new field
```

- [ ] **Step 4: Run backend tests**

```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -pl backend
```

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/ru/selfin/backend/dto/AnalyticsReportDto.java \
        backend/src/main/java/ru/selfin/backend/service/AnalyticsService.java
git commit -m "feat(analytics): add PriorityBreakdown to AnalyticsReportDto (Analytics block)"
```

---

### Task 13: Frontend — BudgetStructureSection + Analytics.tsx wiring

**Files:**
- Create: `frontend/src/components/BudgetStructureSection.tsx`
- Modify: `frontend/src/pages/Analytics.tsx`
- Modify: `frontend/src/types/api.ts`
- Modify: `frontend/src/api/index.ts`

- [ ] **Step 1: Add PriorityBreakdown type + fetchEventsByPriority to api layer**

In `frontend/src/types/api.ts`, add the new interface and update `AnalyticsReport`:

```typescript
export interface PriorityBreakdown {
    highPlanned: number;
    highFact: number;
    mediumPlanned: number;
    mediumFact: number;
    lowPlanned: number;
    lowFact: number;
    totalIncomeFact: number;
}
```

Then find the existing `AnalyticsReport` interface and add the `priorityBreakdown` field:

```typescript
// In the existing AnalyticsReport interface, add this field:
priorityBreakdown: PriorityBreakdown;
```

In `frontend/src/api/index.ts`, add:

```typescript
/** Загружает все события с указанным приоритетом (без ограничения по дате). */
export const fetchEventsByPriority = (priority: 'LOW' | 'MEDIUM' | 'HIGH') =>
    get<FinancialEvent[]>(`/events?priority=${priority}`);
```

- [ ] **Step 2: Create BudgetStructureSection.tsx**

```typescript
// frontend/src/components/BudgetStructureSection.tsx
import type { AnalyticsReport, FinancialEvent } from '../types/api';

const fmt = (n: number) =>
    new Intl.NumberFormat('ru-RU', { minimumFractionDigits: 0 }).format(n) + ' ₽';

interface Props {
    breakdown: AnalyticsReport['priorityBreakdown'];
    wishlistItems: FinancialEvent[];
}

// ── Wishlist dot tooltip ──────────────────────────────────────────────────────

function WishlistDot({ item }: { item: FinancialEvent }) {
    const isDone = item.factAmount != null && item.factAmount > 0;
    const isPlanned = item.date != null && !isDone;

    const dotStyle: React.CSSProperties = isDone
        ? { background: 'var(--color-accent)' }
        : isPlanned
        ? { background: 'rgba(108,99,255,0.18)', border: '1.5px solid var(--color-accent)' }
        : { background: 'var(--color-surface)', border: '1px solid var(--color-border)' };

    const dateLabel = isDone
        ? `факт: ${new Date(item.date! + 'T00:00:00').toLocaleDateString('ru-RU', { day: 'numeric', month: 'short' })}`
        : item.date
        ? `план: ${new Date(item.date + 'T00:00:00').toLocaleDateString('ru-RU', { month: 'long', year: 'numeric' })}`
        : 'без даты';

    return (
        <div className="relative group" style={{ flexShrink: 0 }}>
            <div style={{ width: 22, height: 22, borderRadius: 5, cursor: 'default', ...dotStyle }} />
            {/* Tooltip */}
            <div className="absolute bottom-full left-1/2 -translate-x-1/2 mb-1.5 hidden group-hover:block z-50
                            whitespace-nowrap rounded-lg px-2.5 py-2 text-xs pointer-events-none"
                style={{ background: 'var(--color-surface-2)', border: '1px solid var(--color-border)',
                         boxShadow: '0 4px 20px rgba(0,0,0,0.5)' }}>
                <div className="font-semibold" style={{ color: 'var(--color-text)' }}>
                    {item.description || item.categoryName || 'Без названия'}
                </div>
                <div style={{ color: 'var(--color-text-muted)' }}>
                    {item.plannedAmount != null ? fmt(item.plannedAmount) : '—'}
                </div>
                <div style={{ color: isDone ? 'var(--color-success)' : 'var(--color-text-muted)' }}>
                    {dateLabel}
                </div>
                {/* Arrow */}
                <div className="absolute top-full left-1/2 -translate-x-1/2"
                    style={{ width: 0, height: 0, borderLeft: '5px solid transparent',
                             borderRight: '5px solid transparent',
                             borderTop: '5px solid var(--color-border)' }} />
            </div>
        </div>
    );
}

// ── Main component ────────────────────────────────────────────────────────────

export default function BudgetStructureSection({ breakdown, wishlistItems }: Props) {
    const b = breakdown;
    const highPct = b.highPlanned > 0
        ? Math.min(Math.round((b.highFact / b.highPlanned) * 100), 100) : 0;

    // Stack bar segments (as % of totalIncomeFact; 0 if no income)
    const total = b.totalIncomeFact > 0 ? b.totalIncomeFact : 1;
    const highShare = Math.round((b.highFact / total) * 100);
    const mediumShare = Math.round((b.mediumFact / total) * 100);
    const lowShare = Math.round((b.lowFact / total) * 100);
    const remainder = 100 - highShare - mediumShare - lowShare;
    const isOverspent = remainder < 0;

    // Wishlist dots (max 20)
    const MAX_DOTS = 20;
    const displayed = wishlistItems.slice(0, MAX_DOTS);
    const overflow = wishlistItems.length - MAX_DOTS;
    const doneCount = wishlistItems.filter(i => i.factAmount != null && i.factAmount > 0).length;

    const cardStyle = {
        background: 'var(--color-surface-2)',
        border: '1px solid var(--color-border)',
        borderRadius: 12,
        padding: '13px 15px',
    };

    return (
        <div className="rounded-2xl p-5 space-y-3"
            style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}>
            <h3 className="font-semibold text-sm" style={{ color: 'var(--color-text-muted)' }}>
                СТРУКТУРА МЕСЯЦА
            </h3>

            {/* Card 1: HIGH */}
            <div style={cardStyle}>
                <div className="flex justify-between items-center mb-2">
                    <span className="text-xs font-semibold" style={{ color: 'var(--color-danger)' }}>Обязательные</span>
                    <span className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
                        {fmt(b.highFact)} / {fmt(b.highPlanned)}
                    </span>
                </div>
                <div style={{ background: 'var(--color-border)', borderRadius: 4, height: 6, overflow: 'hidden', marginBottom: 5 }}>
                    <div style={{ width: `${highPct}%`, height: 6, background: 'var(--color-danger)', borderRadius: 4 }} />
                </div>
                <div className="text-xs" style={{
                    color: b.highFact <= b.highPlanned ? 'var(--color-success)' : 'var(--color-danger)',
                }}>
                    {b.highFact <= b.highPlanned
                        ? `сэкономил ${fmt(b.highPlanned - b.highFact)}`
                        : `перерасход ${fmt(b.highFact - b.highPlanned)}`}
                </div>
            </div>

            {/* Card 2: MEDIUM — stack bar */}
            <div style={cardStyle}>
                <div className="flex justify-between items-center mb-2">
                    <span className="text-xs font-semibold" style={{ color: 'var(--color-text)' }}>Прочие расходы</span>
                    <span className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
                        {fmt(b.mediumFact)} · {mediumShare}% бюджета
                    </span>
                </div>
                {/* Stack bar */}
                <div style={{ display: 'flex', height: 8, borderRadius: 5, overflow: 'hidden', gap: 1, marginBottom: 7 }}>
                    <div style={{ flex: highShare, background: 'var(--color-danger)' }} />
                    <div style={{ flex: mediumShare, background: 'var(--color-text-muted)' }} />
                    <div style={{ flex: lowShare, background: 'var(--color-accent)' }} />
                    {!isOverspent && <div style={{ flex: remainder, background: 'var(--color-border)' }} />}
                    {isOverspent && <div style={{ flex: 1, background: 'var(--color-danger)' }} />}
                </div>
                {isOverspent && (
                    <div className="text-xs mb-1" style={{ color: 'var(--color-danger)' }}>
                        Перерасход: {fmt(b.highFact + b.mediumFact + b.lowFact - b.totalIncomeFact)}
                    </div>
                )}
                <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap' }}>
                    {[
                        { label: `обязат. ${highShare}%`, color: 'var(--color-danger)' },
                        { label: `прочие ${mediumShare}%`, color: 'var(--color-text-muted)' },
                        { label: `хотелки ${lowShare}%`, color: 'var(--color-accent)' },
                        !isOverspent
                            ? { label: `остаток ${remainder}%`, color: 'var(--color-border)', border: '1px solid var(--color-text-muted)' }
                            : null,
                    ].filter(Boolean).map((seg, i) => (
                        <span key={i} className="text-xs flex items-center gap-1"
                            style={{ color: 'var(--color-text-muted)' }}>
                            <span style={{ display: 'inline-block', width: 8, height: 8, borderRadius: 2,
                                           background: seg!.color, border: (seg as any).border }} />
                            {seg!.label}
                        </span>
                    ))}
                </div>
            </div>

            {/* Card 3: Wishlist dots */}
            <div style={cardStyle}>
                <div className="flex justify-between items-center mb-2">
                    <span className="text-xs font-semibold" style={{ color: 'var(--color-text-muted)' }}>Хотелки</span>
                    <span className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
                        {doneCount} из {wishlistItems.length} выполнена
                    </span>
                </div>
                {wishlistItems.length === 0 ? (
                    <div className="text-xs" style={{ color: 'var(--color-text-muted)' }}>Список пуст</div>
                ) : (
                    <>
                        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                            {displayed.map(item => <WishlistDot key={item.id} item={item} />)}
                            {overflow > 0 && (
                                <span className="text-xs self-center" style={{ color: 'var(--color-text-muted)' }}>
                                    + ещё {overflow}
                                </span>
                            )}
                        </div>
                        <div style={{ display: 'flex', gap: 12, marginTop: 10, flexWrap: 'wrap' }}>
                            {[
                                { label: 'выполнена', bg: 'var(--color-accent)' },
                                { label: 'в плане', bg: 'rgba(108,99,255,0.18)', border: '1px solid var(--color-accent)' },
                                { label: 'в списке', bg: 'var(--color-surface)', border: '1px solid var(--color-border)' },
                            ].map(s => (
                                <span key={s.label} className="text-xs flex items-center gap-1"
                                    style={{ color: 'var(--color-text-muted)' }}>
                                    <span style={{ display: 'inline-block', width: 9, height: 9, borderRadius: 2,
                                                   background: s.bg, border: s.border }} />
                                    {s.label}
                                </span>
                            ))}
                        </div>
                    </>
                )}
            </div>
        </div>
    );
}
```

- [ ] **Step 3: Wire into Analytics.tsx**

In `Analytics.tsx`:

1. Import:
```typescript
import BudgetStructureSection from '../components/BudgetStructureSection';
import { fetchEventsByPriority } from '../api';
```

2. Add state:
```typescript
const [lowEvents, setLowEvents] = useState<FinancialEvent[]>([]);
```

3. Update the `useEffect` so that the `1m` preset fetches both the report and LOW events in parallel, while all other presets continue using only `fetchAnalyticsReport()`. The existing unconditional fetch path must be preserved for `3m`/`6m`/`12m`:

```typescript
// Replace the existing useEffect fetch logic with:
useEffect(() => {
    setLoading(true);
    if (preset === '1m') {
        Promise.all([fetchAnalyticsReport(), fetchEventsByPriority('LOW')])
            .then(([report, low]) => {
                setAnalytics(report);
                setLowEvents(low);
            })
            .finally(() => setLoading(false));
    } else {
        setLowEvents([]); // reset stale low-events when switching presets
        fetchAnalyticsReport()
            .then(setAnalytics)
            .finally(() => setLoading(false));
    }
}, [preset]);
```

4. Replace `<MandatoryBurnSection burn={analytics.mandatoryBurn} />` with:
```typescript
{analytics.priorityBreakdown && (
    <BudgetStructureSection
        breakdown={analytics.priorityBreakdown}
        wishlistItems={lowEvents}
    />
)}
```

5. Remove the `MandatoryBurnSection` import and component (it's no longer used in `Analytics.tsx`; keep it in Dashboard if used there).

- [ ] **Step 4: Verify in browser**

Open Analytics page → Месяц. Verify:
- Three cards render: Обязательные (with progress bar), Прочие расходы (with stack bar), Хотелки (with dots)
- Hover over wishlist dots shows tooltip with name, amount, date
- No console errors

- [ ] **Step 5: Run backend tests**

```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -pl backend
```

Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add frontend/src/components/BudgetStructureSection.tsx \
        frontend/src/pages/Analytics.tsx \
        frontend/src/types/api.ts \
        frontend/src/api/index.ts
git commit -m "feat(analytics): add BudgetStructureSection replacing MandatoryBurnSection"
```

---

## Settings — checkpoint date max (9.3 frontend)

### Task 14: Settings.tsx — max date on checkpoint input

**Files:**
- Modify: `frontend/src/pages/Settings.tsx`

- [ ] **Step 1: Find the checkpoint date input in Settings.tsx and add `max` attribute**

Find `<input type="date"` in the checkpoint section of `Settings.tsx`. Add `max={new Date().toISOString().slice(0, 10)}`:

```typescript
<input
    type="date"
    max={new Date().toISOString().slice(0, 10)}
    value={checkpointDate}
    onChange={e => setCheckpointDate(e.target.value)}
    ...
/>
```

- [ ] **Step 2: Verify in browser**

Open Settings → checkpoint date input. Verify future dates are greyed out / not selectable.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/pages/Settings.tsx
git commit -m "fix(checkpoint): prevent selecting future dates in UI (9.3)"
```
