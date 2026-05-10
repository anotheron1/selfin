# Capital / Net Worth — Design Spec

**Дата:** 2026-05-10
**Статус:** Draft
**Область:** backend (две новые таблицы `capital_items` + `capital_revaluations`, сервис, контроллер), frontend (новый маршрут `/capital`, страница, Sheet-панель, график, диалог теории).

## Цель

Дать пользователю отдельный модуль «Капитал», в котором он видит свою ценность во времени: ликвидные деньги + материальные активы − обязательства. Без этого модуля стратегическое планирование архаично и неполноценно: покупка автомобиля влияет и на ежемесячные платежи (видно в Budget), и на капитал (минус 1.2М стоимостью) — но второе сегодня в системе не моделируется. Ипотека уменьшает остаток долга на тело каждого платежа — но в системе виден только сам платёж как `EXPENSE`, не движение долга.

Капитал — фундаментальная фича: после неё приобретают смысл прогноз обязательных расходов, редизайн стратегического графика и UI хотелок.

## Scope

**В scope:**
- Новая сущность «единица капитала» (`capital_items`) с типом `ASSET` или `LIABILITY` — единая модель для активов и обязательств.
- Журнал переоценок (`capital_revaluations`) — лента записей с датой; стоимость item'а на любую дату = последняя запись с `valued_at ≤ дата`.
- Новый сервис расчёта капитала, переиспользующий существующие сервисы для жидкой части (`BalanceCheckpoint`, `TargetFund`, события).
- API `/api/v1/capital/*`: CRUD по items и переоценкам, агрегаты `summary` и `trajectory`.
- Страница `/capital` в навигации после Analytics: hero-сводка, две колонки (активы и обязательства), график траектории, теоретическая «?»-кнопка.
- Sheet-панель для создания/переоценки/истории/выбытия одного item'а.
- Поддержка ретроактивных переоценок (`valued_at` в прошлом) — иначе у пользователя не будет истории, чтобы построить траекторию.
- Семантика «выбытие актива / закрытие кредита» через переоценку с `value=0` — без отдельного флага.

**Out of scope (этого PR):**
- Мульти-счёт (несколько банковских счетов вместо одного `BalanceCheckpoint`). Жидкая часть остаётся в существующей модели «один счёт + копилки».
- Инвестиционные позиции (тикеры, цены, количество). Если когда-то — как будущий «инвестиционный счёт» в рамках мульти-счёта.
- Курсы валют. Валюта-кэш моделируется как обычный актив с ручной переоценкой (изменился курс или количество — пользователь сам обновил рублёвую стоимость).
- Авто-амортизация кредитов: остаток долга обновляется только пользователем, никаких «уменьшать тело долга по факту платежа ипотеки».
- Связь recurring-платежей по ипотеке с `capital_items` на уровне БД.
- Связь `TargetFund(purchaseType=CREDIT)` с обязательствами. Конвертация копилки-под-кредит в `asset+liability` после покупки — пользователь делает руками.
- Авто-снимки капитала по cron. Источник истории — журнал переоценок.
- Idempotency-Key на POST'ы. Капитал — редкие ручные операции, риск дубля минимален.
- Bulk-операции, поиск, пагинация по items. Список короткий (3-10 позиций).
- Telegram-бот, отдельный экран регулярных платежей, FUND_TRANSFER recurring.

## Философия

> **«Капитал — отдельная медитация раз в N времени, ДДС — ежедневный журнал.»**

Из этой формулировки вытекают все ключевые решения:
- Никаких авто-обновлений капитала из событий ДДС. Стоимость активов и остаток долгов — только ручной ввод.
- Симметрия активов и обязательств: одна таблица, один CRUD, одна модель «значение + история переоценок». В UI они разделены колонками и цветом, в коде — единая сущность с дискриминатором `kind`.
- Граф истории строится непрерывно из revaluation log + существующих формул жидкой части. Никаких «явных снимков капитала», которые пользователь может забыть нажать.

