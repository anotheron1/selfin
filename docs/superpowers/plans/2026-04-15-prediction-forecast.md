# Prediction Forecast Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add end-of-month spend predictions per forecast-enabled category to the Dashboard progress bars, with a prediction-adjusted pocket balance in the Funds popup.

**Architecture:** A new `PredictionService` holds the hybrid forecast algorithm (fact + remaining plans, fallback to linear extrapolation). Both `DashboardService` and `TargetFundService` call it with already-fetched events to avoid double querying. Prediction data is bundled into existing API responses — no extra frontend calls.

**Tech Stack:** Spring Boot 4.0.3, Java 21, PostgreSQL 15, Flyway, JUnit 5 + Mockito, React 18, TypeScript, Tailwind CSS, Shadcn UI.

**Spec:** `docs/superpowers/specs/2026-04-15-prediction-forecast-design.md`

**Test command:** `JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test`  
**Single test:** `JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -Dtest=ClassName`  
**TS check:** `cd frontend && npx tsc --noEmit`

---

## File Map

| Action | File | Purpose |
|--------|------|---------|
| Create | `backend/src/main/resources/db/migration/V15__add_forecast_enabled_to_categories.sql` | Add `forecast_enabled` column, seed variable categories |
| Modify | `backend/src/main/java/ru/selfin/backend/model/Category.java` | Add `forecastEnabled` field |
| Modify | `backend/src/main/java/ru/selfin/backend/dto/CategoryCreateDto.java` | Add `forecastEnabled: Boolean` |
| Modify | `backend/src/main/java/ru/selfin/backend/dto/CategoryDto.java` | Add `forecastEnabled: boolean` to response |
| Modify | `backend/src/main/java/ru/selfin/backend/service/CategoryService.java` | Map `forecastEnabled` in `toDto()` and `update()` |
| Create | `backend/src/main/java/ru/selfin/backend/dto/DailyForecastPointDto.java` | Record: `day`, `cumulativeFact`, `projectedTotal` |
| Create | `backend/src/main/java/ru/selfin/backend/dto/CategoryForecastDto.java` | Record: per-category forecast with history |
| Create | `backend/src/main/java/ru/selfin/backend/dto/MonthlyForecastDto.java` | Record: all categories + `netPredictionDelta` |
| Create | `backend/src/main/java/ru/selfin/backend/service/PredictionService.java` | Core hybrid forecast algorithm |
| Create | `backend/src/test/java/ru/selfin/backend/service/PredictionServiceTest.java` | Unit tests for forecast algorithms |
| Modify | `backend/src/main/java/ru/selfin/backend/dto/DashboardDto.java` | Extend inner record `CategoryProgressBar` with forecast fields |
| Modify | `backend/src/main/java/ru/selfin/backend/service/DashboardService.java` | Inject PredictionService, call it in `buildProgressBars()` |
| Modify | `backend/src/test/java/ru/selfin/backend/service/DashboardServiceTest.java` | Tests for forecast fields in progress bars |
| Modify | `backend/src/main/java/ru/selfin/backend/controller/AnalyticsController.java` | Add `GET /api/v1/analytics/forecast` endpoint |
| Modify | `backend/src/main/java/ru/selfin/backend/dto/FundsOverviewDto.java` | Add `predictionAdjustedPocket`, `forecastContributors` |
| Modify | `backend/src/main/java/ru/selfin/backend/service/TargetFundService.java` | Inject PredictionService, compute adjusted pocket in `getOverview()` |
| Modify | `backend/src/test/java/ru/selfin/backend/service/TargetFundServiceTest.java` | Tests for adjusted pocket |
| Modify | `frontend/src/types/api.ts` | Extend `CategoryProgressBar`, `FundsOverview`; add `DailyForecastPoint` |
| Modify | `frontend/src/pages/Dashboard.tsx` | Needle, dynamic bar scaling, C2 amounts, hover sparkline |
| Modify | `frontend/src/pages/Funds.tsx` | "По текущему темпу" row in кармашек popup |

---

## Chunk 1: Data Model — DB Migration + Category Entity

### Task 1: Flyway migration V15

**Files:**
- Create: `backend/src/main/resources/db/migration/V15__add_forecast_enabled_to_categories.sql`

- [ ] **Step 1: Create migration file**

```sql
-- V15: Add forecast_enabled flag to categories for spend prediction feature
ALTER TABLE categories ADD COLUMN forecast_enabled BOOLEAN NOT NULL DEFAULT FALSE;

-- Enable for variable expense categories only (mandatory excluded — no spending "pace")
UPDATE categories
SET forecast_enabled = TRUE
WHERE type = 'EXPENSE'
  AND is_deleted = FALSE
  AND name IN (
    'Еда / Продукты',
    'Кафе / Рестораны',
    'Транспорт',
    'Одежда',
    'Развлечения',
    'Подписки',
    'Прочее'
  );
```

- [ ] **Step 2: Run backend to verify migration applies cleanly**

```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw spring-boot:run
```

Expected: application starts, Flyway log shows `V15__add_forecast_enabled_to_categories.sql` executed successfully. Stop with Ctrl+C.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/resources/db/migration/V15__add_forecast_enabled_to_categories.sql
git commit -m "feat: add forecast_enabled column to categories (V15 migration)"
```

---

### Task 2: Category entity, DTOs, and service

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/model/Category.java`
- Modify: `backend/src/main/java/ru/selfin/backend/dto/CategoryCreateDto.java`
- Modify: `backend/src/main/java/ru/selfin/backend/dto/CategoryDto.java`
- Modify: `backend/src/main/java/ru/selfin/backend/service/CategoryService.java`

- [ ] **Step 1: Add `forecastEnabled` to Category entity**

In `Category.java`, add after the `system` field:

```java
@Column(name = "forecast_enabled", nullable = false)
private boolean forecastEnabled;
```

The class already uses `@Getter`, `@Setter`, `@Builder`, `@AllArgsConstructor`, `@NoArgsConstructor` — no extra methods needed.

- [ ] **Step 2: Add `forecastEnabled` to `CategoryCreateDto`**

Replace the record definition in `CategoryCreateDto.java`:

```java
public record CategoryCreateDto(
        @NotBlank String name,
        @NotNull CategoryType type,
        Priority priority,
        Boolean forecastEnabled) {   // nullable: null = don't change on update, false on create
}
```

- [ ] **Step 3: Add `forecastEnabled` to `CategoryDto`**

In `CategoryDto.java`, add the field to the record. The record currently has `id, name, type, priority, isSystem`. Add `forecastEnabled`:

```java
public record CategoryDto(
        UUID id,
        String name,
        CategoryType type,
        Priority priority,
        boolean isSystem,
        boolean forecastEnabled) {
}
```

- [ ] **Step 4: Update `CategoryService`**

In `CategoryService.java`:

**In `toDto(Category c)`** (currently line ~152), change to:
```java
return new CategoryDto(c.getId(), c.getName(), c.getType(), c.getPriority(),
        c.isSystem(), c.isForecastEnabled());
```

**In `create(CategoryCreateDto dto)`** (currently line ~64), add after building the category object (before save):
```java
category.setForecastEnabled(dto.forecastEnabled() != null && dto.forecastEnabled());
```

