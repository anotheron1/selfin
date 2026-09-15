# Конверсия хотелки без срока: срок спрашиваем, а не выдумываем — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** конверсия хотелки в плановое событие больше не создаёт запись с пустой датой; срок спрашивается в диалоге; уже созданные пустышки возвращаются в хотелки.

**Architecture:** проверка даты одна на оба входа — существующий `requireFutureDate`, которым уже пользуется `/fix`. `/convert` получает необязательное поле `planDate`; диалог заполняет его и не даёт подтвердить, пока поле пусто. Миграция `V25` разбирает пары «FIXED-хотелка → событие с пустой датой», созданные до починки.

**Tech Stack:** Java 21 / Spring Boot, JPA, Flyway, JUnit 5 + AssertJ + Mockito, Testcontainers (failsafe); React + TypeScript + vitest.

**Spec:** `docs/superpowers/specs/2026-09-15-wishlist-conversion-date-design.md`

## Global Constraints

* **Сборка идёт из `backend/`**, а не из корня: `cd backend && JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test`. `verify` добавляет интеграционные на Testcontainers; `mvnw test` их НЕ запускает.
* **После правки сигнатур record'ов:** `rm -rf backend/target/test-classes`. `ConvertWishlistRequestDto` получает новый компонент — Maven пропустит перекомпиляцию тестов, если их исходники не менялись, и ответит `BUILD SUCCESS` на сломанном коде.
* **Время только через `Clock`** (ANO-39). Прямой `LocalDate.now()` в production-коде уронит `ClockInjectionGuardTest`.
* **Каждый тест проверяется мутацией.** Позеленел — внести правку, ломающую ровно то, что тест заявляет, убедиться в красноте, откатить. Зелёный без мутации проверкой не считается.
* **Мутация, тождественная оригиналу, — не мутация.** Если правка алгебраически равна исходной, зелёный ничего не значит; ломать надо смысл, а не запись.
* **Откат мутации только по закоммиченному.** Порядок: зелено → коммит → мутация → `git checkout -- <файл>` → при находке правка и `--amend`. По незакоммиченному `checkout` снесёт всю работу шага.
* **Правила продукта:** `docs/superpowers/specs/2026-09-01-product-rules.md`. Тексты на экране — без упрёков (правило 12), без обещания точности (правило 3), словарём пользователя (правило 13).
* **Компонентных тестов на фронте нет.** Все `.test.ts` лежат в `frontend/src/lib/` и `components/wishlist/wishlistUtils.test.ts`, ни одного `.tsx`. Новую логику выносить в чистую функцию и тестировать её; отрисовку проверять на стенде.
* **Стенд поднимается бэкендом из исходников, а не образом.** `docker start selfin-test-backend` проверит старый код.
* **Порт 8080 занят посторонним процессом.** Локальный бэкенд: `--server.port=8090 --spring.datasource.url=jdbc:postgresql://localhost:5433/selfin`.
* **Кириллица в `curl -d` ломает JSON.** В проверочных скриптах — латиница.

---

