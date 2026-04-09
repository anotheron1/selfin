# Дизайн: технический долг и UX-улучшения (9.1–9.5)

**Дата:** 2026-04-09  
**Статус:** Approved  
**Охват:** Приоритеты категорий, управление категориями, чекпоинт, хотелки, отображение транзакций в Budget + новый блок аналитики

---

## Контекст

Пять зон технического долга и UX-проблем, выявленных в стратегической сессии 2026-04-09. Часть решений — чистый технический долг без дизайн-решений, часть потребовала визуального согласования.

---

## 9.1 Приоритеты категорий

### Решения

**Цикличный выбор — сохранить.** Пользователь считает его удобным: не занимает место, не требует дропдаунов.

**MEDIUM — сменить индикатор.** Текущая невидимая точка `·` заменяется на читаемый лейбл. Вариант: текст `"план"` в том же стиле что `"обяз"` и `"хотелка"`, но нейтрального цвета (`--color-text-muted`).

**Дублирование `nextPriority()` — убрать.** Логика живёт только в `CategoryService.nextPriority()`. Из `FinancialEventService` удалить, заменить вызовом или вынести в общий утилитный класс/enum-метод.

**Сортировка в Settings — добавить приоритет как вторичный ключ.** Сортировка: сначала по типу (INCOME / EXPENSE), затем по приоритету (HIGH → MEDIUM → LOW), затем по алфавиту. Это визуально группирует обязательные категории вверху списка.

### Файлы
- `frontend/src/components/PriorityButton.tsx` — заменить рендер MEDIUM
- `backend/.../service/CategoryService.java` — `nextPriority()` остаётся
- `backend/.../service/FinancialEventService.java` — удалить дублированный `nextPriority()`
- `frontend/src/pages/Settings.tsx` — сортировка списка категорий

---

## 9.2 Управление категориями (технический долг)

### Решения

**Двойная сортировка — убрать фронтовую.** Сортировка производится один раз на бэке (`Collator` по ru-RU). Фронт убирает `localeCompare('ru')`, просто рендерит порядок из API.

**Системная категория "Хотелки" — добавить `isSystem` флаг.** В модель `Category` добавляется булево поле `isSystem` (default `false`). При auto-create "Хотелки" флаг выставляется в `true`. В UI системные категории:
- Отображаются с визуальным маркером (например, иконка замка или серый суффикс `· системная`)
- Кнопка удаления скрыта
- Переименование заблокировано

Имя "Хотелки" выносится в константу `SystemCategory.WISHLIST_NAME`. `SystemCategory` объявляется как отдельный класс в пакете `model/enums/` с единственной публичной строковой константой.

**Смена типа (INCOME/EXPENSE) с транзакциями — двухуровневая защита.** Оба уровня обязательны:
1. **Фронт**: при попытке изменить тип категории всегда показывает confirm-диалог: `"Смена типа может нарушить аналитику. Продолжить?"`. Никакого preflight GET не нужно — диалог выводится безусловно при изменении типа в форме редактирования. Пользователь может подтвердить или отменить.
2. **Бэкенд**: `CategoryService.update()` при смене типа вызывает `eventRepository.existsByCategoryIdAndDeletedFalse(id)`. Если `true` — выбрасывает `400 Bad Request` с `ErrorResponse`. Soft-deleted события не считаются (excluded). Это защита от прямых API-вызовов в обход UI.

**Системная категория — защита переименования на бэке.** `CategoryService.update()` проверяет `category.isSystem()`: если `true` — выбрасывает `400 Bad Request` (`"System categories cannot be renamed"`). Фронт скрывает поле ввода имени для системных категорий, бэк защищает от прямых вызовов.

**Зависимость `CategoryService` ↔ `FinancialEventRepository`.** Для проверки наличия событий при смене типа `CategoryService` получает `FinancialEventRepository` как новую зависимость через `@RequiredArgsConstructor`. Альтернатива (делегировать через `FinancialEventService`) не выбрана — лишний слой косвенности для простого `exists`-запроса.

### Файлы
- `backend/.../model/Category.java` — добавить поле `isSystem`
- `backend/.../dto/CategoryDto.java` — добавить поле `isSystem` (фронт читает его для скрытия кнопок)
- `backend/.../service/CategoryService.java` — новая зависимость `FinancialEventRepository`; константа имени; флаг при создании; валидации смены типа и переименования
- `backend/.../repository/FinancialEventRepository.java` — добавить `existsByCategoryIdAndDeletedFalse(UUID categoryId): boolean`
- `backend/.../service/FinancialEventService.java` — использовать `SystemCategory.WISHLIST_NAME` вместо строки
- `frontend/src/pages/Settings.tsx` — убрать localeCompare, UI для isSystem (скрыть поле имени и кнопку удаления)
- Flyway migration — `ALTER TABLE category ADD COLUMN is_system BOOLEAN NOT NULL DEFAULT false`

