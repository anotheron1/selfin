# Инъекция Clock — план реализации (ANO-39)

> **Для агентов:** ОБЯЗАТЕЛЬНЫЙ САБ-СКИЛ: `superpowers:subagent-driven-development` либо `superpowers:executing-plans`. Шаги отмечаются чекбоксами.

**Цель:** «сегодня» в слоях `service` и `controller` становится подменяемым, а прямой вызов — запрещённым тестом-сторожем.

**Архитектура:** бин `Clock` уже есть в `AppConfig`, `FinancialEventService` уже так устроен — это образец. В 14 оставшихся классов внедряется `Clock`, 39 вызовов `now()` получают аргумент. Сторож читает исходники и падает на любом вызове без аргумента.

**Спека:** `docs/superpowers/specs/2026-09-13-clock-injection-design.md`.

## Глобальные ограничения

- **Поведение продукта не меняется ни в одном месте.** `Clock.systemDefaultZone()` даёт то же время. Полный прогон обязан остаться зелёным **без правок ожиданий**. Поехавший тест — находка, а не издержка: он зависел от календаря, и это отдельный разбор, а не повод подкрутить число.
- **Сущности и DTO не трогаем.** В `model` 18 вызовов, в `dto` 2 — это штампы `createdAt`/`updatedAt` и `ErrorResponse.timestamp`. JPA-сущность создаётся не Spring-ом, внедрить в неё `Clock` нечем, и штамп «когда запись создана» — факт о записи, а не решение продукта.
- **Список исключений сторожа пуст.** Сторож с исключениями превращается в счётчик долга. Если после миграции список нужен — миграция не закончена.
- **Из 133 тестовых вызовов переписываются два** — названные в задаче. Остальные законны: в оснастке «сегодня» выбирает сам тест.
- Каждый новый тест проверяется мутацией.

## Что мигрируется

| класс | вызовов |
|---|---|
| `service/TargetFundService` | 10 |
| `service/AccountService` | 6 |
| `service/CapitalService` | 4 |
| `service/RecurringRuleService` | 3 |
| `service/BaselineTimelineBuilder` | 3 |
| `controller/AnalyticsController` | 3 |
| `service/FundPlannerService` | 2 |
| `controller/PocketController` | 2 |
| `service/WishlistSimulationService` | 1 |
| `service/WishlistConversionService` | 1 |
| `service/PredictionService` | 1 |
| `service/BudgetSnapshotService` | 1 |
| `controller/BudgetSnapshotController` | 1 |
| `service/BalanceCheckpointService` | 1 — `LocalDateTime.now()` |

Итого 39 вызовов в 14 классах.

## Запуск тестов

Из каталога `backend`:

```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw verify
```

Сторож отдельно:

```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -Dtest=ClockInjectionGuardTest
```

## Структура файлов

| файл | ответственность |
|---|---|
| `test/.../architecture/ClockInjectionGuardTest.java` | **новый.** Запрет прямых вызовов в `service` и `controller` |
| 11 сервисов и 3 контроллера | **изменяются.** Поле `Clock clock`, вызовы с аргументом |
| 10 тестовых файлов | **изменяются.** Конструкторы получают `Clock.systemDefaultZone()` |
| `test/.../service/FundPlannerServiceTest.java` | **изменяется.** Два теста на фиксированный `Clock` 31-го числа |

---

### Задача 1: Сторож

Идёт первым: он и есть падающий тест, который ведёт всю миграцию.

**Files:**
- Create: `backend/src/test/java/ru/selfin/backend/architecture/ClockInjectionGuardTest.java`

**Interfaces:**
- Consumes: ничего
- Produces: тест, падающий на любом `now()` без аргумента в `service` и `controller`

- [ ] **Шаг 1: Написать сторожа**

