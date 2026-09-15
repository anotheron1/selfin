# План гасится фактами, а не замещается ими — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** план удерживает непогашенный остаток, а не исчезает от первого факта; факт нельзя датировать будущим.

**Architecture:** `EventSnapshot` начинает нести `parentEventId`, и движок связывает факты с планами. `isPendingPlan` перестаёт быть признаком «не тронут» и становится вопросом «есть ли остаток». Прогноз ANO-80 повторяет то же правило — синхронность закрепляется сторожевым тестом. Запрет будущего факта ставится на вводе, окно `asOfDate` не трогается.

**Tech Stack:** Java 21 / Spring Boot, JPA, JUnit 5 + AssertJ + Mockito, Testcontainers (failsafe); React + TypeScript + vitest.

**Spec:** `docs/superpowers/specs/2026-09-15-plan-remainder-design.md`

## Global Constraints

* **Сборка идёт из `backend/`:** `cd backend && JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test`. `verify` добавляет интеграционные; `mvnw test` их НЕ запускает.
* **После правки сигнатур record'ов:** `rm -rf backend/target/test-classes`. `EventSnapshot` получает новый компонент — Maven пропустит перекомпиляцию тестов и ответит `BUILD SUCCESS` на сломанном коде.
* **Время только через `Clock`** (ANO-39). Прямой `LocalDate.now()` в production-коде уронит `ClockInjectionGuardTest`.
* **Каждый тест проверяется мутацией.** Зелёный без мутации проверкой не считается.
* **Мутация, тождественная оригиналу, — не мутация.** Ломать надо смысл, а не запись.
* **Откат мутации только по закоммиченному:** зелено → коммит → мутация → `git checkout -- <файл>`. Многострочные шаблоны `perl -0pi` писать с `\r?\n` — после `checkout` файл возвращается с CRLF, и шаблон с `\n` молча не матчится. После `perl` — обязательный `grep` на результат.
* **Два места одного правила.** `PocketEngine` и `PredictionService` держат предикат «план ещё в пути денег». Расхождение уже измеряли: 16 000 вместо 12 000 по «Авто». Правка идёт в оба места одним движением, синхронность закрепляется сторожем.
* **Правила продукта:** `docs/superpowers/specs/2026-09-01-product-rules.md`. Тексты — без упрёков (правило 12), без обещания точности (правило 3), словарём пользователя (правило 13).
* **Компонентных тестов на фронте нет.** Логику выносить в чистую функцию в `lib/`, отрисовку проверять на стенде.
* **Стенд:** бэкенд из исходников на 8090, база `localhost:5433`; Vite слушает только IPv6 — ходить на `localhost`, не на `127.0.0.1`.

---