## Формула капитала

Для любой даты `t`:

```
Капитал(t) = Liquid(t) + Σ Активов(t) − Σ Обязательств(t)
```

### Жидкая часть `Liquid(t)`

Считается из существующих сущностей; новых таблиц для этого не вводится.

```
Liquid(t) = AccountBalance(t) + Σ FundBalance(t)
```

где
- `AccountBalance(t) = checkpoint(t) + Σ INCOME факт ≤ t − Σ EXPENSE факт ≤ t − Σ FUND_TRANSFER факт ≤ t` — баланс расчётного счёта, считаемый существующим кодом дашборда.
- `Σ FundBalance(t)` — суммарный баланс всех копилок на дату `t`, считаемый существующим `TargetFundService` / `FundTransaction`.

`FUND_TRANSFER` не вычитается из капитала: деньги из копилки всё ещё мои, просто перемещены. В сумме `AccountBalance + Σ FundBalance` `FUND_TRANSFER` и `FundTransaction` взаимно компенсируются, что эквивалентно формуле `checkpoint + Σ INCOME факт − Σ EXPENSE факт`.

Если `BalanceCheckpointService` или `TargetFundService` сегодня не имеют публичного метода «баланс на дату t» — в реализации добавим один такой метод (расширение существующих сервисов, не новый сервис).

### Стоимость item'а на дату

```
value(item, t) = capital_revaluations
                   .filter(item_id = item.id, NOT is_deleted, valued_at ≤ t)
                   .order_by(valued_at DESC, created_at DESC)
                   .first().value
                 OR 0, если ни одной такой записи нет
```

«Ноль до первой переоценки» — это интуитивная семантика: купил машину сегодня → в прошлом её не было, она не двигает прошлый капитал. Если хочется увидеть «у меня была машина в 2024» — пользователь добавляет ретроактивные переоценки (legal-операция через тот же `POST /revaluations` с прошлой датой).

### Точки графика траектории

Точки = `{end-of-month каждого прошедшего месяца внутри запрошенного периода} ∪ {today}`.

«Сегодня» — всегда крайняя правая точка, обновляется в момент любой операции пользователя. Это решает кейс «зашёл первый раз 10 числа, ввёл активы, должен видеть капитал немедленно».

Между переоценками значение item'а — константа от последней известной (step-функция). Жидкая часть — динамическая, считается на каждую точку графика свежим запросом.

## Модель данных

### Таблица `capital_items`

| Колонка | Тип | Описание |
|---------|-----|----------|
| `id` | `UUID PRIMARY KEY DEFAULT gen_random_uuid()` | |
| `kind` | `VARCHAR(20) NOT NULL CHECK (kind IN ('ASSET','LIABILITY'))` | Дискриминатор. Неизменяем после создания (инвариант I5) |
| `name` | `VARCHAR(255) NOT NULL` | «Квартира на Ленинской», «Ипотека Сбер» |
| `description` | `TEXT` (nullable) | Свободная заметка |
| `created_at` | `TIMESTAMP NOT NULL DEFAULT NOW()` | |
| `updated_at` | `TIMESTAMP NOT NULL DEFAULT NOW()` | |
| `is_deleted` | `BOOLEAN NOT NULL DEFAULT FALSE` | Soft-delete для «удалить безвозвратно» |

### Таблица `capital_revaluations`

| Колонка | Тип | Описание |
|---------|-----|----------|
| `id` | `UUID PRIMARY KEY DEFAULT gen_random_uuid()` | |
| `item_id` | `UUID NOT NULL REFERENCES capital_items(id)` | |
| `value` | `NUMERIC(19,2) NOT NULL CHECK (value >= 0)` | Стоимость или остаток долга. Всегда положительная — знак применяется в формуле по `kind` |
| `valued_at` | `DATE NOT NULL` | Дата оценки. Не timestamp — внутридневная точность не нужна |
| `note` | `TEXT` (nullable) | «по объявлениям ЦИАН» |
| `created_at` | `TIMESTAMP NOT NULL DEFAULT NOW()` | Используется для tie-break при двух переоценках в один день |
| `is_deleted` | `BOOLEAN NOT NULL DEFAULT FALSE` | Можно «удалить ошибочную запись» |