**In `update(UUID id, CategoryCreateDto dto)`** (currently line ~84), add after the existing field updates (before save):
```java
if (dto.forecastEnabled() != null) {
    category.setForecastEnabled(dto.forecastEnabled());
}
```

- [ ] **Step 5: Run tests to verify nothing is broken**

```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test
```

Expected: all existing tests pass. If compilation fails, check that all `new CategoryDto(...)` call sites are updated to include the new `forecastEnabled` argument.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/ru/selfin/backend/model/Category.java \
        backend/src/main/java/ru/selfin/backend/dto/CategoryCreateDto.java \
        backend/src/main/java/ru/selfin/backend/dto/CategoryDto.java \
        backend/src/main/java/ru/selfin/backend/service/CategoryService.java
git commit -m "feat: add forecastEnabled field to Category entity and DTOs"
```

---

## Chunk 2: PredictionService

### Task 3: Forecast DTOs

**Files:**
- Create: `backend/src/main/java/ru/selfin/backend/dto/DailyForecastPointDto.java`
- Create: `backend/src/main/java/ru/selfin/backend/dto/CategoryForecastDto.java`
- Create: `backend/src/main/java/ru/selfin/backend/dto/MonthlyForecastDto.java`

- [ ] **Step 1: Create `DailyForecastPointDto.java`**

```java
package ru.selfin.backend.dto;

import java.math.BigDecimal;

/**
 * One day's data point for the sparkline chart.
 * Both cumulativeFact and projectedTotal represent amounts in rubles.
 */
public record DailyForecastPointDto(
        int day,                      // day-of-month, 1-based
        BigDecimal cumulativeFact,    // running total of actual spending up to this day
        BigDecimal projectedTotal     // end-of-month forecast as computed on this day
) {}
```

- [ ] **Step 2: Create `CategoryForecastDto.java`**

```java
package ru.selfin.backend.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Forecast result for one expense category.
 */
public record CategoryForecastDto(
        String categoryName,
        BigDecimal currentFact,       // fact spent so far this month
        BigDecimal plannedLimit,      // sum of all PLAN events this month
        BigDecimal projectionAmount,  // end-of-month projection (hybrid B or linear A)
        List<DailyForecastPointDto> history  // one point per day from day 1 to today
) {}
```

- [ ] **Step 3: Create `MonthlyForecastDto.java`**

```java
package ru.selfin.backend.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Full monthly forecast: per-category results plus the delta relevant for pocket balance.
 *
 * <p>netPredictionDelta is the sum of extrapolated future spending for forecast_enabled
 * categories that have NO plan events (linear-only categories).
 * For plan-based categories, delta = 0 because кармашек already accounts for them
 * via pocketBalance (which tracks executed facts) + remaining plan events.</p>
 */
public record MonthlyForecastDto(
        List<CategoryForecastDto> categories,
        BigDecimal netPredictionDelta
) {}
```

- [ ] **Step 4: Compile check**

```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw compile -pl backend
```

Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/ru/selfin/backend/dto/DailyForecastPointDto.java \
        backend/src/main/java/ru/selfin/backend/dto/CategoryForecastDto.java \
        backend/src/main/java/ru/selfin/backend/dto/MonthlyForecastDto.java
git commit -m "feat: add forecast DTO records"
```

---

### Task 4: PredictionService — core algorithm + tests

**Files:**
- Create: `backend/src/test/java/ru/selfin/backend/service/PredictionServiceTest.java`
- Create: `backend/src/main/java/ru/selfin/backend/service/PredictionService.java`

- [ ] **Step 1: Create failing tests for core algorithm**

Create `backend/src/test/java/ru/selfin/backend/service/PredictionServiceTest.java`:

