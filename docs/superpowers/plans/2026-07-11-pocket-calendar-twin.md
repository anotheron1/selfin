# Календарь-близнец кармашка (ANO-14) — Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Траектория кармашка как график на Dashboard + скоуп «до 2-го дохода» + алерт-сторож из minPoint + выпил зарплатных расчётов DashboardService.

**Architecture:** Всё питается одним `GET /api/v1/pocket` (PocketEngine — единственная истина). Бэкенд получает новый скоуп SECOND_INCOME и FallbackKind; фронт получает чистый SVG-компонент графика с логикой в тестируемых хелперах; DashboardService худеет до progressBars.

**Tech Stack:** Spring Boot 4 / Java 21 / JUnit 5 + Mockito + Testcontainers; React 18 + TS + vitest; рукописный SVG (без чартовых библиотек).

**Спека:** `docs/superpowers/specs/2026-07-11-pocket-calendar-twin-design.md` (одобрена ревью 2026-07-11).
**Ветка:** `feature/pocket-calendar-twin`. Тесты бэка: `JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw -pl backend test` (IT требуют Docker). Тесты фронта: `cd frontend && npx vitest run`.

---

## Chunk 1: Backend — SECOND_INCOME + FallbackKind + recurring-фикс

### Task 1: PocketScope.Type += SECOND_INCOME

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/dto/pocket/PocketScope.java`
- Test: `backend/src/test/java/ru/selfin/backend/dto/pocket/PocketScopeTest.java`

- [ ] **1.1** Тест: `parse("SECOND_INCOME")` → `Type.SECOND_INCOME`, months/date null; кейс в существующий invalid-парам-тест не попадает. Запустить — FAIL (enum-констант нет).
- [ ] **1.2** Реализация: в enum `Type` добавить `SECOND_INCOME`; в `parse` после NEXT_INCOME-ветки:

```java
if (raw.equals("SECOND_INCOME")) {
    return new PocketScope(Type.SECOND_INCOME, null, null);
}
```

- [ ] **1.3** `mvnw -pl backend test -Dtest=PocketScopeTest` → PASS. Коммит `feat(pocket): SECOND_INCOME scope parsing`.

### Task 2: FallbackKind вместо boolean в PocketInput + лейблы движка

**Files:**
- Create: `backend/src/main/java/ru/selfin/backend/dto/pocket/FallbackKind.java`
- Modify: `backend/src/main/java/ru/selfin/backend/dto/pocket/PocketInput.java` (поле `boolean horizonFallback` → `FallbackKind fallbackKind`)
- Modify: `backend/src/main/java/ru/selfin/backend/service/PocketEngine.java` (`horizonLabel`, маппинг `Horizon.fallback = kind != NONE`)
- Test: `backend/src/test/java/ru/selfin/backend/service/PocketEngineTest.java`

- [ ] **2.1** Тесты на лейблы (по образцу существующих horizonLabel-тестов): SECOND_INCOME + NONE → `"до 2-го дохода dd.MM"`; SECOND_INCOME + SECOND_NOT_FOUND → `"до dd.MM (второй доход не найден)"`; SECOND_INCOME + NO_INCOMES и NEXT_INCOME + NO_INCOMES → `"30 дней вперёд (нет плановых доходов)"`; `Horizon.fallback == true` при kind != NONE. FAIL.
- [ ] **2.2** Реализация:

```java
public enum FallbackKind { NONE, NO_INCOMES, SECOND_NOT_FOUND }
```

`horizonLabel`:

```java
private static String horizonLabel(PocketInput in) {
    if (in.fallbackKind() == FallbackKind.SECOND_NOT_FOUND)
        return "до " + DD_MM.format(in.horizonEnd()) + " (второй доход не найден)";
    if (in.fallbackKind() == FallbackKind.NO_INCOMES)
        return "30 дней вперёд (нет плановых доходов)";
    return switch (in.scope().type()) {
        case NEXT_INCOME -> "до дохода " + DD_MM.format(in.horizonEnd());
        case SECOND_INCOME -> "до 2-го дохода " + DD_MM.format(in.horizonEnd());
        case MONTHS -> in.scope().months() + " мес (до " + DD_MM.format(in.horizonEnd()) + ")";
        case DATE -> "до " + DD_MM_YYYY.format(in.horizonEnd());
    };
}
```

Все существующие конструкторы PocketInput в тестах/сервисе: `false` → `FallbackKind.NONE`, `true` → `FallbackKind.NO_INCOMES` (механически).
- [ ] **2.3** `mvnw -pl backend test -Dtest=PocketEngineTest` → PASS (все старые тоже). Коммит `feat(pocket): FallbackKind + truthful SECOND_INCOME horizon labels`.

### Task 3: Repository — две даты дохода одним запросом

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/repository/FinancialEventRepository.java`