### Индексы

```sql
CREATE INDEX idx_capital_items_active
    ON capital_items(kind)
    WHERE NOT is_deleted;

CREATE INDEX idx_capital_revaluations_item_at
    ON capital_revaluations(item_id, valued_at DESC, created_at DESC)
    WHERE NOT is_deleted;
```

**Уникальности `(item_id, valued_at)` нет осознанно.** Если пользователь сегодня дважды обновил квартиру (опечатался, исправил) — пусть в журнале лежат обе записи, для расчёта берётся последняя по `created_at`. Удалить ошибочную можно через soft-delete revaluation'а.

### Семантика выбытия / закрытия

Для актива «продал машину» / для обязательства «закрыл кредит» — добавляется переоценка с `value=0` на дату выбытия. Item с последней переоценкой = 0:
- В UI исчезает из основного списка колонки.
- Появляется в свёрнутом аккордеоне «Архивные» внизу колонки.
- Из него можно «вернуть в строй», добавив новую переоценку с ненулевой стоимостью.

История траектории остаётся правдивой: до даты выбытия item контрибьютил.

«Удалить безвозвратно» (для случая «случайно создал, не было такого в реальности») — это `is_deleted=true` на `capital_items`. Из всех расчётов и графика item исчезает целиком. Это отдельная кнопка с красным confirm dialog.

### Связь со существующими сущностями

- **`BalanceCheckpoint`** используется как источник для `AccountBalance(t)`. Изменений в схеме не требуется.
- **`TargetFund`** используется для `Σ FundBalance(t)`. Изменений в схеме не требуется.
- **`TargetFund(purchaseType=CREDIT)`** остаётся как есть. Это копилка под покупку в кредит — другая фича, не про net-worth.
- **`FinancialEvent` ипотечного платежа** остаётся обычным `EXPENSE` с категорией «Ипотека». Не связан с `capital_items` на уровне БД.

### Миграция Flyway (V17)

```sql
-- V17__add_capital_items.sql

CREATE TABLE capital_items (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    kind        VARCHAR(20)  NOT NULL CHECK (kind IN ('ASSET', 'LIABILITY')),
    name        VARCHAR(255) NOT NULL,
    description TEXT,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    is_deleted  BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE TABLE capital_revaluations (
    id         UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    item_id    UUID           NOT NULL REFERENCES capital_items(id),
    value      NUMERIC(19, 2) NOT NULL CHECK (value >= 0),
    valued_at  DATE           NOT NULL,
    note       TEXT,
    created_at TIMESTAMP      NOT NULL DEFAULT NOW(),
    is_deleted BOOLEAN        NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_capital_items_active
    ON capital_items(kind)
    WHERE NOT is_deleted;

CREATE INDEX idx_capital_revaluations_item_at
    ON capital_revaluations(item_id, valued_at DESC, created_at DESC)
    WHERE NOT is_deleted;
```

## API

Все эндпоинты под `/api/v1/capital/*`.

### CRUD по items

| Метод | Путь | Описание |
|-------|------|----------|
| `GET` | `/items?kind=ASSET\|LIABILITY&includeArchived=false` | Список активных item'ов. `kind` — опциональный фильтр; `includeArchived=true` подмешивает item'ы с последней переоценкой = 0 |
| `POST` | `/items` | Создать item с **первой переоценкой** в одной транзакции (без неё item бессмысленен — инвариант I3) |
| `GET` | `/items/{id}` | Получить один item |
| `PUT` | `/items/{id}` | Переименовать / поменять описание. Стоимость не трогает |
| `DELETE` | `/items/{id}` | Hard soft-delete: `is_deleted=true`. Item и его revaluations исчезают из всех расчётов |

