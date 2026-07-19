# Sandbox-примерка на кармашке (ANO-16) — Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Окно примерки хотелок на кармашке: POST /pocket/sandbox (движок с подменённым входом, две траектории + дневные дельты), резервирование FIXED-копилок в реальном кармашке, пересборка страницы /wishlist.

**Architecture:** Спека `docs/superpowers/specs/2026-07-18-pocket-sandbox-design.md` (прошла ревью, утверждена Кириллом 2026-07-18). Сборка входа выделяется из PocketService в `PocketInputAssembler`; чистая раскладка «item → дневные события» в `SandboxLayout`; `PocketSandboxService` гоняет `PocketEngine.calculate` дважды. Фронт складывает НИЧЕГО — только рисует ответ.

**Tech Stack:** Spring Boot 4 / Java 21 / JUnit 5 + Testcontainers (IT); React 18 + TypeScript + vitest.

**Конвенции проекта:** ветка на PR; `JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test` (юниты), `./mvnw verify` (юниты+IT, нужен Docker); фронт `npm run test` в `frontend/`. Коммиты — conventional commits на русском, как в истории репо.

**Порядок PR:** Chunk 1 = PR A (маленький, меняет видимое число — прожить отдельно), Chunk 2 = PR B (бэк песочницы), Chunk 3 = PR C (фронт). Ветки: `feature/pocket-reserve-savings`, `feature/pocket-sandbox-api`, `feature/pocket-sandbox-ui`.

---

## Chunk 1: PR A — резервирование FIXED-копилок (§5-раскладка + §6)

Итог чанка: `GET /pocket` резервирует взносы датированных FIXED-копилок (SAVINGS) новой breakdown-строкой; появились `SandboxLayout` и `PocketInputAssembler` — фундамент чанка 2. Поведение `GET /pocket` в остальном байт-в-байт прежнее.

### Task 1.1: SyntheticKind + EventSnapshot

**Files:**
- Create: `backend/src/main/java/ru/selfin/backend/dto/pocket/SyntheticKind.java`
- Modify: `backend/src/main/java/ru/selfin/backend/dto/pocket/EventSnapshot.java`

- [ ] **Step 1:** Создать enum:

```java
package ru.selfin.backend.dto.pocket;

/** Происхождение синтетического (не из БД) снапшота события. */
public enum SyntheticKind {
    /** Взнос в FIXED-копилку (§6 спеки sandbox) или взнос примерки (§5). */
    SAVINGS_CONTRIBUTION,
    /** Разовая примерочная трата / платёж кредита примерки (чанк 2). */
    TRY_ON
}
```

- [ ] **Step 2:** Добавить в `EventSnapshot` последний компонент `SyntheticKind syntheticKind` + перегруженный конструктор со старой сигнатурой (делегирует `null`), чтобы существующие тесты движка не менялись массово. `EventSnapshot.from(...)` передаёт `null`. Синтетика реальных id не имеет — `id = null` допустим (движок id использует только в candidates, куда синтетика не попадает).

- [ ] **Step 3:** `./mvnw test` — компиляция + все старые тесты зелёные (перегрузка спасла сигнатуры). Commit `feat(pocket): SyntheticKind в EventSnapshot (ANO-16 §6)`.

### Task 1.2: SandboxLayout — чистая раскладка §5 (TDD)

**Files:**
- Create: `backend/src/main/java/ru/selfin/backend/service/SandboxLayout.java`
- Test: `backend/src/test/java/ru/selfin/backend/service/SandboxLayoutTest.java`