- [ ] **3.1** Добавить общий запрос РЯДОМ со старым `findNextPlannedIncomeDate` (старый удаляется в Task 4 вместе с переводом PocketService — так каждый шаг остаётся компилируемым):

```java
/** Ближайшие различные даты будущих планов-доходов (NEXT_INCOME / SECOND_INCOME, спека ANO-14 §4). */
@Query("""
    SELECT DISTINCT e.date FROM FinancialEvent e
    WHERE e.deleted = false
      AND e.eventKind = ru.selfin.backend.model.EventKind.PLAN
      AND e.status = ru.selfin.backend.model.enums.EventStatus.PLANNED
      AND e.type = ru.selfin.backend.model.enums.EventType.INCOME
      AND e.wishlistStatus IS NULL
      AND e.date > :after AND e.date <= :until
    ORDER BY e.date
    """)
List<LocalDate> findPlannedIncomeDates(
    @Param("after") LocalDate after, @Param("until") LocalDate until, Pageable pageable);
```

Вызов: `PageRequest.of(0, 2)`. Проверка семантики DISTINCT+ORDER — в PocketControllerIT (Task 5).
- [ ] **3.2** Компиляция зелёная (оба метода сосуществуют). Коммит вместе с Task 4.

### Task 4: PocketService — резолюция SECOND_INCOME + recurring-продление

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/service/PocketService.java`
- Test: `backend/src/test/java/ru/selfin/backend/service/PocketServiceTest.java`

- [ ] **4.1** Тесты (мок-стиль существующего PocketServiceTest): (а) две даты → horizonEnd = вторая, kind NONE; (б) одна дата d, d > asOf+30 → horizonEnd = d, kind SECOND_NOT_FOUND; (в) одна дата d, d < asOf+30 → horizonEnd = asOf+30, kind SECOND_NOT_FOUND; (г) ноль дат → asOf+30, kind NO_INCOMES; (д) NEXT_INCOME использует первую дату из того же запроса; (е) `recurringRuleService.extendIndefiniteRules(asOf+36мес)` вызван ДО `findPlannedIncomeDates` (InOrder), исключение из него не роняет запрос. FAIL.
- [ ] **4.2** Реализация: `@Slf4j` на класс (`import lombok.extern.slf4j.Slf4j` — сейчас его НЕТ, без него `log` не скомпилируется); инжект `RecurringRuleService`; удалить `findNextPlannedIncomeDate` из репозитория (оба потребителя переведены). В начале `getPocket` (после parse):

```java
try {
    recurringRuleService.extendIndefiniteRules(asOfDate.plusMonths(36));
} catch (Exception e) {
    log.warn("Lazy-extend failed; pocket continues on existing events: {}", e.getMessage());
}
```

Резолюция горизонта (замена NEXT_INCOME-ветки + новая):

```java
case NEXT_INCOME -> {
    List<LocalDate> dates = incomeDates(asOfDate);
    if (!dates.isEmpty()) horizonEnd = dates.get(0);
    else { horizonEnd = asOfDate.plusDays(FALLBACK_HORIZON_DAYS); kind = FallbackKind.NO_INCOMES; }
}
case SECOND_INCOME -> {
    List<LocalDate> dates = incomeDates(asOfDate);
    if (dates.size() >= 2) horizonEnd = dates.get(1);
    else if (dates.size() == 1) {
        LocalDate min = asOfDate.plusDays(FALLBACK_HORIZON_DAYS);
        horizonEnd = dates.get(0).isAfter(min) ? dates.get(0) : min;
        kind = FallbackKind.SECOND_NOT_FOUND;
    } else { horizonEnd = asOfDate.plusDays(FALLBACK_HORIZON_DAYS); kind = FallbackKind.NO_INCOMES; }
}
```

(`kind` инициализирован `FallbackKind.NONE`, передаётся в PocketInput вместо boolean; `incomeDates` — приватный хелпер с PageRequest.of(0, 2).)
- [ ] **4.3** `mvnw -pl backend test -Dtest=PocketServiceTest,PocketEngineTest,PocketScopeTest` → PASS. Коммит `feat(pocket): SECOND_INCOME horizon + recurring lazy-extend before resolution`.

### Task 5: PocketControllerIT — e2e новый скоуп + recurring

**Files:**
- Test: `backend/src/test/java/ru/selfin/backend/PocketControllerIT.java`

- [ ] **5.1** IT-кейсы (дельта-стиль существующих): (а) два плановых дохода → `?scope=SECOND_INCOME` даёт horizon.endDate = вторая дата, label «до 2-го дохода», trajectory до неё включительно; (б) два дохода В ОДИН день + один позже → вторая РАЗЛИЧНАЯ дата (DISTINCT); (в) один доход → fallback=true, label «(второй доход не найден)», горизонт ≥ asOf+30 и накрывает доход; (г) recurring: создать бессрочное правило расхода штатным флоу, затем репозиторием удалить (soft-delete) сгенерированный хвост событий дальше ~1 мес — имитация нематериализованного правила; `?scope=MONTHS:3` должен содержать события в хвосте траектории (extendIndefiniteRules отработал); (д) recurring-ДОХОД с урезанным хвостом + `?scope=NEXT_INCOME` → `fallback == false` (материализация якорит горизонт — поведенческое доказательство спеки §6, дополняет юнит-InOrder из 4.1е).
- [ ] **5.2** `mvnw -pl backend test -Dtest=PocketControllerIT` (Docker) → PASS. Коммит `test(pocket): SECOND_INCOME + recurring extension IT`.

## Chunk 2: Frontend — скоуп, фраза, график

### Task 6: Типы + пилюля + фраза

**Files:**
- Modify: `frontend/src/types/api.ts` (PocketScopeType += 'SECOND_INCOME')
- Modify: `frontend/src/components/PocketCard.tsx` (SCOPES)
- Modify: `frontend/src/lib/pocketPhrase.ts`
- Test: `frontend/src/lib/pocketPhrase.test.ts`

- [ ] **6.1** Тесты фразы: SECOND_INCOME не-fallback (label «до 2-го дохода 25.07» дословно, хвост «После дохода → X» как у NEXT_INCOME — конец горизонта = день дохода); SECOND_INCOME fallback (label дословно, БЕЗ «плановых доходов нет»). FAIL.
- [ ] **6.2** Реализация: `PocketScopeType = 'NEXT_INCOME' | 'SECOND_INCOME' | 'MONTHS' | 'DATE'`. SCOPES: вставить `{ key: 'SECOND_INCOME', label: '2-й доход' }` после «До дохода». pocketPhrase:

```ts
const isIncomeAnchored = (horizon.type === 'NEXT_INCOME' || horizon.type === 'SECOND_INCOME') && !horizon.fallback;
const horizonPart = horizon.fallback
    ? (horizon.type === 'SECOND_INCOME' ? horizon.label : 'на 30 дней вперёд (плановых доходов нет)')
    : isIncomeAnchored ? horizon.label : horizon.label;