```java
package ru.selfin.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.selfin.backend.dto.CategoryForecastDto;
import ru.selfin.backend.dto.MonthlyForecastDto;
import ru.selfin.backend.model.Category;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.enums.CategoryType;
import ru.selfin.backend.model.enums.Priority;
import ru.selfin.backend.model.EventKind;           // NOTE: EventKind is in model package, NOT model.enums
import ru.selfin.backend.repository.FinancialEventRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PredictionServiceTest {

    @Mock
    private FinancialEventRepository eventRepository;

    @InjectMocks
    private PredictionService predictionService;

    private Category foodCategory;

    @BeforeEach
    void setUp() {
        foodCategory = Category.builder()
                .id(UUID.randomUUID())
                .name("Еда / Продукты")
                .type(CategoryType.EXPENSE)
                .priority(Priority.MEDIUM)
                .forecastEnabled(true)
                .deleted(false)
                .build();
    }

    // ── Hybrid B: plan events present ─────────────────────────────────────────

    @Test
    void forecast_hybridB_factPlusRemainingPlans() {
        // Month: April (30 days). Today = day 8.
        // Plan events: 4 × 10k = 40k total, weeks 1-4
        // Fact: 30k in week 1 (overspend)
        // Remaining PLAN events after today: weeks 2-4 = 30k
        // Expected projection: 30k + 30k = 60k
        LocalDate today = LocalDate.of(2026, 4, 8);
        LocalDate week2 = LocalDate.of(2026, 4, 15);
        LocalDate week3 = LocalDate.of(2026, 4, 22);
        LocalDate week4 = LocalDate.of(2026, 4, 29);

        FinancialEvent plan1 = planEvent(foodCategory, LocalDate.of(2026, 4, 1), new BigDecimal("10000"));
        FinancialEvent plan2 = planEvent(foodCategory, week2, new BigDecimal("10000"));
        FinancialEvent plan3 = planEvent(foodCategory, week3, new BigDecimal("10000"));
        FinancialEvent plan4 = planEvent(foodCategory, week4, new BigDecimal("10000"));
        FinancialEvent fact1 = factEvent(foodCategory, LocalDate.of(2026, 4, 5), new BigDecimal("30000"));

        List<FinancialEvent> events = List.of(plan1, plan2, plan3, plan4, fact1);

        CategoryForecastDto result = predictionService.forecast("Еда / Продукты", events, today);

        assertThat(result.projectionAmount()).isEqualByComparingTo(new BigDecimal("60000"));
        assertThat(result.currentFact()).isEqualByComparingTo(new BigDecimal("30000"));
        assertThat(result.plannedLimit()).isEqualByComparingTo(new BigDecimal("40000"));
    }

    @Test
    void forecast_hybridB_nofutureRemainingPlans_projectionEqualsCurrentFact() {
        // All plan events are in the past, no remaining plans
        // Projection = fact + 0 remaining plans = just the fact
        LocalDate today = LocalDate.of(2026, 4, 28);
        FinancialEvent plan1 = planEvent(foodCategory, LocalDate.of(2026, 4, 1), new BigDecimal("40000"));
        FinancialEvent fact1 = factEvent(foodCategory, LocalDate.of(2026, 4, 5), new BigDecimal("35000"));

        CategoryForecastDto result = predictionService.forecast("Еда / Продукты",
                List.of(plan1, fact1), today);

        assertThat(result.projectionAmount()).isEqualByComparingTo(new BigDecimal("35000"));
    }

    // ── Linear A: no plan events ───────────────────────────────────────────────

    @Test
    void forecast_linearA_extrapolatesFromDailyRate() {
        // No PLAN events. Today = day 10 of 30. Spent 5000.
        // dailyRate = 5000/10 = 500/day. Remaining = 20 days.
        // projection = 5000 + 500*20 = 15000
        LocalDate today = LocalDate.of(2026, 4, 10);
        FinancialEvent fact1 = factEvent(foodCategory, LocalDate.of(2026, 4, 5), new BigDecimal("5000"));

        CategoryForecastDto result = predictionService.forecast("Еда / Продукты",
                List.of(fact1), today);

        assertThat(result.projectionAmount()).isEqualByComparingTo(new BigDecimal("15000"));
    }

    // ── Edge cases ─────────────────────────────────────────────────────────────

    @Test
    void forecast_noFacts_noPlans_projectionIsZero() {
        // No data at all → projection = 0
        LocalDate today = LocalDate.of(2026, 4, 5);

        CategoryForecastDto result = predictionService.forecast("Еда / Продукты",
                List.of(), today);

        assertThat(result.projectionAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void forecast_dayOne_noFacts_projectionIsZero() {
        // daysElapsed = 1, but no facts → projection = 0 (avoid 0/0)
        LocalDate today = LocalDate.of(2026, 4, 1);

        CategoryForecastDto result = predictionService.forecast("Еда / Продукты",
                List.of(), today);

        assertThat(result.projectionAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ── netPredictionDelta ─────────────────────────────────────────────────────

    @Test
    void forecastFromEvents_deltaOnlyFromLinearCategories() {
        // Category A has plan events → delta contribution = 0
        // Category B has NO plan events → delta = extrapolated future spending
        LocalDate today = LocalDate.of(2026, 4, 10);

        Category catA = makeCategory("Еда / Продукты", true);
        Category catB = makeCategory("Прочее", true);

        // catA: plan-based, fact=30k, remaining plan=30k → projection=60k, delta=0
        FinancialEvent planA = planEvent(catA, LocalDate.of(2026, 4, 1), new BigDecimal("40000"));
        FinancialEvent factA = factEvent(catA, LocalDate.of(2026, 4, 5), new BigDecimal("30000"));

        // catB: linear, fact=5000 over 10 days, dailyRate=500, remaining=20 days → future=10000
        FinancialEvent factB = factEvent(catB, LocalDate.of(2026, 4, 5), new BigDecimal("5000"));

        MonthlyForecastDto result = predictionService.forecastFromEvents(
                List.of(planA, factA, factB), today);

        // Delta = only catB's extrapolated future = 10000
        assertThat(result.netPredictionDelta()).isEqualByComparingTo(new BigDecimal("10000"));
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private FinancialEvent planEvent(Category category, LocalDate date, BigDecimal amount) {
        return FinancialEvent.builder()
                .id(UUID.randomUUID())
                .category(category)
                .date(date)
                .eventKind(EventKind.PLAN)
                .plannedAmount(amount)
                .deleted(false)
                .build();
    }

    private FinancialEvent factEvent(Category category, LocalDate date, BigDecimal amount) {
        return FinancialEvent.builder()
                .id(UUID.randomUUID())
                .category(category)
                .date(date)
                .eventKind(EventKind.FACT)
                .factAmount(amount)
                .deleted(false)
                .build();
    }

    private Category makeCategory(String name, boolean forecastEnabled) {
        return Category.builder()
                .id(UUID.randomUUID())
                .name(name)
                .type(CategoryType.EXPENSE)
                .priority(Priority.MEDIUM)
                .forecastEnabled(forecastEnabled)
                .deleted(false)
                .build();
    }
}
```

- [ ] **Step 2: Run tests — verify they fail with "cannot find symbol"**

```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -Dtest=PredictionServiceTest
```

Expected: COMPILATION ERROR — `PredictionService` does not exist yet.

- [ ] **Step 3: Create `PredictionService.java`**

