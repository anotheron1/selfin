# Pocket Core (ANO-12) Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Единый расчёт кармашка — pure `PocketEngine` + тонкий `PocketService` + `GET /api/v1/pocket`, миграция страницы Funds на него (шаги 1–2 миграции из спеки).

**Architecture:** Чистый статический движок без БД (паттерн `WishlistSimulationService`), тонкая Spring-обвязка собирает вход из репозиториев. Формула: кармашек(скоуп) = min прогнозной траектории − буфер. Breakdown складывается в число (инвариант объяснимости). Спека: `docs/superpowers/specs/2026-07-02-pocket-core-design.md` — при любой неоднозначности она главнее плана.

**Tech Stack:** Java 21, Spring Boot 4, JUnit 5 + AssertJ + Mockito (юнит), Testcontainers + MockMvc (IT), React 18 + TypeScript (фронт).

**Тестовый прогон:** корневого `mvnw`/`pom.xml` НЕТ — `backend/` это отдельный Maven-проект со своим врапером. Все maven-команды запускать как `cd backend && JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw ...` (Run-строки ниже уже написаны так). IT-тесты требуют запущенный Docker.

**Конвенции:** TDD (@superpowers:test-driven-development), коммит после каждой задачи. Ветка: `feature/pocket-core` (уже создана, спека закоммичена).

---

## Chunk 1: Чистое ядро — DTO + PocketEngine + табличные тесты

### Task 1: DTO-слой пакета pocket

**Files:**
- Create: `backend/src/main/java/ru/selfin/backend/dto/pocket/BreakdownType.java`
- Create: `backend/src/main/java/ru/selfin/backend/dto/pocket/EventSnapshot.java`
- Create: `backend/src/main/java/ru/selfin/backend/dto/pocket/PocketScope.java`
- Create: `backend/src/main/java/ru/selfin/backend/dto/pocket/PocketInput.java`
- Create: `backend/src/main/java/ru/selfin/backend/dto/pocket/PocketResultDto.java`
- Test: `backend/src/test/java/ru/selfin/backend/dto/pocket/PocketScopeTest.java`

- [ ] **Step 1: Написать падающий тест парсинга скоупа**

```java
package ru.selfin.backend.dto.pocket;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.*;

class PocketScopeTest {

    @Test
    void parse_null_isNextIncome() {
        assertThat(PocketScope.parse(null).type()).isEqualTo(PocketScope.Type.NEXT_INCOME);
    }

    @Test
    void parse_explicitNextIncome() {
        assertThat(PocketScope.parse("NEXT_INCOME").type()).isEqualTo(PocketScope.Type.NEXT_INCOME);
    }

    @Test
    void parse_months() {
        PocketScope s = PocketScope.parse("MONTHS:3");
        assertThat(s.type()).isEqualTo(PocketScope.Type.MONTHS);
        assertThat(s.months()).isEqualTo(3);
    }

    @Test
    void parse_monthsOutOfRange_throws() {
        assertThatIllegalArgumentException().isThrownBy(() -> PocketScope.parse("MONTHS:0"));
        assertThatIllegalArgumentException().isThrownBy(() -> PocketScope.parse("MONTHS:37"));
        assertThatIllegalArgumentException().isThrownBy(() -> PocketScope.parse("MONTHS:abc"));
    }

    @Test
    void parse_date() {
        PocketScope s = PocketScope.parse("DATE:2027-03-01");
        assertThat(s.type()).isEqualTo(PocketScope.Type.DATE);
        assertThat(s.date()).isEqualTo(LocalDate.of(2027, 3, 1));
    }

    @Test
    void parse_garbage_throws() {
        assertThatIllegalArgumentException().isThrownBy(() -> PocketScope.parse("GARBAGE"));
        assertThatIllegalArgumentException().isThrownBy(() -> PocketScope.parse("DATE:not-a-date"));
    }
}
```

- [ ] **Step 2: Прогнать тест — убедиться, что падает** (класс не существует)

Run: `cd backend && JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -Dtest=PocketScopeTest`
Expected: COMPILATION ERROR / cannot find symbol PocketScope

- [ ] **Step 3: Создать все DTO**

`BreakdownType.java`:
```java
package ru.selfin.backend.dto.pocket;

/** Типы строк разбивки «почему столько» (спека §5). Порядок = порядок рендера. */
public enum BreakdownType {
    STARTING_BALANCE, OVERDUE_RESERVE, PLANNED_EXPENSES, PLANNED_INCOME,
    UNPLANNED_FORECAST, TRAJECTORY_MIN, BUFFER, POCKET, WISHLIST_INFO
}
```

`EventSnapshot.java`:
```java
package ru.selfin.backend.dto.pocket;

import ru.selfin.backend.model.EventKind;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.enums.EventStatus;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.Priority;
import ru.selfin.backend.model.enums.WishlistStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Плоский снапшот события для чистого движка (без JPA-прокси и lazy-полей). */
public record EventSnapshot(
        UUID id,
        LocalDate date,
        EventType type,
        EventKind eventKind,
        EventStatus status,
        Priority priority,
        BigDecimal plannedAmount,
        BigDecimal factAmount,
        WishlistStatus wishlistStatus,
        boolean converted,
        String description
) {
    public static EventSnapshot from(FinancialEvent e) {
        return new EventSnapshot(
                e.getId(), e.getDate(), e.getType(), e.getEventKind(), e.getStatus(),
                e.getPriority(), e.getPlannedAmount(), e.getFactAmount(), e.getWishlistStatus(),
                e.getConvertedToEventId() != null || e.getConvertedToFundId() != null,
                e.getDescription());
    }
}
```

`PocketScope.java`:
```java
package ru.selfin.backend.dto.pocket;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * Скоуп кармашка (спека §4): NEXT_INCOME (дефолт) | MONTHS:n (1..36) | DATE:yyyy-MM-dd.
 * Парсер бросает IllegalArgumentException — обвязка мапит на 400.
 */
public record PocketScope(Type type, Integer months, LocalDate date) {

    public enum Type { NEXT_INCOME, MONTHS, DATE }

    public static final int MAX_MONTHS = 36;

    public static PocketScope parse(String raw) {
        if (raw == null || raw.isBlank() || raw.equals("NEXT_INCOME")) {
            return new PocketScope(Type.NEXT_INCOME, null, null);
        }
        if (raw.startsWith("MONTHS:")) {
            int n;
            try {
                n = Integer.parseInt(raw.substring("MONTHS:".length()));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid scope: " + raw);
            }
            if (n < 1 || n > MAX_MONTHS) {
                throw new IllegalArgumentException("MONTHS must be in [1, " + MAX_MONTHS + "]: " + raw);
            }
            return new PocketScope(Type.MONTHS, n, null);
        }
        if (raw.startsWith("DATE:")) {
            try {
                return new PocketScope(Type.DATE, null, LocalDate.parse(raw.substring("DATE:".length())));
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("Invalid scope date: " + raw);
            }
        }
        throw new IllegalArgumentException("Unknown scope: " + raw);
    }
}
```

`PocketInput.java`:
```java
package ru.selfin.backend.dto.pocket;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Вход чистого движка. Собирается PocketService (спека §3.1).
 *
 * @param events          события для БАЛАНСА и ТРАЕКТОРИИ (диапазон дат: чекпоинт..горизонт)
 * @param wishlistEvents  отдельная выборка хотелок (OPEN + FIXED) — date-range их не достаёт
 * @param overdueEvents   просроченные обязательные PLAN(PLANNED) HIGH EXPENSE без FACT-детей,
 *                        БЕЗ границы месяца (спека §3.4)
 * @param checkpointDate  null = чекпоинта нет, баланс от нуля
 * @param horizonFallback true = плановых доходов не нашлось, горизонт условный +30 дней
 * @param unplannedForecast прогноз незапланированных трат текущего месяца (≥ 0)
 * @param forecastContributors имена категорий-виновников прогноза (для details)
 */
public record PocketInput(
        LocalDate asOfDate,
        BigDecimal checkpointAmount,
        LocalDate checkpointDate,
        List<EventSnapshot> events,
        List<EventSnapshot> wishlistEvents,
        List<EventSnapshot> overdueEvents,
        PocketScope scope,
        LocalDate horizonEnd,
        boolean horizonFallback,
        BigDecimal bufferAmount,
        BigDecimal unplannedForecast,
        List<String> forecastContributors
) {}
```

`PocketResultDto.java`:
```java
package ru.selfin.backend.dto.pocket;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Ответ GET /api/v1/pocket (спека §3.6, §6). Один ответ кормит все представления. */
public record PocketResultDto(
        BigDecimal pocket,
        BigDecimal currentBalance,
        BigDecimal buffer,
        Horizon horizon,
        MinPoint minPoint,
        List<BreakdownLine> breakdown,
        List<TrajectoryPoint> trajectory,
        List<WishlistCandidate> wishlistCandidates
) {
    public record Horizon(PocketScope.Type type, LocalDate endDate, String label, boolean fallback) {}
    public record MinPoint(LocalDate date, BigDecimal balance) {}
    public record TrajectoryPoint(LocalDate date, BigDecimal balance) {}
    public record BreakdownLine(BreakdownType type, String label, BigDecimal amount, List<String> details) {}
    public record WishlistCandidate(java.util.UUID id, String description,
                                    BigDecimal plannedAmount, LocalDate date, boolean fixed) {}
}
```