### Task 1: `/convert` перестаёт принимать дату, которой нет

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/service/WishlistConversionService.java`
- Test: `backend/src/test/java/ru/selfin/backend/service/WishlistConversionServiceTest.java`

**Interfaces:**
- Consumes: `requireFutureDate(LocalDate, LocalDate)` — существует, не меняется.
- Produces: `convertFromEvent` ветка `PLAN_EVENT` отвечает 400 на дату `null` / не в будущем.

- [x] **Step 1: Проверить, что существующий тест `/fix` действительно сенситивен**

При написании плана `fix_pastOrMissingDateWithoutStretch_throws400_andSavesNothing` был оценён по имени как «несущий две приметы». Это оказалось неверно: в теле три отдельных `assertThatThrownBy` — прошлая дата, сегодняшняя (`TODAY`) и пустая. Снятие любой половины проверки его красит: без ветки `date == null` прилетит `NullPointerException` вместо `ResponseStatusException`; замена `!date.isAfter(today)` на `date.isBefore(today)` перестаёт ронять случай «сегодня».

**Тест не трогать.** Разделение на три метода улучшило бы диагностику, но это косметика на чужом зелёном тесте, а не починка. Убедиться прогоном, что он зелёный, и идти дальше:

```bash
cd backend && JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw -q test -Dtest=WishlistConversionServiceTest
```

- [x] **Step 2: Завести тестовый вход с явным «сегодня»**

`convertFromEvent` становится календарно-зависимым, а `convertItem` берёт «сегодня» из `clock`. В этом же классе уже есть образец — package-private перегрузка `applyAndFix(UUID, SandboxFixRequestDto, LocalDate)` с комментарием «Тестовый вход с явным «сегодня»». Повторить её для конверсии, чтобы тесты не зависели от системной даты:

```java
    /** Тестовый вход с явным «сегодня»: проверка даты плана календарно-зависима. */
    @Transactional
    ConvertWishlistResponseDto convertItem(UUID itemId, ConvertWishlistRequestDto req, LocalDate today) {
        boolean fromEvent = "WISHLIST".equals(req.sourceKind());
        return fromEvent
                ? convertFromEvent(itemId, req, today)
                : convertFromFund(itemId, req);
    }
```

Публичный `convertItem` делегирует в неё с `LocalDate.now(clock)`. `convertFromEvent` получает параметр `today`; `convertFromFund` не трогаем — дат он не проверяет.

- [x] **Step 3: Написать падающие тесты на `/convert`**

Помощник рядом с `openWishlist`:

```java
    /** Хотелка «когда-нибудь»: срок не задан — законное состояние (WishlistCreateDto.date). */
    private FinancialEvent openWishlistWithoutDate(UUID id) {
        FinancialEvent e = openWishlist(id);
        e.setDate(null);
        return e;
    }
```

Три теста:

```java
    @Test
    @DisplayName("ANO-138: конверсия хотелки без срока — 400, и источник не помечен сконвертированным")
    void convert_wishlistWithoutDate_throws400_andSavesNothing() {
        UUID id = UUID.randomUUID();
        FinancialEvent src = openWishlistWithoutDate(id);
        when(eventRepo.findById(id)).thenReturn(Optional.of(src));

        assertThatThrownBy(() -> service.convertItem(id,
                new ConvertWishlistRequestDto("WISHLIST", "PLAN_EVENT", false), TODAY))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("date is required");

        assertThat(src.getWishlistStatus())
                .as("хотелка обязана остаться в обсуждении: артефакта не появилось")
                .isEqualTo(WishlistStatus.OPEN);
        assertThat(src.getConvertedToEventId()).isNull();
        verify(eventRepo, never()).save(any());
    }

    @Test
    @DisplayName("ANO-138: конверсия с датой в прошлом — 400 (обещание спеки, строка 409)")
    void convert_wishlistWithPastDate_throws400() {
        UUID id = UUID.randomUUID();
        FinancialEvent src = openWishlist(id);
        src.setDate(TODAY.minusDays(1));
        when(eventRepo.findById(id)).thenReturn(Optional.of(src));

        assertThatThrownBy(() -> service.convertItem(id,
                new ConvertWishlistRequestDto("WISHLIST", "PLAN_EVENT", false), TODAY))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("must be in the future");
    }

    @Test
    @DisplayName("ANO-138: конверсия сегодняшним числом — 400: сегодняшний план не резервируется")
    void convert_wishlistWithTodayDate_throws400() {
        UUID id = UUID.randomUUID();
        FinancialEvent src = openWishlist(id);
        src.setDate(TODAY);
        when(eventRepo.findById(id)).thenReturn(Optional.of(src));

        assertThatThrownBy(() -> service.convertItem(id,
                new ConvertWishlistRequestDto("WISHLIST", "PLAN_EVENT", false), TODAY))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("must be in the future");
    }