```java
package ru.selfin.backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.selfin.backend.dto.CategoryForecastDto;
import ru.selfin.backend.dto.DailyForecastPointDto;
import ru.selfin.backend.dto.MonthlyForecastDto;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.EventKind;           // EventKind is in model package, NOT model.enums
import ru.selfin.backend.repository.FinancialEventRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PredictionService {

    private final FinancialEventRepository eventRepository;

    /**
     * Compute forecast for a single named category using already-fetched events.
     * Only EXPENSE events for this category should be in the list.
     * Events for other categories are ignored.
     */
    public CategoryForecastDto forecast(String categoryName,
                                        List<FinancialEvent> monthEvents,
                                        LocalDate today) {
        List<FinancialEvent> catEvents = monthEvents.stream()
                .filter(e -> categoryName.equals(e.getCategory().getName()))
                .filter(e -> !e.isDeleted())
                .toList();

        int daysInMonth = today.lengthOfMonth();
        int daysElapsed = today.getDayOfMonth(); // 1-based: day 1 = 1 day elapsed

        BigDecimal currentFact = sumFacts(catEvents);
        BigDecimal plannedLimit = sumAllPlans(catEvents);
        boolean hasPlans = catEvents.stream().anyMatch(e -> e.getEventKind() == EventKind.PLAN);

        BigDecimal projection = computeProjection(catEvents, currentFact, plannedLimit,
                hasPlans, daysElapsed, daysInMonth, today);

        List<DailyForecastPointDto> history = buildHistory(catEvents, hasPlans, daysElapsed, daysInMonth, today);

        return new CategoryForecastDto(categoryName, currentFact, plannedLimit, projection, history);
    }

    /**
     * Compute forecasts for all forecast_enabled EXPENSE categories in already-fetched events.
     * Use this from DashboardService and TargetFundService to avoid double-fetching events.
     */
    public MonthlyForecastDto forecastFromEvents(List<FinancialEvent> monthEvents, LocalDate today) {
        // Group by category, keep only forecast_enabled EXPENSE categories
        Map<String, List<FinancialEvent>> byCategory = monthEvents.stream()
                .filter(e -> !e.isDeleted())
                .filter(e -> e.getCategory().isForecastEnabled())
                .collect(Collectors.groupingBy(e -> e.getCategory().getName()));

        List<CategoryForecastDto> forecasts = new ArrayList<>();
        BigDecimal netDelta = BigDecimal.ZERO;

        for (Map.Entry<String, List<FinancialEvent>> entry : byCategory.entrySet()) {
            CategoryForecastDto cat = forecast(entry.getKey(), entry.getValue(), today);
            forecasts.add(cat);

            // Delta contribution: only linear categories (no PLAN events in month)
            boolean hasPlans = entry.getValue().stream()
                    .anyMatch(e -> e.getEventKind() == EventKind.PLAN);
            if (!hasPlans && cat.projectionAmount().compareTo(cat.currentFact()) > 0) {
                BigDecimal extrapolatedFuture = cat.projectionAmount().subtract(cat.currentFact());
                netDelta = netDelta.add(extrapolatedFuture);
            }
        }

        return new MonthlyForecastDto(forecasts, netDelta);
    }

    /**
     * Compute forecasts fetching events from DB. Use for standalone /forecast endpoint.
     */
    public MonthlyForecastDto forecastMonth(YearMonth month, LocalDate today) {
        LocalDate start = month.atDay(1);
        LocalDate end = month.atEndOfMonth();
        List<FinancialEvent> events = eventRepository.findAllByDeletedFalseAndDateBetween(start, end);
        return forecastFromEvents(events, today);
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private BigDecimal computeProjection(List<FinancialEvent> catEvents,
                                          BigDecimal currentFact,
                                          BigDecimal plannedLimit,
                                          boolean hasPlans,
                                          int daysElapsed,
                                          int daysInMonth,
                                          LocalDate today) {
        if (hasPlans) {
            // Hybrid B: fact + remaining future plan events
            BigDecimal remainingPlans = catEvents.stream()
                    .filter(e -> e.getEventKind() == EventKind.PLAN)
                    .filter(e -> e.getDate() != null && e.getDate().isAfter(today))
                    .map(e -> e.getPlannedAmount() != null ? e.getPlannedAmount() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            return currentFact.add(remainingPlans);
        }

        // Linear A — guard against daysElapsed=0 or no facts
        if (daysElapsed == 0 || currentFact.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        int remainingDays = daysInMonth - daysElapsed;
        BigDecimal dailyRate = currentFact.divide(
                BigDecimal.valueOf(daysElapsed), 4, RoundingMode.HALF_UP);
        return currentFact.add(dailyRate.multiply(BigDecimal.valueOf(remainingDays)))
                .setScale(0, RoundingMode.HALF_UP);
    }

    private List<DailyForecastPointDto> buildHistory(List<FinancialEvent> catEvents,
                                                      boolean hasPlans,
                                                      int daysElapsed,
                                                      int daysInMonth,
                                                      LocalDate today) {
        List<DailyForecastPointDto> points = new ArrayList<>();
        LocalDate monthStart = today.withDayOfMonth(1);

        for (int d = 1; d <= daysElapsed; d++) {
            LocalDate dayDate = monthStart.withDayOfMonth(d);

            BigDecimal factOnDay = catEvents.stream()
                    .filter(e -> e.getEventKind() == EventKind.FACT)
                    .filter(e -> e.getDate() != null && !e.getDate().isAfter(dayDate))
                    .map(e -> e.getFactAmount() != null ? e.getFactAmount() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal projOnDay;
            if (hasPlans) {
                BigDecimal remainingAfterD = catEvents.stream()
                        .filter(e -> e.getEventKind() == EventKind.PLAN)
                        .filter(e -> e.getDate() != null && e.getDate().isAfter(dayDate))
                        .map(e -> e.getPlannedAmount() != null ? e.getPlannedAmount() : BigDecimal.ZERO)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                projOnDay = factOnDay.add(remainingAfterD);
            } else if (factOnDay.compareTo(BigDecimal.ZERO) == 0) {
                projOnDay = BigDecimal.ZERO;
            } else {
                int remaining = daysInMonth - d;
                BigDecimal dailyRate = factOnDay.divide(
                        BigDecimal.valueOf(d), 4, RoundingMode.HALF_UP);
                projOnDay = factOnDay.add(dailyRate.multiply(BigDecimal.valueOf(remaining)))
                        .setScale(0, RoundingMode.HALF_UP);
            }

            points.add(new DailyForecastPointDto(d, factOnDay, projOnDay));
        }

        return points;
    }

    private BigDecimal sumFacts(List<FinancialEvent> events) {
        return events.stream()
                .filter(e -> e.getEventKind() == EventKind.FACT)
                .map(e -> e.getFactAmount() != null ? e.getFactAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal sumAllPlans(List<FinancialEvent> events) {
        return events.stream()
                .filter(e -> e.getEventKind() == EventKind.PLAN)
                .map(e -> e.getPlannedAmount() != null ? e.getPlannedAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
```

The exact method name is confirmed: `eventRepository.findAllByDeletedFalseAndDateBetween(start, end)` — it exists in `FinancialEventRepository.java`.

**API design note:** The spec declares `forecastMonth(YearMonth)` and `forecastHistory(...)`. In this implementation, `forecastFromEvents(List<FinancialEvent>, LocalDate)` is an additional public method that lets callers reuse already-fetched events, avoiding double queries. The standalone `forecastMonth()` delegates to it after fetching. `forecastHistory` logic is inlined into the private `buildHistory()` method called by `forecast()`.

- [ ] **Step 4: Run tests — verify they pass**

```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -Dtest=PredictionServiceTest
```

Expected: all 6 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/ru/selfin/backend/service/PredictionService.java \
        backend/src/test/java/ru/selfin/backend/service/PredictionServiceTest.java
git commit -m "feat: add PredictionService with hybrid forecast algorithm and tests"
```

---

## Chunk 3: Dashboard Integration

### Task 5: Extend `DashboardDto.CategoryProgressBar`

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/dto/DashboardDto.java`

- [ ] **Step 1: Add forecast fields to inner record**

In `DashboardDto.java`, add these two imports at the top of the file (with the other imports):

```java
import ru.selfin.backend.dto.DailyForecastPointDto;
import java.util.List;
```

Then find the inner record `CategoryProgressBar` and replace it:

```java
// Before:
record CategoryProgressBar(
    String categoryName,
    BigDecimal currentFact,
    BigDecimal plannedLimit,
    int percentage
)

// After:
record CategoryProgressBar(
    String categoryName,
    BigDecimal currentFact,
    BigDecimal plannedLimit,
    int percentage,
    BigDecimal projectionAmount,   // null if forecastEnabled = false
    boolean forecastEnabled,
    List<DailyForecastPointDto> history  // empty if forecastEnabled = false
)
```

- [ ] **Step 2: Fix compilation — update all `CategoryProgressBar` constructors**

The only call site is in `DashboardService.buildProgressBars()`. It currently creates:
```java
new DashboardDto.CategoryProgressBar(entry.getKey(), fact, planned, pct)
```
This will now fail to compile. Step 3 in the next task fixes that. For now just verify the compile error is only in one place:

```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw compile -pl backend 2>&1 | grep "error:"
```

Expected: one compile error in `DashboardService.java`.

- [ ] **Step 3: Commit DTO change**

```bash
git add backend/src/main/java/ru/selfin/backend/dto/DashboardDto.java
git commit -m "feat: extend CategoryProgressBar with forecast fields"
```

---