```

(т.е. label дословно везде, кроме сохранения старой NEXT_INCOME-fallback строки с «на»; `afterIncome`/хвосты переключить с `isNextIncome` на `isIncomeAnchored`, существующие 8 строк-тестов не должны измениться).
- [ ] **6.3** `npx vitest run` → PASS. Коммит `feat(pocket-ui): second-income scope pill + truthful phrase`.

### Task 7: Хелперы графика (чистая логика)

**Files:**
- Create: `frontend/src/lib/trajectoryChart.ts`
- Test: `frontend/src/lib/trajectoryChart.test.ts`

- [ ] **7.1** Тесты на контракты:

```ts
computeDomain(balances: number[]): { min: number; max: number }
// всегда включает 0: [min(0, minBal), max(0, maxBal)]; вся траектория под водой → max = 0
pickTicks(trajectory: TrajPoint[], minDate: string, maxTicks?: number): number[]  // индексы
// всегда: 0 (сегодня), последний (конец горизонта), индекс минимума, дни с income>0;
// прореживание по минимальному расстоянию, приоритет: сегодня/конец/минимум > доходы
buildLinePoints(trajectory, w, h, pad): string  // "x,y x,y ..." для polyline
showDangerZone(domain): boolean   // true iff domain.min < 0 (спека §8: зоны только когда должны)
showBufferZone(buffer): boolean   // true iff buffer > 0
buildMinAnnotation(minPoint): string  // "мин dd.MM · X ₽ · виновник" | без хвоста при drivenBy null
buildDayDetails(point, isToday): string  // "dd.MM · остаток X" + только ненулевые "+доход/−расход"; день 0 → "сегодня"
```

(@testing-library НЕ заводим — вся логика §8-тестов графика живёт в этих чистых хелперах, компонент только рендерит их результат.)

- [ ] **7.2** Реализация (чистые функции, без React). `npx vitest run` → PASS. Коммит `feat(pocket-ui): trajectory chart helpers`.

### Task 8: PocketTrajectoryChart (SVG-компонент)

**Files:**
- Create: `frontend/src/components/pocket/PocketTrajectoryChart.tsx`

- [ ] **8.1** Компонент `({ data }: { data: PocketResponse })`, состояние — только `selectedIdx: number | null`. Структура (стиль страницы: карточка `rounded-2xl p-5`, `var(--color-surface)`, шапка «КАССОВЫЙ КАЛЕНДАРЬ · {data.horizon.label}» в верхнем регистре как у соседей):
  - SVG `viewBox="0 0 600 230"`, `width: 100%`; скейлы из helpers.
  - Слои по спеке §3 (порядок рендера): янтарная зона 0..buffer (если buffer>0) → красная зона <0 (если domain.min<0) → линия нуля → пунктир буфера → polygon-заливка (#6c63ff, opacity .14) → polyline (#8f86ff, 2px) → точки-маркеры дней (только если траектория ≤ 31 точки) → маркеры доходов (income>0: круг var(--color-success)) → маркер+аннотация минимума (minPoint: круг янтарный/красный по знаку balance; текст «мин dd.MM · {fmtRub} · {drivenBy}» без drivenBy-части при null; аннотация над точкой, прижимается внутрь у краёв) → «сегодня» у первой точки → тики дат по pickTicks → вертикальная направляющая selectedIdx.
  - Хит-зоны: невидимые rect на каждый день, onClick → setSelectedIdx (повторный клик — снять).
  - Строка деталей при selectedIdx: «{dd.MM} · остаток {fmtRub(balance)}» + «+{income}» / «−{expense}» (только ненулевые), день 0 подписывать «сегодня». Использовать общий `fmtRub`.
  - Легенда 11px под графиком: запас / ниже буфера / разрыв(<0). В спеке легенды нет — оставляем ОСОЗНАННО: три цветовые зоны без расшифровки нечитаемы для целевого пользователя.
- [ ] **8.2** Ручная проверка в dev-превью (Task 10 сделает полную). Коммит `feat(pocket-ui): PocketTrajectoryChart SVG component`.

### Task 9: Dashboard — rewire: график вместо ленты, алерт-сторож

**Files:**
- Modify: `frontend/src/pages/Dashboard.tsx`
- Modify: `frontend/src/types/api.ts` (DashboardData → { progressBars }; CashGapAlert удалить)
- Test: `frontend/src/lib/watchdogAlert.test.ts` (+ Create: `frontend/src/lib/watchdogAlert.ts`)

- [ ] **9.1** Тесты хелпера алерта:

```ts
buildWatchdogAlert(watchdog: PocketResponse | null, userHorizonEnd: string | null):
  { date: string; deficit: number; drivenBy: string | null; beyondChart: boolean } | null