- [ ] **Step 1:** Написать падающие табличные тесты (@ParameterizedTest где уместно). Обязательные кейсы (спека §5/§10; asOf = 2026-07-18, плановые доходы 2026-07-28, 2026-08-15, 2026-08-28, 2026-09-15 — мини-фикстура):
  - `maxStretchMonths(asOf, target)`: target 2026-12-14 → 5 (авг..дек); target в след. месяце → 1; target в текущем месяце/прошлом → 0.
  - savings n=5, amount 80000: 5 взносов по 16000; август — день 15 (первый доход месяца), месяцы без доходов в фикстуре (окт/ноя/дек) — 1-е число.
  - n=2 (n < max): взносы ТОЛЬКО авг и сен (первые n месяцев), по 40000; к декабрю не прижимаются.
  - Месяц взноса = месяц цели: target 2026-08-10, n=1 → взнос 2026-08-01?? Нет: первый доход августа 15.08 позже цели 10.08 → взнос в сам день цели 10.08 («не позже date»).
  - Копейки: amount 100.00, n=3 → 33.33 + 33.33 + 33.34 (последний добирает; сумма ровно 100.00).
  - `monthlyPmt(1300000, 18.0, 60)` == значение `WishlistSimulationService.computeCreditDelta(...).monthlyPMT()` на тех же аргументах (формулы обязаны совпасть).
  - credit series: date 2026-09-30, term 3 → PMT-расходы 30.10, 30.11, 30.12; кламп day-of-month: date 2026-08-31 → платёж 30.11 в ноябре (31→30).
  - Все синтетики: `eventKind=PLAN, status=PLANNED, factAmount=null, wishlistStatus=null, type=EXPENSE`, description задан, syntheticKind задан — иначе движок их отфильтрует (`isPendingPlan`/`allowedInTrajectory`).

- [ ] **Step 2:** `./mvnw test -Dtest=SandboxLayoutTest` — FAIL (класса нет).

- [ ] **Step 3:** Реализовать `SandboxLayout` — final class, только статика, без Spring:

```java
/** Раскладка «item → дневные синтетические события» (спека sandbox §5). Pure. */
public final class SandboxLayout {
    private SandboxLayout() {}

    /** Месяцы след..месяц цели включительно; ≤ 0 если цель в текущем месяце/прошлом. */
    public static int maxStretchMonths(LocalDate asOf, LocalDate target) { ... }

    /**
     * n взносов amount/n (HALF_UP, последний добирает остаток) в первые n месяцев
     * начиная со следующего. День = первый плановый доход месяца из incomeDates
     * (отсортированный список дат PLAN PLANNED INCOME); нет дохода → 1-е число;
     * месяц взноса == месяц цели → не позже target.
     */
    public static List<EventSnapshot> layoutSavings(String description, BigDecimal amount,
            LocalDate target, int n, LocalDate asOf, List<LocalDate> incomeDates,
            SyntheticKind kind) { ... }

    /** Разовая примерочная трата (чанк 2 использует). */
    public static List<EventSnapshot> layoutOneOff(String description, BigDecimal amount,
            LocalDate date) { ... }

    /** Аннуитет: вынесенная формула PMT (единственная в проекте). */
    public static BigDecimal monthlyPmt(BigDecimal amount, BigDecimal annualRatePct, int termMonths) { ... }

    /** Серия PMT-расходов с месяца после date, день = день date с клампом по длине месяца. */
    public static List<EventSnapshot> layoutCredit(String description, BigDecimal amount,
            LocalDate date, BigDecimal annualRatePct, int termMonths) { ... }
}
```

  `WishlistSimulationService.computeCreditDelta` перевести на `SandboxLayout.monthlyPmt` (одна формула, спека §5).

- [ ] **Step 4:** `./mvnw test` — SandboxLayoutTest + WishlistSimulationService-тесты зелёные. Commit `feat(sandbox): SandboxLayout — раскладка item в дневные события (ANO-16 §5)`.

### Task 1.3: PocketInputAssembler — выделение сборки входа

**Files:**
- Create: `backend/src/main/java/ru/selfin/backend/service/PocketInputAssembler.java`
- Modify: `backend/src/main/java/ru/selfin/backend/service/PocketService.java`
- Modify: `backend/src/test/java/ru/selfin/backend/service/PocketServiceTest.java` (см. Step 2)

- [ ] **Step 1:** Перенести из `PocketService.getPocket` в новый `@Component PocketInputAssembler` (те же репо-зависимости): lazy-extend recurring (шаг 0), резолюцию горизонта (шаг 1), выборки чекпоинта/событий/просрочки/хотелок (шаги 2–3), прогноз (шаг 4), буфер (шаг 5). Публичный метод:

```java
/** Результат сборки: вход движка + что фактически сидит в baseline (для sandbox, §9). */
public record Assembled(PocketInput input, Map<SandboxRef, List<EventSnapshot>> baselineRefs) {}

public Assembled build(PocketScope scope, LocalDate asOfDate) { ... }
```

  `SandboxRef` — маленький record `(RefType type, UUID id)`, `enum RefType { EVENT, FUND }`, в пакете `dto/pocket` (нужен обоим чанкам). `baselineRefs` в этом чанке заполняется: датированные FIXED-неконвертированные события-хотелки (их собственный снапшот) + копилки из Task 1.4. `PocketService.getPocket` худеет до: parse scope → `assembler.build` → `PocketEngine.calculate`. Ошибки: parse-400 остаётся в PocketService; `ResponseStatusException` резолюции горизонта (DATE-диапазон) переезжает в ассемблер вместе с кодом — GlobalExceptionHandler мапит её оттуда так же.

- [ ] **Step 2:** Перенацелить `PocketServiceTest`: он конструирует PocketService со старыми зависимостями и проверяет ровно переносимую логику (горизонты, 92-дневный кап, фолбэки) — эти тесты переезжают в новый `backend/src/test/java/ru/selfin/backend/service/PocketInputAssemblerTest.java` (тот же Mockito-стиль); в PocketServiceTest остаются только parse-400 и склейка assembler→engine.

- [ ] **Step 3:** `./mvnw test` — все юниты зелёные (перенос без изменения поведения). Commit `refactor(pocket): PocketInputAssembler — сборка входа отдельно от сервиса (ANO-16 §3)`.