### Task 6: Update `DashboardService.buildProgressBars()`

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/service/DashboardService.java`
- Modify: `backend/src/test/java/ru/selfin/backend/service/DashboardServiceTest.java`

- [ ] **Step 1: Write failing tests for forecast integration**

**First**, read `DashboardServiceTest.java` to find its `setUp()` method and how `dashboardService` is constructed (it likely uses `new DashboardService(eventRepository, checkpointRepository)` or similar). You must add `predictionService` to the constructor call and to the class's `@Mock` fields.

Add to the test class's mock declarations:
```java
@Mock
private PredictionService predictionService;
```

Update `setUp()` to pass `predictionService` to `DashboardService` constructor — the new signature will be `DashboardService(eventRepository, checkpointRepository, predictionService)`.

Then add two new test methods:

```java
@Test
void buildProgressBars_forecastEnabledCategory_includesProjection() {
    LocalDate today = LocalDate.of(2026, 4, 8);

    Category food = Category.builder()
            .id(UUID.randomUUID()).name("Еда").type(CategoryType.EXPENSE)
            .priority(Priority.MEDIUM).forecastEnabled(true).deleted(false).build();

    FinancialEvent plan = FinancialEvent.builder()
            .id(UUID.randomUUID()).category(food).date(LocalDate.of(2026, 4, 15))
            .eventKind(EventKind.PLAN).plannedAmount(new BigDecimal("20000")).deleted(false).build();
    FinancialEvent fact = FinancialEvent.builder()
            .id(UUID.randomUUID()).category(food).date(LocalDate.of(2026, 4, 5))
            .eventKind(EventKind.FACT).factAmount(new BigDecimal("15000")).deleted(false).build();

    // Stub PredictionService — returns a forecast for "Еда" with projection 35000
    CategoryForecastDto catForecast = new CategoryForecastDto(
            "Еда", new BigDecimal("15000"), new BigDecimal("20000"),
            new BigDecimal("35000"), List.of());
    when(predictionService.forecastFromEvents(any(), eq(today)))
            .thenReturn(new MonthlyForecastDto(List.of(catForecast), BigDecimal.ZERO));

    List<DashboardDto.CategoryProgressBar> bars = dashboardService.buildProgressBars(
            List.of(plan, fact), today);

    assertThat(bars).hasSize(1);
    DashboardDto.CategoryProgressBar bar = bars.get(0);
    assertThat(bar.forecastEnabled()).isTrue();
    assertThat(bar.projectionAmount()).isEqualByComparingTo(new BigDecimal("35000"));
}

@Test
void buildProgressBars_forecastDisabledCategory_noProjection() {
    LocalDate today = LocalDate.of(2026, 4, 8);

    Category rent = Category.builder()
            .id(UUID.randomUUID()).name("Аренда").type(CategoryType.EXPENSE)
            .priority(Priority.HIGH).forecastEnabled(false).deleted(false).build();

    FinancialEvent plan = FinancialEvent.builder()
            .id(UUID.randomUUID()).category(rent).date(LocalDate.of(2026, 4, 1))
            .eventKind(EventKind.PLAN).plannedAmount(new BigDecimal("30000")).deleted(false).build();

    // Аренда has forecastEnabled=false so PredictionService returns no categories for it
    when(predictionService.forecastFromEvents(any(), eq(today)))
            .thenReturn(new MonthlyForecastDto(List.of(), BigDecimal.ZERO));

    List<DashboardDto.CategoryProgressBar> bars = dashboardService.buildProgressBars(
            List.of(plan), today);

    assertThat(bars).hasSize(1);
    DashboardDto.CategoryProgressBar bar = bars.get(0);
    assertThat(bar.forecastEnabled()).isFalse();
    assertThat(bar.projectionAmount()).isNull();
    assertThat(bar.history()).isEmpty();
}
```

Add these imports to `DashboardServiceTest.java`:
```java
import ru.selfin.backend.dto.CategoryForecastDto;
import ru.selfin.backend.dto.MonthlyForecastDto;
import ru.selfin.backend.service.PredictionService;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -Dtest=DashboardServiceTest
```

Expected: compilation error — `buildProgressBars` signature mismatch.

- [ ] **Step 3: Update `DashboardService`**

Add these imports to `DashboardService.java`:
```java
import ru.selfin.backend.dto.CategoryForecastDto;
import ru.selfin.backend.dto.DailyForecastPointDto;
import ru.selfin.backend.dto.MonthlyForecastDto;
import ru.selfin.backend.service.PredictionService;
import java.time.LocalDate;
import java.util.Locale;
import java.text.Collator;
```

1. Add `PredictionService` injection (Lombok `@RequiredArgsConstructor` handles this automatically — add it as a class-level field alongside existing fields):

```java
private final PredictionService predictionService;
```

2. Change `buildProgressBars` signature to accept `LocalDate`:

```java
// Before:
private List<DashboardDto.CategoryProgressBar> buildProgressBars(List<FinancialEvent> events)

// After:
List<DashboardDto.CategoryProgressBar> buildProgressBars(List<FinancialEvent> events, LocalDate today)
```

(Make it package-private `List<...>` instead of `private` so the test can call it directly.)

3. **Replace the entire `buildProgressBars` method body** with the following implementation (do not add to the existing method — overwrite it completely):

```java
List<DashboardDto.CategoryProgressBar> buildProgressBars(List<FinancialEvent> events, LocalDate today) {
    MonthlyForecastDto forecast = predictionService.forecastFromEvents(events, today);
    Map<String, CategoryForecastDto> forecastByCategory = forecast.categories().stream()
            .collect(Collectors.toMap(CategoryForecastDto::categoryName, f -> f));

    Map<String, List<FinancialEvent>> byCategory = events.stream()
            .filter(e -> e.getType() == EventType.EXPENSE)
            .collect(Collectors.groupingBy(e -> e.getCategory().getName()));

    Collator collator = Collator.getInstance(new Locale("ru", "RU"));
    return byCategory.entrySet().stream()
            .sorted((a, b) -> collator.compare(a.getKey(), b.getKey()))
            .map(entry -> {
                List<FinancialEvent> catEvents = entry.getValue();
                BigDecimal planned = catEvents.stream()
                        .map(e -> e.getPlannedAmount() != null ? e.getPlannedAmount() : ZERO)
                        .reduce(ZERO, BigDecimal::add);
                BigDecimal fact = catEvents.stream()
                        .map(e -> e.getFactAmount() != null ? e.getFactAmount() : ZERO)
                        .reduce(ZERO, BigDecimal::add);
                int pct = planned.compareTo(ZERO) == 0 ? 0
                        : fact.multiply(BigDecimal.valueOf(100))
                              .divide(planned, 0, RoundingMode.HALF_UP).intValue();

                CategoryForecastDto catForecast = forecastByCategory.get(entry.getKey());
                boolean forecastEnabled = catForecast != null;
                BigDecimal projection = forecastEnabled ? catForecast.projectionAmount() : null;
                List<DailyForecastPointDto> history = forecastEnabled
                        ? catForecast.history() : List.of();

                return new DashboardDto.CategoryProgressBar(
                        entry.getKey(), fact, planned, pct,
                        projection, forecastEnabled, history);
            })
            .toList();
}
```

4. Update the call site in `getDashboard()` to pass `asOfDate`:
```java
// Before:
buildProgressBars(events)
// After:
buildProgressBars(events, asOfDate)
```

- [ ] **Step 4: Run tests**

```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -Dtest=DashboardServiceTest
```

Expected: all tests PASS including the two new ones.

- [ ] **Step 5: Run full test suite**

```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test
```

Expected: all tests PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/ru/selfin/backend/service/DashboardService.java \
        backend/src/test/java/ru/selfin/backend/service/DashboardServiceTest.java
git commit -m "feat: integrate PredictionService into DashboardService progress bars"
```

---