```java
package ru.selfin.backend.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ANO-39. «Сегодня» в слоях service и controller берётся ТОЛЬКО из внедрённого {@code Clock}.
 *
 * <p>Счётчик прямых вызовов рос 31 → 36 → 38, и каждое новое место добавляла очередная
 * починка: запрета не было, {@code Clock} в большинстве сервисов не инжектился, и человек
 * писал то, что работает. Миграция без запрета через месяц дала бы 43.
 *
 * <p>Пока «сегодня» невозможно подменить, календарно-зависимую логику нельзя проверить
 * детерминированно — только «повезло с датой прогона». Задача заведена именно по такому
 * случаю: два теста {@code FundPlannerServiceTest} падали бы в последний день ЛЮБОГО месяца.
 *
 * <p><b>Список исключений отсутствует, и это условие смысла.</b> Сторож с исключениями
 * превращается в счётчик долга: каждый следующий вызов попадает в список «пока так», и
 * запрета не остаётся. Если сюда захотелось добавить исключение — значит место надо
 * мигрировать, а не разрешать.
 *
 * <p>Сущности ({@code model}) и DTO под запрет не подпадают: там штамп «когда запись
 * создана» — факт о записи, а не решение продукта, и в JPA-сущность {@code Clock} не
 * внедряется технически.
 */
class ClockInjectionGuardTest {

    /** {@code LocalDate.now()} и {@code LocalDateTime.now()} БЕЗ аргумента. */
    private static final Pattern DIRECT_NOW =
            Pattern.compile("\\bLocalDate(Time)?\\.now\\(\\s*\\)");

    private static final List<String> GUARDED_PACKAGES =
            List.of("src/main/java/ru/selfin/backend/service",
                    "src/main/java/ru/selfin/backend/controller");

    @Test
    @DisplayName("ANO-39: в service и controller нет прямых вызовов now()")
    void noDirectNowCalls() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (String pkg : GUARDED_PACKAGES) {
            Path root = Path.of(pkg);
            assertThat(root)
                    .as("путь к исходникам съехал — сторож молчал бы, ничего не проверяя")
                    .exists();
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                    for (int i = 0; i < lines.size(); i++) {
                        if (DIRECT_NOW.matcher(lines.get(i)).find()) {
                            offenders.add(file.getFileName() + ":" + (i + 1) + "  " + lines.get(i).strip());
                        }
                    }
                }
            }
        }

        assertThat(offenders)
                .as("«сегодня» берётся из внедрённого Clock — иначе логику нельзя проверить "
                        + "детерминированно. Не добавляйте сюда исключение: мигрируйте место.")
                .isEmpty();
    }
}
```

- [ ] **Шаг 2: Прогнать — сторож падает, перечислив все 39 мест**

Число в отчёте — контрольное: если их меньше 39, регулярное выражение или пути неверны, и сторож проверяет не то.

**Сторож не разбирает комментарии, и это осознанно.** Проверено: из 39 мест в комментарии ровно одно — `NB ANO-39` в `TargetFundService:354`, и оно удаляется вместе с миграцией, потому что описывает состояние, которого больше не будет.

Отсюда правило для будущих комментариев: **писать `now()` без префикса класса**, иначе javadoc с примером уронит сборку. Плата невелика, а альтернатива — парсер Java в тесте либо дыра «в комментарии можно», через которую вызов однажды и вернётся.

- [ ] **Шаг 3: Коммит сторожа отдельно, красным**

Отдельный коммит нужен, чтобы в истории было видно исходное состояние — 39 мест списком.

```bash
git add backend/src/test/java/ru/selfin/backend/architecture/ClockInjectionGuardTest.java
git commit -m "test(ano-39): сторож прямых вызовов now() — пока красный, 39 мест"
```

---

### Задача 2: Сервисы

**Files:**
- Modify: 11 сервисов из таблицы выше
- Modify: 10 тестовых файлов, конструирующих их вручную

**Interfaces:**
- Consumes: бин `Clock` из `AppConfig`
- Produces: поле `private final Clock clock` в каждом сервисе; конструктор Lombok `@RequiredArgsConstructor` подхватывает его сам

- [ ] **Шаг 1: Мигрировать сервисы по одному**

Для каждого: добавить `import java.time.Clock;`, поле `private final Clock clock;` рядом с остальными зависимостями, заменить `LocalDate.now()` → `LocalDate.now(clock)` (в `BalanceCheckpointService` — `LocalDateTime.now(clock)`).

