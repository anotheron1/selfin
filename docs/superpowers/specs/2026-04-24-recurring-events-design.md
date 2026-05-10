# Recurring Events — Design Spec

**Дата:** 2026-04-24
**Статус:** Draft
**Область:** backend (новая сущность `RecurringRule` + сервис + расширение API), frontend (чекбокс «Повторять» в форме, scope-picker в редактировании, иконка ↻ в Budget).

## Цель

Позволить пользователю создавать повторяющиеся финансовые события (ипотека, аренда, коммуналка, подписки, зарплата) одним правилом вместо ручного заведения десятков событий. Правило порождает материализованные `FinancialEvent` в БД — весь существующий код (Budget, Dashboard, Analytics, планировщик копилок) продолжает работать без изменений. Редактирование и удаление поддерживают scope (this / following / all) с жёсткой инвариантой «EXECUTED-события не трогаются никогда».

Это инфраструктурная фича, которая закрывает две боли:
1. Дальше двух месяцев в Budget/планировщике почти пусто — регулярные платежи руками никто не заводит на 3 года вперёд.
2. Рутина заведения одних и тех же событий каждый месяц убивает привычку вести бюджет.

## Scope

**В scope:**
- Новая таблица `recurring_rule` + FK-колонка на `financial_events`.
- Новый сервис-генератор и сервис-координатор.
- Расширение `POST /events`, `PUT /events/{id}`, `DELETE /events/{id}` — создание правила и scope-semantics.
- UI: чекбокс в форме создания, inline scope-picker в форме редактирования, модалка scope при удалении, иконка ↻ в Budget.

**Out of scope** (фиксируем явно, см. раздел «Future work»):
- Отдельный экран «Регулярные платежи» (CRUD-лист правил).
- FUND_TRANSFER recurring — ждёт PR2.
- Все правки стратегического графика и мультимесячный прогноз — отдельные spec'и.
- Retroactive-создание правил (start_date в прошлом).
- WEEKLY, BIWEEKLY частоты.

---

## Секция 1 — Модель данных

### Таблица `recurring_rule`

```sql
CREATE TABLE recurring_rule (
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    category_id    UUID        NOT NULL REFERENCES categories(id),
    event_type     VARCHAR(20) NOT NULL CHECK (event_type IN ('INCOME', 'EXPENSE')),
    planned_amount NUMERIC(19,2) NOT NULL CHECK (planned_amount >= 0),
    priority       VARCHAR(10) NOT NULL DEFAULT 'MEDIUM' CHECK (priority IN ('HIGH', 'MEDIUM', 'LOW')),
    description    VARCHAR(255),
    frequency      VARCHAR(10) NOT NULL CHECK (frequency IN ('MONTHLY', 'YEARLY')),
    day_of_month   INTEGER     NOT NULL CHECK (day_of_month BETWEEN 1 AND 31),
    month_of_year  INTEGER     CHECK (month_of_year BETWEEN 1 AND 12),
    start_date     DATE        NOT NULL,
    end_date       DATE,
    is_deleted     BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMP,
    CONSTRAINT chk_monthly_no_month CHECK (frequency <> 'MONTHLY' OR month_of_year IS NULL),
    CONSTRAINT chk_yearly_has_month CHECK (frequency <> 'YEARLY'  OR month_of_year IS NOT NULL),
    CONSTRAINT chk_end_after_start  CHECK (end_date IS NULL OR end_date >= start_date)
);

CREATE INDEX idx_recurring_rule_not_deleted
    ON recurring_rule (is_deleted) WHERE is_deleted = FALSE;
```

### Колонка FK на `financial_events`

```sql
ALTER TABLE financial_events
    ADD COLUMN recurring_rule_id UUID REFERENCES recurring_rule(id);

CREATE INDEX idx_events_recurring_rule
    ON financial_events (recurring_rule_id)
    WHERE recurring_rule_id IS NOT NULL;

-- Защита от дублирования при lazy-extend (см. Секцию 2 → «Конкурентность»)
CREATE UNIQUE INDEX uq_events_rule_date_active
    ON financial_events (recurring_rule_id, date)
    WHERE recurring_rule_id IS NOT NULL AND is_deleted = FALSE;
```

Партишн-уник `(recurring_rule_id, date) WHERE recurring_rule_id IS NOT NULL AND is_deleted = FALSE` гарантирует: для одного правила в одну и ту же дату не может быть двух активных событий. Soft-deleted события не учитываются — `regenerate` сначала помечает старые `deleted=true`, потом вставляет новые.

### JPA-маппинг (следуем существующей конвенции)

В кодбазе `FinancialEvent` уже связан с `Category` через ассоциацию, а не UUID-поле:

```java
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "category_id", nullable = false)
private Category category;
```

Следуем тому же паттерну для `recurring_rule_id`:

```java
// FinancialEvent.java
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "recurring_rule_id")   // nullable = true по умолчанию
private RecurringRule recurringRule;

// RecurringRule.java
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "category_id", nullable = false)
private Category category;

@Column(name = "is_deleted", nullable = false)
private boolean deleted = false;
```

**Кодинг-конвенция soft-delete:** В существующих сущностях SQL-колонка зовётся `is_deleted`, а Java-поле — `deleted` через `@Column(name = "is_deleted")`. `RecurringRule` следует этой же схеме. В псевдокоде ниже встречается `rule.setDeleted(true)` / `event.setDeleted(true)` — это про Java-поле; на уровне БД пишется в `is_deleted`.

**Null-safe доступ к правилу:** Псевдокод этого spec'а часто использует короткий `event.getRecurringRuleId()` для читаемости. На самом деле это означает `event.getRecurringRule() != null ? event.getRecurringRule().getId() : null` — никаких raw UUID-полей в сущностях, держим единый стиль с `Category`. При имплементации заведите хелпер `boolean event.isRecurring()` или `Optional<UUID> event.recurringRuleId()` — чтобы не дублировать null-чек в трёх местах.

### Инварианты (канонический список)

| # | Правило | Уровень |
|---|---------|---------|
| I1 | `frequency=MONTHLY` ⇒ `month_of_year IS NULL`; `frequency=YEARLY` ⇒ `month_of_year IS NOT NULL` | CHECK |
| I2 | `end_date IS NULL OR end_date >= start_date` | CHECK |
| I3 | При создании: `start_date >= today` (today не статичен, в БД не загоняем) | сервис |
| I4 | EXECUTED-события (`status=EXECUTED`) никогда не модифицируются и не удаляются правилом | сервис |
| I5 | Правило — источник истины только для PLAN-событий в будущем; EXECUTED-прошлое независимо | сервис |
| I6 | `scope=FOLLOWING\|ALL` валиден только для recurring-событий (на одиночных → 400) | сервис |
| I7 | Clamp `day_of_month` к `lengthOfMonth` целевого месяца — стандарт для всех вычислений дат (31 → 28/29/30) | генератор |
| I8 | `start_date` нельзя редактировать через edit-эндпоинт. Хочешь другой старт — удали правило и создай заново. Упрощает контракт, избегает сложной логики «что делать с событиями между старым и новым start». | сервис |
| I9 | `recurring_rule_id` на событии ≠ null ⇔ событие порождено этим правилом | сервис |
| I10 | `scope=FOLLOWING\|ALL` на edit/delete теряет ранее сделанные `scope=THIS` правки в перезатрагиваемом интервале PLAN-событий. Это сознательный trade-off: per-instance override (preservation отклонений) — out of scope (см. Future work #8). | сервис |

Этот список — источник истины. Финальная таблица «Ключевые инварианты» в конце документа дублирует его только для удобства чтения; при расхождении ориентируемся на этот раздел.

### Миграция

`V16__add_recurring_events.sql` — аддитивная. Не трогает существующие данные.

### Семантика источника истины

Правило — **источник истины для ненаступивших PLAN-событий**. События, уже материализованные (в том числе те, что получили FACT и перешли в EXECUTED) — живут независимо. При scope=FOLLOWING/ALL правило перегенерирует будущие PLAN-события, но EXECUTED не трогает.

---

## Секция 2 — Генератор событий

### Класс

`ru.selfin.backend.service.RecurringEventGenerator` — stateless `@Component`. Работает с чистыми структурами, ничего не сохраняет.

### Публичный метод

```java
List<FinancialEvent> generate(RecurringRule rule, LocalDate from, LocalDate through);
```

Генерирует список событий для правила `rule` на интервале `[from, through]`. Вызывающий сохраняет их сам.

### Алгоритм генерации дат

```
cursor = firstDateOnOrAfter(from, rule)
dates = []
while cursor <= through:
    dates.add(cursor)
    cursor = nextDate(cursor, rule)
```

### Вычисление первой даты `>= from`

**MONTHLY:**
```java
LocalDate firstMonthlyOnOrAfter(LocalDate from, int dayOfMonth) {
    YearMonth ym = YearMonth.from(from);
    int candidateDay = Math.min(dayOfMonth, ym.lengthOfMonth());
    LocalDate candidate = ym.atDay(candidateDay);
    return candidate.isBefore(from) ? nextMonthly(candidate, dayOfMonth) : candidate;
}
```

**YEARLY:**
```java
LocalDate firstYearlyOnOrAfter(LocalDate from, int monthOfYear, int dayOfMonth) {
    int year = from.getYear();
    YearMonth ym = YearMonth.of(year, monthOfYear);
    int candidateDay = Math.min(dayOfMonth, ym.lengthOfMonth());
    LocalDate candidate = ym.atDay(candidateDay);
    return candidate.isBefore(from)
        ? nextYearly(candidate, monthOfYear, dayOfMonth)
        : candidate;
}
```

**Edge:** если `rule.start_date > through` → возвращаем пустой список (guard в начале `generate`).

### Вычисление даты с clamp'ом

```java
// MONTHLY: +1 месяц, clamp dayOfMonth к lengthOfMonth
LocalDate nextMonthly(LocalDate cursor, int dayOfMonth) {
    YearMonth ym = YearMonth.from(cursor).plusMonths(1);
    int day = Math.min(dayOfMonth, ym.lengthOfMonth());
    return ym.atDay(day);
}

// YEARLY: +1 год, месяц фиксирован, clamp dayOfMonth (для 29 фев в не-високосные)
LocalDate nextYearly(LocalDate cursor, int monthOfYear, int dayOfMonth) {
    YearMonth ym = YearMonth.of(cursor.getYear() + 1, monthOfYear);
    int day = Math.min(dayOfMonth, ym.lengthOfMonth());
    return ym.atDay(day);
}
```

### Границы при первичной генерации

```java
LocalDate horizonEnd = rule.getEndDate() != null
    ? rule.getEndDate()
    : LocalDate.now().plusMonths(36);   // бессрочные
```

### Lazy-расширение бессрочных

Отдельный метод в `RecurringRuleService`:

```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
void extendIndefiniteRules(LocalDate requiredThrough) {
    for (UUID ruleId : ruleRepo.findIndefiniteActiveIds()) {
        // Pessimistic lock — параллельный getPlanner() ждёт коммита.
        RecurringRule rule = ruleRepo.findForUpdate(ruleId).orElseThrow();
        LocalDate maxExisting = eventRepo.findMaxActiveDateByRule(ruleId).orElse(null);
        LocalDate from = maxExisting != null
            ? maxExisting.plusDays(1)
            : rule.getStartDate();
        if (from.isAfter(requiredThrough)) continue;
        List<FinancialEvent> extra = generator.generate(rule, from, requiredThrough);
        eventRepo.saveAll(extra);
    }
}
```

**`REQUIRES_NEW` обязательно.** Вызывающий `FundPlannerService.getPlanner()` помечен `@Transactional(readOnly = true)` — без отдельной транзакции запись (`saveAll`) упадёт или молча ничего не сделает. `REQUIRES_NEW` даёт нам write-транзакцию внутри read-only вызывающего. Если расширение упало — не заваливаем весь запрос планировщика, логируем и читаем то, что есть.

Вызывается в `FundPlannerService.getPlanner()` первым шагом с `requiredThrough = LocalDate.now().plusMonths(36)`. Идемпотентно — при повторе ничего не делает (все даты уже материализованы, `from.isAfter(requiredThrough)` → continue).

### Конкурентность

Два параллельных запроса `GET /api/v1/funds/planner` могут оба попасть в `extendIndefiniteRules` до того, как первый зафиксирует транзакцию. Без защиты они оба прочитают `maxExisting` для одного и того же правила и оба вставят одинаковый набор событий → дубликаты.

Защита двухуровневая:

1. **Pessimistic lock на правиле.** Внутри цикла берём `SELECT ... FOR UPDATE` через JPA:
   ```java
   @Lock(LockModeType.PESSIMISTIC_WRITE)
   @Query("SELECT r FROM RecurringRule r WHERE r.id = :id")
   Optional<RecurringRule> findForUpdate(@Param("id") UUID id);
   ```
   Реализация в цикле:
   ```java
   for (UUID ruleId : ruleRepo.findIndefiniteActiveIds()) {
       RecurringRule rule = ruleRepo.findForUpdate(ruleId).orElseThrow();
       // ... генерация и сохранение
   }
   ```
   Параллельный запрос на этом правиле блокируется до коммита. После коммита читает уже материализованные события и `maxExisting + 1 > requiredThrough` → continue.

2. **Уник-индекс `uq_events_rule_date_active`** (см. Секцию 1) — last line of defense. Если первый уровень дал сбой (например, сервис изменил уровень изоляции), уник-индекс выдаст `DataIntegrityViolationException` вместо тихих дубликатов.

Метод `findIndefiniteActiveIds()` возвращает только `id` (не сами правила) — чтобы не блокировать ничего лишнего на первом запросе.

### Репозиторные методы (новые)

Все predicate'ы фиксируем явно — это контракт, на который опирается псевдокод выше.

**`RecurringRuleRepository`:**

```java
// «Активное» = не soft-deleted И без end_date.
// Используется в extendIndefiniteRules.
@Query("SELECT r.id FROM RecurringRule r " +
       "WHERE r.deleted = false AND r.endDate IS NULL")
List<UUID> findIndefiniteActiveIds();

@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT r FROM RecurringRule r WHERE r.id = :id")
Optional<RecurringRule> findForUpdate(@Param("id") UUID id);
```

**`FinancialEventRepository` (новые):**

```java
// Максимальная дата активного PLAN-события правила.
// EXECUTED-события игнорируются — мы продлеваем PLAN-будущее,
// а EXECUTED уже за горизонтом lazy-extend по определению.
@Query("SELECT MAX(e.date) FROM FinancialEvent e " +
       "WHERE e.recurringRule.id = :ruleId " +
       "  AND e.deleted = false " +
       "  AND e.status = 'PLANNED'")
Optional<LocalDate> findMaxActiveDateByRule(@Param("ruleId") UUID ruleId);

@Modifying
@Query("UPDATE FinancialEvent e SET e.deleted = true, e.updatedAt = CURRENT_TIMESTAMP " +
       "WHERE e.recurringRule.id = :ruleId " +
       "  AND e.deleted = false " +
       "  AND e.status = 'PLANNED' " +
       "  AND e.date >= :fromDate")
int softDeletePlanEventsByRuleFromDate(@Param("ruleId") UUID ruleId,
                                       @Param("fromDate") LocalDate fromDate);

@Query("SELECT e.date FROM FinancialEvent e " +
       "WHERE e.recurringRule.id = :ruleId " +
       "  AND e.deleted = false " +
       "  AND e.status = 'EXECUTED'")
Set<LocalDate> findExecutedDatesByRule(@Param("ruleId") UUID ruleId);

@Query("SELECT MAX(e.date) FROM FinancialEvent e " +
       "WHERE e.recurringRule.id = :ruleId " +
       "  AND e.deleted = false " +
       "  AND e.status = 'EXECUTED'")
Optional<LocalDate> findMaxExecutedDateByRule(@Param("ruleId") UUID ruleId);

@Query("SELECT e FROM FinancialEvent e " +
       "WHERE e.recurringRule.id = :ruleId " +
       "  AND e.date = :date " +
       "  AND e.deleted = false")
Optional<FinancialEvent> findActiveByRuleAndDate(@Param("ruleId") UUID ruleId,
                                                 @Param("date") LocalDate date);
```

Все методы фильтруют `deleted = false`. EXECUTED-методы дополнительно фильтруют статус — для I4. PLAN-методы тоже фильтруют статус — чтобы случайно не задеть FACT-snapshot'ы.

---

## Секция 3 — API

### Создание: расширение `POST /events`

```http
POST /api/v1/events
Content-Type: application/json
Idempotency-Key: <uuid>

{
  "date": "2026-05-15",
  "categoryId": "...",
  "type": "EXPENSE",
  "plannedAmount": 80000,
  "priority": "HIGH",
  "description": "Ипотека",

  "recurring": {
    "frequency": "MONTHLY",
    "dayOfMonth": 15,
    "monthOfYear": null,
    "endDate": "2035-01-15"
  }
}
```

- `recurring` отсутствует → одиночное событие (текущая логика).
- `recurring` есть → создаётся `RecurringRule` + генерируются события от `date` до `endDate` (или +36 мес).
- `start_date` правила = `date` из тела.
- Возврат — DTO созданного события (первого экземпляра).

**Idempotency-Key — семантика при создании recurring:**

В кодбазе нет отдельной таблицы `idempotency_keys` — ключ хранится на самом `financial_events.idempotency_key`. Это значит:

- Ключ ставится только на «головное» событие (первое сгенерированное правилом — то, чья дата = `dto.date`). Остальные сгенерированные события идут с `idempotency_key = NULL` — их клиент не запрашивал индивидуально.
- Создание правила + всех событий обёрнуто в один `@Transactional` метод. Откат любого `INSERT` откатывает всё (правило, все события, idempotency_key головы).
- При ретрае: `findByIdempotencyKey(key)` находит головное событие → возвращаем его DTO без новой генерации. Уже существующее правило и хвост событий не трогаем.
- Если первый запрос упал ДО коммита — никакой записи нет, ретрай пройдёт как первый запуск. Это и есть текущая семантика для одиночного POST /events.

Никаких изменений в существующий идемпотентный механизм не вносим — головное событие закрывает контракт, остальные — детерминированный side-effect его создания.

### Редактирование: добавляем query-параметр `scope`

```http
PUT /api/v1/events/{id}?scope=THIS|FOLLOWING|ALL
```

- Без `scope` или `scope=THIS` — обычное обновление одного события.
- `scope=FOLLOWING` — требует `recurring_rule_id != null`. Обновляет поля правила, перегенерирует PLAN-события с `date` этого экземпляра и далее.
- `scope=ALL` — аналогично FOLLOWING, но перегенерация от `start_date` правила.

Валидация: `scope=FOLLOWING|ALL` на не-recurring → `400 Bad Request` через `GlobalExceptionHandler` + `ErrorResponse`.

### Удаление: симметрично

```http
DELETE /api/v1/events/{id}?scope=THIS|FOLLOWING|ALL
```

- `scope=THIS` — soft-delete одного PLAN-события.
- `scope=FOLLOWING` — soft-delete всех PLAN c `date >= event.date`. Rule.end_date = event.date.minusDays(1).
- `scope=ALL` — soft-delete всех PLAN + rule.is_deleted=true. Rule.end_date = max(executed.date) или start_date.minusDays(1) если EXECUTED нет.

### Чтение: расширение `FinancialEventDto`

```java
UUID recurringRuleId;                    // null если не recurring
RecurringFrequency recurringFrequency;   // MONTHLY | YEARLY, null иначе
Integer recurringDayOfMonth;             // 1..31, null иначе — нужен для tooltip'а
Integer recurringMonthOfYear;            // 1..12, только для YEARLY, null иначе
```

Поля `recurringDayOfMonth` / `recurringMonthOfYear` нужны фронту, чтобы нарисовать tooltip («Повторяется ежемесячно 15-го числа» / «Повторяется 15 августа каждого года») без отдельного запроса правила. `frequency` одного мало — в tooltip'е зашит день, а он живёт в правиле.

### FACT-события: наследование `recurring_rule_id`

Когда пользователь вносит факт по PLAN-событию, в текущем коде создаётся (или обновляется) FACT с `parent_event_id = plan.id`. Чтобы UI-иконка ↻ (раздел 5.2) показывалась и на FACT-строках, FACT должен наследовать `recurring_rule_id` от родительского PLAN'а **в момент создания** — это статический snapshot, не runtime-резолв.

Контракт:

- `FinancialEventService.createFact(planId, factDto)`: при `INSERT` нового FACT-события копируем `recurring_rule_id` из `plan` в FACT.
- В DTO-маппере `toDto(event)`: `recurringRuleId = event.getRecurringRule() != null ? event.getRecurringRule().getId() : null` — обращаемся к собственному полю, не к parent'у. Это значит: если PLAN был recurring, FACT-snapshot тоже считается recurring и получает иконку.
- При `regenerate` правила EXECUTED-события (т.е. PLAN'ы со статусом EXECUTED, у которых FACT уже привязан) не удаляются (I4) — соответственно ни один FACT не остаётся «осиротевшим» с битой ссылкой.
- При `scope=ALL delete`: rule.deleted=true, PLAN'ы soft-deleted, но FACT-события остаются живыми и продолжают показывать ↻ (это исторический факт — он действительно был частью повторяющегося правила).

### Что НЕ добавляем

- Отдельный `GET/POST/PUT/DELETE /api/v1/recurring-rules` — future work.
- `Idempotency-Key` на PUT/DELETE — редактирование идемпотентно по природе.

---

## Секция 4 — Сервисный слой

### Новый сервис

`ru.selfin.backend.service.RecurringRuleService` — координирует операции над правилом + порождёнными событиями. Все методы `@Transactional`.

### Создание

В `FinancialEventService.create`:

```java
if (dto.recurring != null) {
    RecurringRule rule = ruleService.createFromDto(dto);
    LocalDate horizonEnd = rule.getEndDate() != null
        ? rule.getEndDate()
        : LocalDate.now().plusMonths(36);
    List<FinancialEvent> events = generator.generate(rule,
        rule.getStartDate(),
        horizonEnd);
    eventRepo.saveAll(events);
    return toDto(events.get(0));
}
// иначе — текущая логика одиночного события
```

### Редактирование

```java
FinancialEventDto update(UUID eventId, ScopeEnum scope, EventUpdateDto dto) {
    FinancialEvent event = eventRepo.findById(eventId).orElseThrow();
    RecurringRule rule = event.getRecurringRule();   // null если не recurring

    // 1. Reject FOLLOWING/ALL на не-recurring сразу — иначе THIS-ветка
    //    проглотила бы невалидный scope незаметно.
    if (scope != THIS && rule == null) {
        throw new BadRequestException("Scope requires recurring event");
    }

    // 2. THIS — правило не трогаем, модифицируем только этот экземпляр.
    //    Возвращаем DTO самого изменённого события.
    if (scope == THIS) {
        applyDto(event, dto);
        return toDto(event);
    }

    // 3. FOLLOWING / ALL — правило существует (проверка выше), обновляем его и regenerate.
    //    I8: dto.startDate (если присутствует) валидируется как ошибка.
    rejectStartDateInDto(dto);   // см. helper-контракты ниже
    applyDtoToRule(rule, dto);
    ruleRepo.save(rule);

    LocalDate regenerateFrom = (scope == FOLLOWING)
        ? event.getDate()
        : rule.getStartDate();
    regenerate(rule, regenerateFrom);

    // I4 гарантирует: если `event` был EXECUTED, его дата осталась в наборе.
    //                Если был PLAN, то после regenerate он soft-deleted, но новый
    //                экземпляр с той же датой создан — возвращаем именно его.
    return toDto(eventRepo.findActiveByRuleAndDate(rule.getId(), event.getDate())
        .orElseThrow(() -> new IllegalStateException("Regenerate dropped the triggering date")));
}
```

**Helper-контракты (приватные методы сервиса):**

```java
// I8 enforcement: попытка изменить start_date через edit-эндпоинт → 400.
// Вызывается ТОЛЬКО для scope=FOLLOWING/ALL — на scope=THIS правило не трогаем.
void rejectStartDateInDto(EventUpdateDto dto) {
    if (dto.recurring() != null && dto.recurring().startDate() != null) {
        throw new BadRequestException(
            "start_date is immutable; delete the rule and create a new one");
    }
}

// Копирует обновляемые поля из DTO в правило. Поля правила, которые можно менять:
//   plannedAmount, priority, description, frequency, dayOfMonth, monthOfYear, endDate.
// Не меняются: id, category_id, event_type, start_date, created_at, deleted.
// Категорию и тип не пускаем — это требует пересоздания правила (out of scope).
void applyDtoToRule(RecurringRule rule, EventUpdateDto dto) {
    if (dto.plannedAmount()  != null) rule.setPlannedAmount(dto.plannedAmount());
    if (dto.priority()       != null) rule.setPriority(dto.priority());
    if (dto.description()    != null) rule.setDescription(dto.description());
    if (dto.recurring() != null) {
        var r = dto.recurring();
        if (r.frequency()    != null) rule.setFrequency(r.frequency());
        if (r.dayOfMonth()   != null) rule.setDayOfMonth(r.dayOfMonth());
        if (r.monthOfYear()  != null) rule.setMonthOfYear(r.monthOfYear());
        // endDate — null допустим (значит «сделать бессрочным»).
        rule.setEndDate(r.endDate());
    }
    // Перепроверяем CHECK-инварианты I1, I2 на уровне сервиса перед save —
    // CHECK constraint всё равно сработает, но 400 человекочитаемее, чем 500.
    validateRuleInvariants(rule);
}
```

### Ядро — `regenerate(rule, from)`

```java
void regenerate(RecurringRule rule, LocalDate from) {
    LocalDate horizonEnd = rule.getEndDate() != null
        ? rule.getEndDate()
        : LocalDate.now().plusMonths(36);

    // 1. Soft-delete PLAN-события правила с date >= from (EXECUTED не трогаем)
    eventRepo.softDeletePlanEventsByRuleFromDate(rule.getId(), from);

    // 2. Собрать даты EXECUTED по правилу — их в новом наборе пропускаем
    Set<LocalDate> executedDates = eventRepo.findExecutedDatesByRule(rule.getId());

    // 3. Сгенерировать заново
    List<FinancialEvent> fresh = generator.generate(rule, from, horizonEnd).stream()
        .filter(e -> !executedDates.contains(e.getDate()))
        .toList();

    eventRepo.saveAll(fresh);
}
```

Ключевая инвариант (I4): EXECUTED-события не удаляются в (1) — репозиторный метод `softDeletePlanEventsByRuleFromDate` фильтрует `status = PLANNED`. В (3) они пропускаются при генерации.

### Удаление

```java
void delete(UUID eventId, ScopeEnum scope) {
    FinancialEvent event = eventRepo.findById(eventId).orElseThrow();
    RecurringRule rule = event.getRecurringRule();

    // 1. Reject FOLLOWING/ALL на не-recurring сразу.
    if (scope != THIS && rule == null) {
        throw new BadRequestException("Scope requires recurring event");
    }

    // 2. THIS на одиночном или recurring → soft-delete только этого экземпляра.
    if (scope == THIS || rule == null) {
        event.setDeleted(true);
        return;
    }

    LocalDate cutoff = (scope == FOLLOWING) ? event.getDate() : rule.getStartDate();
    eventRepo.softDeletePlanEventsByRuleFromDate(rule.getId(), cutoff);

    if (scope == FOLLOWING) {
        rule.setEndDate(event.getDate().minusDays(1));
    } else { // ALL
        rule.setDeleted(true);
        LocalDate lastExec = eventRepo.findMaxExecutedDateByRule(rule.getId());
        rule.setEndDate(lastExec != null ? lastExec : rule.getStartDate().minusDays(1));
    }
    ruleRepo.save(rule);
}
```

### Lazy-расширение бессрочных

`extendIndefiniteRules(LocalDate.now().plusMonths(36))` вызывается в `FundPlannerService.getPlanner()` в самом начале. За счёт `Propagation.REQUIRES_NEW` работает в отдельной write-транзакции поверх read-only вызывающего.

### Транзакции и ошибки

- Все мутации — `@Transactional` целиком (правило + события атомарно).
- `extendIndefiniteRules` — в транзакции вызывающего метода.
- Ошибки маппятся через существующий `GlobalExceptionHandler`:
  - Правило/событие не найдено → 404
  - scope=FOLLOWING/ALL на не-recurring → 400 (`ErrorResponse`)
  - Валидация DTO (start_date, end_date, day_of_month, frequency↔month_of_year) → 400

---

## Секция 5 — UI

### 5.1 Создание: чекбокс в `EventSheet`

Новый блок под полями даты/суммы:

```
☐ Повторять
```

При включении раскрывается:

```
☑ Повторять
┌─────────────────────────────────────┐
│ Частота    [ Ежемесячно ▾ ]         │
│ День       [ 15 ]                   │
│ Месяц      (только для "Ежегодно")  │
│ Окончание  (•) Бессрочно            │
│            ( ) До даты  [___]       │
└─────────────────────────────────────┘
```

- Частота: MONTHLY | YEARLY (radio или select)
- День автоподставляется из `event.date`, перезаписываем вручную.
- Месяц появляется только для YEARLY, автоподставляется из `event.date`.
- Окончание: radio (бессрочно / до даты).

Валидация на фронте дублирует бэкенд (`start_date >= today`, `day_of_month 1–31`, `end_date >= start_date`).

### 5.2 Отображение в Budget

Каждое recurring-событие получает маленькую иконку ↻ (Lucide `Repeat`, 12px, `var(--color-text-muted)`). Позиция — рядом с суммой.

Hover tooltip: `Повторяется ежемесячно 15-го числа` или `Повторяется 15 августа каждого года`.

FACT-события, унаследовавшие parent_plan от recurring PLAN, тоже отображают иконку.

### 5.3 Редактирование: inline scope-picker

В `EditEventSheet`, над кнопками Save/Cancel, отдельным блоком:

```
┌─────────────────────────────────────────────┐
│ ↻ Это повторяющееся событие                 │
│ Изменения применить к:                      │
│  ( ) Только к этому                         │
│  (•) К этому и следующим                    │
│  ( ) Ко всем                                │
└─────────────────────────────────────────────┘
```

- Блок рендерится только когда `event.recurringRuleId != null`.
- Default = FOLLOWING (из Q4).
- При save scope передаётся в `?scope=` query-параметр.

### UX-принцип: edit inline, delete modal

Edit — обратимая операция (снова открыл форму, поправил). Scope выбирается inline, без лишнего клика. Контекст всегда виден — форма уже открыта, поля на глазах.

Delete — необратимая (soft-delete, но на уровне UX откат не предусмотрен). Scope=ALL удаляет всё правило разом — это тот класс действия, где нужна отдельная пауза «ты точно?». Модалка даёт эту паузу и отделяет destructive-действие от обычного редактирования.

### 5.4 Удаление: модалка scope

При клике Delete на recurring-событии — confirmation-модалка:

```
┌──────────────────────────────────────────────┐
│ Удалить повторяющееся событие?               │
│                                              │
│  ( ) Только это                              │
│  (•) Это и все следующие                     │
│  ( ) Все (правило будет удалено)             │
│                                              │
│  Исполненные события прошлого не удаляются   │
│                                              │
│                     [Отмена]  [Удалить]      │
└──────────────────────────────────────────────┘
```

### 5.5 Что НЕ трогаем

- `SavingsStrategySection` и график — никаких изменений в этом spec'е.
- Dashboard, Journal, Analytics — без изменений.
- Settings — отдельного раздела «Регулярные платежи» нет.

---

## Секция 6 — Тестирование

### Unit (без Spring)

**`RecurringEventGenerator`** — критичные edge-case'ы:

- MONTHLY 31-го: clamp в феврале (28/29), 30-дневных месяцах (30).
- YEARLY 29 февраля: clamp к 28 в невисокосный.
- `start_date == end_date`: ровно одно событие.
- `end_date` между периодами (15-го до 2035-01-10): последний экземпляр 2034-12-15, не 2035-01-15.
- Бессрочное + horizon = start + 36 мес: 36 или 37 экземпляров в зависимости от дня.
- Day=1 каждого месяца: без clamp, всегда 1-го.

**`RecurringRuleService.regenerate`** (с моком репозитория):

- scope=THIS не вызывает regenerate.
- scope=FOLLOWING удаляет PLAN с `date >= cutoff`, пересоздаёт.
- scope=ALL удаляет все PLAN, пересоздаёт от start_date.
- EXECUTED с данной датой пропускается при генерации (нет дубля).
- `softDeletePlanEventsByRuleFromDate` не задевает EXECUTED (проверяется на уровне SQL в integration).

### Integration (Testcontainers + PostgreSQL)

1. Create MONTHLY + end_date через 12 мес → одно правило + 12 событий с FK.
2. Create + scope=FOLLOWING edit amount на середине горизонта → первые 6 старой суммы, следующие 6 новой.
3. Create + FACT одного события + scope=ALL edit amount → FACT не изменён, остальные PLAN новой суммы.
4. Create + scope=ALL delete → rule.is_deleted=true, PLAN soft-deleted, FACT живы.
5. Create бессрочное + `extendIndefiniteRules` на следующий месяц → добавлено 1 событие.
6. Retroactive-создание отклоняется валидацией: POST /events с `recurring.start_date < LocalDate.now()` → 400 Bad Request. Правило не создано, ни одного события в БД не появилось. (Это проверка самого факта валидации инварианта I3, а не поддержка фичи — см. «Out of scope».)
7. Create YEARLY 29 февраля → первый экземпляр 29 фев (если старт в високосный) или 28 фев (если нет).
8. PUT /events/{id}?scope=ALL с попыткой изменить `recurring.startDate` правила → 400 Bad Request, ничего не изменилось (I8 — start_date неизменяем после создания).
9. PATCH /events/{planId}/fact на recurring PLAN → созданный FACT-event имеет тот же `recurring_rule_id`, что и parent PLAN. DTO FACT-события возвращает `recurringRuleId != null`, на UI рисуется ↻.

### Контроллер-тесты (`@WebMvcTest`)

- POST /events с `recurring` блоком → 201 + Location.
- PUT /events/{id}?scope=FOLLOWING на recurring → 200.
- PUT /events/{id}?scope=FOLLOWING на не-recurring → 400 + `ErrorResponse`.
- DELETE /events/{id}?scope=ALL → 200, проверяем soft-delete и правила и событий.
- Невалидный `recurring.frequency=WEEKLY` → 400.

### Frontend

- `EventSheet` с чекбоксом «Повторять»: заполнение, submit, проверка payload.
- `EditEventSheet` показывает scope-picker только когда `recurringRuleId != null`.
- Default scope = FOLLOWING.
- `start_date < today` → inline ошибка формы.

### Manual smoke

- Иконка ↻ в Budget рядом с recurring-событием.
- Tooltip на hover.
- Модалка подтверждения при удалении.

---

## Секция 7 — Future work / Out of scope

Явный список того, что НЕ делаем в этом spec'е:

1. **Отдельный экран «Регулярные платежи»** — CRUD-лист правил. Добавим, когда правил станет 15+ и обзор в Budget перестанет помогать.
2. **FUND_TRANSFER recurring** — регулярные пополнения копилок. Ждёт реализации PR2 (fund transfer events).
3. **Следующая brainstorming-сессия: метод прогноза** — trimmed mean на 6 мес, P50/P75, линия прогноза на стратегическом графике.
4. **Редизайн стратегического графика** (линии прогноза, fan chart, ghost trajectory для хотелок, start-from-first-tracking-month) — ждёт реализации капитала.
5. **Retroactive-создание** правил (start_date в прошлом).
6. **WEEKLY, BIWEEKLY** частоты.
7. **Scheduled job для lazy-extend** — сейчас on-demand. Cron станет нужен если планировщик вызывается редко (длинные простои).
8. **Preservation per-instance override при scope=FOLLOWING** — сейчас FOLLOWING перезаписывает индивидуальные отклонения в будущих событиях. Можно усложнить позже.

## Ключевые инварианты (сводка)

Канонический список с уровнями enforcement — в разделе «Секция 1 → Инварианты». Ниже — краткая сводка для быстрого чтения.

| # | Инвариант |
|---|-----------|
| I1 | MONTHLY не имеет `month_of_year`; YEARLY обязан иметь |
| I2 | `end_date >= start_date` или NULL |
| I3 | `start_date >= today` при создании |
| I4 | EXECUTED-события не изменяются и не удаляются правилом — никогда |
| I5 | Правило — источник истины только для PLAN-событий в будущем; прошлое независимо |
| I6 | `scope=FOLLOWING/ALL` валиден только для recurring-событий |
| I7 | Clamp дня к `lengthOfMonth` — стандарт для всех дат (31 → 28/29/30) |
| I8 | `start_date` неизменяем после создания правила (см. Секцию 1) |
| I9 | `recurring_rule_id != null` ⇔ событие порождено этим правилом |
| I10 | `scope=FOLLOWING/ALL` затирает ранее сделанные `scope=THIS` правки в перезатрагиваемом интервале (см. Секцию 1) |

## Архитектурная схема

```
  Frontend (EventSheet / EditEventSheet / Budget)
           ↓  POST / PUT?scope / DELETE?scope
  FinancialEventController (с новой scope-query)
           ↓
  FinancialEventService ───────────────────┐
    ├─ create: если recurring → ruleService│
    ├─ update: scope != THIS → ruleService │
    └─ delete: scope != THIS → ruleService │
                                            ↓
                            RecurringRuleService
                             ├─ createFromDto
                             ├─ regenerate(rule, from)
                             ├─ extendIndefiniteRules
                             └─ scope-based delete
                                            ↓
                            RecurringEventGenerator (stateless)
                             ├─ generate(rule, from, through)
                             ├─ nextMonthly / nextYearly
                             └─ clamp к lengthOfMonth
```

`FundPlannerService.getPlanner()` → `ruleService.extendIndefiniteRules(LocalDate.now().plusMonths(36))` → дальше обычная агрегация.
