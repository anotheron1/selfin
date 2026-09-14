# Прогноз текущего месяца: норма вместо дневного темпа — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** прогноз текущего месяца считается по медиане прошлых месяцев, а не по дневному темпу; кармашек показывает два числа — без прогноза и с ним.

**Architecture:** `PredictionService` получает одно определение медианы (окно наблюдения, пустые месяцы — нули) и отдаёт вклад по формуле `max(0, медиана − факт − непогашенные планы)`. `PocketEngine` ведёт две кумуляты: главное число считается без прогноза, прогнозное — оговоркой. Из тех же двух минимумов фронт выбирает один из двух родов предупреждения о разрыве.

**Tech Stack:** Java 21 / Spring Boot, JPA, Flyway, JUnit 5 + AssertJ + Mockito, Testcontainers (failsafe); React + TypeScript + vitest.

**Spec:** `docs/superpowers/specs/2026-09-14-current-month-forecast-design.md`

## Global Constraints

* **Сборка:** `JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test` — юниты; `./mvnw verify` — плюс интеграционные на Testcontainers. `mvnw test` интеграционные НЕ запускает.
* **Перед каждым прогоном после правки сигнатур:** `rm -rf backend/target/test-classes`. `PocketResultDto` — record, он получает новые поля; Maven пропускает перекомпиляцию тестов, если их исходники не менялись, и отвечает `BUILD SUCCESS` на сломанном коде.
* **Время только через `Clock`** (ANO-39). Прямой вызов `LocalDate.now()` / `YearMonth.now()` в production-коде уронит тест-сторож.
* **Каждый тест проверяется мутацией.** После того как тест позеленел: внести правку, ломающую ровно то, что тест заявляет, убедиться, что тест краснеет, откатить правку. Зелёный без мутации проверкой не считается.
* **Правила продукта:** `docs/superpowers/specs/2026-09-01-product-rules.md`. Тексты на экране — без упрёков (правило 12), без обещания точности (правило 3), словарём пользователя (правило 13).
* **Кириллица в `curl -d` на стенде ломает JSON.** В проверочных скриптах — латиница.
* **Пороговые константы после Задачи 2 живут в одном месте** — `PredictionService.MIN_HISTORY_MONTHS` и `PredictionService.HISTORY_WINDOW_MONTHS`. Новых копий не заводить.

---

### Task 1: Окно наблюдения и медиана с нулями

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/repository/FinancialEventRepository.java`
- Modify: `backend/src/main/java/ru/selfin/backend/service/PredictionService.java:112-146`
- Test: `backend/src/test/java/ru/selfin/backend/service/PredictionServiceStatsTest.java`

**Interfaces:**
- Consumes: `CategoryMonthStats(UUID categoryId, int monthsOfHistory, BigDecimal median, BigDecimal p25, BigDecimal p75)` — существует, не меняется.
- Produces: `FinancialEventRepository.findFirstFactDate(): LocalDate` (null, если фактов нет); `PredictionService.getStatsForCategory(Category, int)` с новой семантикой `monthsOfHistory` — число месяцев НАБЛЮДЕНИЯ, а не месяцев с тратой.

- [ ] **Step 1: Написать падающий тест на подбивку нулями**

В `PredictionServiceStatsTest` добавить фиксированные часы. Заменить в `setUp()` строку создания сервиса и добавить поле:

```java
    /** Фиксируем «сегодня» = 14 сентября 2026: окно наблюдения зависит от календаря. */
    private static final Clock FIXED = Clock.fixed(
            LocalDate.of(2026, 9, 14).atStartOfDay(ZoneId.systemDefault()).toInstant(),
            ZoneId.systemDefault());

    @BeforeEach
    void setUp() {
        eventRepo = mock(FinancialEventRepository.class);
        service = new PredictionService(eventRepo, FIXED);
        cat = Category.builder().id(UUID.randomUUID()).name("Одежда").build();
    }
```

Новый тест:

```java
    @Test
    @DisplayName("ANO-80: месяц наблюдения без траты в категории считается нулём")
    void getStatsForCategory_monthsWithoutSpending_countAsZero() {
        // Наблюдение с марта (первый факт 9 марта) → окно апрель..август, шесть месяцев
        // не набирается: полных пять. Покупки одежды в трёх из них.
        when(eventRepo.findFirstFactDate()).thenReturn(LocalDate.of(2026, 3, 9));
        when(eventRepo.findFactsByDateRange(any(), any())).thenReturn(List.of(
                factEvent(LocalDate.of(2026, 5, 10), "9000"),
                factEvent(LocalDate.of(2026, 7, 10), "12000"),
                factEvent(LocalDate.of(2026, 8, 10), "15000")));

        CategoryMonthStats stats = service.getStatsForCategory(cat, 6);

        assertThat(stats.monthsOfHistory())
                .as("месяцев наблюдения пять: апрель..август, март отброшен как неполный")
                .isEqualTo(5);
        assertThat(stats.median())
                .as("ряд 0, 0, 9000, 12000, 15000 — медиана девять тысяч, а не двенадцать")
                .isEqualByComparingTo("9000");
    }

    @Test
    @DisplayName("ANO-80: месяц первого факта отброшен как неполный")
    void getStatsForCategory_monthOfFirstFact_isDropped() {
        when(eventRepo.findFirstFactDate()).thenReturn(LocalDate.of(2026, 7, 20));
        when(eventRepo.findFactsByDateRange(any(), any())).thenReturn(List.of(
                factEvent(LocalDate.of(2026, 7, 21), "50000"),   // в отброшенном месяце
                factEvent(LocalDate.of(2026, 8, 10), "10000")));

        CategoryMonthStats stats = service.getStatsForCategory(cat, 6);

        assertThat(stats.monthsOfHistory())
                .as("наблюдение начинается с августа: июль неполон")
                .isEqualTo(1);
        assertThat(stats.median())
                .as("июльские 50 000 в ряд не входят")
                .isEqualByComparingTo("10000");
    }

    @Test
    @DisplayName("ANO-80: фактов нет вовсе — ноль месяцев наблюдения")
    void getStatsForCategory_noFactsAtAll_returnsZeroMonths() {
        when(eventRepo.findFirstFactDate()).thenReturn(null);

        CategoryMonthStats stats = service.getStatsForCategory(cat, 6);

        assertThat(stats.monthsOfHistory()).isZero();
        assertThat(stats.median()).isEqualByComparingTo("0");
    }
```

Импорты к добавлению: `java.time.ZoneId`, `org.junit.jupiter.api.DisplayName`.

- [ ] **Step 2: Прогнать — должны падать**

```bash
rm -rf backend/target/test-classes
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw -q test -Dtest=PredictionServiceStatsTest
```

Ожидание: FAIL — метода `findFirstFactDate` не существует, код не компилируется.

- [ ] **Step 3: Добавить запрос первого факта**

В `FinancialEventRepository`:

```java
    /**
     * Дата самого раннего факта-расхода по ВСЕМ категориям; null, если фактов нет.
     *
     * <p>ANO-80: задаёт начало окна наблюдения. Окно общее, а не покатегорийное — оно
     * отвечает на вопрос «давно ли человек ведёт учёт», а не «давно ли он покупает одежду».
     */
    @Query("""
        SELECT MIN(e.date) FROM FinancialEvent e
        WHERE e.deleted = false
          AND e.eventKind = ru.selfin.backend.model.EventKind.FACT
          AND e.type = ru.selfin.backend.model.enums.EventType.EXPENSE
        """)
    LocalDate findFirstFactDate();
