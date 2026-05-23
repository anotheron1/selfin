# Strategic Graph Redesign Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Заменить текущий помесячный stack-агрегат `SavingsStrategySection` на новой странице `/strategy` двумя стеком графиков (cashflow + капитал) с кумулятивным балансом, fan chart P25–P75, расширяющимся по `√t`, синхронизированным hover и tooltip с разбивкой по категориям.

**Architecture:**
- Backend: новый `StrategyTimelineService` оркеструет существующие сервисы (`CapitalService`, `PredictionService`, репозитории), отдаёт всё одним endpoint `GET /api/v1/strategy/timeline`.
- `PredictionService` расширяется аддитивно методом `getStatsForCategory()` с возвратом percentile-статистик; `CapitalService.liquidAt()` становится public.
- Frontend: новая страница `/strategy`, три новых компонента в `components/strategy/`, recharts `ComposedChart` для cashflow + `LineChart` для капитала с `syncId` для синхронного hover.

**Tech Stack:** Spring Boot 4.0.3, Java 21, PostgreSQL 15 + Flyway (без миграций — спека не требует), JUnit 5 + Mockito + Testcontainers, React 18 + TypeScript + Vite, recharts, lucide-react, Tailwind, Shadcn UI.

**Spec:** [`docs/superpowers/specs/2026-05-21-strategy-graph-redesign-design.md`](../specs/2026-05-21-strategy-graph-redesign-design.md)

**Test commands:**
- Backend unit-тесты: `rtk JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -Dtest='!*IT'` (из `backend/`)
- Backend IT: `rtk JAVA_HOME=... ./mvnw test -Dtest=StrategyTimelineControllerIT` (требует Docker; на Windows Docker pipe может не работать — IT прогоняются в CI)
- Frontend typecheck: `cd frontend && rtk npx tsc --noEmit`
- Frontend build: `cd frontend && rtk npm run build`

Если `rtk` недоступен — выполнять команды без префикса.

## File Map

### Backend — создаваемые файлы

| Файл | Ответственность |
|------|-----------------|
| `backend/src/main/java/ru/selfin/backend/dto/strategy/StrategyTimelineDto.java` | Корневой response: метаданные timeline + список точек |
| `backend/src/main/java/ru/selfin/backend/dto/strategy/StrategyTimelinePointDto.java` | Одна точка месяца: балансы, потоки, капитал, опц. breakdown |
| `backend/src/main/java/ru/selfin/backend/dto/strategy/StrategyPointPhase.java` | Enum PAST/CURRENT/FUTURE |
| `backend/src/main/java/ru/selfin/backend/dto/strategy/BreakdownDto.java` | Контейнер списков income/expense items |
| `backend/src/main/java/ru/selfin/backend/dto/strategy/BreakdownItemDto.java` | Один элемент разбивки: category, amount, isRecurring, isPredicted |
| `backend/src/main/java/ru/selfin/backend/dto/strategy/CategoryMonthStats.java` | Record возврата PredictionService.getStatsForCategory |
| `backend/src/main/java/ru/selfin/backend/service/StrategyTimelineService.java` | Оркестратор; одна публичная точка — `getTimeline(int, boolean)` |
| `backend/src/main/java/ru/selfin/backend/controller/StrategyTimelineController.java` | GET `/api/v1/strategy/timeline` |
| `backend/src/test/java/ru/selfin/backend/service/PredictionServiceStatsTest.java` | Unit-тесты для нового метода (отдельный файл — не модифицируем существующий) |
| `backend/src/test/java/ru/selfin/backend/service/StrategyTimelineServiceTest.java` | Unit-тесты сервиса с Mockito |
| `backend/src/test/java/ru/selfin/backend/StrategyTimelineControllerIT.java` | Integration tests через Testcontainers + MockMvc |

### Backend — модифицируемые файлы

| Файл | Что меняется |
|------|--------------|
| `backend/src/main/java/ru/selfin/backend/service/CapitalService.java` | `liquidAt(LocalDate)` private → public |
| `backend/src/main/java/ru/selfin/backend/service/PredictionService.java` | Добавить метод `getStatsForCategory(Category, int)` |
| `backend/src/main/java/ru/selfin/backend/repository/CategoryRepository.java` | Добавить derived query `findAllByForecastEnabledTrueAndDeletedFalse()` |

### Frontend — создаваемые файлы

| Файл | Ответственность |
|------|-----------------|
| `frontend/src/pages/Strategy.tsx` | Тонкая обёртка: header + загрузка hook + рендер двух карточек |
| `frontend/src/components/strategy/CashflowChartCard.tsx` | Карточка-обёртка верхнего графика: toggles + chart |
| `frontend/src/components/strategy/CashflowChart.tsx` | recharts ComposedChart с линией баланса, столбиками, fan |
| `frontend/src/components/strategy/CapitalTrajectoryCard.tsx` | Карточка-обёртка нижнего графика: toggles + chart |
| `frontend/src/components/strategy/StrategyCapitalChart.tsx` | recharts LineChart для капитала (отличается от существующего `CapitalTrajectoryChart` в `components/`) |
| `frontend/src/components/strategy/MonthTooltip.tsx` | Кастомный tooltip с breakdown |
| `frontend/src/components/strategy/ChartLegendToggles.tsx` | Pill-кнопки toggle слоёв |
| `frontend/src/components/strategy/useStrategyTimeline.ts` | Hook fetch + refetch |
| `frontend/src/components/strategy/strategyChartUtils.ts` | Pure helpers: маппинг DTO → chart data, formatters |

### Frontend — модифицируемые файлы

| Файл | Что меняется |
|------|--------------|
| `frontend/src/types/api.ts` | Добавить типы `StrategyTimelineDto`, `StrategyTimelinePointDto`, `StrategyPointPhase`, `BreakdownDto`, `BreakdownItemDto` |
| `frontend/src/api/index.ts` | Добавить `fetchStrategyTimeline()` |
| `frontend/src/components/BottomNav.tsx` | Пункт «Стратегия» (иконка TrendingUp) |
| `frontend/src/App.tsx` | Маршрут `/strategy` → `<Strategy />` |

---

## Chunk 1: Backend подложка — repo, public liquidAt, DTOs

Минимальная база: один публичный метод (`liquidAt`), один новый запрос репозитория, скелеты всех DTO. Никакой логики timeline ещё нет — это «строительные материалы».

### Task 1.1: Сделать `CapitalService.liquidAt` публичным

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/service/CapitalService.java`

- [ ] **Step 1: Найти текущую сигнатуру**

Открыть файл, найти `private BigDecimal liquidAt(LocalDate t)` (около строки 233). Убедиться что метод существует и читает `BalanceCheckpointRepository` + события для расчёта `checkpoint + Σ INCOME факт − Σ EXPENSE факт`.

- [ ] **Step 2: Изменить модификатор**

Заменить `private BigDecimal liquidAt(LocalDate t)` на `public BigDecimal liquidAt(LocalDate t)`. Добавить Javadoc выше:

```java
    /**
     * Жидкий баланс на дату {@code t} = баланс расчётного счёта (по чекпоинтам и фактам)
     * + сумма балансов всех копилок. Публичный API для согласования с другими сервисами
     * (например, StrategyTimelineService использует этот метод для seed `balanceConfirmed`).
     */
    public BigDecimal liquidAt(LocalDate t) {
        // ... существующее тело ...
    }
```

- [ ] **Step 3: Compile + regression tests**

Выполнить:
```
rtk JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -Dtest='CapitalServiceLiquidTest'
```

Ожидание: PASS. `liquidAt` логика не изменилась (формула: `checkpoint + Σ INCOME факт − Σ EXPENSE факт − Σ FUND_TRANSFER + Σ FundTransaction balances`), только модификатор — существующие тесты не должны сломаться.

- [ ] **Step 4: Commit**

```
git add backend/src/main/java/ru/selfin/backend/service/CapitalService.java
git commit -m "refactor(capital): expose liquidAt as public API"
```

---

### Task 1.2: `CategoryRepository.findAllByForecastEnabledTrueAndDeletedFalse`

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/repository/CategoryRepository.java`

- [ ] **Step 1: Открыть файл, добавить метод**

Добавить в интерфейс (после существующих методов `findAllByDeletedFalse`, `findAllByDeletedFalseAndType`, `findByNameAndDeletedFalse`):

```java
    /**
     * Категории, для которых разрешён прогноз PredictionService (поле {@code forecast_enabled = true}).
     * Используется StrategyTimelineService для построения fan chart.
     */
    List<Category> findAllByForecastEnabledTrueAndDeletedFalse();
```

Spring Data сгенерирует реализацию автоматически из имени метода.

- [ ] **Step 2: Compile**

```
rtk JAVA_HOME=... ./mvnw compile
```

Ожидание: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```
git add backend/src/main/java/ru/selfin/backend/repository/CategoryRepository.java
git commit -m "feat(repo): add findAllByForecastEnabledTrueAndDeletedFalse"
```

---

### Task 1.3: DTOs — phase, item, breakdown, stats

**Files:**
- Create: `backend/src/main/java/ru/selfin/backend/dto/strategy/StrategyPointPhase.java`
- Create: `backend/src/main/java/ru/selfin/backend/dto/strategy/BreakdownItemDto.java`
- Create: `backend/src/main/java/ru/selfin/backend/dto/strategy/BreakdownDto.java`
- Create: `backend/src/main/java/ru/selfin/backend/dto/strategy/CategoryMonthStats.java`

- [ ] **Step 1: Создать enum `StrategyPointPhase`**

```java
package ru.selfin.backend.dto.strategy;

/**
 * Фаза точки временной шкалы стратегии. См. spec, раздел 2, инвариант I3.
 *
 * <ul>
 *   <li>{@code PAST} — yearMonth &lt; currentMonth. Балансовые поля прогноза null.</li>
 *   <li>{@code CURRENT} — yearMonth = currentMonth. {@code balance} = {@code liquidAt(today)}.</li>
 *   <li>{@code FUTURE} — yearMonth &gt; currentMonth. Все балансовые поля заполнены.</li>
 * </ul>
 */
public enum StrategyPointPhase {
    PAST,
    CURRENT,
    FUTURE
}
```

- [ ] **Step 2: Создать record `BreakdownItemDto`**

```java
package ru.selfin.backend.dto.strategy;

import java.math.BigDecimal;

/**
 * Один элемент разбивки за месяц (для tooltip).
 *
 * @param category    название категории (готовое для отображения)
 * @param amount      сумма (положительная для income и expense; знак подразумевается типом списка)
 * @param isRecurring флаг recurring-события (для иконки ↻ во фронте)
 * @param isPredicted флаг прогнозного значения (для пометки «прогноз» во фронте)
 */
public record BreakdownItemDto(
        String category,
        BigDecimal amount,
        boolean isRecurring,
        boolean isPredicted
) {}
```

- [ ] **Step 3: Создать record `BreakdownDto`**

```java
package ru.selfin.backend.dto.strategy;

import java.util.List;

/**
 * Контейнер разбивки точки timeline на income и expense items.
 * Заполняется, только если запрос пришёл с {@code withBreakdown=true}.
 */
public record BreakdownDto(
        List<BreakdownItemDto> incomeItems,
        List<BreakdownItemDto> expenseItems
) {}
```

- [ ] **Step 4: Создать record `CategoryMonthStats`**

```java
package ru.selfin.backend.dto.strategy;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Возврат метода {@code PredictionService.getStatsForCategory()}.
 * См. spec, раздел 3.
 *
 * @param categoryId      идентификатор категории
 * @param monthsOfHistory сколько месяцев истории учтено (0..historyWindowMonths)
 * @param median          медиана (P50) траты по месяцам
 * @param p25             P25 траты
 * @param p75             P75 траты
 */
public record CategoryMonthStats(
        UUID categoryId,
        int monthsOfHistory,
        BigDecimal median,
        BigDecimal p25,
        BigDecimal p75
) {}
```

- [ ] **Step 5: Compile**

```
rtk JAVA_HOME=... ./mvnw compile
```

Ожидание: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```
git add backend/src/main/java/ru/selfin/backend/dto/strategy/
git commit -m "feat(dto): add strategy timeline phase, breakdown, stats DTOs"
```

---

### Task 1.4: DTOs — point + root

**Files:**
- Create: `backend/src/main/java/ru/selfin/backend/dto/strategy/StrategyTimelinePointDto.java`
- Create: `backend/src/main/java/ru/selfin/backend/dto/strategy/StrategyTimelineDto.java`

- [ ] **Step 1: Создать `StrategyTimelinePointDto`**

```java
package ru.selfin.backend.dto.strategy;

import java.math.BigDecimal;
import java.time.YearMonth;

/**
 * Одна точка временной шкалы стратегии — один месяц.
 * См. spec, раздел 2.
 *
 * <p>Поля {@code balanceConfirmed}, {@code balanceLow}, {@code balanceHigh}
 * заполнены ТОЛЬКО для {@code phase == CURRENT} или {@code FUTURE}; для PAST они null.
 *
 * <p>Поле {@code breakdown} заполнено только если запрос пришёл с {@code withBreakdown=true}.
 *
 * @param yearMonth         месяц (формат YYYY-MM при сериализации)
 * @param phase             PAST | CURRENT | FUTURE
 * @param balance           кумулятивный баланс на конец месяца (для CURRENT — live liquidAt(today))
 * @param income            суммарный доход месяца
 * @param expense           суммарный расход месяца
 * @param nettoFlow         income − expense
 * @param balanceConfirmed  баланс БЕЗ прогноза (только recurring + manual planned). Null для PAST.
 * @param balanceLow        P25-граница fan chart. Null для PAST.
 * @param balanceHigh       P75-граница fan chart. Null для PAST.
 * @param capital           капитал (активы − обязательства) на конец месяца
 * @param assets            сумма активов
 * @param liabilities       сумма обязательств
 * @param breakdown         разбивка по категориям; null если запрос без breakdown
 */
public record StrategyTimelinePointDto(
        YearMonth yearMonth,
        StrategyPointPhase phase,

        BigDecimal balance,
        BigDecimal income,
        BigDecimal expense,
        BigDecimal nettoFlow,

        BigDecimal balanceConfirmed,
        BigDecimal balanceLow,
        BigDecimal balanceHigh,

        BigDecimal capital,
        BigDecimal assets,
        BigDecimal liabilities,

        BreakdownDto breakdown
) {}
```

- [ ] **Step 2: Создать `StrategyTimelineDto`**

```java
package ru.selfin.backend.dto.strategy;

import java.time.YearMonth;
import java.util.List;

/**
 * Корневой DTO ответа {@code GET /api/v1/strategy/timeline}.
 * См. spec, раздел 2.
 *
 * @param firstActivityMonth     минимум из первого FACT-события, чекпоинта, capital_revaluation
 * @param currentMonth           маркер «сегодня»
 * @param horizonEnd             конец оси = currentMonth + horizonMonths
 * @param predictionWindowMonths сколько месяцев истории использовано в прогнозе
 * @param fanEnabled             false если меньше 3 категорий имеют ≥3 мес истории
 * @param points                 точки по месяцам, отсортированы по yearMonth возрастающе
 */
public record StrategyTimelineDto(
        YearMonth firstActivityMonth,
        YearMonth currentMonth,
        YearMonth horizonEnd,
        int predictionWindowMonths,
        boolean fanEnabled,
        List<StrategyTimelinePointDto> points
) {}
```

