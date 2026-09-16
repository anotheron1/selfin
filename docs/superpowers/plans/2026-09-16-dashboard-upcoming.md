# Дашборд смотрит вперёд — план реализации

> **Для агентных исполнителей:** ОБЯЗАТЕЛЬНАЯ ПОД-СКИЛЛ: `superpowers:executing-plans` или `superpowers:subagent-driven-development`. Шаги размечены чекбоксами.

**Цель:** заменить на дашборде блок «План / факт за месяц» списком «что осталось потратить» на горизонте кармашка; отчёт план-факт перенести на «Аналитику» и убрать из состояния по умолчанию.

**Архитектура:** список удерживаемого собирает сам движок кармашка — он уже обходит ровно те события и ровно с теми суммами (после ANO-155 — с непогашенным остатком). Отдельной выборки, повторяющей правила отбора, не заводим: две копии правила в этом репозитории уже расходились (ANO-82, ANO-155). Имя категории движку неизвестно и подставляется сервисом одним запросом по идентификаторам — ровно тем, что попали в список.

**Стек:** Java 21 / Spring Boot 4, React 18 / TypeScript, vitest, JUnit 5 + Testcontainers.

**Спека:** `docs/superpowers/specs/2026-09-16-dashboard-upcoming-design.md`

## Global Constraints

- Сборка: `JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw verify` **из каталога `backend`**; фронт — `npm test -- --run` и `npx tsc --noEmit` из `frontend`.
- «Сегодня» в слоях `service` и `controller` — только из внедрённого `Clock` (сторож `ClockInjectionGuardTest`; в комментариях писать `now()` без префикса класса, иначе сторож краснеет).
- Запрет 2: отчёт отклонений план-факт не в состоянии по умолчанию **ни на одном экране**.
- Правило 12: никаких упрёков и оценок — ни словом, ни цветом.
- Правило 5: состояние по умолчанию не требует правильности; пустой список — это нормальное состояние, а не призыв что-то завести.
- Правило 13: термины пользователей. На экране «осталось потратить», а не «непогашенные обязательства».
- Каждая правка поведения закрывается тестом, и каждый тест проверяется мутацией.

---

### Task 1: Движок отдаёт список того, что удержал

**Файлы:**
- Изменить: `backend/src/main/java/ru/selfin/backend/dto/pocket/PocketResultDto.java`
- Изменить: `backend/src/main/java/ru/selfin/backend/service/PocketEngine.java`
- Тест: `backend/src/test/java/ru/selfin/backend/service/PocketEngineTest.java`

**Интерфейсы:**
- Отдаёт наружу: `PocketResultDto.upcoming` — `List<UpcomingItem>`, где
  `UpcomingItem(UUID id, LocalDate date, String categoryName, BigDecimal amount, String description, boolean overdue, boolean wishlist)`.
  На выходе движка `categoryName` всегда `null` — его подставляет Task 2.

- [ ] **Шаг 1: Тест на состав списка**

В `PocketEngineTest` рядом с существующими тестами ANO-155:

```java
@Test
@DisplayName("ANO-119: список удержанного = просрочка + сегодня + будущее до горизонта")
void upcoming_holdsOverdueTodayAndFuture_notTheTail() {
    LocalDate today = LocalDate.of(2026, 9, 16);
    EventSnapshot overdue = plan(UUID.randomUUID(), today.minusDays(5), "4000", "Коммуналка");
    EventSnapshot todayPlan = plan(UUID.randomUUID(), today, "300", "Продукты");
    EventSnapshot inHorizon = plan(UUID.randomUUID(), today.plusDays(5), "23600", "Ипотека");
    EventSnapshot beyond = plan(UUID.randomUUID(), today.plusDays(40), "8000", "Автосервис");

    PocketInput in = inputWith(today, today.plusDays(12),
            List.of(todayPlan, inHorizon, beyond), List.of(overdue));

    List<PocketResultDto.UpcomingItem> out = PocketEngine.calculate(in).upcoming();

    assertThat(out).extracting(PocketResultDto.UpcomingItem::description)
            .containsExactly("Коммуналка", "Продукты", "Ипотека");
    assertThat(out.get(0).overdue()).isTrue();
    assertThat(out.get(1).overdue()).isFalse();
}
```