### CRUD по переоценкам

| Метод | Путь | Описание |
|-------|------|----------|
| `GET` | `/items/{id}/revaluations` | История переоценок одного item'а (новейшие сверху) |
| `POST` | `/items/{id}/revaluations` | Добавить переоценку. Это же — способ выбытия (`value=0`) |
| `PUT` | `/revaluations/{id}` | Поправить ошибочную запись |
| `DELETE` | `/revaluations/{id}` | Soft-delete переоценки |

### Агрегаты

| Метод | Путь | Описание |
|-------|------|----------|
| `GET` | `/summary` | Сводка на сегодня: `{ total, liquid, assetsTotal, liabilitiesTotal, items[], deltas: { month, quarter, year } }`. Это содержимое hero-карточки и двух колонок |
| `GET` | `/trajectory?from=...&to=...` | Точки графика: `[{ date, capital, liquid, assets, liabilities }]`. По умолчанию `from` — самая ранняя дата в `capital_revaluations` или `BalanceCheckpoint` (что раньше), `to` — сегодня. Точки = end-of-month + сегодня |

### DTOs

```java
// CapitalItemCreateDto
{
  kind: "ASSET" | "LIABILITY",     // required
  name: String,                     // required, 1..255
  description: String?,             // optional, max 2000
  initialValue: BigDecimal,         // required, >= 0
  initialValuedAt: LocalDate?       // optional, default = today
}

// CapitalItemUpdateDto
{
  name: String?,                    // optional, 1..255
  description: String?              // optional, max 2000
}

// CapitalItemDto (response)
{
  id: UUID,
  kind: "ASSET" | "LIABILITY",
  name: String,
  description: String?,
  createdAt: Instant,
  currentValue: BigDecimal,         // последняя переоценка
  lastValuedAt: LocalDate,
  isArchived: Boolean               // true если currentValue = 0
}

// CapitalRevaluationCreateDto
{
  value: BigDecimal,                // required, >= 0
  valuedAt: LocalDate?,             // optional, default = today, must be <= today (I2)
  note: String?                     // optional, max 1000
}

// CapitalRevaluationUpdateDto
{
  value: BigDecimal?,
  valuedAt: LocalDate?,             // must be <= today (I2)
  note: String?
}

// CapitalRevaluationDto (response)
{
  id: UUID,
  itemId: UUID,
  value: BigDecimal,
  valuedAt: LocalDate,
  note: String?,
  createdAt: Instant
}

// CapitalSummaryDto
{
  total: BigDecimal,
  liquid: BigDecimal,
  assetsTotal: BigDecimal,
  liabilitiesTotal: BigDecimal,
  items: CapitalItemDto[],          // плоской лентой; UI сам разбивает по kind
  deltas: {
    month: BigDecimal,              // total - capital(today - 30d)
    quarter: BigDecimal,            // total - capital(today - 90d)
    year: BigDecimal                // total - capital(today - 365d)
  }
}

// CapitalTrajectoryDto
{
  points: [
    {
      date: LocalDate,
      capital: BigDecimal,
      liquid: BigDecimal,
      assets: BigDecimal,
      liabilities: BigDecimal
    }
  ]
}
```

### Бизнес-инварианты

- **I1.** `value ≥ 0` на уровне БД (`CHECK`) и DTO (`@PositiveOrZero`).
- **I2.** `valuedAt ≤ today`. Future-dated → 400. Переоценка — это факт, не прогноз.
- **I3.** Item должен иметь хотя бы одну живую переоценку. `POST /items` всегда создаёт первую переоценку транзакционно.
- **I4.** Если `capital_items.is_deleted=true`, новые переоценки на этот item не принимаются (404). Существующие revaluations остаются в БД, но в расчётах не участвуют.
- **I5.** `kind` неизменяем после создания. Если ошибся — удалить и создать заново.
- **I6.** Все операции пишут в `capital_items.updated_at` через `@PreUpdate`.