---

## 9.3 Чекпоинт баланса (технический долг)

### Решения

**Размещение — оставить в Settings.** Чекпоинт не регулярное действие, текущее размещение устраивает.

**Валидация даты в будущем — добавить.** Бэкенд: в `BalanceCheckpointCreateDto` аннотация `@PastOrPresent` на поле `date`. Фронт: атрибут `max={today}` на `<input type="date">`.

**Тесты bridge-events — написать.** `calcStartBalance()` — приватный метод, тестируется косвенно через публичный `getReport(asOfDate)` с проверкой `cashFlow[0].runningBalance`. Покрыть граничные случаи:
- Чекпоинт в текущем месяце
- Чекпоинт в прошлом месяце
- Чекпоинт два и более месяцев назад
- Отсутствие чекпоинта (возврат нуля)

### Файлы
- `backend/.../dto/BalanceCheckpointCreateDto.java` — `@PastOrPresent` на поле `date`
- `backend/.../controller/BalanceCheckpointController.java` — убедиться что `@Valid` присутствует на `@RequestBody` (иначе `@PastOrPresent` не сработает); добавить если отсутствует
- `frontend/src/pages/Settings.tsx` — `max={today}` на date input чекпоинта
- `backend/src/test/java/ru/selfin/backend/service/AnalyticsServiceTest.java` (существующий) — добавить тест-методы для `calcStartBalance()` и валидации `@PastOrPresent` через `javax.validation.Validator`

---

## 9.4 Хотелки

### Концептуальная модель (уточнённая)

Хотелка — это LOW-priority `FinancialEvent`. Жизненный цикл:

1. **В списке**: `priority=LOW`, `date=null` — отображается только в WishlistSection
2. **Запланирована**: `priority=LOW`, `date=будущий месяц` — видна в Budget + в WishlistSection (контурная точка)
3. **Выполнена**: есть `factAmount` — видна в Budget, в WishlistSection как фиолетовая точка
4. **Просрочена**: `priority=LOW`, `date < начало текущего месяца`, нет факта — появляется в WishlistSection (тёмная точка без даты), остаётся в Budget как обычная строка плана без дополнительных маркеров. Отдельной подсказки «вернулась в список» в Budget не нужно — пользователь увидит её в WishlistSection.

### Фикс фильтра «просроченные → список»

**Было (баг):** `priority=LOW AND status=PLANNED AND (date IS NULL OR date < today)`  
**Стало:** `priority=LOW AND status=PLANNED AND (date IS NULL OR date < first_day_of_current_month)`

`first_day_of_current_month` вычисляется на **call-site** в `FinancialEventService.findWishlist()`: `LocalDate.now(clock).withDayOfMonth(1)` — и передаётся как параметр в репозиторный метод. `Clock` инжектируется как Spring bean (`@Bean Clock clock() { return Clock.systemDefaultZone(); }`), что позволяет подменить его в тестах через `Clock.fixed()`. Вся система использует `LocalDate` без timezone — timezone сервера соответствует timezone пользователя (допустимо для single-user продукта).

Результат: в течение месяца хотелка видна только в Budget. С 1-го числа следующего месяца тихо появляется в WishlistSection без создания новых записей. Идемпотентно.

Дополнительно: фронтовый фильтр в `WishlistSection.tsx` (строка 45: `e.status === 'PLANNED' && !(e.factAmount != null && e.factAmount > 0)`) привести в соответствие с бэком — убрать, так как бэк уже фильтрует корректно.

### Рефакторинг WishlistSection (God Component → 3 компонента)

`WishlistSection.tsx` (407 строк) разбивается:

| Компонент | Ответственность |
|---|---|
| `WishlistSection.tsx` | Оркестратор: fetch, состояние `items`, callback-и `onDelete`/`onReload`, рендер списка |
| `WishlistItem.tsx` | Одна хотелка: отображение полей, кнопки редактировать/удалить/запланировать, reschedule-панель с локальным state. Получает `item: FinancialEvent` и колбэки. |
| `WishlistForm.tsx` | Форма добавления и редактирования (переиспользуется для обоих кейсов через проп `initialValues`). Локальный state полей формы, submit, error. |

Зависимость от 9.2: вместо хардкоженной строки `"Хотелки"` при создании использовать `SystemCategory.WISHLIST_NAME` (введена в 9.2).