- [ ] **Шаг 2: Прогнать — тест не компилируется («upcoming() не существует»)**

```bash
cd backend && JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw -o test -Dtest=PocketEngineTest
```

- [ ] **Шаг 3: Добавить тип и поле в `PocketResultDto`**

Поле `List<UpcomingItem> upcoming` добавляется **последним** компонентом записи, тип — рядом с `WishlistCandidate`:

```java
/**
 * Строка блока «осталось потратить» (ANO-119): то, что движок удержал в пути денег
 * на этом горизонте. Не отдельная выборка, а побочный продукт обхода: иначе правило
 * отбора существовало бы в двух экземплярах и однажды разъехалось (ANO-82, ANO-155).
 *
 * @param amount непогашенный остаток плана, а не полная плановая сумма (ANO-155)
 * @param categoryName подставляет PocketService; движку имена категорий не видны
 * @param overdue дата прошла, а факта нет — кармашек держит это бронью
 */
public record UpcomingItem(UUID id, LocalDate date, String categoryName, BigDecimal amount,
                           String description, boolean overdue, boolean wishlist) {}
```

- [ ] **Шаг 4: Собирать список в `calculate`**

Просрочка — из `in.overdueEvents()` (там уже только непогашенные HIGH-расходы). Сегодня и будущее — там же, где считаются `todayExpenses` и `futureByDay`, тем же предикатом `isPendingPlan` и той же суммой `remainderOf`. Доходы не входят: блок про траты.

```java
List<PocketResultDto.UpcomingItem> upcoming = new ArrayList<>();
for (EventSnapshot e : in.overdueEvents()) {
    upcoming.add(new PocketResultDto.UpcomingItem(e.id(), e.date(), null,
            e.plannedAmount() != null ? e.plannedAmount() : BigDecimal.ZERO,
            e.description(), true, e.wishlistStatus() != null));
}
```

Для сегодняшних и будущих — внутри уже существующих обходов, сразу после `remainderOf`,
с `overdue = false`. Порядок итоговый: просрочка (по дате), затем остальное по дате.

- [ ] **Шаг 5: Прогнать — зелено**

- [ ] **Шаг 6: Мутация — хвост за горизонтом попадает в список**

Заменить границу обхода `trajEnd` на `trajEnd.plusMonths(12)` в сборе списка; тест обязан покраснеть на `containsExactly`. Вернуть.

- [ ] **Шаг 7: Тест на остаток частично погашенного плана**

```java
@Test
@DisplayName("ANO-119: в список идёт непогашенный остаток, а не полная сумма плана")
void upcoming_partiallySettledPlan_showsRemainder() {
    // план 20 000, факт-ребёнок 8 000 → в списке 12 000
}
```

- [ ] **Шаг 8: Мутация — вместо остатка полная сумма**

`remainderOf(e, settled)` → `e.plannedAmount()`; тест из шага 7 обязан покраснеть. Вернуть.

- [ ] **Шаг 9: Коммит**

```bash
git add backend/src/main/java/ru/selfin/backend/dto/pocket/PocketResultDto.java \
        backend/src/main/java/ru/selfin/backend/service/PocketEngine.java \
        backend/src/test/java/ru/selfin/backend/service/PocketEngineTest.java
git commit -m "feat(ano-119): движок отдаёт список удержанного на горизонте"
```

---

### Task 2: Имя категории подставляет сервис

**Файлы:**
- Изменить: `backend/src/main/java/ru/selfin/backend/service/PocketService.java`
- Тест: `backend/src/test/java/ru/selfin/backend/service/PocketServiceTest.java`

**Интерфейсы:**
- Потребляет: `PocketResultDto.upcoming` из Task 1 (с `categoryName == null`).
- Отдаёт: тот же список с заполненными именами, порядок сохранён.

- [ ] **Шаг 1: Тест**

```java
@Test
@DisplayName("ANO-119: сервис подставляет имена категорий, не меняя порядок")
void getPocket_fillsCategoryNames() {
    // движок вернул две строки с categoryName == null;
    // репозиторий по их id отдаёт события с категориями «Ипотека» и «Продукты»
    // ожидание: имена на местах, порядок прежний
}
```

