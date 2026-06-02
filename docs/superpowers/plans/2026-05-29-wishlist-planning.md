# Wishlist Planning Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a `/wishlist` page where the user manages wishlist items, savings funds and credit funds as one list of "big decisions", dragging amount/date sliders to see real-time impact on account balance and capital, with green/yellow/red risk zones.

**Architecture:** Backend adds `wishlist_status` + conversion FK columns to `financial_events` and `target_funds` (migration V18), a new `user_settings` table, and three services: `BaselineTimelineBuilder` (extracted from `StrategyTimelineService` to break a circular dependency), `WishlistSimulationService` (baseline + per-item delta vectors), `WishlistConversionService` (OPEN→FIXED→artifact), `UserSettingsService` (thresholds). A single `GET /api/v1/wishlist/simulation` returns baseline + items + deltas; the frontend composes `baseline + Σ active deltas` locally for instant slider feedback. `StrategyTimelineService` is refactored into a thin coordinator that adds FIXED-item deltas onto the baseline.

**Tech Stack:** Spring Boot 4.0.3, Java 21, PostgreSQL 15, Flyway, JPA/Hibernate, Lombok, JUnit 5 + Mockito + Testcontainers (backend); React 18, TypeScript, Vite, Tailwind, Shadcn UI, lucide-react, recharts, vitest (frontend).

**Spec:** `docs/superpowers/specs/2026-05-29-wishlist-planning-design.md`

**Test commands** (per user CLAUDE.md, prefix with `rtk`; fall back to bare command if `rtk` not available in the shell):
- Backend single class: `rtk JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -Dtest=<Class>` (from `backend/`)
- Backend unit-only (skip Docker ITs): `rtk JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -Dtest='!*IT'`
- Backend compile: `rtk JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw compile`
- Frontend typecheck: `cd frontend && npx tsc --noEmit`
- Frontend unit: `cd frontend && npx vitest run <path>`
- Frontend build: `cd frontend && rtk npm run build`

> **Docker note:** Testcontainers-based ITs (`*IT.java`) require Docker. On the user's Windows machine the Docker Desktop named-pipe is currently broken, so ITs can't run locally — they pass in CI on Linux. When an IT chunk can't run locally, compile it (`mvnw test-compile`), code-review it, commit it, and note the deferred run. Do NOT skip writing ITs.

---

## File Map

### Backend — create

| File | Responsibility |
|---|---|
| `backend/src/main/resources/db/migration/V18__add_wishlist_planning.sql` | Add `wishlist_status` + `converted_to_event_id` + `converted_to_fund_id` to `financial_events` and `target_funds`; create `user_settings`; backfill |
| `model/enums/WishlistStatus.java` | Enum OPEN/FIXED/DISMISSED |
| `model/UserSettings.java` | JPA entity for key/value settings |
| `repository/UserSettingsRepository.java` | `findBySettingsKey` |
| `service/BaselineTimelineBuilder.java` | Pure timeline builder (account + capital + fan), no wishlist dependency — extracted from `StrategyTimelineService` |
| `service/WishlistSimulationService.java` | Baseline + per-item delta vectors; `computeDeltaForItem` reused by `StrategyTimelineService` |
| `service/WishlistConversionService.java` | Status transitions + conversion to PLAN event / fund / fund+recurring |
| `service/UserSettingsService.java` | Get/update wishlist thresholds with lazy init |
| `controller/WishlistController.java` | `GET /simulation`, `POST /simulation/recompute`, `POST /items/{id}/convert` |
| `controller/UserSettingsController.java` | `GET/PUT /settings/wishlist` |
| `dto/wishlist/*.java` | `WishlistSimulationDto`, `WishlistItemDto`, `MonthDeltaDto`, `WishlistThresholdsDto`, `WishlistConstraintsDto`, `ConvertWishlistRequestDto`, `ConvertWishlistResponseDto`, `WishlistStatusUpdateDto`, `RecomputeRequestDto`, `RecomputeResponseDto`, `TimelineSnapshot` (internal record) |

### Backend — modify

| File | Change |
|---|---|
| `model/FinancialEvent.java` | Add `wishlistStatus`, `convertedToEventId`, `convertedToFundId` fields |
| `model/TargetFund.java` | Add `wishlistStatus`, `convertedToEventId`, `convertedToFundId` fields |
| `repository/FinancialEventRepository.java` | `findByPriorityAndWishlistStatusIsNotNull`, `findByWishlistStatus` (FIXED collection) |
| `repository/TargetFundRepository.java` | `findByWishlistStatus`, `findByWishlistStatusAndStatus` |
| `service/StrategyTimelineService.java` | Refactor to delegate baseline to `BaselineTimelineBuilder`; add FIXED-item deltas via `WishlistSimulationService` |
| `service/FinancialEventService.java` | `setWishlistStatus(id, status)` |
| `service/TargetFundService.java` | `setWishlistStatus(id, status)` |
| `controller/FinancialEventController.java` | `PATCH /events/{id}/wishlist-status` |
| `controller/TargetFundController.java` | `PATCH /funds/{id}/wishlist-status` |

### Frontend — create

| File | Responsibility |
|---|---|
| `frontend/src/pages/Wishlist.tsx` | Page: header + chart + item list + states |
| `frontend/src/components/wishlist/WishlistThresholdsHeader.tsx` | Two threshold inputs (debounced PUT) |
| `frontend/src/components/wishlist/WishlistImpactChart.tsx` | ComposedChart with risk-zone backgrounds + threshold line |
| `frontend/src/components/wishlist/WishlistItemList.tsx` | Three collapsible sections (OPEN/FIXED/DISMISSED) |
| `frontend/src/components/wishlist/WishlistItemCard.tsx` | One item: checkbox, sliders, risk badge, fix button |
| `frontend/src/components/wishlist/WishlistRiskBadge.tsx` | Solo-risk color dot |
| `frontend/src/components/wishlist/FixWishlistDialog.tsx` | Conversion dialog |
| `frontend/src/components/wishlist/AddWishlistDialog.tsx` | Create new item dialog |
| `frontend/src/components/wishlist/DeleteWishlistDialog.tsx` | Delete confirm (+ artifact option) |
| `frontend/src/components/wishlist/useWishlistSimulation.ts` | Loads simulation, holds local active/override state |
| `frontend/src/components/wishlist/wishlistUtils.ts` | `composeTimeline`, `riskZones`, `scaleDelta`, formatting helpers |
| `frontend/src/components/wishlist/wishlistUtils.test.ts` | Unit tests for composeTimeline + riskZones |

### Frontend — modify

| File | Change |
|---|---|
| `frontend/src/types/api.ts` | Wishlist DTOs: `WishlistSimulationDto`, `WishlistItem`, `MonthDelta`, `WishlistThresholds`, `WishlistConstraints`, `ConvertRequest/Response`, `WishlistStatus`, `WishlistKind`, `ConvertedTo` |
| `frontend/src/api/index.ts` | `fetchWishlistSimulation`, `recomputeWishlistItem`, `convertWishlistItem`, `setEventWishlistStatus`, `setFundWishlistStatus`, `fetchWishlistSettings`, `updateWishlistSettings` |
| `frontend/src/components/BottomNav.tsx` | Add `/wishlist` nav entry |
| `frontend/src/App.tsx` (or router file) | Route for `/wishlist` |
| `frontend/src/pages/Funds.tsx` | Remove `<WishlistSection>` and `<SavingsStrategySection>` |

### Frontend — delete (Chunk 7)

- `frontend/src/components/WishlistSection.tsx`, `WishlistItem.tsx`, `WishlistForm.tsx`
- `frontend/src/components/funds/SavingsStrategySection.tsx`, `savingsStrategyUtils.ts`

---

## Chunk 1 — Migration V18 + entities + enum + repositories

This chunk lays the data layer. No behavior change to existing flows — only additive columns (all nullable) and one new table. End state: backend compiles, existing tests still green.

### Task 1.1: Flyway migration V18

**Files:**
- Create: `backend/src/main/resources/db/migration/V18__add_wishlist_planning.sql`

- [ ] **Step 1: Create migration file**

```sql
-- V18: wishlist planning — статусы хотелок/копилок/кредитов + конверсия + user_settings

-- 1. wishlist_status + конверсия на financial_events
ALTER TABLE financial_events ADD COLUMN wishlist_status VARCHAR(16);
ALTER TABLE financial_events ADD COLUMN converted_to_event_id UUID REFERENCES financial_events(id) ON DELETE SET NULL;
ALTER TABLE financial_events ADD COLUMN converted_to_fund_id  UUID REFERENCES target_funds(id)    ON DELETE SET NULL;

ALTER TABLE financial_events
    ADD CONSTRAINT chk_wishlist_status_only_low
    CHECK (wishlist_status IS NULL OR priority = 'LOW');
ALTER TABLE financial_events
    ADD CONSTRAINT chk_event_converted_only_fixed
    CHECK ((converted_to_event_id IS NULL AND converted_to_fund_id IS NULL) OR wishlist_status = 'FIXED');
ALTER TABLE financial_events
    ADD CONSTRAINT chk_event_single_conversion
    CHECK (NOT (converted_to_event_id IS NOT NULL AND converted_to_fund_id IS NOT NULL));

CREATE INDEX idx_events_wishlist_status
    ON financial_events (wishlist_status) WHERE wishlist_status IS NOT NULL;

-- 2. wishlist_status + конверсия на target_funds
ALTER TABLE target_funds ADD COLUMN wishlist_status VARCHAR(16);
ALTER TABLE target_funds ADD COLUMN converted_to_event_id UUID REFERENCES financial_events(id) ON DELETE SET NULL;
ALTER TABLE target_funds ADD COLUMN converted_to_fund_id  UUID REFERENCES target_funds(id)    ON DELETE SET NULL;

ALTER TABLE target_funds
    ADD CONSTRAINT chk_fund_converted_only_fixed
    CHECK ((converted_to_event_id IS NULL AND converted_to_fund_id IS NULL) OR wishlist_status = 'FIXED');
ALTER TABLE target_funds
    ADD CONSTRAINT chk_fund_single_conversion
    CHECK (NOT (converted_to_event_id IS NOT NULL AND converted_to_fund_id IS NOT NULL));

CREATE INDEX idx_funds_wishlist_status
    ON target_funds (wishlist_status) WHERE wishlist_status IS NOT NULL;

-- 3. Backfill: активные (FUNDING) копилки/кредиты → FIXED
-- FundStatus enum в коде = {FUNDING, REACHED}.
UPDATE target_funds SET wishlist_status = 'FIXED' WHERE status = 'FUNDING';

-- 4. Backfill: хотелки (LOW без даты) → OPEN
UPDATE financial_events SET wishlist_status = 'OPEN'
WHERE priority = 'LOW' AND date IS NULL AND is_deleted = FALSE;

-- 5. user_settings (key/value JSONB)
CREATE TABLE user_settings (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    settings_key   VARCHAR(64) NOT NULL UNIQUE,
    settings_value JSONB       NOT NULL,
    updated_at     TIMESTAMP   NOT NULL DEFAULT NOW()
);
```

- [ ] **Step 2: Verify migration applies cleanly**

Run: `rtk JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -Dtest=BackendApplicationTests`
Expected: PASS (Flyway runs at context init). If Docker unavailable, run `rtk JAVA_HOME=... ./mvnw compile` and note the deferred verification; the V18 SQL is validated end-to-end in Chunk 4 ITs.

- [ ] **Step 3: Commit**

```bash
rtk git add backend/src/main/resources/db/migration/V18__add_wishlist_planning.sql
rtk git commit -m "feat(db): V18 wishlist_status, conversion FKs, user_settings"
```

---

### Task 1.2: WishlistStatus enum

**Files:**
- Create: `backend/src/main/java/ru/selfin/backend/model/enums/WishlistStatus.java`

- [ ] **Step 1: Create enum**

```java
package ru.selfin.backend.model.enums;

/**
 * Статус item'а в модуле планирования /wishlist.
 *
 * <ul>
 *   <li>{@code OPEN} — на обсуждении; влияет на симуляцию /wishlist, не влияет на /strategy и /budget.</li>
 *   <li>{@code FIXED} — решение принято; влияет на /strategy и /capital (через delta); может иметь конверсию.</li>
 *   <li>{@code DISMISSED} — отклонён; виден в свёрнутой секции, не влияет ни на что.</li>
 * </ul>
 */
public enum WishlistStatus {
    OPEN,
    FIXED,
    DISMISSED
}
```

- [ ] **Step 2: Compile**

Run: `rtk JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
rtk git add backend/src/main/java/ru/selfin/backend/model/enums/WishlistStatus.java
rtk git commit -m "feat(model): add WishlistStatus enum"
```

---

### Task 1.3: Extend FinancialEvent + TargetFund entities

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/model/FinancialEvent.java`
- Modify: `backend/src/main/java/ru/selfin/backend/model/TargetFund.java`

- [ ] **Step 1: Add fields to `FinancialEvent`**

After the `deleted` field (near the end of the class), insert:

```java
    /**
     * Статус item'а в модуле /wishlist. NULL — обычное событие, не хотелка.
     * Не-NULL допустим только при priority=LOW (DB constraint chk_wishlist_status_only_low).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "wishlist_status", length = 16)
    private ru.selfin.backend.model.enums.WishlistStatus wishlistStatus;

    /** Если хотелка сконвертирована в PLAN-событие — ссылка на него. Только при FIXED. */
    @Column(name = "converted_to_event_id")
    private UUID convertedToEventId;

    /** Если хотелка сконвертирована в копилку/кредит — ссылка на TargetFund. Только при FIXED. */
    @Column(name = "converted_to_fund_id")
    private UUID convertedToFundId;
```

- [ ] **Step 2: Add fields to `TargetFund`**

After the `creditTermMonths` field, insert:

```java
    /** Статус в модуле /wishlist. NULL — обычная копилка/кредит вне планирования. */
    @Enumerated(EnumType.STRING)
    @Column(name = "wishlist_status", length = 16)
    private ru.selfin.backend.model.enums.WishlistStatus wishlistStatus;

    /** Конверсия копилки в PLAN-событие (редко; для симметрии модели). Только при FIXED. */
    @Column(name = "converted_to_event_id")
    private UUID convertedToEventId;

    /** Конверсия копилки в другую копилку/кредит. Только при FIXED. */
    @Column(name = "converted_to_fund_id")
    private UUID convertedToFundId;
```

- [ ] **Step 3: Compile**

Run: `rtk JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Run full test suite (no regression — new columns are nullable)**

Run: `rtk JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -Dtest='!*IT'`
Expected: all PASS (1 pre-existing `BackendApplicationTests.contextLoads` Docker error is acceptable and unrelated).