- [ ] **Step 4: Прогнать тест — зелёный**

Run: `cd backend && JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -Dtest=PocketScopeTest`
Expected: PASS (6 tests)

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/ru/selfin/backend/dto/pocket backend/src/test/java/ru/selfin/backend/dto/pocket
git commit -m "feat(pocket): dto layer — scope parsing, event snapshot, engine input/output (ANO-12)"
```

### Task 2: PocketEngine — движок с табличными тестами

**Files:**
- Create: `backend/src/main/java/ru/selfin/backend/service/PocketEngine.java`
- Test: `backend/src/test/java/ru/selfin/backend/service/PocketEngineTest.java`

- [ ] **Step 1: Написать падающие тесты** (весь список юнит-кейсов спеки §9)

Хелперы теста строят `EventSnapshot` напрямую — БД не нужна. `TODAY = 2026-03-01`.

```java
package ru.selfin.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.selfin.backend.dto.pocket.*;
import ru.selfin.backend.model.EventKind;
import ru.selfin.backend.model.enums.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Табличные тесты формулы кармашка. Чистый движок, ни одного мока (спека §9). */
class PocketEngineTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 3, 1);

    // ── хелперы ──────────────────────────────────────────────────────────────

    private static EventSnapshot plan(EventType type, LocalDate date, long amount, Priority prio) {
        return new EventSnapshot(UUID.randomUUID(), date, type, EventKind.PLAN, EventStatus.PLANNED,
                prio, dec(amount), null, null, false, "plan");
    }

    private static EventSnapshot fact(EventType type, LocalDate date, long amount) {
        return new EventSnapshot(UUID.randomUUID(), date, type, EventKind.FACT, EventStatus.EXECUTED,
                Priority.MEDIUM, null, dec(amount), null, false, "fact");
    }

    private static EventSnapshot executedPlan(EventType type, LocalDate date, long planned) {
        return new EventSnapshot(UUID.randomUUID(), date, type, EventKind.PLAN, EventStatus.EXECUTED,
                Priority.MEDIUM, dec(planned), null, null, false, "executed plan");
    }

    /** Легаси-строка FUND_TRANSFER: eventKind=PLAN, но factAmount заполнен (спека §3.2). */
    private static EventSnapshot legacyTransfer(LocalDate date, long amount) {
        return new EventSnapshot(UUID.randomUUID(), date, EventType.FUND_TRANSFER, EventKind.PLAN,
                EventStatus.EXECUTED, Priority.MEDIUM, null, dec(amount), null, false, "transfer");
    }

    private static EventSnapshot wishlist(WishlistStatus st, LocalDate date, long amount, boolean converted) {
        return new EventSnapshot(UUID.randomUUID(), date, EventType.EXPENSE, EventKind.PLAN,
                EventStatus.PLANNED, Priority.LOW, dec(amount), null, st, converted, "хотелка");
    }

    private static BigDecimal dec(long v) { return BigDecimal.valueOf(v); }

    private static PocketInputBuilder base() { return PocketInputBuilder.create(); }

    /** Билдер входа с дефолтами: чекпоинт 10 000 на TODAY, горизонт NEXT_INCOME до 15.03, буфер 0. */
    static class PocketInputBuilder {
        LocalDate asOf = TODAY;
        BigDecimal checkpoint = dec(10_000);
        LocalDate checkpointDate = TODAY;
        List<EventSnapshot> events = List.of();
        List<EventSnapshot> wishlistEvents = List.of();
        List<EventSnapshot> overdue = List.of();
        PocketScope scope = new PocketScope(PocketScope.Type.NEXT_INCOME, null, null);
        LocalDate horizonEnd = LocalDate.of(2026, 3, 15);
        boolean fallback = false;
        BigDecimal buffer = BigDecimal.ZERO;
        BigDecimal forecast = BigDecimal.ZERO;
        List<String> contributors = List.of();

        static PocketInputBuilder create() { return new PocketInputBuilder(); }
        PocketInputBuilder events(EventSnapshot... e) { this.events = List.of(e); return this; }
        PocketInputBuilder wishlist(EventSnapshot... e) { this.wishlistEvents = List.of(e); return this; }
        PocketInputBuilder overdue(EventSnapshot... e) { this.overdue = List.of(e); return this; }
        PocketInputBuilder buffer(long b) { this.buffer = dec(b); return this; }
        PocketInputBuilder forecast(long f, String... names) {
            this.forecast = dec(f); this.contributors = List.of(names); return this;
        }
        PocketInputBuilder horizon(LocalDate end) { this.horizonEnd = end; return this; }
        PocketInputBuilder monthsScope(int n, LocalDate end) {
            this.scope = new PocketScope(PocketScope.Type.MONTHS, n, null); this.horizonEnd = end; return this;
        }
        PocketInputBuilder noCheckpoint() { this.checkpoint = BigDecimal.ZERO; this.checkpointDate = null; return this; }
        PocketInputBuilder fallback() { this.fallback = true; return this; }

        PocketInput build() {
            return new PocketInput(asOf, checkpoint, checkpointDate, events, wishlistEvents, overdue,
                    scope, horizonEnd, fallback, buffer, forecast, contributors);
        }
    }

    // ── сходимость со старой моделью (мартовский пример спеки free-money) ────

    @Test
    @DisplayName("Мартовский пример: план на месяц, дефолтный скоуп до зп 5.03 — min = конец горизонта")
    void marchExample_defaultScope() {
        // Checkpoint 10 000 на 1.03; зп 100 000 5-го (горизонт), аренда 30 000 10-го — ЗА горизонтом
        PocketInput in = base()
                .events(plan(EventType.INCOME, LocalDate.of(2026, 3, 5), 100_000, Priority.HIGH),
                        plan(EventType.EXPENSE, LocalDate.of(2026, 3, 10), 30_000, Priority.HIGH))
                .horizon(LocalDate.of(2026, 3, 5))
                .build();

        PocketResultDto r = PocketEngine.calculate(in);

        // До зп трат нет: траектория 10 000 → min в день 0 → 5-го +зп. Min = 10 000.
        assertThat(r.currentBalance()).isEqualByComparingTo(dec(10_000));
        assertThat(r.minPoint().balance()).isEqualByComparingTo(dec(10_000));
        assertThat(r.pocket()).isEqualByComparingTo(dec(10_000));
    }

    @Test
    @DisplayName("Факт вытесняет план: PLAN(EXECUTED) пропущен, FACT посчитан")
    void factDisplacesPlan() {
        PocketInput in = base()
                .events(executedPlan(EventType.INCOME, LocalDate.of(2026, 3, 1), 100_000),
                        fact(EventType.INCOME, LocalDate.of(2026, 3, 1), 95_000))
                .build();
        PocketResultDto r = PocketEngine.calculate(in);
        assertThat(r.currentBalance()).isEqualByComparingTo(dec(105_000)); // 10 000 + 95 000, не 205 000
    }

    @Test
    @DisplayName("Легаси FUND_TRANSFER (PLAN + factAmount) учтён как факт")
    void legacyFundTransferCountedAsFact() {
        PocketInput in = base()
                .events(legacyTransfer(LocalDate.of(2026, 3, 1), 3_000))
                .build();
        assertThat(PocketEngine.calculate(in).currentBalance()).isEqualByComparingTo(dec(7_000));
    }

    // ── просрочка ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Просрочка через границу месяца резервируется в день 0")
    void overdueAcrossMonthBoundary() {
        PocketInput in = base()
                .overdue(plan(EventType.EXPENSE, LocalDate.of(2026, 2, 27), 6_000, Priority.HIGH))
                .build();
        PocketResultDto r = PocketEngine.calculate(in);
        assertThat(r.trajectory().get(0).balance()).isEqualByComparingTo(dec(4_000));
        assertThat(r.pocket()).isEqualByComparingTo(dec(4_000));
        assertThat(r.breakdown()).anySatisfy(l -> {
            assertThat(l.type()).isEqualTo(BreakdownType.OVERDUE_RESERVE);
            assertThat(l.amount()).isEqualByComparingTo(dec(-6_000));
        });
    }

    // ── граница «сегодня» ────────────────────────────────────────────────────

    @Test
    @DisplayName("Плановый доход сегодня НЕ считается (ждём факт), плановый расход сегодня — считается")
    void todayBoundary_conservativeAsymmetry() {
        PocketInput in = base()
                .events(plan(EventType.INCOME, TODAY, 100_000, Priority.HIGH),
                        plan(EventType.EXPENSE, TODAY, 2_000, Priority.MEDIUM))
                .build();
        PocketResultDto r = PocketEngine.calculate(in);
        assertThat(r.trajectory().get(0).balance()).isEqualByComparingTo(dec(8_000)); // 10 000 − 2 000, без +100 000
    }

    // ── провал в середине (dip-aware) ────────────────────────────────────────

    @Test
    @DisplayName("MONTHS:3 с провалом в середине: pocket = min траектории, не конец горизонта")
    void stretchedScope_dipInMiddle() {
        // 12.03 страховка −9 000 (провал до 1 000), 15.03 зп +100 000: конец = 101 000, но min = 1 000
        PocketInput in = base()
                .monthsScope(3, LocalDate.of(2026, 6, 1))
                .events(plan(EventType.EXPENSE, LocalDate.of(2026, 3, 12), 9_000, Priority.HIGH),
                        plan(EventType.INCOME, LocalDate.of(2026, 3, 15), 100_000, Priority.HIGH))
                .build();
        PocketResultDto r = PocketEngine.calculate(in);
        assertThat(r.minPoint().date()).isEqualTo(LocalDate.of(2026, 3, 12));
        assertThat(r.minPoint().balance()).isEqualByComparingTo(dec(1_000));
        assertThat(r.pocket()).isEqualByComparingTo(dec(1_000));
    }

    @Test
    @DisplayName("Breakdown складывается в TRAJECTORY_MIN на растянутом скоупе (суммы до дня минимума)")
    void breakdownArithmetic_sumsToMin() {
        PocketInput in = base()
                .monthsScope(3, LocalDate.of(2026, 6, 1))
                .events(plan(EventType.EXPENSE, LocalDate.of(2026, 3, 12), 9_000, Priority.HIGH),
                        plan(EventType.INCOME, LocalDate.of(2026, 3, 15), 100_000, Priority.HIGH),
                        plan(EventType.EXPENSE, LocalDate.of(2026, 4, 20), 50_000, Priority.MEDIUM))
                .buffer(500)
                .build();
        PocketResultDto r = PocketEngine.calculate(in);

        BigDecimal starting = line(r, BreakdownType.STARTING_BALANCE).amount();
        BigDecimal expenses = line(r, BreakdownType.PLANNED_EXPENSES).amount(); // только до 12.03 → −9 000
        BigDecimal min = line(r, BreakdownType.TRAJECTORY_MIN).amount();
        assertThat(expenses).isEqualByComparingTo(dec(-9_000)); // расход 20.04 после минимума — не входит
        assertThat(starting.add(expenses)).isEqualByComparingTo(min);
        assertThat(min.add(line(r, BreakdownType.BUFFER).amount()))
                .isEqualByComparingTo(line(r, BreakdownType.POCKET).amount());
        // PLANNED_INCOME (15.03 — после минимума 12.03) в breakdown отсутствует
        assertThat(r.breakdown()).noneMatch(l -> l.type() == BreakdownType.PLANNED_INCOME);
    }

    private static PocketResultDto.BreakdownLine line(PocketResultDto r, BreakdownType t) {
        return r.breakdown().stream().filter(l -> l.type() == t).findFirst().orElseThrow();
    }

    // ── буфер ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Буфер вычитается из min; буфер 0 → pocket = min, строка BUFFER опущена")
    void buffer() {
        PocketResultDto withBuffer = PocketEngine.calculate(base().buffer(3_000).build());
        assertThat(withBuffer.pocket()).isEqualByComparingTo(dec(7_000));

        PocketResultDto zeroBuffer = PocketEngine.calculate(base().build());
        assertThat(zeroBuffer.pocket()).isEqualByComparingTo(dec(10_000));
        assertThat(zeroBuffer.breakdown()).noneMatch(l -> l.type() == BreakdownType.BUFFER);
    }

    // ── хотелки (фильтр §3.2) ────────────────────────────────────────────────

    @Test
    @DisplayName("OPEN → кандидат, не вычитается; DISMISSED — игнор; FIXED-конвертированная — игнор")
    void wishlistFilter_openDismissedConverted() {
        PocketInput in = base()
                .events(wishlist(WishlistStatus.DISMISSED, LocalDate.of(2026, 3, 10), 5_000, false),
                        wishlist(WishlistStatus.FIXED, LocalDate.of(2026, 3, 10), 7_000, true))
                .wishlist(wishlist(WishlistStatus.OPEN, null, 20_000, false))
                .horizon(LocalDate.of(2026, 3, 15))
                .build();
        PocketResultDto r = PocketEngine.calculate(in);
        assertThat(r.pocket()).isEqualByComparingTo(dec(10_000)); // ни одна не съела
        assertThat(r.wishlistCandidates()).hasSize(1);
        assertThat(r.wishlistCandidates().get(0).fixed()).isFalse();
        assertThat(line(r, BreakdownType.WISHLIST_INFO).amount()).isEqualByComparingTo(dec(20_000));
    }

    @Test
    @DisplayName("FIXED-неконвертированная с датой в окне режет траекторию; без даты — кандидат fixed=true")
    void wishlistFilter_fixedUnconverted() {
        PocketInput in = base()
                .events(wishlist(WishlistStatus.FIXED, LocalDate.of(2026, 3, 10), 4_000, false))
                .wishlist(wishlist(WishlistStatus.FIXED, null, 15_000, false))
                .build();
        PocketResultDto r = PocketEngine.calculate(in);
        assertThat(r.pocket()).isEqualByComparingTo(dec(6_000)); // 10 000 − 4 000
        assertThat(r.wishlistCandidates()).hasSize(1);
        assertThat(r.wishlistCandidates().get(0).fixed()).isTrue();
    }

    // ── прогноз незапланированных ────────────────────────────────────────────

    @Test
    @DisplayName("Прогноз размазан по дням до конца месяца и виден явной строкой")
    void unplannedForecast_spread() {
        // Окно 2.03..15.03 (горизонт раньше конца месяца) = 14 дней, прогноз 1 400 → 100/день
        PocketInput in = base().forecast(1_400, "Продукты").build();
        PocketResultDto r = PocketEngine.calculate(in);
        assertThat(r.pocket()).isEqualByComparingTo(dec(8_600)); // min в конце: 10 000 − 1 400
        PocketResultDto.BreakdownLine f = line(r, BreakdownType.UNPLANNED_FORECAST);
        assertThat(f.amount()).isEqualByComparingTo(dec(-1_400));
        assertThat(f.details()).containsExactly("Продукты");
    }

    @Test
    @DisplayName("asOfDate = последний день месяца → окно прогноза пусто, строка опущена")
    void unplannedForecast_emptyWindow() {
        LocalDate eom = LocalDate.of(2026, 3, 31);
        PocketInput in = base().forecast(5_000, "Продукты").build();
        in = new PocketInput(eom, in.checkpointAmount(), eom, in.events(), in.wishlistEvents(),
                in.overdueEvents(), in.scope(), LocalDate.of(2026, 4, 5), false,
                in.bufferAmount(), in.unplannedForecast(), in.forecastContributors());
        PocketResultDto r = PocketEngine.calculate(in);
        assertThat(r.pocket()).isEqualByComparingTo(dec(10_000));
        assertThat(r.breakdown()).noneMatch(l -> l.type() == BreakdownType.UNPLANNED_FORECAST);
    }

    // ── без чекпоинта ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Нет чекпоинта — баланс от нуля по фактам")
    void noCheckpoint() {
        PocketInput in = base().noCheckpoint()
                .events(fact(EventType.INCOME, LocalDate.of(2026, 2, 20), 50_000),
                        fact(EventType.EXPENSE, LocalDate.of(2026, 2, 25), 20_000))
                .build();
        assertThat(PocketEngine.calculate(in).currentBalance()).isEqualByComparingTo(dec(30_000));
    }

    // ── горизонт-фолбэк ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Фолбэк-горизонт помечен флагом и label без даты дохода")
    void fallbackHorizonLabel() {
        PocketResultDto r = PocketEngine.calculate(base().fallback().horizon(TODAY.plusDays(30)).build());
        assertThat(r.horizon().fallback()).isTrue();
        assertThat(r.horizon().label()).isEqualTo("30 дней вперёд (нет плановых доходов)");
    }
}
```

- [ ] **Step 2: Прогнать — убедиться, что падает** (PocketEngine не существует)

Run: `cd backend && JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -Dtest=PocketEngineTest`
Expected: COMPILATION ERROR

- [ ] **Step 3: Реализовать движок**

```java
package ru.selfin.backend.service;