- [ ] **Step 3: Compile**

```
rtk JAVA_HOME=... ./mvnw compile
```

Ожидание: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```
git add backend/src/main/java/ru/selfin/backend/dto/strategy/
git commit -m "feat(dto): add strategy timeline point and root DTOs"
```

---

### Task 1.5: Jackson — корректная сериализация `YearMonth` как строки

**Files:**
- Modify: `backend/src/main/resources/application.properties`

**Контекст:** новые DTO используют `YearMonth` (`firstActivityMonth`, `currentMonth`, `horizonEnd`, `yearMonth`). По умолчанию Jackson сериализует `YearMonth` как JSON-массив `[2026, 5]`. Frontend ожидает строку `"2026-05"`. Без этого исправления контракт API ломается.

- [ ] **Step 1: Открыть `application.properties` и добавить строку**

В конец файла добавить (если ещё не присутствует — текущий файл не содержит):

```
# Jackson: serialise dates/YearMonth as ISO strings, not numeric arrays
spring.jackson.serialization.write-dates-as-timestamps=false
```

- [ ] **Step 2: Compile + smoke test**

```
rtk JAVA_HOME=... ./mvnw test -Dtest='!*IT'
```

Ожидание: PASS. Это пропертя влияет на runtime, не на компиляцию — но прогон unit-тестов убеждает что Spring context поднимается.

- [ ] **Step 3: Commit**

```
git add backend/src/main/resources/application.properties
git commit -m "config(json): write dates as ISO strings (YearMonth as YYYY-MM)"
```

---

## Chunk 2: PredictionService extension + StrategyTimelineService skeleton (TDD)

В этом чанке добавляем единственный новый метод в PredictionService (с тестами), создаём скелет StrategyTimelineService и реализуем `firstActivityMonth()` (тоже с тестами). Без построения точек — это следующий чанк.

### Task 2.1: PredictionService.getStatsForCategory — failing tests

**Files:**
- Create: `backend/src/test/java/ru/selfin/backend/service/PredictionServiceStatsTest.java`

- [ ] **Step 1: Создать тестовый класс с 5 тестами**

```java
package ru.selfin.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.selfin.backend.dto.strategy.CategoryMonthStats;
import ru.selfin.backend.model.Category;
import ru.selfin.backend.model.EventKind;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.enums.EventStatus;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.repository.FinancialEventRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PredictionServiceStatsTest {

    private FinancialEventRepository eventRepo;
    private PredictionService service;
    private Category cat;

    @BeforeEach
    void setUp() {
        eventRepo = mock(FinancialEventRepository.class);
        // PredictionService имеет одну зависимость: FinancialEventRepository (проверено в коде).
        service = new PredictionService(eventRepo);
        cat = Category.builder().id(UUID.randomUUID()).name("Продукты").build();
    }

    private FinancialEvent factEvent(LocalDate date, String amount) {
        return FinancialEvent.builder()
                .id(UUID.randomUUID())
                .category(cat)
                .type(EventType.EXPENSE)
                .date(date)
                .factAmount(new BigDecimal(amount))
                .eventKind(EventKind.FACT)
                .status(EventStatus.EXECUTED)
                .deleted(false)
                .build();
    }

    @Test
    void getStatsForCategory_with_6mo_history_returns_correct_percentiles() {
        // 6 месяцев фактов с разной величиной — посчитать P25, median, P75 численно
        LocalDate today = LocalDate.now();
        List<FinancialEvent> facts = List.of(
                factEvent(today.minusMonths(6).withDayOfMonth(15), "20000"),
                factEvent(today.minusMonths(5).withDayOfMonth(15), "25000"),
                factEvent(today.minusMonths(4).withDayOfMonth(15), "30000"),
                factEvent(today.minusMonths(3).withDayOfMonth(15), "35000"),
                factEvent(today.minusMonths(2).withDayOfMonth(15), "40000"),
                factEvent(today.minusMonths(1).withDayOfMonth(15), "45000")
        );
        when(eventRepo.findFactsByDateRange(any(), any())).thenReturn(facts);

        CategoryMonthStats stats = service.getStatsForCategory(cat, 6);

        assertThat(stats.categoryId()).isEqualTo(cat.getId());
        assertThat(stats.monthsOfHistory()).isEqualTo(6);
        // median = (30000 + 35000) / 2 = 32500
        assertThat(stats.median()).isEqualByComparingTo("32500");
        // p25 интерполированно между 25000 и 30000 на позиции 1.25 → 25000 + 0.25 * (30000-25000) = 26250
        assertThat(stats.p25()).isEqualByComparingTo("26250");
        // p75 интерполированно между 35000 и 40000 на позиции 3.75 → 35000 + 0.75 * (40000-35000) = 38750
        assertThat(stats.p75()).isEqualByComparingTo("38750");
    }

    @Test
    void getStatsForCategory_with_2mo_history_returns_low_history_marker() {
        LocalDate today = LocalDate.now();
        List<FinancialEvent> facts = List.of(
                factEvent(today.minusMonths(2).withDayOfMonth(15), "20000"),
                factEvent(today.minusMonths(1).withDayOfMonth(15), "30000")
        );
        when(eventRepo.findFactsByDateRange(any(), any())).thenReturn(facts);

        CategoryMonthStats stats = service.getStatsForCategory(cat, 6);

        assertThat(stats.monthsOfHistory()).isEqualTo(2);
        // Статы посчитаны на 2 точках — median между ними
        assertThat(stats.median()).isEqualByComparingTo("25000");
    }

    @Test
    void getStatsForCategory_with_zero_history_returns_zeros() {
        when(eventRepo.findFactsByDateRange(any(), any())).thenReturn(List.of());

        CategoryMonthStats stats = service.getStatsForCategory(cat, 6);

        assertThat(stats.monthsOfHistory()).isZero();
        assertThat(stats.median()).isEqualByComparingTo("0");
        assertThat(stats.p25()).isEqualByComparingTo("0");
        assertThat(stats.p75()).isEqualByComparingTo("0");
    }

    @Test
    void getStatsForCategory_ignores_soft_deleted_events() {
        LocalDate today = LocalDate.now();
        FinancialEvent live = factEvent(today.minusMonths(1).withDayOfMonth(15), "30000");
        FinancialEvent dead = factEvent(today.minusMonths(2).withDayOfMonth(15), "99999");
        dead.setDeleted(true);
        when(eventRepo.findFactsByDateRange(any(), any())).thenReturn(List.of(live, dead));

        CategoryMonthStats stats = service.getStatsForCategory(cat, 6);

        assertThat(stats.monthsOfHistory()).isEqualTo(1);
        assertThat(stats.median()).isEqualByComparingTo("30000");
    }

    @Test
    void getStatsForCategory_uses_only_FACT_events() {
        LocalDate today = LocalDate.now();
        FinancialEvent fact = factEvent(today.minusMonths(1).withDayOfMonth(15), "30000");
        FinancialEvent plan = FinancialEvent.builder()
                .id(UUID.randomUUID()).category(cat).type(EventType.EXPENSE)
                .date(today.minusMonths(2).withDayOfMonth(15))
                .plannedAmount(new BigDecimal("99999"))
                .eventKind(EventKind.PLAN)
                .status(EventStatus.PLANNED)
                .deleted(false)
                .build();
        when(eventRepo.findFactsByDateRange(any(), any())).thenReturn(List.of(fact, plan));

        CategoryMonthStats stats = service.getStatsForCategory(cat, 6);

        assertThat(stats.monthsOfHistory()).isEqualTo(1);
        assertThat(stats.median()).isEqualByComparingTo("30000");
    }
}
```

- [ ] **Step 2: Run — должно ФАЙЛИТЬ (метод ещё не существует)**

```
rtk JAVA_HOME=... ./mvnw test -Dtest=PredictionServiceStatsTest
```

Ожидание: COMPILE FAIL — `getStatsForCategory` не определён.

- [ ] **Step 3: Commit failing tests**

```
git add backend/src/test/java/ru/selfin/backend/service/PredictionServiceStatsTest.java
git commit -m "test(prediction): failing tests for getStatsForCategory"
```

---

