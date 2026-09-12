# День якоря: что считается остатком — план реализации (ANO-82, ANO-125)

> **Для агентов:** ОБЯЗАТЕЛЬНЫЙ САБ-СКИЛ: `superpowers:subagent-driven-development` либо `superpowers:executing-plans`. Шаги отмечаются чекбоксами.

**Цель:** факт, записанный после сверки остатка, перестаёт пропадать; перевод в копилку в день ре-якоря перестаёт создавать деньги из ничего.

**Архитектура:** правило «что входит в остаток» перестаёт опираться на голую дату и начинает различать по времени записи: факт дня якоря считается, если записан позже, чем введён якорь. Правило переезжает в одно место вместо трёх копий. Движок получает время записи во входе — сейчас он его не видит.

**Стек:** Java 21, Spring Boot 4.0.3, PostgreSQL, JUnit 5, Mockito, Testcontainers.

**Спека:** `docs/superpowers/specs/2026-09-13-anchor-day-design.md`. Ссылки «спека §N» ведут туда.

## Глобальные ограничения

- **Правок схемы нет.** `created_at` уже есть и в `financial_events`, и в `balance_checkpoints`, обе NOT NULL. Миграция данных не нужна: правило читает то, что записано.
- **Защита ANO-28 обязана уцелеть.** Факт, записанный ДО ввода якоря, по-прежнему не считается. На стенде это 12 фактов из 26 — если после починки их станет 0, защита сломана.
- **ANO-79 в этом плане не чинится** (решение 2): правило для планов не трогаем, `findOverdueMandatoryExpenses` остаётся как есть. Объяснение дельты — отдельная задача.
- **Механизм 2 из ANO-82** («факт на будущий план уводит расход целиком») сюда НЕ входит: отдельный дефект, ждёт разбора кейсов с владельцем.
- Каждый новый тест проверяется мутацией.

## Запуск тестов

Из каталога `backend`:

```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw verify
```

Один IT-класс отдельно (см. ловушку ниже):

```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw verify -Dit.test=AnchorDayIT -DfailIfNoSpecifiedTests=false -Dtest=ZzzNoSuchTest -Dsurefire.failIfNoSpecifiedTests=false
```

**Ловушка отчётов.** Падающий юнит-тест не даёт Maven дойти до фазы интеграционных, а отчёт в `target/failsafe-reports` остаётся от прошлого прогона и выглядит зелёным. На этом в прошлой сессии чуть не построили ложный вывод. При проверке мутаций сверяйте время файла отчёта либо гоните IT отдельно.

**Ловушка оснастки.** Якорь в тестах денежных сценариев ставить ВЧЕРАШНИМ днём, если тест не про день якоря. При якоре «сегодня» переводы выпадают из остатка и маскируют проверяемое — на этом уже спотыкались в `FundMoneyFlowIT`.

## Структура файлов

| файл | ответственность |
|---|---|
| `service/AnchorWindow.java` | **новый.** Единственное место правила «входит ли факт в остаток» |
| `dto/pocket/EventSnapshot.java` | получает `createdAt` |
| `dto/pocket/PocketInput.java` | получает `checkpointCreatedAt` |
| `service/AccountBalanceService.java` | `factsDelta` зовёт `AnchorWindow` |
| `service/PocketEngine.java` | шаг 1 зовёт `AnchorWindow` |
| `service/BalanceCheckpointService.java` | дрейф зовёт `AnchorWindow` |
| `service/PocketInputAssembler.java` | прокидывает время ввода якоря во вход |
| `test/.../AnchorDayIT.java` | **новый.** Закон сохранения и защита ANO-28 на живой базе |

---

### Задача 1: Правило в одном месте

**Files:**
- Create: `backend/src/main/java/ru/selfin/backend/service/AnchorWindow.java`
- Test: `backend/src/test/java/ru/selfin/backend/service/AnchorWindowTest.java`

**Interfaces:**
- Consumes: ничего
- Produces: `AnchorWindow.countsTowardBalance(LocalDate factDate, Instant factCreatedAt, LocalDate anchorDate, Instant anchorCreatedAt, LocalDate upperBound)` → `boolean`