- [ ] **Step 5: Commit**

```bash
rtk git add backend/src/main/java/ru/selfin/backend/model/FinancialEvent.java backend/src/main/java/ru/selfin/backend/model/TargetFund.java
rtk git commit -m "feat(model): add wishlist_status + conversion FKs to FinancialEvent and TargetFund"
```

---

### Task 1.4: UserSettings entity + repository

**Files:**
- Create: `backend/src/main/java/ru/selfin/backend/model/UserSettings.java`
- Create: `backend/src/main/java/ru/selfin/backend/repository/UserSettingsRepository.java`

- [ ] **Step 1: Create entity**

```java
package ru.selfin.backend.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Простое key/value-хранилище настроек (single-user).
 * {@code settingsValue} — произвольный JSON, маппится как String; парсинг в сервисе.
 */
@Entity
@Table(name = "user_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "settings_key", nullable = false, unique = true, length = 64)
    private String settingsKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "settings_value", nullable = false, columnDefinition = "jsonb")
    private String settingsValue;

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
```

> Note: `@JdbcTypeCode(SqlTypes.JSON)` on a `String` field maps a JSONB column to a raw JSON string. The service layer parses/serializes via Jackson `ObjectMapper`. This keeps the entity dumb and the schema flexible. Verify `org.hibernate.type.SqlTypes` import resolves (Hibernate 6, bundled with Spring Boot 4).

- [ ] **Step 2: Create repository**

```java
package ru.selfin.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.selfin.backend.model.UserSettings;

import java.util.Optional;
import java.util.UUID;

public interface UserSettingsRepository extends JpaRepository<UserSettings, UUID> {
    Optional<UserSettings> findBySettingsKey(String settingsKey);
}
```

- [ ] **Step 3: Compile**

Run: `rtk JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
rtk git add backend/src/main/java/ru/selfin/backend/model/UserSettings.java backend/src/main/java/ru/selfin/backend/repository/UserSettingsRepository.java
rtk git commit -m "feat(model): UserSettings entity + repository"
```

---

### Task 1.5: Repository query methods for wishlist items

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/repository/FinancialEventRepository.java`
- Modify: `backend/src/main/java/ru/selfin/backend/repository/TargetFundRepository.java`

- [ ] **Step 1: Add methods to `FinancialEventRepository`**

Add at end of interface, under a `// --- Wishlist ---` comment:

```java
    // --- Wishlist ---

    /** Все хотелки (LOW-события с явным статусом). Для страницы /wishlist. */
    @Query("SELECT e FROM FinancialEvent e " +
           "WHERE e.priority = ru.selfin.backend.model.enums.Priority.LOW " +
           "  AND e.wishlistStatus IS NOT NULL " +
           "  AND e.deleted = false")
    List<FinancialEvent> findAllWishlistEvents();

    /** Хотелки-события с конкретным статусом (например FIXED для timeline). */
    List<FinancialEvent> findByWishlistStatusAndDeletedFalse(
            ru.selfin.backend.model.enums.WishlistStatus status);
```

Ensure imports include `ru.selfin.backend.model.enums.WishlistStatus` is reachable (FQN used above avoids a new import; keep consistent with existing file style which uses FQN for `Priority`).

- [ ] **Step 2: Add methods to `TargetFundRepository`**

```java
    // --- Wishlist ---

    List<TargetFund> findByWishlistStatusAndDeletedFalse(
            ru.selfin.backend.model.enums.WishlistStatus status);

    /** Все копилки/кредиты с явным wishlist-статусом. Для страницы /wishlist. */
    @Query("SELECT f FROM TargetFund f WHERE f.wishlistStatus IS NOT NULL AND f.deleted = false")
    List<TargetFund> findAllWishlistFunds();
```

Verify `TargetFundRepository` already imports `List` and `@Query`/`org.springframework.data.jpa.repository.Query`; add if missing.

- [ ] **Step 3: Compile**

Run: `rtk JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
rtk git add backend/src/main/java/ru/selfin/backend/repository/FinancialEventRepository.java backend/src/main/java/ru/selfin/backend/repository/TargetFundRepository.java
rtk git commit -m "feat(repo): wishlist query methods on event + fund repositories"
```

---

## Chunk 2 — BaselineTimelineBuilder extraction + WishlistSimulationService delta math (TDD)

This chunk does the riskiest backend work: extracting the timeline-building core out of `StrategyTimelineService` into a standalone `BaselineTimelineBuilder` (to break the circular dependency), then building the pure delta-vector math in `WishlistSimulationService`. The existing `StrategyTimelineControllerIT` is the regression safety net for the extraction.

### Task 2.1: Internal `TimelineSnapshot` record

**Files:**
- Create: `backend/src/main/java/ru/selfin/backend/dto/wishlist/TimelineSnapshot.java`

- [ ] **Step 1: Create the internal carrier record**

```java
package ru.selfin.backend.dto.wishlist;

import ru.selfin.backend.dto.strategy.StrategyTimelinePointDto;

import java.time.YearMonth;
import java.util.List;

/**
 * Внутренний результат {@link ru.selfin.backend.service.BaselineTimelineBuilder}:
 * полный timeline БЕЗ влияния хотелок (past + current + future, обогащённый капиталом).
 *
 * <p>Используется и {@code WishlistSimulationService} (как baseline для симуляции),
 * и {@code StrategyTimelineService} (как основа, поверх которой накладываются FIXED-items).
 *
 * @param firstMonth     первый месяц активности
 * @param currentMonth   текущий месяц
 * @param horizonEnd     последний месяц горизонта
 * @param predictionWindowMonths окно прогноза (мес)
 * @param fanEnabled     включён ли веер неопределённости
 * @param points         все точки (past + current + future), обогащённые капиталом
 */
public record TimelineSnapshot(
        YearMonth firstMonth,
        YearMonth currentMonth,
        YearMonth horizonEnd,
        int predictionWindowMonths,
        boolean fanEnabled,
        List<StrategyTimelinePointDto> points
) {}
```

- [ ] **Step 2: Compile**

Run: `rtk JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
rtk git add backend/src/main/java/ru/selfin/backend/dto/wishlist/TimelineSnapshot.java
rtk git commit -m "feat(dto): internal TimelineSnapshot carrier for baseline timeline"
```

---

### Task 2.2: Extract `BaselineTimelineBuilder` from `StrategyTimelineService`

This is a **move-not-rewrite** refactor. Copy the timeline-building methods verbatim into a new `@Component`, then make `StrategyTimelineService` delegate to it. Behavior must be byte-identical — `StrategyTimelineControllerIT` proves it.

**Files:**
- Create: `backend/src/main/java/ru/selfin/backend/service/BaselineTimelineBuilder.java`
- Modify: `backend/src/main/java/ru/selfin/backend/service/StrategyTimelineService.java`

- [ ] **Step 1: Create `BaselineTimelineBuilder` with the moved logic**

Move these methods VERBATIM from `StrategyTimelineService` into the new class (same bodies, same helper methods, same constants): `buildCurrentPoint`, `buildPastPoints`, `buildFuturePoints`, `enrichWithCapital`, `enrichWithBreakdown` + all its private breakdown helpers (`breakdownForPast/Current/Future`, `aggregatePlannedByCategory`, `aggregateFactsByCategory`, `withBreakdown`), `firstActivityMonth`, `computeStatsMap`, `sumByType`, `sumPlannedByType`, and the constants `PREDICTION_WINDOW_MONTHS`, `MIN_HISTORY_FOR_FAN`, `MIN_CATEGORIES_FOR_FAN`.

The new class exposes one public method that returns the snapshot:

```java
package ru.selfin.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.selfin.backend.dto.strategy.CategoryMonthStats;
import ru.selfin.backend.dto.strategy.StrategyTimelinePointDto;
import ru.selfin.backend.dto.wishlist.TimelineSnapshot;
import ru.selfin.backend.model.Category;
import ru.selfin.backend.repository.BalanceCheckpointRepository;
import ru.selfin.backend.repository.CategoryRepository;
import ru.selfin.backend.repository.FinancialEventRepository;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Строит "честный" timeline БЕЗ влияния хотелок.
 *
 * <p>Выделен из {@link StrategyTimelineService} (PR wishlist-planning), чтобы разорвать
 * циклическую зависимость: {@code WishlistSimulationService} нуждается в baseline,
 * а {@code StrategyTimelineService} нуждается в delta хотелок. Теперь зависимости линейны:
 * {@code BaselineTimelineBuilder ← WishlistSimulationService ← StrategyTimelineService}.
 *
 * <p>Поведение методов идентично прежнему {@code StrategyTimelineService} — это move-рефакторинг.
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class BaselineTimelineBuilder {

    private final FinancialEventRepository eventRepository;
    private final BalanceCheckpointRepository checkpointRepository;
    private final CategoryRepository categoryRepository;
    private final PredictionService predictionService;
    private final CapitalService capitalService;

    static final int PREDICTION_WINDOW_MONTHS = 6;
    static final int MIN_HISTORY_FOR_FAN = 3;
    static final int MIN_CATEGORIES_FOR_FAN = 3;

    /**
     * Полный timeline без хотелок: past + current + future, обогащённый капиталом
     * и (опционально) breakdown по категориям.
     */
    public TimelineSnapshot build(int horizonMonths, boolean withBreakdown) {
        YearMonth firstMonth = firstActivityMonth();
        YearMonth currentMonth = YearMonth.now();
        YearMonth horizonEnd = currentMonth.plusMonths(horizonMonths);

        Map<Category, CategoryMonthStats> statsMap = computeStatsMap();

        boolean fanEnabled = statsMap.values().stream()
                .filter(s -> s.monthsOfHistory() >= MIN_HISTORY_FOR_FAN)
                .count() >= MIN_CATEGORIES_FOR_FAN;

        List<StrategyTimelinePointDto> past = buildPastPoints(firstMonth, currentMonth);
        StrategyTimelinePointDto current = buildCurrentPoint(currentMonth);
        List<StrategyTimelinePointDto> future = buildFuturePoints(currentMonth, horizonMonths, statsMap);

        List<StrategyTimelinePointDto> all = new ArrayList<>();
        all.addAll(past);
        all.add(current);
        all.addAll(future);

        all = enrichWithCapital(all);
        if (withBreakdown) {
            all = enrichWithBreakdown(all, statsMap);
        }

        return new TimelineSnapshot(firstMonth, currentMonth, horizonEnd,
                PREDICTION_WINDOW_MONTHS, fanEnabled, all);
    }

    // === MOVED VERBATIM FROM StrategyTimelineService ===
    // computeStatsMap, buildCurrentPoint, buildPastPoints, buildFuturePoints,
    // enrichWithCapital, enrichWithBreakdown, breakdownForPast/Current/Future,
    // aggregatePlannedByCategory, aggregateFactsByCategory, withBreakdown,
    // firstActivityMonth, sumByType, sumPlannedByType
    // (paste the exact method bodies from the current StrategyTimelineService)
}
```

> Move the bodies exactly. Change method visibility to `private` except `build`. The `statsMap` computation and all helpers come along unchanged.

- [ ] **Step 2: Rewrite `StrategyTimelineService` as a thin coordinator**

Replace the entire body of `getTimeline` and remove the now-moved methods. For this task, `StrategyTimelineService` just delegates (FIXED-item overlay comes in Task 2.6):

```java
package ru.selfin.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.selfin.backend.dto.strategy.StrategyTimelineDto;
import ru.selfin.backend.dto.wishlist.TimelineSnapshot;

/**
 * Координатор стратегической шкалы. Базовый timeline собирает {@link BaselineTimelineBuilder};
 * поверх него накладываются delta зафиксированных (FIXED) хотелок (см. Task 2.6).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class StrategyTimelineService {

    private final BaselineTimelineBuilder baselineBuilder;

    public StrategyTimelineDto getTimeline(int horizonMonths, boolean withBreakdown) {
        TimelineSnapshot snap = baselineBuilder.build(horizonMonths, withBreakdown);
        return new StrategyTimelineDto(
                snap.firstMonth(),
                snap.currentMonth(),
                snap.horizonEnd(),
                snap.predictionWindowMonths(),
                snap.fanEnabled(),
                snap.points()
        );
    }
}
```

- [ ] **Step 3: Compile + update the moved unit test**

Run: `rtk JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw compile`
Expected: BUILD SUCCESS.

The existing `StrategyTimelineServiceTest` constructs `new StrategyTimelineService(eventRepo, checkpointRepo, categoryRepo, predictionService, capitalService)` and calls the now-moved package-private methods (`buildCurrentPoint`, `buildPastPoints`, `buildFuturePoints`, `enrichWithCapital`, `enrichWithBreakdown`, `firstActivityMonth`) directly. After the move those methods live on `BaselineTimelineBuilder`. **Explicitly update the test:** rename it to `BaselineTimelineBuilderTest`, change instantiation to `new BaselineTimelineBuilder(eventRepo, checkpointRepo, categoryRepo, predictionService, capitalService)` (same 5 args), and repoint all moved-method calls onto it. (The new thin `StrategyTimelineService` gets its own focused test in Task 2.6.) Run `mvnw test-compile` to confirm the test compiles.

- [ ] **Step 4: Run the moved unit test + strategy IT — must still pass (proves move is behavior-neutral)**

Run: `rtk JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -Dtest='BaselineTimelineBuilderTest,StrategyTimelineControllerIT'`
Expected: PASS. If Docker is down, run `BaselineTimelineBuilderTest` only and defer the IT to CI; note it.

- [ ] **Step 5: Commit**

```bash
rtk git add backend/src/main/java/ru/selfin/backend/service/BaselineTimelineBuilder.java backend/src/main/java/ru/selfin/backend/service/StrategyTimelineService.java backend/src/test/java/ru/selfin/backend/service/
rtk git commit -m "refactor(strategy): extract BaselineTimelineBuilder to break wishlist cycle"
```

---

### Task 2.3: `MonthDeltaDto` + `WishlistItemDto` + simulation DTOs

**Files:**
- Create: `backend/src/main/java/ru/selfin/backend/dto/wishlist/MonthDeltaDto.java`
- Create: `backend/src/main/java/ru/selfin/backend/dto/wishlist/WishlistItemDto.java`
- Create: `backend/src/main/java/ru/selfin/backend/dto/wishlist/WishlistThresholdsDto.java`
- Create: `backend/src/main/java/ru/selfin/backend/dto/wishlist/WishlistConstraintsDto.java`
- Create: `backend/src/main/java/ru/selfin/backend/dto/wishlist/WishlistSimulationDto.java`

- [ ] **Step 1: Create the DTOs**