Образец — `FinancialEventService`: поле объявлено в том же блоке `private final`, `@RequiredArgsConstructor` делает остальное.

В `TargetFundService` удалить `NB ANO-39`-комментарий: он описывает состояние, которого больше нет.

- [ ] **Шаг 2: Починить конструкторы в тестах**

Десять вызовов `new XxxService(...)` получают последним аргументом `Clock.systemDefaultZone()` — поведение тестов не меняется, меняется только сигнатура.

- [ ] **Шаг 3: Прогнать полный юнит-прогон**

Ожидается: ноль падений **без правок ожиданий**. Поехавшее число — находка, разбирать отдельно, а не подкручивать.

- [ ] **Шаг 4: Коммит**

---

### Задача 3: Контроллеры

**Files:**
- Modify: `AnalyticsController`, `PocketController`, `BudgetSnapshotController`

- [ ] **Шаг 1: Мигрировать три контроллера**

Те же правки. Тестовых конструкторов у них нет — их создаёт Spring, MockMvc-тесты не трогаются.

- [ ] **Шаг 2: Прогнать сторожа — зелёный**

Это момент, ради которого всё делалось: список пуст.

- [ ] **Шаг 3: Проверить сторожа мутацией**

Вписать в любой сервис строку `LocalDate x = LocalDate.now();` → сторож краснеет и называет файл и строку. Убрать.

Вторая мутация: подменить `GUARDED_PACKAGES` на несуществующий путь → падает проверка `exists()`, а не «список пуст». Без неё сторож, потерявший путь, молчал бы и выглядел зелёным.

- [ ] **Шаг 4: Полный прогон и коммит**

---

### Задача 4: Два календарных теста снова проверяют заявленное

**Files:**
- Modify: `backend/src/test/java/ru/selfin/backend/service/FundPlannerServiceTest.java`

- [ ] **Шаг 1: Перевести два теста на фиксированный Clock**

`firstMonthExcludesPastPlannedIncome` и `firstMonthFactExpensesIncludesPastExecuted` получают `Clock.fixed` на **31-е число** — тот самый день, в который они когда-то упали. В PR #28 их залечили выбором безопасного дня; теперь лечится причина.

```java
    /** 31-е число: день, в который эти тесты падали до ANO-39 (красная сборка 31.07). */
    private static final Clock JANUARY_31 =
            Clock.fixed(LocalDate.of(2026, 1, 31).atStartOfDay(ZoneId.systemDefault()).toInstant(),
                    ZoneId.systemDefault());
```

Сервис в этих тестах конструируется с `JANUARY_31`, а даты событий задаются относительно `LocalDate.of(2026, 1, 31)` — «завтра» уезжает в февраль, и тест обязан это пережить.

- [ ] **Шаг 2: Прогнать — оба зелёные**

- [ ] **Шаг 3: Проверить мутацией**

Вернуть тестам системный `Clock` и выставить дату события через `LocalDate.now().plusDays(1)` — тест снова станет зависеть от дня прогона. Проверяется глазами на 31-м числе: с фиксированным часами он зелёный, с системными — падает. Вернуть.

- [ ] **Шаг 4: Коммит**

---

### Задача 5: Полная проверка и отметка

- [ ] **Шаг 1: Полный прогон** — `mvnw verify`. Ожидается 372+ юнитов и 151+ интеграционных, ноль падений, числа не менялись.

- [ ] **Шаг 2: Пересобрать стенд и сверить эталон**

```bash
COMPOSE_PROJECT_NAME=selfin-test docker compose -f docker-compose.yml -f docker-compose.test.yml up -d --build backend
```

Кармашек 26 000, остаток 60 000, капитал 2 668 277, ликвид 210 000, якорь 2026-08-29. **Любое расхождение — регрессия**: эта задача не имеет права двигать числа.

- [ ] **Шаг 3: Прогнать `node tools/ano50-invariants.mjs`** — зелёный.

- [ ] **Шаг 4: Отметиться в ANO-39** — комментарий с числом мигрированных мест, результатом сторожа и мутациями. Перевести в Done.