- [ ] **Шаг 1: Написать падающий тест**

```java
package ru.selfin.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ANO-82/ANO-125. Правило «входит ли факт в остаток» — единственное место (спека §4).
 *
 * <p>До этой правки правило было записано ТРИЖДЫ и опиралось на голую дату, отчего факт,
 * записанный после сверки остатка, пропадал навсегда.
 */
class AnchorWindowTest {

    private static final LocalDate ANCHOR_DAY = LocalDate.of(2026, 9, 5);
    private static final LocalDateTime ANCHOR_ENTERED = LocalDateTime.of(2026, 9, 5, 10, 0);
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 5);

    @Test
    @DisplayName("факт ПОСЛЕ даты якоря считается всегда")
    void factAfterAnchorDate_counts() {
        assertThat(AnchorWindow.countsTowardBalance(
                LocalDate.of(2026, 9, 6), LocalDateTime.of(2026, 9, 6, 12, 0),
                ANCHOR_DAY, ANCHOR_ENTERED, LocalDate.of(2026, 9, 7)))
                .isTrue();
    }

    @Test
    @DisplayName("ANO-82: факт дня якоря, записанный ПОСЛЕ сверки, считается")
    void factOnAnchorDay_recordedAfter_counts() {
        assertThat(AnchorWindow.countsTowardBalance(
                ANCHOR_DAY, LocalDateTime.of(2026, 9, 5, 18, 0),
                ANCHOR_DAY, ANCHOR_ENTERED, TODAY))
                .as("человек сверился утром и записал траты вечером — правило 6")
                .isTrue();
    }

    @Test
    @DisplayName("ANO-28: факт дня якоря, записанный ДО сверки, НЕ считается")
    void factOnAnchorDay_recordedBefore_doesNotCount() {
        assertThat(AnchorWindow.countsTowardBalance(
                ANCHOR_DAY, LocalDateTime.of(2026, 9, 5, 9, 0),
                ANCHOR_DAY, ANCHOR_ENTERED, TODAY))
                .as("трата была на экране, когда человек вводил число из банка — оно её содержит")
                .isFalse();
    }

    @Test
    @DisplayName("факт РАНЬШЕ даты якоря не считается, когда бы ни был записан")
    void factBeforeAnchorDate_neverCounts() {
        assertThat(AnchorWindow.countsTowardBalance(
                LocalDate.of(2026, 9, 1), LocalDateTime.of(2026, 9, 9, 12, 0),
                ANCHOR_DAY, ANCHOR_ENTERED, TODAY))
                .as("до дня якоря правило прежнее: банк уже всё учёл")
                .isFalse();
    }

    @Test
    @DisplayName("якорь задним числом: граница по времени ввода, а не по дате")
    void backdatedAnchor_usesEntryTime() {
        LocalDate anchorDay = LocalDate.of(2026, 8, 29);
        LocalDateTime enteredLater = LocalDateTime.of(2026, 9, 5, 15, 12);

        assertThat(AnchorWindow.countsTowardBalance(
                anchorDay, LocalDateTime.of(2026, 8, 29, 20, 0),
                anchorDay, enteredLater, TODAY))
                .as("записано до ввода якоря — человек видел это в банке 05.09")
                .isFalse();

        assertThat(AnchorWindow.countsTowardBalance(
                anchorDay, LocalDateTime.of(2026, 9, 6, 11, 0),
                anchorDay, enteredLater, TODAY))
                .as("вспомнили и записали позже ввода — в число попасть не могло")
                .isTrue();
    }

    @Test
    @DisplayName("факт позже верхней границы не считается")
    void factAfterUpperBound_doesNotCount() {
        assertThat(AnchorWindow.countsTowardBalance(
                LocalDate.of(2026, 9, 10), LocalDateTime.of(2026, 9, 10, 12, 0),
                ANCHOR_DAY, ANCHOR_ENTERED, TODAY))
                .isFalse();
    }

    @Test
    @DisplayName("якоря нет: верхняя граница работает, нижней нет")
    void noAnchor_onlyUpperBoundApplies() {
        assertThat(AnchorWindow.countsTowardBalance(
                LocalDate.of(2020, 1, 1), LocalDateTime.of(2020, 1, 1, 12, 0),
                null, null, TODAY))
                .as("без якоря суммируются все факты до верхней границы (ANO-28 фолбэк)")
                .isTrue();
    }
}
```