// null если watchdog null или minPoint.balance >= 0; beyondChart = watchdog.minPoint.date > userHorizonEnd
// userHorizonEnd === null (пользовательский pocket не загружен/упал) → beyondChart: false — с тестом
```

- [ ] **9.2** Dashboard: в `loadAll` Promise.all добавить `fetchPocket('SECOND_INCOME')` → state `watchdog: PocketResponse | null` (ошибку сторожа глотать → null, страница не падает). Алерт-блок (строки ~249-261): рендер из `buildWatchdogAlert(watchdog, pocket?.horizon.endDate ?? null)`; текст прежний + «виновник» при наличии + при `beyondChart` строка «Разрыв за пределами графика — переключись на „2-й доход“». Empty-state условие (~363): `!watchdogAlert` вместо `!data.cashGapAlert`. `CashFlowSection` (378-420) удалить целиком, вместо неё `<PocketTrajectoryChart data={pocket} />` (шапка с label теперь внутри компонента). `DashboardData` в types → только `progressBars`; `CashGapAlert` интерфейс удалить.
- [ ] **9.3** `npx vitest run` + `npx tsc --noEmit` → PASS. Коммит `feat(dashboard): trajectory chart replaces ribbon; watchdog alert from pocket minPoint`.

## Chunk 3: Backend-выпил + верификация

### Task 10: DashboardService/DTO — смерть зарплатных расчётов

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/service/DashboardService.java`
- Modify: `backend/src/main/java/ru/selfin/backend/dto/DashboardDto.java`
- Test: `backend/src/test/java/ru/selfin/backend/service/DashboardServiceTest.java`