```

- [ ] **Step 4: Переписать `getStatsForCategory`**

Заменить тело метода целиком (`PredictionService.java:112-146`):

```java
    public CategoryMonthStats getStatsForCategory(Category cat, int historyWindowMonths) {
        LocalDate today = LocalDate.now(clock);
        YearMonth lastFull = YearMonth.from(today).minusMonths(1);

        LocalDate firstFact = eventRepository.findFirstFactDate();
        if (firstFact == null) {
            return new CategoryMonthStats(cat.getId(), 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }

        // Месяц первого факта отбрасывается ВСЕГДА, даже если факт пришёлся на первое число:
        // запись первого числа так же может быть занесена задним числом, как и любая другая.
        // Правило не угадывает, вёлся ли учёт с начала месяца, — и потому проверяется тестом.
        YearMonth firstObserved = YearMonth.from(firstFact).plusMonths(1);
        YearMonth windowStart = lastFull.minusMonths(historyWindowMonths - 1L);
        if (windowStart.isBefore(firstObserved)) windowStart = firstObserved;
        if (windowStart.isAfter(lastFull)) {
            return new CategoryMonthStats(cat.getId(), 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }

        List<YearMonth> observedMonths = new ArrayList<>();
        for (YearMonth m = windowStart; !m.isAfter(lastFull); m = m.plusMonths(1)) {
            observedMonths.add(m);
        }

        Map<YearMonth, BigDecimal> monthlyTotals = eventRepository
                .findFactsByDateRange(windowStart.atDay(1), lastFull.atEndOfMonth()).stream()
                .filter(e -> !e.isDeleted())
                .filter(e -> e.getEventKind() == EventKind.FACT)
                .filter(e -> e.getCategory() != null && cat.getId().equals(e.getCategory().getId()))
                .collect(Collectors.groupingBy(
                        e -> YearMonth.from(e.getDate()),
                        Collectors.reducing(BigDecimal.ZERO,
                                e -> e.getFactAmount() != null ? e.getFactAmount() : BigDecimal.ZERO,
                                BigDecimal::add)));

        // Месяц наблюдения без траты в категории — полноценный ноль в ряду, а не пропуск.
        // Без этого «Одежда», покупаемая раз в квартал, закладывалась бы каждый месяц целиком.
        List<BigDecimal> sorted = observedMonths.stream()
                .map(m -> monthlyTotals.getOrDefault(m, BigDecimal.ZERO))
                .sorted()
                .toList();

        return new CategoryMonthStats(
                cat.getId(),
                observedMonths.size(),
                percentile(sorted, 0.50),
                percentile(sorted, 0.25),
                percentile(sorted, 0.75));
    }
```

Обновить javadoc метода: выкинуть фразу «Если `monthsOfHistory < 3`, caller не должен учитывать категорию» — она остаётся верной, но «месяцев истории» заменить на «месяцев наблюдения».

- [ ] **Step 5: Прогнать — должны пройти**

```bash
rm -rf backend/target/test-classes
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw -q test -Dtest=PredictionServiceStatsTest
```

Ожидание: PASS. Старые тесты в этом классе могли опираться на `LocalDate.now()` и «месяцы с тратой» — если они падают, это находка, а не повод поправить ожидание: разобрать каждый и переписать под фиксированные часы.

- [ ] **Step 6: Мутации**

1. В `getStatsForCategory` заменить `.map(m -> monthlyTotals.getOrDefault(m, BigDecimal.ZERO))` на итерацию по `monthlyTotals.values()` → `getStatsForCategory_monthsWithoutSpending_countAsZero` обязан покраснеть (медиана станет 12 000).
2. Заменить `YearMonth.from(firstFact).plusMonths(1)` на `YearMonth.from(firstFact)` → `getStatsForCategory_monthOfFirstFact_isDropped` обязан покраснеть.
3. Убрать проверку `firstFact == null` → `getStatsForCategory_noFactsAtAll_returnsZeroMonths` обязан упасть с NPE.

Откатить все три.

- [ ] **Step 7: Коммит**

```bash
git add backend/src/main/java/ru/selfin/backend/repository/FinancialEventRepository.java backend/src/main/java/ru/selfin/backend/service/PredictionService.java backend/src/test/java/ru/selfin/backend/service/PredictionServiceStatsTest.java
git commit -m "feat(ano-80): медиана считает месяцы наблюдения, пустые — нулями"
```

---

### Task 2: Один порог и одно окно на всех потребителей

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/service/PredictionService.java`
- Modify: `backend/src/main/java/ru/selfin/backend/service/PocketInputAssembler.java:265-267,294`
- Modify: `backend/src/main/java/ru/selfin/backend/service/BaselineTimelineBuilder.java:59-60,93,108,205`
- Test: `backend/src/test/java/ru/selfin/backend/service/ForecastThresholdSingleSourceTest.java` (создать)

**Interfaces:**
- Produces: `PredictionService.MIN_HISTORY_MONTHS = 3`, `PredictionService.HISTORY_WINDOW_MONTHS = 6` — единственные объявления в production-коде.

- [ ] **Step 1: Написать падающий тест-сторож**

По образцу сторожа прямых вызовов `now()` из ANO-39: тест читает исходники и считает объявления.

```java
package ru.selfin.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ANO-80: порог «сколько месяцев наблюдения нужно, чтобы верить медиане» и окно истории
 * обязаны существовать в ОДНОМ экземпляре каждый.
 *
 * <p>До этой задачи копий было по две: MIN_HISTORY_MONTHS в сборке входа кармашка и
 * MIN_HISTORY_FOR_FAN в конусе fan chart; PREDICTION_WINDOW_MONTHS и HISTORY_WINDOW_MONTHS
 * — та же пара для окна. Прогноз текущего месяца стал бы третьей копией каждой.
 *
 * <p>Разъехавшись, они дадут экран, где прогноз в кармашке есть, а конуса на том же экране
 * нет. Ровно та форма дефекта, из-за которой ANO-82 чинилась в трёх местах подряд.
 */
class ForecastThresholdSingleSourceTest {

    private static final Path MAIN = Path.of("src/main/java");

    @Test
    @DisplayName("ANO-80: порог месяцев наблюдения объявлен ровно один раз")
    void minHistoryMonths_declaredOnce() throws IOException {
        assertThat(declarationsOf("MIN_HISTORY|MONTHS_FOR_FAN"))
                .as("порог обязан жить в PredictionService и больше нигде")
                .hasSize(1);
    }

    @Test
    @DisplayName("ANO-80: окно истории объявлено ровно один раз")
    void historyWindow_declaredOnce() throws IOException {
        assertThat(declarationsOf("HISTORY_WINDOW_MONTHS|PREDICTION_WINDOW_MONTHS"))
                .as("окно обязано жить в PredictionService и больше нигде")
                .hasSize(1);
    }

    private List<String> declarationsOf(String names) throws IOException {
        Pattern decl = Pattern.compile("(?:static\\s+final\\s+int\\s+)(" + names + ")\\w*\\s*=");
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files
                    .filter(p -> p.toString().endsWith(".java"))
                    .flatMap(p -> {
                        try {
                            return decl.matcher(Files.readString(p)).results()
                                    .map(r -> p.getFileName() + ": " + r.group());
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .toList();
        }
    }
}
```

- [ ] **Step 2: Прогнать — обязан упасть**

```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw -q test -Dtest=ForecastThresholdSingleSourceTest
```

Ожидание: FAIL — по два объявления каждой величины.

- [ ] **Step 3: Свести константы**

В `PredictionService` добавить рядом с полями:

```java
    /**
     * Сколько полных месяцев наблюдения нужно, чтобы верить медиане категории.
     * Один порог на трёх потребителей: прогноз текущего месяца, прогноз будущих месяцев
     * (PocketInputAssembler) и конус fan chart (BaselineTimelineBuilder).
     */
    public static final int MIN_HISTORY_MONTHS = 3;

    /** Окно истории для медианы — те же шесть месяцев у всех троих. */
    public static final int HISTORY_WINDOW_MONTHS = 6;
```

В `PocketInputAssembler` удалить объявления `MIN_HISTORY_MONTHS` и `HISTORY_WINDOW_MONTHS` (строки 265-267), в использовании на 293-294 подставить `PredictionService.MIN_HISTORY_MONTHS` и `PredictionService.HISTORY_WINDOW_MONTHS`.

В `BaselineTimelineBuilder` удалить `MIN_HISTORY_FOR_FAN` и `PREDICTION_WINDOW_MONTHS` (строки 59-60), в использованиях на 75, 93, 108, 205 подставить константы из `PredictionService`.

- [ ] **Step 4: Прогнать сторож и весь бэкенд**

```bash
rm -rf backend/target/test-classes
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw -q test
```

Ожидание: PASS, включая `ForecastThresholdSingleSourceTest`.

- [ ] **Step 5: Мутация**

Вернуть в `BaselineTimelineBuilder` строку `static final int MIN_HISTORY_FOR_FAN = 3;` → `minHistoryMonths_declaredOnce` обязан покраснеть. Откатить.

- [ ] **Step 6: Коммит**

```bash
git add backend/src/main/java/ru/selfin/backend/service/ backend/src/test/java/ru/selfin/backend/service/ForecastThresholdSingleSourceTest.java
git commit -m "refactor(ano-80): порог и окно истории — по одному объявлению, сторож их удерживает"
```

---

### Task 3: Норма вместо дневного темпа

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/service/PredictionService.java:38-98,169-235`
- Modify: `backend/src/main/java/ru/selfin/backend/service/PocketInputAssembler.java:230-231`
- Test: `backend/src/test/java/ru/selfin/backend/service/PredictionServiceTest.java`

**Interfaces:**
- Consumes: `PredictionService.getStatsForCategory` из Задачи 1; `CategoryRepository.findAllByForecastEnabledTrueAndDeletedFalse(): List<Category>` — существует.
- Produces: `PredictionService.forecastFromEvents(List<FinancialEvent> monthEvents, LocalDate today): MonthlyForecastDto` — сигнатура прежняя, семантика новая: обход идёт по ВКЛЮЧЁННЫМ КАТЕГОРИЯМ, а не по категориям, у которых есть события месяца.

- [ ] **Step 1: Написать падающие тесты**

В `PredictionServiceTest` заменить `setUp()` на версию с `CategoryRepository` и фиксированными часами, затем добавить:

```java
    @Test
    @DisplayName("ANO-80: планов нет — вклад равен норме минус потраченное")
    void forecastFromEvents_noPlans_contributesMedianMinusFact() {
        enabled(cat);                       // «Продукты», forecastEnabled = true
        medianOf(cat, "50000", 5);          // пять месяцев наблюдения, медиана 50 000
        List<FinancialEvent> month = List.of(fact(LocalDate.of(2026, 9, 5), "23444"));

        MonthlyForecastDto result = service.forecastFromEvents(month, LocalDate.of(2026, 9, 5));

        assertThat(result.netPredictionDelta())
                .as("дневной темп дал бы 117 220; норма даёт разницу до обычного месяца")
                .isEqualByComparingTo("26556");
    }

    @Test
    @DisplayName("ANO-80: план не отключает категорию — отдаётся разница между нормой и планом")
    void forecastFromEvents_withPlan_contributesMedianMinusPlan() {
        enabled(cat);
        medianOf(cat, "50000", 5);
        List<FinancialEvent> month = List.of(plan(LocalDate.of(2026, 9, 20), "40000"));

        MonthlyForecastDto result = service.forecastFromEvents(month, LocalDate.of(2026, 9, 14));

        assertThat(result.netPredictionDelta())
                .as("до ANO-80 категория с планом давала ноль, сколько бы сверх него ни тратилось")
                .isEqualByComparingTo("10000");
    }

    @Test
    @DisplayName("ANO-80: погашенный план не вычитается — его уже заменил факт")
    void forecastFromEvents_executedPlan_notSubtractedTwice() {
        enabled(cat);
        medianOf(cat, "50000", 5);
        List<FinancialEvent> month = List.of(
                executedPlan(LocalDate.of(2026, 9, 3), "4000"),
                fact(LocalDate.of(2026, 9, 3), "5000"));

        MonthlyForecastDto result = service.forecastFromEvents(month, LocalDate.of(2026, 9, 14));

        assertThat(result.netPredictionDelta())
                .as("вычесть и план, и заменивший его факт значит посчитать трату дважды")
                .isEqualByComparingTo("45000");
    }

    @Test
    @DisplayName("ANO-80: просроченный план вычитается наравне с будущим")
    void forecastFromEvents_overduePlan_subtracted() {
        enabled(cat);
        medianOf(cat, "50000", 5);
        List<FinancialEvent> month = List.of(plan(LocalDate.of(2026, 9, 2), "40000"));

        MonthlyForecastDto result = service.forecastFromEvents(month, LocalDate.of(2026, 9, 14));

        assertThat(result.netPredictionDelta())
                .as("просрочка удержана в траектории строкой брони — норма обязана её учесть")
                .isEqualByComparingTo("10000");
    }

    @Test
    @DisplayName("ANO-80: потрачено больше нормы — вклад ноль, а не отрицательное число")
    void forecastFromEvents_spentAboveMedian_contributesZero() {
        enabled(cat);
        medianOf(cat, "50000", 5);
        List<FinancialEvent> month = List.of(fact(LocalDate.of(2026, 9, 5), "70000"));

        assertThat(service.forecastFromEvents(month, LocalDate.of(2026, 9, 5)).netPredictionDelta())
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("ANO-80: наблюдений меньше трёх — прогноза нет вовсе")
    void forecastFromEvents_belowThreshold_noForecast() {
        enabled(cat);
        medianOf(cat, "50000", 2);          // два месяца наблюдения
        List<FinancialEvent> month = List.of(fact(LocalDate.of(2026, 9, 5), "23444"));

        assertThat(service.forecastFromEvents(month, LocalDate.of(2026, 9, 5)).netPredictionDelta())
                .as("это и есть симптом ANO-80: у нового пользователя оснований нет")
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("ANO-80: категория без событий месяца всё равно даёт норму")
    void forecastFromEvents_categoryWithoutMonthEvents_stillContributes() {
        enabled(cat);
        medianOf(cat, "15851", 5);

        assertThat(service.forecastFromEvents(List.of(), LocalDate.of(2026, 9, 14)).netPredictionDelta())
                .as("обход идёт по включённым категориям, а не по событиям месяца")
                .isEqualByComparingTo("15851");
    }

    @Test
    @DisplayName("ANO-80: линия спарклайна — планка нормы, а не кривая от номера дня")
    void forecastFromEvents_history_isFlatUntilFactCrossesMedian() {
        enabled(cat);
        medianOf(cat, "50000", 5);
        List<FinancialEvent> month = List.of(fact(LocalDate.of(2026, 9, 3), "60000"));

        List<DailyForecastPointDto> history = service
                .forecastFromEvents(month, LocalDate.of(2026, 9, 5))
                .categories().get(0).history();

        // usingComparatorForType обязателен: BigDecimal.equals различает 50000 и 50000.00,
        // а медиана приходит из percentile со своей шкалой. Сравнивать нужно значения.
        assertThat(history).extracting(DailyForecastPointDto::projectedTotal)
                .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .as("до траты — планка нормы; после — факт, который её перерос")
                .containsExactly(new BigDecimal("50000"), new BigDecimal("50000"),
                        new BigDecimal("60000"), new BigDecimal("60000"), new BigDecimal("60000"));
    }
```

Вспомогательные методы класса:

```java
    private void enabled(Category... cats) {
        when(categoryRepo.findAllByForecastEnabledTrueAndDeletedFalse()).thenReturn(List.of(cats));
    }

    /**
     * Подменяет статистику категории: медиана и число месяцев наблюдения.
     *
     * <p>Через spy, а не через факты в репозитории: здесь проверяется ФОРМУЛА вклада, и
     * подсовывать ей пять месяцев фактов значило бы заодно перепроверять медиану, у которой
     * свой класс тестов. Spy создаётся один раз в setUp — пересоздание обернуло бы обёртку.
     */
    private void medianOf(Category c, String median, int months) {
        doReturn(new CategoryMonthStats(c.getId(), months, new BigDecimal(median),
                new BigDecimal(median), new BigDecimal(median)))
                .when(service).getStatsForCategory(eq(c), anyInt());
    }

    private FinancialEvent fact(LocalDate date, String amount) {
        return FinancialEvent.builder().id(UUID.randomUUID()).category(cat)
                .type(EventType.EXPENSE).date(date).factAmount(new BigDecimal(amount))
                .eventKind(EventKind.FACT).status(EventStatus.EXECUTED).deleted(false).build();
    }

    private FinancialEvent plan(LocalDate date, String amount) {
        return FinancialEvent.builder().id(UUID.randomUUID()).category(cat)
                .type(EventType.EXPENSE).date(date).plannedAmount(new BigDecimal(amount))
                .eventKind(EventKind.PLAN).status(EventStatus.PLANNED).deleted(false).build();
    }

    private FinancialEvent executedPlan(LocalDate date, String amount) {
        return FinancialEvent.builder().id(UUID.randomUUID()).category(cat)
                .type(EventType.EXPENSE).date(date).plannedAmount(new BigDecimal(amount))
                .eventKind(EventKind.PLAN).status(EventStatus.EXECUTED).deleted(false).build();
    }
```

`setUp()` целиком:

```java
    private static final Clock FIXED = Clock.fixed(
            LocalDate.of(2026, 9, 14).atStartOfDay(ZoneId.systemDefault()).toInstant(),
            ZoneId.systemDefault());

    @BeforeEach
    void setUp() {
        eventRepo = mock(FinancialEventRepository.class);
        categoryRepo = mock(CategoryRepository.class);
        // spy — чтобы подменять getStatsForCategory в medianOf(); внутренние вызовы
        // forecastFromEvents идут через прокси и потому перехватываются.
        service = spy(new PredictionService(eventRepo, categoryRepo, FIXED));
        cat = Category.builder().id(UUID.randomUUID()).name("Продукты")
                .forecastEnabled(true).build();
    }
```

**Заодно поправить `PredictionServiceStatsTest`:** его `setUp()` собирает `PredictionService` двухаргументным конструктором и после этой задачи перестанет компилироваться. Добавить третьим аргументом `mock(CategoryRepository.class)` — на тесты медианы он не влияет.

- [ ] **Step 2: Прогнать — должны падать**

```bash
rm -rf backend/target/test-classes
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw -q test -Dtest=PredictionServiceTest
```

Ожидание: FAIL — конструктор `PredictionService` не принимает `CategoryRepository`.

- [ ] **Step 3: Переписать расчёт**

В `PredictionService` добавить зависимость и переписать два метода:

```java
    private final CategoryRepository categoryRepository;
```

```java
    /**
     * Прогноз по всем категориям с включённой галочкой.
     *
     * <p>ANO-80: обход идёт по КАТЕГОРИЯМ, а не по событиям месяца. До этой задачи
     * категория без единого факта в текущем месяце не попадала в расчёт вовсе — и прогноз
     * появлялся только после первой траты, раздувая её дневным темпом. Норма известна
     * заранее и не ждёт, пока человек что-нибудь купит.
     */
    public MonthlyForecastDto forecastFromEvents(List<FinancialEvent> monthEvents, LocalDate today) {
        List<CategoryForecastDto> forecasts = new ArrayList<>();
        BigDecimal netDelta = BigDecimal.ZERO;

        for (Category cat : categoryRepository.findAllByForecastEnabledTrueAndDeletedFalse()) {
            List<FinancialEvent> catEvents = monthEvents.stream()
                    .filter(e -> !e.isDeleted())
                    .filter(e -> e.getCategory() != null && cat.getId().equals(e.getCategory().getId()))
                    .toList();

            BigDecimal fact = sumFacts(catEvents);
            BigDecimal pendingPlans = sumPendingPlans(catEvents);
            BigDecimal median = medianIfTrusted(cat);

            // Норма — это ВСЯ обычная трата месяца, а план и факт — её части, уже стоящие
            // в пути денег: факт ушёл со счёта, план удержан траекторией (просроченный —
            // строкой брони). Сверх плана ожидается только разница.
            BigDecimal beyond = median.subtract(fact).subtract(pendingPlans).max(BigDecimal.ZERO);
            BigDecimal projection = median.max(fact.add(pendingPlans));

            forecasts.add(new CategoryForecastDto(cat.getName(), fact, sumAllPlans(catEvents),
                    projection, buildHistory(catEvents, median, pendingPlans, today)));
            netDelta = netDelta.add(beyond);
        }

        return new MonthlyForecastDto(forecasts, netDelta);
    }

    /** Медиана категории или ноль, если наблюдений меньше порога. */
    private BigDecimal medianIfTrusted(Category cat) {
        CategoryMonthStats stats = getStatsForCategory(cat, HISTORY_WINDOW_MONTHS);
        return stats.monthsOfHistory() >= MIN_HISTORY_MONTHS ? stats.median() : BigDecimal.ZERO;
    }

    /**
     * Непогашенные планы месяца: {@code PLAN} со статусом {@code PLANNED}.
     *
     * <p>Статус важен. План со статусом {@code EXECUTED} уже заменён фактом, и вычесть оба
     * значило бы посчитать одну трату дважды. На эталонном стенде в сентябре ровно один
     * такой план, и первый замер без этого фильтра дал по «Авто» 16 000 вместо 12 000.
     */
    private BigDecimal sumPendingPlans(List<FinancialEvent> events) {
        return events.stream()
                .filter(e -> e.getEventKind() == EventKind.PLAN)
                .filter(e -> e.getStatus() == EventStatus.PLANNED)
                .map(e -> e.getPlannedAmount() != null ? e.getPlannedAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
```

`buildHistory` переписать под норму:

```java
    /**
     * Линия спарклайна: факт по дням и планка обычного месяца.
     *
     * <p>ANO-80: раньше проекция пересчитывалась от номера дня и потому извивалась.
     * Теперь это горизонтальная планка, к которой ползёт факт; пересечение планки и
     * означает «в этом месяце выходит дороже обычного».
     */
    private List<DailyForecastPointDto> buildHistory(List<FinancialEvent> catEvents,
                                                     BigDecimal median,
                                                     BigDecimal pendingPlans,
                                                     LocalDate today) {
        List<DailyForecastPointDto> points = new ArrayList<>();
        LocalDate monthStart = today.withDayOfMonth(1);

        for (int d = 1; d <= today.getDayOfMonth(); d++) {
            LocalDate dayDate = monthStart.withDayOfMonth(d);
            BigDecimal factOnDay = catEvents.stream()
                    .filter(e -> e.getEventKind() == EventKind.FACT)
                    .filter(e -> e.getDate() != null && !e.getDate().isAfter(dayDate))
                    .map(e -> e.getFactAmount() != null ? e.getFactAmount() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            points.add(new DailyForecastPointDto(d, factOnDay,
                    median.max(factOnDay.add(pendingPlans))));
        }
        return points;
    }
```

Удалить `computeProjection` целиком — дневного темпа больше нет. Удалить публичный `forecast(String, List, LocalDate)`, если после правки на него нет ссылок; если есть — переписать вызывающих на `forecastFromEvents`.

Импорты: `ru.selfin.backend.model.enums.EventStatus`, `ru.selfin.backend.repository.CategoryRepository`.

- [ ] **Step 4: Прогнать**

```bash
rm -rf backend/target/test-classes
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw -q test
```

Ожидание: новые тесты PASS. `DashboardServiceTest` и `PocketInputAssemblerTest` могут покраснеть — у них появилась новая зависимость в конструкторе и изменилось поведение. Каждое падение разобрать: если тест доказывал поведение дневного темпа, он подлежит переписыванию под норму, а не удалению.

- [ ] **Step 5: Мутации**

1. Вернуть дневной темп: `beyond = fact.divide(BigDecimal.valueOf(today.getDayOfMonth()), 4, HALF_UP).multiply(...)` → `forecastFromEvents_noPlans_contributesMedianMinusFact` даёт 117 220 и краснеет.
2. Убрать `.subtract(pendingPlans)` → `forecastFromEvents_withPlan_contributesMedianMinusPlan` краснеет (50 000 вместо 10 000).
3. Убрать фильтр `getStatus() == EventStatus.PLANNED` → `forecastFromEvents_executedPlan_notSubtractedTwice` краснеет (41 000 вместо 45 000).
4. Убрать `.max(BigDecimal.ZERO)` → `forecastFromEvents_spentAboveMedian_contributesZero` краснеет (−20 000).
5. Убрать порог в `medianIfTrusted` → `forecastFromEvents_belowThreshold_noForecast` краснеет.

Откатить все пять.

- [ ] **Step 6: Коммит**

```bash
git add backend/src/main/java/ru/selfin/backend/service/PredictionService.java backend/src/test/java/ru/selfin/backend/service/
git commit -m "feat(ano-80): прогноз текущего месяца считает норму, а не дневной темп"
```

---

### Task 4: Две кумуляты в движке

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/dto/pocket/PocketResultDto.java`
- Modify: `backend/src/main/java/ru/selfin/backend/service/PocketEngine.java:99-205,228-300`
- Test: `backend/src/test/java/ru/selfin/backend/service/PocketEngineTest.java`

**Interfaces:**
- Produces: `PocketResultDto.pocketWithForecast: BigDecimal` (null, когда прогноза нет), `PocketResultDto.minPointWithForecast: MinPoint` (null там же), `PocketResultDto.TrajectoryPoint.balanceWithForecast: BigDecimal` (null там же).

- [ ] **Step 1: Написать падающие тесты**

```java
    @Test
    @DisplayName("ANO-80: кармашек равен минимуму БЕЗ прогноза, хотя прогноз есть")
    void pocket_ignoresForecast_whileSecondNumberCountsIt() {
        PocketResultDto r = PocketEngine.compute(base()
                .forecast(30_000, "Продукты")
                .horizon(LocalDate.of(2026, 3, 15))
                .build());

        assertThat(r.pocket())
                .as("главное число — только факты и планы")
                .isEqualByComparingTo("10000");
        assertThat(r.pocketWithForecast())
                .as("оговорка — то же число с обычными тратами")
                .isEqualByComparingTo("-20000");
    }

    @Test
    @DisplayName("ANO-80: POCKET + строка прогноза = второе число, даже когда минимумы в разные дни")
    void breakdown_forecastLine_isDifferenceBetweenTwoNumbers() {
        // План 50 000 на 10 марта топит траекторию раньше, чем это делает размазанный прогноз:
        // минимум без прогноза — 10 марта, минимум с прогнозом — в конце горизонта.
        PocketResultDto r = PocketEngine.compute(base()
                .events(planNamed(EventType.EXPENSE, LocalDate.of(2026, 3, 10), 50_000, "Шины"))
                .forecast(30_000, "Продукты")
                .horizon(LocalDate.of(2026, 3, 15))
                .build());

        BigDecimal line = r.breakdown().stream()
                .filter(b -> b.type() == BreakdownType.UNPLANNED_FORECAST)
                .map(PocketResultDto.BreakdownLine::amount)
                .findFirst().orElseThrow();

        assertThat(r.pocket().add(line))
                .as("два числа на экране обязаны отличаться ровно на эту строку")
                .isEqualByComparingTo(r.pocketWithForecast());
        assertThat(r.minPoint().date())
                .as("предпосылка теста: минимумы действительно в разные дни")
                .isNotEqualTo(r.minPointWithForecast().date());
    }

    @Test
    @DisplayName("ANO-80: строка прогноза стоит ПОСЛЕ кармашка и в инвариант не входит")
    void breakdown_invariant_holdsWithoutForecast() {
        PocketResultDto r = PocketEngine.compute(base().forecast(30_000, "Продукты").build());

        int pocketIdx = indexOf(r, BreakdownType.POCKET);
        int forecastIdx = indexOf(r, BreakdownType.UNPLANNED_FORECAST);
        assertThat(forecastIdx).isGreaterThan(pocketIdx);

        BigDecimal sum = r.breakdown().stream()
                .limit(pocketIdx)
                .map(PocketResultDto.BreakdownLine::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum)
                .as("сумма строк до POCKET по-прежнему даёт кармашек, прогноза среди них нет")
                .isEqualByComparingTo(r.pocket());
    }

    @Test
    @DisplayName("ANO-80: прогноза нет — три поля null, а не нули")
    void noForecast_secondNumberIsNull() {
        PocketResultDto r = PocketEngine.compute(base().build());

        assertThat(r.pocketWithForecast()).isNull();
        assertThat(r.minPointWithForecast()).isNull();
        assertThat(r.trajectory()).allSatisfy(p ->
                assertThat(p.balanceWithForecast()).isNull());
    }

    @Test
    @DisplayName("ANO-80: прогнозная линия идёт ниже основной всюду, где прогноз накоплен")
    void forecastLine_staysBelowPlainLine() {
        PocketResultDto r = PocketEngine.compute(base().forecast(30_000, "Продукты").build());

        assertThat(r.trajectory().stream().skip(1))
                .allSatisfy(p -> assertThat(p.balanceWithForecast())
                        .isLessThanOrEqualTo(p.balance()));
    }

    private static int indexOf(PocketResultDto r, BreakdownType type) {
        for (int i = 0; i < r.breakdown().size(); i++) {
            if (r.breakdown().get(i).type() == type) return i;
        }
        throw new AssertionError("нет строки " + type);
    }
```

- [ ] **Step 2: Прогнать — должны падать**

```bash
rm -rf backend/target/test-classes
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw -q test -Dtest=PocketEngineTest
```

Ожидание: FAIL — методов `pocketWithForecast()`, `minPointWithForecast()`, `balanceWithForecast()` нет.

- [ ] **Step 3: Расширить DTO**

В `PocketResultDto` добавить два поля в конец списка компонентов и переписать `TrajectoryPoint`:

```java
        BigDecimal pocketWithDeposits,
        /**
         * «Кармашек с обычными тратами» (ANO-80) = минимум прогнозной кумуляты − буфер.
         * {@code null}, когда прогноза по горизонту нет вовсе: галочка не стоит нигде,
         * порог наблюдения не пройден либо норма целиком покрыта планами. Различать эти
         * причины ответу не нужно — экран во всех трёх случаях делает одно и то же.
         */
        BigDecimal pocketWithForecast,
        /** Минимум прогнозной кумуляты; {@code null} там же, где и число выше. */
        MinPoint minPointWithForecast
) {
```

```java
    /**
     * Точка траектории с дневными суммами.
     *
     * <p>ANO-80: {@code expense} — расход ПО ПЛАНАМ. Раньше в него подмешивался прогноз,
     * что противоречило имени поля; дневной прогноз теперь берётся разностью балансов.
     * {@code balanceWithForecast} — вторая линия графика, {@code null} без прогноза.
     */
    public record TrajectoryPoint(LocalDate date, BigDecimal balance,
                                  BigDecimal income, BigDecimal expense,
                                  BigDecimal balanceWithForecast) {}
```

- [ ] **Step 4: Вести две кумуляты**

В `PocketEngine.compute` рядом с `running` завести вторую и вести её в том же цикле:

```java
        BigDecimal running = currentBalance.subtract(overdue).subtract(todayExpenses);
        BigDecimal runningForecast = running;
        boolean hasForecast = false;
        trajectory.add(new PocketResultDto.TrajectoryPoint(
                in.asOfDate(), running, BigDecimal.ZERO, overdue.add(todayExpenses), null));

        BigDecimal minForecast = runningForecast;
        LocalDate minForecastDate = in.asOfDate();
        String minForecastDrivenBy = null;
```

Внутри цикля дней: доходы и плановые расходы двигают ОБЕ кумуляты (добавить `runningForecast = runningForecast.add(amount)` и `.subtract(amount)` рядом с существующими), прогноз — только вторую:

```java
            BigDecimal dayForecast = forecastByDay.getOrDefault(d, BigDecimal.ZERO);
            if (dayForecast.signum() != 0) {
                hasForecast = true;
                forecastCum = forecastCum.add(dayForecast);
                runningForecast = runningForecast.subtract(dayForecast);
            }
            trajectory.add(new PocketResultDto.TrajectoryPoint(
                    d, running, dayIncome, dayExpense, hasForecast ? runningForecast : null));

            if (!d.isAfter(in.horizonEnd())) {
                if (running.compareTo(minBalance) < 0) {
                    minBalance = running;
                    minDate = d;
                    minDrivenBy = dayTopExpense;
                    expensesAtMin = expensesCum;
                    incomeAtMin = incomeCum;
                    contribAtMin = contribCum;
                    contribNamesAtMin = List.copyOf(contribNames);
                }
                if (runningForecast.compareTo(minForecast) < 0) {
                    minForecast = runningForecast;
                    minForecastDate = d;
                    minForecastDrivenBy = dayTopExpense;
                }
            }
```

Из главной ветки минимума убрать `forecastAtMin = forecastCum;` и само поле `forecastAtMin` — строка прогноза считается иначе. `dayExpense` больше не пополняется прогнозом.

После цикла:

```java
        BigDecimal pocket = minBalance.subtract(buffer);
        BigDecimal pocketWithForecast = hasForecast ? minForecast.subtract(buffer) : null;
        PocketResultDto.MinPoint minPointWithForecast = hasForecast
                ? new PocketResultDto.MinPoint(minForecastDate, minForecast, minForecastDrivenBy)
                : null;
```

- [ ] **Step 5: Передвинуть строку разбивки**

В `buildBreakdown` заменить параметр `forecastAtMin` на `forecastDifference` и удалить блок `UNPLANNED_FORECAST` из места перед `TRAJECTORY_MIN`. Добавить после строки `POCKET`, перед `OVERDUE_RELEASED`:

```java
        // ANO-80: прогноз — оговорка, а не слагаемое кармашка. Он стоит после POCKET по той же
        // причине, что WISHLIST_INFO и CREDIT_RESTORE: всё до кармашка объясняет, из чего число
        // сложилось, всё после — то, что человек может учесть, а может нет.
        //
        // В строке — РАЗНИЦА между двумя числами экрана, а не прогноз, накопленный к минимуму.
        // Минимумы стоят на разных днях, и «прогноз до минимума» с разницей чисел не сошёлся бы:
        // человек увидел бы две величины, которые не бьются.
        if (forecastDifference != null && forecastDifference.signum() != 0) {
            lines.add(new PocketResultDto.BreakdownLine(BreakdownType.UNPLANNED_FORECAST,
                    "С обычными тратами до " + DD_MM.format(minForecastDate),
                    forecastDifference, in.forecastContributors()));
        }
```

Вызов: `buildBreakdown(..., pocketWithForecast != null ? pocketWithForecast.subtract(pocket) : null, minForecastDate, ...)`.

- [ ] **Step 6: Прогнать весь бэкенд**

```bash
rm -rf backend/target/test-classes
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw -q test
```

Ожидание: PASS. Конструкторы `TrajectoryPoint` и `PocketResultDto` поедут во всех тестах и в `PocketSandboxService` — поправить вызовы, не меняя ожиданий.

- [ ] **Step 7: Мутации**

1. Вычесть `dayForecast` из `running` (главной кумуляты) → `pocket_ignoresForecast_whileSecondNumberCountsIt` краснеет.
2. Положить в строку `forecastCum` вместо разницы → `breakdown_forecastLine_isDifferenceBetweenTwoNumbers` краснеет.
3. Вернуть строку `UNPLANNED_FORECAST` перед `TRAJECTORY_MIN` → `breakdown_invariant_holdsWithoutForecast` краснеет.
4. Отдавать `BigDecimal.ZERO` вместо `null` при `hasForecast == false` → `noForecast_secondNumberIsNull` краснеет.
5. Поменять кумуляты местами в записи точки траектории → `forecastLine_staysBelowPlainLine` краснеет.

Откатить все пять.

- [ ] **Step 8: Коммит**

```bash
git add backend/src/main/java/ru/selfin/backend/ backend/src/test/java/ru/selfin/backend/service/PocketEngineTest.java
git commit -m "feat(ano-80): движок ведёт две кумуляты, кармашек считается без прогноза"
```

---

### Task 5: Второе число на карточке и вторая линия на графике

**Files:**
- Modify: `frontend/src/types/api.ts:163-188`
- Modify: `frontend/src/components/PocketCard.tsx:64-82`
- Modify: `frontend/src/components/pocket/PocketTrajectoryChart.tsx:33-40,105-120,175-205`
- Modify: `frontend/src/lib/trajectoryChart.ts`
- Test: `frontend/src/lib/trajectoryChart.test.ts`

**Как тестируется фронт в этом проекте:** только чистой логикой в `lib/*.test.ts` через vitest. `@testing-library/react` и jsdom в зависимостях отсутствуют, рендер-тестов нет ни одного. Поэтому расчётная часть второй линии выносится в `lib` и покрывается там, а разметка карточки проверяется глазами на стенде в Задаче 9: тянуть новую тестовую библиотеку ради одной проверки на `null` — не та цена.

**Interfaces:**
- Consumes: поля `pocketWithForecast`, `minPointWithForecast`, `trajectory[].balanceWithForecast` из Задачи 4.
- Produces: `forecastSeries(trajectory: TrajPoint[]): number[] | null` в `lib/trajectoryChart.ts` — ряд для второй линии либо `null`, когда прогноза нет.

- [ ] **Step 1: Расширить типы**

```ts
    minPoint: { date: string; balance: number; drivenBy: string | null };
    /** Минимум с обычными тратами (ANO-80); null — прогноза нет. */
    minPointWithForecast: { date: string; balance: number; drivenBy: string | null } | null;
    trajectory: {
        date: string; balance: number; income: number; expense: number;
        /** Вторая линия графика (ANO-80); null — прогноза нет. */
        balanceWithForecast: number | null;
    }[];
```

и рядом с `pocketWithDeposits`:

```ts
    /**
     * «Кармашек с обычными тратами» (ANO-80) — оговорка к главному числу, не замена ему.
     * null, когда прогноза нет: галочки не стоят, порог наблюдения не пройден либо норма
     * целиком покрыта планами.
     */
    pocketWithForecast: number | null;
```

- [ ] **Step 2: Написать падающий тест ряда второй линии**

В `frontend/src/lib/trajectoryChart.test.ts` добавить:

```ts
import { forecastSeries } from './trajectoryChart';

describe('ANO-80: ряд второй линии', () => {
    const pt = (balance: number, withForecast: number | null) => ({
        date: '2026-09-14', balance, income: 0, expense: 0, balanceWithForecast: withForecast,
    });

    it('прогноза нет — ряда нет, рисовать нечего', () => {
        expect(forecastSeries([pt(100, null), pt(90, null)])).toBeNull();
    });

    it('прогноз есть — ряд той же длины, что основной', () => {
        const series = forecastSeries([pt(100, null), pt(90, 80), pt(80, 60)]);
        expect(series).toEqual([100, 80, 60]);
    });

    it('дни до накопления прогноза берут основной баланс, чтобы линия не рвалась', () => {
        // Первая точка траектории — сегодня, прогноз на неё не размазывается: там null.
        // Подставить ноль значило бы уронить линию в пол на первом же дне.
        expect(forecastSeries([pt(100, null), pt(90, 70)])![0]).toBe(100);
    });
});
```

- [ ] **Step 3: Прогнать — должен падать**

```bash
cd frontend && npm test -- trajectoryChart
```

Ожидание: FAIL — `forecastSeries` не существует.

- [ ] **Step 3а: Реализовать `forecastSeries`**

В `frontend/src/lib/trajectoryChart.ts`:

```ts
/**
 * Ряд второй линии графика (ANO-80): «с обычными тратами».
 *
 * <p>null, когда прогноза нет вовсе — рисовать нечего. Точки, где прогноз ещё не накоплен,
 * берут основной баланс: подстановка нуля уронила бы линию в пол на первом же дне.
 */
export function forecastSeries(trajectory: TrajPoint[]): number[] | null {
    if (!trajectory.some(p => p.balanceWithForecast != null)) return null;
    return trajectory.map(p => p.balanceWithForecast ?? p.balance);
}
```

- [ ] **Step 3б: Прогнать — должен пройти**

```bash
cd frontend && npm test -- trajectoryChart
```

Ожидание: PASS.

- [ ] **Step 4: Добавить строку на карточку**

В `PocketCard.tsx` сразу после `<p className="text-3xl font-bold text-white">{fmtC(data.pocket)}</p>`:

```tsx
                            {/*
                              ANO-80: прогноз — предположение, и он не входит в число, которым
                              человек распоряжается. Стоит ВЫШЕ оговорок про карты и вклад: те
                              отвечают на вопрос «если я сделаю то-то», а эта — на тот же вопрос,
                              что главное число, но с другой уверенностью. Слова «прогноз» нет:
                              оно требует объяснения, которого в этом месте не будет.
                            */}
                            {data.pocketWithForecast != null && (
                                <p className="text-sm text-white/85 mt-0.5">
                                    {fmtC(data.pocketWithForecast)}
                                    <span className="text-white/60"> — с обычными тратами</span>
                                </p>
                            )}
```

- [ ] **Step 5: Добавить вторую линию на график**

В `PocketTrajectoryChart.tsx` в `useMemo` добавить вторую линию:

```tsx
        const forecast = forecastSeries(trajectory);
        // Домен считается по ОБЕИМ линиям: иначе пунктир уходит за нижнюю границу графика.
        const domain = computeDomain(forecast ? [...balances, ...forecast] : balances);
        const forecastLine = forecast
            ? buildLinePoints(forecast, W, PAD_X, TOP, FLOOR, domain)
            : null;
        return { domain, x, y, line, forecastLine, n };
```

`forecastSeries` добавить в импорт из `../../lib/trajectoryChart`, `forecastLine` — в деструктуризацию `const { domain, x, y, line, forecastLine, n } = geom;`.

и после отрисовки `mainLine`:

```tsx
                {/*
                  ANO-80: пунктир — «с обычными тратами». Пунктиром на этом графике уже нарисованы
                  буфер, граница горизонта и метка минимума, то есть всё, что не является самой
                  траекторией; предположение читается так же без нового словаря.
                */}
                {forecastLine && (
                    <polyline points={forecastLine} fill="none" stroke={LINE} strokeWidth={1.5}
                        strokeDasharray="5 4" opacity={0.65} strokeLinejoin="round" />
                )}
```

В легенду (блок со строками-образцами внизу компонента) добавить пункт рядом с существующими:

```tsx
                {forecastLine && (
                    <span className="flex items-center gap-1.5">
                        <span className="inline-block w-3.5 h-0.5 rounded"
                            style={{ background: LINE, opacity: 0.65 }} />
                        с обычными тратами
                    </span>
                )}
```

- [ ] **Step 6: Прогнать фронт целиком**

```bash
cd frontend && npm test && npx tsc --noEmit
```

Ожидание: PASS, `tsc` чистый. Тесты `trajectoryChart.test.ts` могут поехать из-за нового поля в точке — поправить фикстуры.

- [ ] **Step 7: Мутации**

1. Убрать проверку `!trajectory.some(p => p.balanceWithForecast != null)` в `forecastSeries` → тест «прогноза нет — ряда нет» краснеет.
2. Заменить `p.balanceWithForecast ?? p.balance` на `p.balanceWithForecast ?? 0` → тест «дни до накопления прогноза берут основной баланс» краснеет.

Разметка карточки тестом не покрыта — её и домен графика проверяем глазами на стенде в Задаче 9, шаг 3.

- [ ] **Step 8: Коммит**

```bash
git add frontend/src/types/api.ts frontend/src/components/PocketCard.tsx frontend/src/components/pocket/PocketTrajectoryChart.tsx frontend/src/lib/trajectoryChart.ts frontend/src/lib/trajectoryChart.test.ts
git commit -m "feat(ano-80): второе число на карточке и пунктир обычных трат на графике"
```

---

### Task 6: Две плашки кассового разрыва

**Files:**
- Modify: `frontend/src/lib/watchdogAlert.ts`
- Modify: `frontend/src/lib/watchdogAlert.test.ts`
- Modify: `frontend/src/pages/Dashboard.tsx:264-283`

**Interfaces:**
- Produces: `WatchdogAlert` с полями `kind: 'PLAN' | 'FORECAST'` и `forecastNote: { date: string; deficit: number } | null`.

- [ ] **Step 1: Написать падающие тесты**

Фикстура в том же файле — существующие тесты собирают `PocketResponse` вручную, этот хелпер их не ломает, а лишь избавляет новые тесты от полей, к делу не относящихся:

```ts
/** Минимальный PocketResponse: значимы только два минимума, остальное — заглушки. */
function pocket(over: Partial<PocketResponse>): PocketResponse {
    return {
        pocket: 0, currentBalance: 0, buffer: 0, checkpointDate: null,
        horizon: { type: 'SECOND_INCOME', endDate: '2026-10-31', label: '', fallback: false },
        minPoint: { date: '2026-10-14', balance: 0, drivenBy: null },
        minPointWithForecast: null,
        breakdown: [], trajectory: [], wishlistCandidates: [],
        pocketAfterCreditRestore: null, pocketWithDeposits: null, pocketWithForecast: null,
        ...over,
    };
}
```

```ts
    it('ANO-80: разрыв по планам — жёсткая плашка', () => {
        const alert = buildWatchdogAlert(pocket({
            minPoint: { date: '2026-10-14', balance: -4500, drivenBy: 'Аренда' },
            minPointWithForecast: null,
        }), null);
        expect(alert?.kind).toBe('PLAN');
        expect(alert?.deficit).toBe(4500);
        expect(alert?.forecastNote).toBeNull();
    });

    it('ANO-80: по планам хватает, с обычными тратами — нет: мягкая плашка', () => {
        const alert = buildWatchdogAlert(pocket({
            minPoint: { date: '2026-10-14', balance: 12000, drivenBy: 'Аренда' },
            minPointWithForecast: { date: '2026-10-09', balance: -3000, drivenBy: null },
        }), null);
        expect(alert?.kind).toBe('FORECAST');
        expect(alert?.date).toBe('2026-10-09');
        expect(alert?.deficit).toBe(3000);
    });

    it('ANO-80: оба ниже нуля — жёсткая плашка с оговоркой', () => {
        const alert = buildWatchdogAlert(pocket({
            minPoint: { date: '2026-10-14', balance: -4500, drivenBy: 'Аренда' },
            minPointWithForecast: { date: '2026-10-08', balance: -9000, drivenBy: null },
        }), null);
        expect(alert?.kind).toBe('PLAN');
        expect(alert?.forecastNote).toEqual({ date: '2026-10-08', deficit: 9000 });
    });

    it('ANO-80: оба выше нуля — плашки нет', () => {
        expect(buildWatchdogAlert(pocket({
            minPoint: { date: '2026-10-14', balance: 12000, drivenBy: null },
            minPointWithForecast: { date: '2026-10-09', balance: 500, drivenBy: null },
        }), null)).toBeNull();
    });
```

- [ ] **Step 2: Прогнать — должны падать**

```bash
cd frontend && npm test -- watchdogAlert
```

Ожидание: FAIL — поля `kind` нет.

- [ ] **Step 3: Переписать `buildWatchdogAlert`**

```ts
export type WatchdogKind = 'PLAN' | 'FORECAST';

export interface WatchdogAlert {
    /**
     * ANO-80: род предупреждения. 'PLAN' — разрыв держится на собственных планах человека
     * и требует действия. 'FORECAST' — по планам хватает, но так обычно не выходит; это
     * повод для внимания, а не для действия. Разными плашками разница видна без чтения.
     */
    kind: WatchdogKind;
    date: string;
    deficit: number;
    drivenBy: string | null;
    beyondChart: boolean;
    /** Для kind==='PLAN': с обычными тратами разрыв глубже — дата и сумма. */
    forecastNote: { date: string; deficit: number } | null;
}

export function buildWatchdogAlert(
    watchdog: PocketResponse | null,
    userHorizonEnd: string | null,
): WatchdogAlert | null {
    if (!watchdog) return null;
    const hard = watchdog.minPoint;
    const soft = watchdog.minPointWithForecast;
    const beyond = (d: string) => userHorizonEnd != null && d > userHorizonEnd;

    if (hard.balance < 0) {
        return {
            kind: 'PLAN',
            date: hard.date,
            deficit: Math.abs(hard.balance),
            drivenBy: hard.drivenBy,
            beyondChart: beyond(hard.date),
            forecastNote: soft != null && soft.balance < hard.balance
                ? { date: soft.date, deficit: Math.abs(soft.balance) }
                : null,
        };
    }
    if (soft != null && soft.balance < 0) {
        return {
            kind: 'FORECAST',
            date: soft.date,
            deficit: Math.abs(soft.balance),
            drivenBy: null,
            beyondChart: beyond(soft.date),
            forecastNote: null,
        };
    }
    return null;
}
```

- [ ] **Step 4: Развести плашки на экране**

В `Dashboard.tsx` заменить блок алерта. Жёсткая — как была, плюс оговорка; мягкая — другим тоном и без слова «дефицит»:

```tsx
            {watchdogAlert && watchdogAlert.kind === 'PLAN' && (
                <div className="rounded-xl p-4 flex gap-3 items-start"
                    style={{ background: 'rgba(239,68,68,0.12)', border: '1px solid var(--color-danger)' }}>
                    <AlertTriangle size={18} style={{ color: 'var(--color-danger)', flexShrink: 0, marginTop: 2 }} />
                    <div>
                        <p className="font-semibold text-sm" style={{ color: 'var(--color-danger)' }}>Кассовый разрыв!</p>
                        <p className="text-sm" style={{ color: 'var(--color-text)' }}>
                            {fmtLocalDate(watchdogAlert.date)} — ожидается дефицит{' '}
                            <b>{fmt(watchdogAlert.deficit)}</b>
                            {watchdogAlert.drivenBy && <> («{watchdogAlert.drivenBy}»)</>}
                        </p>
                        {watchdogAlert.forecastNote && (
                            <p className="text-xs mt-1" style={{ color: 'var(--color-text-muted)' }}>
                                С обычными тратами — раньше, {fmtLocalDate(watchdogAlert.forecastNote.date)}
                            </p>
                        )}
                        {watchdogAlert.beyondChart && (
                            <p className="text-xs mt-1" style={{ color: 'var(--color-text-muted)' }}>
                                Разрыв за пределами графика — переключись на «2-й доход»
                            </p>
                        )}
                    </div>
                </div>
            )}

            {/*
              ANO-80: предупреждение по прогнозу — другого рода, чем по плану, и оттенком
              формулировки это не передать. Слово «дефицит» занято твёрдым случаем. «Около»
              и «может» стоят не для мягкости, а потому что это правда о том, чем продукт
              располагает (правило 3). Речь о деньгах, не о человеке (правило 12).
            */}
            {watchdogAlert && watchdogAlert.kind === 'FORECAST' && (
                <div className="rounded-xl p-4 flex gap-3 items-start"
                    style={{ background: 'rgba(239,159,39,0.10)', border: '1px solid var(--color-warning)' }}>
                    <Info size={18} style={{ color: 'var(--color-warning)', flexShrink: 0, marginTop: 2 }} />
                    <p className="text-sm" style={{ color: 'var(--color-text)' }}>
                        До {fmtLocalDate(watchdogAlert.date)} по планам хватает. С обычными
                        тратами — впритык: около <b>{fmt(watchdogAlert.deficit)}</b> может не хватить.
                    </p>
                </div>
            )}
```

Импорт `Info` из `lucide-react`. Если переменной `--color-warning` в теме нет — использовать `AMBER` (`#EF9F27`) напрямую, как в графике.

- [ ] **Step 5: Прогнать фронт**

```bash
cd frontend && npm test && npx tsc --noEmit
```

Ожидание: PASS.

- [ ] **Step 6: Мутация**

В ветке «оба ниже нуля» вернуть `kind: 'FORECAST'` → тест «оба ниже нуля — жёсткая плашка с оговоркой» краснеет. Откатить.

- [ ] **Step 7: Коммит**

```bash
git add frontend/src/lib/watchdogAlert.ts frontend/src/lib/watchdogAlert.test.ts frontend/src/pages/Dashboard.tsx
git commit -m "feat(ano-80): предупреждение по прогнозу отделено от предупреждения по плану"
```

---

### Task 7: Готовность прогноза и подпись у галочки

**Files:**
- Create: `backend/src/main/java/ru/selfin/backend/dto/ForecastReadinessDto.java`
- Modify: `backend/src/main/java/ru/selfin/backend/service/PredictionService.java`
- Modify: `backend/src/main/java/ru/selfin/backend/controller/AnalyticsController.java`
- Modify: `frontend/src/types/api.ts`, `frontend/src/api/index.ts`, `frontend/src/pages/Settings.tsx:365-376,432-443`
- Test: `backend/src/test/java/ru/selfin/backend/service/PredictionServiceReadinessTest.java` (создать)

**Interfaces:**
- Produces: `GET /api/v1/analytics/forecast-readiness` → `{ monthsObserved: number, monthsRequired: number, readyFrom: string | null }`, где `readyFrom` — месяц вида `2026-11` или `null`, если порог уже пройден.

- [ ] **Step 1: Написать падающие тесты**

```java
    @Test
    @DisplayName("ANO-80: наблюдений хватает — прогноз готов, readyFrom пуст")
    void readiness_enoughObservation_isReady() {
        when(eventRepo.findFirstFactDate()).thenReturn(LocalDate.of(2026, 3, 9));
        // сегодня 14 сентября → наблюдение апрель..август = пять месяцев
        assertThat(service.readiness().monthsObserved()).isEqualTo(5);
        assertThat(service.readiness().readyFrom()).isNull();
    }

    @Test
    @DisplayName("ANO-80: наблюдений мало — сказано, с какого месяца появится")
    void readiness_belowThreshold_namesTheMonth() {
        when(eventRepo.findFirstFactDate()).thenReturn(LocalDate.of(2026, 7, 20));
        // наблюдение с августа = один месяц, нужно три → ещё два, то есть с ноября
        assertThat(service.readiness().monthsObserved()).isEqualTo(1);
        assertThat(service.readiness().readyFrom()).isEqualTo("2026-11");
    }

    @Test
    @DisplayName("ANO-80: фактов нет — ноль наблюдений и пустой месяц готовности")
    void readiness_noFacts_isEmpty() {
        when(eventRepo.findFirstFactDate()).thenReturn(null);
        assertThat(service.readiness().monthsObserved()).isZero();
        assertThat(service.readiness().readyFrom()).isNull();
    }
```

- [ ] **Step 2: Прогнать — должны падать**

```bash
rm -rf backend/target/test-classes
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw -q test -Dtest=PredictionServiceReadinessTest
```

Ожидание: FAIL — метода `readiness()` нет.

- [ ] **Step 3: Реализовать**

```java
package ru.selfin.backend.dto;

/**
 * Готовность прогноза (ANO-80). Величина ОБЩАЯ, а не покатегорийная: окно наблюдения
 * отвечает на вопрос «давно ли человек ведёт учёт».
 *
 * @param monthsObserved сколько полных месяцев наблюдения набралось
 * @param monthsRequired сколько нужно
 * @param readyFrom      месяц вида «2026-11», с которого прогноз появится; null — уже готов
 *                       либо фактов нет вовсе и считать не от чего
 */
public record ForecastReadinessDto(int monthsObserved, int monthsRequired, String readyFrom) {}
```

В `PredictionService`:

```java
    public ForecastReadinessDto readiness() {
        LocalDate today = LocalDate.now(clock);
        YearMonth lastFull = YearMonth.from(today).minusMonths(1);
        LocalDate firstFact = eventRepository.findFirstFactDate();
        if (firstFact == null) {
            return new ForecastReadinessDto(0, MIN_HISTORY_MONTHS, null);
        }
        YearMonth firstObserved = YearMonth.from(firstFact).plusMonths(1);
        int observed = (int) Math.max(0, ChronoUnit.MONTHS.between(firstObserved, lastFull) + 1);
        if (observed >= MIN_HISTORY_MONTHS) {
            return new ForecastReadinessDto(observed, MIN_HISTORY_MONTHS, null);
        }
        YearMonth readyFrom = YearMonth.from(today).plusMonths(MIN_HISTORY_MONTHS - observed);
        return new ForecastReadinessDto(observed, MIN_HISTORY_MONTHS, readyFrom.toString());
    }
```

В `AnalyticsController`:

```java
    @GetMapping("/forecast-readiness")
    public ForecastReadinessDto getForecastReadiness() {
        return predictionService.readiness();
    }
```

- [ ] **Step 4: Прогнать**

```bash
rm -rf backend/target/test-classes
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw -q test
```

Ожидание: PASS.

- [ ] **Step 5: Подпись у галочки**

В `Settings.tsx` под обе галочки «Отслеживать прогноз» (создание и редактирование) добавить постоянную подпись:

```tsx
                            {/*
                              ANO-80: три факта, о которые иначе спотыкаются — откуда берётся,
                              почему может не появиться, и что главное число он не трогает.
                              Подписью, а не модалкой: модалка читается как предупреждение о
                              риске, а предупреждать не о чем.
                            */}
                            <p className="text-[11px] leading-snug mt-1"
                                style={{ color: 'var(--color-text-muted)' }}>
                                Считает, сколько обычно уходит в эту категорию за месяц — по
                                последним месяцам, включая те, когда трат не было. Нужно три
                                месяца наблюдений. На кармашек не влияет: показывается отдельным
                                числом рядом.
                            </p>
```

Рядом с пометкой «· прогноз» в списке категорий дописать месяц готовности:

```tsx
                                                    {c.forecastEnabled && (
                                                        <span className="text-xs ml-1.5" style={{ color: 'var(--color-accent)', opacity: 0.7 }}>
                                                            {readiness?.readyFrom
                                                                ? `· прогноз — с ${monthLabel(readiness.readyFrom)}`
                                                                : '· прогноз'}
                                                        </span>
                                                    )}
```

Хелпер в том же файле:

```tsx
const MONTHS_GENITIVE = ['января', 'февраля', 'марта', 'апреля', 'мая', 'июня',
    'июля', 'августа', 'сентября', 'октября', 'ноября', 'декабря'];

/** '2026-11' → 'ноября'. Родительный падеж: подставляется в «с ноября». */
function monthLabel(yearMonth: string): string {
    return MONTHS_GENITIVE[Number(yearMonth.slice(5, 7)) - 1] ?? yearMonth;
}
```

`readiness` грузится в `load()` через новый вызов в `api/index.ts`:

```ts
export const getForecastReadiness = () =>
    request<ForecastReadiness>('/analytics/forecast-readiness');
```

и тип рядом с остальными в `types/api.ts`:

```ts
/** Готовность прогноза (ANO-80). readyFrom — месяц вида '2026-11'; null — уже готов. */
export interface ForecastReadiness {
    monthsObserved: number;
    monthsRequired: number;
    readyFrom: string | null;
}
```

- [ ] **Step 6: Прогнать фронт**

```bash
cd frontend && npm test && npx tsc --noEmit
```

- [ ] **Step 7: Мутация**

Вернуть `readyFrom` вычисление без вычитания `observed` (`today.plusMonths(MIN_HISTORY_MONTHS)`) → `readiness_belowThreshold_namesTheMonth` краснеет (даст 2026-12). Откатить.

- [ ] **Step 8: Коммит**

```bash
git add backend/src/main/java/ru/selfin/backend/ backend/src/test/java/ru/selfin/backend/service/PredictionServiceReadinessTest.java frontend/src
git commit -m "feat(ano-80): подпись у галочки и месяц, с которого прогноз появится"
```

---

### Task 8: Интеграционный тест — ANO-80 дословно

**Files:**
- Create: `backend/src/test/java/ru/selfin/backend/CurrentMonthForecastIT.java`

**Interfaces:**
- Consumes: всё, собранное в Задачах 1-7; проверяется через HTTP-слой `MockMvc`, как в `FundMoneyFlowIT`.

**Имя категории.** В чистой базе действуют сиды `V2`: расходная категория называется `Еда / Продукты`. Просто `Продукты` — имя с эталонного стенда владельца, в тестовой базе его нет.

- [ ] **Step 1: Написать падающий тест**

```java
package ru.selfin.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.selfin.backend.dto.StandaloneFactCreateDto;
import ru.selfin.backend.model.enums.EventType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ANO-80: первая трата нового пользователя не обваливает кармашек.
 *
 * <p>Тест НЕ подменяет «сегодня» — и это часть утверждения. До починки результат зависел от
 * номера дня: множитель равен «дней в месяце / номер дня», и тест, написанный под пятое
 * число, в другой день доказывал бы другое число. После починки день не влияет ни на что,
 * поэтому проверка идёт относительно реального «сегодня» и обязана держаться любой датой.
 *
 * <p>Спека: {@code docs/superpowers/specs/2026-09-14-current-month-forecast-design.md}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class CurrentMonthForecastIT {

    private static final String FOOD = "Еда / Продукты";   // имя из сидов V2

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;

    /** Контейнер один на класс: без уборки тесты протекают друг в друга. Сиды не трогаем. */
    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM financial_events");
        jdbc.update("DELETE FROM balance_checkpoints");
        jdbc.update("UPDATE categories SET forecast_enabled = false");
    }

    @Test
    @DisplayName("ANO-80: первая трата нового пользователя не обваливает кармашек")
    void firstExpenseOfNewUser_doesNotCollapsePocket() throws Exception {
        // Галочка включается ВРУЧНУЮ — V21 сняла её у всех. Это делает тест строже:
        // он доказывает, что дело не в галочке, а в механизме.
        enableForecast(FOOD);
        anchor("0");
        postFact(FOOD, "23444", LocalDate.now());

        JsonNode pocket = getPocket();

        assertThat(pocket.get("pocket").decimalValue())
                .as("дневной темп давал −140 664: сумма × дней в месяце / номер дня")
                .isEqualByComparingTo("-23444");
        assertThat(pocket.get("pocketWithForecast").isNull())
                .as("наблюдений нет — оснований для второго числа тоже нет")
                .isTrue();
    }

    @Test
    @DisplayName("ANO-80: три месяца наблюдений — второе число равно норме")
    void withThreeMonthsOfObservation_secondNumberEqualsMedian() throws Exception {
        enableForecast(FOOD);
        anchor("300000");
        // Месяц первого факта отбрасывается как неполный: фактов четыре, месяцев наблюдения
        // три. Ряд 36 000, 42 000, 36 000 → медиана 36 000.
        postFact(FOOD, "30000", monthsAgo(4));
        postFact(FOOD, "36000", monthsAgo(3));
        postFact(FOOD, "42000", monthsAgo(2));
        postFact(FOOD, "36000", monthsAgo(1));

        JsonNode pocket = getPocket();

        assertThat(pocket.get("pocketWithForecast").isNull())
                .as("наблюдений хватает — оговорка обязана появиться")
                .isFalse();
        assertThat(pocket.get("pocket").decimalValue()
                        .subtract(pocket.get("pocketWithForecast").decimalValue()))
                .as("в текущем месяце трат нет, поэтому второе число ниже ровно на норму")
                .isEqualByComparingTo("36000");
    }

    // ── хелперы ────────────────────────────────────────────────────────────────

    /** День фиксируем десятым: «минус месяц» от 31-го зажимается календарём и уезжает. */
    private static LocalDate monthsAgo(int months) {
        return LocalDate.now().minusMonths(months).withDayOfMonth(10);
    }

    private void enableForecast(String categoryName) {
        jdbc.update("UPDATE categories SET forecast_enabled = true WHERE name = ?", categoryName);
    }

    /** Якорь остатка вчерашним днём — как в FundMoneyFlowIT. */
    private void anchor(String amount) {
        String accountId = jdbc.queryForObject(
                "SELECT id::text FROM accounts WHERE is_default = true AND is_deleted = false",
                String.class);
        jdbc.update("""
                INSERT INTO balance_checkpoints (id, date, amount, account_id, created_at)
                VALUES (gen_random_uuid(), CURRENT_DATE - 1, ?::numeric, ?::uuid, now())
                """, amount, accountId);
    }

    private void postFact(String categoryName, String amount, LocalDate date) throws Exception {
        String categoryId = jdbc.queryForObject(
                "SELECT id::text FROM categories WHERE name = ? AND is_deleted = false",
                String.class, categoryName);
        String body = objectMapper.writeValueAsString(new StandaloneFactCreateDto(
                date, UUID.fromString(categoryId), EventType.EXPENSE,
                new BigDecimal(amount), "IT", null, null));
        mockMvc.perform(post("/api/v1/events/facts")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    private JsonNode getPocket() throws Exception {
        String json = mockMvc.perform(get("/api/v1/pocket"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json);
    }
}
```

- [ ] **Step 2: Прогнать — должен падать**

```bash
rm -rf backend/target/test-classes
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw -q verify -Dit.test=CurrentMonthForecastIT
```

Ожидание: FAIL до реализации; после Задач 1-7 — PASS. Если PASS сразу, тест ничего не доказывает: проверить, что фикстура действительно включает галочку.

- [ ] **Step 3: Мутация**

Вернуть в `PredictionService` дневной темп → первый тест обязан показать −140 664, то самое число из тела задачи. Откатить.

- [ ] **Step 4: Полный прогон**

```bash
rm -rf backend/target/test-classes
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw -q verify
cd frontend && npm test && npx tsc --noEmit
```

Ожидание: ноль падений на обеих сторонах.

- [ ] **Step 5: Коммит**

```bash
git add backend/src/test/java/ru/selfin/backend/CurrentMonthForecastIT.java
git commit -m "test(ano-80): воспроизведение из тела задачи закрыто интеграционным тестом"
```

---

### Task 9: Проверка на стенде и закрытие задачи

**Files:**
- Modify: `docs/superpowers/plans/2026-09-14-current-month-forecast.md` (раздел «Выполнено»)

- [ ] **Step 1: Поднять стенд**

```bash
docker start selfin-test-db selfin-test-backend selfin-test-frontend
```

- [ ] **Step 2: Снять «до» — пока галочки сняты**

Открыть `http://localhost:5174`. Записать кармашек, наличие второго числа, наличие пунктира, плашку. Ожидание: всё как до правки, второго числа нет — галочки сняты везде.

- [ ] **Step 3: Включить прогноз трём категориям**

На экране категорий включить «Продукты», «Кафе, рестики, фастфуд», «Авто». Обновить дашборд.

Ожидание по замеру из спеки: второе число ниже главного примерно на 47 965; на графике появился пунктир; строка «С обычными тратами …» стоит ПОСЛЕ строки «Кармашек» в разбивке.

- [ ] **Step 4: Сверить арифметику разбивки**

```bash
curl -s "http://localhost:8081/api/v1/pocket" | python -m json.tool | head -60
```

Проверить руками: `pocket` + сумма строки `UNPLANNED_FORECAST` = `pocketWithForecast`. Сумма строк ДО `POCKET` = `pocket`.

- [ ] **Step 4а: Проверить, что примерка не поехала**

Спека утверждает: «Примерка не трогается — `PocketSandboxService` пересобирает вход и зовёт тот же движок». Утверждение проверяемое, а не самоочевидное: движок теперь отдаёт два числа, и примерка сравнивает базу с подгонкой.

Открыть примерку, добавить любой элемент. Ожидание: обе линии графика — без прогноза, сравнение «как сейчас / как станет» осталось на месте, чисел стало не больше. Прогонять заодно `PocketSandboxServiceTest`, `SandboxLayoutTest`, `SandboxLayoutStretchTargetDateTest` — они уже в общем прогоне, но убедиться, что зелены именно они.

- [ ] **Step 5: Проверить обе плашки**

Жёсткая: добавить плановый расход, топящий баланс до отрицательного, — убедиться, что плашка красная и со словом «дефицит». Мягкая: убрать его и подобрать состояние, где по планам хватает, а с обычными тратами нет, — убедиться, что плашка другого тона и слова «дефицит» в ней нет.

- [ ] **Step 6: Вернуть стенд к эталону**

Снять галочки с трёх категорий и удалить добавленные проверочные события. Сверить: кармашек 26 000, остаток 60 000, капитал 2 668 277, ликвид 210 000, якорь 2026-08-29.

- [ ] **Step 7: Дописать в план раздел «Выполнено»**

Замеры «до/после», список выполненных мутаций, расхождения плана с реальностью — по образцу планов предыдущей сессии.

- [ ] **Step 8: Отметить в Linear и открыть PR**

Комментарий в ANO-80 с замерами и списком мутаций, статус Done. PR с идентификатором ANO-80 в заголовке — задача закрывается целиком, поэтому автоматика Linear сработает верно.

```bash
git push -u origin fix/ano-80-current-month-forecast
gh pr create --title "Прогноз текущего месяца: норма вместо дневного темпа (ANO-80)" --body-file -
```

---

## Заметки для исполнителя

**Порядок задач обязателен.** Задача 3 опирается на медиану из Задачи 1 и константы из Задачи 2; Задача 4 — на вклад из Задачи 3; фронт (5-7) — на контракт из Задачи 4.

**Падающие чужие тесты — находка, а не помеха.** В Задачах 3 и 4 поедут `DashboardServiceTest`, `PocketInputAssemblerTest`, `PocketServiceTest`, `PocketMigrationRegressionTest`. Каждое падение разбирается: тест, доказывавший поведение дневного темпа, переписывается под норму; тест, поехавший по конструктору, чинится механически. Подкручивать ожидания, чтобы позеленело, нельзя — если число изменилось не так, как предсказывает спека, это находка и повод остановиться.

**`rm -rf backend/target/test-classes` перед каждым прогоном** в Задачах 3, 4 и 8 — там меняются сигнатуры.

---

# Выполнено

Все девять задач закрыты 14–15 сентября 2026. Ветка `fix/ano-80-current-month-forecast`,
тринадцать коммитов. Прогон: **392 юнита + 155 интеграционных**, фронт 114, `tsc` чистый.

## Замеры на эталонном стенде

Бэкенд поднят на базе стенда (порт 5433), дата расчёта 15 сентября.

| | галочки сняты | Продукты, Кафе, Авто включены |
|---|---|---|
| `pocket` | −10 100 | **−10 100** |
| `pocketWithForecast` | `null` | −54 375,44 |
| строка `UNPLANNED_FORECAST` | нет | −44 275,44 |

**Главное число не сдвинулось ни на копейку** — в этом вся починка.

Строка прогноза сошлась с предсказанием спеки до копейки. Предсказано было 47 965 на всю
норму трёх категорий; минимум с прогнозом стоит на 27.09, за день до дохода 28.09, то есть
в него попадают 12 дней размазки из 13: дневная доля 47 965 / 13 = 3 689,62, накоплено
12 × 3 689,62 = **44 275,44**. Совпадение точное, а не приблизительное.

Вторая линия траектории: 16.09 — 26 310,38, 17.09 — 22 620,76, шаг ровно 3 689,62.
Основная линия при этом плоская (30 000) — прогноз её не трогает.

Готовность прогноза: `monthsObserved = 5`, `monthsRequired = 3`, `readyFrom = null` —
сходится с SQL-замером окна апрель–август из спеки.

## Стенд не пострадал

Правка не двигает стенд: при снятых галочках `main` и ветка дают **идентичные** числа —
проверено прямым замером, второй бэкенд поднят из worktree на `main` и опрошен тем же
запросом. Кармашек −10 100 против 26 000 из передачи — это два прошедших дня (другой
горизонт и другая просрочка), а не регрессия.

После проверки галочки сняты, состояние возвращено: капитал 2 668 277, ликвид 210 000,
остаток 60 000, якорь 29.08.

## Что нашлось сверх плана

**Четыре находки собственного ревью диффа** (Задача 3½, коммиты `8effa54` и `90446d6`):

1. Половины одной формулы считали потраченное по-разному. Факт вносится двумя путями —
   отдельным событием и правкой строки плана, — и второй путь терялся фильтром
   `eventKind == FACT`. Человек, отмечающий траты прямо в плане, не имел бы ни одного
   месяца наблюдения и **не получил бы прогноза никогда**.
2. N+1 на горячем пути: 21 категория × 2 запроса × 2 скоупа на загрузку дашборда.
3. Виновники прогноза отбирались по «плана нет» — признак стал враньём после смены
   механизма; вклад переехал в `CategoryForecastDto.beyondPlan`.
4. Собственная мутация вскрыла слабый тест: проверка погашенного плана несла сразу обе
   приметы закрытия, поэтому снятие любого одного фильтра её не роняло.

**Находка стенда, которую не поймал ни один из 547 тестов** (коммит `c0f5f66`): оговорка
в плашке разрыва говорила «с обычными тратами — раньше», а показывается она по признаку
«глубже». На стенде прогнозный минимум оказался ПОЗЖЕ планового — 14 октября против
12-го, — и плашка утверждала бы неправду. Текст теперь называет то, по чему отбирается.

## Расхождения плана с реальностью

* **`mvnw` лежит в `backend/`, а не в корне.** Команды в плане писались от корня.
* **`PocketEngine.calculate`, а не `compute`** — план ссылался на несуществующее имя.
* **Мутация «прогноз до минимума» в первом заходе оказалась не мутацией:**
  `minForecast − minBalance` алгебраически тождественно разнице двух чисел. Настоящая
  мутация потребовала вернуть накопитель `forecastCum`; она поймана четырьмя тестами.
* **Фильтр `factAmount == null` в `sumPendingPlans` не убивается мутацией:** продукт
  переводит статус и проставляет факт одной операцией, поэтому состояние, от которого
  фильтр защищает, его же кодом не создаётся. Оставлен как зеркало
  `PocketEngine.isPendingPlan` и помечен комментарием — защита от битых строк, а не живая
  ветка.
* **Интеграционный тест уронил ожидание автора:** разница двух чисел оказалась не одной
  нормой, а двумя — при фолбэк-горизонте «+30 дней» кармашек видит и норму следующего
  месяца (ANO-36). Утверждение переписано так, чтобы не зависеть ни от даты прогона, ни
  от длины горизонта.

## Отложено отдельной задачей

**Плашка разрыва не называет виновника, когда у самого крупного расхода дня нет описания.**
На стенде минимум 21.09 стоит на «Ипотеке» 23 600 — расходе без описания, — и человек
видит «ожидается дефицит 10 100 ₽» без единого слова о причине. `minPoint.drivenBy`
падает на описание события и не имеет запасного варианта в виде имени категории. Механизм
другой, чем у ANO-80, поэтому не бандлилось.