### Task 7: New `/api/v1/analytics/forecast` endpoint

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/controller/AnalyticsController.java`

- [ ] **Step 1: Add endpoint to `AnalyticsController`**

The controller already has `DashboardService` injected. Add `PredictionService` as a **class-level field** (alongside the existing `dashboardService` field — not inside a method), then add a new endpoint method:

```java
// Class-level field — add alongside existing fields:
private final PredictionService predictionService;

@GetMapping("/forecast")
public MonthlyForecastDto getForecast(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
    LocalDate today = date != null ? date : LocalDate.now();
    YearMonth month = YearMonth.from(today);
    return predictionService.forecastMonth(month, today);
}
```

Add imports: `MonthlyForecastDto`, `PredictionService`, `YearMonth`.

- [ ] **Step 2: Verify endpoint works**

```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw spring-boot:run
```

In another terminal:
```bash
curl -s "http://localhost:8080/api/v1/analytics/forecast" | head -c 500
```

Expected: JSON response with `{"categories":[...],"netPredictionDelta":...}`.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/ru/selfin/backend/controller/AnalyticsController.java
git commit -m "feat: add GET /api/v1/analytics/forecast endpoint"
```

---

## Chunk 4: Funds Integration

### Task 8: Extend `FundsOverviewDto` and update `TargetFundService`

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/dto/FundsOverviewDto.java`
- Modify: `backend/src/main/java/ru/selfin/backend/service/TargetFundService.java`
- Modify: `backend/src/test/java/ru/selfin/backend/service/TargetFundServiceTest.java`

- [ ] **Step 1: Extend `FundsOverviewDto`**

Replace the record fields:

```java
import ru.selfin.backend.dto.TargetFundDto;
import java.math.BigDecimal;
import java.util.List;

public record FundsOverviewDto(
        BigDecimal pocketBalance,
        List<TargetFundDto> funds,
        BigDecimal predictionAdjustedPocket,  // null when delta < 100 ₽ (effectively zero)
        List<String> forecastContributors     // e.g. ["Прочее (+4к)", "Транспорт (+3.5к)"]
) {}
```

- [ ] **Step 2: Write failing tests**

The existing `TargetFundServiceTest` uses plain `mock()` (not `@Mock` annotations), and constructs `TargetFundService` manually in `setUp()`. You must:
1. Add `private PredictionService predictionService;` to the field list
2. Add `predictionService = mock(PredictionService.class);` in `setUp()`
3. Update the `service = new TargetFundService(...)` call to include `predictionService` as the last argument

Add these imports:
```java
import ru.selfin.backend.dto.CategoryForecastDto;
import ru.selfin.backend.dto.MonthlyForecastDto;
import ru.selfin.backend.service.PredictionService;
```

Then add the two new test methods:

```java
@Test
@DisplayName("Кармашек: linear category extrapolation adds predictionAdjustedPocket when delta >= 100")
void getOverview_withLinearCategory_includesPredictionAdjustedPocket() {
    when(checkpointRepository.findTopByOrderByDateDesc()).thenReturn(Optional.empty());
    when(fundRepository.findAllByDeletedFalseOrderByPriorityAsc()).thenReturn(List.of());
    when(eventRepository.sumFactExecutedByType(EventType.INCOME)).thenReturn(bd(100_000));
    when(eventRepository.sumFactExecutedByType(EventType.EXPENSE)).thenReturn(bd(30_000));
    when(eventRepository.sumAllFactByType(EventType.FUND_TRANSFER)).thenReturn(BigDecimal.ZERO);

    // Month events: empty list → afterAllExpenses = pocketBalance + 0 - 0 = 70_000
    when(eventRepository.findAllByDeletedFalseAndDateBetween(any(), any())).thenReturn(List.of());

    // Linear category contributes delta = 10_000
    CategoryForecastDto linearCat = new CategoryForecastDto(
            "Прочее", new BigDecimal("5000"), BigDecimal.ZERO,
            new BigDecimal("15000"), List.of());
    when(predictionService.forecastFromEvents(any(), any()))
            .thenReturn(new MonthlyForecastDto(List.of(linearCat), new BigDecimal("10000")));

    FundsOverviewDto overview = service.getOverview();

    // pocketBalance = 70_000; afterAllExpenses = 70_000 (no future planned events)
    // adjustedPocket = 70_000 - 10_000 = 60_000
    assertThat(overview.predictionAdjustedPocket()).isNotNull();
    assertThat(overview.predictionAdjustedPocket()).isEqualByComparingTo(new BigDecimal("60000"));
    assertThat(overview.forecastContributors()).contains("Прочее (+10к)");
}

@Test
@DisplayName("Кармашек: delta < 100 → predictionAdjustedPocket is null")
void getOverview_allPlanBased_predictionAdjustedPocketIsNull() {
    when(checkpointRepository.findTopByOrderByDateDesc()).thenReturn(Optional.empty());
    when(fundRepository.findAllByDeletedFalseOrderByPriorityAsc()).thenReturn(List.of());
    when(eventRepository.sumFactExecutedByType(any())).thenReturn(BigDecimal.ZERO);
    when(eventRepository.sumAllFactByType(any())).thenReturn(BigDecimal.ZERO);
    when(eventRepository.findAllByDeletedFalseAndDateBetween(any(), any())).thenReturn(List.of());

    // All plan-based → delta = 0 (below 100 threshold)
    when(predictionService.forecastFromEvents(any(), any()))
            .thenReturn(new MonthlyForecastDto(List.of(), BigDecimal.ZERO));

    FundsOverviewDto overview = service.getOverview();

    assertThat(overview.predictionAdjustedPocket()).isNull();
    assertThat(overview.forecastContributors()).isEmpty();
}
```

- [ ] **Step 3: Update `TargetFundService.getOverview()`**

1. Add injection (note: `eventRepository` **already exists** in `TargetFundService` — do NOT add it again; only add `predictionService`):
```java
private final PredictionService predictionService;
```

Add these imports:
```java
import ru.selfin.backend.dto.MonthlyForecastDto;
import ru.selfin.backend.model.EventKind;
import ru.selfin.backend.service.PredictionService;
import java.time.YearMonth;
import java.util.List;
```

2. In `getOverview()`, after computing `pocketBalance`, add:

```java
LocalDate today = LocalDate.now();
YearMonth currentMonth = YearMonth.from(today);
LocalDate monthStart = currentMonth.atDay(1);
LocalDate monthEnd = currentMonth.atEndOfMonth();

// eventRepository is the existing field — confirmed as FinancialEventRepository
// Method name confirmed: findAllByDeletedFalseAndDateBetween
List<FinancialEvent> monthEvents = eventRepository
        .findAllByDeletedFalseAndDateBetween(monthStart, monthEnd);

// Compute afterAllExpenses: pocket + future planned income - future planned expenses
// This is the end-of-month projection (not current pocketBalance — it includes uncommitted future plans)
BigDecimal futureIncome = monthEvents.stream()
        .filter(e -> e.getEventKind() == EventKind.PLAN
                && e.getType() == EventType.INCOME
                && e.getDate() != null && !e.getDate().isBefore(today))
        .map(e -> e.getPlannedAmount() != null ? e.getPlannedAmount() : BigDecimal.ZERO)
        .reduce(BigDecimal.ZERO, BigDecimal::add);