### Task 1.4: Резервирование FIXED-копилок (§6) + breakdown-строка (TDD)

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/service/PocketInputAssembler.java`
- Modify: `backend/src/main/java/ru/selfin/backend/service/PocketEngine.java`
- Modify: `backend/src/main/java/ru/selfin/backend/dto/pocket/BreakdownType.java` (+`SAVINGS_CONTRIBUTIONS`)
- Test: `backend/src/test/java/ru/selfin/backend/service/PocketEngineTest.java` (дополнить)
- Test: `backend/src/test/java/ru/selfin/backend/service/PocketInputAssemblerTest.java` (создан в Task 1.3 Step 2 — дополнить)

- [ ] **Step 1:** Падающие тесты движка: вход с двумя synthetic-взносами (SAVINGS_CONTRIBUTION, 15.08 и 15.09 по 16000) → траектория падает в эти дни; breakdown содержит строку `SAVINGS_CONTRIBUTIONS` «Взносы в копилки (1 шт)» с details=[имя копилки] и суммой −(взносы до минимума); строка `PLANNED_EXPENSES` взносы НЕ включает; инвариант STARTING − OVERDUE − EXPENSES(≤min) − CONTRIB(≤min) + INCOME(≤min) − FORECAST(≤min) = MIN сходится; взнос — крупнейший расход дня минимума → `minPoint.drivenBy` = имя копилки.

- [ ] **Step 2:** Падающие тесты ассемблера (Mockito, по образцу PocketServiceTest): FIXED SAVINGS фонд (target 80000, currentBalance 0, targetDate +5 мес) → во входе взносы `остаток/5`, ref в `baselineRefs`; края: `остаток ≤ 0` → нет взносов; targetDate в текущем месяце → нет взносов; фонд без даты → нет; CREDIT-фонд → нет (см. Step 4); DISMISSED/конвертированный → нет.

- [ ] **Step 3:** Реализация. Ассемблер: `fundRepository.findAllWishlistFunds()` → фильтр `FIXED && !converted && targetDate != null && purchaseType == SAVINGS` → `остаток = targetAmount − currentBalance`; если `остаток > 0 && maxStretchMonths > 0` → `SandboxLayout.layoutSavings(fund.getName(), остаток, targetDate, max, asOf, incomeDates, SAVINGS_CONTRIBUTION)` → добавить к events + в baselineRefs. `incomeDates` — все даты плановых доходов до конца траектории (не только 2 ближайшие: расширить существующий репо-запрос `findPlannedIncomeDates` вызовом с большим Pageable или без лимита до `trajectoryEnd`). Движок: отдельные аккумуляторы `contribCum/contribAtMin` по `syntheticKind == SAVINGS_CONTRIBUTION`, новая строка после PLANNED_EXPENSES.

- [ ] **Step 4:** В спеку `2026-07-18-pocket-sandbox-design.md` §6 добавить одну строку-уточнение (вскрыто при планировании): резервируются только `purchaseType = SAVINGS`; CREDIT-фонды не резервируются — их поток начинается с покупки и приходит recurring-PMT-правилом при конверсии.

- [ ] **Step 5:** `./mvnw test` — зелёное. Commit `feat(pocket): FIXED-копилки резервируют взносы в кармашке (ANO-16 §6)`.

### Task 1.5: IT + смок + закрытие чанка

**Files:**
- Modify: `backend/src/test/java/ru/selfin/backend/PocketControllerIT.java` (дополнить; ИТ живут в корневом пакете `ru.selfin.backend`, пакета controller в тестах НЕТ)
- Modify: `frontend/src/types/api.ts` (union `BreakdownType` += `'SAVINGS_CONTRIBUTIONS'` — иначе тип протухает с мержем PR A; PocketCard рендерит label с сервера, ничего больше не нужно)
- Modify: `docs/superpowers/specs/2026-07-02-pocket-core-design.md` (§3.2 пометка)

- [ ] **Step 1:** IT: создать FIXED SAVINGS фонд с датой → `GET /pocket?scope=MONTHS:3` содержит строку SAVINGS_CONTRIBUTIONS и pocket меньше, чем без фонда; фонд с `остаток ≤ 0` и фонд с протухшей целью → строки нет, 200 (не падает).
- [ ] **Step 2:** Пометка в pocket-core спеке §3.2 (конвенция ANO-15): «ANO-16 §6: датированные FIXED-копилки (SAVINGS) резервируются синтетическими взносами». Строка в union `BreakdownType` фронта.
- [ ] **Step 3:** `./mvnw verify` (полный, с IT) — зелёное. Смок на dev-базе (`docker start selfin-db`, поднять бэк): `GET /pocket?scope=MONTHS:6` — видны взносы Египта (75000×2) и Горнолыжки (16000×5), breakdown объясняет. Commit, PR A `feature/pocket-reserve-savings`, ревью по @superpowers:requesting-code-review.

## Chunk 2: PR B — POST /pocket/sandbox (§3, §4, §9) + расширение конверсии (§8)

Итог чанка: рабочий sandbox-endpoint с валидацией и дельта-инвариантом; `ConvertWishlistRequestDto.fundTargetDate`. UI ещё старый.

### Task 2.1: DTO контракта

**Files:**
- Create: `backend/src/main/java/ru/selfin/backend/dto/pocket/sandbox/SandboxRequestDto.java` (+ `TryOnDto`, `SandboxResponseDto`, `ItemDeltaDto`, `DayDeltaDto`, `SandboxItemDto` — по record на файл в пакете `dto/pocket/sandbox`)

- [ ] **Step 1:** Records по спеке §4:

```java
public record SandboxRequestDto(String scope, List<TryOnDto> tryOn, List<SandboxRef> exclude) {}
public record TryOnDto(SandboxRef ref, BigDecimal amount, LocalDate date,
                       Integer stretchMonths, BigDecimal creditRate, Integer creditTermMonths) {}
public record DayDeltaDto(LocalDate date, BigDecimal delta) {}
/** Порядок itemDeltas: сначала tryOn в порядке запроса, затем exclude в порядке запроса. */
public record ItemDeltaDto(SandboxRef ref, List<DayDeltaDto> days) {}
public record SandboxItemDto(SandboxRef ref, String kind, String name, BigDecimal amount,
                             LocalDate date, Integer stretchMonthsMax, Integer stretchMonthsDefault,
                             BigDecimal creditRate, Integer creditTermMonths,
                             String wishlistStatus, boolean inBaseline) {}
