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
```

### Инварианты

| # | Правило | Уровень |
|---|---------|---------|
| I1 | `frequency=MONTHLY` ⇒ `month_of_year IS NULL` | CHECK |
| I2 | `frequency=YEARLY` ⇒ `month_of_year IS NOT NULL` | CHECK |
| I3 | `end_date IS NULL OR end_date >= start_date` | CHECK |
| I4 | При создании: `start_date >= today` | сервис (today не статичен, в БД не загоняем) |
| I5 | `recurring_rule_id` на событии → событие порождено правилом | сервис |
| I6 | EXECUTED-события (`status=EXECUTED`) никогда не модифицируются и не удаляются правилом | сервис |

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
cursor = первая дата >= from, подходящая по частоте и (dayOfMonth, monthOfYear)
dates = []
while cursor <= through:
    dates.add(cursor)
    cursor = nextDate(cursor)
```

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
void extendIndefiniteRules(LocalDate requiredThrough) {
    for (RecurringRule rule : ruleRepo.findIndefiniteActive()) {
        LocalDate maxExisting = eventRepo.findMaxDateByRule(rule.getId());
        LocalDate from = maxExisting != null
            ? maxExisting.plusDays(1)
            : rule.getStartDate();
        if (from.isAfter(requiredThrough)) continue;
        List<FinancialEvent> extra = generator.generate(rule, from, requiredThrough);
        eventRepo.saveAll(extra);
    }
}
```

Вызывается в `FundPlannerService.getPlanner()` первым шагом с `requiredThrough = today.plusMonths(36)`. Идемпотентно — при повторе ничего не делает.

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
```

Этого достаточно для UI (иконка ↻, scope-picker, текст tooltip'а).

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
    List<FinancialEvent> events = generator.generate(rule,
        rule.getStartDate(),
        rule.getEndDate() != null ? rule.getEndDate() : today.plusMonths(36));
    eventRepo.saveAll(events);
    return toDto(events.get(0));
}
// иначе — текущая логика одиночного события
```

### Редактирование

```java
void update(UUID eventId, ScopeEnum scope, EventUpdateDto dto) {
    FinancialEvent event = eventRepo.findById(eventId).orElseThrow();

    if (scope == THIS || event.getRecurringRuleId() == null) {
        applyDto(event, dto);
        return;
    }

    if (event.getRecurringRuleId() == null) {
        throw new BadRequestException("Scope requires recurring event");
    }

    RecurringRule rule = ruleRepo.findById(event.getRecurringRuleId()).orElseThrow();
    applyDtoToRule(rule, dto);
    ruleRepo.save(rule);

    LocalDate regenerateFrom = (scope == FOLLOWING)
        ? event.getDate()
        : rule.getStartDate();
    regenerate(rule, regenerateFrom);
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

Ключевая инвариант (I6): EXECUTED-события не удаляются в (1) — репозиторный метод `softDeletePlanEventsByRuleFromDate` фильтрует `status = PLANNED`. В (3) они пропускаются при генерации.

### Удаление

```java
void delete(UUID eventId, ScopeEnum scope) {
    FinancialEvent event = eventRepo.findById(eventId).orElseThrow();

    if (scope == THIS || event.getRecurringRuleId() == null) {
        event.setDeleted(true);
        return;
    }

    RecurringRule rule = ruleRepo.findById(event.getRecurringRuleId()).orElseThrow();
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

`extendIndefiniteRules(today.plusMonths(36))` вызывается в `FundPlannerService.getPlanner()` в самом начале. Отдельная логическая операция, но находится в той же транзакции метода-читателя.

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

Обоснование inline (а не модалки перед edit'ом): пользователь сразу видит контекст изменения, нет лишнего клика.

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
6. Create с `start_date` в прошлом → 400 Bad Request.
7. Create YEARLY 29 февраля → первый экземпляр 29 фев (если старт в високосный) или 28 фев (если нет).

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

## Ключевые инварианты

| # | Инвариант |
|---|-----------|
| I1 | MONTHLY не имеет month_of_year; YEARLY обязан иметь |
| I2 | end_date >= start_date или NULL |
| I3 | start_date >= today при создании |
| I4 | EXECUTED-события не изменяются и не удаляются правилом — никогда |
| I5 | Правило — источник истины только для PLAN-событий в будущем; прошлое независимо |
| I6 | scope=FOLLOWING/ALL валиден только для recurring-событий |
| I7 | Clamp дня к lengthOfMonth — стандарт для всех дат (31 → 28/29/30) |

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

`FundPlannerService.getPlanner()` → `ruleService.extendIndefiniteRules(today.plusMonths(36))` → дальше обычная агрегация.