`MonthDeltaDto.java`:
```java
package ru.selfin.backend.dto.wishlist;

import java.math.BigDecimal;

/**
 * Влияние одного item'а на один месяц горизонта.
 * @param monthIndex     0 = current+1, ... (смещение от текущего месяца)
 * @param accountDelta   изменение баланса счёта в этом месяце
 * @param capitalDelta   изменение капитала в этом месяце
 * @param fundDelta      изменение баланса копилок (для tooltip; опционально)
 * @param liabilityDelta изменение обязательств (для tooltip; опционально)
 */
public record MonthDeltaDto(
        int monthIndex,
        BigDecimal accountDelta,
        BigDecimal capitalDelta,
        BigDecimal fundDelta,
        BigDecimal liabilityDelta
) {}
```

`WishlistItemDto.java`:
```java
package ru.selfin.backend.dto.wishlist;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Один item на странице /wishlist (хотелка / копилка / кредит).
 * @param convertedTo {kind, id} артефакта или null
 */
public record WishlistItemDto(
        UUID id,
        String kind,                 // WISHLIST | SAVINGS | CREDIT
        String name,
        BigDecimal amount,
        LocalDate targetDate,
        String status,               // OPEN | FIXED | DISMISSED
        ConvertedToDto convertedTo,
        List<MonthDeltaDto> delta,
        // kind-specific (nullable when not applicable):
        UUID categoryId,             // WISHLIST
        BigDecimal monthlyContribution,  // SAVINGS
        BigDecimal rate,             // CREDIT
        Integer termMonths,          // CREDIT
        BigDecimal monthlyPMT        // CREDIT
) {
    /** Ссылка на сконвертированный артефакт. */
    public record ConvertedToDto(String kind, UUID id) {}  // kind: EVENT | FUND
}
```

`WishlistThresholdsDto.java`:
```java
package ru.selfin.backend.dto.wishlist;

import java.math.BigDecimal;

/**
 * @param capitalThresholdRub null = критерий капитала выключен
 * @param cashBufferMonths    буфер счёта в месяцах расходов (дефолт 1.0)
 */
public record WishlistThresholdsDto(
        BigDecimal capitalThresholdRub,
        BigDecimal cashBufferMonths
) {}
```

`WishlistConstraintsDto.java`:
```java
package ru.selfin.backend.dto.wishlist;

import java.math.BigDecimal;

public record WishlistConstraintsDto(
        BigDecimal monthlyExpensesAvg,
        BigDecimal monthlyIncomeAvg,
        BigDecimal currentCapital,
        BigDecimal maxWishlistAmount,
        BigDecimal maxCreditAmount
) {}
```

`WishlistSimulationDto.java`:
```java
package ru.selfin.backend.dto.wishlist;

import ru.selfin.backend.dto.strategy.StrategyTimelineDto;

import java.util.List;

/**
 * Полный ответ GET /api/v1/wishlist/simulation: baseline timeline (без хотелок) +
 * список items с их delta-векторами + пороги + ограничения для слайдеров.
 */
public record WishlistSimulationDto(
        StrategyTimelineDto baseline,
        List<WishlistItemDto> items,
        WishlistThresholdsDto thresholds,
        WishlistConstraintsDto constraints
) {}
```

- [ ] **Step 2: Compile**

Run: `rtk JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
rtk git add backend/src/main/java/ru/selfin/backend/dto/wishlist/
rtk git commit -m "feat(dto): wishlist simulation DTOs"
```

---

### Task 2.4: `WishlistSimulationService.computeDeltaForItem` — WISHLIST (TDD)

**Files:**
- Create: `backend/src/main/java/ru/selfin/backend/service/WishlistSimulationService.java`
- Create: `backend/src/test/java/ru/selfin/backend/service/WishlistSimulationServiceTest.java`

The service computes a per-item delta vector. We TDD each `kind` in its own task (2.4 WISHLIST, 2.5 SAVINGS+CREDIT). Items are modelled internally as a small `WishlistItemModel` carrier the service builds from `FinancialEvent`/`TargetFund`; the test constructs it directly.

- [ ] **Step 1: Write the failing test for WISHLIST delta**

```java
package ru.selfin.backend.service;

import org.junit.jupiter.api.Test;
import ru.selfin.backend.dto.wishlist.MonthDeltaDto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WishlistSimulationServiceTest {

    // Pure-math helper under test is static (no Spring context needed).
    @Test
    void computeDelta_wishlist_singleMonthExpense() {
        YearMonth current = YearMonth.now();
        LocalDate target = current.plusMonths(15).atDay(10);

        List<MonthDeltaDto> delta = WishlistSimulationService.computeWishlistDelta(
                new BigDecimal("150000"), target, current, 36);

        assertThat(delta).hasSize(1);
        MonthDeltaDto d = delta.get(0);
        assertThat(d.monthIndex()).isEqualTo(14);   // current+1 == index 0, so current+15 == index 14
        assertThat(d.accountDelta()).isEqualByComparingTo("-150000");
        assertThat(d.capitalDelta()).isEqualByComparingTo("-150000");
    }

    @Test
    void computeDelta_wishlist_pastDate_empty() {
        YearMonth current = YearMonth.now();
        LocalDate past = current.minusMonths(2).atDay(10);

        List<MonthDeltaDto> delta = WishlistSimulationService.computeWishlistDelta(
                new BigDecimal("150000"), past, current, 36);

        assertThat(delta).isEmpty();
    }

    @Test
    void computeDelta_wishlist_beyondHorizon_empty() {
        YearMonth current = YearMonth.now();
        LocalDate far = current.plusMonths(50).atDay(10);

        List<MonthDeltaDto> delta = WishlistSimulationService.computeWishlistDelta(
                new BigDecimal("150000"), far, current, 36);

        assertThat(delta).isEmpty();
    }
}
```

> Index convention pinned here: `monthIndex = 0` corresponds to `current + 1` month. A target in `current + N` months → `monthIndex = N - 1`. Document this in the method javadoc — the frontend `composeTimeline` must use the same convention.

- [ ] **Step 2: Run, expect FAIL (method does not exist)**

Run: `rtk JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -Dtest=WishlistSimulationServiceTest`
Expected: COMPILE FAIL ("cannot find symbol: computeWishlistDelta").

- [ ] **Step 3: Create the service skeleton + implement `computeWishlistDelta`**

```java
package ru.selfin.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.selfin.backend.dto.wishlist.MonthDeltaDto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * Считает влияние (delta-вектор) каждого wishlist-item'а на горизонт месяцев.
 * Чистая математика в статических методах — переиспользуется и для GET /simulation,
 * и для наложения FIXED-items в {@link StrategyTimelineService}.
 *
 * <p>Соглашение об индексах: {@code monthIndex = 0} соответствует {@code current + 1}.
 * Item с целевой датой в {@code current + N} месяцев → {@code monthIndex = N - 1}.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class WishlistSimulationService {

    /**
     * Разовая хотелка: один отток в месяц целевой даты. Уменьшает счёт и капитал на сумму.
     * Возвращает пустой список, если дата в прошлом или за горизонтом.
     */
    public static List<MonthDeltaDto> computeWishlistDelta(
            BigDecimal amount, LocalDate targetDate, YearMonth current, int horizonMonths) {
        int idx = monthIndexOf(targetDate, current);
        if (idx < 0 || idx >= horizonMonths) return List.of();
        List<MonthDeltaDto> out = new ArrayList<>(1);
        out.add(new MonthDeltaDto(idx, amount.negate(), amount.negate(), null, null));
        return out;
    }

    /** monthIndex для targetDate: (current+1)=0. Возвращает -1, если targetDate раньше current+1. */
    static int monthIndexOf(LocalDate targetDate, YearMonth current) {
        YearMonth target = YearMonth.from(targetDate);
        int diff = (target.getYear() - current.getYear()) * 12
                + (target.getMonthValue() - current.getMonthValue());
        return diff - 1;   // current+1 → 0
    }
}
```

- [ ] **Step 4: Run, expect PASS**

Run: `rtk JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -Dtest=WishlistSimulationServiceTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
rtk git add backend/src/main/java/ru/selfin/backend/service/WishlistSimulationService.java backend/src/test/java/ru/selfin/backend/service/WishlistSimulationServiceTest.java
rtk git commit -m "feat(service): WishlistSimulationService.computeWishlistDelta (TDD)"
```

---