import ru.selfin.backend.dto.pocket.*;
import ru.selfin.backend.model.EventKind;
import ru.selfin.backend.model.enums.EventStatus;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.WishlistStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Чистый движок кармашка (спека 2026-07-02-pocket-core-design.md §3, §5).
 * Ни одного обращения к БД и Spring-зависимостей — только вход → выход.
 *
 * Формула: кармашек(скоуп) = min прогнозной траектории баланса − буфер.
 * Breakdown-инвариант: STARTING − OVERDUE − EXPENSES(≤min) + INCOME(≤min) − FORECAST(≤min) = MIN;
 * MIN − BUFFER = POCKET.
 */
public final class PocketEngine {

    private static final DateTimeFormatter DD_MM = DateTimeFormatter.ofPattern("dd.MM");
    private static final DateTimeFormatter DD_MM_YYYY = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private PocketEngine() {}

    public static PocketResultDto calculate(PocketInput in) {
        // 1. Текущий баланс: checkpoint + факты (правило §3.2) от даты чекпоинта до asOfDate.
        BigDecimal currentBalance = in.checkpointAmount();
        for (EventSnapshot e : in.events()) {
            if (e.wishlistStatus() != null || e.factAmount() == null || e.date() == null) continue;
            if (e.date().isAfter(in.asOfDate())) continue;
            if (in.checkpointDate() != null && e.date().isBefore(in.checkpointDate())) continue;
            currentBalance = currentBalance.add(signed(e.type(), e.factAmount()));
        }

        // 2. День 0: − резерв просрочки − плановые расходы сегодняшнего дня.
        //    Плановые доходы с датой ≤ asOfDate НЕ учитываются (консервативная асимметрия §3.3.2).
        BigDecimal overdue = in.overdueEvents().stream()
                .map(EventSnapshot::plannedAmount).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal todayExpenses = in.events().stream()
                .filter(PocketEngine::isPendingPlan)
                .filter(e -> e.wishlistStatus() == null)
                .filter(e -> in.asOfDate().equals(e.date()) && e.type() != EventType.INCOME)
                .map(EventSnapshot::plannedAmount).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 3. Окно прогноза незапланированных: asOf+1 .. min(конец месяца, горизонт) (§3.5).
        LocalDate monthEnd = in.asOfDate().withDayOfMonth(in.asOfDate().lengthOfMonth());
        LocalDate forecastEnd = monthEnd.isBefore(in.horizonEnd()) ? monthEnd : in.horizonEnd();
        long forecastDays = ChronoUnit.DAYS.between(in.asOfDate(), forecastEnd); // дней в (asOf, forecastEnd]
        BigDecimal forecastTotal = forecastDays > 0 && in.unplannedForecast() != null
                ? in.unplannedForecast().max(BigDecimal.ZERO) : BigDecimal.ZERO;
        BigDecimal dailyForecast = forecastDays > 0
                ? forecastTotal.divide(BigDecimal.valueOf(forecastDays), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        // 4. Плановые события будущих дней (фильтр хотелок §3.2 применён).
        Map<LocalDate, List<EventSnapshot>> futureByDay = in.events().stream()
                .filter(PocketEngine::isPendingPlan)
                .filter(PocketEngine::allowedInTrajectory)
                .filter(e -> e.date() != null
                        && e.date().isAfter(in.asOfDate()) && !e.date().isAfter(in.horizonEnd()))
                .collect(Collectors.groupingBy(EventSnapshot::date));

        // 5. Траектория + минимум + суммы-до-минимума (для breakdown-инварианта §5).
        List<PocketResultDto.TrajectoryPoint> trajectory = new ArrayList<>();
        BigDecimal running = currentBalance.subtract(overdue).subtract(todayExpenses);
        trajectory.add(new PocketResultDto.TrajectoryPoint(in.asOfDate(), running));

        BigDecimal minBalance = running;
        LocalDate minDate = in.asOfDate();
        BigDecimal expensesCum = todayExpenses, incomeCum = BigDecimal.ZERO, forecastCum = BigDecimal.ZERO;
        BigDecimal expensesAtMin = todayExpenses, incomeAtMin = BigDecimal.ZERO, forecastAtMin = BigDecimal.ZERO;
        BigDecimal forecastSpread = BigDecimal.ZERO;

        for (LocalDate d = in.asOfDate().plusDays(1); !d.isAfter(in.horizonEnd()); d = d.plusDays(1)) {
            for (EventSnapshot e : futureByDay.getOrDefault(d, List.of())) {
                BigDecimal amount = e.plannedAmount() != null ? e.plannedAmount() : BigDecimal.ZERO;
                if (e.type() == EventType.INCOME) {
                    incomeCum = incomeCum.add(amount);
                    running = running.add(amount);
                } else {
                    expensesCum = expensesCum.add(amount);
                    running = running.subtract(amount);
                }
            }
            if (forecastDays > 0 && !d.isAfter(forecastEnd)) {
                BigDecimal dayForecast = d.equals(forecastEnd)
                        ? forecastTotal.subtract(forecastSpread) // остаток на последний день — сумма сходится точно
                        : dailyForecast;
                forecastSpread = forecastSpread.add(dayForecast);
                forecastCum = forecastCum.add(dayForecast);
                running = running.subtract(dayForecast);
            }
            trajectory.add(new PocketResultDto.TrajectoryPoint(d, running));
            if (running.compareTo(minBalance) < 0) {
                minBalance = running;
                minDate = d;
                expensesAtMin = expensesCum;
                incomeAtMin = incomeCum;
                forecastAtMin = forecastCum;
            }
        }

        BigDecimal buffer = in.bufferAmount() != null ? in.bufferAmount() : BigDecimal.ZERO;
        BigDecimal pocket = minBalance.subtract(buffer);

        // 6. Кандидаты-хотелки — ТОЛЬКО из отдельной выборки (§3.1): OPEN любые
        //    + FIXED-неконвертированные без даты. Датированные FIXED уже в траектории из events.
        List<PocketResultDto.WishlistCandidate> candidates = in.wishlistEvents().stream()
                .filter(e -> e.wishlistStatus() == WishlistStatus.OPEN
                        || (e.wishlistStatus() == WishlistStatus.FIXED && !e.converted() && e.date() == null))
                .map(e -> new PocketResultDto.WishlistCandidate(e.id(), e.description(),
                        e.plannedAmount(), e.date(), e.wishlistStatus() == WishlistStatus.FIXED))
                .toList();

        List<PocketResultDto.BreakdownLine> breakdown = buildBreakdown(in, currentBalance, overdue,
                expensesAtMin, incomeAtMin, forecastAtMin, minBalance, minDate, buffer, pocket, candidates);

        return new PocketResultDto(pocket, currentBalance, buffer,
                new PocketResultDto.Horizon(in.scope().type(), in.horizonEnd(),
                        horizonLabel(in), in.horizonFallback()),
                new PocketResultDto.MinPoint(minDate, minBalance),
                breakdown, trajectory, candidates);
    }

    // ── правила фильтрации (спека §3.2) ─────────────────────────────────────

    /** PLAN(PLANNED) без факта — ещё не исполнен, участвует в прогнозе. */
    private static boolean isPendingPlan(EventSnapshot e) {
        return e.factAmount() == null
                && e.eventKind() == EventKind.PLAN && e.status() == EventStatus.PLANNED;
    }

    /** Фильтр хотелок для траектории: обычные события + датированные FIXED-неконвертированные. */
    private static boolean allowedInTrajectory(EventSnapshot e) {
        if (e.wishlistStatus() == null) return true;
        return e.wishlistStatus() == WishlistStatus.FIXED && !e.converted() && e.date() != null;
    }

    private static BigDecimal signed(EventType type, BigDecimal amount) {
        return type == EventType.INCOME ? amount : amount.negate();
    }

    // ── breakdown (спека §5) ────────────────────────────────────────────────

    private static List<PocketResultDto.BreakdownLine> buildBreakdown(
            PocketInput in, BigDecimal currentBalance, BigDecimal overdue,
            BigDecimal expensesAtMin, BigDecimal incomeAtMin, BigDecimal forecastAtMin,
            BigDecimal minBalance, LocalDate minDate, BigDecimal buffer, BigDecimal pocket,
            List<PocketResultDto.WishlistCandidate> candidates) {

        List<PocketResultDto.BreakdownLine> lines = new ArrayList<>();
        String minDateLabel = DD_MM.format(minDate);

        lines.add(new PocketResultDto.BreakdownLine(BreakdownType.STARTING_BALANCE,
                in.checkpointDate() != null
                        ? "Остаток на счёте (чекпоинт " + DD_MM.format(in.checkpointDate()) + " + движение)"
                        : "Остаток на счёте (по событиям, чекпоинта нет)",
                currentBalance, List.of()));

        if (overdue.signum() != 0) {
            List<String> details = in.overdueEvents().stream()
                    .map(e -> e.description() != null ? e.description() : "без описания").toList();
            lines.add(new PocketResultDto.BreakdownLine(BreakdownType.OVERDUE_RESERVE,
                    "Просроченные обязательства (" + in.overdueEvents().size() + " шт)",
                    overdue.negate(), details));
        }
        if (expensesAtMin.signum() != 0) {
            lines.add(new PocketResultDto.BreakdownLine(BreakdownType.PLANNED_EXPENSES,
                    "Плановые расходы до " + minDateLabel, expensesAtMin.negate(), List.of()));
        }
        if (incomeAtMin.signum() != 0) {
            lines.add(new PocketResultDto.BreakdownLine(BreakdownType.PLANNED_INCOME,
                    "Плановые доходы до " + minDateLabel, incomeAtMin, List.of()));
        }
        if (forecastAtMin.signum() != 0) {
            lines.add(new PocketResultDto.BreakdownLine(BreakdownType.UNPLANNED_FORECAST,
                    "Прогноз незапланированных", forecastAtMin.negate(), in.forecastContributors()));
        }
        lines.add(new PocketResultDto.BreakdownLine(BreakdownType.TRAJECTORY_MIN,
                "Минимум траектории (" + minDateLabel + ")", minBalance, List.of()));
        if (buffer.signum() != 0) {
            lines.add(new PocketResultDto.BreakdownLine(BreakdownType.BUFFER,
                    "Буфер (настройка)", buffer.negate(), List.of()));
        }
        lines.add(new PocketResultDto.BreakdownLine(BreakdownType.POCKET, "Кармашек", pocket, List.of()));

        BigDecimal wishlistSum = candidates.stream()
                .map(PocketResultDto.WishlistCandidate::plannedAmount).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (wishlistSum.signum() != 0) {
            lines.add(new PocketResultDto.BreakdownLine(BreakdownType.WISHLIST_INFO,
                    "Хотелки-кандидаты (не вычтены)", wishlistSum, List.of()));
        }
        return lines;
    }

    /** Шаблоны label горизонта — без категорийных эвристик (спека §4). */
    private static String horizonLabel(PocketInput in) {
        if (in.horizonFallback()) return "30 дней вперёд (нет плановых доходов)";
        return switch (in.scope().type()) {
            case NEXT_INCOME -> "до дохода " + DD_MM.format(in.horizonEnd());
            case MONTHS -> in.scope().months() + " мес (до " + DD_MM.format(in.horizonEnd()) + ")";
            case DATE -> "до " + DD_MM_YYYY.format(in.horizonEnd());
        };
    }
}
```

- [ ] **Step 4: Прогнать тесты движка — зелёные**

Run: `cd backend && JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -Dtest=PocketEngineTest`
Expected: PASS (14 tests)

- [ ] **Step 5: Прогнать ВСЕ юнит-тесты бэкенда** (регрессий быть не может — новый код изолирован, но проверяем)

Run: `cd backend && JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/ru/selfin/backend/service/PocketEngine.java backend/src/test/java/ru/selfin/backend/service/PocketEngineTest.java
git commit -m "feat(pocket): pure calculation engine — min trajectory, breakdown invariant, wishlist filter (ANO-12)"
```

## Chunk 2: Обвязка — репозиторий, настройки, сервис, контроллер, IT

### Task 3: Новые запросы репозитория

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/repository/FinancialEventRepository.java` (добавить в конец интерфейса, перед `}`)

- [ ] **Step 1: Добавить три метода**

```java
    // --- Pocket (ANO-12) ---

    /**
     * Просроченные обязательные расходы БЕЗ границы месяца (спека §3.4):
     * PLAN(PLANNED) HIGH EXPENSE с датой в прошлом и без FACT-детей.
     * Возвращает события (не сумму) — движку нужны details для breakdown.
     * wishlistStatus проверять не нужно: хотелки всегда LOW (DB constraint), HIGH-фильтр их исключает.
     */
    @Query("""
        SELECT e FROM FinancialEvent e
        WHERE e.eventKind = ru.selfin.backend.model.EventKind.PLAN
          AND e.type = ru.selfin.backend.model.enums.EventType.EXPENSE
          AND e.priority = ru.selfin.backend.model.enums.Priority.HIGH
          AND e.status = ru.selfin.backend.model.enums.EventStatus.PLANNED
          AND e.date < :today
          AND e.deleted = false
          AND NOT EXISTS (
              SELECT 1 FROM FinancialEvent f
              WHERE f.parentEventId = e.id AND f.deleted = false
          )
        """)
    List<FinancialEvent> findOverdueMandatoryExpenses(@Param("today") LocalDate today);

    /**
     * Дата ближайшего будущего плана-дохода ЛЮБОЙ категории (горизонт NEXT_INCOME, спека §4).
     * Хотелки исключены явно (income-хотелок не бывает, но фильтр дешёвый и страхует).
     */
    @Query("""
        SELECT MIN(e.date) FROM FinancialEvent e
        WHERE e.deleted = false
          AND e.eventKind = ru.selfin.backend.model.EventKind.PLAN
          AND e.status = ru.selfin.backend.model.enums.EventStatus.PLANNED
          AND e.type = ru.selfin.backend.model.enums.EventType.INCOME
          AND e.wishlistStatus IS NULL
          AND e.date > :after AND e.date <= :until
        """)
    Optional<LocalDate> findNextPlannedIncomeDate(
        @Param("after") LocalDate after, @Param("until") LocalDate until);

    /** Хотелки нескольких статусов одним запросом (вход движка wishlistEvents, спека §3.1). */
    List<FinancialEvent> findByWishlistStatusInAndDeletedFalse(
            java.util.Collection<ru.selfin.backend.model.enums.WishlistStatus> statuses);
```

- [ ] **Step 2: Компиляция**

Run: `cd backend && JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/ru/selfin/backend/repository/FinancialEventRepository.java
git commit -m "feat(pocket): repository queries — overdue without month boundary, next income date, wishlist by statuses"
```

### Task 4: Настройка буфера (ключ pocket в user_settings)

**Files:**
- Create: `backend/src/main/java/ru/selfin/backend/dto/pocket/PocketSettingsDto.java`
- Modify: `backend/src/main/java/ru/selfin/backend/service/UserSettingsService.java`
- Modify: `backend/src/main/java/ru/selfin/backend/controller/UserSettingsController.java`

- [ ] **Step 1: DTO**

```java
package ru.selfin.backend.dto.pocket;

import java.math.BigDecimal;

/** Настройки кармашка (ключ "pocket" в user_settings, спека §7). */
public record PocketSettingsDto(BigDecimal bufferAmount) {}
```

- [ ] **Step 2: Расширить UserSettingsService** (по образцу wishlist-методов того же класса)

Добавить константу и методы:

```java
    private static final String POCKET_KEY = "pocket";

    @Transactional(readOnly = true)
    public PocketSettingsDto getPocketSettings() {
        return repo.findBySettingsKey(POCKET_KEY)
                .map(this::parsePocket)
                .orElse(new PocketSettingsDto(BigDecimal.ZERO));
    }

    @Transactional
    public PocketSettingsDto updatePocketSettings(PocketSettingsDto dto) {
        if (dto.bufferAmount() == null || dto.bufferAmount().compareTo(BigDecimal.ZERO) < 0) {
            // null не означает «сбросить» — сброс = явный PUT с 0 (спека §7)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "bufferAmount must be >= 0");
        }
        UserSettings entity = repo.findBySettingsKey(POCKET_KEY).orElseGet(() ->
                UserSettings.builder().settingsKey(POCKET_KEY).build());
        entity.setSettingsValue(serializePocket(dto));
        repo.save(entity);
        return dto;
    }

    private PocketSettingsDto parsePocket(UserSettings s) {
        try {
            return objectMapper.readValue(s.getSettingsValue(), PocketSettingsDto.class);
        } catch (Exception e) {
            return new PocketSettingsDto(BigDecimal.ZERO);
        }
    }

    private String serializePocket(PocketSettingsDto dto) {
        try {
            return objectMapper.writeValueAsString(dto);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "serialize settings");
        }
    }
```

Импорт добавить: `ru.selfin.backend.dto.pocket.PocketSettingsDto`.

- [ ] **Step 3: Endpoints в UserSettingsController**

```java
    @GetMapping("/pocket")
    public PocketSettingsDto getPocket() {
        return service.getPocketSettings();
    }

    @PutMapping("/pocket")
    public PocketSettingsDto updatePocket(@RequestBody PocketSettingsDto dto) {
        return service.updatePocketSettings(dto);
    }
```

Импорт: `ru.selfin.backend.dto.pocket.PocketSettingsDto`.

- [ ] **Step 4: Компиляция + commit**

Run: `cd backend && JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw compile`
Expected: BUILD SUCCESS

```bash
git add backend/src/main/java/ru/selfin/backend/dto/pocket/PocketSettingsDto.java backend/src/main/java/ru/selfin/backend/service/UserSettingsService.java backend/src/main/java/ru/selfin/backend/controller/UserSettingsController.java
git commit -m "feat(pocket): buffer setting — pocket key in user_settings, GET/PUT /settings/pocket"
```

### Task 5: PocketService — сборка входа

**Files:**
- Create: `backend/src/main/java/ru/selfin/backend/service/PocketService.java`
- Test: `backend/src/test/java/ru/selfin/backend/service/PocketServiceTest.java`

- [ ] **Step 1: Написать падающие тесты** (Mockito, по образцу `DashboardServiceTest`)

```java
package ru.selfin.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import ru.selfin.backend.dto.MonthlyForecastDto;
import ru.selfin.backend.dto.pocket.PocketResultDto;
import ru.selfin.backend.dto.pocket.PocketScope;
import ru.selfin.backend.dto.pocket.PocketSettingsDto;
import ru.selfin.backend.repository.BalanceCheckpointRepository;
import ru.selfin.backend.repository.FinancialEventRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Тесты сборки входа: разрешение горизонта, кап 92 дня, маппинг ошибок на 400. */
class PocketServiceTest {

    private FinancialEventRepository eventRepository;
    private BalanceCheckpointRepository checkpointRepository;
    private UserSettingsService settingsService;
    private PredictionService predictionService;
    private PocketService pocketService;

    private static final LocalDate TODAY = LocalDate.of(2026, 3, 1);

    @BeforeEach
    void setUp() {
        eventRepository = mock(FinancialEventRepository.class);
        checkpointRepository = mock(BalanceCheckpointRepository.class);
        settingsService = mock(UserSettingsService.class);
        predictionService = mock(PredictionService.class);

        when(checkpointRepository.findTopByOrderByDateDesc()).thenReturn(Optional.empty());
        when(eventRepository.findAllByDeletedFalseAndDateBetween(any(), any())).thenReturn(List.of());
        when(eventRepository.findOverdueMandatoryExpenses(any())).thenReturn(List.of());
        when(eventRepository.findByWishlistStatusInAndDeletedFalse(any())).thenReturn(List.of());
        when(eventRepository.findNextPlannedIncomeDate(any(), any())).thenReturn(Optional.empty());
        when(settingsService.getPocketSettings()).thenReturn(new PocketSettingsDto(BigDecimal.ZERO));
        when(predictionService.forecastFromEvents(any(), any()))
                .thenReturn(new MonthlyForecastDto(List.of(), BigDecimal.ZERO));

        pocketService = new PocketService(eventRepository, checkpointRepository,
                settingsService, predictionService);
    }

    @Test
    @DisplayName("NEXT_INCOME: доход найден в пределах 92 дней — горизонт до него")
    void nextIncome_found() {
        when(eventRepository.findNextPlannedIncomeDate(TODAY, TODAY.plusDays(92)))
                .thenReturn(Optional.of(LocalDate.of(2026, 3, 15)));
        PocketResultDto r = pocketService.getPocket(null, TODAY);
        assertThat(r.horizon().endDate()).isEqualTo(LocalDate.of(2026, 3, 15));
        assertThat(r.horizon().fallback()).isFalse();
        assertThat(r.horizon().type()).isEqualTo(PocketScope.Type.NEXT_INCOME);
    }

    @Test
    @DisplayName("NEXT_INCOME: дохода нет — фолбэк +30 дней с флагом")
    void nextIncome_fallback() {
        PocketResultDto r = pocketService.getPocket(null, TODAY);
        assertThat(r.horizon().endDate()).isEqualTo(TODAY.plusDays(30));
        assertThat(r.horizon().fallback()).isTrue();
    }

    @Test
    @DisplayName("MONTHS:3 — горизонт +3 месяца")
    void monthsScope() {
        PocketResultDto r = pocketService.getPocket("MONTHS:3", TODAY);
        assertThat(r.horizon().endDate()).isEqualTo(TODAY.plusMonths(3));
    }

    @Test
    @DisplayName("DATE в прошлом или дальше 36 мес → 400")
    void dateScope_validation() {
        assertThatThrownBy(() -> pocketService.getPocket("DATE:2026-02-01", TODAY))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> pocketService.getPocket("DATE:2030-01-01", TODAY))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @DisplayName("Мусорный скоуп → 400 (ResponseStatusException)")
    void garbageScope_400() {
        assertThatThrownBy(() -> pocketService.getPocket("GARBAGE", TODAY))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @DisplayName("Отрицательная netPredictionDelta зажимается в 0")
    void negativeForecast_clamped() {
        when(predictionService.forecastFromEvents(any(), any()))
                .thenReturn(new MonthlyForecastDto(List.of(), new BigDecimal("-500")));
        PocketResultDto r = pocketService.getPocket(null, TODAY);
        assertThat(r.breakdown()).noneMatch(l ->
                l.type() == ru.selfin.backend.dto.pocket.BreakdownType.UNPLANNED_FORECAST);
    }
}
```

- [ ] **Step 2: Прогнать — падает** (PocketService не существует)

Run: `cd backend && JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -Dtest=PocketServiceTest`
Expected: COMPILATION ERROR

- [ ] **Step 3: Реализовать сервис**

```java
package ru.selfin.backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ru.selfin.backend.dto.CategoryForecastDto;
import ru.selfin.backend.dto.MonthlyForecastDto;
import ru.selfin.backend.dto.pocket.EventSnapshot;
import ru.selfin.backend.dto.pocket.PocketInput;
import ru.selfin.backend.dto.pocket.PocketResultDto;
import ru.selfin.backend.dto.pocket.PocketScope;
import ru.selfin.backend.model.BalanceCheckpoint;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.enums.WishlistStatus;
import ru.selfin.backend.repository.BalanceCheckpointRepository;
import ru.selfin.backend.repository.FinancialEventRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * Тонкая обвязка PocketEngine: собирает вход из репозиториев (спека §2).
 * Вся математика — в движке; здесь только выборки, разрешение горизонта и маппинг ошибок.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PocketService {

    /** Кап поиска следующего дохода (спека §4): дальше квартала — не «период до дохода». */
    static final int NEXT_INCOME_SEARCH_DAYS = 92;
    static final int FALLBACK_HORIZON_DAYS = 30;
    /** Дата «с начала времён» для выборки фактов без чекпоинта. */
    private static final LocalDate EPOCH = LocalDate.of(2000, 1, 1);

    private final FinancialEventRepository eventRepository;
    private final BalanceCheckpointRepository checkpointRepository;
    private final UserSettingsService settingsService;
    private final PredictionService predictionService;

    public PocketResultDto getPocket(String rawScope, LocalDate asOfDate) {
        PocketScope scope;
        try {
            scope = PocketScope.parse(rawScope);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }

        // 1. Горизонт (спека §4)
        boolean fallback = false;
        LocalDate horizonEnd;
        switch (scope.type()) {
            case NEXT_INCOME -> {
                Optional<LocalDate> next = eventRepository.findNextPlannedIncomeDate(
                        asOfDate, asOfDate.plusDays(NEXT_INCOME_SEARCH_DAYS));
                if (next.isPresent()) {
                    horizonEnd = next.get();
                } else {
                    horizonEnd = asOfDate.plusDays(FALLBACK_HORIZON_DAYS);
                    fallback = true;
                }
            }
            case MONTHS -> horizonEnd = asOfDate.plusMonths(scope.months());
            case DATE -> {
                horizonEnd = scope.date();
                if (!horizonEnd.isAfter(asOfDate) || horizonEnd.isAfter(asOfDate.plusMonths(PocketScope.MAX_MONTHS))) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "DATE scope must be in the future and within 36 months");
                }
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown scope");
        }

        // 2. Чекпоинт и события (баланс + траектория)
        Optional<BalanceCheckpoint> checkpoint = checkpointRepository.findTopByOrderByDateDesc();
        LocalDate from = checkpoint.map(BalanceCheckpoint::getDate).orElse(EPOCH);
        List<EventSnapshot> events = eventRepository
                .findAllByDeletedFalseAndDateBetween(from, horizonEnd)
                .stream().map(EventSnapshot::from).toList();

        // 3. Просрочка (без границы месяца) и хотелки (отдельные выборки, спека §3.1, §3.4)
        List<EventSnapshot> overdue = eventRepository.findOverdueMandatoryExpenses(asOfDate)
                .stream().map(EventSnapshot::from).toList();
        List<EventSnapshot> wishlist = eventRepository
                .findByWishlistStatusInAndDeletedFalse(EnumSet.of(WishlistStatus.OPEN, WishlistStatus.FIXED))
                .stream().map(EventSnapshot::from).toList();

        // 4. Прогноз незапланированных: текущий месяц, как в старом adjustedPocket (спека §3.5)
        LocalDate monthStart = asOfDate.withDayOfMonth(1);
        LocalDate monthEnd = asOfDate.withDayOfMonth(asOfDate.lengthOfMonth());
        List<FinancialEvent> monthEvents = eventRepository
                .findAllByDeletedFalseAndDateBetween(monthStart, monthEnd);
        MonthlyForecastDto forecast = predictionService.forecastFromEvents(monthEvents, asOfDate);
        BigDecimal delta = forecast.netPredictionDelta().max(BigDecimal.ZERO);
        List<String> contributors = buildContributors(forecast);

        // 5. Буфер
        BigDecimal buffer = settingsService.getPocketSettings().bufferAmount();

        return PocketEngine.calculate(new PocketInput(asOfDate,
                checkpoint.map(BalanceCheckpoint::getAmount).orElse(BigDecimal.ZERO),
                checkpoint.map(BalanceCheckpoint::getDate).orElse(null),
                events, wishlist, overdue, scope, horizonEnd, fallback, buffer, delta, contributors));
    }

    /**
     * Имена линейных категорий с ожидаемым добором (для details строки UNPLANNED_FORECAST).
     * Фильтр идентичен бывшему TargetFundService.buildContributors (линейная категория =
     * без PLAN-событий, plannedLimit == 0; вклад = projection − currentFact > 0),
     * но вывод — голые имена категорий, без сумм «(+3к)».
     */
    private List<String> buildContributors(MonthlyForecastDto forecast) {
        return forecast.categories().stream()
                .filter(c -> c.plannedLimit().signum() == 0
                        && c.projectionAmount().compareTo(c.currentFact()) > 0)
                .map(CategoryForecastDto::categoryName)
                .toList();
    }
}
```

- [ ] **Step 4: Прогнать — зелёные**

Run: `cd backend && JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -Dtest=PocketServiceTest`
Expected: PASS (6 tests)

Примечание: если `CategoryForecastDto` не имеет полей `plannedLimit()/projectionAmount()/currentFact()` — открой DTO и подставь фактические имена (эталон использования: `TargetFundService.buildContributors`, строки 151–169).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/ru/selfin/backend/service/PocketService.java backend/src/test/java/ru/selfin/backend/service/PocketServiceTest.java
git commit -m "feat(pocket): thin service — horizon resolution, input assembly, 400 mapping (ANO-12)"
```

### Task 6: PocketController + интеграционные тесты

**Files:**
- Create: `backend/src/main/java/ru/selfin/backend/controller/PocketController.java`
- Test: `backend/src/test/java/ru/selfin/backend/PocketControllerIT.java`

- [ ] **Step 1: Контроллер**

```java
package ru.selfin.backend.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.selfin.backend.dto.pocket.PocketResultDto;
import ru.selfin.backend.service.PocketService;

import java.time.LocalDate;

/** Кармашек — единый ответ «сколько свободно и почему» (спека §6). */
@RestController
@RequestMapping("/api/v1/pocket")
@RequiredArgsConstructor
public class PocketController {