### Ошибки

Стандартный проектный `GlobalExceptionHandler`: 400 на нарушения валидаций и I1-I5, 404 на отсутствие item / revaluation, 500 на неперехваченное. Тело — `ErrorResponse` JSON.

## Frontend и UX

### Маршрут и навигация

- Новый маршрут `/capital` в `frontend/src/`.
- Новая вкладка «Капитал» в главной навигации. Порядок: **Dashboard | Budget | Analytics | Капитал**.

### Структура страницы (Layout A)

```
<CapitalPage>
  <CapitalHeader>                         // заголовок + кнопка-«?» (CapitalTheoryDialog)
  <CapitalSummaryCard>                    // hero
  <div class="grid grid-cols-1 md:grid-cols-2">
    <CapitalItemList kind="ASSET" />
    <CapitalItemList kind="LIABILITY" />
  </div>
  <CapitalTrajectoryChart />
  <CapitalSheet />                        // выезжает по клику
</CapitalPage>
```

### Компоненты

#### `CapitalSummaryCard` (hero)

```
КАПИТАЛ НА 10 МАЯ
5 240 000 ₽
[ Месяц +82 000 (+1.6%) ↗ ] [ Квартал ] [ Год ]
```

Цифра — `summary.total`. Под ней — табы дельт. Активный таб подкрашивает текущую дельту цветом (зелёный/красный) со стрелкой.

#### `CapitalItemList` (колонка)

- Заголовок: «АКТИВЫ · 10.15М» (зелёный) / «ОБЯЗАТЕЛЬСТВА · 4.91М» (красный).
- Строки: `name` слева, `currentValue` справа. Клик → открывает Sheet.
- Внизу колонки: кнопка «+ Добавить актив» / «+ Добавить обязательство». Открывает Sheet с пустыми полями для создания.
- Свёрнутый аккордеон «Архивные (N)» — раскрывается, внутри items с `isArchived=true`.

#### `CapitalSheet` (правая панель, shadcn `Sheet`)

Единая поверхность для всех операций над одним item'ом. Клик на другой item в списке слева **не закрывает** Sheet, а перезаполняет содержимое.

Секции сверху вниз:

1. **Заголовок и метаданные:** редактируемое inline `name`, бейдж `kind` (неизменяем), редактируемое `description`.
2. **Текущая стоимость:** крупная цифра + дата последней переоценки.
3. **Форма переоценки:** поля `value`, `valuedAt` (default сегодня, можно прошлой датой), `note` (опц.). Кнопка «Сохранить переоценку».
4. **История переоценок:** список новейшие → старейшие. Каждая запись: дата · значение · заметка · меню (✏️ редактировать inline / 🗑 удалить через soft-delete).
5. **Опасные операции** (внизу, отдельной секцией):
   - «Отметить выбытие (актив продан / кредит закрыт)» → мини-форма с датой → создаёт переоценку `value=0` → item уходит в архив.
   - «Удалить безвозвратно» → красный `AlertDialog` → DELETE item (`is_deleted=true`).

Создание нового item'а: тот же Sheet, открытый с пустыми полями. После сохранения Sheet остаётся открытым, показывает только что созданный item с одной записью в истории.

#### `CapitalTrajectoryChart`

- Библиотека: `recharts` (та же, что в `Analytics`).
- Одна линия — итоговый `capital`. Точки — данные из `summary.trajectory`.
- Ось X — даты, метки = месяцы; ось Y — рубли (с компактным форматированием тысячами/миллионами).
- Под графиком — переключатель периода: **6м / 1г / 3г / Всё**. Default: 1г.
- Hover-tooltip на точке: дата + капитал + разбивка `liquid + assets − liabilities` + дельта от предыдущей точки. Пример: «1 апр 2026 · 5 158 000 ₽ · cash 280к + активы 9.95М − долги 5.07М · −12 000 от прошлого месяца».
- Empty state (одна точка «сегодня»): рисуем точку + подпись «История появится по мере переоценок. Хочешь увидеть прошлое — добавь оценку задним числом из Sheet любого актива».