- [ ] **Шаг 2: Прогнать и убедиться, что не компилируется**

Ожидается: класса `AnchorWindow` нет.

- [ ] **Шаг 3: Написать правило**

```java
package ru.selfin.backend.service;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Единственное место правила «входит ли факт в остаток счёта» (ANO-82, ANO-125, спека §4).
 *
 * <p>Правило ANO-15 §5 гласит: число из банка уже содержит всё, что случилось до него.
 * Раньше это выражалось голой датой — операции дня якоря отбрасывались целиком. Отсюда
 * ANO-82: человек сверялся утром, записывал траты вечером, и весь день ввода уходил в
 * никуда. И ANO-125: перевод в копилку в тот же день выпадал из остатка, а баланс копилки
 * его засчитывал, отчего капитал рос на пустом месте.
 *
 * <p><b>Различаем по времени записи, а не по дате.</b> «Банк уже всё учёл» верно только для
 * того, что существовало В МОМЕНТ СВЕРКИ. Записанное после — попасть в то число не могло.
 * Правило 6 продуктовых правил: ре-якорь разделяет частоты ввода и вывода, склеивать их
 * обратно нельзя.
 *
 * <p>Правило переживает якорь задним числом: сравнивается время ВВОДА якоря, а не его дата.
 *
 * <p><b>Принятый риск (спека §решение 1).</b> Трата картой, случившаяся ДО момента сверки, но
 * записанная ПОСЛЕ неё, будет вычтена второй раз: признака «прошло по банку» у события нет
 * (у {@code financial_events} вовсе нет привязки к счёту, §5.1). Случай узкий — трата картой
 * ПОСЛЕ сверки в прочитанное число не попала и считается верно. Ошибка уходит в сторону
 * уменьшения остатка, то есть в безопасную.
 */
public final class AnchorWindow {

    private AnchorWindow() {}

    /**
     * Входит ли факт в остаток на дату {@code upperBound}.
     *
     * @param factDate        дата факта
     * @param factCreatedAt   когда факт записан; {@code null} — считать записанным давно
     * @param anchorDate      дата якоря либо {@code null}, если якоря нет
     * @param anchorCreatedAt когда якорь введён; {@code null} вместе с {@code anchorDate}
     * @param upperBound      верхняя граница окна, включительно
     */
    public static boolean countsTowardBalance(LocalDate factDate, LocalDateTime factCreatedAt,
                                              LocalDate anchorDate, LocalDateTime anchorCreatedAt,
                                              LocalDate upperBound) {
        if (factDate == null || factDate.isAfter(upperBound)) return false;
        if (anchorDate == null) return true;           // якоря нет — нижней границы тоже
        if (factDate.isAfter(anchorDate)) return true; // строго после якоря — как было
        if (!factDate.isEqual(anchorDate)) return false; // раньше якоря — как было

        // День якоря: решает время записи, а не дата.
        if (factCreatedAt == null || anchorCreatedAt == null) return false; // нечем решить — как было
        return factCreatedAt.isAfter(anchorCreatedAt);
    }
}
```

- [ ] **Шаг 4: Прогнать — семь тестов проходят**

- [ ] **Шаг 5: Проверить мутациями**

| мутация | обязан упасть |
|---|---|
| `factCreatedAt.isAfter(...)` → `true` | `factOnAnchorDay_recordedBefore_doesNotCount` |
| `factCreatedAt.isAfter(...)` → `false` | `factOnAnchorDay_recordedAfter_counts` |
| убрать проверку `isAfter(upperBound)` | `factAfterUpperBound_doesNotCount` |