    private final PocketService pocketService;

    @GetMapping
    public PocketResultDto get(@RequestParam(required = false) String scope) {
        return pocketService.getPocket(scope, LocalDate.now());
    }
}
```

- [ ] **Step 2: Написать IT** (образец инфраструктуры: `BalanceCheckpointControllerIT` — Testcontainers + MockMvc). Тесты ассертят ДЕЛЬТЫ, а не абсолюты — в dev-БД могут быть seed-события.

```java
package ru.selfin.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * IT кармашка: контракт GET /pocket, настройки буфера,
 * сценарий ANO-6 (ввод немедленно меняет ответ), «факт вытесняет план».
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class PocketControllerIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    private BigDecimal pocket() throws Exception {
        String body = mockMvc.perform(get("/api/v1/pocket"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new BigDecimal(objectMapper.readTree(body).get("pocket").asText());
    }

    @Test
    void contract_defaultScope() throws Exception {
        mockMvc.perform(get("/api/v1/pocket"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pocket").exists())
                .andExpect(jsonPath("$.currentBalance").exists())
                .andExpect(jsonPath("$.buffer").exists())
                .andExpect(jsonPath("$.horizon.type").exists())
                .andExpect(jsonPath("$.horizon.endDate").exists())
                .andExpect(jsonPath("$.horizon.label").exists())
                .andExpect(jsonPath("$.minPoint.date").exists())
                .andExpect(jsonPath("$.breakdown").isArray())
                .andExpect(jsonPath("$.trajectory").isArray())
                .andExpect(jsonPath("$.wishlistCandidates").isArray());
    }

    @Test
    void scopes_monthsOkGarbage400() throws Exception {
        mockMvc.perform(get("/api/v1/pocket?scope=MONTHS:3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.horizon.type").value("MONTHS"));
        mockMvc.perform(get("/api/v1/pocket?scope=GARBAGE"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/pocket?scope=MONTHS:99"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void settings_putGetValidate() throws Exception {
        mockMvc.perform(put("/api/v1/settings/pocket")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bufferAmount": 5000}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bufferAmount").value(5000));

        mockMvc.perform(get("/api/v1/settings/pocket"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bufferAmount").value(5000));

        mockMvc.perform(put("/api/v1/settings/pocket")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bufferAmount": -1}
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/api/v1/settings/pocket")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        // вернуть 0, чтобы не влиять на другие тесты
        mockMvc.perform(put("/api/v1/settings/pocket")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bufferAmount": 0}
                                """))
                .andExpect(status().isOk());
    }

    /**
     * Сценарий ANO-6: ввод плана и факта немедленно меняет ответ кармашка.
     * План HIGH-расхода на СЕГОДНЯ −5000 → кармашек падает на 5000 (день 0 траектории);
     * факт 4000 по этому плану → факт вытесняет план: кармашек = старт − 4000.
     *
     * Дата = сегодня НАМЕРЕННО: updateFact пишет factAmount в ту же PLAN-запись,
     * а факт с БУДУЩЕЙ датой не попадает ни в баланс (date > asOf), ни в траекторию
     * (factAmount != null) — известная дыра v1, залогирована в ANO-12.
     */
    @Test
    void ano6_inputImmediatelyChangesPocket() throws Exception {
        // Категория для события (оба POST в этом API возвращают 200, не 201)
        String catBody = mockMvc.perform(post("/api/v1/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"IT-pocket-test","type":"EXPENSE"}
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String categoryId = objectMapper.readTree(catBody).get("id").asText();

        BigDecimal before = pocket();

        // План: HIGH-расход на сегодня. POST /events требует Idempotency-Key (обязательный header).
        LocalDate planDate = LocalDate.now();
        String eventBody = mockMvc.perform(post("/api/v1/events")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"date":"%s","categoryId":"%s","type":"EXPENSE",
                                 "plannedAmount":5000,"priority":"HIGH","description":"IT plan"}
                                """.formatted(planDate, categoryId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String planId = objectMapper.readTree(eventBody).get("id").asText();

        BigDecimal afterPlan = pocket();
        assertThat(before.subtract(afterPlan)).isEqualByComparingTo(new BigDecimal("5000"));

        // Факт 4000 по плану: PATCH /events/{id}/fact (FinancialEventUpdateFactDto)
        mockMvc.perform(patch("/api/v1/events/" + planId + "/fact")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"factAmount": 4000}
                                """))
                .andExpect(status().isOk());

        BigDecimal afterFact = pocket();
        // План вытеснен фактом: итоговая дельта от старта = −4000, не −9000 и не −5000
        assertThat(before.subtract(afterFact)).isEqualByComparingTo(new BigDecimal("4000"));
    }
}
```

- [ ] **Step 3: Прогнать IT** (нужен Docker)

Run: `cd backend && JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -Dtest=PocketControllerIT`
Expected: PASS (4 tests). Имена полей JSON и статусы уже сверены с `CategoryCreateDto`/`FinancialEventCreateDto`/контроллерами (оба POST → 200; POST /events требует Idempotency-Key; PATCH /fact → 200).

- [ ] **Step 4: Полный прогон**

Run: `cd backend && JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test`
Expected: PASS, ноль регрессий

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/ru/selfin/backend/controller/PocketController.java backend/src/test/java/ru/selfin/backend/PocketControllerIT.java
git commit -m "feat(pocket): GET /api/v1/pocket endpoint + IT incl. ANO-6 scenario (ANO-12)"
```

## Chunk 3: Миграция Funds на /pocket

### Task 7: Фронт — типы, API-клиент, компонент PocketCard

**Files:**
- Modify: `frontend/src/types/api.ts` (добавить типы; строки 151–154 — старые поля FundsOverview, пока НЕ трогать)
- Modify: `frontend/src/api/index.ts` (рядом с `fetchFunds`, строка 142)
- Create: `frontend/src/components/PocketCard.tsx`

- [ ] **Step 1: Типы**

Добавить в `frontend/src/types/api.ts`:

```typescript
// ── Pocket (ANO-12) ──────────────────────────────────────────────────────────
export type PocketScopeType = 'NEXT_INCOME' | 'MONTHS' | 'DATE';
export type BreakdownType =
    | 'STARTING_BALANCE' | 'OVERDUE_RESERVE' | 'PLANNED_EXPENSES' | 'PLANNED_INCOME'
    | 'UNPLANNED_FORECAST' | 'TRAJECTORY_MIN' | 'BUFFER' | 'POCKET' | 'WISHLIST_INFO';

export interface PocketResponse {
    pocket: number;
    currentBalance: number;
    buffer: number;
    horizon: { type: PocketScopeType; endDate: string; label: string; fallback: boolean };
    minPoint: { date: string; balance: number };
    breakdown: { type: BreakdownType; label: string; amount: number; details: string[] }[];
    trajectory: { date: string; balance: number }[];
    wishlistCandidates: {
        id: string; description: string | null;
        plannedAmount: number | null; date: string | null; fixed: boolean;
    }[];
}
```

- [ ] **Step 2: API-клиент** — в `frontend/src/api/index.ts` (импортировать `PocketResponse` из types):

```typescript
export const fetchPocket = (scope?: string) =>
    get<PocketResponse>(`/pocket${scope ? `?scope=${encodeURIComponent(scope)}` : ''}`);
```

- [ ] **Step 3: Компонент PocketCard** — минимальный UI спеки §8.2: число + label горизонта + разворачиваемый breakdown + переключатель скоупа (замена старым клиентским прогнозам 3/6 мес):

```tsx
import { useCallback, useEffect, useState } from 'react';
import { HelpCircle, Wallet } from 'lucide-react';
import { fetchPocket } from '../api';
import type { PocketResponse } from '../types/api';

const fmtC = (n: number) =>
    new Intl.NumberFormat('ru-RU', { style: 'currency', currency: 'RUB', minimumFractionDigits: 0 }).format(n);

const SCOPES: { key: string | undefined; label: string }[] = [
    { key: undefined, label: 'До дохода' },
    { key: 'MONTHS:3', label: '3 мес' },
    { key: 'MONTHS:6', label: '6 мес' },
];

/**
 * Кармашек: одно число + «почему столько» (breakdown из GET /pocket).
 * Минимальный UI по спеке ANO-12 §8.2; полноценная подача — ANO-13/14.
 */
export default function PocketCard({ onData, refreshSignal }: {
    onData?: (p: PocketResponse) => void;
    refreshSignal?: number;
}) {
    const [scope, setScope] = useState<string | undefined>(undefined);
    const [data, setData] = useState<PocketResponse | null>(null);
    const [error, setError] = useState<string | null>(null);
    const [showWhy, setShowWhy] = useState(false);

    const load = useCallback(() => {
        fetchPocket(scope)
            .then(p => { setData(p); setError(null); onData?.(p); })
            .catch((e: Error) => setError(e.message));
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [scope]);
    useEffect(() => { load(); }, [load]);
    useEffect(() => { if (refreshSignal) load(); }, [refreshSignal, load]);

    return (
        <div className="rounded-2xl p-6"
            style={{ background: 'linear-gradient(135deg, var(--color-accent) 0%, #9f8cff 100%)' }}>
            <div className="flex items-start gap-4">
                <Wallet size={32} color="white" className="shrink-0 mt-1" />
                <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-1.5">
                        <p className="text-sm text-white/70">В кармашке</p>
                        <button onClick={() => setShowWhy(v => !v)}
                            className="text-white/50 hover:text-white/90 transition-colors"
                            aria-label="Почему столько">
                            <HelpCircle size={14} />
                        </button>
                    </div>

                    {error && <p className="text-sm text-white/80 mt-1">Ошибка: {error}</p>}
                    {!data && !error && <p className="text-sm text-white/60 mt-1 animate-pulse">Загрузка…</p>}

                    {data && (
                        <>
                            <p className="text-3xl font-bold text-white">{fmtC(data.pocket)}</p>
                            <p className="text-xs text-white/60 mt-0.5">
                                {data.horizon.label}
                                {data.minPoint.date !== data.horizon.endDate &&
                                    ` · минимум ${fmtC(data.minPoint.balance)}`}
                            </p>

                            <div className="flex gap-1.5 mt-3">
                                {SCOPES.map(s => (
                                    <button key={s.label}
                                        onClick={() => setScope(s.key)}
                                        className={`text-xs px-2.5 py-1 rounded-full transition-colors ${
                                            scope === s.key
                                                ? 'bg-white/90 text-black font-semibold'
                                                : 'bg-white/15 text-white/80 hover:bg-white/25'
                                        }`}>
                                        {s.label}
                                    </button>
                                ))}
                            </div>

                            {showWhy && (
                                <div className="mt-3 rounded-xl bg-black/20 px-3 py-2 space-y-1">
                                    {data.breakdown.map((line, i) => (
                                        <div key={i}>
                                            <div className="flex justify-between text-xs">
                                                <span className={line.type === 'POCKET'
                                                    ? 'text-white font-semibold' : 'text-white/70'}>
                                                    {line.label}
                                                </span>
                                                <span className={line.type === 'POCKET'
                                                    ? 'text-white font-semibold' : 'text-white/90'}>
                                                    {line.amount > 0 && line.type !== 'STARTING_BALANCE'
                                                        && line.type !== 'TRAJECTORY_MIN'
                                                        && line.type !== 'POCKET'
                                                        && line.type !== 'WISHLIST_INFO' ? '+' : ''}
                                                    {fmtC(line.amount)}
                                                </span>
                                            </div>
                                            {line.details.length > 0 && (
                                                <p className="text-[11px] text-white/50">{line.details.join(', ')}</p>
                                            )}
                                        </div>
                                    ))}
                                </div>
                            )}
                        </>
                    )}
                </div>
            </div>
        </div>
    );
}
```

- [ ] **Step 4: Сборка фронта**

Run: `cd frontend && npm run build`
Expected: сборка без ошибок TypeScript

- [ ] **Step 5: Commit**

```bash
git add frontend/src/types/api.ts frontend/src/api/index.ts frontend/src/components/PocketCard.tsx
git commit -m "feat(pocket): frontend client + PocketCard with breakdown and scope switcher (ANO-12)"
```

### Task 8: Funds.tsx на PocketCard, удаление старого расчёта

**Files:**
- Modify: `frontend/src/pages/Funds.tsx`
- Modify: `frontend/src/types/api.ts:151-154`
- Modify: `backend/src/main/java/ru/selfin/backend/dto/FundsOverviewDto.java`
- Modify: `backend/src/main/java/ru/selfin/backend/service/TargetFundService.java`
- Modify: `backend/src/test/java/ru/selfin/backend/service/TargetFundServiceTest.java`

- [ ] **Step 1: Funds.tsx — заменить виджет и источники pocketBalance**

1. Импортировать `PocketCard` и тип `PocketResponse`; добавить стейт `const [pocket, setPocket] = useState<PocketResponse | null>(null);`.
2. Удалить: блок клиентских прогнозов (useEffect строки ~410–471, стейт `projections`, хелперы `projectFull`/`projectMandatory`), блок JSX «Кармашек» (строки ~483–564 вместе с `showHelp`-подсказкой и блоком `predictionAdjustedPocket`).
3. На место блока «Кармашек» поставить: `<PocketCard onData={setPocket} refreshSignal={refreshSignal} />`.
4. «Доступно сейчас» для переводов = день-0 траектории (деньги, не занятые прямо сейчас — ближайший аналог старого pocketBalance): `const availableNow = pocket?.trajectory[0]?.balance ?? 0;`
5. Заменить `data.pocketBalance` → `availableNow` в `FundCard` (строка ~592) и `TransferModal` (строка ~612).
6. В `onSuccess` у `TransferModal` дополнительно перезагружать кармашек через **локальный инкремент, скомбинированный с пропом**: `const [pocketBump, setPocketBump] = useState(0);` → `<PocketCard onData={setPocket} refreshSignal={(refreshSignal ?? 0) + pocketBump} />` → в `onSuccess` перевода: `setPocketBump(b => b + 1)`. НЕ использовать `setPocket(null)` — PocketCard владеет данными и рефетчит только по scope/refreshSignal, обнуление в родителе оставит `availableNow` = 0 навсегда и скроет кнопки перевода.
7. Удалить ставшее неиспользуемым: импорты `Wallet`, `HelpCircle`, `fetchEvents`; тип-импорт `FinancialEvent` (жил только в `isMandatory` удаляемого useEffect); тип `ProjectionSet` (строки ~384–390); хелпер `fmtDate` (строка ~476, единственное использование — внутри удаляемого JSX). Сборка без них не падает (нет noUnusedLocals), но ESLint отловит.

- [ ] **Step 2: types/api.ts — убрать старые поля из FundsOverview** (строки 151–154): удалить `pocketBalance`, `predictionAdjustedPocket`, `forecastContributors`; оставить `funds`.

- [ ] **Step 3: Сборка фронта — проверить, что не осталось потребителей**

Run: `cd frontend && npm run build`
Expected: ошибок нет. Если TypeScript находит других потребителей удалённых полей — мигрировать их на `fetchPocket` тем же способом.

- [ ] **Step 4: Бэкенд — упростить FundsOverviewDto и TargetFundService**

`FundsOverviewDto.java`:
```java
package ru.selfin.backend.dto;

import java.util.List;

public record FundsOverviewDto(List<TargetFundDto> funds) {}
```

`TargetFundService.java`: удалить `calcPocketBalance()`, `buildContributors()`, `formatK()`, поле `predictionService` (если больше нигде в классе не используется — проверить) и всю прогнозную часть `getOverview()`; метод сжимается до:

```java
    public FundsOverviewDto getOverview() {
        List<TargetFundDto> fundDtos = fundRepository.findAllByDeletedFalseOrderByPriorityAsc().stream()
                .filter(f -> !POCKET_NAME.equals(f.getName()))
                .map(this::toDto)
                .toList();
        return new FundsOverviewDto(fundDtos);
    }
```

Удалить ставшие ненужными импорты. Дополнительно — скрытые ссылки на удаляемое:

1. **Поле `checkpointRepository`** в `TargetFundService` использовалось только внутри `calcPocketBalance` — удалить поле (конструктор через `@RequiredArgsConstructor` сожмётся автоматически; в тестах поправить создание сервиса).
2. **Javadoc `CapitalService`** (строки ~48, 52 — `{@link TargetFundService#calcPocketBalance()}`, и строка ~250 — комментарий «обе формулы должны двигаться вместе»): перенаправить на `PocketEngine` — это инвариант согласованности формул, он не должен указывать в пустоту.
3. **Мёртвые методы репозитория** после удаления `calcPocketBalance`: `sumFactExecutedByType`, `sumFactExecutedByTypeFromDate`, `sumAllFactByType`, `sumAllFactByTypeFromDate` — проверить грепом, что других потребителей нет (`sumFactByTypeBetween` НЕ трогать — им живёт CapitalService), и удалить.
4. **Javadoc класса `TargetFundService`** (шапка, строки ~38–67, описывает алгоритм кармашка целиком) переписать: класс теперь только про CRUD фондов и переводы; расчёт кармашка — `PocketEngine`/`PocketService` (спека 2026-07-02).

- [ ] **Step 5: Удалить `TargetFundServiceTest` целиком** — все 6 тестов файла (строки 63–186) проверяют `getOverview`/`pocketBalance`/`adjustedPocket`, чьё поведение теперь покрыто `PocketEngineTest`; тестов переводов/CRUD в этом файле НЕТ (переводы покрыты `FinancialEventControllerIT`/фонд-IT, проверить грепом перед удалением). Если предпочитаешь оставить файл — оставь пустой каркас с одним smoke-тестом слимованного `getOverview` (возвращает funds без POCKET), но не ищи несуществующие тесты.

- [ ] **Step 6: Полный прогон бэка + фронта**

Run: `cd backend && JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test` затем `cd frontend && npm run build` (из корня репо)
Expected: PASS / сборка чистая. Отдельно проверить глазами через preview: страница Funds показывает PocketCard, breakdown разворачивается, переключатель скоупов меняет число, перевод в копилку обновляет кармашек.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "refactor(funds): migrate Funds page to /pocket, remove calcPocketBalance/adjustedPocket (ANO-12 step 2)"
```

### Task 9: Финализация

- [ ] **Step 1: Обновить Linear** — комментарий в ANO-12: ядро + Funds-миграция готовы (шаги 1–2 спеки §8), шаги 3–4 (Dashboard, кассовый календарь + ANO-6) — следующие; статус ANO-12 → In Progress.
- [ ] **Step 2: Использовать superpowers:finishing-a-development-branch** — решить: PR в main сейчас (шаги 1–2 самодостаточны и зелены) или продолжить шагами 3–4 на этой же ветке. Рекомендация: PR сейчас, шаги 3–4 отдельной веткой/PR — меньше диff на ревью.

**Вне этого плана (следующий план):** миграция Dashboard (§8.3), кассовый календарь на trajectory + верификация ANO-6 (§8.4).