### Task 2.2: PredictionService.getStatsForCategory — implementation

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/service/PredictionService.java`

- [ ] **Step 1: Добавить метод в PredictionService**

В существующий `PredictionService.java` добавить публичный метод (рядом с существующими методами):

```java
    /**
     * Стата трат категории за последние {@code historyWindowMonths} полных месяцев.
     * Используется StrategyTimelineService для построения fan chart.
     *
     * <p>Фильтр событий — тот же что в {@link #sumFacts}: {@code eventKind = FACT, deleted = false}.
     * {@code EventStatus} не учитывается (все FACT-события — учётные транзакции).
     *
     * <p>Если {@code monthsOfHistory < 3}, caller (StrategyTimelineService) не должен учитывать
     * категорию в расчёте конуса неопределённости — но median всё равно вычисляется.
     *
     * <p>Percentile-вычисление — линейная интерполяция между соседними точками отсортированного массива.
     */
    public CategoryMonthStats getStatsForCategory(Category cat, int historyWindowMonths) {
        LocalDate today = LocalDate.now();
        LocalDate from = today.minusMonths(historyWindowMonths).withDayOfMonth(1);
        LocalDate to = today.withDayOfMonth(1).minusDays(1);   // конец предыдущего месяца

        List<FinancialEvent> events = eventRepository.findFactsByDateRange(from, to).stream()
                .filter(e -> !e.isDeleted())
                .filter(e -> e.getEventKind() == EventKind.FACT)
                .filter(e -> e.getCategory() != null && cat.getId().equals(e.getCategory().getId()))
                .toList();

        // Группируем по месяцу
        Map<YearMonth, BigDecimal> monthlyTotals = events.stream()
                .collect(Collectors.groupingBy(
                        e -> YearMonth.from(e.getDate()),
                        Collectors.reducing(BigDecimal.ZERO,
                                e -> e.getFactAmount() != null ? e.getFactAmount() : BigDecimal.ZERO,
                                BigDecimal::add)
                ));

        if (monthlyTotals.isEmpty()) {
            return new CategoryMonthStats(cat.getId(), 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }

        List<BigDecimal> sorted = monthlyTotals.values().stream()
                .sorted()
                .toList();

        return new CategoryMonthStats(
                cat.getId(),
                sorted.size(),
                percentile(sorted, 0.50),
                percentile(sorted, 0.25),
                percentile(sorted, 0.75)
        );
    }

    /**
     * Линейная интерполяция percentile из отсортированного списка.
     * При position не на целом индексе — берёт взвешенное среднее двух соседних точек.
     */
    private BigDecimal percentile(List<BigDecimal> sorted, double q) {
        if (sorted.isEmpty()) return BigDecimal.ZERO;
        if (sorted.size() == 1) return sorted.get(0);

        double position = q * (sorted.size() - 1);
        int lowerIdx = (int) Math.floor(position);
        int upperIdx = (int) Math.ceil(position);
        if (lowerIdx == upperIdx) return sorted.get(lowerIdx);

        double frac = position - lowerIdx;
        BigDecimal lower = sorted.get(lowerIdx);
        BigDecimal upper = sorted.get(upperIdx);
        BigDecimal diff = upper.subtract(lower);
        return lower.add(diff.multiply(BigDecimal.valueOf(frac)));
    }
```

Добавить недостающие импорты вверху файла (если ещё не импортированы): `YearMonth`, `Collectors`, `Map`, `List`, `LocalDate`, `BigDecimal`, `EventKind`, `FinancialEvent`, `Category`, `CategoryMonthStats`.

- [ ] **Step 2: Run — должно PASS**

```
rtk JAVA_HOME=... ./mvnw test -Dtest=PredictionServiceStatsTest
```

Ожидание: PASS (все 5 тестов).

- [ ] **Step 3: Run all tests — убедиться что существующие методы PredictionService не сломались**

```
rtk JAVA_HOME=... ./mvnw test -Dtest='PredictionService*'
```

Ожидание: все PASS.

- [ ] **Step 4: Commit**

```
git add backend/src/main/java/ru/selfin/backend/service/PredictionService.java
git commit -m "feat(prediction): getStatsForCategory with percentile computation"
```

---

### Task 2.3: StrategyTimelineService — скелет + firstActivityMonth (failing tests)

**Files:**
- Create: `backend/src/main/java/ru/selfin/backend/service/StrategyTimelineService.java` (skeleton)
- Create: `backend/src/test/java/ru/selfin/backend/service/StrategyTimelineServiceTest.java`

- [ ] **Step 1: Создать скелет сервиса**

```java
package ru.selfin.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.selfin.backend.dto.strategy.StrategyTimelineDto;
import ru.selfin.backend.repository.BalanceCheckpointRepository;
import ru.selfin.backend.repository.CategoryRepository;
import ru.selfin.backend.repository.FinancialEventRepository;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class StrategyTimelineService {

    private final FinancialEventRepository eventRepository;
    private final BalanceCheckpointRepository checkpointRepository;
    private final CategoryRepository categoryRepository;
    private final PredictionService predictionService;
    private final CapitalService capitalService;
    // ВАЖНО: НЕ добавлять CapitalRevaluationRepository здесь.
    // Доступ к "earliest revaluation" идёт через capitalService.findEarliestRevaluationDate() —
    // CapitalService уже инжектит revRepo и предоставляет публичный метод (см. Task 2.4).

    /**
     * Главная точка входа: собирает timeline для страницы /strategy.
     */
    public StrategyTimelineDto getTimeline(int horizonMonths, boolean withBreakdown) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * Минимум из первого FACT-события, первого BalanceCheckpoint, первого CapitalRevaluation.
     * Если ничего нет — fallback на {@code LocalDate.now().minusMonths(1).withDayOfMonth(1)}.
     * Округление до первого числа месяца.
     */
    YearMonth firstActivityMonth() {
        throw new UnsupportedOperationException("Not implemented yet");
    }
}
```

> **Подтверждено:** `CapitalRevaluationRepository` существует в `backend/src/main/java/ru/selfin/backend/repository/` и уже имеет метод `findEarliestValuedAt()`. CapitalService инжектит его как поле `revRepo`. Никаких новых репозиториев / методов добавлять не нужно — только публичный wrapper-метод `findEarliestRevaluationDate()` на CapitalService (см. Task 2.4 Step 3).

- [ ] **Step 2: Создать тестовый класс с 5 тестами на firstActivityMonth**

```java
package ru.selfin.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.selfin.backend.repository.BalanceCheckpointRepository;
import ru.selfin.backend.repository.CategoryRepository;
import ru.selfin.backend.repository.FinancialEventRepository;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StrategyTimelineServiceTest {

    private FinancialEventRepository eventRepo;
    private BalanceCheckpointRepository checkpointRepo;
    private CategoryRepository categoryRepo;
    private PredictionService predictionService;
    private CapitalService capitalService;
    private StrategyTimelineService service;

    @BeforeEach
    void setUp() {
        eventRepo = mock(FinancialEventRepository.class);
        checkpointRepo = mock(BalanceCheckpointRepository.class);
        categoryRepo = mock(CategoryRepository.class);
        predictionService = mock(PredictionService.class);
        capitalService = mock(CapitalService.class);

        service = new StrategyTimelineService(eventRepo, checkpointRepo, categoryRepo,
                predictionService, capitalService);
    }

    @Test
    void firstActivityMonth_with_only_fact_event() {
        // Замокать eventRepo.findEarliestFactDate() (метод ещё не существует — будет добавлен)
        when(eventRepo.findEarliestFactDate()).thenReturn(Optional.of(LocalDate.of(2024, 3, 17)));
        when(checkpointRepo.findEarliestCheckpointDate()).thenReturn(Optional.empty());
        when(capitalService.findEarliestRevaluationDate()).thenReturn(Optional.empty());

        assertThat(service.firstActivityMonth()).isEqualTo(YearMonth.of(2024, 3));
    }

    @Test
    void firstActivityMonth_with_only_checkpoint() {
        when(eventRepo.findEarliestFactDate()).thenReturn(Optional.empty());
        when(checkpointRepo.findEarliestCheckpointDate()).thenReturn(Optional.of(LocalDate.of(2024, 2, 1)));
        when(capitalService.findEarliestRevaluationDate()).thenReturn(Optional.empty());

        assertThat(service.firstActivityMonth()).isEqualTo(YearMonth.of(2024, 2));
    }

    @Test
    void firstActivityMonth_with_only_capital_revaluation() {
        when(eventRepo.findEarliestFactDate()).thenReturn(Optional.empty());
        when(checkpointRepo.findEarliestCheckpointDate()).thenReturn(Optional.empty());
        when(capitalService.findEarliestRevaluationDate()).thenReturn(Optional.of(LocalDate.of(2023, 11, 1)));

        assertThat(service.firstActivityMonth()).isEqualTo(YearMonth.of(2023, 11));
    }

    @Test
    void firstActivityMonth_with_all_three_returns_earliest() {
        when(eventRepo.findEarliestFactDate()).thenReturn(Optional.of(LocalDate.of(2024, 3, 17)));
        when(checkpointRepo.findEarliestCheckpointDate()).thenReturn(Optional.of(LocalDate.of(2024, 1, 1)));
        when(capitalService.findEarliestRevaluationDate()).thenReturn(Optional.of(LocalDate.of(2023, 11, 1)));

        assertThat(service.firstActivityMonth()).isEqualTo(YearMonth.of(2023, 11));
    }

    @Test
    void firstActivityMonth_with_no_data_returns_previous_month() {
        when(eventRepo.findEarliestFactDate()).thenReturn(Optional.empty());
        when(checkpointRepo.findEarliestCheckpointDate()).thenReturn(Optional.empty());
        when(capitalService.findEarliestRevaluationDate()).thenReturn(Optional.empty());

        YearMonth expected = YearMonth.now().minusMonths(1);
        assertThat(service.firstActivityMonth()).isEqualTo(expected);
    }
}
```

- [ ] **Step 3: Run — должно FAIL (compile + UnsupportedOperationException)**

```
rtk JAVA_HOME=... ./mvnw test -Dtest=StrategyTimelineServiceTest
```

Ожидание: COMPILE FAIL (методы `findEarliestFactDate`, `findEarliestCheckpointDate`, `findEarliestRevaluationDate` пока не существуют).

- [ ] **Step 4: Commit failing tests + skeleton**

```
git add backend/src/main/java/ru/selfin/backend/service/StrategyTimelineService.java backend/src/test/java/ru/selfin/backend/service/StrategyTimelineServiceTest.java
git commit -m "test(strategy): failing tests for firstActivityMonth + service skeleton"
```

---

### Task 2.4: Repository methods для firstActivityMonth + implementation

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/repository/FinancialEventRepository.java`
- Modify: `backend/src/main/java/ru/selfin/backend/repository/BalanceCheckpointRepository.java`
- Modify: `backend/src/main/java/ru/selfin/backend/service/CapitalService.java`
- Modify: `backend/src/main/java/ru/selfin/backend/service/StrategyTimelineService.java`

- [ ] **Step 1: Добавить query method в FinancialEventRepository**

В файл `FinancialEventRepository.java` (в раздел `// --- Recurring ---` или новый `// --- Strategy ---`):

```java
    /**
     * Самая ранняя дата FACT-события. Null если фактов нет вообще.
     * Используется StrategyTimelineService.firstActivityMonth().
     */
    @Query("SELECT MIN(e.date) FROM FinancialEvent e " +
           "WHERE e.eventKind = ru.selfin.backend.model.EventKind.FACT " +
           "  AND e.deleted = false")
    Optional<LocalDate> findEarliestFactDate();
```

- [ ] **Step 2: Добавить query method в BalanceCheckpointRepository**

```java
    /**
     * Самая ранняя дата чекпоинта. Null если чекпоинтов нет.
     */
    @Query("SELECT MIN(b.date) FROM BalanceCheckpoint b")
    Optional<LocalDate> findEarliestCheckpointDate();
```

**Внимание:** `BalanceCheckpoint` сущность НЕ имеет soft-delete (нет поля `deleted`) — checkpoint не удаляется. Поэтому фильтр `WHERE b.deleted = false` НЕ нужен.

(Добавить импорт `@Query` если ещё не импортирован.)

- [ ] **Step 3: Добавить метод в CapitalService**

```java
    /**
     * Самая ранняя дата переоценки капитала. Null если переоценок нет.
     * Используется StrategyTimelineService.firstActivityMonth().
     */
    public Optional<LocalDate> findEarliestRevaluationDate() {
        return revRepo.findEarliestValuedAt();
    }
```

> **Подтверждено:** `revRepo` — фактическое имя поля в `CapitalService`. Метод `findEarliestValuedAt()` уже существует в `CapitalRevaluationRepository` — добавлять не нужно.

- [ ] **Step 4: Реализовать firstActivityMonth в StrategyTimelineService**

```java
    YearMonth firstActivityMonth() {
        Optional<LocalDate> earliestFact = eventRepository.findEarliestFactDate();
        Optional<LocalDate> earliestCheckpoint = checkpointRepository.findEarliestCheckpointDate();
        Optional<LocalDate> earliestRevaluation = capitalService.findEarliestRevaluationDate();

        Optional<LocalDate> earliest = Stream.of(earliestFact, earliestCheckpoint, earliestRevaluation)
                .flatMap(Optional::stream)
                .min(LocalDate::compareTo);

        return earliest
                .map(YearMonth::from)
                .orElseGet(() -> YearMonth.now().minusMonths(1));
    }
```

Добавить импорт `java.util.stream.Stream`.

- [ ] **Step 5: Run — должно PASS**

```
rtk JAVA_HOME=... ./mvnw test -Dtest=StrategyTimelineServiceTest
```

Ожидание: 5/5 PASS.

- [ ] **Step 6: Run all tests — никаких регрессий**

```
rtk JAVA_HOME=... ./mvnw test -Dtest='!*IT'
```

Ожидание: все PASS.

- [ ] **Step 7: Commit**

```
git add backend/src/main/java/ru/selfin/backend/repository/FinancialEventRepository.java backend/src/main/java/ru/selfin/backend/repository/BalanceCheckpointRepository.java backend/src/main/java/ru/selfin/backend/service/CapitalService.java backend/src/main/java/ru/selfin/backend/service/StrategyTimelineService.java
git commit -m "feat(strategy): firstActivityMonth + supporting earliest-date queries"
```

---

## Chunk 3: StrategyTimelineService body — buildPastPoints, buildFuturePoints, enrichCapital, enrichBreakdown (TDD)

Это самый большой чанк по объёму кода. Каждый шаг — TDD: тест с моками, реализация, прогон.

### Task 3.0: Добавить `findPlannedEventsByDateRange` в FinancialEventRepository

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/repository/FinancialEventRepository.java`

Этот метод нужен для будущих точек (recurring + manual planned events на месяц). Без него Task 3.3 не скомпилируется.

- [ ] **Step 1: Добавить query method**

```java
    /**
     * Все PLAN-события в диапазоне дат (включая recurring-материализованные).
     * Используется StrategyTimelineService для построения {@code balanceConfirmed} и breakdown будущих точек.
     */
    @Query("SELECT e FROM FinancialEvent e " +
           "WHERE e.deleted = false " +
           "  AND e.eventKind = ru.selfin.backend.model.EventKind.PLAN " +
           "  AND e.date >= :startDate AND e.date <= :endDate")
    List<FinancialEvent> findPlannedEventsByDateRange(
        @Param("startDate") LocalDate startDate,
        @Param("endDate") LocalDate endDate);
```

- [ ] **Step 2: Compile**

```
rtk JAVA_HOME=... ./mvnw compile
```

Ожидание: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```
git add backend/src/main/java/ru/selfin/backend/repository/FinancialEventRepository.java
git commit -m "feat(repo): add findPlannedEventsByDateRange for strategy timeline"
```

---

### Task 3.1: buildPastPoints — failing test

**Files:**
- Modify: `backend/src/test/java/ru/selfin/backend/service/StrategyTimelineServiceTest.java`

- [ ] **Step 1: Добавить тест на построение прошлых точек**

```java
    @Test
    void buildPastPoints_uses_liquidAt_per_month_and_aggregates_facts() {
        // Готовим прошлое: с марта 2024 по апрель 2026 (currentMonth = май 2026)
        // Для тест-сценария замокаем 3 месяца истории и проверим что точки построены корректно.
        YearMonth current = YearMonth.of(2026, 5);

        // capitalService.liquidAt вызывается для конца каждого прошлого месяца
        when(capitalService.liquidAt(LocalDate.of(2026, 2, 28))).thenReturn(new BigDecimal("100000"));
        when(capitalService.liquidAt(LocalDate.of(2026, 3, 31))).thenReturn(new BigDecimal("150000"));
        when(capitalService.liquidAt(LocalDate.of(2026, 4, 30))).thenReturn(new BigDecimal("180000"));

        // Замокать findFactsByDateRange чтобы вернуть факты по месяцам
        Category catFood = Category.builder().id(UUID.randomUUID()).name("Продукты").build();
        Category catSalary = Category.builder().id(UUID.randomUUID()).name("Зарплата").build();
        when(eventRepo.findFactsByDateRange(any(), any())).thenReturn(List.of(
                FinancialEvent.builder().date(LocalDate.of(2026, 3, 5)).category(catSalary)
                        .type(EventType.INCOME).factAmount(new BigDecimal("200000"))
                        .eventKind(EventKind.FACT).deleted(false).build(),
                FinancialEvent.builder().date(LocalDate.of(2026, 3, 10)).category(catFood)
                        .type(EventType.EXPENSE).factAmount(new BigDecimal("40000"))
                        .eventKind(EventKind.FACT).deleted(false).build(),
                FinancialEvent.builder().date(LocalDate.of(2026, 4, 5)).category(catSalary)
                        .type(EventType.INCOME).factAmount(new BigDecimal("200000"))
                        .eventKind(EventKind.FACT).deleted(false).build(),
                FinancialEvent.builder().date(LocalDate.of(2026, 4, 10)).category(catFood)
                        .type(EventType.EXPENSE).factAmount(new BigDecimal("35000"))
                        .eventKind(EventKind.FACT).deleted(false).build()
        ));

        List<StrategyTimelinePointDto> past = service.buildPastPoints(YearMonth.of(2026, 2), current);

        assertThat(past).hasSize(3);
        // Февраль — нет фактов в моке, баланс из liquidAt
        assertThat(past.get(0).yearMonth()).isEqualTo(YearMonth.of(2026, 2));
        assertThat(past.get(0).phase()).isEqualTo(StrategyPointPhase.PAST);
        assertThat(past.get(0).balance()).isEqualByComparingTo("100000");
        assertThat(past.get(0).balanceConfirmed()).isNull();
        assertThat(past.get(0).balanceLow()).isNull();

        // Март
        assertThat(past.get(1).income()).isEqualByComparingTo("200000");
        assertThat(past.get(1).expense()).isEqualByComparingTo("40000");
        assertThat(past.get(1).nettoFlow()).isEqualByComparingTo("160000");
        assertThat(past.get(1).balance()).isEqualByComparingTo("150000");

        // Апрель
        assertThat(past.get(2).income()).isEqualByComparingTo("200000");
        assertThat(past.get(2).expense()).isEqualByComparingTo("35000");
        assertThat(past.get(2).balance()).isEqualByComparingTo("180000");
    }
```

Не забыть импорты: `Category`, `FinancialEvent`, `EventType`, `EventKind`, `StrategyTimelinePointDto`, `StrategyPointPhase`, `BigDecimal`, `LocalDate`, `YearMonth`, `List`.

- [ ] **Step 2: Run — должно FAIL (метод не существует)**

```
rtk JAVA_HOME=... ./mvnw test -Dtest=StrategyTimelineServiceTest#buildPastPoints_uses_liquidAt_per_month_and_aggregates_facts
```

Ожидание: COMPILE FAIL.

- [ ] **Step 3: Commit failing test**

```
git add backend/src/test/java/ru/selfin/backend/service/StrategyTimelineServiceTest.java
git commit -m "test(strategy): failing test for buildPastPoints"
```

---

### Task 3.2: buildPastPoints — implementation

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/service/StrategyTimelineService.java`

- [ ] **Step 1: Реализовать buildPastPoints**

Добавить package-private метод (testable):

```java
    List<StrategyTimelinePointDto> buildPastPoints(YearMonth from, YearMonth currentMonth) {
        List<StrategyTimelinePointDto> points = new ArrayList<>();
        if (from.isAfter(currentMonth.minusMonths(1))) {
            return points; // нет прошлых месяцев
        }

        LocalDate windowStart = from.atDay(1);
        LocalDate windowEnd = currentMonth.minusMonths(1).atEndOfMonth();

        // Один запрос фактов на весь диапазон, потом группируем
        Map<YearMonth, List<FinancialEvent>> factsByMonth = eventRepository
                .findFactsByDateRange(windowStart, windowEnd).stream()
                .filter(e -> !e.isDeleted())
                .filter(e -> e.getEventKind() == EventKind.FACT)
                .collect(Collectors.groupingBy(e -> YearMonth.from(e.getDate())));

        for (YearMonth ym = from; ym.isBefore(currentMonth); ym = ym.plusMonths(1)) {
            List<FinancialEvent> facts = factsByMonth.getOrDefault(ym, List.of());

            BigDecimal income = sumByType(facts, EventType.INCOME);
            BigDecimal expense = sumByType(facts, EventType.EXPENSE);
            BigDecimal nettoFlow = income.subtract(expense);

            BigDecimal balance = capitalService.liquidAt(ym.atEndOfMonth());

            points.add(new StrategyTimelinePointDto(
                    ym,
                    StrategyPointPhase.PAST,
                    balance,
                    income,
                    expense,
                    nettoFlow,
                    null, null, null,                       // balanceConfirmed/Low/High не для PAST
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,  // капитал — заполнится в enrichWithCapital
                    null                                    // breakdown — заполнится в enrichWithBreakdown
            ));
        }
        return points;
    }

    private BigDecimal sumByType(List<FinancialEvent> facts, EventType type) {
        return facts.stream()
                .filter(e -> e.getType() == type)
                .map(e -> e.getFactAmount() != null ? e.getFactAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
```

Добавить импорты: `ArrayList`, `Collectors`, `Map`, `List`, `LocalDate`, `BigDecimal`, `YearMonth`, `FinancialEvent`, `EventKind`, `EventType`, `StrategyPointPhase`.

- [ ] **Step 2: Run — должно PASS**

```
rtk JAVA_HOME=... ./mvnw test -Dtest=StrategyTimelineServiceTest
```

Ожидание: все PASS, включая новый buildPastPoints test.

- [ ] **Step 3: Commit**

```
git add backend/src/main/java/ru/selfin/backend/service/StrategyTimelineService.java
git commit -m "feat(strategy): buildPastPoints via liquidAt per month"
```

---

### Task 3.3: buildFuturePoints — failing test

**Files:**
- Modify: `backend/src/test/java/ru/selfin/backend/service/StrategyTimelineServiceTest.java`

- [ ] **Step 1: Добавить тест**

```java
    @Test
    void buildFuturePoints_uses_recurring_planned_and_predicts_with_fan_bounds() {
        YearMonth current = YearMonth.of(2026, 5);

        // Замокать liquidAt(today) — seed для balanceConfirmed[0]
        when(capitalService.liquidAt(LocalDate.now())).thenReturn(new BigDecimal("180000"));

        // Замокать список forecast-enabled категорий: 3 категории с разной историей
        Category food = Category.builder().id(UUID.randomUUID()).name("Продукты").forecastEnabled(true).build();
        Category transport = Category.builder().id(UUID.randomUUID()).name("Транспорт").forecastEnabled(true).build();
        Category fun = Category.builder().id(UUID.randomUUID()).name("Развлечения").forecastEnabled(true).build();
        when(categoryRepo.findAllByForecastEnabledTrueAndDeletedFalse()).thenReturn(List.of(food, transport, fun));

        // PredictionService возвращает разные статы
        when(predictionService.getStatsForCategory(food, 6)).thenReturn(
                new CategoryMonthStats(food.getId(), 6,
                        new BigDecimal("35000"), new BigDecimal("30000"), new BigDecimal("40000"))); // halfIqr=5000
        when(predictionService.getStatsForCategory(transport, 6)).thenReturn(
                new CategoryMonthStats(transport.getId(), 6,
                        new BigDecimal("10000"), new BigDecimal("8000"), new BigDecimal("12000"))); // halfIqr=2000
        when(predictionService.getStatsForCategory(fun, 6)).thenReturn(
                new CategoryMonthStats(fun.getId(), 6,
                        new BigDecimal("15000"), new BigDecimal("10000"), new BigDecimal("20000"))); // halfIqr=5000

        // sumMedian = 60000, sumHalfIqr = sqrt(25M + 4M + 25M) = sqrt(54M) ≈ 7348

        // Recurring + planned событий на будущие месяцы — пусто (тест на чистый прогноз)
        when(eventRepo.findPlannedEventsByDateRange(any(), any())).thenReturn(List.of());

        List<StrategyTimelinePointDto> future = service.buildFuturePoints(current, 3);

        assertThat(future).hasSize(3);

        // Месяц 1 — k=1
        StrategyTimelinePointDto m1 = future.get(0);
        assertThat(m1.yearMonth()).isEqualTo(YearMonth.of(2026, 6));
        assertThat(m1.phase()).isEqualTo(StrategyPointPhase.FUTURE);
        // balanceConfirmed[1] = 180000 + 0 - 0 = 180000 (нет recurring/planned)
        assertThat(m1.balanceConfirmed()).isEqualByComparingTo("180000");
        // balanceMedian[1] = 180000 - 60000 * 1 = 120000
        assertThat(m1.balance()).isEqualByComparingTo("120000");
        // accumulatedHalfIqr[1] = 7348 * sqrt(1) ≈ 7348
        // balanceLow = 120000 - 7348 ≈ 112652, balanceHigh = 120000 + 7348 ≈ 127348
        assertThat(m1.balanceLow().doubleValue()).isCloseTo(112652, within(50.0));
        assertThat(m1.balanceHigh().doubleValue()).isCloseTo(127348, within(50.0));

        // Месяц 3 — k=3
        StrategyTimelinePointDto m3 = future.get(2);
        // balanceMedian[3] = 180000 - 60000 * 3 = 0
        assertThat(m3.balance()).isEqualByComparingTo("0");
        // accumulatedHalfIqr[3] = 7348 * sqrt(3) ≈ 12727; cap=2×|0|=0
        // НО т.к. balanceMedian=0, cap=0 → fan ширина 0
        // Это интересный edge case спеки — если balanceMedian близко к 0, cap его жёстко прижимает
        // Реалистичнее использовать absolute cap, но спека пока такая — оставляем
        assertThat(m3.balanceLow()).isEqualByComparingTo("0");
        assertThat(m3.balanceHigh()).isEqualByComparingTo("0");
    }
```

> **Внимание к cap:** в спеке `cap = 2 × |balanceMedian|`. Если balanceMedian близко к нулю — fan тоже становится нулевым. Тест выше демонстрирует это поведение. Если хочется иную семантику (fan не ужимается ниже sumHalfIqr × √k) — это другое требование, поднять с пользователем (см. recommendation в спеке).

Добавить импорт `within` из `org.assertj.core.data.Offset`:
```java
import static org.assertj.core.data.Offset.offset;
import static org.assertj.core.api.Assertions.within;
```

Если `within` недоступен — заменить на `assertThat(value).isCloseTo(expected, offset(50.0))`.

- [ ] **Step 2: Run — должно FAIL (метод не существует)**

```
rtk JAVA_HOME=... ./mvnw test -Dtest=StrategyTimelineServiceTest#buildFuturePoints_uses_recurring_planned_and_predicts_with_fan_bounds
```

Ожидание: COMPILE FAIL.

- [ ] **Step 3: Commit failing test**

```
git add backend/src/test/java/ru/selfin/backend/service/StrategyTimelineServiceTest.java
git commit -m "test(strategy): failing test for buildFuturePoints with fan chart"
```

---

### Task 3.4: buildFuturePoints — implementation

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/service/StrategyTimelineService.java`

- [ ] **Step 1: Реализовать**

```java
    /**
     * @param current        текущий месяц
     * @param horizonMonths  сколько будущих месяцев построить (включая current+1 … current+horizonMonths)
     */
    List<StrategyTimelinePointDto> buildFuturePoints(YearMonth current, int horizonMonths) {
        List<StrategyTimelinePointDto> points = new ArrayList<>();
        if (horizonMonths <= 0) return points;

        // Шаг 1: forecast-категории и их статы
        List<Category> forecastCats = categoryRepository.findAllByForecastEnabledTrueAndDeletedFalse();
        List<CategoryMonthStats> allStats = forecastCats.stream()
                .map(c -> predictionService.getStatsForCategory(c, PREDICTION_WINDOW_MONTHS))
                .toList();
        List<CategoryMonthStats> eligibleStats = allStats.stream()
                .filter(s -> s.monthsOfHistory() >= MIN_HISTORY_FOR_FAN)
                .toList();

        BigDecimal sumMedian = eligibleStats.stream()
                .map(CategoryMonthStats::median)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        double sumHalfIqr = Math.sqrt(eligibleStats.stream()
                .mapToDouble(s -> {
                    double halfIqr = s.p75().subtract(s.p25()).doubleValue() / 2.0;
                    return halfIqr * halfIqr;
                })
                .sum());

        boolean fanEnabled = eligibleStats.size() >= MIN_CATEGORIES_FOR_FAN;

        // Шаг 2: планы (recurring + manual) на будущее
        LocalDate futureStart = current.plusMonths(1).atDay(1);
        LocalDate futureEnd = current.plusMonths(horizonMonths).atEndOfMonth();
        Map<YearMonth, List<FinancialEvent>> plannedByMonth = eventRepository
                .findPlannedEventsByDateRange(futureStart, futureEnd).stream()
                .filter(e -> !e.isDeleted())
                .filter(e -> e.getEventKind() == EventKind.PLAN)
                .collect(Collectors.groupingBy(e -> YearMonth.from(e.getDate())));

        // Шаг 3: построение точек
        BigDecimal balanceConfirmed = capitalService.liquidAt(LocalDate.now());

        for (int k = 1; k <= horizonMonths; k++) {
            YearMonth ym = current.plusMonths(k);
            List<FinancialEvent> planned = plannedByMonth.getOrDefault(ym, List.of());

            BigDecimal confirmedIncome = sumPlannedByType(planned, EventType.INCOME);
            BigDecimal confirmedExpense = sumPlannedByType(planned, EventType.EXPENSE);
            balanceConfirmed = balanceConfirmed.add(confirmedIncome).subtract(confirmedExpense);

            BigDecimal balanceMedian = balanceConfirmed.subtract(sumMedian.multiply(BigDecimal.valueOf(k)));

            BigDecimal balanceLow, balanceHigh;
            if (fanEnabled) {
                double rawHalfIqr = sumHalfIqr * Math.sqrt(k);
                double capCeiling = 2.0 * Math.abs(balanceMedian.doubleValue());
                double accumulatedHalfIqr = Math.min(rawHalfIqr, capCeiling);
                BigDecimal halfIqrBd = BigDecimal.valueOf(accumulatedHalfIqr)
                        .setScale(2, RoundingMode.HALF_UP);
                balanceLow = balanceMedian.subtract(halfIqrBd);
                balanceHigh = balanceMedian.add(halfIqrBd);
            } else {
                balanceLow = balanceMedian;
                balanceHigh = balanceMedian;
            }

            points.add(new StrategyTimelinePointDto(
                    ym,
                    StrategyPointPhase.FUTURE,
                    balanceMedian,
                    confirmedIncome,
                    confirmedExpense.add(sumMedian),    // expense = confirmed + prediction
                    confirmedIncome.subtract(confirmedExpense.add(sumMedian)),  // nettoFlow
                    balanceConfirmed,
                    balanceLow,
                    balanceHigh,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    null
            ));
        }
        return points;
    }

    private BigDecimal sumPlannedByType(List<FinancialEvent> events, EventType type) {
        return events.stream()
                .filter(e -> e.getType() == type)
                .map(e -> e.getPlannedAmount() != null ? e.getPlannedAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static final int PREDICTION_WINDOW_MONTHS = 6;
    private static final int MIN_HISTORY_FOR_FAN = 3;
    private static final int MIN_CATEGORIES_FOR_FAN = 3;
```

Добавить импорт `RoundingMode`.

> **Note:** метод `findPlannedEventsByDateRange` добавлен в Task 3.0 (Chunk 3). Здесь уже доступен.

- [ ] **Step 2: Run — должно PASS**

```
rtk JAVA_HOME=... ./mvnw test -Dtest=StrategyTimelineServiceTest#buildFuturePoints_uses_recurring_planned_and_predicts_with_fan_bounds
```

Ожидание: PASS.

- [ ] **Step 3: Commit**

```
git add backend/src/main/java/ru/selfin/backend/service/StrategyTimelineService.java
git commit -m "feat(strategy): buildFuturePoints with fan chart computation"
```

---

### Task 3.5: enrichWithCapital — test + implementation

**Files:**
- Modify: `backend/src/test/java/ru/selfin/backend/service/StrategyTimelineServiceTest.java`
- Modify: `backend/src/main/java/ru/selfin/backend/service/StrategyTimelineService.java`

- [ ] **Step 1: Failing test**

```java
    @Test
    void enrichWithCapital_fills_capital_assets_liabilities_for_past_and_future() {
        // Подаём 3 точки (2 PAST + 1 FUTURE) с нулевыми капитал-полями
        YearMonth jan = YearMonth.of(2026, 1);
        YearMonth feb = YearMonth.of(2026, 2);
        YearMonth jun = YearMonth.of(2026, 6);
        List<StrategyTimelinePointDto> points = new ArrayList<>(List.of(
                pointWith(jan, StrategyPointPhase.PAST),
                pointWith(feb, StrategyPointPhase.PAST),
                pointWith(jun, StrategyPointPhase.FUTURE)
        ));

        // Замокать trajectory: 2 прошлые точки + 0 будущих в реальности
        CapitalTrajectoryDto trajectory = new CapitalTrajectoryDto(List.of(
                new CapitalTrajectoryDto.Point(LocalDate.of(2026, 1, 31),
                        new BigDecimal("4000000"), new BigDecimal("4500000"), new BigDecimal("500000")),
                new CapitalTrajectoryDto.Point(LocalDate.of(2026, 2, 28),
                        new BigDecimal("4100000"), new BigDecimal("4600000"), new BigDecimal("500000"))
        ));
        when(capitalService.trajectory(any(), any())).thenReturn(trajectory);

        List<StrategyTimelinePointDto> result = service.enrichWithCapital(points);

        assertThat(result.get(0).capital()).isEqualByComparingTo("4000000");
        assertThat(result.get(1).capital()).isEqualByComparingTo("4100000");
        // Future месяц получает last-known: 4100000
        assertThat(result.get(2).capital()).isEqualByComparingTo("4100000");
        assertThat(result.get(2).assets()).isEqualByComparingTo("4600000");
        assertThat(result.get(2).liabilities()).isEqualByComparingTo("500000");
    }

    // helper-метод pointWith ниже
    private StrategyTimelinePointDto pointWith(YearMonth ym, StrategyPointPhase phase) {
        return new StrategyTimelinePointDto(ym, phase,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                null, null, null,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                null);
    }
```

- [ ] **Step 2: Run — должно FAIL**

```
rtk JAVA_HOME=... ./mvnw test -Dtest=StrategyTimelineServiceTest#enrichWithCapital_fills_capital_assets_liabilities_for_past_and_future
```

Ожидание: COMPILE FAIL (метод не существует).

- [ ] **Step 3: Реализовать enrichWithCapital**

В `StrategyTimelineService.java`:

```java
    /**
     * Обогащает точки timeline данными капитала (capital, assets, liabilities).
     *
     * <p><b>Контракт:</b> {@code points} ДОЛЖЕН быть полным списком (past + current + future)
     * — диапазон вызова `trajectory(first, last)` определяется крайними точками. При передаче
     * частичного списка trajectory будет вычислена на меньшем интервале, и future-точки могут
     * получить некорректные значения капитала.
     */
    List<StrategyTimelinePointDto> enrichWithCapital(List<StrategyTimelinePointDto> points) {
        if (points.isEmpty()) return points;

        YearMonth first = points.get(0).yearMonth();
        YearMonth last = points.get(points.size() - 1).yearMonth();

        CapitalTrajectoryDto trajectory = capitalService.trajectory(first.atDay(1), last.atEndOfMonth());

        // Маппим точки траектории по YearMonth
        Map<YearMonth, CapitalTrajectoryDto.Point> byMonth = trajectory.points().stream()
                .collect(Collectors.toMap(
                        p -> YearMonth.from(p.date()),
                        p -> p,
                        (a, b) -> b   // если коллизия — берём более поздний
                ));

        // Last known — для пропусков и для будущих точек после последней revaluation
        BigDecimal lastCapital = BigDecimal.ZERO;
        BigDecimal lastAssets = BigDecimal.ZERO;
        BigDecimal lastLiabilities = BigDecimal.ZERO;

        List<StrategyTimelinePointDto> enriched = new ArrayList<>(points.size());
        for (StrategyTimelinePointDto p : points) {
            CapitalTrajectoryDto.Point cap = byMonth.get(p.yearMonth());
            if (cap != null) {
                lastCapital = cap.capital();
                lastAssets = cap.assets();
                lastLiabilities = cap.liabilities();
            }
            enriched.add(new StrategyTimelinePointDto(
                    p.yearMonth(), p.phase(),
                    p.balance(), p.income(), p.expense(), p.nettoFlow(),
                    p.balanceConfirmed(), p.balanceLow(), p.balanceHigh(),
                    lastCapital, lastAssets, lastLiabilities,
                    p.breakdown()
            ));
        }
        return enriched;
    }
```

Импорты: `CapitalTrajectoryDto`.

- [ ] **Step 4: Run — должно PASS**

```
rtk JAVA_HOME=... ./mvnw test -Dtest=StrategyTimelineServiceTest
```

Ожидание: все PASS.

- [ ] **Step 5: Commit**

```
git add backend/src/main/java/ru/selfin/backend/service/StrategyTimelineService.java backend/src/test/java/ru/selfin/backend/service/StrategyTimelineServiceTest.java
git commit -m "feat(strategy): enrichWithCapital reuses CapitalService.trajectory"
```

---

### Task 3.6: enrichWithBreakdown — test + implementation

**Files:**
- Modify: `backend/src/test/java/ru/selfin/backend/service/StrategyTimelineServiceTest.java`
- Modify: `backend/src/main/java/ru/selfin/backend/service/StrategyTimelineService.java`

- [ ] **Step 1: Failing test для PAST точки**

```java
    @Test
    void enrichWithBreakdown_for_past_aggregates_facts_by_category() {
        YearMonth mar = YearMonth.of(2026, 3);
        StrategyTimelinePointDto march = pointWith(mar, StrategyPointPhase.PAST);

        Category salary = Category.builder().id(UUID.randomUUID()).name("Зарплата").build();
        Category food = Category.builder().id(UUID.randomUUID()).name("Продукты").build();

        when(eventRepo.findFactsByDateRange(mar.atDay(1), mar.atEndOfMonth())).thenReturn(List.of(
                FinancialEvent.builder().date(LocalDate.of(2026, 3, 5)).category(salary)
                        .type(EventType.INCOME).factAmount(new BigDecimal("200000"))
                        .eventKind(EventKind.FACT).deleted(false).build(),
                FinancialEvent.builder().date(LocalDate.of(2026, 3, 10)).category(food)
                        .type(EventType.EXPENSE).factAmount(new BigDecimal("40000"))
                        .eventKind(EventKind.FACT).deleted(false).build()
        ));

        List<StrategyTimelinePointDto> result = service.enrichWithBreakdown(List.of(march));

        BreakdownDto br = result.get(0).breakdown();
        assertThat(br).isNotNull();
        assertThat(br.incomeItems()).hasSize(1);
        assertThat(br.incomeItems().get(0).category()).isEqualTo("Зарплата");
        assertThat(br.incomeItems().get(0).amount()).isEqualByComparingTo("200000");
        assertThat(br.expenseItems()).hasSize(1);
        assertThat(br.expenseItems().get(0).category()).isEqualTo("Продукты");
        assertThat(br.expenseItems().get(0).amount()).isEqualByComparingTo("40000");
    }
```

- [ ] **Step 2: Failing test для FUTURE точки**

```java
    @Test
    void enrichWithBreakdown_for_future_includes_recurring_planned_and_predicted() {
        YearMonth jun = YearMonth.of(2026, 6);
        StrategyTimelinePointDto june = pointWith(jun, StrategyPointPhase.FUTURE);

        Category mortgage = Category.builder().id(UUID.randomUUID()).name("Ипотека").build();
        Category food = Category.builder().id(UUID.randomUUID()).name("Продукты").forecastEnabled(true).build();
        RecurringRule rule = RecurringRule.builder().id(UUID.randomUUID()).build();

        // Recurring planned EXPENSE на ипотеку
        when(eventRepo.findPlannedEventsByDateRange(jun.atDay(1), jun.atEndOfMonth())).thenReturn(List.of(
                FinancialEvent.builder().date(LocalDate.of(2026, 6, 15)).category(mortgage)
                        .type(EventType.EXPENSE).plannedAmount(new BigDecimal("80000"))
                        .eventKind(EventKind.PLAN).recurringRule(rule).deleted(false).build()
        ));

        // Forecast-enabled категории и их прогноз
        when(categoryRepo.findAllByForecastEnabledTrueAndDeletedFalse()).thenReturn(List.of(food));
        when(predictionService.getStatsForCategory(food, 6)).thenReturn(
                new CategoryMonthStats(food.getId(), 6,
                        new BigDecimal("35000"), new BigDecimal("30000"), new BigDecimal("40000")));

        List<StrategyTimelinePointDto> result = service.enrichWithBreakdown(List.of(june));

        BreakdownDto br = result.get(0).breakdown();
        assertThat(br.expenseItems()).hasSize(2);

        // Recurring ипотека
        BreakdownItemDto mortgageItem = br.expenseItems().stream()
                .filter(i -> i.category().equals("Ипотека"))
                .findFirst().orElseThrow();
        assertThat(mortgageItem.amount()).isEqualByComparingTo("80000");
        assertThat(mortgageItem.isRecurring()).isTrue();
        assertThat(mortgageItem.isPredicted()).isFalse();

        // Predicted продукты
        BreakdownItemDto foodItem = br.expenseItems().stream()
                .filter(i -> i.category().equals("Продукты"))
                .findFirst().orElseThrow();
        assertThat(foodItem.amount()).isEqualByComparingTo("35000");
        assertThat(foodItem.isRecurring()).isFalse();
        assertThat(foodItem.isPredicted()).isTrue();
    }
```

- [ ] **Step 3: Run — должно FAIL**

```
rtk JAVA_HOME=... ./mvnw test -Dtest=StrategyTimelineServiceTest
```

Ожидание: compile fail / новые тесты падают.

- [ ] **Step 4: Реализовать enrichWithBreakdown**

```java
    List<StrategyTimelinePointDto> enrichWithBreakdown(List<StrategyTimelinePointDto> points) {
        List<StrategyTimelinePointDto> enriched = new ArrayList<>(points.size());
        for (StrategyTimelinePointDto p : points) {
            BreakdownDto br = switch (p.phase()) {
                case PAST -> breakdownForPast(p.yearMonth());
                case CURRENT -> breakdownForCurrent(p.yearMonth());
                case FUTURE -> breakdownForFuture(p.yearMonth());
            };
            enriched.add(withBreakdown(p, br));
        }
        return enriched;
    }

    private BreakdownDto breakdownForPast(YearMonth ym) {
        List<FinancialEvent> facts = eventRepository.findFactsByDateRange(ym.atDay(1), ym.atEndOfMonth()).stream()
                .filter(e -> !e.isDeleted())
                .filter(e -> e.getEventKind() == EventKind.FACT)
                .toList();
        return new BreakdownDto(
                aggregateByCategory(facts, EventType.INCOME, FinancialEvent::getFactAmount, false, false),
                aggregateByCategory(facts, EventType.EXPENSE, FinancialEvent::getFactAmount, false, false)
        );
    }

    private BreakdownDto breakdownForFuture(YearMonth ym) {
        // Recurring + manual planned (recurring distinguished by recurringRule != null)
        List<FinancialEvent> planned = eventRepository.findPlannedEventsByDateRange(ym.atDay(1), ym.atEndOfMonth()).stream()
                .filter(e -> !e.isDeleted())
                .filter(e -> e.getEventKind() == EventKind.PLAN)
                .toList();

        List<BreakdownItemDto> incomeItems = new ArrayList<>(
                planned.stream()
                        .filter(e -> e.getType() == EventType.INCOME)
                        .collect(Collectors.groupingBy(
                                e -> e.getCategory().getName(),
                                Collectors.collectingAndThen(Collectors.toList(),
                                        list -> new BreakdownItemDto(
                                                list.get(0).getCategory().getName(),
                                                list.stream().map(e -> e.getPlannedAmount() != null ? e.getPlannedAmount() : BigDecimal.ZERO)
                                                        .reduce(BigDecimal.ZERO, BigDecimal::add),
                                                list.stream().anyMatch(e -> e.getRecurringRule() != null),
                                                false
                                        ))))
                        .values()
        );

        List<BreakdownItemDto> expenseItems = new ArrayList<>(
                planned.stream()
                        .filter(e -> e.getType() == EventType.EXPENSE)
                        .collect(Collectors.groupingBy(
                                e -> e.getCategory().getName(),
                                Collectors.collectingAndThen(Collectors.toList(),
                                        list -> new BreakdownItemDto(
                                                list.get(0).getCategory().getName(),
                                                list.stream().map(e -> e.getPlannedAmount() != null ? e.getPlannedAmount() : BigDecimal.ZERO)
                                                        .reduce(BigDecimal.ZERO, BigDecimal::add),
                                                list.stream().anyMatch(e -> e.getRecurringRule() != null),
                                                false
                                        ))))
                        .values()
        );

        // Добавляем predicted items для forecast-категорий
        List<Category> forecastCats = categoryRepository.findAllByForecastEnabledTrueAndDeletedFalse();
        for (Category cat : forecastCats) {
            CategoryMonthStats stats = predictionService.getStatsForCategory(cat, PREDICTION_WINDOW_MONTHS);
            if (stats.median().compareTo(BigDecimal.ZERO) > 0) {
                expenseItems.add(new BreakdownItemDto(cat.getName(), stats.median(), false, true));
            }
        }
        return new BreakdownDto(incomeItems, expenseItems);
    }

    private BreakdownDto breakdownForCurrent(YearMonth ym) {
        // Текущий месяц: факты до сегодня + прогноз остатка
        // Для MVP — то же что past + добавляем pro-rated прогноз
        BreakdownDto past = breakdownForPast(ym);

        List<BreakdownItemDto> expense = new ArrayList<>(past.expenseItems());

        // Pro-rated прогноз
        LocalDate today = LocalDate.now();
        int daysInMonth = ym.lengthOfMonth();
        int daysRemaining = Math.max(0, daysInMonth - today.getDayOfMonth());
        double fraction = (double) daysRemaining / daysInMonth;

        if (fraction > 0) {
            List<Category> forecastCats = categoryRepository.findAllByForecastEnabledTrueAndDeletedFalse();
            for (Category cat : forecastCats) {
                CategoryMonthStats stats = predictionService.getStatsForCategory(cat, PREDICTION_WINDOW_MONTHS);
                BigDecimal proRated = stats.median().multiply(BigDecimal.valueOf(fraction))
                        .setScale(2, RoundingMode.HALF_UP);
                if (proRated.compareTo(BigDecimal.ZERO) > 0) {
                    expense.add(new BreakdownItemDto(cat.getName() + " (прогноз остатка)", proRated, false, true));
                }
            }
        }
        return new BreakdownDto(past.incomeItems(), expense);
    }

    private List<BreakdownItemDto> aggregateByCategory(List<FinancialEvent> events, EventType type,
                                                       java.util.function.Function<FinancialEvent, BigDecimal> amountFn,
                                                       boolean isRecurring, boolean isPredicted) {
        return events.stream()
                .filter(e -> e.getType() == type)
                .filter(e -> e.getCategory() != null)
                .collect(Collectors.groupingBy(
                        e -> e.getCategory().getName(),
                        Collectors.reducing(BigDecimal.ZERO,
                                e -> amountFn.apply(e) != null ? amountFn.apply(e) : BigDecimal.ZERO,
                                BigDecimal::add)
                ))
                .entrySet().stream()
                .map(en -> new BreakdownItemDto(en.getKey(), en.getValue(), isRecurring, isPredicted))
                .sorted((a, b) -> b.amount().compareTo(a.amount()))    // сортировка по убыванию суммы
                .toList();
    }

    private StrategyTimelinePointDto withBreakdown(StrategyTimelinePointDto p, BreakdownDto br) {
        return new StrategyTimelinePointDto(
                p.yearMonth(), p.phase(),
                p.balance(), p.income(), p.expense(), p.nettoFlow(),
                p.balanceConfirmed(), p.balanceLow(), p.balanceHigh(),
                p.capital(), p.assets(), p.liabilities(),
                br
        );
    }
```

- [ ] **Step 5: Run — должно PASS**

```
rtk JAVA_HOME=... ./mvnw test -Dtest=StrategyTimelineServiceTest
```

Ожидание: все PASS.

- [ ] **Step 6: Commit**

```
git add backend/src/main/java/ru/selfin/backend/service/StrategyTimelineService.java backend/src/test/java/ru/selfin/backend/service/StrategyTimelineServiceTest.java
git commit -m "feat(strategy): enrichWithBreakdown for past/current/future phases"
```

---

### Task 3.7: getTimeline — composer + CURRENT точка

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/service/StrategyTimelineService.java`
- Modify: `backend/src/test/java/ru/selfin/backend/service/StrategyTimelineServiceTest.java`

- [ ] **Step 1: Failing test на getTimeline()**

```java
    @Test
    void getTimeline_assembles_past_current_future_with_capital_and_breakdown() {
        // Замокать минимально, чтобы пройти всю цепочку
        when(eventRepo.findEarliestFactDate()).thenReturn(Optional.of(LocalDate.now().minusMonths(2)));
        when(checkpointRepo.findEarliestCheckpointDate()).thenReturn(Optional.empty());
        when(capitalService.findEarliestRevaluationDate()).thenReturn(Optional.empty());

        when(capitalService.liquidAt(any())).thenReturn(new BigDecimal("100000"));
        when(eventRepo.findFactsByDateRange(any(), any())).thenReturn(List.of());
        when(eventRepo.findPlannedEventsByDateRange(any(), any())).thenReturn(List.of());
        when(categoryRepo.findAllByForecastEnabledTrueAndDeletedFalse()).thenReturn(List.of());
        when(capitalService.trajectory(any(), any())).thenReturn(new CapitalTrajectoryDto(List.of()));

        StrategyTimelineDto dto = service.getTimeline(3, true);

        // 2 past + 1 current + 3 future = 6
        assertThat(dto.points()).hasSize(6);
        assertThat(dto.currentMonth()).isEqualTo(YearMonth.now());
        assertThat(dto.horizonEnd()).isEqualTo(YearMonth.now().plusMonths(3));
        assertThat(dto.predictionWindowMonths()).isEqualTo(6);
        assertThat(dto.fanEnabled()).isFalse(); // нет forecast-категорий
    }

    @Test
    void getTimeline_currentMonth_balance_uses_liquidAt_today() {
        when(eventRepo.findEarliestFactDate()).thenReturn(Optional.empty());
        when(checkpointRepo.findEarliestCheckpointDate()).thenReturn(Optional.empty());
        when(capitalService.findEarliestRevaluationDate()).thenReturn(Optional.empty());
        // Fallback firstActivityMonth = today.minusMonths(1) — значит будут PAST + CURRENT + FUTURE
        when(capitalService.liquidAt(LocalDate.now())).thenReturn(new BigDecimal("550000"));
        when(capitalService.liquidAt(any())).thenReturn(new BigDecimal("550000"));
        when(eventRepo.findFactsByDateRange(any(), any())).thenReturn(List.of());
        when(eventRepo.findPlannedEventsByDateRange(any(), any())).thenReturn(List.of());
        when(categoryRepo.findAllByForecastEnabledTrueAndDeletedFalse()).thenReturn(List.of());
        when(capitalService.trajectory(any(), any())).thenReturn(new CapitalTrajectoryDto(List.of()));

        StrategyTimelineDto dto = service.getTimeline(2, false);

        StrategyTimelinePointDto current = dto.points().stream()
                .filter(p -> p.phase() == StrategyPointPhase.CURRENT)
                .findFirst().orElseThrow();
        assertThat(current.balance()).isEqualByComparingTo("550000");
        assertThat(current.yearMonth()).isEqualTo(YearMonth.now());
    }
```

- [ ] **Step 2: Реализовать getTimeline**

```java
    @Override
    public StrategyTimelineDto getTimeline(int horizonMonths, boolean withBreakdown) {
        YearMonth firstMonth = firstActivityMonth();
        YearMonth currentMonth = YearMonth.now();
        YearMonth horizonEnd = currentMonth.plusMonths(horizonMonths);

        List<StrategyTimelinePointDto> past = buildPastPoints(firstMonth, currentMonth);

        StrategyTimelinePointDto current = buildCurrentPoint(currentMonth);

        List<StrategyTimelinePointDto> future = buildFuturePoints(currentMonth, horizonMonths);

        // fanEnabled определяется внутри buildFuturePoints — нужно вытащить
        // Для простоты — пересчитываем здесь
        boolean fanEnabled = isFanEnabled();

        List<StrategyTimelinePointDto> all = new ArrayList<>();
        all.addAll(past);
        all.add(current);
        all.addAll(future);

        all = enrichWithCapital(all);
        if (withBreakdown) {
            all = enrichWithBreakdown(all);
        }

        return new StrategyTimelineDto(
                firstMonth,
                currentMonth,
                horizonEnd,
                PREDICTION_WINDOW_MONTHS,
                fanEnabled,
                all
        );
    }

    StrategyTimelinePointDto buildCurrentPoint(YearMonth current) {
        LocalDate today = LocalDate.now();
        LocalDate monthStart = current.atDay(1);
        LocalDate monthEnd = current.atEndOfMonth();

        // Факты с начала месяца до сегодня
        List<FinancialEvent> factsToDate = eventRepository.findFactsByDateRange(monthStart, today).stream()
                .filter(e -> !e.isDeleted())
                .filter(e -> e.getEventKind() == EventKind.FACT)
                .toList();

        BigDecimal incomeToDate = sumByType(factsToDate, EventType.INCOME);
        BigDecimal expenseToDate = sumByType(factsToDate, EventType.EXPENSE);
        BigDecimal nettoFlow = incomeToDate.subtract(expenseToDate);

        // balance для CURRENT — live liquidAt(today), не end-of-month проекция
        BigDecimal balance = capitalService.liquidAt(today);

        return new StrategyTimelinePointDto(
                current,
                StrategyPointPhase.CURRENT,
                balance,
                incomeToDate,
                expenseToDate,
                nettoFlow,
                balance,   // balanceConfirmed = текущий live баланс
                balance,   // balanceLow = balance (нет прогноза накопленного на текущий месяц)
                balance,   // balanceHigh = balance
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                null
        );
    }

    private boolean isFanEnabled() {
        List<Category> cats = categoryRepository.findAllByForecastEnabledTrueAndDeletedFalse();
        long eligibleCount = cats.stream()
                .map(c -> predictionService.getStatsForCategory(c, PREDICTION_WINDOW_MONTHS))
                .filter(s -> s.monthsOfHistory() >= MIN_HISTORY_FOR_FAN)
                .count();
        return eligibleCount >= MIN_CATEGORIES_FOR_FAN;
    }
```

- [ ] **Step 3: Run**

```
rtk JAVA_HOME=... ./mvnw test -Dtest=StrategyTimelineServiceTest
```

Ожидание: все PASS.

- [ ] **Step 4: Commit**

```
git add backend/src/main/java/ru/selfin/backend/service/StrategyTimelineService.java backend/src/test/java/ru/selfin/backend/service/StrategyTimelineServiceTest.java
git commit -m "feat(strategy): getTimeline composes past + current + future + capital + breakdown"
```

---

## Chunk 4: Controller + IT тесты

### Task 4.1: StrategyTimelineController

**Files:**
- Create: `backend/src/main/java/ru/selfin/backend/controller/StrategyTimelineController.java`

- [ ] **Step 1: Создать контроллер**

```java
package ru.selfin.backend.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.selfin.backend.dto.strategy.StrategyTimelineDto;
import ru.selfin.backend.service.StrategyTimelineService;

@RestController
@RequestMapping("/api/v1/strategy")
@RequiredArgsConstructor
public class StrategyTimelineController {

    private final StrategyTimelineService service;

    @GetMapping("/timeline")
    public StrategyTimelineDto getTimeline(
            @RequestParam(defaultValue = "36") int horizonMonths,
            @RequestParam(defaultValue = "true") boolean withBreakdown
    ) {
        // Гарантируем безопасный максимум
        int safeHorizon = Math.min(Math.max(horizonMonths, 1), 60);
        return service.getTimeline(safeHorizon, withBreakdown);
    }
}
```

- [ ] **Step 2: Compile + smoke (поднимется Spring context)**

```
rtk JAVA_HOME=... ./mvnw test -Dtest=BackendApplicationTests
```

(Если эта тестовая обёртка не работает локально из-за Docker — `mvnw compile` достаточно.)

Ожидание: PASS или хотя бы compile success.

- [ ] **Step 3: Commit**

```
git add backend/src/main/java/ru/selfin/backend/controller/StrategyTimelineController.java
git commit -m "feat(controller): GET /api/v1/strategy/timeline"
```

---

### Task 4.2: IT тесты — 6 сценариев

**Files:**
- Create: `backend/src/test/java/ru/selfin/backend/StrategyTimelineControllerIT.java`

> Этот файл аналогичен по структуре `RecurringEventControllerIT.java` и `CapitalControllerIT.java` (есть в репо после капитала). Использовать те же аннотации (`@SpringBootTest`, `@AutoConfigureMockMvc`, `@Testcontainers`, `@ServiceConnection PostgreSQLContainer`).

- [ ] **Step 1: Скаффолд класса + 1 тест**

```java
package ru.selfin.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;   // Spring Boot 4.x
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class StrategyTimelineControllerIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void getTimeline_returns_200_with_valid_shape() throws Exception {
        mockMvc.perform(get("/api/v1/strategy/timeline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstActivityMonth").exists())
                .andExpect(jsonPath("$.currentMonth").exists())
                .andExpect(jsonPath("$.horizonEnd").exists())
                .andExpect(jsonPath("$.predictionWindowMonths").value(6))
                .andExpect(jsonPath("$.fanEnabled").exists())
                .andExpect(jsonPath("$.points").isArray())
                .andExpect(jsonPath("$.points.length()", greaterThanOrEqualTo(1)));
    }
}
```

- [ ] **Step 2: Run (требует Docker)**

```
rtk JAVA_HOME=... ./mvnw test -Dtest=StrategyTimelineControllerIT
```

Если Docker недоступен — отметить test пропущенным и отложить на CI.

- [ ] **Step 3: Добавить тесты 2-6**

```java
    @Test
    void getTimeline_with_horizon12_limits_future_points() throws Exception {
        String body = mockMvc.perform(get("/api/v1/strategy/timeline").param("horizonMonths", "12"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        var dto = objectMapper.readValue(body, java.util.Map.class);
        java.util.List<?> points = (java.util.List<?>) dto.get("points");
        long futureCount = points.stream()
                .filter(p -> "FUTURE".equals(((java.util.Map<?, ?>) p).get("phase")))
                .count();
        // FUTURE count = horizonMonths (создаём ровно horizonMonths точек в будущем).
        // Этот тест НЕ зависит от состояния контейнера, потому что horizonMonths определяет
        // количество future-точек напрямую (за минусом возможной коллизии в логике current+1..current+12).
        org.assertj.core.api.Assertions.assertThat(futureCount).isEqualTo(12);
    }

    @Test
    void getTimeline_with_withBreakdown_false_omits_breakdown() throws Exception {
        String body = mockMvc.perform(get("/api/v1/strategy/timeline").param("withBreakdown", "false"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Все breakdown должны быть null
        var dto = objectMapper.readValue(body, java.util.Map.class);
        java.util.List<?> points = (java.util.List<?>) dto.get("points");
        for (var p : points) {
            org.assertj.core.api.Assertions.assertThat(((java.util.Map<?, ?>) p).get("breakdown")).isNull();
        }
    }

    @Test
    void getTimeline_returns_200_even_when_no_forecast_history() throws Exception {
        // Тест проверяет что endpoint не падает при дефиците истории.
        // НЕ утверждаем fanEnabled==false, потому что контейнер shared между тестами:
        // если Test 5/6 уже создали факты в forecast-категориях, fanEnabled может быть true.
        // Цель: проверить отзывчивость endpoint'а на любом состоянии БД.
        mockMvc.perform(get("/api/v1/strategy/timeline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fanEnabled").exists())
                .andExpect(jsonPath("$.points").isArray());
    }

    @Test
    void getTimeline_includes_recurring_planned_in_balanceConfirmed() throws Exception {
        // Шаг 1: создать recurring rule через POST /api/v1/events с recurringConfig
        // (полный путь имитирует реальный flow — НЕ direct DB insert)
        String catId = getFirstCategoryId();
        String today = java.time.LocalDate.now().plusDays(1).toString();
        String body = """
            {
              "date": "%s",
              "categoryId": "%s",
              "type": "EXPENSE",
              "plannedAmount": 80000,
              "priority": "HIGH",
              "description": "Test recurring",
              "recurring": {
                "frequency": "MONTHLY",
                "dayOfMonth": %d,
                "endDate": "%s"
              }
            }
            """.formatted(today, catId, java.time.LocalDate.now().plusDays(1).getDayOfMonth(),
                java.time.LocalDate.now().plusMonths(6).toString());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/events")
                        .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is2xxSuccessful());

        // Шаг 2: получить timeline и проверить, что в одной из будущих точек expense >= 80000
        String tlBody = mockMvc.perform(get("/api/v1/strategy/timeline"))
                .andReturn().getResponse().getContentAsString();
        var tlDto = objectMapper.readValue(tlBody, java.util.Map.class);
        java.util.List<?> points = (java.util.List<?>) tlDto.get("points");
        boolean foundRecurringExpense = points.stream()
                .filter(p -> "FUTURE".equals(((java.util.Map<?, ?>) p).get("phase")))
                .anyMatch(p -> {
                    Object exp = ((java.util.Map<?, ?>) p).get("expense");
                    return exp != null && Double.parseDouble(exp.toString()) >= 80000.0;
                });
        org.assertj.core.api.Assertions.assertThat(foundRecurringExpense).isTrue();
    }

    @Test
    void getTimeline_with_patched_fact_includes_in_current_breakdown() throws Exception {
        // 1. Создать событие на сегодня
        String catId = getFirstCategoryId();
        String today = java.time.LocalDate.now().toString();
        String body = """
            {
              "date": "%s", "categoryId": "%s",
              "type": "EXPENSE", "plannedAmount": 5000,
              "priority": "HIGH", "description": "Test fact"
            }
            """.formatted(today, catId);

        String created = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/events")
                        .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();

        String eventId = (String) objectMapper.readValue(created, java.util.Map.class).get("id");

        // 2. PATCH-fact (FinancialEventUpdateFactDto имеет только factAmount + опц. description)
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/events/" + eventId + "/fact")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"factAmount\": 4900, \"description\": \"Оплачено\" }"))
                .andExpect(status().is2xxSuccessful());

        // 3. Получить timeline, проверить что breakdown CURRENT содержит этот факт
        String tlBody = mockMvc.perform(get("/api/v1/strategy/timeline"))
                .andReturn().getResponse().getContentAsString();
        var tlDto = objectMapper.readValue(tlBody, java.util.Map.class);
        java.util.List<?> points = (java.util.List<?>) tlDto.get("points");
        var currentPoint = points.stream()
                .filter(p -> "CURRENT".equals(((java.util.Map<?, ?>) p).get("phase")))
                .findFirst().orElseThrow();
        var breakdown = (java.util.Map<?, ?>) ((java.util.Map<?, ?>) currentPoint).get("breakdown");
        java.util.List<?> expenseItems = (java.util.List<?>) breakdown.get("expenseItems");
        org.assertj.core.api.Assertions.assertThat(expenseItems).isNotEmpty();
    }

    private String getFirstCategoryId() throws Exception {
        // Хелпер: берёт любую существующую категорию из БД (миграция должна создавать default-категории)
        String body = mockMvc.perform(get("/api/v1/categories"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        java.util.List<?> cats = objectMapper.readValue(body, java.util.List.class);
        return (String) ((java.util.Map<?, ?>) cats.get(0)).get("id");
    }
```

- [ ] **Step 4: Run all IT**

```
rtk JAVA_HOME=... ./mvnw test -Dtest=StrategyTimelineControllerIT
```

Ожидание: все 6 PASS. Если Docker недоступен — отложить на CI.

- [ ] **Step 5: Commit**

```
git add backend/src/test/java/ru/selfin/backend/StrategyTimelineControllerIT.java
git commit -m "test(it): 6 IT scenarios for strategy timeline endpoint"
```

---

## Chunk 5: Frontend — типы, API, базовые компоненты

### Task 5.1: Frontend types

**Files:**
- Modify: `frontend/src/types/api.ts`

- [ ] **Step 1: Добавить типы**

Добавить в конец файла (после существующих):

```typescript
// ─── Strategy ─────────────────────────────────────────────────────────────────

export type StrategyPointPhase = 'PAST' | 'CURRENT' | 'FUTURE';

export interface BreakdownItemDto {
    category: string;
    amount: number;
    isRecurring: boolean;
    isPredicted: boolean;
}

export interface BreakdownDto {
    incomeItems: BreakdownItemDto[];
    expenseItems: BreakdownItemDto[];
}

export interface StrategyTimelinePointDto {
    yearMonth: string;          // YYYY-MM (Jackson serialises YearMonth as such)
    phase: StrategyPointPhase;
    balance: number;
    income: number;
    expense: number;
    nettoFlow: number;
    balanceConfirmed: number | null;
    balanceLow: number | null;
    balanceHigh: number | null;
    capital: number;
    assets: number;
    liabilities: number;
    breakdown: BreakdownDto | null;
}

export interface StrategyTimelineDto {
    firstActivityMonth: string;   // YYYY-MM
    currentMonth: string;
    horizonEnd: string;
    predictionWindowMonths: number;
    fanEnabled: boolean;
    points: StrategyTimelinePointDto[];
}
```

- [ ] **Step 2: Typecheck**

```
cd frontend && rtk npx tsc --noEmit
```

Ожидание: clean.

- [ ] **Step 3: Commit**

```
git add frontend/src/types/api.ts
git commit -m "feat(frontend): types for strategy timeline"
```

---

### Task 5.2: API helper

**Files:**
- Modify: `frontend/src/api/index.ts`

- [ ] **Step 1: Добавить функцию**

```typescript
import type { StrategyTimelineDto } from '../types/api';

export const fetchStrategyTimeline = (params?: {
    horizonMonths?: number;
    withBreakdown?: boolean;
}) => {
    const qs = new URLSearchParams();
    if (params?.horizonMonths !== undefined) qs.set('horizonMonths', String(params.horizonMonths));
    if (params?.withBreakdown !== undefined) qs.set('withBreakdown', String(params.withBreakdown));
    const query = qs.toString();
    return get<StrategyTimelineDto>(`/strategy/timeline${query ? '?' + query : ''}`);
};
```

- [ ] **Step 2: Typecheck + commit**

```
cd frontend && rtk npx tsc --noEmit
git add frontend/src/api/index.ts
git commit -m "feat(frontend-api): fetchStrategyTimeline"
```

---

### Task 5.3: `useStrategyTimeline` hook

**Files:**
- Create: `frontend/src/components/strategy/useStrategyTimeline.ts`

- [ ] **Step 1: Создать hook**

```typescript
import { useEffect, useState, useCallback } from 'react';
import { fetchStrategyTimeline } from '../../api';
import type { StrategyTimelineDto } from '../../types/api';

/**
 * Простой fetch-хук. React сам не вызывает повторный fetch на re-render,
 * пока deps не меняются — отдельный кэш не нужен. Refetch триггерится явно через
 * возвращаемый колбэк (например, после редактирования события в Budget).
 */
export function useStrategyTimeline(params?: {
    horizonMonths?: number;
    withBreakdown?: boolean;
}): {
    data: StrategyTimelineDto | null;
    isLoading: boolean;
    error: string | null;
    refetch: () => void;
} {
    const [data, setData] = useState<StrategyTimelineDto | null>(null);
    const [isLoading, setIsLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [tick, setTick] = useState(0);

    const refetch = useCallback(() => setTick(t => t + 1), []);

    useEffect(() => {
        let cancelled = false;
        setIsLoading(true);
        setError(null);
        fetchStrategyTimeline(params)
            .then(d => {
                if (cancelled) return;
                setData(d);
            })
            .catch(e => {
                if (cancelled) return;
                setError(e instanceof Error ? e.message : String(e));
            })
            .finally(() => {
                if (cancelled) return;
                setIsLoading(false);
            });
        return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [tick, params?.horizonMonths, params?.withBreakdown]);

    return { data, isLoading, error, refetch };
}
```

- [ ] **Step 2: Typecheck + commit**

```
cd frontend && rtk npx tsc --noEmit
git add frontend/src/components/strategy/useStrategyTimeline.ts
git commit -m "feat(frontend): useStrategyTimeline hook"
```

---

### Task 5.4: `strategyChartUtils`

**Files:**
- Create: `frontend/src/components/strategy/strategyChartUtils.ts`

- [ ] **Step 1: Создать utils**

```typescript
import type { StrategyTimelinePointDto } from '../../types/api';

/** "2026-03" → "мар 26" */
export function fmtYearMonthLabel(ym: string): string {
    const [year, month] = ym.split('-');
    const d = new Date(Number(year), Number(month) - 1, 1);
    const shortMonth = d.toLocaleDateString('ru-RU', { month: 'short' }).replace(/\./g, '');
    return `${shortMonth} ${String(year).slice(2)}`;
}

/** Месячное имя + год для tooltip ("Сентябрь 2026") */
export function fmtYearMonthFull(ym: string): string {
    const [year, month] = ym.split('-');
    const d = new Date(Number(year), Number(month) - 1, 1);
    const fullMonth = d.toLocaleDateString('ru-RU', { month: 'long' });
    return `${fullMonth.charAt(0).toUpperCase()}${fullMonth.slice(1)} ${year}`;
}

/** N месяцев между from и to (>=0). Для подписи "через N мес" в tooltip */
export function monthsBetween(fromYm: string, toYm: string): number {
    const [fy, fm] = fromYm.split('-').map(Number);
    const [ty, tm] = toYm.split('-').map(Number);
    return Math.max(0, (ty - fy) * 12 + (tm - fm));
}

/** Маппинг DTO в chart-friendly формат: добавляем балансReange для Recharts Area */
export interface ChartPoint extends StrategyTimelinePointDto {
    label: string;
    balanceRange: [number, number] | null;
}

export function toChartPoints(points: StrategyTimelinePointDto[]): ChartPoint[] {
    return points.map(p => ({
        ...p,
        label: fmtYearMonthLabel(p.yearMonth),
        balanceRange: (p.balanceLow !== null && p.balanceHigh !== null && p.balanceLow !== p.balanceHigh)
            ? [p.balanceLow, p.balanceHigh]
            : null,
    }));
}

/** Currency formatting */
export const fmtRub = (n: number) =>
    new Intl.NumberFormat('ru-RU', { style: 'currency', currency: 'RUB', minimumFractionDigits: 0 }).format(n);

/** Compact Y-axis ticks (350к, 5М) */
export const fmtCompact = (n: number) => {
    if (Math.abs(n) >= 1_000_000) return (n / 1_000_000).toFixed(1).replace(/\.0$/, '') + 'М';
    if (Math.abs(n) >= 1_000) return Math.round(n / 1000) + 'к';
    return String(n);
};
```

- [ ] **Step 2: Typecheck + commit**

```
cd frontend && rtk npx tsc --noEmit
git add frontend/src/components/strategy/strategyChartUtils.ts
git commit -m "feat(frontend): strategyChartUtils helpers"
```

---

### Task 5.5: `ChartLegendToggles` компонент

**Files:**
- Create: `frontend/src/components/strategy/ChartLegendToggles.tsx`

- [ ] **Step 1: Создать**

```tsx
interface Toggle {
    id: string;
    label: string;
    color: string;       // CSS color value для маркера
    shape?: 'dot' | 'bar' | 'dash';
    active: boolean;
    onToggle?: () => void;   // если не задан — toggle не работает (для "Always on" линий)
}

interface Props {
    title: string;
    toggles: Toggle[];
}

export default function ChartLegendToggles({ title, toggles }: Props) {
    return (
        <div className="flex flex-wrap items-center gap-2 mb-2">
            <span className="text-sm font-semibold mr-2">{title}</span>
            {toggles.map(t => (
                <button
                    key={t.id}
                    type="button"
                    onClick={t.onToggle}
                    disabled={!t.onToggle}
                    className="inline-flex items-center gap-1.5 text-xs px-2 py-1 rounded-full border transition-opacity"
                    style={{
                        background: t.active ? `${t.color}26` : 'transparent',
                        borderColor: t.active ? `${t.color}80` : 'var(--color-border)',
                        color: t.active ? 'inherit' : 'var(--color-text-muted)',
                        cursor: t.onToggle ? 'pointer' : 'default',
                    }}
                >
                    {t.shape === 'dash'
                        ? <span style={{ display: 'inline-block', width: 12, height: 1, background: t.color }} />
                        : t.shape === 'bar'
                        ? <span style={{ display: 'inline-block', width: 8, height: 8, background: t.color, borderRadius: 2 }} />
                        : <span style={{ display: 'inline-block', width: 8, height: 8, background: t.color, borderRadius: '50%' }} />}
                    {t.label}
                </button>
            ))}
        </div>
    );
}
```

- [ ] **Step 2: Typecheck + commit**

```
cd frontend && rtk npx tsc --noEmit
git add frontend/src/components/strategy/ChartLegendToggles.tsx
git commit -m "feat(frontend): ChartLegendToggles pill-button toggles"
```

---

### Task 5.6: `MonthTooltip` компонент

**Files:**
- Create: `frontend/src/components/strategy/MonthTooltip.tsx`

- [ ] **Step 1: Создать**

```tsx
import { useNavigate } from 'react-router-dom';
import type { StrategyTimelinePointDto } from '../../types/api';
import { fmtYearMonthFull, fmtRub, monthsBetween } from './strategyChartUtils';
import { Repeat } from 'lucide-react';

interface Props {
    active?: boolean;
    payload?: Array<{ payload: StrategyTimelinePointDto }>;
    currentMonth?: string;
}

export default function MonthTooltip({ active, payload, currentMonth }: Props) {
    const navigate = useNavigate();
    if (!active || !payload || payload.length === 0) return null;

    const p = payload[0].payload;
    const isFuture = p.phase === 'FUTURE';
    const isCurrent = p.phase === 'CURRENT';

    const monthLabel = fmtYearMonthFull(p.yearMonth);
    const subtitle = isFuture && currentMonth
        ? ` (через ${monthsBetween(currentMonth, p.yearMonth)} мес)`
        : isCurrent ? ' (сейчас)' : '';

    const goToBudget = () => navigate(`/budget?month=${p.yearMonth}`);

    return (
        <div
            className="rounded-lg p-3 max-w-xs text-xs"
            style={{
                background: 'var(--color-surface)',
                border: '1px solid var(--color-border)',
                boxShadow: '0 4px 16px rgba(0,0,0,0.4)',
            }}
        >
            <div className="text-[11px] uppercase tracking-wide mb-1.5" style={{ color: 'var(--color-text-muted)' }}>
                {monthLabel}{subtitle}
            </div>

            <div className="flex justify-between items-baseline mb-1">
                <span style={{ color: 'var(--color-text-muted)' }}>Баланс</span>
                <span className="font-semibold text-[14px]">{fmtRub(p.balance)}</span>
            </div>
            {isFuture && p.balanceLow !== null && p.balanceHigh !== null && p.balanceLow !== p.balanceHigh && (
                <div className="text-[10px] mb-2" style={{ color: 'var(--color-text-muted)' }}>
                    Диапазон: {fmtRub(p.balanceLow)} – {fmtRub(p.balanceHigh)}
                </div>
            )}

            {p.breakdown && (
                <>
                    {p.breakdown.incomeItems.length > 0 && (
                        <div className="rounded p-2 mb-1.5" style={{ background: 'rgba(34, 197, 94, 0.08)' }}>
                            <div className="text-[10px] uppercase mb-1" style={{ color: 'var(--color-text-muted)' }}>
                                Доход +{fmtRub(p.income)}
                            </div>
                            {p.breakdown.incomeItems.map(it => (
                                <div key={it.category} className="flex justify-between items-center">
                                    <span>{it.isRecurring && <Repeat size={10} className="inline mr-1" />}{it.category}</span>
                                    <span>+{fmtRub(it.amount)}</span>
                                </div>
                            ))}
                        </div>
                    )}
                    {p.breakdown.expenseItems.length > 0 && (
                        <div className="rounded p-2" style={{ background: 'rgba(239, 68, 68, 0.08)' }}>
                            <div className="text-[10px] uppercase mb-1" style={{ color: 'var(--color-text-muted)' }}>
                                Расход −{fmtRub(p.expense)}
                            </div>
                            {p.breakdown.expenseItems.map(it => (
                                <div key={it.category} className="flex justify-between items-center">
                                    <span>
                                        {it.isRecurring && <Repeat size={10} className="inline mr-1" />}
                                        {it.category}
                                        {it.isPredicted && <span style={{ color: 'var(--color-text-muted)' }}> (прогноз)</span>}
                                    </span>
                                    <span>{it.isPredicted ? '~' : ''}{fmtRub(it.amount)}</span>
                                </div>
                            ))}
                        </div>
                    )}
                </>
            )}

            <button
                type="button"
                onClick={goToBudget}
                className="mt-2 text-[11px] hover:underline"
                style={{ color: 'var(--color-primary)' }}
            >
                Открыть Budget этого месяца →
            </button>
        </div>
    );
}
```

- [ ] **Step 2: Typecheck + commit**

```
cd frontend && rtk npx tsc --noEmit
git add frontend/src/components/strategy/MonthTooltip.tsx
git commit -m "feat(frontend): MonthTooltip with category breakdown"
```

---

## Chunk 6: Frontend — графики, страница, навигация

### Task 6.1: `CashflowChart` (recharts ComposedChart)

**Files:**
- Create: `frontend/src/components/strategy/CashflowChart.tsx`

- [ ] **Step 1: Создать**

```tsx
import {
    ComposedChart, Line, Bar, Area, XAxis, YAxis, Tooltip, ReferenceLine, ResponsiveContainer, Cell,
} from 'recharts';
import type { StrategyTimelinePointDto } from '../../types/api';
import { toChartPoints, fmtCompact, fmtYearMonthLabel } from './strategyChartUtils';
import MonthTooltip from './MonthTooltip';

interface Props {
    points: StrategyTimelinePointDto[];
    currentMonth: string;
    showFan: boolean;
    showNettoBars: boolean;
    showConfirmed: boolean;
}

export default function CashflowChart({ points, currentMonth, showFan, showNettoBars, showConfirmed }: Props) {
    const data = toChartPoints(points);
    const currentLabel = fmtYearMonthLabel(currentMonth);   // должен совпадать с dataKey="label"

    return (
        <ResponsiveContainer width="100%" height={240}>
            <ComposedChart data={data} margin={{ top: 8, right: 12, left: 0, bottom: 0 }} syncId="strategyTimeline">
                <XAxis
                    dataKey="label"
                    tick={{ fontSize: 11 }}
                    tickLine={false}
                    axisLine={false}
                />
                <YAxis
                    tickFormatter={fmtCompact}
                    tick={{ fontSize: 11 }}
                    tickLine={false}
                    axisLine={false}
                    width={40}
                />
                {/* Render-prop форма: Recharts передаёт active/payload/label, мы добавляем currentMonth */}
                <Tooltip content={(props: any) => <MonthTooltip {...props} currentMonth={currentMonth} />} />
                <ReferenceLine x={currentLabel} stroke="#fbbf24" strokeDasharray="3 3" />

                {showFan && (
                    <Area
                        type="monotone"
                        dataKey="balanceRange"
                        stroke="none"
                        fill="rgba(108, 99, 255, 0.12)"
                        isAnimationActive={false}
                    />
                )}

                {showNettoBars && (
                    <Bar dataKey="nettoFlow" maxBarSize={20}>
                        {data.map((d, i) => (
                            <Cell key={i} fill={d.nettoFlow >= 0 ? 'rgba(34, 197, 94, 0.4)' : 'rgba(239, 68, 68, 0.4)'} />
                        ))}
                    </Bar>
                )}

                {showConfirmed && (
                    <Line
                        type="monotone"
                        dataKey="balanceConfirmed"
                        stroke="#9da9b8"
                        strokeWidth={1.5}
                        strokeDasharray="4 3"
                        dot={false}
                        isAnimationActive={false}
                    />
                )}

                <Line
                    type="monotone"
                    dataKey="balance"
                    stroke="#6c63ff"
                    strokeWidth={2}
                    dot={false}
                    isAnimationActive={false}
                />
            </ComposedChart>
        </ResponsiveContainer>
    );
}
```

> **Note:** `ReferenceLine x` принимает значение совпадающее с `dataKey="label"` (формат "май 26"). Используем `currentLabel` пред-вычисленный через `fmtYearMonthLabel(currentMonth)`.
>
> **Note про render-prop tooltip:** `<Tooltip content={(props) => <MonthTooltip ... />}>` использует функциональную форму чтобы передать `currentMonth` параллельно с recharts-инжектируемыми `active`, `payload`, `label`. Прямая форма `<Tooltip content={<MonthTooltip currentMonth={...} />}>` тоже работает (recharts мерджит свои props), но render-prop явнее.

- [ ] **Step 2: Typecheck + commit**

```
cd frontend && rtk npx tsc --noEmit
git add frontend/src/components/strategy/CashflowChart.tsx
git commit -m "feat(frontend): CashflowChart ComposedChart with fan, bars, lines"
```

---

### Task 6.2: `StrategyCapitalChart`

**Files:**
- Create: `frontend/src/components/strategy/StrategyCapitalChart.tsx`

- [ ] **Step 1: Создать**

```tsx
import { LineChart, Line, XAxis, YAxis, Tooltip, ReferenceLine, ResponsiveContainer } from 'recharts';
import type { StrategyTimelinePointDto } from '../../types/api';
import { toChartPoints, fmtCompact, fmtYearMonthLabel } from './strategyChartUtils';
import MonthTooltip from './MonthTooltip';

interface Props {
    points: StrategyTimelinePointDto[];
    currentMonth: string;
    showAssets: boolean;
    showLiabilities: boolean;
}

export default function StrategyCapitalChart({ points, currentMonth, showAssets, showLiabilities }: Props) {
    const data = toChartPoints(points);
    const currentLabel = fmtYearMonthLabel(currentMonth);

    return (
        <ResponsiveContainer width="100%" height={150}>
            <LineChart data={data} margin={{ top: 8, right: 12, left: 0, bottom: 0 }} syncId="strategyTimeline">
                <XAxis dataKey="label" tick={{ fontSize: 11 }} tickLine={false} axisLine={false} />
                <YAxis tickFormatter={fmtCompact} tick={{ fontSize: 11 }} tickLine={false} axisLine={false} width={40} />
                <Tooltip content={(props: any) => <MonthTooltip {...props} currentMonth={currentMonth} />} />
                <ReferenceLine x={currentLabel} stroke="#fbbf24" strokeDasharray="3 3" />

                <Line type="monotone" dataKey="capital" stroke="#22c55e" strokeWidth={2} dot={false} isAnimationActive={false} />
                {showAssets && (
                    <Line type="monotone" dataKey="assets" stroke="rgba(255,255,255,0.3)" strokeWidth={1} dot={false} isAnimationActive={false} />
                )}
                {showLiabilities && (
                    <Line type="monotone" dataKey="liabilities" stroke="rgba(255,255,255,0.3)" strokeWidth={1} strokeDasharray="2 2" dot={false} isAnimationActive={false} />
                )}
            </LineChart>
        </ResponsiveContainer>
    );
}
```

- [ ] **Step 2: Typecheck + commit**

```
cd frontend && rtk npx tsc --noEmit
git add frontend/src/components/strategy/StrategyCapitalChart.tsx
git commit -m "feat(frontend): StrategyCapitalChart with sync hover"
```

---

### Task 6.3: `CashflowChartCard` + `CapitalTrajectoryCard` (карточки-обёртки)

**Files:**
- Create: `frontend/src/components/strategy/CashflowChartCard.tsx`
- Create: `frontend/src/components/strategy/CapitalTrajectoryCard.tsx`

- [ ] **Step 1: Создать `CashflowChartCard`**

```tsx
import { useState } from 'react';
import type { StrategyTimelineDto } from '../../types/api';
import ChartLegendToggles from './ChartLegendToggles';
import CashflowChart from './CashflowChart';

interface Props {
    timeline: StrategyTimelineDto;
}

export default function CashflowChartCard({ timeline }: Props) {
    const [showFan, setShowFan] = useState(true);
    const [showNettoBars, setShowNettoBars] = useState(true);
    const [showConfirmed, setShowConfirmed] = useState(false);

    return (
        <div className="rounded-lg p-3.5"
             style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}>
            <ChartLegendToggles
                title="Денежный поток"
                toggles={[
                    { id: 'balance', label: 'Баланс', color: '#6c63ff', shape: 'dot', active: true },
                    { id: 'fan', label: 'Диапазон', color: '#6c63ff', shape: 'dot',
                      active: showFan && timeline.fanEnabled,
                      onToggle: timeline.fanEnabled ? () => setShowFan(v => !v) : undefined },
                    { id: 'netto', label: 'Нетто-потоки', color: '#22c55e', shape: 'bar',
                      active: showNettoBars, onToggle: () => setShowNettoBars(v => !v) },
                    { id: 'confirmed', label: 'Только подтверждённое', color: '#9da9b8', shape: 'dash',
                      active: showConfirmed, onToggle: () => setShowConfirmed(v => !v) },
                ]}
            />
            {!timeline.fanEnabled && (
                <div className="text-[11px] mb-2 px-2 py-1 rounded inline-block"
                     style={{ background: 'rgba(251, 191, 36, 0.1)', color: 'var(--color-text-muted)' }}>
                    Прогноз пока недоступен — нужно ≥3 месяца истории по 3+ категориям
                </div>
            )}
            <CashflowChart
                points={timeline.points}
                currentMonth={timeline.currentMonth}
                showFan={showFan && timeline.fanEnabled}
                showNettoBars={showNettoBars}
                showConfirmed={showConfirmed}
            />
        </div>
    );
}
```

- [ ] **Step 2: Создать `CapitalTrajectoryCard`**

```tsx
import { useState } from 'react';
import type { StrategyTimelineDto } from '../../types/api';
import ChartLegendToggles from './ChartLegendToggles';
import StrategyCapitalChart from './StrategyCapitalChart';

interface Props {
    timeline: StrategyTimelineDto;
}

export default function CapitalTrajectoryCard({ timeline }: Props) {
    const [showAssets, setShowAssets] = useState(false);
    const [showLiabilities, setShowLiabilities] = useState(false);

    return (
        <div className="rounded-lg p-3.5"
             style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}>
            <ChartLegendToggles
                title="Капитал"
                toggles={[
                    { id: 'capital', label: 'Чистый', color: '#22c55e', shape: 'dot', active: true },
                    { id: 'assets', label: 'Активы', color: 'rgba(255,255,255,0.5)', shape: 'dot',
                      active: showAssets, onToggle: () => setShowAssets(v => !v) },
                    { id: 'liabilities', label: 'Обязательства', color: 'rgba(255,255,255,0.5)', shape: 'dash',
                      active: showLiabilities, onToggle: () => setShowLiabilities(v => !v) },
                ]}
            />
            <StrategyCapitalChart
                points={timeline.points}
                currentMonth={timeline.currentMonth}
                showAssets={showAssets}
                showLiabilities={showLiabilities}
            />
        </div>
    );
}
```

- [ ] **Step 3: Typecheck + commit**

```
cd frontend && rtk npx tsc --noEmit
git add frontend/src/components/strategy/CashflowChartCard.tsx frontend/src/components/strategy/CapitalTrajectoryCard.tsx
git commit -m "feat(frontend): CashflowChartCard and CapitalTrajectoryCard wrappers"
```

---

### Task 6.4: Страница `/strategy`

**Files:**
- Create: `frontend/src/pages/Strategy.tsx`

- [ ] **Step 1: Создать**

```tsx
import { useStrategyTimeline } from '../components/strategy/useStrategyTimeline';
import CashflowChartCard from '../components/strategy/CashflowChartCard';
import CapitalTrajectoryCard from '../components/strategy/CapitalTrajectoryCard';
import { Button } from '../components/ui/button';
import { fmtYearMonthFull } from '../components/strategy/strategyChartUtils';

export default function Strategy() {
    const { data: timeline, isLoading, error, refetch } = useStrategyTimeline();

    return (
        <div className="px-3 pt-3 pb-6 space-y-3">
            <div>
                <h1 className="text-xl font-semibold">Стратегия</h1>
                {timeline && (
                    <p className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
                        Финансовая траектория с {fmtYearMonthFull(timeline.firstActivityMonth).toLowerCase()} на 3 года вперёд
                    </p>
                )}
            </div>

            {isLoading && (
                <>
                    <div className="rounded-lg h-[280px] animate-pulse"
                         style={{ background: 'var(--color-surface)' }} />
                    <div className="rounded-lg h-[180px] animate-pulse"
                         style={{ background: 'var(--color-surface)' }} />
                </>
            )}

            {error && (
                <div className="rounded-lg p-4 text-center"
                     style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}>
                    <p className="text-sm mb-3">Не удалось загрузить стратегию</p>
                    <p className="text-xs mb-3" style={{ color: 'var(--color-text-muted)' }}>{error}</p>
                    <Button onClick={refetch} size="sm">Повторить</Button>
                </div>
            )}

            {timeline && !isLoading && !error && timeline.points.length === 0 && (
                <div className="rounded-lg p-6 text-center"
                     style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}>
                    <p className="text-sm">Здесь появится финансовая траектория, когда вы начнёте записывать события и снимки баланса.</p>
                </div>
            )}

            {timeline && !isLoading && !error && timeline.points.length > 0 && (
                <>
                    <CashflowChartCard timeline={timeline} />
                    <CapitalTrajectoryCard timeline={timeline} />
                </>
            )}
        </div>
    );
}
```

- [ ] **Step 2: Typecheck + commit**

```
cd frontend && rtk npx tsc --noEmit
git add frontend/src/pages/Strategy.tsx
git commit -m "feat(frontend): Strategy page with loading/error/empty states"
```

---

### Task 6.5: Навигация — пункт «Стратегия»

**Files:**
- Modify: `frontend/src/components/BottomNav.tsx`
- Modify: `frontend/src/App.tsx`

- [ ] **Step 1: Открыть `BottomNav.tsx` и добавить пункт**

Найти существующий массив `navItems` (или похожий). Добавить элемент:

```tsx
{ to: '/strategy', label: 'Стратегия', icon: TrendingUp },
```

Импорт `TrendingUp` из `lucide-react`. Расположение — между Capital и Settings (или в соответствии с существующим порядком пунктов).

- [ ] **Step 2: Открыть `App.tsx` и добавить маршрут**

Найти существующие `<Route>` элементы. Добавить:

```tsx
<Route path="/strategy" element={<Strategy />} />
```

Импорт `Strategy` из `./pages/Strategy`.

- [ ] **Step 3: Typecheck + smoke**

```
cd frontend && rtk npx tsc --noEmit
cd frontend && rtk npm run build
```

Ожидание: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```
git add frontend/src/components/BottomNav.tsx frontend/src/App.tsx
git commit -m "feat(frontend): add Strategy to bottom nav and routes"
```

---

### Task 6.6: Полный backend test suite + frontend build + manual smoke matrix

**Files:** —

- [ ] **Step 1: Backend full unit tests**

```
rtk JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -Dtest='!*IT'
```

Ожидание: все PASS (1 pre-existing `BackendApplicationTests.contextLoads` error из-за Docker — known).

- [ ] **Step 2: Frontend build**

```
cd frontend && rtk npm run build
```

Ожидание: BUILD SUCCESS.

- [ ] **Step 3: Manual smoke matrix** (выполнить руками после поднятия dev-сервера `npm run dev`)

- [ ] Open `/strategy` на новом/пустом аккаунте — видна empty state карточка с подсказкой
- [ ] Open `/strategy` с историей — header показывает первый месяц, видны две карточки
- [ ] Hover на месяц в прошлом — tooltip показывает фактические income/expense items
- [ ] Hover на месяц в будущем — tooltip с recurring (↻) и predicted (пометка) items
- [ ] Toggle Fan chart off → конус исчезает
- [ ] Toggle «Только подтверждённое» on → появляется пунктирная серая линия
- [ ] Toggle Активы on на капитале → появляется вторая линия
- [ ] Hover на cashflow → курсор синхронно подсвечивается на капитальном
- [ ] Кнопка «Открыть Budget» в tooltip — переход в Budget на нужный месяц
- [ ] Resize окна — графики корректно перерисовываются
- [ ] Mobile-ширина (DevTools) — нет горизонтального скролла, графики читаемы
- [ ] При отсутствии forecast-категорий — видна жёлтая подсказка про «прогноз пока недоступен»
- [ ] Маркер «Сегодня» — жёлтая пунктирная вертикаль на обоих графиках в одной X-координате

Если какой-то пункт fails — debug через `@superpowers:systematic-debugging` и зафиксировать новым коммитом.

- [ ] **Step 4: Update MEMORY.md**

В файле `C:\Users\Kirill\.claude\projects\C--Users-Kirill-IdeaProjects-selfin\memory\MEMORY.md`:
- В разделе `## What's done (blockers fixed)` добавить новый пункт:
  ```
  7. Strategic graph redesign (PR 2): новая страница /strategy с двумя графиками — cashflow (кумулятивный баланс + столбики нетто-потока + fan chart P25–P75 с √t расширением) и капитал. Новый StrategyTimelineService аггрегирует CapitalService + PredictionService + репозитории в один endpoint GET /api/v1/strategy/timeline. PredictionService расширен методом getStatsForCategory. liquidAt сделан публичным. Tooltip с разбивкой по категориям. Toggles слоёв. Sync hover между графиками. Branch: feature/strategy-graph-redesign.
  ```
- Roadmap: пометить PR 2 как done (если он там был как «strategy»). Если в Roadmap другие PRы — оставить нумерацию.

(MEMORY.md не в проектном git — просто сохранить.)

- [ ] **Step 5: Final commit + branch hygiene**

Проверить чистоту:

```
rtk git status
```

Ожидание: nothing to commit.

Просмотреть лог:

```
rtk git log --oneline -40
```

Если коммиты сложены чисто — дальше hand-off через `@superpowers:finishing-a-development-branch`.

---

## Out of scope (per spec §7 — НЕ трогать в этом PR)

- UI хотелок с зонами «зелёная/жёлтая/красная» и ограничениями капитала — отдельный PR.
- Сезонность в прогнозе (год-к-году, день недели). Сейчас прогноз — плоская медиана.
- Прогноз дохода. Сейчас prediction только для расходов.
- Persistence toggles слоёв в localStorage. Дефолты при каждом открытии.
- Удаление существующего графика на `/funds` (`SavingsStrategySection`). Остаётся как есть.
- Mobile-специфичный layout. Используем тот же дизайн с вертикальным скроллом.
- Авто-обновление при изменении событий в Budget (нет real-time refetch). Хук имеет `refetch`, но автотриггера нет.
- Drill-down — клик на точку → детальная страница месяца.
- Cross-page sync hover между `/strategy` и `/capital`.
- Цели и контрольные точки на графике.