### Файлы
- `backend/.../model/enums/SystemCategory.java` (новый) — класс с константой `WISHLIST_NAME`
- `backend/.../service/FinancialEventService.java` — добавить зависимость `Clock clock`; использовать `clock` вместо `LocalDate.now()` при вычислении `first_day_of_current_month`; использовать `SystemCategory.WISHLIST_NAME`
- `backend/.../config/AppConfig.java` (или аналог конфигурационного класса) — добавить `@Bean Clock clock() { return Clock.systemDefaultZone(); }`
- `backend/.../repository/FinancialEventRepository.java` — изменить запрос `findWishlist` (параметр `LocalDate cutoff` вместо `LocalDate.now()`)
- `frontend/src/components/WishlistSection.tsx` — рефакторинг + убрать фронтовый фильтр (строка 45)
- `frontend/src/components/WishlistItem.tsx` (новый)
- `frontend/src/components/WishlistForm.tsx` (новый)

---

## 9.5 Отображение транзакций (Budget)

### Решение: hover-группы + сумма факта в строке плана

**Сумма факта в строке плана — всегда видна:**

```
Продукты          [обяз]   план 5 000 ₽
                           факт 4 320 ₽   ← зелёный (≤ плана)
```

Три состояния подписи факта:
- `факт N ₽` зелёным — факт ≤ план (в рамках)
- `факт N ₽` красным (`--color-danger`) — факт > план (перерасход)
- `нет факта` серым курсивом — факта ещё нет

**Hover-подсветка через `data-group`:**

Каждая строка события получает `data-group={event.parentEventId ?? event.id}` (поле `parentEventId` из `FinancialEventDto`). При hover обработчики находят все строки с тем же group ID и добавляют класс `.pf-hovered`:
- План: яркая левая полоска (`--color-accent`)
- Факты: приглушённая левая полоска (`--color-accent` 35% opacity)
- Фон всей группы: `rgba(108,99,255,0.07)`

Работает cross-day и cross-week — план и факт могут находиться в разных неделях.

**Факт в другом дне — сноска:**

Если дата факта ≠ дата плана, в подзаголовке факта добавляется: `"→ план «Бензин» 7 апр"`.

**FACT без родительского плана:**

Строка без `data-group` (или group = собственный id) — hover подсвечивает только её саму. Визуально отличима от linked-фактов.

### Техническая реализация (React)

Budget не использует виртуализацию списка — все строки месяца присутствуют в DOM одновременно. Поэтому DOM-подход через `data-group` допустим и проще React-state-альтернативы (которая потребовала бы поднятия состояния `hoveredGroup` в родительский компонент и передачи пропсов во все строки).

```typescript
// Утилитные функции hover-группы (DOM-подход, допустим без виртуализации)
// Не использует useState/useRef — это не хук в смысле React, а просто пара функций.
function createPlanFactHoverHandlers() {
  const handleMouseEnter = (groupId: string) => {
    document.querySelectorAll(`[data-group="${groupId}"]`)
      .forEach(el => el.classList.add('pf-hovered'));
  };
  const handleMouseLeave = (groupId: string) => {
    document.querySelectorAll(`[data-group="${groupId}"]`)
      .forEach(el => el.classList.remove('pf-hovered'));
  };
  return { handleMouseEnter, handleMouseLeave };
}
```

**`factSum` источник данных.** `FinancialEventDto` уже содержит `linkedFactsAmount` из `FactAggregateProjection`. Именно это поле используется как `factSum` в строке плана — новых запросов не требуется.

**Сортировка в `Budget.tsx` — не трогать.** Существующая сортировка событий внутри дня (строки ~201, ~204) выходит за рамки данного спека.

### Файлы
- `frontend/src/pages/Budget.tsx` — добавить отображение `linkedFactsAmount` в строке плана, `data-group`, обработчики hover
- `frontend/src/index.css` — классы `.pf-hovered`, `.pf-hovered.is-plan`, `.pf-hovered.is-fact`

---

## Новый блок: «Структура месяца» в Analytics

### Контекст решения

Секция «Обязательные траты» (MandatoryBurnRate) не используется: еженедельный burn rate дублирует план-факт таблицу и не добавляет инсайта. Заменяется на «Структуру месяца» — три карточки, каждая отвечает на свой вопрос.

### Три карточки

**Карточка 1: Обязательные (HIGH)**
- Вопрос: обязательства покрыты?
- Контент: `план / факт`, прогресс-бар (`--color-danger`), строка `"сэкономил N ₽"` или `"перерасход N ₽"`

**Карточка 2: Прочие расходы (MEDIUM)**
- Вопрос: какую долю занимают в бюджете?
- Контент: сумма факта + горизонтальный стэк-бар с долями четырёх сегментов:
  - Обязательные (доля от суммы всех расходов + доходов)
  - Прочие
  - Хотелки (исполненные)
  - Остаток (доходы − все расходы)