- [ ] **10.1** Grep-проверка потребителей перед удалением: `rg "cashGapAlert|nextSalaryDate|balanceBefore|balanceAfter|secondSalary|endOfMonthForecast|currentBalance" frontend/src backend/src` — фронт уже не потребляет (после Task 9), бэк: AnalyticsController только пробрасывает DTO; PredictionService — проверить, что hit был по javadoc/именам, не по полям.
- [ ] **10.2** `DashboardDto` → `record DashboardDto(List<CategoryProgressBar> progressBars)` (CashGapAlert-рекорд удалить, CategoryProgressBar остаётся). `getDashboard` → выборка monthEvents + `buildProgressBars` (шаги 1, 3-6 и хелперы calcBalanceBefore/After, detectCashGap, effectiveNetSum, signedAmount/plannedSignedAmount — удалить, если ничем больше не используются; FORECAST_HORIZON_DAYS удалить; javadoc класса переписать под новую роль). `sumOverdueMandatoryExpenses` — не трогать (другие потребители).
- [ ] **10.3** DashboardServiceTest: удалить тесты зарплат/разрыва, оставить/дополнить progressBars; `mvnw -pl backend test` полный → PASS. Коммит `refactor(dashboard): salary horizons + detectCashGap die; DTO = progressBars only (ANO-23 slice)`.

### Task 11: Полная верификация

- [ ] **11.1** Бэк: `JAVA_HOME=... ./mvnw -pl backend test` (юниты + IT, Docker) → зелёные.
- [ ] **11.2** Фронт: `npx vitest run`, `npx tsc --noEmit`, `npm run build` → зелёные.
- [ ] **11.3** Живая проверка (preview, launch.json `npm run dev`): Dashboard — график рендерится, скоуп-пилюли меняют график+число+фразу синхронно, «2-й доход» работает, алерт при искусственном разрыве, тап по дню; Funds — карточка живёт, перевод в копилку не сломан. Скриншот в чат.
- [ ] **11.4** Адверсариальная верификация диффа (2-3 агента на финальный дифф) — политика сессии.
- [ ] **11.5** PR в main + обновление Linear ANO-14/ANO-23 + пост в Slack.