```

- [x] **Step 4: Прогнать — должны падать**

Ожидание: FAIL, все три. Сейчас `convertFromEvent` дату не смотрит: первый тест упадёт на том, что исключения нет вовсе и `save` был вызван.

- [x] **Step 5: Поставить проверку**

В `convertFromEvent`, ветка `PLAN_EVENT`:

```java
            case "PLAN_EVENT" -> {
                // ANO-138: дата обязана быть в будущем — ровно то же правило, что на /fix.
                // Пустая дата давала событие, невидимое в Бюджете, /strategy и кармашке,
                // а хотелка при этом уходила в FIXED: введённое пропадало молча.
                LocalDate planDate = requireFutureDate(src.getDate(), LocalDate.now(clock));
                FinancialEvent created = buildPlanEvent(
                        src.getCategory(), src.getPlannedAmount(), planDate, src.getDescription());
```

- [x] **Step 6: Прогнать — зелено, затем мутации**

```bash
cd backend && JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw -q test -Dtest=WishlistConversionServiceTest
```

Коммит. Затем мутации, каждая с откатом через `git checkout --`:

| мутация | обязан покраснеть |
|---|---|
| `LocalDate planDate = src.getDate()` (убрать вызов проверки) | все три теста Шага 3 — **проверено, 3 падения** |
| `requireFutureDate(src.getDate(), today.minusYears(1))` | `convert_wishlistWithPastDate`, `convert_wishlistWithTodayDate` — **проверено, 2 падения; тест на пустую дату остался зелёным, как и должно** |

Третья мутация («вернуть `src.getDate()` в `buildPlanEvent` мимо проверенной переменной») здесь **невозможна**: пока `planDate` в запросе нет, проверенная переменная и `src.getDate()` — одно и то же значение, и правка тождественна оригиналу. Перенесена в Задачу 2, где `convert_planDateOverridesSourceDate` делает её осмысленной.

---

### Task 2: Срок приезжает в запросе

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/dto/wishlist/ConvertWishlistRequestDto.java`
- Modify: `backend/src/main/java/ru/selfin/backend/service/WishlistConversionService.java`
- Test: `backend/src/test/java/ru/selfin/backend/service/WishlistConversionServiceTest.java`

**Interfaces:**
- Produces: `ConvertWishlistRequestDto.planDate()` — `null` означает «взять срок хотелки».

- [ ] **Step 1: Написать падающие тесты**

```java
    @Test
    @DisplayName("ANO-138: срок из диалога побеждает срок хотелки")
    void convert_planDateOverridesSourceDate() {
        UUID id = UUID.randomUUID();
        FinancialEvent src = openWishlistWithoutDate(id);
        LocalDate chosen = TODAY.plusMonths(2);
        when(eventRepo.findById(id)).thenReturn(Optional.of(src));
        stubEventSave();

        service.convertItem(id,
                new ConvertWishlistRequestDto("WISHLIST", "PLAN_EVENT", false, null, chosen), TODAY);

        ArgumentCaptor<FinancialEvent> cap = ArgumentCaptor.forClass(FinancialEvent.class);
        verify(eventRepo, atLeast(1)).save(cap.capture());
        assertThat(cap.getAllValues()).anySatisfy(e -> {
            assertThat(e.getEventKind()).isEqualTo(EventKind.PLAN);
            assertThat(e.getDate())
                    .as("в план уехал срок из диалога, а не пустота хотелки")
                    .isEqualTo(chosen);
        });
        assertThat(src.getDate())
                .as("исходная хотелка срока не получает: она уходит в архив как решённая")
                .isNull();
    }

    @Test
    @DisplayName("ANO-138: без planDate берётся срок хотелки — старый клиент работает как раньше")
    void convert_withoutPlanDate_usesSourceDate() {
        UUID id = UUID.randomUUID();
        FinancialEvent src = openWishlist(id);        // срок = +6 месяцев
        when(eventRepo.findById(id)).thenReturn(Optional.of(src));
        stubEventSave();

        service.convertItem(id,
                new ConvertWishlistRequestDto("WISHLIST", "PLAN_EVENT", false), TODAY);

        ArgumentCaptor<FinancialEvent> cap = ArgumentCaptor.forClass(FinancialEvent.class);
        verify(eventRepo, atLeast(1)).save(cap.capture());
        assertThat(cap.getAllValues()).anySatisfy(e ->
                assertThat(e.getDate()).isEqualTo(src.getDate()));
    }
```

Второй тест обязан быть: без него правка «всегда брать `planDate`» прошла бы молча и сломала совместимость.

- [ ] **Step 2: Прогнать — не компилируется**

Ожидание: пятикомпонентного конструктора нет.

- [ ] **Step 3: Добавить компонент в DTO**

```java
/**
 * @param planDate для target=PLAN_EVENT: срок создаваемого плана; null = срок источника.
 *                 ANO-138: диалог спрашивает срок у хотелки без него — выдумывать дату
 *                 нельзя (ANO-29), а пустая уводила план в невидимость.
 */
public record ConvertWishlistRequestDto(
        String sourceKind,
        String target,
        Boolean createRecurringPayments,
        LocalDate fundTargetDate,
        LocalDate planDate
) {
    /** Без срока плана (ANO-16 §8: фиксация растянутой примерки). */
    public ConvertWishlistRequestDto(String sourceKind, String target,
                                     Boolean createRecurringPayments, LocalDate fundTargetDate) {
        this(sourceKind, target, createRecurringPayments, fundTargetDate, null);
    }

    /** Старая сигнатура (без переопределения дат). */
    public ConvertWishlistRequestDto(String sourceKind, String target, Boolean createRecurringPayments) {
        this(sourceKind, target, createRecurringPayments, null, null);
    }
}
```

- [ ] **Step 4: Прочитать компонент в сервисе**

```java
                LocalDate planDate = requireFutureDate(
                        req.planDate() != null ? req.planDate() : src.getDate(),
                        LocalDate.now(clock));
```

- [ ] **Step 5: Прогнать весь класс, затем мутации**

```bash
cd backend && rm -rf target/test-classes
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw -q test -Dtest=WishlistConversionServiceTest
```

Коммит. Мутации:

| мутация | обязан покраснеть |
|---|---|
| `req.planDate()` → всегда `src.getDate()` | `convert_planDateOverridesSourceDate` |
| `req.planDate() != null ? ... : null` (убрать запасной путь) | `convert_withoutPlanDate_usesSourceDate` |
| дописать `src.setDate(planDate)` перед сохранением источника | `convert_planDateOverridesSourceDate` (последнее утверждение) |

---

### Task 3: Миграция V25 — возврат уже созданных пустышек

**Files:**
- Create: `backend/src/main/resources/db/migration/V25__return_dateless_conversions.sql`
- Create: `backend/src/test/java/ru/selfin/backend/DatelessConversionMigrationIT.java`

**Interfaces:** ничего в Java-коде не меняется.

- [ ] **Step 1: Написать миграцию**

```sql
-- ANO-138: вернуть в обсуждение хотелки, чья конверсия создала событие с пустой датой.
--
-- До этой починки convertFromEvent брал src.getDate() без проверки. У хотелки без срока
-- (законное «когда-нибудь», WishlistCreateDto.date) это давало плановое событие с
-- date = NULL: его не видно ни в Бюджете, ни в /strategy, ни в кармашке — все выборки
-- идут по диапазону дат. Исходная при этом уходила в FIXED. Человек получал исчезнувшую
-- хотелку и не появившийся план.
--
-- Возвращаем ровно то, что было до нажатия: артефакт помечаем удалённым, источник
-- отпускаем обратно в OPEN. Дату не выдумываем (ANO-29) — человек поставит её сам
-- при повторной конверсии, теперь диалог её спросит.
--
-- Ссылку converted_to_event_id снять ОБЯЗАТЕЛЬНО, и не из-за схемы: ограничение
-- chk_event_converted_only_fixed снято миграцией V22. Причина в ensureNotConverted —
-- он отдаёт 409 на любой записи с непустой ссылкой, и хотелка вернулась бы в список
-- неработоспособной.
--
-- Сироты (событие с пустой датой, на которое никто не ссылается) не трогаются:
-- доказать, что такая запись родилась из конверсии, нечем.
WITH broken AS (
    SELECT s.id AS src_id, e.id AS event_id
    FROM financial_events s
    JOIN financial_events e ON e.id = s.converted_to_event_id
    WHERE s.is_deleted = FALSE
      AND e.is_deleted = FALSE
      AND e.date IS NULL
),
killed AS (
    UPDATE financial_events e
       SET is_deleted = TRUE, updated_at = CURRENT_TIMESTAMP
      FROM broken b
     WHERE e.id = b.event_id
    RETURNING e.id
)
UPDATE financial_events s
   SET wishlist_status = 'OPEN',
       converted_to_event_id = NULL,
       updated_at = CURRENT_TIMESTAMP
  FROM broken b
 WHERE s.id = b.src_id;
```

- [ ] **Step 2: Написать тест по образцу `DisposedFundMigrationIT`**

Прогоном Flyway миграцию не проверить: на Testcontainers её очередь наступает на пустых таблицах, и любое утверждение о результате зелено по построению. Поэтому проверяется **то же SQL на тех же данных** — тело миграции копируется в тест дословно.

```java
/**
 * ANO-138: миграция V25 возвращает в обсуждение хотелки, чья конверсия создала
 * событие с пустой датой.
 *
 * <p>Прогон самой миграции ничего не доказывает: Flyway применяет её к пустой базе
 * контейнера. Проверяется дословная копия её тела на подготовленных данных —
 * расхождение между запросом здесь и запросом в V25 остаётся единственным способом
 * обмануть этот тест, поэтому SQL копируется без изменений.
 */
class DatelessConversionMigrationIT {

    @Test
    @DisplayName("пара «FIXED-хотелка → событие с пустой датой» разбирается: событие удалено, хотелка OPEN")
    void migration_returnsBrokenPairToDiscussion() { /* ... */ }

    @Test
    @DisplayName("здоровая конверсия не трогается: событие со сроком остаётся, хотелка FIXED")
    void migration_leavesHealthyConversionAlone() { /* ... */ }

    @Test
    @DisplayName("сирота с пустой датой не трогается: доказать её происхождение нечем")
    void migration_leavesOrphanAlone() { /* ... */ }
}
```

Второй тест обязателен: без него миграция «пометить удалёнными все события с пустой датой» прошла бы зелёной.

- [ ] **Step 3: Прогнать интеграционные**

```bash
cd backend && JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw -q verify -Dit.test=DatelessConversionMigrationIT
```

- [ ] **Step 4: Мутации миграции**

| мутация | обязан покраснеть |
|---|---|
| убрать `converted_to_event_id = NULL` | тест на повторную готовность пары |
| убрать `AND e.date IS NULL` | `migration_leavesHealthyConversionAlone` |
| заменить `JOIN` на выборку всех событий с пустой датой | `migration_leavesOrphanAlone` |

Коммит.

---

### Task 4: Диалог спрашивает срок

**Files:**
- Modify: `frontend/src/components/wishlist/wishlistUtils.ts`
- Modify: `frontend/src/components/wishlist/wishlistUtils.test.ts`
- Modify: `frontend/src/components/wishlist/FixWishlistDialog.tsx`
- Modify: `frontend/src/components/sandbox/CapitalWhatIf.tsx`
- Modify: `frontend/src/api/index.ts`

**Interfaces:**
- Produces: `canConfirmConversion(target, planDate): boolean`; `onConfirm(target, createRecurring, planDate)`.

- [ ] **Step 1: Падающий тест на чистую функцию**

В `wishlistUtils.test.ts`:

```ts
describe('canConfirmConversion (ANO-138)', () => {
    it('плановое событие без срока подтвердить нельзя', () => {
        expect(canConfirmConversion('PLAN_EVENT', '')).toBe(false);
    });
    it('плановое событие со сроком подтвердить можно', () => {
        expect(canConfirmConversion('PLAN_EVENT', '2026-12-01')).toBe(true);
    });
    it('копилке срок в этом диалоге не нужен', () => {
        expect(canConfirmConversion('FUND', '')).toBe(true);
    });
    it('кредиту срок в этом диалоге не нужен', () => {
        expect(canConfirmConversion('FUND_WITH_CREDIT', '')).toBe(true);
    });
});
```

Два последних случая — не формальность: без них правка «требовать срок всегда» сломала бы копилку и прошла зелёной.

- [ ] **Step 2: `npm test` — падает**

- [ ] **Step 3: Функция**

```ts
/**
 * ANO-138: «Плановое событие» без срока подтвердить нельзя — пустая дата уводила
 * план в невидимость. Копилке срок в этом диалоге не нужен: фонд без targetDate —
 * законное состояние, он виден на экране и дату можно поставить потом.
 */
export function canConfirmConversion(target: ConvertTarget, planDate: string): boolean {
    return target !== 'PLAN_EVENT' || planDate !== '';
}
```

- [ ] **Step 4: Поле в диалоге**

В `FixWishlistDialog`: состояние `planDate`, сбрасываемое в `useEffect` вместе с остальными — `setPlanDate(item.targetDate ?? '')`. Поле показывать при `target === 'PLAN_EVENT'`, кнопку подтверждения гасить по `canConfirmConversion`.

```tsx
                    {target === 'PLAN_EVENT' && (
                        <div className="pl-6 space-y-1">
                            <input
                                type="date"
                                value={planDate}
                                onChange={e => setPlanDate(e.target.value)}
                                className="..."
                            />
                            <p className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
                                Когда планируешь потратить
                            </p>
                        </div>
                    )}
```

```tsx
                    <Button
                        disabled={!canConfirmConversion(target, planDate)}
                        onClick={() => onConfirm(target, createRecurring, planDate || undefined)}
                    >
                        Зафиксировать
                    </Button>
```

Подпись нейтральная намеренно: правило 12 запрещает упрёки, «укажи срок, иначе не выйдет» — упрёк.

- [ ] **Step 5: Проброс до API**

`CapitalWhatIf.handleFixConfirm` получает третий аргумент и кладёт его в тело:

```tsx
    const handleFixConfirm = (target: ConvertTarget, createRecurringPayments: boolean,
                              planDate?: string) => {
        ...
        convertWishlistItem(item.id, { sourceKind: item.kind, target, createRecurringPayments, planDate })
```

В `api/index.ts` — `planDate?: string` в теле `convertWishlistItem`.

- [ ] **Step 6: `npm test` и `tsc`**

```bash
cd frontend && npm test && npx tsc --noEmit
```

Коммит. Мутация: убрать `disabled` с кнопки — красный тест на `canConfirmConversion` не покраснеет (он о функции, не о кнопке). **Это ожидаемо и записано сознательно:** связь функции с кнопкой компонентными тестами здесь не покрывается, её проверяет Задача 6 на стенде.

---

### Task 5: Воспроизведение из тела задачи

**Files:**
- Modify: `backend/src/test/java/ru/selfin/backend/WishlistControllerIT.java`

- [ ] **Step 1: Два теста**

```java
    @Test
    @DisplayName("ANO-138: конверсия хотелки без срока — 400, и в базе не изменилось ничего")
    void convert_wishlistWithoutDate_returns400_andChangesNothing() throws Exception {
        // хотелка «когда-нибудь»: date = null
        // POST /api/v1/wishlist/items/{id}/convert {sourceKind: WISHLIST, target: PLAN_EVENT}
        // → 400
        // → в базе ровно одно живое событие (сама хотелка), её wishlistStatus = OPEN,
        //   convertedToEventId = null
    }

    @Test
    @DisplayName("ANO-138: со сроком из диалога план создаётся и виден в выборке за свой месяц")
    void convert_wishlistWithPlanDate_createsVisibleEvent() throws Exception {
        // тот же вход + planDate = сегодня + 2 месяца
        // → 200
        // → GET /api/v1/events?startDate=..&endDate=.. за месяц planDate содержит созданное
    }
```

Второй тест — не дубль юнита: он проверяет то, из-за чего задача заведена. «Событие создано» и «событие видно» — разные утверждения, и ровно на этом расхождении дефект и жил.

- [ ] **Step 2: Прогнать**

```bash
cd backend && JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw -q verify -Dit.test=WishlistControllerIT
```

- [ ] **Step 3: Полный прогон**

```bash
cd backend && rm -rf target/test-classes
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw verify
cd ../frontend && npm test && npx tsc --noEmit
```

Записать числа: сколько юнитов, сколько интеграционных, сколько фронтовых. Коммит.

---

### Task 6: Стенд и закрытие

- [ ] **Step 1: Проверочные запросы на рабочей базе ДО выкладки**

Оба только читают. Результаты записать в раздел «Выполнено» этого плана.

```sql
SELECT s.id AS wishlist_id, s.description, e.id AS broken_event
FROM financial_events s
JOIN financial_events e ON e.id = s.converted_to_event_id
WHERE s.is_deleted = FALSE AND e.is_deleted = FALSE AND e.date IS NULL;
```

```sql
SELECT e.id, e.description, e.planned_amount
FROM financial_events e
WHERE e.is_deleted = FALSE AND e.date IS NULL
  AND e.priority = 'LOW' AND e.wishlist_status IS NULL
  AND NOT EXISTS (SELECT 1 FROM financial_events s
                  WHERE s.converted_to_event_id = e.id AND s.is_deleted = FALSE);
```

Если сирот окажется заметно много — не расширять миграцию, а завести отдельную задачу и записать замер туда.

- [ ] **Step 2: Прогон по экрану**

Бэкенд из исходников на 8090, фронт на своём порту (Vite слушает только IPv6 — ходить на `localhost`, не на `127.0.0.1`).

Сценарий: завести хотелку без срока → «Зафиксировать» → «Плановое событие» → убедиться, что кнопка неактивна и видна подпись → поставить срок → подтвердить → открыть Бюджет на этом месяце и увидеть план. Скриншот до и после.

Отдельно: хотелка со сроком — поле предзаполнено, лишних действий не прибавилось.

- [ ] **Step 3: Стенд вернуть к эталону**

Капитал 2 668 277, ликвид 210 000, остаток 60 000, якорь 29.08, галочки прогноза сняты. Заведённые для проверки записи удалить.

- [ ] **Step 4: Linear и PR**

ANO-138 → Done с комментарием: что оказалось шире тела задачи (прошлая дата, вторая точка на Аналитике), что вынесено (ANO-161), какие две неточности в описании сняты разбором.

PR с описанием по образцу прошлых. После открытия — дождаться Codex и разобрать его замечания до мёржа.

---

## Заметки для исполнителя

**Не чинить по дороге.** Показ ошибки человеку — ANO-141; двойники в журнале — ANO-106; блок «Хотелки» на Аналитике — ANO-161; отклонённая хотелка на «Целях» — ANO-107. Соблазн будет: всё это лежит в тех же двух файлах.

**Копилка без даты цели — не дефект.** `target_funds.target_date` объявлена `null = не указана` с V4, `PocketInputAssembler` такую копилку явно пропускает в резервировании, и она **видна** на экране. Не трогать.

**`perl -0pi -e` молча не матчится.** Для правок длиннее строки — `Edit`; после `perl` — обязательный `grep` на результат.