BigDecimal futureExpenses = monthEvents.stream()
        .filter(e -> e.getEventKind() == EventKind.PLAN
                && e.getType() == EventType.EXPENSE
                && e.getDate() != null && !e.getDate().isBefore(today))
        .map(e -> e.getPlannedAmount() != null ? e.getPlannedAmount() : BigDecimal.ZERO)
        .reduce(BigDecimal.ZERO, BigDecimal::add);

// afterAllExpenses = end-of-month projection of the pocket:
// current pocketBalance + remaining planned income - remaining planned expenses.
// This is NOT the same as pocketBalance — pocketBalance reflects only executed facts so far.
BigDecimal afterAllExpenses = pocketBalance.add(futureIncome).subtract(futureExpenses);

// Prediction delta — only linear (unplanned) categories contribute
MonthlyForecastDto forecast = predictionService.forecastFromEvents(monthEvents, today);
BigDecimal delta = forecast.netPredictionDelta();

BigDecimal adjustedPocket = null;
List<String> contributors = List.of();

if (delta.compareTo(new BigDecimal("100")) >= 0) {
    adjustedPocket = afterAllExpenses.subtract(delta);
    contributors = buildContributors(forecast);
}
```

3. Add the private helper:

```java
private List<String> buildContributors(MonthlyForecastDto forecast) {
    return forecast.categories().stream()
            .filter(c -> {
                // Only linear categories contribute to delta
                boolean hasPlans = c.plannedLimit().compareTo(BigDecimal.ZERO) > 0;
                return !hasPlans && c.projectionAmount().compareTo(c.currentFact()) > 0;
            })
            .map(c -> {
                BigDecimal extra = c.projectionAmount().subtract(c.currentFact());
                String formatted = formatK(extra);
                return c.categoryName() + " (+" + formatted + ")";
            })
            .toList();
}

private String formatK(BigDecimal amount) {
    long rubles = amount.longValue();
    if (rubles >= 1000) {
        long thousands = Math.round(rubles / 1000.0);
        return thousands + "к";
    }
    return String.valueOf(rubles) + "₽";
}
```

4. Pass the new fields when constructing `FundsOverviewDto`:
```java
// Before:
return new FundsOverviewDto(pocketBalance, funds);
// After:
return new FundsOverviewDto(pocketBalance, funds, adjustedPocket, contributors);
```

- [ ] **Step 4: Run tests**

```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test
```

Expected: all tests PASS. Fix any `FundsOverviewDto` constructor call sites.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/ru/selfin/backend/dto/FundsOverviewDto.java \
        backend/src/main/java/ru/selfin/backend/service/TargetFundService.java \
        backend/src/test/java/ru/selfin/backend/service/TargetFundServiceTest.java
git commit -m "feat: add predictionAdjustedPocket to FundsOverview"
```

---

## Chunk 5: Frontend

### Task 9: Extend TypeScript types

**Files:**
- Modify: `frontend/src/types/api.ts`

- [ ] **Step 1: Add `DailyForecastPoint` and extend `CategoryProgressBar`**

In `api.ts`, add:

```typescript
export interface DailyForecastPoint {
  day: number;           // day-of-month, 1-based
  cumulativeFact: number;
  projectedTotal: number;
}
```

Extend `CategoryProgressBar`:

```typescript
export interface CategoryProgressBar {
  categoryName: string;
  currentFact: number;
  plannedLimit: number;
  percentage: number;
  // forecast fields (present when forecastEnabled = true)
  projectionAmount: number | null;
  forecastEnabled: boolean;
  history: DailyForecastPoint[];
}
```

Extend `FundsOverview`:

```typescript
export interface FundsOverview {
  pocketBalance: number;
  funds: TargetFund[];
  predictionAdjustedPocket: number | null;
  forecastContributors: string[];
}
```

- [ ] **Step 2: TypeScript check**

```bash
cd frontend && npx tsc --noEmit
```

Expected: no errors. Fix any type mismatches that arise from the new required fields.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/types/api.ts
git commit -m "feat: extend TypeScript types for forecast fields"
```

---

### Task 10: Dashboard progress bars UI

**Files:**
- Modify: `frontend/src/pages/Dashboard.tsx`

- [ ] **Step 1: Add bar scaling helper**

In `Dashboard.tsx`, add a helper function near the top (after existing `getBarState`):

```typescript
function getBarMax(plannedLimit: number, projection: number | null): number {
  if (!projection || projection <= plannedLimit) return plannedLimit;
  return Math.max(plannedLimit * 1.25, projection * 1.1);
}
```

- [ ] **Step 2: Add sparkline component**

Add a `ForecastSparkline` component in `Dashboard.tsx` (before the main component):

```typescript
interface SparklineProps {
  history: DailyForecastPoint[];
  plannedLimit: number;
  projectionAmount: number;
  daysInMonth: number;
}