- Легенда под баром: 4 цветовые метки с %
- **При отрицательном остатке** (расходы > доходов): сегмент «Остаток» не отображается (нет отрицательной ширины). Вместо него стэк-бар заполнен полностью, последний сегмент окрашивается в `--color-danger`. Под баром добавляется строка `"Перерасход: N ₽"` красным.

**Карточка 3: Хотелки (LOW)**
- Вопрос: сколько желаний реализовано?
- Контент: точки-квадратики, **все** LOW-priority события (`GET /api/v1/events?priority=LOW`)
- Лимит отображения: показываются первые 20 точек, при наличии большего количества — добавляется текст `"+ ещё N"`. Без пагинации.
- Три состояния точки:
  - Фиолетовая (`--color-accent`) — выполнена (есть factAmount)
  - Контурная (фон `--color-accent` 18%, бордер `--color-accent`) — запланирована (date есть, факта нет)
  - Тёмная (`--color-surface`, бордер `--color-border`) — в списке без даты
- Тултип при hover: название, сумма, `"факт: дата"` (зелёным) или `"план: дата"` или `"без даты"`
- Счётчик в заголовке: `"N из M выполнена"`

### Где живёт

Analytics-страница, только режим `1m` (текущий месяц). Заменяет компонент `MandatoryBurnSection`. Компонент `PlanFactSection` остаётся рядом.

В режимах `3m`, `6m`, `12m` блок «Структура месяца» не отображается — там показывается только многомесячная таблица. Переключение режимов уже реализовано через `preset` state, условный рендер добавляется туда же.

### Backend

`AnalyticsReportDto` расширяется новым полем `PriorityBreakdown priorityBreakdown`. `PriorityBreakdown` объявляется как **вложенный record** внутри `AnalyticsReportDto` — по аналогии с уже существующими `CashFlowDay`, `PlanFactReport`, `MandatoryBurnRate`. Вычисляется в `AnalyticsService.buildPriorityBreakdown()` — агрегация тех же `monthEvents` по `event.priority`:

```java
record PriorityBreakdown(
    BigDecimal highPlanned, BigDecimal highFact,       // Обязательные (HIGH)
    BigDecimal mediumPlanned, BigDecimal mediumFact,   // Прочие (MEDIUM)
    BigDecimal lowPlanned, BigDecimal lowFact,         // Хотелки (LOW) — для сегмента стэк-бара
    BigDecimal totalIncomeFact                         // Доходы-факт — для вычисления остатка
) {}
```

Хотелки (карточка 3) требуют **все** LOW-priority события независимо от статуса (в том числе выполненные). Существующий `fetchWishlist()` возвращает только незакрытые, поэтому используется отдельный запрос: `GET /api/v1/events?priority=LOW`.

Семантика нового параметра `priority`: точное совпадение с enum-значением (`LOW` / `MEDIUM` / `HIGH`). Параметры `startDate` и `endDate` становятся **опциональными** (`required = false`): если не переданы — возвращаются все события указанного приоритета без ограничения по дате. Если переданы — фильтр по дате применяется как прежде. Soft-delete фильтр (`deleted=false`) применяется всегда. Пагинация отсутствует.

Два запроса при загрузке Analytics в режиме `1m` выполняются параллельно через `Promise.all`: `fetchAnalyticsReport()` + `fetchEventsByPriority('LOW')`.

### Файлы
- `frontend/src/pages/Analytics.tsx` — заменить `MandatoryBurnSection` на `BudgetStructureSection`
- `frontend/src/components/BudgetStructureSection.tsx` (новый)
- `backend/.../dto/AnalyticsReportDto.java` — добавить поле `PriorityBreakdown priorityBreakdown`
- `backend/.../service/AnalyticsService.java` — добавить метод `buildPriorityBreakdown(monthEvents)`
- `backend/.../controller/FinancialEventController.java` — добавить query-параметр `priority` в `GET /events`
- `backend/.../repository/FinancialEventRepository.java` — добавить `findAllByDeletedFalseAndPriority(Priority priority)`

---

## Порядок реализации (рекомендуемый)

1. **9.2 + 9.3 + 9.1** — можно параллельно. 9.1 не зависит от isSystem, сортировка Settings зависит только от значений priority (уже есть в API).
2. **9.4** — фикс фильтра (1 строка), затем рефакторинг компонента. **Должна следовать после 9.2**: использует `SystemCategory.WISHLIST_NAME` из 9.2.
3. **9.5** — Budget hover-группы + сумма факта
4. **Analytics «Структура месяца»** — последним, зависит от расширения `AnalyticsReportDto` и нового query-параметра `priority`

---

## Что не меняется

- Цикличный выбор приоритета (HIGH→MEDIUM→LOW→HIGH) — сохранить
- Размещение чекпоинта в Settings — сохранить
- Хронологический порядок событий в Budget — сохранить
- Hard delete чекпоинта — out of scope, оставить как есть