### Task 2.5: SAVINGS + CREDIT delta math (TDD)

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/service/WishlistSimulationService.java`
- Modify: `backend/src/test/java/ru/selfin/backend/service/WishlistSimulationServiceTest.java`

- [ ] **Step 1: Write failing tests for SAVINGS and CREDIT**

```java
    @Test
    void computeDelta_savings_monthlyContributionsThenPurchase() {
        YearMonth current = YearMonth.now();
        LocalDate target = current.plusMonths(12).atDay(1);   // purchaseIdx = 11
        BigDecimal amount = new BigDecimal("200000");

        var result = WishlistSimulationService.computeSavingsDelta(amount, target, current, 36);

        // Model: contribute monthly across ALL months 0..purchaseIdx (inclusive) = purchaseIdx+1 = 12 months.
        // monthly = amount / (purchaseIdx + 1) = 200000 / 12 = 16666.67.
        // account drops by monthly EVERY month 0..11 (total = amount); capital flat until purchase.
        // At purchase month (index 11): that month's contribution PLUS the consumption.
        // account axis and capital axis are independent (account=checking, capital=net worth);
        // the fund pocket is the intermediary and shows only in tooltips.
        assertThat(result.monthlyContribution()).isEqualByComparingTo("16666.67");

        // contribution months 0..10: account -= monthly, fund += monthly, capital = 0
        assertThat(result.delta().get(0).monthIndex()).isEqualTo(0);
        assertThat(result.delta().get(0).accountDelta()).isEqualByComparingTo("-16666.67");
        assertThat(result.delta().get(0).fundDelta()).isEqualByComparingTo("16666.67");
        assertThat(result.delta().get(0).capitalDelta()).isEqualByComparingTo("0");

        // purchase month (index 11): account -= monthly (final contribution), capital -= amount (consumption)
        MonthDeltaDto last = result.delta().get(result.delta().size() - 1);
        assertThat(last.monthIndex()).isEqualTo(11);
        assertThat(last.accountDelta()).isEqualByComparingTo("-16666.67");
        assertThat(last.capitalDelta()).isEqualByComparingTo("-200000");

        // total account drop across all 12 entries == amount (12 × 16666.67 ≈ 200000)
        BigDecimal totalAccount = result.delta().stream()
                .map(MonthDeltaDto::accountDelta).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(totalAccount).isEqualByComparingTo("-200000.04");  // rounding: 12 × -16666.67
    }

    @Test
    void computeDelta_savings_nextMonthTarget_degeneratesToLumpSum() {
        YearMonth current = YearMonth.now();
        LocalDate target = current.plusMonths(1).atDay(1);   // purchaseIdx = 0
        var result = WishlistSimulationService.computeSavingsDelta(
                new BigDecimal("200000"), target, current, 36);
        // No time to save: single entry at index 0, account -amount, capital -amount.
        assertThat(result.delta()).hasSize(1);
        assertThat(result.delta().get(0).monthIndex()).isEqualTo(0);
        assertThat(result.delta().get(0).accountDelta()).isEqualByComparingTo("-200000");
        assertThat(result.delta().get(0).capitalDelta()).isEqualByComparingTo("-200000");
    }

    @Test
    void computeDelta_savings_beyondHorizon_empty() {
        YearMonth current = YearMonth.now();
        LocalDate far = current.plusMonths(50).atDay(1);
        var result = WishlistSimulationService.computeSavingsDelta(
                new BigDecimal("200000"), far, current, 36);
        assertThat(result.delta()).isEmpty();
    }

    @Test
    void computeDelta_credit_lumpSumThenPMT() {
        YearMonth current = YearMonth.now();
        LocalDate target = current.plusMonths(3).atDay(1);  // purchase at index 2
        var result = WishlistSimulationService.computeCreditDelta(
                new BigDecimal("2000000"), target, current, 36,
                new BigDecimal("16.5"), 60);

        assertThat(result.monthlyPMT()).isGreaterThan(new BigDecimal("48000"))
                .isLessThan(new BigDecimal("50000"));
        // purchase month (index 2): account +amount (loan disbursed), liability +amount, capital unchanged
        MonthDeltaDto purchase = result.delta().get(0);
        assertThat(purchase.monthIndex()).isEqualTo(2);
        assertThat(purchase.accountDelta()).isEqualByComparingTo("2000000");
        assertThat(purchase.liabilityDelta()).isEqualByComparingTo("2000000");
        assertThat(purchase.capitalDelta()).isEqualByComparingTo("0");
        // first PMT month (index 3): account -PMT, liability -principalPart, capital +principalPart
        MonthDeltaDto firstPmt = result.delta().get(1);
        assertThat(firstPmt.monthIndex()).isEqualTo(3);
        assertThat(firstPmt.accountDelta()).isLessThan(BigDecimal.ZERO);
        assertThat(firstPmt.liabilityDelta()).isLessThan(BigDecimal.ZERO);
        assertThat(firstPmt.capitalDelta()).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void computeDelta_credit_clipsToHorizon() {
        YearMonth current = YearMonth.now();
        LocalDate target = current.plusMonths(1).atDay(1);  // purchase index 0
        var result = WishlistSimulationService.computeCreditDelta(
                new BigDecimal("2000000"), target, current, 36,
                new BigDecimal("16.5"), 120);   // 10y term > 36mo horizon

        // No delta entry exceeds monthIndex 35
        assertThat(result.delta()).allSatisfy(d -> assertThat(d.monthIndex()).isLessThan(36));
    }
```

Add a small result carrier in the test imports (define it in the service in Step 3): `WishlistSimulationService.SavingsResult` and `CreditResult`.

- [ ] **Step 2: Run, expect FAIL**

Run: `rtk JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -Dtest=WishlistSimulationServiceTest`
Expected: COMPILE FAIL.

- [ ] **Step 3: Implement `computeSavingsDelta` and `computeCreditDelta`**

```java
    /** Результат расчёта копилки: delta + выведенный месячный взнос. */
    public record SavingsResult(List<MonthDeltaDto> delta, BigDecimal monthlyContribution) {}
    /** Результат расчёта кредита: delta + выведенный месячный платёж (PMT). */
    public record CreditResult(List<MonthDeltaDto> delta, BigDecimal monthlyPMT) {}

    /**
     * Копилка: равномерные взносы КАЖДЫЙ месяц 0..purchaseIdx (включительно = purchaseIdx+1 месяцев),
     * в последний месяц — покупка.
     *
     * <p>account (расчётный счёт) и capital (чистая стоимость) — независимые оси; копилка-pocket
     * выступает посредником и видна только в tooltip:
     * <ul>
     *   <li>месяцы 0..purchaseIdx-1: account −= monthly, fund += monthly, capital = 0
     *       (деньги переехали в копилку, всё ещё мои);</li>
     *   <li>месяц purchaseIdx: account −= monthly (последний взнос), capital −= amount (потребление),
     *       fund += (monthly − amount) (копилка наполнилась и потрачена).</li>
     * </ul>
     * Итог: account падает на amount равномерно за (purchaseIdx+1) месяцев; capital падает на amount
     * в месяц покупки. monthly = amount / (purchaseIdx + 1).
     *
     * <p>purchaseIdx == 0 (цель в current+1, нет времени копить) → одна запись: account −amount,
     * capital −amount (вырождается в разовый отток). Возвращает пустой список, если дата в прошлом
     * или за горизонтом.
     */
    public static SavingsResult computeSavingsDelta(
            BigDecimal amount, LocalDate targetDate, YearMonth current, int horizonMonths) {
        int purchaseIdx = monthIndexOf(targetDate, current);
        if (purchaseIdx < 0 || purchaseIdx >= horizonMonths) {
            return new SavingsResult(List.of(), BigDecimal.ZERO);
        }
        if (purchaseIdx == 0) {
            // Нет времени копить — разовый отток.
            return new SavingsResult(
                    List.of(new MonthDeltaDto(0, amount.negate(), amount.negate(), BigDecimal.ZERO, null)),
                    amount);
        }
        int contribMonths = purchaseIdx + 1;   // взносы в месяцах 0..purchaseIdx включительно
        BigDecimal monthly = amount.divide(BigDecimal.valueOf(contribMonths), 2, java.math.RoundingMode.HALF_UP);

        List<MonthDeltaDto> out = new ArrayList<>();
        for (int i = 0; i < purchaseIdx; i++) {
            out.add(new MonthDeltaDto(i, monthly.negate(), BigDecimal.ZERO, monthly, null));
        }
        // Месяц покупки: последний взнос + потребление.
        out.add(new MonthDeltaDto(purchaseIdx, monthly.negate(), amount.negate(),
                monthly.subtract(amount), null));
        return new SavingsResult(out, monthly);
    }

    /**
     * Кредит: в месяц покупки сумма зачисляется на счёт (account +amount) и появляется
     * обязательство (liability +amount, capital неизменен — актив компенсирует долг).
     * Далее аннуитетный PMT каждый месяц: account -PMT, principalPart гасит долг
     * (liability -principalPart), capital растёт на principalPart (долг тает).
     * Серия PMT обрезается по горизонту.
     */
    public static CreditResult computeCreditDelta(
            BigDecimal amount, LocalDate targetDate, YearMonth current, int horizonMonths,
            BigDecimal annualRatePct, int termMonths) {
        int purchaseIdx = monthIndexOf(targetDate, current);
        if (purchaseIdx < 0) return new CreditResult(List.of(), BigDecimal.ZERO);

        double monthlyRate = annualRatePct.doubleValue() / 100.0 / 12.0;
        double pmtRaw;
        if (monthlyRate == 0.0) {
            pmtRaw = amount.doubleValue() / termMonths;
        } else {
            double f = Math.pow(1 + monthlyRate, termMonths);
            pmtRaw = amount.doubleValue() * monthlyRate * f / (f - 1);
        }
        BigDecimal pmt = BigDecimal.valueOf(pmtRaw).setScale(2, java.math.RoundingMode.HALF_UP);

        List<MonthDeltaDto> out = new ArrayList<>();
        if (purchaseIdx < horizonMonths) {
            out.add(new MonthDeltaDto(purchaseIdx, amount, BigDecimal.ZERO, null, amount));
        }
        double remaining = amount.doubleValue();
        for (int p = 1; p <= termMonths; p++) {
            int idx = purchaseIdx + p;
            if (idx >= horizonMonths) break;
            double interest = remaining * monthlyRate;
            double principal = pmtRaw - interest;
            remaining -= principal;
            BigDecimal principalBd = BigDecimal.valueOf(principal).setScale(2, java.math.RoundingMode.HALF_UP);
            out.add(new MonthDeltaDto(
                    idx,
                    pmt.negate(),
                    principalBd,                 // capital grows as debt shrinks
                    null,
                    principalBd.negate()         // liability shrinks
            ));
        }
        return new CreditResult(out, pmt);
    }
```

- [ ] **Step 4: Run, expect PASS**

Run: `rtk JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -Dtest=WishlistSimulationServiceTest`
Expected: PASS (6 tests total).

- [ ] **Step 5: Commit**

```bash
rtk git add backend/src/main/java/ru/selfin/backend/service/WishlistSimulationService.java backend/src/test/java/ru/selfin/backend/service/WishlistSimulationServiceTest.java
rtk git commit -m "feat(service): savings + credit delta math (TDD)"
```

---

### Task 2.6: Assemble `getSimulation` + overlay FIXED items on `/strategy` (TDD)

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/service/WishlistSimulationService.java`
- Modify: `backend/src/main/java/ru/selfin/backend/service/StrategyTimelineService.java`
- Modify: `backend/src/test/java/ru/selfin/backend/service/WishlistSimulationServiceTest.java`

- [ ] **Step 1: Write failing test for `getSimulation` assembly (Mockito)**

Add a Spring-free Mockito test: mock `BaselineTimelineBuilder`, `FinancialEventRepository`, `TargetFundRepository`, `UserSettingsService`, `CapitalService`. Verify:
1. `getSimulation(36)` returns baseline from builder.
2. Items collected from both repos (`findAllWishlistEvents` + `findAllWishlistFunds`), DISMISSED excluded.
3. Each returned `WishlistItemDto` has a non-null `delta` computed via the static helpers.
4. `constraints.maxWishlistAmount` and `maxCreditAmount` computed from `monthlyIncomeAvg`/`currentCapital`.

Keep assertions focused (one behavior each).

- [ ] **Step 2: Run, expect FAIL**

Run: `rtk JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -Dtest=WishlistSimulationServiceTest`
Expected: FAIL (`getSimulation` not implemented).

- [ ] **Step 3: Implement `getSimulation`, `recomputeItemDelta`, `computeDeltaForFixedItems`**

Implement:
- `getSimulation(int horizonMonths)` — builds baseline via `baselineBuilder.build(horizonMonths, true)`, collects wishlist events (`findAllWishlistEvents`) + funds (`findAllWishlistFunds`), drops `DISMISSED`, maps each to `WishlistItemDto`, computes delta via the static helpers, fetches thresholds via `UserSettingsService`, computes `WishlistConstraintsDto` (income/expense averages from a 6-month window; reuse existing `eventRepository.findFactsByDateRange`, or a small private helper), wraps into `WishlistSimulationDto`.

  **Field mapping (explicit):**
  - WISHLIST item ← `FinancialEvent`: `kind=WISHLIST`, `name = description` (fallback category name), `amount = plannedAmount`, **`targetDate = event.date`** (the event's `date` field is the wishlist target date; it is nullable — if null, the item still lists but `delta` is empty and the card prompts the user to pick a date), `categoryId = category.id`, `status = wishlistStatus`, `convertedTo` from `convertedToEventId`/`convertedToFundId`.
  - SAVINGS/CREDIT item ← `TargetFund`: `kind` from `purchaseType` (SAVINGS→`SAVINGS`, CREDIT→`CREDIT`), `name = name`, `amount = targetAmount`, `targetDate = targetDate`, `rate = creditRate`, `termMonths = creditTermMonths` (CREDIT only), `status = wishlistStatus`.
  - When `targetDate == null`, return an empty `delta` for that item (the static helpers already return empty for a null/past date — guard the call).
- `recomputeItemDelta(RecomputeRequestDto)` → `RecomputeResponseDto` (delta + monthlyContribution/PMT).
- `computeDeltaForFixedItems(YearMonth current, int horizonMonths)` → `List<MonthDeltaDto>` summed across all FIXED items (events with `wishlistStatus=FIXED` and funds with `wishlistStatus=FIXED`). Used by `StrategyTimelineService`.

> **Avoid double-count:** FIXED items WITH conversion (`convertedToEventId`/`convertedToFundId != null`) already exist as real PLAN events / funds and are in the baseline. `computeDeltaForFixedItems` must include ONLY FIXED items WITHOUT conversion. Filter `convertedToEventId == null && convertedToFundId == null`.

- [ ] **Step 4: Wire FIXED overlay into `StrategyTimelineService`**

Inject `WishlistSimulationService` into `StrategyTimelineService`. After building the snapshot, add FIXED-item deltas onto each point's `balance`/`balanceConfirmed`/`balanceLow`/`balanceHigh`/`capital`. Add a focused unit test asserting that a FIXED-without-conversion item shifts the future point's balance.

```java
// StrategyTimelineService.getTimeline (updated)
TimelineSnapshot snap = baselineBuilder.build(horizonMonths, withBreakdown);
List<MonthDeltaDto> fixedDeltas = wishlistSimulationService
        .computeDeltaForFixedItems(snap.currentMonth(), horizonMonths);
List<StrategyTimelinePointDto> overlaid = applyDeltas(snap.points(), snap.currentMonth(), fixedDeltas);
return new StrategyTimelineDto(snap.firstMonth(), snap.currentMonth(), snap.horizonEnd(),
        snap.predictionWindowMonths(), snap.fanEnabled(), overlaid);
```

`applyDeltas` is a small private helper. **Concrete semantics (load-bearing — the frontend `composeTimeline` mirrors this exactly):** deltas are per-month *flows*, not one-time point shifts. Maintain two running totals `runAccount = 0`, `runCapital = 0`. Iterate future points in chronological order; for the point at month-offset `k` (where `k = 1` is `current+1`, i.e. `monthIndex = k-1`), first add every delta entry whose `monthIndex == k-1` into the running totals (`runAccount += Σ accountDelta`, `runCapital += Σ capitalDelta`), then add `runAccount` to that point's `balance`/`balanceConfirmed`/`balanceLow`/`balanceHigh` and `runCapital` to its `capital`. This way a single outflow at `monthIndex=2` lowers every point from `current+3` onward — matching how `buildFuturePoints` carries `balanceConfirmed` as a running total, and matching the frontend test in Task 5.3 (`out[2].account == 30000`).

- [ ] **Step 5: Run all affected tests**

Run: `rtk JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -Dtest='WishlistSimulationServiceTest,StrategyTimelineServiceTest'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
rtk git add backend/src/main/java/ru/selfin/backend/service/ backend/src/test/java/ru/selfin/backend/service/
rtk git commit -m "feat(service): getSimulation assembly + FIXED-item overlay on strategy timeline"
```

---

## Chunk 3 — Settings + conversion + status endpoints + controllers

This chunk completes the backend write paths: thresholds storage, status transitions, conversion to real artifacts, and all controllers.

### Task 3.0: Map `ResponseStatusException` to its embedded status (prerequisite)

The existing `GlobalExceptionHandler` has a catch-all `@ExceptionHandler(Exception.class)` returning 500. Because `@ControllerAdvice` handlers resolve before Spring's `ResponseStatusExceptionResolver`, that catch-all intercepts `ResponseStatusException` and returns **500 instead of the embedded 400/409**. Every 400/409 in this chunk relies on `ResponseStatusException`, so add an explicit handler first. (This also retroactively fixes the same latent issue in already-merged code that throws `ResponseStatusException`.)

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/config/GlobalExceptionHandler.java`

- [ ] **Step 1: Add a handler ABOVE the generic `Exception` handler**

```java
    /**
     * Пробрасывает HTTP-статус, заложенный в ResponseStatusException (400/404/409/...),
     * вместо того чтобы он попадал в generic-обработчик и превращался в 500.
     * Должен стоять ВЫШЕ @ExceptionHandler(Exception.class) — более специфичный матч.
     */
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatus(
            org.springframework.web.server.ResponseStatusException ex) {
        int code = ex.getStatusCode().value();
        log.warn("ResponseStatusException {}: {}", code, ex.getReason());
        return ResponseEntity
                .status(ex.getStatusCode())
                .body(ErrorResponse.of(code, ex.getReason() != null ? ex.getReason() : "Error"));
    }
```

- [ ] **Step 2: Compile**

Run: `rtk JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
rtk git add backend/src/main/java/ru/selfin/backend/config/GlobalExceptionHandler.java
rtk git commit -m "fix(error): map ResponseStatusException to its embedded HTTP status"
```

---

### Task 3.1: `UserSettingsService` + remaining settings DTO (TDD)

**Files:**
- Create: `backend/src/main/java/ru/selfin/backend/service/UserSettingsService.java`
- Create: `backend/src/test/java/ru/selfin/backend/service/UserSettingsServiceTest.java`

- [ ] **Step 1: Failing tests**

```java
package ru.selfin.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.selfin.backend.dto.wishlist.WishlistThresholdsDto;
import ru.selfin.backend.repository.UserSettingsRepository;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserSettingsServiceTest {

    private final UserSettingsRepository repo = mock(UserSettingsRepository.class);
    private final UserSettingsService service = new UserSettingsService(repo, new ObjectMapper());

    @Test
    void getWishlistSettings_firstCall_returnsDefaults() {
        when(repo.findBySettingsKey("wishlist")).thenReturn(Optional.empty());
        WishlistThresholdsDto dto = service.getWishlistSettings();
        assertThat(dto.capitalThresholdRub()).isNull();
        assertThat(dto.cashBufferMonths()).isEqualByComparingTo("1.0");
    }

    @Test
    void updateWishlistSettings_negativeBuffer_throws() {
        assertThatThrownBy(() -> service.updateWishlistSettings(
                new WishlistThresholdsDto(BigDecimal.ZERO, new BigDecimal("-1"))))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void updateWishlistSettings_bufferAbove36_throws() {
        assertThatThrownBy(() -> service.updateWishlistSettings(
                new WishlistThresholdsDto(BigDecimal.ZERO, new BigDecimal("37"))))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void updateWishlistSettings_nullCapitalThreshold_isAllowed() {
        when(repo.findBySettingsKey("wishlist")).thenReturn(Optional.empty());
        when(repo.save(org.mockito.ArgumentMatchers.any())).thenAnswer(i -> i.getArgument(0));
        var saved = service.updateWishlistSettings(
                new WishlistThresholdsDto(null, new BigDecimal("1.0")));
        assertThat(saved.capitalThresholdRub()).isNull();   // null = capital criterion disabled
    }
}
```

- [ ] **Step 2: Run, expect FAIL**

Run: `rtk JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -Dtest=UserSettingsServiceTest`
Expected: COMPILE FAIL.

- [ ] **Step 3: Implement service**

```java
package ru.selfin.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ru.selfin.backend.dto.wishlist.WishlistThresholdsDto;
import ru.selfin.backend.model.UserSettings;
import ru.selfin.backend.repository.UserSettingsRepository;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class UserSettingsService {

    private static final String KEY = "wishlist";
    private final UserSettingsRepository repo;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public WishlistThresholdsDto getWishlistSettings() {
        return repo.findBySettingsKey(KEY)
                .map(this::parse)
                .orElse(new WishlistThresholdsDto(null, new BigDecimal("1.0")));
    }

    @Transactional
    public WishlistThresholdsDto updateWishlistSettings(WishlistThresholdsDto dto) {
        validate(dto);
        UserSettings entity = repo.findBySettingsKey(KEY).orElseGet(() ->
                UserSettings.builder().settingsKey(KEY).build());
        entity.setSettingsValue(serialize(dto));
        repo.save(entity);
        return dto;
    }

    private void validate(WishlistThresholdsDto dto) {
        if (dto.cashBufferMonths() == null
                || dto.cashBufferMonths().compareTo(BigDecimal.ZERO) < 0
                || dto.cashBufferMonths().compareTo(new BigDecimal("36")) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "cashBufferMonths must be in [0, 36]");
        }
        if (dto.capitalThresholdRub() != null
                && dto.capitalThresholdRub().compareTo(BigDecimal.ZERO) < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "capitalThresholdRub must be >= 0");
        }
    }

    private WishlistThresholdsDto parse(UserSettings s) {
        try {
            return objectMapper.readValue(s.getSettingsValue(), WishlistThresholdsDto.class);
        } catch (Exception e) {
            return new WishlistThresholdsDto(null, new BigDecimal("1.0"));
        }
    }

    private String serialize(WishlistThresholdsDto dto) {
        try {
            return objectMapper.writeValueAsString(dto);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "serialize settings");
        }
    }
}
```

- [ ] **Step 4: Run, expect PASS; Commit**

```bash
rtk JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -Dtest=UserSettingsServiceTest
rtk git add backend/src/main/java/ru/selfin/backend/service/UserSettingsService.java backend/src/test/java/ru/selfin/backend/service/UserSettingsServiceTest.java
rtk git commit -m "feat(service): UserSettingsService with lazy-init + validation (TDD)"
```

---

### Task 3.2: Status-transition methods on event + fund services (TDD)

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/service/FinancialEventService.java`
- Modify: `backend/src/main/java/ru/selfin/backend/service/TargetFundService.java`
- Modify: `backend/src/test/java/ru/selfin/backend/service/FinancialEventServiceTest.java`

- [ ] **Step 1: Failing tests in `FinancialEventServiceTest`**

```java
    @Test
    void setWishlistStatus_onNonLowEvent_throws400() {
        UUID id = UUID.randomUUID();
        FinancialEvent e = FinancialEvent.builder().id(id).priority(Priority.HIGH).build();
        when(eventRepository.findById(id)).thenReturn(Optional.of(e));
        assertThatThrownBy(() -> service.setWishlistStatus(id, WishlistStatus.FIXED))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void setWishlistStatus_onLowEvent_updates() {
        UUID id = UUID.randomUUID();
        FinancialEvent e = FinancialEvent.builder().id(id).priority(Priority.LOW)
                .wishlistStatus(WishlistStatus.OPEN).build();
        when(eventRepository.findById(id)).thenReturn(Optional.of(e));
        when(eventRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        service.setWishlistStatus(id, WishlistStatus.DISMISSED);
        assertThat(e.getWishlistStatus()).isEqualTo(WishlistStatus.DISMISSED);
    }

    @Test
    void setWishlistStatus_sameValue_isNoOpNoThrow() {
        UUID id = UUID.randomUUID();
        FinancialEvent e = FinancialEvent.builder().id(id).priority(Priority.LOW)
                .wishlistStatus(WishlistStatus.FIXED).build();
        when(eventRepository.findById(id)).thenReturn(Optional.of(e));
        when(eventRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        service.setWishlistStatus(id, WishlistStatus.FIXED);   // same value
        assertThat(e.getWishlistStatus()).isEqualTo(WishlistStatus.FIXED);  // unchanged, no exception
    }
```

- [ ] **Step 2: Run, expect FAIL**

Run: `rtk JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -Dtest=FinancialEventServiceTest`
Expected: COMPILE FAIL.

- [ ] **Step 3: Implement `setWishlistStatus` on both services**

`FinancialEventService`:
```java
    @Transactional
    public void setWishlistStatus(UUID id, ru.selfin.backend.model.enums.WishlistStatus status) {
        FinancialEvent e = eventRepository.findById(id)
                .filter(ev -> !ev.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("FinancialEvent", id));
        if (e.getPriority() != Priority.LOW) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "wishlist_status applies to LOW-priority events only");
        }
        e.setWishlistStatus(status);   // idempotent: same value is a no-op write
        eventRepository.save(e);
    }
```

`TargetFundService`:
```java
    @Transactional
    public void setWishlistStatus(UUID id, ru.selfin.backend.model.enums.WishlistStatus status) {
        TargetFund f = fundRepository.findById(id)
                .filter(x -> !x.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("TargetFund", id));
        f.setWishlistStatus(status);
        fundRepository.save(f);
    }
```

(Verify the actual repository field name in `TargetFundService` — it may be `targetFundRepository`. Match the existing field.)

- [ ] **Step 4: Run, expect PASS; Commit**

```bash
rtk JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -Dtest=FinancialEventServiceTest
rtk git add backend/src/main/java/ru/selfin/backend/service/FinancialEventService.java backend/src/main/java/ru/selfin/backend/service/TargetFundService.java backend/src/test/java/ru/selfin/backend/service/FinancialEventServiceTest.java
rtk git commit -m "feat(service): setWishlistStatus on event + fund services (TDD)"
```

---

### Task 3.3: Conversion DTOs + `WishlistConversionService` (TDD)

**Files:**
- Create: `backend/src/main/java/ru/selfin/backend/dto/wishlist/ConvertWishlistRequestDto.java`
- Create: `backend/src/main/java/ru/selfin/backend/dto/wishlist/ConvertWishlistResponseDto.java`
- Create: `backend/src/main/java/ru/selfin/backend/dto/wishlist/WishlistStatusUpdateDto.java`
- Create: `backend/src/main/java/ru/selfin/backend/dto/wishlist/RecomputeRequestDto.java`
- Create: `backend/src/main/java/ru/selfin/backend/dto/wishlist/RecomputeResponseDto.java`
- Create: `backend/src/main/java/ru/selfin/backend/service/WishlistConversionService.java`
- Create: `backend/src/test/java/ru/selfin/backend/service/WishlistConversionServiceTest.java`

- [ ] **Step 1: Create the request/response DTOs**

```java
// ConvertWishlistRequestDto.java
package ru.selfin.backend.dto.wishlist;
public record ConvertWishlistRequestDto(
        String sourceKind,   // WISHLIST | SAVINGS | CREDIT
        String target,       // PLAN_EVENT | FUND | FUND_WITH_CREDIT
        Boolean createRecurringPayments
) {}
```
```java
// ConvertWishlistResponseDto.java
package ru.selfin.backend.dto.wishlist;
import java.util.UUID;
public record ConvertWishlistResponseDto(
        UUID wishlistItemId,
        String newStatus,
        WishlistItemDto.ConvertedToDto convertedTo,
        String artifactKind,        // PLAN_EVENT | FUND | FUND_WITH_CREDIT
        UUID recurringRuleId
) {}
```
```java
// WishlistStatusUpdateDto.java
package ru.selfin.backend.dto.wishlist;
public record WishlistStatusUpdateDto(String status) {}   // OPEN | FIXED | DISMISSED
```
```java
// RecomputeRequestDto.java
package ru.selfin.backend.dto.wishlist;
import java.math.BigDecimal;
import java.time.LocalDate;
public record RecomputeRequestDto(
        String kind, BigDecimal amount, LocalDate targetDate,
        BigDecimal rate, Integer termMonths
) {}
```
```java
// RecomputeResponseDto.java
package ru.selfin.backend.dto.wishlist;
import java.math.BigDecimal;
import java.util.List;
public record RecomputeResponseDto(
        List<MonthDeltaDto> delta,
        BigDecimal monthlyContribution,
        BigDecimal monthlyPMT
) {}
```

- [ ] **Step 2: Failing tests for conversion**

```java
package ru.selfin.backend.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.selfin.backend.dto.wishlist.ConvertWishlistRequestDto;
import ru.selfin.backend.exception.ResourceNotFoundException;  // verify exact package
import ru.selfin.backend.model.*;
import ru.selfin.backend.model.enums.*;
import ru.selfin.backend.repository.FinancialEventRepository;
import ru.selfin.backend.repository.TargetFundRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WishlistConversionServiceTest {

    private final FinancialEventRepository eventRepo = mock(FinancialEventRepository.class);
    private final TargetFundRepository fundRepo = mock(TargetFundRepository.class);
    private final RecurringRuleService recurringRuleService = mock(RecurringRuleService.class);
    private final WishlistConversionService service =
            new WishlistConversionService(eventRepo, fundRepo, recurringRuleService);

    private FinancialEvent openWishlist(UUID id) {
        Category cat = Category.builder().id(UUID.randomUUID()).name("Прочее").build();
        return FinancialEvent.builder().id(id).priority(Priority.LOW)
                .wishlistStatus(WishlistStatus.OPEN).category(cat)
                .type(EventType.EXPENSE).plannedAmount(new BigDecimal("150000"))
                .date(LocalDate.now().plusMonths(6)).description("Ноут").build();
    }

    @Test
    void convert_wishlistToPlanEvent_createsEventAndFixesSource() {
        UUID id = UUID.randomUUID();
        FinancialEvent src = openWishlist(id);
        when(eventRepo.findById(id)).thenReturn(Optional.of(src));
        when(eventRepo.save(any())).thenAnswer(i -> {
            FinancialEvent e = i.getArgument(0);
            if (e.getId() == null) e.setId(UUID.randomUUID());
            return e;
        });

        var resp = service.convertItem(id,
                new ConvertWishlistRequestDto("WISHLIST", "PLAN_EVENT", false));

        assertThat(src.getWishlistStatus()).isEqualTo(WishlistStatus.FIXED);
        assertThat(src.getConvertedToEventId()).isNotNull();
        assertThat(resp.convertedTo().kind()).isEqualTo("EVENT");
        // verify a new PLAN event (eventKind=PLAN, wishlistStatus=null) was saved
        ArgumentCaptor<FinancialEvent> cap = ArgumentCaptor.forClass(FinancialEvent.class);
        verify(eventRepo, atLeast(1)).save(cap.capture());
        assertThat(cap.getAllValues()).anySatisfy(e -> {
            assertThat(e.getEventKind()).isEqualTo(EventKind.PLAN);
            assertThat(e.getWishlistStatus()).isNull();
        });
    }

    @Test
    void convert_alreadyConverted_throws409() {
        UUID id = UUID.randomUUID();
        FinancialEvent src = openWishlist(id);
        src.setWishlistStatus(WishlistStatus.FIXED);
        src.setConvertedToEventId(UUID.randomUUID());   // already converted
        when(eventRepo.findById(id)).thenReturn(Optional.of(src));

        assertThatThrownBy(() -> service.convertItem(id,
                new ConvertWishlistRequestDto("WISHLIST", "PLAN_EVENT", false)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .satisfies(ex -> assertThat(((org.springframework.web.server.ResponseStatusException) ex)
                        .getStatusCode().value()).isEqualTo(409));
    }

    @Test
    void convert_creditWithRecurring_createsFundAndRule() {
        UUID id = UUID.randomUUID();
        TargetFund src = TargetFund.builder().id(id).name("Машина")
                .purchaseType(FundPurchaseType.CREDIT).wishlistStatus(WishlistStatus.OPEN)
                .targetAmount(new BigDecimal("2000000")).targetDate(LocalDate.now().plusMonths(2))
                .creditRate(new BigDecimal("16.5")).creditTermMonths(60).build();
        when(fundRepo.findById(id)).thenReturn(Optional.of(src));
        when(fundRepo.save(any())).thenAnswer(i -> {
            TargetFund f = i.getArgument(0);
            if (f.getId() == null) f.setId(UUID.randomUUID());
            return f;
        });
        UUID ruleId = UUID.randomUUID();
        // RecurringRuleService.createFromDto returns a CreateResult carrying the rule;
        // mock to return a rule with ruleId. Match the actual return type.
        when(recurringRuleService.createFromDto(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new RecurringRuleService.CreateResult(
                        RecurringRule.builder().id(ruleId).build(), java.util.List.of()));

        var resp = service.convertItem(id,
                new ConvertWishlistRequestDto("CREDIT", "FUND_WITH_CREDIT", true));

        assertThat(src.getWishlistStatus()).isEqualTo(WishlistStatus.FIXED);
        assertThat(src.getConvertedToFundId()).isNotNull();
        assertThat(resp.recurringRuleId()).isEqualTo(ruleId);
        verify(recurringRuleService).createFromDto(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void convert_notFound_throws404() {
        UUID id = UUID.randomUUID();
        when(eventRepo.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.convertItem(id,
                new ConvertWishlistRequestDto("WISHLIST", "PLAN_EVENT", false)))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
```

> Verify before writing: the exact package of `ResourceNotFoundException` (grep `class ResourceNotFoundException`), and the exact return type + shape of `RecurringRuleService.createFromDto` (the merged recurring PR added an 8-arg overload returning a `CreateResult(rule, events)` record — confirm the record name and constructor). Adjust the mock in `convert_creditWithRecurring` to match the real signature.

- [ ] **Step 3: Run, expect FAIL**

Run: `rtk JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -Dtest=WishlistConversionServiceTest`
Expected: COMPILE FAIL.

- [ ] **Step 4: Implement `WishlistConversionService`**

`@Transactional convertItem(UUID itemId, ConvertWishlistRequestDto req)`:

1. **Load source by `sourceKind`:** `WISHLIST` → `eventRepo.findById` (a `FinancialEvent`); `SAVINGS`/`CREDIT` → `fundRepo.findById` (a `TargetFund`). Not found → throw `ResourceNotFoundException` (404). Both source types expose `wishlistStatus`, `convertedToEventId`, `convertedToFundId` getters/setters (added in Chunk 1).
2. **Already-converted guard:** if source has a non-null `convertedToEventId` OR `convertedToFundId` → `throw new ResponseStatusException(HttpStatus.CONFLICT, "already converted")` (409).
3. **Branch on `target`:**
   - `PLAN_EVENT`: build a new `FinancialEvent` (`eventKind=PLAN`, `status=PLANNED`, `type=EXPENSE`, `priority=LOW`, `wishlistStatus=null`, `category` copied from source event, `plannedAmount=source.amount`, `date=source.targetDate`, `description=source.name`); `eventRepo.save`; set `source.convertedToEventId = saved.id`; `convertedTo = (EVENT, saved.id)`; `artifactKind="PLAN_EVENT"`.
   - `FUND`: build `TargetFund` (`status=FUNDING`, `purchaseType=SAVINGS`, `wishlistStatus=FIXED`, `targetAmount=source.amount`, `name=source.name`, `targetDate=source.targetDate`); `fundRepo.save`; set `source.convertedToFundId = saved.id`; `convertedTo=(FUND, saved.id)`; `artifactKind="FUND"`.
   - `FUND_WITH_CREDIT`: build `TargetFund` (`status=FUNDING`, `purchaseType=CREDIT`, `wishlistStatus=FIXED`, `targetAmount=source.amount`, `name=source.name`, `creditRate=source.rate`, `creditTermMonths=source.termMonths`, `targetDate=source.targetDate`); `fundRepo.save`; **set `source.convertedToFundId = saved.id`** (do not forget this assignment); `convertedTo=(FUND, saved.id)`; `artifactKind="FUND_WITH_CREDIT"`. If `Boolean.TRUE.equals(req.createRecurringPayments())`, also create a PMT rule:
     ```java
     var cfg = new RecurringConfigDto(
             RecurringFrequency.MONTHLY,
             source.getTargetDate().getDayOfMonth(),   // dayOfMonth
             null,                                       // monthOfYear (MONTHLY → null)
             source.getTargetDate().plusMonths(1),       // startDate: first payment month after purchase
             source.getTargetDate().plusMonths(termMonths)); // endDate
     var ruleResult = recurringRuleService.createFromDto(
             /* category   */ null,                       // PMT has no user category (or a "Кредит" system cat if one exists)
             /* type       */ EventType.EXPENSE,
             /* plannedAmt  */ monthlyPMT,                 // computed via WishlistSimulationService.computeCreditDelta(...).monthlyPMT()
             /* priority    */ Priority.MEDIUM,
             /* description */ source.getName() + " — платёж по кредиту",
             /* targetFundId*/ savedFund.getId(),
             /* headRawInput*/ null,
             cfg);
     recurringRuleId = ruleResult.rule().getId();
     ```
     > If `createFromDto` rejects a null category, check whether a system "Кредит"/"Прочее" category exists (the recurring PR may require a non-null category) — if so, resolve it via `CategoryService`/repo and pass it. Verify against the actual `createFromDto` validation before finalizing.
4. Set `source.wishlistStatus = FIXED`; save source (`eventRepo.save` or `fundRepo.save`).
5. Return `ConvertWishlistResponseDto(itemId, "FIXED", convertedTo, artifactKind, recurringRuleId)`.

All in one `@Transactional` method (rollback covers all-or-nothing). Inject `FinancialEventRepository`, `TargetFundRepository`, `RecurringRuleService`. Use `ResourceNotFoundException` (`ru.selfin.backend.exception`) and `ResponseStatusException`.

- [ ] **Step 5: Run, expect PASS; Commit**

```bash
rtk JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -Dtest=WishlistConversionServiceTest
rtk git add backend/src/main/java/ru/selfin/backend/dto/wishlist/ backend/src/main/java/ru/selfin/backend/service/WishlistConversionService.java backend/src/test/java/ru/selfin/backend/service/WishlistConversionServiceTest.java
rtk git commit -m "feat(service): WishlistConversionService (OPEN→FIXED→artifact) (TDD)"
```

---

### Task 3.4: Controllers — WishlistController + UserSettingsController + status PATCH endpoints

**Files:**
- Create: `backend/src/main/java/ru/selfin/backend/controller/WishlistController.java`
- Create: `backend/src/main/java/ru/selfin/backend/controller/UserSettingsController.java`
- Modify: `backend/src/main/java/ru/selfin/backend/controller/FinancialEventController.java`
- Modify: `backend/src/main/java/ru/selfin/backend/controller/TargetFundController.java`

- [ ] **Step 1: Create `WishlistController`**

```java
package ru.selfin.backend.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import ru.selfin.backend.dto.wishlist.*;
import ru.selfin.backend.service.WishlistConversionService;
import ru.selfin.backend.service.WishlistSimulationService;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/wishlist")
@RequiredArgsConstructor
public class WishlistController {

    private final WishlistSimulationService simulationService;
    private final WishlistConversionService conversionService;

    @GetMapping("/simulation")
    public WishlistSimulationDto getSimulation(
            @RequestParam(defaultValue = "36") int horizonMonths) {
        int safe = Math.min(Math.max(horizonMonths, 1), 60);
        return simulationService.getSimulation(safe);
    }

    @PostMapping("/simulation/recompute")
    public RecomputeResponseDto recompute(@Valid @RequestBody RecomputeRequestDto req) {
        return simulationService.recomputeItemDelta(req);
    }

    @PostMapping("/items/{itemId}/convert")
    public ConvertWishlistResponseDto convert(
            @PathVariable UUID itemId,
            @Valid @RequestBody ConvertWishlistRequestDto req) {
        return conversionService.convertItem(itemId, req);
    }
}
```

- [ ] **Step 2: Create `UserSettingsController`**

```java
package ru.selfin.backend.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import ru.selfin.backend.dto.wishlist.WishlistThresholdsDto;
import ru.selfin.backend.service.UserSettingsService;

@RestController
@RequestMapping("/api/v1/settings")
@RequiredArgsConstructor
public class UserSettingsController {

    private final UserSettingsService service;

    @GetMapping("/wishlist")
    public WishlistThresholdsDto get() {
        return service.getWishlistSettings();
    }

    @PutMapping("/wishlist")
    public WishlistThresholdsDto update(@Valid @RequestBody WishlistThresholdsDto dto) {
        return service.updateWishlistSettings(dto);
    }
}
```

- [ ] **Step 3: Add PATCH status endpoints**

`FinancialEventController` (add method):
```java
    @PatchMapping("/{id}/wishlist-status")
    public void setWishlistStatus(
            @PathVariable UUID id,
            @RequestBody ru.selfin.backend.dto.wishlist.WishlistStatusUpdateDto dto) {
        eventService.setWishlistStatus(id,
                ru.selfin.backend.model.enums.WishlistStatus.valueOf(dto.status()));
    }
```

`TargetFundController` (add analogous method calling `fundService.setWishlistStatus`). Match the existing controller's field/var name for the injected service.

- [ ] **Step 4: Compile + run all unit tests**

Run: `rtk JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -Dtest='!*IT'`
Expected: PASS (1 pre-existing Docker error acceptable).

- [ ] **Step 5: Commit**

```bash
rtk git add backend/src/main/java/ru/selfin/backend/controller/
rtk git commit -m "feat(controller): wishlist + settings controllers + status PATCH endpoints"
```

---

## Chunk 4 — Integration tests (Testcontainers + MockMvc)

End-to-end HTTP coverage. Requires Docker; if unavailable locally, compile + code-review + commit, defer the run to CI.

### Task 4.1: IT scaffold + simulation happy path

**Files:**
- Create: `backend/src/test/java/ru/selfin/backend/WishlistControllerIT.java`

- [ ] **Step 1: Bootstrap class mirroring `CapitalControllerIT`**

Same `@SpringBootTest`, `@AutoConfigureMockMvc`, `@Testcontainers`, `@Container @ServiceConnection static PostgreSQLContainer`. Autowire `MockMvc`, `ObjectMapper`, and the repositories needed for assertions.

- [ ] **Step 2: First IT — empty DB simulation**

```java
@Test
void getSimulation_emptyDb_returnsDefaults() throws Exception {
    mockMvc.perform(get("/api/v1/wishlist/simulation"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isArray())
        .andExpect(jsonPath("$.thresholds.cashBufferMonths").value(1.0));
}
```

- [ ] **Step 3: Run + commit**

Run: `rtk JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -Dtest=WishlistControllerIT` (or `test-compile` + defer if Docker down).
```bash
rtk git add backend/src/test/java/ru/selfin/backend/WishlistControllerIT.java
rtk git commit -m "test(it): wishlist simulation empty-db scaffold"
```

---

### Task 4.2: IT — create wishlist item, appears in simulation with delta

- [ ] **Step 1: Test** — Seed a wishlist event so it produces a delta. **Key field mapping:** `WishlistItemDto.targetDate` is populated from `FinancialEvent.date` (see Task 2.6 mapping). So to get a non-empty delta, the event MUST have a future `date`. Simplest: autowire `FinancialEventRepository` and save a `FinancialEvent` with `priority=LOW`, `wishlistStatus=OPEN`, `type=EXPENSE`, `plannedAmount=150000`, `date=LocalDate.now().plusMonths(6)`, `category=<seeded>`. Then GET `/simulation`; assert `items[]` contains an entry with `kind=WISHLIST`, `targetDate` = that date, and `delta` of size 1 with negative `accountDelta`. (Also assert a DISMISSED-status event does NOT appear.)
- [ ] **Step 2: Run + commit** — `test(it): wishlist item appears in simulation with delta`

---

### Task 4.3: IT — convert WISHLIST → PLAN_EVENT end-to-end

- [ ] **Step 1: Test** — create OPEN wishlist event, `POST /wishlist/items/{id}/convert` with `target=PLAN_EVENT`. Assert: response `convertedTo.kind=EVENT`; source event now `wishlistStatus=FIXED` + `convertedToEventId` set; a new PLAN event exists at the target date queryable via `GET /events?startDate&endDate` with `wishlist_status=null`.
- [ ] **Step 2: Run + commit** — `test(it): convert wishlist to plan event`

---

### Task 4.4: IT — double convert → 409

- [ ] **Step 1: Test** — convert twice; second returns 409 with body referencing existing artifact id.
- [ ] **Step 2: Run + commit** — `test(it): double conversion returns 409`

---

### Task 4.5: IT — convert CREDIT → FUND_WITH_CREDIT + recurring

- [ ] **Step 1: Test** — seed a CREDIT wishlist fund via repo autowire: `TargetFund` with `purchaseType=CREDIT`, `wishlistStatus=OPEN`, `targetAmount=2000000`, `targetDate=LocalDate.now().plusMonths(2)`, **`creditRate=16.5`, `creditTermMonths=60`** (both non-null — required for `computeCreditDelta` and the PMT rule). Convert with `target=FUND_WITH_CREDIT, createRecurringPayments=true`. Assert: a new TargetFund exists (query `TargetFundRepository`), a RecurringRule exists (query `RecurringRuleRepository`), response has non-null `recurringRuleId`, and the source fund now has `wishlistStatus=FIXED` + `convertedToFundId` set.
- [ ] **Step 2: Run + commit** — `test(it): convert credit creates fund + recurring rule`

---

### Task 4.6: IT — status FIXED→OPEN keeps artifact

- [ ] **Step 1: Test** — convert to PLAN_EVENT (FIXED), then PATCH wishlist-status back to OPEN. Assert: `convertedToEventId` still set, the artifact event still exists.
- [ ] **Step 2: Run + commit** — `test(it): status FIXED to OPEN preserves artifact`

---

### Task 4.7: IT — settings round-trip

- [ ] **Step 1: Test** — `PUT /settings/wishlist` with `{capitalThresholdRub: 1000000, cashBufferMonths: 2.0}`, then `GET` returns the same. Then `PUT` with negative buffer → 400.
- [ ] **Step 2: Run + commit** — `test(it): settings round-trip + validation`

---

### Task 4.8: IT — FIXED-without-conversion item affects /strategy timeline

- [ ] **Step 1: Test** — deterministic two-call comparison. (a) Seed a FIXED wishlist event WITHOUT conversion (`wishlistStatus=FIXED`, `convertedToEventId=null`, `convertedToFundId=null`, `date=LocalDate.now().plusMonths(6)`, `plannedAmount=300000`, `type=EXPENSE`). `GET /api/v1/strategy/timeline`, capture `balance` of the point at month `now+6` → call it `withFixed`. (b) PATCH the event's `wishlist-status` to `DISMISSED`. `GET /api/v1/strategy/timeline` again, capture the same month's `balance` → `withoutFixed`. (c) Assert `withFixed < withoutFixed` (the FIXED outflow lowered the balance) and approximately `withoutFixed - withFixed ≈ 300000` (allow rounding tolerance). This pins the Task 2.6 overlay deterministically.
- [ ] **Step 2: Run + commit** — `test(it): fixed wishlist item affects strategy timeline`

---

## Chunk 5 — Frontend types, API, hook, utils (+ unit tests)

Pure data-layer + logic on the frontend. No visual components yet. Vitest covers the composition + risk math.

### Task 5.1: TypeScript types

**Files:**
- Modify: `frontend/src/types/api.ts`

- [ ] **Step 1: Add types**

```ts
export type WishlistStatus = 'OPEN' | 'FIXED' | 'DISMISSED';
export type WishlistKind = 'WISHLIST' | 'SAVINGS' | 'CREDIT';

export interface MonthDelta {
    monthIndex: number;
    accountDelta: number;
    capitalDelta: number;
    fundDelta?: number | null;
    liabilityDelta?: number | null;
}

export interface ConvertedTo { kind: 'EVENT' | 'FUND'; id: string; }

export interface WishlistItem {
    id: string;
    kind: WishlistKind;
    name: string;
    amount: number;
    targetDate: string;       // YYYY-MM-DD
    status: WishlistStatus;
    convertedTo: ConvertedTo | null;
    delta: MonthDelta[];
    categoryId?: string | null;
    monthlyContribution?: number | null;
    rate?: number | null;
    termMonths?: number | null;
    monthlyPMT?: number | null;
}

export interface WishlistThresholds {
    capitalThresholdRub: number | null;
    cashBufferMonths: number;
}

export interface WishlistConstraints {
    monthlyExpensesAvg: number;
    monthlyIncomeAvg: number;
    currentCapital: number;
    maxWishlistAmount: number;
    maxCreditAmount: number;
}

export interface WishlistSimulationDto {
    baseline: StrategyTimelineDto;   // existing type
    items: WishlistItem[];
    thresholds: WishlistThresholds;
    constraints: WishlistConstraints;
}

export interface RecomputeResponse {
    delta: MonthDelta[];
    monthlyContribution?: number | null;
    monthlyPMT?: number | null;
}

export interface ConvertResponse {
    wishlistItemId: string;
    newStatus: WishlistStatus;
    convertedTo: ConvertedTo;
    artifactKind: 'PLAN_EVENT' | 'FUND' | 'FUND_WITH_CREDIT';
    recurringRuleId?: string | null;
}
```

- [ ] **Step 2: Typecheck**

Run: `cd frontend && npx tsc --noEmit`
Expected: clean. (`StrategyTimelineDto` already exists in `types/api.ts`.)

- [ ] **Step 3: Commit**

```bash
rtk git add frontend/src/types/api.ts
rtk git commit -m "feat(frontend): wishlist types"
```

---

### Task 5.2: API client functions

**Files:**
- Modify: `frontend/src/api/index.ts`

- [ ] **Step 1: Add functions**

```ts
export const fetchWishlistSimulation = (horizonMonths = 36) =>
    get<WishlistSimulationDto>(`/wishlist/simulation?horizonMonths=${horizonMonths}`);

export const recomputeWishlistItem = (body: {
    kind: WishlistKind; amount: number; targetDate: string; rate?: number; termMonths?: number;
}) => post<RecomputeResponse>('/wishlist/simulation/recompute', body);

export const convertWishlistItem = (itemId: string, body: {
    sourceKind: WishlistKind; target: 'PLAN_EVENT' | 'FUND' | 'FUND_WITH_CREDIT'; createRecurringPayments?: boolean;
}) => post<ConvertResponse>(`/wishlist/items/${itemId}/convert`, body);

export const setEventWishlistStatus = (id: string, status: WishlistStatus) =>
    patch<void>(`/events/${id}/wishlist-status`, { status });

export const setFundWishlistStatus = (id: string, status: WishlistStatus) =>
    patch<void>(`/funds/${id}/wishlist-status`, { status });

export const fetchWishlistSettings = () =>
    get<WishlistThresholds>('/settings/wishlist');

export const updateWishlistSettings = (body: WishlistThresholds) =>
    put<WishlistThresholds>('/settings/wishlist', body);
```

Add these to the existing `import type { ... } from '../types/api'` block at the top of `api/index.ts`: `WishlistSimulationDto`, `WishlistThresholds`, `WishlistKind`, `WishlistStatus`, `RecomputeResponse`, `ConvertResponse`. (`MonthDelta`/`WishlistItem` are only used transitively via `WishlistSimulationDto`, no direct import needed.)

- [ ] **Step 2: Typecheck + commit**

```bash
cd frontend && npx tsc --noEmit
cd .. && rtk git add frontend/src/api/index.ts
rtk git commit -m "feat(frontend-api): wishlist endpoints"
```

---

### Task 5.3: `wishlistUtils` — composeTimeline + scaleDelta (TDD)

**Files:**
- Create: `frontend/src/components/wishlist/wishlistUtils.ts`
- Create: `frontend/src/components/wishlist/wishlistUtils.test.ts`

- [ ] **Step 1: Write failing tests**

```ts
import { describe, it, expect } from 'vitest';
import { composeTimeline, scaleDelta } from './wishlistUtils';
import type { MonthDelta } from '../../types/api';

const baseline = [
    { account: 100000, capital: 500000 },
    { account: 90000, capital: 500000 },
    { account: 80000, capital: 500000 },
]; // index 0 = current+1

describe('composeTimeline', () => {
    it('returns baseline when no active items', () => {
        const out = composeTimeline(baseline, []);
        expect(out).toEqual(baseline);
    });

    it('applies a single delta cumulatively from its month', () => {
        const delta: MonthDelta[] = [{ monthIndex: 1, accountDelta: -50000, capitalDelta: -50000 }];
        const out = composeTimeline(baseline, [{ active: true, delta }]);
        expect(out[0].account).toBe(100000);          // before
        expect(out[1].account).toBe(40000);           // 90000 - 50000
        expect(out[2].account).toBe(30000);           // 80000 - 50000 (cumulative)
    });

    it('excludes disabled items', () => {
        const delta: MonthDelta[] = [{ monthIndex: 0, accountDelta: -50000, capitalDelta: 0 }];
        const out = composeTimeline(baseline, [{ active: false, delta }]);
        expect(out).toEqual(baseline);
    });

    it('sums multiple items in the same month', () => {
        const a: MonthDelta[] = [{ monthIndex: 0, accountDelta: -10000, capitalDelta: 0 }];
        const b: MonthDelta[] = [{ monthIndex: 0, accountDelta: -20000, capitalDelta: 0 }];
        const out = composeTimeline(baseline, [{ active: true, delta: a }, { active: true, delta: b }]);
        expect(out[0].account).toBe(70000);   // 100000 - 30000
    });
});

describe('scaleDelta', () => {
    it('scales linearly by amount ratio', () => {
        const delta: MonthDelta[] = [{ monthIndex: 0, accountDelta: -100000, capitalDelta: -100000 }];
        const scaled = scaleDelta(delta, 100000, 150000);  // baseAmount=100k, override=150k
        expect(scaled[0].accountDelta).toBe(-150000);
        expect(scaled[0].capitalDelta).toBe(-150000);
    });
});
```

> Cumulative semantics pinned: a delta is a *flow* applied at `monthIndex` and persisting forward — matches backend `applyDeltas`. `composeTimeline` accumulates running sums of `accountDelta`/`capitalDelta` across months ≥ each delta's `monthIndex`.

- [ ] **Step 2: Run, expect FAIL**

Run: `cd frontend && npx vitest run src/components/wishlist/wishlistUtils.test.ts`
Expected: FAIL (module not found).

- [ ] **Step 3: Implement `composeTimeline` + `scaleDelta`**

```ts
import type { MonthDelta } from '../../types/api';

export interface BaselinePoint { account: number; capital: number; }
export interface ActiveItem { active: boolean; delta: MonthDelta[]; }

/**
 * Накладывает delta всех активных items на baseline. Delta трактуется как поток (flow),
 * применяемый начиная с monthIndex и накапливающийся вперёд — зеркалит backend applyDeltas.
 */
export function composeTimeline(baseline: BaselinePoint[], items: ActiveItem[]): BaselinePoint[] {
    const accountCum = new Array(baseline.length).fill(0);
    const capitalCum = new Array(baseline.length).fill(0);
    for (const item of items) {
        if (!item.active) continue;
        for (const d of item.delta) {
            for (let i = d.monthIndex; i < baseline.length; i++) {
                accountCum[i] += d.accountDelta;
                capitalCum[i] += d.capitalDelta;
            }
        }
    }
    return baseline.map((p, i) => ({
        account: p.account + accountCum[i],
        capital: p.capital + capitalCum[i],
    }));
}

/** Линейно масштабирует delta по отношению override/base (для слайдера суммы хотелки). */
export function scaleDelta(delta: MonthDelta[], baseAmount: number, override: number): MonthDelta[] {
    if (baseAmount === 0) return delta;
    const k = override / baseAmount;
    return delta.map(d => ({
        ...d,
        accountDelta: d.accountDelta * k,
        capitalDelta: d.capitalDelta * k,
        fundDelta: d.fundDelta != null ? d.fundDelta * k : d.fundDelta,
        liabilityDelta: d.liabilityDelta != null ? d.liabilityDelta * k : d.liabilityDelta,
    }));
}
```

- [ ] **Step 4: Run, expect PASS; Commit**

```bash
cd frontend && npx vitest run src/components/wishlist/wishlistUtils.test.ts
cd .. && rtk git add frontend/src/components/wishlist/wishlistUtils.ts frontend/src/components/wishlist/wishlistUtils.test.ts
rtk git commit -m "feat(frontend): composeTimeline + scaleDelta (TDD)"
```

---

### Task 5.4: `wishlistUtils` — riskZones (TDD)

**Files:**
- Modify: `frontend/src/components/wishlist/wishlistUtils.ts`
- Modify: `frontend/src/components/wishlist/wishlistUtils.test.ts`

- [ ] **Step 1: Write failing tests**

```ts
import { riskZones } from './wishlistUtils';

describe('riskZones', () => {
    const thresholds = { capitalThresholdRub: 1000000, cashBufferMonths: 1 };
    const monthlyExpenses = 95000;

    it('account negative is red', () => {
        const zones = riskZones([{ account: -1, capital: 2000000 }], thresholds, monthlyExpenses);
        expect(zones[0]).toBe('red');
    });
    it('account below buffer is yellow', () => {
        const zones = riskZones([{ account: 50000, capital: 2000000 }], thresholds, monthlyExpenses);
        expect(zones[0]).toBe('yellow');
    });
    it('capital below threshold is red', () => {
        const zones = riskZones([{ account: 500000, capital: 900000 }], thresholds, monthlyExpenses);
        expect(zones[0]).toBe('red');
    });
    it('capital near threshold is yellow', () => {
        const zones = riskZones([{ account: 500000, capital: 1050000 }], thresholds, monthlyExpenses);
        expect(zones[0]).toBe('yellow');
    });
    it('null capital threshold disables capital criterion', () => {
        const zones = riskZones([{ account: 500000, capital: 1 }],
            { capitalThresholdRub: null, cashBufferMonths: 1 }, monthlyExpenses);
        expect(zones[0]).toBe('green');
    });
    it('combined risk takes the worse of the two', () => {
        const zones = riskZones([{ account: 50000, capital: 900000 }], thresholds, monthlyExpenses);
        expect(zones[0]).toBe('red');   // account=yellow, capital=red → red
    });
});
```

- [ ] **Step 2: Run, expect FAIL**

Run: `cd frontend && npx vitest run src/components/wishlist/wishlistUtils.test.ts`
Expected: FAIL (`riskZones` undefined).

- [ ] **Step 3: Implement `riskZones`**

```ts
export type RiskLevel = 'green' | 'yellow' | 'red';

export function riskZones(
    points: BaselinePoint[],
    thresholds: { capitalThresholdRub: number | null; cashBufferMonths: number },
    monthlyExpensesAvg: number,
): RiskLevel[] {
    const buffer = monthlyExpensesAvg * thresholds.cashBufferMonths;
    return points.map(p => {
        const accountRisk: RiskLevel =
            p.account < 0 ? 'red' : p.account < buffer ? 'yellow' : 'green';
        let capitalRisk: RiskLevel = 'green';
        if (thresholds.capitalThresholdRub != null) {
            const t = thresholds.capitalThresholdRub;
            capitalRisk = p.capital < t ? 'red' : p.capital < t * 1.1 ? 'yellow' : 'green';
        }
        return worse(accountRisk, capitalRisk);
    });
}

function worse(a: RiskLevel, b: RiskLevel): RiskLevel {
    const rank = { green: 0, yellow: 1, red: 2 };
    return rank[a] >= rank[b] ? a : b;
}
```

- [ ] **Step 4: Run, expect PASS; Commit**

```bash
cd frontend && npx vitest run src/components/wishlist/wishlistUtils.test.ts
cd .. && rtk git add frontend/src/components/wishlist/wishlistUtils.ts frontend/src/components/wishlist/wishlistUtils.test.ts
rtk git commit -m "feat(frontend): riskZones (TDD)"
```

---

### Task 5.5: `useWishlistSimulation` hook

**Files:**
- Create: `frontend/src/components/wishlist/useWishlistSimulation.ts`

- [ ] **Step 1: Implement the hook**

Mirror `useStrategyTimeline` structure (fetch + tick refetch). Additionally hold local UI state:
- `activeMap: Record<itemId, boolean>` — default true for OPEN+FIXED items.
- `overrideMap: Record<itemId, { amount?: number; targetDate?: string; delta?: MonthDelta[] }>` — slider overrides; amount-only overrides reuse `scaleDelta` locally, parameter changes store a recomputed `delta`.

**Baseline extraction (index alignment — load-bearing):**
- `monthIndex=0` means `current+1`. So the composed arrays must cover ONLY the future segment, aligned so array index `i` ↔ `monthIndex=i`.
- Filter `data.baseline.points` to `phase === 'FUTURE'` (these are already in chronological order, `current+1 … current+horizon`). Past + current points are NOT part of the simulation surface.
- **Field mapping:** `StrategyTimelinePointDto` has `balance` and `capital` (no `account` field). Map each future point to `BaselinePoint` as `{ account: point.balance, capital: point.capital }`. (`balance` is the running account/cashflow balance — what the spec calls "account" on the chart.)
- Derived `composed: BaselinePoint[]` via `composeTimeline(futureBaseline, activeItemsWithOverrides)`; `zones: RiskLevel[]` via `riskZones(composed, thresholds, constraints.monthlyExpensesAvg)`.

- `toggleItem(id)`, `setAmountOverride(id, amount)`, `setDateOverride(id, date)`, `applyRecomputedDelta(id, delta)`, `refetch()`.

Return `{ data, isLoading, error, refetch, activeMap, overrideMap, composed, zones, futureMonths, actions }` where `futureMonths` is the array of `YYYY-MM` labels from the FUTURE points (for the chart x-axis).

> Keep the hook focused on state + derivation. No network call on slider drag (only on release, done by the card via the API). Recompute calls are triggered by the card and pushed back via `applyRecomputedDelta`.

- [ ] **Step 2: Typecheck**

Run: `cd frontend && npx tsc --noEmit`
Expected: clean.

> **Testing decision (deviation from spec's generic test list, documented):** The spec listed `useWishlistSimulation.test.ts` + component tests using MSW. The actual project has **no React test infrastructure** — vitest runs in the `node` environment, there is no `@testing-library/react`, no `jsdom`, no MSW; the only existing frontend test (`savingsStrategyUtils.test.ts`) is a pure-function test. Introducing React-rendering + network-mock infra is a disproportionate scope addition for this PR and fights the codebase convention. Instead: ALL non-trivial hook logic is extracted into the pure functions `composeTimeline`, `scaleDelta`, `riskZones` (fully unit-tested in Tasks 5.3–5.4). The hook is a thin fetch+state+derivation wrapper over those tested functions; its end-to-end behavior is covered by the manual smoke matrix in Task 7.4. Introducing React test infra can be a separate follow-up if desired. Do NOT add jsdom/testing-library/MSW in this PR.

- [ ] **Step 3: Commit**

```bash
rtk git add frontend/src/components/wishlist/useWishlistSimulation.ts
rtk git commit -m "feat(frontend): useWishlistSimulation hook"
```

---

## Chunk 6 — Frontend components + page + navigation

The visual layer. Components are small and single-responsibility. Native `title` tooltips and native range inputs (matching existing `SavingsStrategySection` slider style). Use existing Shadcn `ui/` components (dialog, button, input, select).

### Task 6.1: `WishlistRiskBadge`

**Files:**
- Create: `frontend/src/components/wishlist/WishlistRiskBadge.tsx`

- [ ] **Step 1: Implement**

A small colored dot + optional label. Props: `level: RiskLevel`. Colors: green `#10b981`, yellow `#f59e0b`, red `#ef4444`, plus a muted gray when `level` is undefined (disabled item). Native `title` with a Russian explanation.

- [ ] **Step 2: Typecheck + commit**

```bash
cd frontend && npx tsc --noEmit
cd .. && rtk git add frontend/src/components/wishlist/WishlistRiskBadge.tsx
rtk git commit -m "feat(frontend): WishlistRiskBadge"
```

---

### Task 6.2: `WishlistImpactChart`

**Files:**
- Create: `frontend/src/components/wishlist/WishlistImpactChart.tsx`

- [ ] **Step 1: Implement**

Recharts `ComposedChart`. Props: `months: string[]` (YYYY-MM labels), `account: number[]`, `capital: number[]`, `zones: RiskLevel[]`, `capitalThreshold: number | null`. Render:
- Left Y-axis (account, rubles), right Y-axis (capital).
- `<Line>` for account and capital.
- Background `<ReferenceArea>` per month colored by `zones[i]` at 10% opacity.
- Horizontal `<ReferenceLine y={capitalThreshold}>` (right axis) when not null.
- Native browser tooltip is insufficient here — use recharts `<Tooltip>` with a minimal content renderer showing account+capital for the hovered month.

Reuse formatting helpers from existing `strategyChartUtils.ts` where applicable (import, don't duplicate).

- [ ] **Step 2: Typecheck + commit**

```bash
cd frontend && npx tsc --noEmit
cd .. && rtk git add frontend/src/components/wishlist/WishlistImpactChart.tsx
rtk git commit -m "feat(frontend): WishlistImpactChart with risk zones"
```

---

### Task 6.3: `WishlistThresholdsHeader`

**Files:**
- Create: `frontend/src/components/wishlist/WishlistThresholdsHeader.tsx`

- [ ] **Step 1: Implement**

Two inputs: capital threshold (number, nullable — empty = disabled), cash buffer months (number). Props: `value: WishlistThresholds`, `monthlyExpensesAvg: number`, `onChange(next)`. Debounce the `onChange`→`updateWishlistSettings` PUT at **800 ms**. Show derived line: «≈ не давать счёту опуститься ниже {monthlyExpensesAvg × cashBufferMonths} ₽».

- [ ] **Step 2: Typecheck + commit**

```bash
cd frontend && npx tsc --noEmit
cd .. && rtk git add frontend/src/components/wishlist/WishlistThresholdsHeader.tsx
rtk git commit -m "feat(frontend): WishlistThresholdsHeader (debounced 800ms)"
```

---

### Task 6.4: `FixWishlistDialog` + `AddWishlistDialog` + `DeleteWishlistDialog`

**Files:**
- Create: `frontend/src/components/wishlist/FixWishlistDialog.tsx`
- Create: `frontend/src/components/wishlist/AddWishlistDialog.tsx`
- Create: `frontend/src/components/wishlist/DeleteWishlistDialog.tsx`

- [ ] **Step 1: `FixWishlistDialog`**

Shadcn `Dialog`. Props: `open`, `item: WishlistItem`, `onClose`, `onConfirm(target, createRecurringPayments)`. Radio target with default by `item.kind` (WISHLIST→PLAN_EVENT, SAVINGS→FUND, CREDIT→FUND_WITH_CREDIT). For `FUND_WITH_CREDIT` show a "Создать платёжный график (recurring)" checkbox. A "Зафиксировать без конверсии" secondary action (status FIXED only, no conversion).

- [ ] **Step 2: `AddWishlistDialog`**

Dialog with kind selector (WISHLIST/SAVINGS/CREDIT) and fields: name, amount, targetDate, plus credit params when CREDIT. Props: `open`, `onClose`, `onCreate(payload)`. Creating a WISHLIST posts a LOW event (existing `createEvent` with priority=LOW, `date=targetDate`) then PATCHes status OPEN; creating SAVINGS/CREDIT posts a fund (existing `createFund`) then PATCHes fund status OPEN.

> **Two-call flow + failure mode (document in a comment and handle):** creation is two sequential network calls — POST entity, then PATCH `wishlist-status=OPEN`. If the POST succeeds but the PATCH fails, the entity exists with `wishlist_status=NULL` and is invisible on `/wishlist` (a "stranded" event/fund). Acceptable behavior for this PR: surface a toast/error and trigger a `refetch()` of the parent funds/events so the user can see and retry; the stranded entity is tolerated (it shows up on `/funds` for a fund, or is simply an undated LOW event). Do NOT attempt a compensating delete. Wrap the two calls in a try/catch; on PATCH failure, show the error and still close-or-keep-open per the catch. Keep this explicit so the implementer doesn't ship a silent inconsistency.

- [ ] **Step 3: `DeleteWishlistDialog`**

Confirm dialog. If `item.convertedTo != null` show a checkbox "удалить также созданный план/копилку". Props: `open`, `item`, `onClose`, `onConfirm(alsoDeleteArtifact: boolean)`.

- [ ] **Step 4: Typecheck + commit**

```bash
cd frontend && npx tsc --noEmit
cd .. && rtk git add frontend/src/components/wishlist/FixWishlistDialog.tsx frontend/src/components/wishlist/AddWishlistDialog.tsx frontend/src/components/wishlist/DeleteWishlistDialog.tsx
rtk git commit -m "feat(frontend): wishlist dialogs (fix/add/delete)"
```

---

### Task 6.5: `WishlistItemCard`

**Files:**
- Create: `frontend/src/components/wishlist/WishlistItemCard.tsx`

- [ ] **Step 1: Implement**

Props: `item`, `active`, `amountOverride`, `dateOverride`, `soloRisk: RiskLevel`, callbacks `onToggleActive`, `onAmountChange`, `onDateChange`, `onParamsRecompute(req)`, `onFix`, `onDelete`, `onStatusChange`. Layout: checkbox + name + `<WishlistRiskBadge>` + Fix button; two range sliders (amount with min 0 / max from constraints; date mapped to month offset 1..36); for CREDIT, an expandable params block (rate, termMonths) that calls `onParamsRecompute` on change; PMT/contribution display line. On slider release, persist via `updateEvent`/`updateFund` (passed as callback from page). Use the existing slider visual style (`type="range"` with `accent-[hsl(var(--primary))]`).

- [ ] **Step 2: Typecheck + commit**

```bash
cd frontend && npx tsc --noEmit
cd .. && rtk git add frontend/src/components/wishlist/WishlistItemCard.tsx
rtk git commit -m "feat(frontend): WishlistItemCard with sliders + risk badge"
```

---

### Task 6.6: `WishlistItemList`

**Files:**
- Create: `frontend/src/components/wishlist/WishlistItemList.tsx`

- [ ] **Step 1: Implement**

Three sections: OPEN (always expanded), FIXED (collapsed, with count), DISMISSED (collapsed, with count). Maps items to `<WishlistItemCard>`. Props: pass-through of items + per-item state + callbacks from the page/hook. A "+ Добавить хотелку" button at the bottom opens `AddWishlistDialog`.

- [ ] **Step 2: Typecheck + commit**

```bash
cd frontend && npx tsc --noEmit
cd .. && rtk git add frontend/src/components/wishlist/WishlistItemList.tsx
rtk git commit -m "feat(frontend): WishlistItemList with three sections"
```

---

### Task 6.7: `Wishlist` page + navigation + route

**Files:**
- Create: `frontend/src/pages/Wishlist.tsx`
- Modify: `frontend/src/components/BottomNav.tsx`
- Modify: router file (find where `/strategy` route is registered — likely `frontend/src/App.tsx`)

- [ ] **Step 1: Implement the page**

Composes `useWishlistSimulation` + `WishlistThresholdsHeader` + `WishlistImpactChart` + `WishlistItemList`. Wires loading/error/empty/partial states matching `Strategy.tsx` patterns. Derives `composed` account/capital arrays and `zones` from the hook + `riskZones`. Computes each item's solo-risk for its badge.

- [ ] **Step 2: Add nav entry**

In `BottomNav.tsx` add `{ to: '/wishlist', icon: <pick lucide icon e.g. Sparkles or Target>, label: 'Хотелки' }`. Note: nav now has 8 items — verify layout doesn't overflow on narrow screens; if it does, this is acceptable for now (flag in smoke test). Import the icon.

- [ ] **Step 3: Add route**

In the router file, register `<Route path="/wishlist" element={<Wishlist />} />` mirroring the `/strategy` route.

- [ ] **Step 4: Typecheck + build**

Run: `cd frontend && npx tsc --noEmit && rtk npm run build`
Expected: build succeeds.

- [ ] **Step 5: Commit**

```bash
rtk git add frontend/src/pages/Wishlist.tsx frontend/src/components/BottomNav.tsx frontend/src/App.tsx
rtk git commit -m "feat(frontend): Wishlist page + nav + route"
```

---

## Chunk 7 — Polish: remove old sections, full verification, cleanup

### Task 7.1: Remove old wishlist + savings sections from `/funds`

**Files:**
- Modify: `frontend/src/pages/Funds.tsx`
- Delete: `frontend/src/components/WishlistSection.tsx`, `WishlistItem.tsx`, `WishlistForm.tsx`
- Delete: `frontend/src/components/funds/SavingsStrategySection.tsx`, `savingsStrategyUtils.ts`

- [ ] **Step 1: Remove imports + usages from `Funds.tsx`**

Remove `<WishlistSection>` and `<SavingsStrategySection>` JSX and their imports. Leave the fund list + create button intact.

- [ ] **Step 2: Delete the orphaned files**

```bash
rm frontend/src/components/WishlistSection.tsx frontend/src/components/WishlistItem.tsx frontend/src/components/WishlistForm.tsx
rm frontend/src/components/funds/SavingsStrategySection.tsx frontend/src/components/funds/savingsStrategyUtils.ts
```

- [ ] **Step 3: Grep for dangling references**

```bash
rtk grep "WishlistSection|WishlistItem|WishlistForm|SavingsStrategySection|savingsStrategyUtils" frontend/src
```
Note `WishlistItem` is in the pattern because `WishlistItem.tsx` is a standalone file that could be imported independently of `WishlistSection`. Also delete the existing util test for the removed savings file — confirmed present at `frontend/src/components/funds/__tests__/savingsStrategyUtils.test.ts`:
```bash
rm frontend/src/components/funds/__tests__/savingsStrategyUtils.test.ts
```
Fix any remaining import the grep surfaces.

- [ ] **Step 4: Typecheck + build**

Run: `cd frontend && npx tsc --noEmit && rtk npm run build`
Expected: build succeeds, no unresolved imports.

- [ ] **Step 5: Commit**

```bash
rtk git add -A frontend/src
rtk git commit -m "refactor(frontend): remove old wishlist + savings sections from /funds (moved to /wishlist)"
```

---

### Task 7.2: Full backend test suite

- [ ] **Step 1: Run all unit tests**

Run: `rtk JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -Dtest='!*IT'`
Expected: PASS (1 pre-existing `BackendApplicationTests.contextLoads` Docker error acceptable). If any real failure — debug with @superpowers:systematic-debugging.

- [ ] **Step 2: If Docker available, run ITs**

Run: `rtk JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -Dtest='*IT'`
Expected: PASS. If Docker is down locally, note ITs are deferred to CI.

---

### Task 7.3: Frontend full test + build

- [ ] **Step 1: Vitest**

Run: `cd frontend && npx vitest run`
Expected: all wishlist util tests PASS, no regressions in existing tests.

- [ ] **Step 2: Build + lint**

Run: `cd frontend && rtk npm run build && rtk npm run lint`
Expected: build succeeds, lint clean.

---

### Task 7.4: Manual smoke matrix

- [ ] Create item of each kind (WISHLIST/SAVINGS/CREDIT) → appears in OPEN section.
- [ ] Toggle disable → line disappears from chart, badge greys out.
- [ ] Drag amount slider → chart re-renders with NO network request (verify DevTools Network is quiet).
- [ ] Change credit termMonths in the expandable block → one `POST /wishlist/simulation/recompute`, delta updates.
- [ ] Fix WISHLIST → PLAN_EVENT → check it appears in `/budget`.
- [ ] Fix CREDIT → FUND_WITH_CREDIT + recurring → check `/funds` shows the fund and Budget shows recurring payments.
- [ ] FIXED → OPEN → artifact remains.
- [ ] FIXED-without-conversion item → appears on `/strategy` graph (future point shifts).
- [ ] Change thresholds in header → debounce ~800 ms, risk zones recolor.
- [ ] Empty DB → empty state with "+ Добавить".
- [ ] `/funds` page after removal → fund list + create button look OK, no broken layout.
- [ ] Bottom nav with 8 items → check it doesn't overflow awkwardly on a phone-width viewport.

---

### Task 7.5: Update MEMORY.md

**Files:**
- Modify: `C:\Users\Kirill\.claude\projects\C--Users-Kirill-IdeaProjects-selfin\memory\MEMORY.md`

- [ ] **Step 1: Add a "What's done" entry**

Add under `## What's done (blockers fixed)`:

> ```
> Wishlist planning: new /wishlist page unifying wishlist items + savings + credits as
> "big decisions"; backend V18 (wishlist_status + conversion FKs + user_settings);
> WishlistSimulationService delta math (single-month / contributions / annuity PMT);
> BaselineTimelineBuilder extracted from StrategyTimelineService to break circular dep;
> FIXED-without-conversion items overlay /strategy timeline; green/yellow/red risk zones
> (cash gap + capital threshold); OPEN/FIXED/DISMISSED lifecycle with conversion to
> PLAN event / fund / fund+recurring. Old WishlistSection + SavingsStrategySection
> removed from /funds. Branch: feature/wishlist-planning.
> ```

- [ ] **Step 2: No commit needed**

MEMORY.md is in `~/.claude/...` (auto-memory), not in the project repo. Save only.

---

### Task 7.6: Final commit + branch hygiene

- [ ] **Step 1: Verify clean tree**

Run: `rtk git status`
Expected: nothing to commit.

- [ ] **Step 2: Review commit log**

Run: `rtk git log --oneline -40`
Verify chronological narrative (V18 → entities → repos → BaselineTimelineBuilder → simulation math → settings → conversion → controllers → ITs → frontend types/api/utils → components → page → cleanup). Interactive rebase is forbidden in this environment; if the log is noisy, flag to the user.

- [ ] **Step 3: Hand off via @superpowers:finishing-a-development-branch**

---

## Out of scope (per spec, do NOT build in this PR)

- Drag-and-drop точки прямо на графике.
- Сравнение нескольких сценариев одновременно («с машиной» vs «без»).
- Разбивка delta по категориям (только cashflow + capital).
- Совместный hover между `/wishlist` и `/strategy`, `/capital`.
- Multi-user.
- Telegram-нотификации о попадании в красную зону.
- Машинный совет «возьми хотелку через N месяцев чтобы остаться в зелёной зоне».
- Сезонность прогноза, прогноз дохода (PredictionService остаётся как есть).
- Persistence слайдеров на каждое движение (только onRelease).