public record SandboxResponseDto(PocketResultDto baseline, PocketResultDto fitted,
                                 List<ItemDeltaDto> itemDeltas, List<SandboxItemDto> items) {}
```

  `inBaseline` — операциональное «фактически развёрнут ассемблером» (§9): фронту не надо повторять края §6. Nullable-поля дефолтов items — по спеке §4 (хотелка: stretch 0; копилка: остаток/дата/max; кредитная: + ставка/срок).

- [ ] **Step 2:** Порядок `itemDeltas` (tryOn в порядке запроса, затем exclude) — дописать одной строкой в спеку §4 (спека = контракт записи, правило родилось в плане).

- [ ] **Step 3:** Компиляция `./mvnw test-compile`. Commit `feat(sandbox): DTO контракта POST /pocket/sandbox (ANO-16 §4)`.

### Task 2.2: PocketSandboxService (TDD)

**Files:**
- Create: `backend/src/main/java/ru/selfin/backend/service/PocketSandboxService.java`
- Test: `backend/src/test/java/ru/selfin/backend/service/PocketSandboxServiceTest.java` (Mockito: мок ассемблера и репо; движок настоящий)

- [ ] **Step 1:** Падающие тесты:
  - пустая примерка → baseline == fitted (тот же pocket), itemDeltas пуст, items собраны.
  - tryOn разовая ad-hoc (ref null, date в горизонте) → fitted.pocket < baseline.pocket ровно на amount (если день ≤ дня минимума); itemDeltas[0].days = [{date, −amount}].
  - tryOn копилки n=2 → два дневных взноса; префикс-инвариант: для каждого дня d `fitted.trajectory[d].balance == baseline.trajectory[d].balance + Σ days≤d` (юнит-версия контрактного теста).
  - exclude FIXED-копилки из baseline → fitted.pocket > baseline.pocket; дельта положительная по дням взносов.
  - tryOn ref, сидящий в baseline, БЕЗ парного exclude → 400; с парным exclude → ok (события ref-а вычищены из fitted, синтетика добавлена).
  - tryOn: date null / date ≤ asOf / amount ≤ 0 / stretch вне [0..max] / stretch ≥ 1 вместе с creditRate → 400; ref незнакомый/DISMISSED/конвертированный → 400; exclude ref не из baseline → 400.
  - позитивная сторона операционального правила §9: датированная FIXED SAVINGS-копилка, выпавшая из baseline по краям §6 (остаток ≤ 0 / протухшая цель), принимает tryOn БЕЗ парного exclude (Chunk 3 Task 3.3 Step 2 на это опирается).
  - tryOn существующей OPEN-хотелки → её нет в fitted.wishlistCandidates и нет строки WISHLIST_INFO по ней (вычищена из wishlist-выборки fitted).
  - kind CREDIT (creditRate+term заданы) → PMT-серия в fitted, сама покупка счёт не трогает.
- [ ] **Step 2:** FAIL-прогон.
- [ ] **Step 3:** Реализация `simulate(SandboxRequestDto req, LocalDate asOf)`:
  1. parse scope (реюз PocketScope.parse, 400 как в PocketService);
  2. `assembler.build` → baseline Assembled;
  3. валидация §9 на baselineRefs + репо-выборках (хотелки/фонды по ref);
  4. fittedInput: events − (exclude ∪ tryOn-refs из baseline) + Σ SandboxLayout.layout*(tryOn); wishlistEvents − tryOn-refs;
  5. `PocketEngine.calculate` ×2;
  6. itemDeltas: tryOn → его синтетика как {date, −amount} (агрегировать по дате); exclude → его baseline-события как {date, +amount};
  7. items: candidates + FIXED из выборок, дефолты по спеке §4, `inBaseline = baselineRefs.containsKey(ref)`.
- [ ] **Step 4:** Зелёный прогон, commit `feat(sandbox): PocketSandboxService — движок с подменённым входом (ANO-16 §3)`.

### Task 2.3: Контроллер + IT

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/controller/PocketController.java` (`@PostMapping("/sandbox")`)
- Test: `backend/src/test/java/ru/selfin/backend/PocketSandboxIT.java` (корневой тест-пакет — конвенция всех 9 существующих IT)