function ForecastSparkline({ history, plannedLimit, projectionAmount, daysInMonth }: SparklineProps) {
  if (history.length === 0) return null;

  const W = 180, H = 65;
  const PAD = 5;
  const xRange = W - PAD * 2;
  const yRange = H - PAD * 2;

  const maxValue = Math.max(plannedLimit, projectionAmount, ...history.map(p => p.projectedTotal)) * 1.05;

  const x = (day: number) => PAD + ((day - 1) / (daysInMonth - 1)) * xRange;
  const y = (val: number) => H - PAD - (val / maxValue) * yRange;

  const today = history[history.length - 1];
  const todayX = x(today.day);

  const factPoints = history.map(p => `${x(p.day)},${y(p.cumulativeFact)}`).join(' ');
  const projPoints = history.map(p => `${x(p.day)},${y(p.projectedTotal)}`).join(' ');
  const futureEndX = x(daysInMonth);
  const futureEndY = y(projectionAmount);
  const planY = y(plannedLimit);

  return (
    <div className="bg-[#0e0e1e] border border-[#2a2a3a] rounded-lg p-3 mt-2 w-[220px]">
      <div className="text-[10px] text-muted-foreground mb-1">Динамика месяца</div>
      <svg width={W} height={H} viewBox={`0 0 ${W} ${H}`}>
        {/* Plan line */}
        <line x1={0} y1={planY} x2={W} y2={planY}
          stroke="rgba(255,255,255,0.2)" strokeWidth={1} strokeDasharray="4 3" />
        {/* Fact line */}
        <polyline points={factPoints} fill="none" stroke="#6c63ff" strokeWidth={2} strokeLinejoin="round" />
        {/* Projection history line */}
        <polyline points={projPoints} fill="none" stroke="#ffaa44" strokeWidth={1.5}
          strokeDasharray="3 3" strokeLinejoin="round" />
        {/* Future projection */}
        <line x1={todayX} y1={y(today.cumulativeFact)} x2={futureEndX} y2={futureEndY}
          stroke="#ff5a5a" strokeWidth={1.5} strokeDasharray="4 4" opacity={0.8} />
        {/* Today marker */}
        <line x1={todayX} y1={0} x2={todayX} y2={H}
          stroke="rgba(108,99,255,0.4)" strokeWidth={1} strokeDasharray="2 2" />
      </svg>
      <div className="flex justify-between text-[10px] text-muted-foreground mt-1">
        <span>1</span><span>{daysInMonth}</span>
      </div>
      <div className="flex gap-3 mt-1.5 flex-wrap">
        {[
          { color: '#6c63ff', label: 'факт', dashed: false },
          { color: '#ffaa44', label: 'прогноз по дням', dashed: true },
          { color: '#ff5a5a', label: 'прогноз вперёд', dashed: true },
        ].map(({ color, label, dashed }) => (
          <div key={label} className="flex items-center gap-1">
            <div className="w-3.5 h-0.5 rounded" style={{
              background: dashed
                ? `repeating-linear-gradient(90deg,${color} 0,${color} 3px,transparent 3px,transparent 6px)`
                : color
            }} />
            <span className="text-[10px] text-muted-foreground">{label}</span>
          </div>
        ))}
      </div>
    </div>
  );
}
```

- [ ] **Step 3: Update the progress bar rendering loop**

Find the section in `Dashboard.tsx` where `data.progressBars` is mapped (currently renders `categoryName`, amounts, bar, and transaction list). Update it:

```typescript
{data.progressBars.map((bar) => {
  const barMax = getBarMax(bar.plannedLimit, bar.projectionAmount);
  const factPct = barMax > 0 ? (bar.currentFact / barMax) * 100 : 0;
  const planMarkerPct = barMax > 0 ? (bar.plannedLimit / barMax) * 100 : 100;
  const needlePct = bar.forecastEnabled && bar.projectionAmount && barMax > 0
    ? (bar.projectionAmount / barMax) * 100 : null;
  const isOverspend = bar.projectionAmount != null && bar.projectionAmount > bar.plannedLimit;
  const daysInMonth = new Date(
    new Date().getFullYear(), new Date().getMonth() + 1, 0
  ).getDate();

  return (
    <div key={bar.categoryName} className="...existing classes...">
      {/* Amounts row: C2 format */}
      <div className="flex justify-between items-center mb-2">
        <span className="text-sm font-medium text-foreground">{bar.categoryName}</span>
        <span className="text-xs text-muted-foreground">
          {formatAmount(bar.currentFact)} / {formatAmount(bar.plannedLimit)}
          {bar.forecastEnabled && bar.projectionAmount != null && (
            <span className={isOverspend ? 'text-destructive font-semibold ml-1' : 'text-green-400 font-semibold ml-1'}>
              / ~{formatAmount(bar.projectionAmount)}
            </span>
          )}
        </span>
      </div>

      {/* Progress bar with dynamic scale */}
      <div className="relative h-2 bg-secondary rounded-full group">
        {/* Fact fill */}
        <div
          className="absolute left-0 top-0 h-full bg-primary rounded-full"
          style={{ width: `${Math.min(factPct, 100)}%` }}
        />
        {/* Plan marker */}
        <div
          className="absolute top-[-3px] h-[calc(100%+6px)] w-0.5 bg-white/20 rounded-sm z-10"
          style={{ left: `${planMarkerPct}%` }}
        />
        {/* Needle */}
        {needlePct != null && (
          <>
            <div
              className={`absolute top-[-5px] h-[calc(100%+10px)] w-0.5 rounded-sm z-20 ${isOverspend ? 'bg-destructive' : 'bg-green-400'}`}
              style={{ left: `${Math.min(needlePct, 98)}%` }}
            />
            <div
              className={`absolute top-[-3px] w-2 h-2 rounded-full border-2 border-background z-30 ${isOverspend ? 'bg-destructive' : 'bg-green-400'}`}
              style={{ left: `calc(${Math.min(needlePct, 98)}% - 4px)` }}
            />
          </>
        )}

        {/* Hover sparkline tooltip */}
        {bar.forecastEnabled && bar.history.length > 0 && (
          <div className="absolute right-0 top-6 hidden group-hover:block z-50">
            <ForecastSparkline
              history={bar.history}
              plannedLimit={bar.plannedLimit}
              projectionAmount={bar.projectionAmount ?? bar.plannedLimit}
              daysInMonth={daysInMonth}
            />
          </div>
        )}
      </div>

      {/* Keep existing transaction names list unchanged below */}
    </div>
  );
})}
```

Note: Keep all existing status badge logic (`getBarState`, badge rendering, transaction name list) unchanged. Only modify the amounts row and bar visuals.

- [ ] **Step 4: TypeScript check**

```bash
cd frontend && npx tsc --noEmit
```

Expected: no errors.

- [ ] **Step 5: Verify visually**

Start dev server (`npm run dev` in `frontend/`), open the Dashboard page, verify:
- Progress bars with `forecastEnabled=true` show three numbers in amounts row
- Needle appears at projection position
- Hovering over bar reveals sparkline tooltip

- [ ] **Step 6: Commit**

```bash
git add frontend/src/pages/Dashboard.tsx
git commit -m "feat: add prediction needle, dynamic bar scale, and sparkline to progress bars"
```

---

### Task 11: Funds popup — "По текущему темпу" row

**Files:**
- Modify: `frontend/src/pages/Funds.tsx`

- [ ] **Step 1: Add the conditional row to кармашек popup**

In `Funds.tsx`, find the кармашек projection rows section (where "После обязательных расходов" and "После всех расходов" are rendered). After the last projection row, add:

```typescript
{fundsData.predictionAdjustedPocket != null && (
  <div className="mt-2 p-2.5 bg-secondary/30 border border-primary/20 rounded-lg">
    <div className="flex justify-between items-start">
      <span className="text-sm text-primary">По текущему темпу</span>
      <span className={`text-sm font-semibold ml-3 ${
        fundsData.predictionAdjustedPocket < fundsData.pocketBalance
          ? 'text-destructive'
          : 'text-green-400'
      }`}>
        {formatAmount(fundsData.predictionAdjustedPocket)}
      </span>
    </div>
    {fundsData.forecastContributors.length > 0 && (
      <p className="text-xs text-muted-foreground mt-1">
        {fundsData.forecastContributors.join(', ')}
      </p>
    )}
  </div>
)}
```

Note: check the exact variable name used for `FundsOverview` data in `Funds.tsx` (may be `overview`, `data`, or `fundsData`) and replace accordingly. Also check the existing `formatAmount` helper name.

- [ ] **Step 2: TypeScript check**

```bash
cd frontend && npx tsc --noEmit
```

- [ ] **Step 3: Verify visually**

Open Funds page. With all categories having plan events, the block should be hidden. To test it appears: temporarily add a fact event to a category with no plans in the DB, then check the block shows.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/pages/Funds.tsx
git commit -m "feat: show prediction-adjusted pocket balance in Funds popup"
```

---

## Final Verification

- [ ] **Run full test suite**

```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test
```

Expected: all tests PASS.

- [ ] **TypeScript check**

```bash
cd frontend && npx tsc --noEmit
```

Expected: no errors.

- [ ] **Smoke test the UI**

Start backend + frontend, open Dashboard and Funds pages. Verify:
1. Progress bars for variable expense categories show `~Xк` projection in amounts row
2. Needle appears at projected position with correct colour
3. Hovering shows sparkline
4. Mandatory category bars unchanged (no needle, no third number)
5. Funds popup shows "По текущему темпу" only when there's a meaningful delta