- [ ] **Шаг 2: Прогнать — красный**

- [ ] **Шаг 3: Реализация**

`PocketService` получает `FinancialEventRepository`. После `calculate`:

```java
PocketResultDto result = PocketEngine.calculate(assembler.build(scope, asOfDate).input());
return withCategoryNames(result);
```

`withCategoryNames` берёт `findAllById` по идентификаторам списка (их единицы — блок читается человеком), строит `Map<UUID, String>` и пересобирает список. Синтетика (`id == null`) остаётся без имени.

- [ ] **Шаг 4: Прогнать — зелено**

- [ ] **Шаг 5: Мутация — имена подставляются не тем строкам**

Взять `values()` карты по порядку вместо `get(id)`; тест обязан покраснеть. Вернуть.

- [ ] **Шаг 6: Интеграционный тест в `PocketControllerIT`**

```java
@Test
void ano119_upcoming_containsCategoryNameAndRemainder() throws Exception {
    // план 20 000 в горизонте + факт 8 000 → одна строка: категория по имени, сумма 12 000
}
```

- [ ] **Шаг 7: Коммит**

---

### Task 3: Блок «Осталось потратить» на дашборде

**Файлы:**
- Создать: `frontend/src/lib/upcoming.ts` (группировка — чистая логика)
- Создать: `frontend/src/lib/upcoming.test.ts`
- Создать: `frontend/src/components/dashboard/UpcomingList.tsx`
- Изменить: `frontend/src/types/api.ts` (поле `upcoming` в `PocketResponse`)
- Изменить: `frontend/src/pages/Dashboard.tsx` (убрать блок прогресс-баров)

**Интерфейсы:**
- Потребляет: `PocketResponse.upcoming` из Task 2.
- Отдаёт: `groupUpcoming(items): { overdue: UpcomingItem[]; byDate: { date: string; items: UpcomingItem[] }[] }`.

- [ ] **Шаг 1: Тесты группировки**

```ts
describe('groupUpcoming (ANO-119)', () => {
    it('просроченные уходят в отдельную группу', () => { /* ... */ });
    it('остальные группируются по датам в порядке возрастания', () => { /* ... */ });
    it('пустой вход даёт пустые группы, а не падает', () => { /* ... */ });
});
```

- [ ] **Шаг 2: Прогнать — красный**
- [ ] **Шаг 3: Реализовать `groupUpcoming`**
- [ ] **Шаг 4: Прогнать — зелено**

- [ ] **Шаг 5: Компонент `UpcomingList`**

Заголовок «ОСТАЛОСЬ ПОТРАТИТЬ», под ним — группа «раньше по плану» (если есть) и группы по датам. Строка: дата (в группе — один раз), категория, описание, сумма. Никаких бейджей, полосок и цветовой оценки. Хотелка помечается нейтральным словом «хотелка» рядом с категорией.

Пустое состояние — одна строка: «До {дата горизонта} плановых трат не осталось».

- [ ] **Шаг 6: Убрать блок прогресс-баров из `Dashboard.tsx`**

Удаляются: секция «ПЛАН / ФАКТ ЗА МЕСЯЦ», функции `getBarState`, `getBarMax`, импорт `ForecastSparkline` (если больше не нужен), вызов `fetchDashboard`, если `progressBars` был его единственным потребителем.

- [ ] **Шаг 7: Сторож по исходнику**

```ts
it('на дашборде не осталось слов-вердиктов', () => {
    const src = readFileSync(new URL('../pages/Dashboard.tsx', import.meta.url), 'utf8');
    for (const word of ['Не выполнено', 'Перерасход', 'Не запланировано', 'В процессе']) {
        expect(src).not.toContain(word);
    }
});
```

- [ ] **Шаг 8: `npm test -- --run` и `npx tsc --noEmit` — зелено**
- [ ] **Шаг 9: Мутация — вернуть одно слово-вердикт в Dashboard.tsx, сторож обязан покраснеть**
- [ ] **Шаг 10: Коммит**

---

### Task 4: Отметить исполнение прямо из строки