#### `CapitalTheoryDialog` (по клику на «?»)

`shadcn` `Dialog`, ~200-300 слов на русском:
- Что такое капитал (net worth) и зачем его считать физлицу.
- Формула: «активы − обязательства, плюс деньги, которые у вас в наличии».
- Что включаем как актив (квартира, авто, валюта-кэш, ценное имущество).
- Что включаем как обязательство (ипотека, потребкредит, остаток по карте).
- Главный совет: переоценивать раз в 1-3 месяца, не чаще — иначе теряет смысл.
- Связь со стратегическим планированием: «график капитала покажет, движетесь ли вы туда, куда хотите».

Финальный текст утвердим на этапе плана реализации (это контент, не дизайнерское решение).

### Empty state главной страницы

Когда нет ни одного item'а — крупная центральная карточка:

> **Здесь будет ваш капитал**
>
> Добавьте первый актив (квартира, авто, валюта, ценное имущество) или обязательство (ипотека, кредит). Деньги на счетах и копилки уже считаются автоматически.
>
> [+ Добавить актив] [+ Добавить обязательство] [Что такое капитал? →]

Кнопки открывают Sheet для создания. Третья — `CapitalTheoryDialog`.

### Форматирование

- Деньги: `1 240 000 ₽`, тысячные пробелом, `₽` справа.
- Большие суммы (hero, заголовки колонок): компактно `5.24М`, `8.5М`.
- Даты: `dd.MM.yyyy` или `10 мая` — через текущую i18n-функцию проекта.

### Состояния загрузки / ошибки

Стандартные паттерны проекта: skeleton-loaders на первом рендере, toast-нотификации на ошибки.

## Архитектура backend

### Java-классы (план)

- `model.CapitalItem` — entity, маппится на `capital_items`.
- `model.CapitalRevaluation` — entity, маппится на `capital_revaluations`.
- `model.enums.CapitalItemKind` — enum `ASSET` / `LIABILITY`.
- `repository.CapitalItemRepository` — Spring Data JPA.
- `repository.CapitalRevaluationRepository` — с методом `findLatestForItemAtOrBefore(itemId, date)` через нативный запрос или JPQL.
- `service.CapitalService` — единая фасадная служба:
  - CRUD по items и переоценкам.
  - `summary()` — собирает `CapitalSummaryDto` (total + dialy дельты + список items).
  - `trajectory(from, to)` — точки графика.
- `controller.CapitalController` — REST endpoints.
- DTOs в `dto.capital.*` (новый под-пакет).

### Расчёт `Liquid(t)`

`CapitalService` зовёт публичные методы существующих сервисов:
- `BalanceCheckpointService.balanceAt(date)` — может потребовать расширения, если сегодня сервис умеет только «текущий баланс». Это маленькое расширение, не передизайн.
- `TargetFundService.totalFundsBalanceAt(date)` — аналогично.

Если эти методы уже есть под другими именами — переиспользуем; если нет — добавляем минимальные публичные методы. Никаких новых формул не пишется в `CapitalService` — только композиция существующих.

### Расчёт траектории

```
trajectory(from, to):
    points = [end-of-month within [from, to]] ∪ [today]
    points = sorted, deduplicated
    for each t in points:
        liquid_t = LiquidService.liquidAt(t)
        item_values = repository.snapshotItemValuesAt(t)  // одна оптимизированная query
        assets_t = sum(value for kind=ASSET in item_values)
        liabilities_t = sum(value for kind=LIABILITY in item_values)
        capital_t = liquid_t + assets_t - liabilities_t
        yield TrajectoryPoint(t, capital_t, liquid_t, assets_t, liabilities_t)
```