### Task 1: Факт нельзя датировать будущим

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/service/FinancialEventService.java`
- Test: `backend/src/test/java/ru/selfin/backend/service/FinancialEventServiceTest.java`

**Interfaces:**
- Produces: `createLinkedFact` и `createStandaloneFact` отвечают 400 на `date > сегодня`.

- [ ] **Step 1: Падающие тесты**

Три формы, каждая со своей приметой — завтрашняя дата, сегодняшняя (проходит), далёкое будущее:

```java
    @Test
    void createLinkedFact_futureDate_throws400_andSavesNothing() {
        // Факт значит «деньги ушли». В будущем они уйти не могли.
        assertThatThrownBy(() -> service.createLinkedFact(planId,
                new FactCreateDto(TODAY.plusDays(1), new BigDecimal("5480"), null, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("не может быть в будущем");
        verify(eventRepo, never()).save(any());
    }

    @Test
    void createLinkedFact_todayDate_isAccepted() { /* граница включительно */ }

    @Test
    void createStandaloneFact_futureDate_throws400() { /* внеплановая трата — то же правило */ }
```

Точный текст сообщения согласовать с правилом 13 (словарём пользователя), а не оставлять английскую заглушку.

- [ ] **Step 2: Прогнать — должны падать**

- [ ] **Step 3: Проверка в обоих методах**

Одна приватная проверка на оба входа, «сегодня» из `Clock`. Не две копии: разъедутся при первой же правке.

- [ ] **Step 4: Зелено, коммит, мутации**

| мутация | обязан покраснеть |
|---|---|
| убрать проверку из `createLinkedFact` | тест на связанный факт |
| убрать проверку из `createStandaloneFact` | тест на внеплановый |
| `isAfter` → `isBefore` | тест на завтрашнюю дату |
| граница `>=` вместо `>` | тест на сегодняшнюю дату |

Последняя — не формальность: сегодня факт записывать можно, и это ровно тот случай, ради которого правило и вводится.

---

### Task 2: Снимок несёт родителя, движок считает остаток

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/dto/pocket/EventSnapshot.java`
- Modify: `backend/src/main/java/ru/selfin/backend/service/PocketEngine.java`
- Test: `backend/src/test/java/ru/selfin/backend/service/PocketEngineTest.java`

**Interfaces:**
- Produces: `EventSnapshot.parentEventId()`; `PocketEngine` удерживает `max(0, plannedAmount − погашенное)`.

- [ ] **Step 1: Падающие тесты на остаток**

Четыре формы, каждая ломается своей мутацией:

```java
    @Test
    void partialFact_leavesRemainderReserved() {
        // Продукты 20 000 на будущую дату, чек 300 сегодня.
        // Удерживается 19 700 — не ноль и не 20 000.
    }

    @Test
    void fullFact_releasesPlanEntirely() {
        // Факт 6 000 на план 6 000 — план больше не удерживается.
    }

    @Test
    void overpay_givesZeroRemainder_notNegative() {
        // Факт 7 300 на план 6 000 — удержание ноль, а не −1 300.
        // Иначе переплата ВЕРНЁТ деньги в кармашек.
    }

    @Test
    void planOwnFactAmount_countsAsSettled() {
        // Легаси-путь PATCH /events/{id}/fact пишет число в сам план.
        // Оно обязано считаться погашением наравне с детьми, иначе удержим дважды.
    }
```

- [ ] **Step 2: Прогнать — должны падать**

Компиляция упадёт первой: `parentEventId` у снимка нет.

- [ ] **Step 3: Добавить `parentEventId` в снимок**

Новый компонент в record, `EventSnapshot.from` переносит `e.getParentEventId()`. Старые вспомогательные конструкторы сохранить — на них стоят существующие тесты.

- [ ] **Step 4: Считать погашенное в движке**

Перед построением траектории — карта «план → сколько погашено»:

```java
        // ANO-155: факт не замещает план, а гасит его на свою сумму. Складываем оба
        // источника погашения: детей-фактов и собственный factAmount плана (легаси-путь
        // PATCH). Иначе запись обоими путями удержала бы расход дважды.
        Map<UUID, BigDecimal> settled = new HashMap<>();
        for (EventSnapshot e : in.events()) {
            if (e.eventKind() == EventKind.FACT && e.parentEventId() != null && e.factAmount() != null) {
                settled.merge(e.parentEventId(), e.factAmount(), BigDecimal::add);
            }
        }
```

Остаток:

```java
    /** Непогашенная часть плана: факт гасит обязательство на свою сумму (ANO-155). */
    private static BigDecimal remainderOf(EventSnapshot e, Map<UUID, BigDecimal> settled) {
        BigDecimal planned = e.plannedAmount() != null ? e.plannedAmount() : BigDecimal.ZERO;
        BigDecimal paid = settled.getOrDefault(e.id(), BigDecimal.ZERO)
                .add(e.factAmount() != null ? e.factAmount() : BigDecimal.ZERO);
        return planned.subtract(paid).max(BigDecimal.ZERO);
    }
```

- [ ] **Step 5: Переписать предикат и суммы**

`isPendingPlan` становится «план с непогашенным остатком»:

```java
    /** План с непогашенным остатком — он ещё стоит в пути денег (ANO-155). */
    private static boolean isPendingPlan(EventSnapshot e, Map<UUID, BigDecimal> settled) {
        return e.eventKind() == EventKind.PLAN
                && e.status() != EventStatus.CANCELLED
                && remainderOf(e, settled).signum() > 0;
    }
```

Условие `status == PLANNED` уходит: частично погашенный план сегодня носит `EXECUTED`, и по старому предикату выпал бы. Вместо него — явное исключение отменённых.

Суммы берутся из остатка в двух местах: расход сегодняшнего дня (шаг 2) и [строка 132](../../../backend/src/main/java/ru/selfin/backend/service/PocketEngine.java) — единственное место, где берётся сумма будущего дня.

Резерв просрочки не трогаем: выборка исключает планы с фактами-детьми, там остаток всегда равен полной сумме.

- [ ] **Step 6: Зелено, коммит, мутации**

| мутация | обязан покраснеть |
|---|---|
| `remainderOf` возвращает `planned` | `partialFact_leavesRemainderReserved` |
| `remainderOf` возвращает ноль при любом факте | тот же тест |
| убрать `.max(ZERO)` | `overpay_givesZeroRemainder_notNegative` |
| не складывать собственный `factAmount` плана | `planOwnFactAmount_countsAsSettled` |
| в строке 132 оставить `plannedAmount` | `partialFact_leavesRemainderReserved` |

Последняя важна отдельно: предикат может быть верным, а сумма всё равно браться старая.

---

### Task 3: Статус значит «погашен полностью»

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/service/FinancialEventService.java`
- Test: рядом с тестами `createLinkedFact`

**Interfaces:**
- Produces: `EXECUTED` ставится, когда остаток дошёл до нуля; удаление факта возвращает `PLANNED`.

- [ ] **Step 1: Падающие тесты**

```java
    @Test
    void partialFact_keepsPlanPlanned() {
        // Чек 300 на план 20 000 — план ещё не исполнен.
    }

    @Test
    void factCoveringPlan_marksExecuted() { /* 6 000 на 6 000 */ }

    @Test
    void overpayingFact_marksExecuted() { /* 7 300 на 6 000 — тоже закрыт */ }
```

- [ ] **Step 2: Считать сумму детей при записи факта**

`createLinkedFact` перестаёт ставить `EXECUTED` безусловно: статус переключается, только когда сумма фактов покрыла план. Симметрично — удаление факта возвращает план в `PLANNED`, если остаток снова появился.

- [ ] **Step 3: Проверить потребителей статуса**

`AnalyticsService` фильтрует по `PLAN + EXECUTED`; Бюджет и план-факт показывают исполненные строки. Прогнать их тесты и убедиться, что смысл «исполнен» не поехал. Если поехал — это находка, а не повод подгонять число.

- [ ] **Step 4: Зелено, коммит, мутации**

---

### Task 4: Прогноз едет вместе

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/service/PredictionService.java`
- Test: `backend/src/test/java/ru/selfin/backend/service/PredictionServiceTest.java`
- Test: новый сторож `PlanRemainderSingleSourceTest`

**Interfaces:**
- Produces: `sumHeldPlans` вычитает из нормы остаток, а не полную плановую сумму.

- [ ] **Step 1: Падающий тест**

```java
    @Test
    void sumHeldPlans_partiallySettledPlan_subtractsOnlyRemainder() {
        // План 20 000, чек 300 → норма вычитает 19 700.
        // Вычтет 20 000 — прогноз оптимистичнее правды; вычтет ноль — трата посчитана дважды.
    }
```

- [ ] **Step 2: Привести предикат к правилу остатка**

Комментарий «слово в слово» в `PredictionService` обновить: он теперь отсылает к остатку, а не к `factAmount == null`.

- [ ] **Step 3: Сторож на единственность правила**

По образцу `ForecastThresholdSingleSourceTest` — тест, читающий исходники и падающий, если правило остатка объявлено больше одного раза либо если `PredictionService` снова завёл собственную копию предиката.

- [ ] **Step 4: Зелено, коммит, мутации**

| мутация | обязан покраснеть |
|---|---|
| `sumHeldPlans` берёт `plannedAmount` | тест Шага 1 |
| завести копию предиката в `PredictionService` | сторож Шага 3 |

---

### Task 5: Фронт не даёт поставить факту будущую дату

**Files:**
- Modify: `frontend/src/components/FactCreateSheet.tsx`
- Modify: `frontend/src/components/Fab.tsx`
- Modify: `frontend/src/lib/` — новая чистая функция и её тест

**Interfaces:**
- Produces: `canRecordFact(dateIso, todayIso): boolean`.

- [ ] **Step 1: Падающий тест на чистую функцию**

Границы: вчера — да, сегодня — да, завтра — нет. Три формы.

- [ ] **Step 2: Функция и `max` у поля**

`max` = сегодня у поля даты в «записать факт». Функция гасит кнопку — как в ANO-138: форма не предлагает того, что сервер отвергнет.

- [ ] **Step 3: Плюсик перестаёт наследовать будущую дату**

Сегодня факт получает дату создаваемого события. Если событие датировано будущим, факт обязан либо быть недоступен, либо записаться сегодняшним числом. Выбор зафиксировать в спеке при реализации — молча наследовать нельзя.

- [ ] **Step 4: `npm test`, `tsc`, коммит, мутация**

Мутация на границе: `<=` вместо `<` — краснеет тест «завтра нельзя».

---

### Task 6: Воспроизведение из тела задачи

**Files:**
- Modify: `backend/src/test/java/ru/selfin/backend/PocketControllerIT.java`

- [ ] **Step 1: Три теста**

Оба числа из задачи и случай «продукты»:

```java
    // факт 5 480 на план 6 000 → кармашек не меняется: сколько собирались, столько и потратили
    // факт 7 300 на план 6 000 → кармашек падает на 1 300
    // продукты 20 000, два чека по 300 → удерживается 19 400
```

Третий — не дубль: он ловит дефект Б, который срабатывает без всяких будущих дат.

- [ ] **Step 2: Полный прогон**

```bash
cd backend && rm -rf target/test-classes
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw verify
cd ../frontend && npm test && npx tsc --noEmit
```

Записать числа: юниты, интеграционные, фронтовые.

---

### Task 7: Стенд, Linear, PR

- [ ] **Step 1: Замер до правки**

Поднять бэкенд из `main` на эталонной базе, записать кармашек, минимум и разбивку. Числа понадобятся для сравнения — «до/после» проверяется замером, а не рассуждением.

- [ ] **Step 2: Прогон по экрану**

Сценарий: план на будущую неделю → «записать факт» на часть суммы → кармашек **упал** на сумму факта, остаток продолжает удерживаться. Затем добить факт до полной суммы → план исчез из траектории.

Отдельно: попытка поставить факту завтрашнюю дату — поле не даёт.

- [ ] **Step 3: Стенд вернуть к эталону**

Капитал 2 668 277, ликвид 210 000, остаток 60 000, якорь 29.08. Заведённые для проверки записи удалить.

- [ ] **Step 4: Linear и PR**

ANO-155 → In Review с разбором: два дефекта вместо одного, снятая неточность про путь в Бюджете, ландшафт и почему правило именно такое.

После открытия — дождаться Codex и разобрать замечания до мёржа.

---

## Заметки для исполнителя

**Не чинить по дороге.** Показ ошибки человеку — ANO-141: 400 из Задачи 1 человек не увидит, пока она не сделана. Признак «счёт или ориентир» и действие «закрыть остаток» — за рамками, решаются после того, как станет видно, часто ли нужны.

**Датирование периода концом** — правило, а не механика. Продолжительность плана не заводить: она даёт тот же эффект дороже.

**Факты за окном событий.** События грузятся от даты якоря; факт раньше якоря в окно не попадёт, и остаток окажется больше реального. Ошибка консервативная, принята сознательно — но если тест на неё наткнётся, это не повод менять окно.

**Осторожно с `status`.** После Задачи 3 `EXECUTED` значит «погашен полностью». Любое место, читающее статус как «тронут», поедет — и это находка, а не повод подгонять.