**Файлы:**
- Изменить: `frontend/src/components/dashboard/UpcomingList.tsx`
- Изменить: `frontend/src/pages/Dashboard.tsx` (проброс `refetch`)

**Интерфейсы:**
- Потребляет: существующий `FactCreateSheet` (`planId`, `planDescription`, `planPriority`, `open`, `onClose`, `onCreated`).

- [ ] **Шаг 1:** В строке — кнопка «записать факт», открывающая `FactCreateSheet` для этого плана.
- [ ] **Шаг 2:** `onCreated` → `refetch` дашборда: кармашек и список пересчитываются вместе.
- [ ] **Шаг 3:** `npx tsc --noEmit` — зелено.
- [ ] **Шаг 4:** Проверка руками на стенде (Task 7) — здесь только код.
- [ ] **Шаг 5: Коммит**

---

### Task 5: Отчёт план-факт переезжает на «Аналитику» и не открывается сам

**Файлы:**
- Изменить: `frontend/src/pages/Analytics.tsx`
- Создать: `frontend/src/components/analytics/CategoryProgressSection.tsx` (перенесённые прогресс-бары без вердиктов)

- [ ] **Шаг 1:** Перенести разметку прогресс-баров из `Dashboard.tsx` в новый компонент, **без** бейджей и без цветовой оценки; прогноз и спарклайн сохраняются.
- [ ] **Шаг 2:** На «Аналитике» обе секции (`PlanFactSection` и новая) убрать под явное действие: свёрнутый раздел «Как прошёл месяц» с кнопкой раскрытия. По умолчанию — свёрнут. Этим закрывается ANO-122.
- [ ] **Шаг 3:** Сторож: на «Аналитике» отчёт не рендерится без раскрытия.

```ts
it('отчёт план-факт на Аналитике не в состоянии по умолчанию', () => {
    const src = readFileSync(new URL('../pages/Analytics.tsx', import.meta.url), 'utf8');
    expect(src).toMatch(/useState\(false\)/);        // раздел свёрнут
    expect(src).toMatch(/showPlanFact &&/);          // рендер только по раскрытию
});
```

- [ ] **Шаг 4:** Тесты и `tsc` — зелено.
- [ ] **Шаг 5:** Завести задачу: на «Аналитике» теперь два представления одного и того же (таблица и бары) — выбрать одно.
- [ ] **Шаг 6: Коммит**

---

### Task 6: Правила продукта

**Файлы:**
- Изменить: `docs/superpowers/specs/2026-09-01-product-rules.md`

- [ ] **Шаг 1:** Запрет 1 переписать: запрещены **назначаемые** лимиты по категориям и оценка их соблюдения; показ собственных плановых трат и их суммы под запрет не подпадает. Добавить дату уточнения и ссылку на спеку.
- [ ] **Шаг 2:** В раздел «Что осталось непроверенным» добавить строку про дискреционные категории: список предстоящего арифметически равен остатку рамки и может работать как разрешение; форма ослабляет механизм, но не снимает; проверять на живых пользователях.
- [ ] **Шаг 3: Коммит**

---

### Task 7: Стенд, замер, PR

- [ ] **Шаг 1:** Поднять стенд: `COMPOSE_PROJECT_NAME=selfin-test docker compose -f docker-compose.yml -f docker-compose.test.yml up -d --build backend`, фронт — dev-сервер против `http://localhost:8081/api/v1`.
- [ ] **Шаг 2:** Снять «до и после»: что показывал дашборд 16-го числа и что показывает теперь. Числа записать в план, а не пересказать.
- [ ] **Шаг 3:** Проверить руками: запись факта из строки убирает её из списка и двигает кармашек.
- [ ] **Шаг 4:** Проверить пустое состояние (горизонт без плановых трат).
- [ ] **Шаг 5:** Вернуть стенд в исходное состояние, остановить контейнеры.
- [ ] **Шаг 6:** Полный прогон: `./mvnw verify` из `backend`, `npm test -- --run` и `npx tsc --noEmit` из `frontend`.
- [ ] **Шаг 7:** PR с разделом «Замер на стенде» и списком мутаций.

---

# Выполнено

(заполняется по ходу)