- [ ] **Step 1:** `POST /api/v1/pocket/sandbox` → `sandboxService.simulate(req, LocalDate.now())`.
- [ ] **Step 2:** IT (Testcontainers, по образцу PocketControllerIT): сценарии §10 — 200+структура; включил копилку → меньше; exclude FIXED-копилки → больше; **дельта-инвариант день-в-день на полном ответе**; пустая примерка; tryOn без exclude на baseline-элемент → 400; date ≤ today → 400.
- [ ] **Step 3:** `./mvnw verify` зелёный. Commit `feat(sandbox): POST /pocket/sandbox + контрактные IT (ANO-16 §4, §10)`.

### Task 2.4: Конверсия §8 — fundTargetDate

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/dto/wishlist/ConvertWishlistRequestDto.java` (+`LocalDate fundTargetDate`)
- Modify: `backend/src/main/java/ru/selfin/backend/service/WishlistConversionService.java`
- Test: `backend/src/test/java/ru/selfin/backend/service/WishlistConversionServiceTest.java` (дополнить)

- [ ] **Step 1:** Падающий тест: конверсия WISHLIST→FUND с `fundTargetDate=2026-10-31` создаёт фонд с этой датой (не датой хотелки); без поля — поведение прежнее (дата хотелки).
- [ ] **Step 2:** `buildSavingsFund` принимает `req.fundTargetDate() != null ? req.fundTargetDate() : src-дата`. Javadoc: фронт передаёт последний день месяца последнего взноса примерки (§8) — тогда резерв §6 воспроизводит примерку.
- [ ] **Step 3:** Зелёный прогон, commit, PR B `feature/pocket-sandbox-api`, ревью.

## Chunk 3: PR C — окно примерки (§7, §8)

Итог чанка: /wishlist = окно примерки (две линии, тумблеры, ползунок, ad-hoc, капитал-кнопка), вход с Dashboard. Старые компоненты wishlist переиспользуются внутри капитал-кнопки; неиспользуемое — удаляется (boyscout, без жалости: страница пересобирается по решению юзера).

### Task 3.1: Типы + API + storage + чистая математика

**Files:**
- Modify: `frontend/src/types/api.ts` (Sandbox* интерфейсы зеркально DTO чанка 2)
- Modify: `frontend/src/api/index.ts` (`postPocketSandbox(req): Promise<SandboxResponse>`; расширить inline-тип `convertWishlistItem` полем `fundTargetDate?` — понадобится в Task 3.3)
- Create: `frontend/src/lib/sandboxMath.ts` (взнос ↔ месяцы: `monthlyFor(amount, n)`, `monthsFor(amount, monthly)`; предикат скоуп-чипа `realizationScope(enabledItems): string | null` — `DATE:<max targetDate включённых датированных>` или null — только производные для UI, НИКАКИХ правил движка)
- Create: `frontend/src/lib/sandboxStorage.ts` (localStorage: `{enabled: SandboxRef[], overrides: Record<key, {amount?, date?, stretchMonths?}>, adhoc: {amount, date}[], excluded: SandboxRef[]}`, ключ `selfin.sandbox.v1`; ref-key = `${type}:${id}`)
- Test: `frontend/src/lib/sandboxMath.test.ts`, `frontend/src/lib/sandboxStorage.test.ts` (vitest, TDD)

- [ ] **Step 1:** vitest падающие → реализация → зелёные. Кейсы sandboxMath: взнос↔месяцы в обе стороны; `realizationScope`: нет включённых датированных → null, две датированные → max дата (закрывает vitest-кейс спеки §10 «до реализации появляется/исчезает»). Commit `feat(sandbox-ui): типы, api, storage, математика ползунка`.

### Task 3.2: Двухсерийный график

**Files:**
- Create: `frontend/src/components/sandbox/SandboxChart.tsx` (реюз хелперов `lib/trajectoryChart.ts`; props `{baseline: PocketResponse, fitted: PocketResponse}`; baseline приглушённая линия, fitted яркая, оба минимума подписаны; домен по объединению серий)
- Modify: `docs/superpowers/specs/2026-07-18-pocket-sandbox-design.md` (§7: зафиксировать отклонение)

- [ ] **Step 1:** Компонент + ручная проверка на моках (Storybook нет — временная страница не нужна, проверка в Task 3.4 на живом бэке). `PocketTrajectoryChart` НЕ трогаем (Dashboard живёт как жил).

- [ ] **Step 2:** Отклонение от спеки §7 («расширить PocketTrajectoryChart до двух серий») — осознанное: отдельный `SandboxChart` изолирует Dashboard от изменений; общая математика остаётся в `lib/trajectoryChart.ts`. Дописать одну строку в спеку §7. Commit.

### Task 3.3: Пересборка Wishlist.tsx → окно примерки

**Files:**
- Rewrite: `frontend/src/pages/Wishlist.tsx`
- Create: `frontend/src/components/sandbox/SandboxItemRow.tsx` (тумблер, инлайн-правки суммы/даты, ползунок растяжки, бейдж OPEN-пунктир/FIXED-сплошной, кнопки «зафиксировать»/«отложить»)
- Create: `frontend/src/components/sandbox/AdhocRow.tsx` («+ а если трата…», «сохранить как хотелку»)
- Keep/Reuse: `WishlistImpactChart`, `useWishlistSimulation` — ТОЛЬКО внутри свёрнутой секции «что с капиталом» (ленивая загрузка по раскрытию, подпись «приблизительно, по месяцам»)

- [ ] **Step 1:** Каркас страницы: шапка (число baseline + число fitted + разница + скоуп-чипы: те же 4, что PocketCard, + «до реализации» когда включена датированная хотелка — `DATE:<max targetDate включённых>`), SandboxChart, список из `items` ответа, ad-hoc строка. Состояние = sandboxStorage; каждый чих (тумблер/правка/скоуп) → debounce 200 мс → `postPocketSandbox`.
- [ ] **Step 2:** Правила UI из спеки: тумблер OPEN-хотелки без даты сперва открывает поле даты; «покрутить FIXED» шлёт exclude+tryOn парой; `inBaseline=false` FIXED-копилка (края §6) показывается с пометкой «не в кармашке» и тумблер работает как для OPEN (только tryOn, бэк это принимает — тест Task 2.2); фиксация: растяжка 0 → `setEventWishlistStatus(FIXED)`, растяжка n ≥ 1 → `convertWishlistItem(..., {target: 'FUND', fundTargetDate: lastDayOfMonth(последнего взноса)})`; «отложить» → существующие `setEventWishlistStatus`/`setFundWishlistStatus` с DISMISSED; после фиксации — refetch и чистка storage от ref.
- [ ] **Step 3:** Удалить из страницы всё, что осталось без потребителя (старый 36-месячный график в главной роли, thresholds-шапка, если её место заняла новая шапка — проверить по факту, лишнего не выпиливать: капитал-кнопка часть старого стека использует).
- [ ] **Step 4:** `npm run build && npm run test` зелёные. Commit.

### Task 3.4: Вход с Dashboard + смок + закрытие

**Files:**
- Modify: `frontend/src/components/PocketCard.tsx` (кнопка «примерить» → navigate('/wishlist'); маршрут /wishlist уже есть в App.tsx:30 — роутер и Funds.tsx не трогаем)

- [ ] **Step 1:** Кнопка в PocketCard рядом со скоуп-чипами.
- [ ] **Step 2:** Живой смок по спеке §10 (dev-база, preview_start): Горнолыжка на «до дохода» (удар 0), «3 мес» (−16000×N видно на линии), «до реализации» (−80000 к 14.12); выключение Египта — линия вверх; ad-hoc 12000 на 20.08; перезагрузка страницы — набор жив. Скриншот в PR.
- [ ] **Step 3:** `npm run build`, vitest, `./mvnw verify` (не сломали бэк) — зелёные. Commit, PR C `feature/pocket-sandbox-ui`, ревью.
- [ ] **Step 4:** Post-merge хвосты: коммент-итог в ANO-16 (Linear) + статус в Slack #selfin; пометить в ANO-12, что дыра «взносы не резервируются» закрыта.