Каждую применить, прогнать, вернуть.

- [ ] **Шаг 6: Коммит**

```bash
git add backend/src/main/java/ru/selfin/backend/service/AnchorWindow.java backend/src/test/java/ru/selfin/backend/service/AnchorWindowTest.java
git commit -m "feat(ano-82): правило дня якоря в одном месте, различает по времени записи"
```

---

### Задача 2: Остаток счёта считает по новому правилу

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/service/AccountBalanceService.java` (метод `factsDelta`, строки 274-285)
- Test: `backend/src/test/java/ru/selfin/backend/service/AccountBalanceServiceTest.java`

**Interfaces:**
- Consumes: `AnchorWindow.countsTowardBalance`
- Produces: изменённая семантика `balanceAt` и `freeMoneyAt`

- [ ] **Шаг 1: Написать падающий тест**

Дописать в `AccountBalanceServiceTest`:

```java
    @Test
    @DisplayName("ANO-82: факт дня якоря, записанный после сверки, входит в остаток")
    void factOnAnchorDay_recordedAfter_entersBalance() {
        LocalDate day = LocalDate.of(2026, 9, 5);
        BalanceCheckpoint anchor = checkpoint(day, dec(50_000),
                LocalDateTime.of(2026, 9, 5, 10, 0));
        FinancialEvent late = factEvent(day, dec(3_000),
                LocalDateTime.of(2026, 9, 5, 18, 0));

        when(checkpointRepository.findLatestForAccountAt(any(), any()))
                .thenReturn(Optional.of(anchor));
        when(eventRepository.findAllByDeletedFalseAndDateBetween(any(), any()))
                .thenReturn(List.of(late));

        assertThat(service.balanceAt(defaultAccount, day))
                .as("трата записана после сверки — в банковское число попасть не могла")
                .isEqualByComparingTo(dec(47_000));
    }

    @Test
    @DisplayName("ANO-28: факт дня якоря, записанный до сверки, в остаток НЕ входит")
    void factOnAnchorDay_recordedBefore_staysOut() {
        LocalDate day = LocalDate.of(2026, 9, 5);
        BalanceCheckpoint anchor = checkpoint(day, dec(50_000),
                LocalDateTime.of(2026, 9, 5, 10, 0));
        FinancialEvent early = factEvent(day, dec(3_000),
                LocalDateTime.of(2026, 9, 5, 9, 0));

        when(checkpointRepository.findLatestForAccountAt(any(), any()))
                .thenReturn(Optional.of(anchor));
        when(eventRepository.findAllByDeletedFalseAndDateBetween(any(), any()))
                .thenReturn(List.of(early));

        assertThat(service.balanceAt(defaultAccount, day))
                .as("защита от задвоения обязана уцелеть")
                .isEqualByComparingTo(dec(50_000));
    }