Метод репозитория `snapshotItemValuesAt(t)` — один запрос, который для каждого живого item'а возвращает последнюю переоценку с `valued_at ≤ t`. Делается через `LATERAL JOIN` или window function (`ROW_NUMBER() OVER PARTITION BY item_id ORDER BY valued_at DESC, created_at DESC`). Не делать N+1 — производительно даже на 36 точках × 10 item'ов.

### Транзакционность

- `POST /items` (item + первая revaluation) — `@Transactional`.
- `POST /revaluations` — простой insert, транзакция автоматическая.
- `DELETE /items/{id}` — `@Transactional`, soft-deletes item.
- Никаких pessimistic locks: операции редкие, конкурентного доступа нет.

## Тестирование

Минимальный план тестов (детализируется в плане реализации):

**Unit:**
- `CapitalService.summary()` — пустой / с активами / с обязательствами / с архивными / с дельтами.
- `CapitalService.trajectory()` — точки end-of-month + сегодня; ретроактивная переоценка попадает в нужную точку; step-функция между переоценками; «0 до первой переоценки».
- Валидации DTO: `@PositiveOrZero`, `valuedAt ≤ today`, инварианты I1-I5.
- Edge case: две переоценки в один день — последняя по `created_at` побеждает.
- Edge case: пустая БД — summary возвращает нулевой total и пустой items, trajectory возвращает только точку «сегодня» с liquid из существующего checkpoint.

**Integration (Testcontainers):**
- Полный цикл: create item → revalue → revalue retroactively → archive (value=0) → un-archive (новая переоценка) → hard-delete.
- Trajectory с реальной БД и реальным `BalanceCheckpoint` + `FundTransaction` за прошлые периоды.
- 404 на операции с несуществующим / soft-deleted item'ом.
- 400 на future-dated revaluation, на отрицательное value, на пустое name.

**Frontend:**
- Vitest unit tests на форматтеры и helpers.
- Один интеграционный тест страницы `/capital`: создать актив → увидеть в списке и в hero → переоценить → увидеть обновление.

## Производительность

- Items на пользователя: 5-15 в типовом случае, 30 в редком. Это не нагрузка.
- Revaluations на item: 10-50 за время жизни. Тоже не нагрузка.
- Trajectory: для горизонта «3 года» — 36 точек × до 30 items = 1080 значений → одна оптимизированная SQL-query. Допустимо.
- Cache не нужен. Если в будущем `Liquid(t)` для прошлых месяцев станет дорогим — можно мемоизировать в сервисе на короткий TTL, но не сейчас.

## Безопасность

Капитал — приватные данные пользователя. Текущая авторизация проекта применяется автоматически (если она есть). Никаких новых ролей / прав не вводится.

## Open questions

Намеренно отложены до плана реализации:
- Финальный текст `CapitalTheoryDialog` (~200-300 слов на русском).
- Точный набор пресетов периода в графике (`6м / 1г / 3г / Всё` — гипотеза; проверим на реальных данных).
- Реалистичный набор пресетов дельт (`Месяц / Квартал / Год` — гипотеза).
- Поведение при крайне разреженных данных (один item, одна переоценка): рендерить ли line-chart или точку.

## Future work (не в этом PR)

- Метод прогноза обязательных расходов (отдельная brainstorming-сессия).
- Редизайн стратегического графика — линия капитала на нём (опирается на эту фичу).
- UI-улучшения хотелок с привязкой к доступному капиталу (опирается на эту фичу).
- Telegram-бот, отдельный экран регулярных платежей, FUND_TRANSFER recurring.
- Связь recurring-платежа по ипотеке с обязательством — авто-уменьшение тела долга по факту платежа.
- Автоконвертация `TargetFund(CREDIT)` → `asset + liability` после покупки.
- Мульти-счёт, инвестиционные позиции, курсы валют.