```

Вспомогательные методы дописать рядом с существующими, повторив их стиль: `checkpoint(date, amount, createdAt)` строит `BalanceCheckpoint` с заданным `createdAt`, `factEvent(date, amount, createdAt)` — `FinancialEvent` с `eventKind = FACT`, `factAmount`, `type = EXPENSE` и заданным `createdAt`.

- [ ] **Шаг 2: Прогнать и убедиться, что первый падает**

Ожидается: `factOnAnchorDay_recordedAfter_entersBalance` FAIL, ждали 47 000, получили 50 000. Второй проходит уже сейчас — он фиксирует защиту, которую нельзя сломать.

- [ ] **Шаг 3: Перевести factsDelta на правило**

```java
    private BigDecimal factsDelta(LocalDate from, LocalDateTime fromCreatedAt, LocalDate to) {
        return eventRepository.findAllByDeletedFalseAndDateBetween(from, to).stream()
                .filter(e -> e.getFactAmount() != null)
                .filter(e -> e.getWishlistStatus() == null)
                .filter(e -> AnchorWindow.countsTowardBalance(
                        e.getDate(), e.getCreatedAt(), from, fromCreatedAt, to))
                .map(e -> e.getType() == EventType.INCOME
                        ? e.getFactAmount() : e.getFactAmount().negate())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
```

Вызывающие: `balanceAt` передаёт `anchor.getCreatedAt()`, `noAnchorFallbackAt` передаёт `EPOCH, null`.

Javadoc метода переписать: правило больше не «строго после даты якоря», а «после момента ввода якоря для дня якоря».

- [ ] **Шаг 4: Прогнать — оба теста проходят**

- [ ] **Шаг 5: Проверить мутацией**

Вернуть `.filter(e -> e.getDate().isAfter(from))` → `factOnAnchorDay_recordedAfter_entersBalance` краснеет. Вернуть правило.

- [ ] **Шаг 6: Коммит**

---

### Задача 3: Движок получает время записи

Самая объёмная задача: движок сейчас времени не видит.

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/dto/pocket/EventSnapshot.java`
- Modify: `backend/src/main/java/ru/selfin/backend/dto/pocket/PocketInput.java`
- Modify: `backend/src/main/java/ru/selfin/backend/service/PocketEngine.java` (шаг 1, строки 61-65)
- Modify: `backend/src/main/java/ru/selfin/backend/service/PocketInputAssembler.java`
- Test: `backend/src/test/java/ru/selfin/backend/service/PocketEngineTest.java`

**Interfaces:**
- Consumes: `AnchorWindow`
- Produces: `EventSnapshot` с полем `createdAt`; `PocketInput` с полем `checkpointCreatedAt`

- [ ] **Шаг 1: Переписать тест, закрепивший дефект**

`PocketEngineTest.factOnCheckpointDay_notDoubleCounted` (строка 174) утверждает ровно дефектное поведение — название прямо говорит «факт В ДЕНЬ чекпоинта не считается». Это **единственный** тест движка с фактом сегодняшней датой, проверено подсчётом.

Заменить на пару:

```java
    @Test
    @DisplayName("ANO-28: факт дня чекпоинта, записанный ДО сверки, не задваивается")
    void factOnCheckpointDay_recordedBefore_notDoubleCounted() {
        PocketInput in = base()
                .checkpointCreatedAt(LocalDateTime.of(2026, 3, 1, 10, 0))
                .events(fact(EventType.EXPENSE, TODAY, 4_000,
                        LocalDateTime.of(2026, 3, 1, 9, 0)))
                .build();
        assertThat(PocketEngine.calculate(in).currentBalance())
                .as("трата была на экране, когда вводили число из банка")
                .isEqualByComparingTo(dec(10_000));
    }

    @Test
    @DisplayName("ANO-82: факт дня чекпоинта, записанный ПОСЛЕ сверки, считается")
    void factOnCheckpointDay_recordedAfter_counts() {
        PocketInput in = base()
                .checkpointCreatedAt(LocalDateTime.of(2026, 3, 1, 10, 0))
                .events(fact(EventType.EXPENSE, TODAY, 4_000,
                        LocalDateTime.of(2026, 3, 1, 18, 0)))
                .build();
        assertThat(PocketEngine.calculate(in).currentBalance())
                .as("сверился утром, записал вечером — ввод не должен пропадать")
                .isEqualByComparingTo(dec(6_000));
    }
```

Билдеру добавить `checkpointCreatedAt(LocalDateTime)`, помощнику `fact` — перегрузку с `createdAt`. Существующая перегрузка `fact(type, date, amount)` остаётся и ставит `createdAt = null`: тогда день якоря решается «как было», и 33 остальных теста не трогаются.

- [ ] **Шаг 2: Прогнать и убедиться, что второй падает**

- [ ] **Шаг 3: Расширить записи**

`EventSnapshot` получает `LocalDateTime createdAt` последним полем. Совместимый конструктор без него оставить — прецедент уже есть в файле («щадит существующие тесты»), он ставит `createdAt = null`. `EventSnapshot.from` заполняет из `e.getCreatedAt()`.

`PocketInput` получает `LocalDateTime checkpointCreatedAt` — рядом с `checkpointDate`.

- [ ] **Шаг 4: Перевести шаг 1 движка на правило**

```java
        for (EventSnapshot e : in.events()) {
            if (e.wishlistStatus() != null || e.factAmount() == null || e.date() == null) continue;
            if (!AnchorWindow.countsTowardBalance(e.date(), e.createdAt(),
                    in.checkpointDate(), in.checkpointCreatedAt(), in.asOfDate())) continue;
            currentBalance = currentBalance.add(signed(e.type(), e.factAmount()));
        }
```

Комментарий над циклом переписать: правило теперь одно и живёт в `AnchorWindow`, список «зеркал» заменить ссылкой на него.

- [ ] **Шаг 5: Прокинуть время ввода якоря в сборщике**

`PocketInputAssembler` берёт якорь через `accountBalanceService.anchorAt(...)` — передать в `PocketInput` его `getCreatedAt()`.

- [ ] **Шаг 6: Прогнать весь `PocketEngineTest`**

Ожидается: оба новых проходят, **остальные 33 не затронуты** (у них `createdAt = null`).

- [ ] **Шаг 7: Проверить мутацией**

В `AnchorWindow` вернуть `factCreatedAt == null → true` вместо `false` → `factOnCheckpointDay_recordedBefore_notDoubleCounted` краснеет.

- [ ] **Шаг 8: Коммит**

---

### Задача 4: Дрейф между якорями

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/service/BalanceCheckpointService.java` (строки 90-96)
- Test: `backend/src/test/java/ru/selfin/backend/service/BalanceCheckpointServiceTest.java`

- [ ] **Шаг 1: Написать падающий тест**

```java
    @Test
    @DisplayName("ANO-82: дрейф считает факт дня предыдущего якоря, записанный после него")
    void drift_countsFactRecordedAfterPreviousAnchor() {
        // Два якоря: 01.09 введён в 10:00, 05.09 введён позже. Факт 01.09 записан в 18:00 —
        // то есть после ввода первого якоря, значит в его число попасть не мог и обязан
        // участвовать в дрейфе между якорями.
    }
```

Тело дописать по образцу существующих тестов класса: два чекпоинта и один факт, проверяется вычисленное значение и дельта дрейфа.

- [ ] **Шаг 2: Прогнать и убедиться, что падает**

- [ ] **Шаг 3: Перевести дрейф на правило**

```java
                BigDecimal delta = facts.stream()
                        .filter(e -> AnchorWindow.countsTowardBalance(
                                e.getDate(), e.getCreatedAt(),
                                prev.getDate(), prev.getCreatedAt(), cur.getDate()))
                        .map(e -> signed(e.getType(), e.getFactAmount()))
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
```

- [ ] **Шаг 4: Прогнать — тест проходит**

- [ ] **Шаг 5: Проверить мутацией**

Вернуть прежнюю пару `isAfter`/`!isAfter` → новый тест краснеет.

- [ ] **Шаг 6: Коммит**

---

### Задача 5: Закон сохранения на живой базе

Здесь ловится ANO-125 — как утверждение о системе, а не о методе.

**Files:**
- Create: `backend/src/test/java/ru/selfin/backend/AnchorDayIT.java`

- [ ] **Шаг 1: Написать тесты**

```java
    @Test
    @DisplayName("ANO-125: перевод в копилку в день ре-якоря не создаёт денег")
    void transferOnReanchorDay_conservesCapital() throws Exception {
        anchorDefaultAccountToday("60000");
        String fundId = createFund("Отпуск");
        BigDecimal liquidBefore = capitalService.cashLiquidAt(LocalDate.now());

        transfer(fundId, new BigDecimal("15000")).andExpect(status().isOk());

        assertThat(capitalService.cashLiquidAt(LocalDate.now()))
                .as("перемещение между своими деньгами не создаёт их — даже в день ре-якоря")
                .isEqualByComparingTo(liquidBefore);
    }

    @Test
    @DisplayName("ANO-82: факт, записанный после ре-якоря, двигает остаток")
    void factRecordedAfterReanchor_movesBalance() throws Exception {
        anchorDefaultAccountToday("60000");
        BigDecimal before = pocketBalance();

        createFactToday(new BigDecimal("5000"));

        assertThat(before.subtract(pocketBalance()))
                .as("весь день ввода не имеет права уходить в никуда")
                .isEqualByComparingTo("5000");
    }

    @Test
    @DisplayName("ANO-28: факт, записанный ДО ре-якоря, остаток не двигает")
    void factRecordedBeforeReanchor_leavesBalanceAlone() throws Exception {
        createFactToday(new BigDecimal("5000"));   // сначала факт
        anchorDefaultAccountToday("60000");        // потом сверка
        BigDecimal after = pocketBalance();

        assertThat(after)
                .as("число из банка уже содержало эту трату — защита от задвоения")
                .isEqualByComparingTo("60000");
    }
```

Оснастку (`anchorDefaultAccountToday`, `createFund`, `transfer`, `createFactToday`, `pocketBalance`) написать по образцу `FundMoneyFlowIT`, включая `@BeforeEach` с уборкой денежного состояния: без неё тесты протекают друг в друга.

- [ ] **Шаг 2: Прогнать — все три проходят**

Третий тест — самый важный: он доказывает, что защита ANO-28 уцелела. Если он зелёный только потому, что факт вообще не создался, тест бесполезен — проверить, что факт в базе есть.

- [ ] **Шаг 3: Коммит**

---

### Задача 6: Проверка на стенде

- [ ] **Шаг 1: Полный прогон** — `mvnw verify`, ожидается 350+ юнитов и 142+ интеграционных, ноль падений.

- [ ] **Шаг 2: Пересобрать стенд**

```bash
COMPOSE_PROJECT_NAME=selfin-test docker compose -f docker-compose.yml -f docker-compose.test.yml up -d --build backend
```

Дождаться `200` на `/api/v1/pocket`. Собирается несколько минут — не проверять раньше, иначе ответит старый контейнер.

- [ ] **Шаг 3: Воспроизвести исходный сценарий ANO-125**

Ре-якорь текущей суммой по дефолтному счёту, затем перевод 15 000 в копилку без счёта. Замерить капитал и ликвид до и после. **Ожидается сдвиг ноль** — до починки было +15 000.

- [ ] **Шаг 4: Проверить, что защита ANO-28 на месте**

На стенде должно остаться 12 фактов, записанных до своего якоря, и они не должны войти в остаток. Запрос:

```sql
select count(*) filter (where e.created_at > c.created_at) as после_якоря,
       count(*) filter (where e.created_at <= c.created_at) as до_якоря
from financial_events e join balance_checkpoints c on c.date = e.date
where e.is_deleted = false and e.fact_amount is not null and e.wishlist_status is null;
```

**Если «до якоря» стало 0 — защита сломана, останавливаться.**

- [ ] **Шаг 5: Прогнать скрипты**

```bash
node tools/ano50-invariants.mjs
node tools/ano50-stage5.mjs
```

Инварианты обязаны быть зелёными. `stage5` — этап кармашка.

**Внимание:** ожидания скриптов зависят от того, куда попадает следующий доход стенда относительно горизонта. Расхождение вида «сдвиг 0» может означать событие за горизонтом, а не дефект — проверить горизонт в ответе `/pocket` до того, как заводить баг.

- [ ] **Шаг 6: Восстановить стенд**

```bash
bash tools/ano50-reset-stand.sh C:/Users/Kirill/selfin-backups/ano50-fixsession-start-2026-09-12.sql
```

Сверить: кармашек 26 000, остаток 60 000, капитал 2 668 277, ликвид 210 000, якорь 2026-08-29.

- [ ] **Шаг 7: Отметиться в Linear** — комментарии в ANO-82 и ANO-125 с замерами до и после, списком мутаций и результатом скриптов. Перевести обе в Done. ANO-79 **не закрывать**: в ней остаётся объяснение дельты.
