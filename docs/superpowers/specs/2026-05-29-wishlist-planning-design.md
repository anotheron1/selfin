# Wishlist Planning — Design Spec

**Дата:** 2026-05-29
**Статус:** Draft
**Область:** backend (новая колонка `wishlist_status` на `financial_events` и `target_funds`, новая таблица `user_settings`, новый сервис `WishlistSimulationService`, расширения существующих сервисов и контроллеров), frontend (новый маршрут `/wishlist` со сводным графиком и UI-конструктором сценариев, удаление двух старых секций со страницы `/funds`).

## Цель

Заменить «архаичный» UI хотелок и копилок единым модулем планирования крупных решений. Пользователь видит список своих «хотелок» (что-нибудь дорогое, что он хочет купить), копилок (планомерное накопление) и кредитов как один список «крупных решений». Двигая ползунки цены и даты для каждого решения, он в реальном времени видит, как изменится его счёт и капитал на горизонте до трёх лет, и попадает ли в «красную зону» (cash gap или капитал ниже выбранного порога).

Эта фича закрывает последний из пяти пунктов исходного брейнсторма января 2026 года. К моменту реализации уже готовы: recurring events (PR #10), capital (PR #11), strategy graph (PR #12). Без них модуль был бы невозможен — он использует все три источника как вход.

## Scope

**В scope:**

- Новая страница `/wishlist` в нижней навигации с тремя зонами: настройки порогов, сводный график влияния, список items с ползунками.
- Объединение трёх существующих сущностей под одним UX: `FinancialEvent.priority=LOW` (хотелки), `TargetFund` с `purchaseType=SAVINGS` (копилки), `TargetFund` с `purchaseType=CREDIT` (кредиты).
- Жизненный цикл item'а: `OPEN → FIXED → DISMISSED` с явной кнопкой «Зафиксировать» и опциональной конверсией в `FinancialEvent` (PLAN), `TargetFund` или `TargetFund + RecurringRule`.
- Backend simulation endpoint, отдающий baseline timeline (через существующий `StrategyTimelineService`) + delta-векторы по месяцам для каждого item'а. Frontend локально складывает baseline + Σ active deltas с мгновенным фидбеком.
- Цветовая разметка риска на сводном графике (зелёный/жёлтый/красный) с двумя критериями одновременно: cash gap (`account < 0` или `account < monthlyExpensesAvg × cashBufferMonths`) и капитал ниже порога (`capital < capitalThreshold`).
- Настройки порогов (`capitalThresholdRub`, `cashBufferMonths`) живут в шапке `/wishlist` и сохраняются в новой таблице `user_settings`.
- Расширение `StrategyTimelineService`: items со статусом `FIXED` (с конверсией или без) учитываются в timeline на `/strategy` и `/capital`.
- Удаление со страницы `/funds` двух «архаичных» секций: `WishlistSection` и `SavingsStrategySection`. Страница `/funds` остаётся только со списком копилок и кнопкой их создания.

**Out of scope (этого PR):**

- Сезонность прогноза, прогноз дохода. `PredictionService` остаётся в текущем виде; хотелки используют только cashflow-влияние и не пытаются скорректировать прогноз расходов.
- Drag-and-drop точки прямо на графике. Используем стандартные range-input'ы под графиком.
- Мульти-юзер. Таблица `user_settings` хранит одну запись на key; multi-tenancy не моделируется.
- Telegram-бот, отдельный экран регулярных платежей, FUND_TRANSFER recurring.
- Совместный hover между `/wishlist` и `/strategy`, `/capital`. Графики живут независимо.
- Persistence ползунков `amount/date` в БД на каждом движении. Сохранение через `PATCH /events/{id}` или `PATCH /funds/{id}` происходит при отпускании ползунка (debounce/onRelease).
- Дополнительный «жирный» граф под-сценариев (типа «сравни две комбинации»). Один сводный график с включением/выключением items.

## Философия

> **«Хотелки — это будущие решения, ещё не выпавшие в реальность. Их влияние моделируется, но не воплощается в Budget, пока пользователь явно не зафиксирует.»**

Из этой формулировки вытекают ключевые решения:

- Item в статусе `OPEN` существует только в собственном модуле — не виден в `/budget`, не виден в `/dashboard`. Он влияет на симуляцию `/wishlist`, но не на baseline timeline `/strategy` и `/capital`.
- Item в статусе `FIXED` без конверсии — это «принято к учёту в долгосрочном плане», виден в `/strategy` и `/capital`, но не в `/budget` (там нет соответствующего PLAN-event).
- Item в статусе `FIXED` с конверсией порождает реальную сущность (`PLAN-event` / `TargetFund` / `TargetFund + RecurringRule`), которая живёт самостоятельно. Исходный item остаётся со статусом `FIXED` и хранит ссылку на конвертированный артефакт.
- Симметрия между типами решений: хотелка, копилка, кредит — три kind'а одной модели «решение с целевой суммой и датой».
- Конверсия — не точка невозврата: `FIXED → OPEN` возможен в любой момент, при этом сконвертированный артефакт остаётся (или удаляется по явному выбору).
- Никаких авто-конверсий или авто-удалений. Все переходы между статусами — явные действия пользователя.

## Архитектура

```
            ┌────────────────────────────────────────────────────┐
            │  Frontend: /wishlist                                │
            │  ┌────────────────────────────────────────────────┐│
            │  │ WishlistThresholdsHeader                       ││
            │  │  ├─ capitalThresholdRub input                 ││
            │  │  └─ cashBufferMonths input                    ││
            │  └────────────────────────────────────────────────┘│
            │  ┌────────────────────────────────────────────────┐│
            │  │ WishlistImpactChart (composedline + bars)      ││
            │  │  ├─ background ReferenceAreas (risk zones)     ││
            │  │  ├─ ReferenceLine (capital threshold)          ││
            │  │  └─ MonthTooltip on hover                      ││
            │  └────────────────────────────────────────────────┘│
            │  ┌────────────────────────────────────────────────┐│
            │  │ WishlistItemList                               ││
            │  │  ├─ Section: OPEN (visible)                    ││
            │  │  │   └─ WishlistItemCard × N                   ││
            │  │  ├─ Section: FIXED (collapsed)                 ││
            │  │  └─ Section: DISMISSED (collapsed)             ││
            │  └────────────────────────────────────────────────┘│
            └─────────────────┬──────────────────────────────────┘
                              │
                  GET /api/v1/wishlist/simulation
                              │
            ┌─────────────────▼──────────────────────────────────┐
            │  WishlistSimulationController                       │
            └─────────────────┬──────────────────────────────────┘
                              │
            ┌─────────────────▼──────────────────────────────────┐
            │  WishlistSimulationService                          │
            │   ├─ buildBaseline()      ─→ StrategyTimelineService│
            │   ├─ collectItems()       ─→ FinancialEventService + │
            │   │                          TargetFundService       │
            │   └─ computeDeltaForItem() ─→ pure math              │
            └─────────────────────────────────────────────────────┘
```

Симуляция строится на одном backend-вызове на загрузку страницы. Все последующие манипуляции ползунками amount/targetDate — локальные на frontend (умножение и сдвиг delta-вектора). При изменении нелинейных параметров (срок/ставка кредита, целевая сумма копилки) — отдельный `POST /simulation/recompute` пересчитывает delta только этого item'а.

## Модель данных

### Миграция Flyway V18

```sql
-- 1. wishlist_status на financial_events
ALTER TABLE financial_events
    ADD COLUMN wishlist_status VARCHAR(16);

ALTER TABLE financial_events
    ADD CONSTRAINT chk_wishlist_status_only_low
    CHECK (wishlist_status IS NULL OR priority = 'LOW');

ALTER TABLE financial_events
    ADD COLUMN converted_to_event_id UUID REFERENCES financial_events(id) ON DELETE SET NULL;

ALTER TABLE financial_events
    ADD CONSTRAINT chk_converted_only_fixed
    CHECK (converted_to_event_id IS NULL OR wishlist_status = 'FIXED');

CREATE INDEX idx_events_wishlist_status
    ON financial_events (wishlist_status)
    WHERE wishlist_status IS NOT NULL;

-- 2. wishlist_status на target_funds
ALTER TABLE target_funds
    ADD COLUMN wishlist_status VARCHAR(16);

ALTER TABLE target_funds
    ADD COLUMN converted_to_event_id UUID REFERENCES financial_events(id) ON DELETE SET NULL;

ALTER TABLE target_funds
    ADD CONSTRAINT chk_fund_converted_only_fixed
    CHECK (converted_to_event_id IS NULL OR wishlist_status = 'FIXED');

CREATE INDEX idx_funds_wishlist_status
    ON target_funds (wishlist_status)
    WHERE wishlist_status IS NOT NULL;

-- 3. Backfill активных копилок и кредитов как FIXED
UPDATE target_funds SET wishlist_status = 'FIXED' WHERE status = 'ACTIVE';

-- 4. user_settings
CREATE TABLE user_settings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    settings_key VARCHAR(64) NOT NULL UNIQUE,
    settings_value JSONB NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

### Семантика статусов

| `wishlist_status` | Что значит | Видна на `/wishlist` | Включена в симуляцию по умолчанию | Влияет на `/strategy`, `/capital` | Видна в `/budget` |
|---|---|---|---|---|---|
| `NULL` | Обычная сущность (не хотелка) | нет | n/a | да (через свой PLAN) | да |
| `OPEN` | На обсуждении | в секции «Активные» | да | **нет** | нет |
| `FIXED` | Принято решение | в секции «Зафиксированные» (свёрнуто) | да | **да** (через delta) | только если есть `converted_to_event_id` |
| `DISMISSED` | Отклонена | в секции «Отклонённые» (свёрнуто) | нет | нет | нет |

### Инварианты

- **I1.** `wishlist_status != NULL` для `financial_events` ⇒ `priority = 'LOW'`. Защита: CHECK constraint `chk_wishlist_status_only_low`. Для `target_funds` ограничение отсутствует — копилки/кредиты любого типа могут быть хотелкой.
- **I2.** `converted_to_event_id != NULL` ⇒ `wishlist_status = 'FIXED'`. Защита: CHECK constraint `chk_converted_only_fixed` (и `chk_fund_converted_only_fixed`).
- **I3.** Удаление сконвертированного `FinancialEvent` через `DELETE /events/{id}` не каскадно удаляет исходный wishlist item, но обнуляет ссылку `converted_to_event_id` (`ON DELETE SET NULL`). Хотелка остаётся в `FIXED` без артефакта — пользователь увидит это в UI и сможет вернуть в `OPEN` или заново конвертировать.
- **I4.** Повторная конверсия уже сконвертированного item'а → `409 Conflict`. Семантически — один item имеет максимум один артефакт.
- **I5.** Item со статусом `DISMISSED` не отдаётся в `WishlistSimulationDto.items`, но остаётся в БД и виден на `/wishlist` в свёрнутой секции «Отклонённые». Может быть восстановлен.

### Backfill и миграция данных

После V18 применяется на проде:
- Все существующие `target_funds.status = 'ACTIVE'` становятся `wishlist_status = 'FIXED'`. Они сразу видны на новой странице `/wishlist` как «зафиксированные», их можно редактировать или вернуть в обсуждение.
- Все `target_funds.status = 'CLOSED'` остаются `wishlist_status = NULL`. Они закрытая история, не хотелки.
- Все существующие `financial_events.priority = 'LOW'` без даты остаются `wishlist_status = NULL`. Чтобы они попали в `/wishlist`, пользователь должен явно их «принять» — но мы упрощаем: SELECT на странице `/wishlist` фильтрует по `priority='LOW' AND (wishlist_status IS NULL OR wishlist_status='OPEN')`. То есть «исторические» хотелки автоматически считаются `OPEN` до тех пор, пока пользователь не пометит их `FIXED` или `DISMISSED`.

## API

### Эндпоинт симуляции

```
GET /api/v1/wishlist/simulation?horizonMonths=36
```

Один заход возвращает всё необходимое для отрисовки страницы.

```jsonc
{
  "baseline": {
    "firstActivityMonth": "2024-01",
    "horizonMonths": 36,
    "forecastFrom": "2026-07",
    "points": [
      {
        "month": "2026-06",
        "account": 250000,
        "capital": 1200000,
        "p25": 240000,
        "p75": 260000
      }
      // ... 36 точек по числу месяцев горизонта
    ]
  },
  "items": [
    {
      "id": "uuid",
      "kind": "WISHLIST",
      "name": "Новый ноут",
      "categoryId": "uuid",
      "amount": 150000,
      "targetDate": "2027-09-15",
      "status": "OPEN",
      "convertedToEventId": null,
      "delta": [
        { "monthIndex": 15, "accountDelta": -150000, "capitalDelta": -150000 }
      ]
    },
    {
      "id": "uuid",
      "kind": "SAVINGS",
      "name": "Отпуск",
      "amount": 200000,
      "targetDate": "2027-06-01",
      "monthlyContribution": 16667,
      "status": "OPEN",
      "convertedToEventId": null,
      "delta": [
        { "monthIndex": 0,  "accountDelta": -16667, "capitalDelta": 0, "fundDelta": 16667 },
        // ... 11 промежуточных месяцев
        { "monthIndex": 12, "accountDelta": -200000, "capitalDelta": -200000, "fundDelta": -200000 }
      ]
    },
    {
      "id": "uuid",
      "kind": "CREDIT",
      "name": "Машина",
      "amount": 2000000,
      "targetDate": "2026-08-01",
      "rate": 16.5,
      "termMonths": 60,
      "monthlyPMT": 48700,
      "status": "FIXED",
      "convertedToEventId": "uuid-of-target-fund",
      "delta": [
        { "monthIndex": 2, "accountDelta": 2000000, "capitalDelta": 0, "liabilityDelta": 2000000 },
        { "monthIndex": 3, "accountDelta": -48700, "capitalDelta": 21200, "liabilityDelta": -21200 }
        // ... 59 ежемесячных платежей
      ]
    }
  ],
  "thresholds": {
    "capitalThresholdRub": 1000000,
    "cashBufferMonths": 1.0
  },
  "constraints": {
    "monthlyExpensesAvg": 95000,
    "monthlyIncomeAvg": 180000,
    "currentCapital": 1200000,
    "maxWishlistAmount": 4140000,
    "maxCreditAmount": 9000000
  }
}
```

Поле `delta` для каждого item'а нормализовано на текущий `amount` item'а. Frontend при изменении `amountOverride` локально умножает все значения delta на `amountOverride / item.amount`.

Поле `delta` обрезано по `monthIndex < horizonMonths` — даже у длинного кредита оно никогда не превысит 36 точек.

### Эндпоинт пересчёта одного item'а

```
POST /api/v1/wishlist/simulation/recompute
Content-Type: application/json

{
  "kind": "CREDIT",
  "amount": 2500000,
  "targetDate": "2026-09-01",
  "rate": 18.0,
  "termMonths": 72
}
```

Возвращает только массив `MonthDeltaDto[]` для свежевычисленных параметров. Используется при изменении нелинейных параметров (ставка/срок кредита, монтлы-взнос копилки). Простые ползунки amount/date не требуют этого вызова — frontend перешкаливает существующий delta локально.

### CRUD статусов

```
PATCH /api/v1/events/{id}/wishlist-status
{ "status": "OPEN" | "FIXED" | "DISMISSED" }

PATCH /api/v1/funds/{id}/wishlist-status
{ "status": "OPEN" | "FIXED" | "DISMISSED" }
```

Идемпотентны (повторный переход в тот же статус возвращает 200 без изменений). Не отвечают за создание артефактов — только меняют статус.

### Конверсия в реальные сущности

```
POST /api/v1/wishlist/items/{itemId}/convert
Content-Type: application/json

{
  "sourceKind": "WISHLIST" | "SAVINGS" | "CREDIT",
  "target": "PLAN_EVENT" | "FUND" | "FUND_WITH_CREDIT",
  "createRecurringPayments": false   // только для FUND_WITH_CREDIT
}
```

Ответ:
```jsonc
{
  "wishlistItemId": "uuid",
  "newStatus": "FIXED",
  "artifactKind": "PLAN_EVENT" | "FUND" | "FUND_WITH_CREDIT",
  "artifactId": "uuid",
  "recurringRuleId": "uuid"   // если createRecurringPayments=true
}
```

Что создаётся под капотом, в одной транзакции:

- **`PLAN_EVENT`**: новый `FinancialEvent` (`eventKind=PLAN`, `status=PLANNED`, `type=EXPENSE`, `priority=LOW`, `wishlist_status=NULL`, `categoryId` копируется из исходного item'а, `plannedAmount=item.amount`, `date=item.targetDate`, `description=item.name`). Исходный item обновляется (`wishlist_status='FIXED'`, `converted_to_event_id` = новый `FinancialEvent.id`).
- **`FUND`**: новый `TargetFund` (`status=ACTIVE`, `wishlist_status='FIXED'`, `purchaseType=SAVINGS`, `targetAmount=item.amount`, `name=item.name`, deadline опционально). Исходный item обновляется.
- **`FUND_WITH_CREDIT`**: новый `TargetFund` (`purchaseType=CREDIT`, `creditRate=item.rate`, `creditTermMonths=item.termMonths`, ...). Если `createRecurringPayments=true` — дополнительно создаётся `RecurringRule` для PMT-платежей (`frequency=MONTHLY`, `dayOfMonth=targetDate.dayOfMonth`, `endDate=targetDate+termMonths`).

Повторная конверсия → `409 Conflict` с body `{ existingArtifactId, artifactKind }`. Frontend показывает ссылку на существующий артефакт.

### Настройки

```
GET /api/v1/settings/wishlist
PUT /api/v1/settings/wishlist
{ "capitalThresholdRub": 1000000, "cashBufferMonths": 1.0 }
```

Поля nullable. `capitalThresholdRub = null` отключает критерий капитала. `cashBufferMonths` обязателен, дефолт `1.0` при первом GET.

### Ошибки

- `400 BAD_REQUEST` — `wishlist-status` на не-LOW событии; `convert` с невозможной комбинацией kind+target; `targetDate` в прошлом; `cashBufferMonths < 0` или `> 36`; `capitalThresholdRub < 0`.
- `404 NOT_FOUND` — item не найден.
- `409 CONFLICT` — повторная конверсия уже сконвертированного item'а.

### DTO

- `WishlistSimulationDto` — корневой ответ `GET /simulation`.
- `WishlistTimelineBaselineDto` — `baseline` сегмент (повторяет существующий `StrategyTimelineDto`).
- `WishlistItemDto` — один item. Поля: `id`, `kind`, `name`, `amount`, `targetDate`, `status`, `convertedToEventId`, `delta[]`, плюс kind-specific: `categoryId` (WISHLIST), `monthlyContribution` (SAVINGS), `rate`/`termMonths`/`monthlyPMT` (CREDIT).
- `MonthDeltaDto` — `{ monthIndex, accountDelta, capitalDelta, fundDelta?, liabilityDelta? }`. Поля fundDelta и liabilityDelta используются только для агрегированной визуализации в tooltip (cashflow и capital уже включают их).
- `WishlistThresholdsDto` — `{ capitalThresholdRub: nullable BigDecimal, cashBufferMonths: BigDecimal }`.
- `WishlistConstraintsDto` — `{ monthlyExpensesAvg, monthlyIncomeAvg, currentCapital, maxWishlistAmount, maxCreditAmount }`.
- `ConvertWishlistRequestDto` — `{ sourceKind, target, createRecurringPayments? }`.
- `ConvertWishlistResponseDto` — `{ wishlistItemId, newStatus, artifactKind, artifactId, recurringRuleId? }`.
- `WishlistStatusUpdateDto` — `{ status }`.

## Frontend и UX

### Маршрут и навигация

Новый таб `Хотелки` в `BottomNav` (иконка `lucide:Sparkles` или `lucide:ListChecks`). Маршрут `/wishlist`. Заголовок страницы: «Хотелки», подзаголовок: «Что я могу себе позволить и когда».

### Структура страницы (Layout)

```
┌────────────────────────────────────────────────────────┐
│ Хотелки                                                 │
│ Что я могу себе позволить и когда                      │
├────────────────────────────────────────────────────────┤
│ ┌─── Пороги ────────────────────────────────────────┐ │
│ │ Минимальный капитал: [   1 000 000 ₽ ▼ ]        │ │
│ │ Буфер счёта:         [   1 месяц расходов ▼ ]    │ │
│ │ ≈ не давать счёту опуститься ниже 95 000 ₽       │ │
│ └────────────────────────────────────────────────────┘ │
├────────────────────────────────────────────────────────┤
│ ┌─── График влияния ────────────────────────────────┐ │
│ │ [cashflow line + risk zones background]           │ │
│ │ ─────────────── threshold capital ─────────────── │ │
│ │ [capital line]                                     │ │
│ └────────────────────────────────────────────────────┘ │
│   Зелёный фон = безопасно · Жёлтый = тонко · Красный  │
├────────────────────────────────────────────────────────┤
│ ┌─── Активные / OPEN (3) ──────────────────────────┐ │
│ │ [✓] ● Машина в кредит              [Зафиксир.]   │ │
│ │     2 000 000 ₽ · август 2026                     │ │
│ │     Сумма: ──────●──── 2.0 М (макс. 2.4 М)       │ │
│ │     Дата:  ───●──────  авг 2026                  │ │
│ │     PMT 48 700 ₽/мес · 5 лет под 16.5%           │ │
│ │     [Изменить параметры кредита ▾]                │ │
│ │ ─────────────────────────────────────────────     │ │
│ │ [✓] ● Ноут                         [Зафиксир.]   │ │
│ │     150 000 ₽ · сентябрь 2027                     │ │
│ │     Сумма: ─────────●─ 150 к (макс. 180 к)       │ │
│ │     Дата:  ──────●──── сен 2027                  │ │
│ │ ─────────────────────────────────────────────     │ │
│ │ [ ] ● Отпуск                       [Зафиксир.]   │ │
│ │     200 000 ₽ · июнь 2027                         │ │
│ │     ...                                            │ │
│ └────────────────────────────────────────────────────┘ │
│ ┌─── Зафиксированные (2) ▼ ─────────────────────────┐ │
│ │ ... (свёрнуто по умолчанию)                       │ │
│ └────────────────────────────────────────────────────┘ │
│ ┌─── Отклонённые (5) ▶ ─────────────────────────────┐ │
│ │ ... (свёрнуто по умолчанию)                       │ │
│ └────────────────────────────────────────────────────┘ │
│                                                          │
│ [ + Добавить хотелку ]                                  │
└────────────────────────────────────────────────────────┘
```

### Компоненты

- **`<WishlistThresholdsHeader>`** — два number-input'а. При изменении — `PUT /settings/wishlist` debounced 800 ms. Перерасчёт цветовых зон локально (не требует нового запроса).
- **`<WishlistImpactChart>`** — один сводный ComposedChart (recharts). Левая Y-ось: счёт (рубли). Правая Y-ось: капитал. Фоновые `ReferenceArea` по месяцам с цветами зон, прозрачность 8–12%. Горизонтальная `ReferenceLine` — `capitalThreshold`. Tooltip с per-item breakdown.
- **`<WishlistItemList>`** — три секции (OPEN, FIXED, DISMISSED). OPEN всегда развёрнута. FIXED и DISMISSED по умолчанию свёрнуты. Каждая показывает счётчик в заголовке.
- **`<WishlistItemCard>`** — чекбокс активности, название, бейдж риска, два ползунка (сумма и дата) с подписями текущих значений и max, опциональный раскрывающийся блок параметров (кредит/копилка). Кнопка «Зафиксировать» (или «Вернуть в обсуждение» для FIXED).
- **`<WishlistRiskBadge>`** — компактный цветной значок. Считается как «риск этого item'а в одиночку»: применяем delta только этого item'а к baseline, проверяем зоны. `red` если хоть один месяц красный, `yellow` если жёлтый, иначе `green`.
- **`<FixWishlistDialog>`** — диалог конверсии при нажатии «Зафиксировать». Радио-выбор target'а с дефолтом по `kind` item'а. Для `FUND_WITH_CREDIT` — чекбокс «Создать платёжный график (recurring)».
- **`<AddWishlistDialog>`** — диалог создания нового item'а с выбором kind (WISHLIST/SAVINGS/CREDIT) и заполнением полей.
- **`<DeleteWishlistDialog>`** — подтверждение удаления. Если есть `convertedToEventId` — предлагает «удалить также созданный план/копилку».

### Ползунки — ограничения и значения

**Min:**
- Сумма: `0` (но при 0 item автоматически отключается из симуляции)
- Дата: для WISHLIST/CREDIT — `сегодня + 1 день`; для SAVINGS — `сегодня + 1 месяц`

**Max** (отдаются с backend в `WishlistConstraintsDto`):
- `maxWishlistAmount` = `currentCapital + monthlyIncomeAvg × horizonMonths × 0.5`
- `maxCreditAmount` = `monthlyIncomeAvg × 50`
- Дата: `сегодня + horizonMonths`

### Цветовые зоны

Для каждой точки месяца сводного timeline:

- `accountRisk` = `red` если `account < 0`; `yellow` если `account < monthlyExpensesAvg × cashBufferMonths`; иначе `green`.
- `capitalRisk` (если `capitalThresholdRub != null`) = `red` если `capital < capitalThresholdRub`; `yellow` если `capital < capitalThresholdRub × 1.1`; иначе `green`. Если `capitalThresholdRub == null` — критерий выключен (`green` всегда).
- `pointRisk` = `max(accountRisk, capitalRisk)` (red > yellow > green).

### Drag UX

- Локальная композиция timeline = O(items × horizon) = 30 × 36 = ~1080 операций. Перерисовка через `requestAnimationFrame`.
- Сохранение `amount`/`targetDate` в БД на onRelease (или debounced 500 ms): `PATCH /events/{id}` или `PATCH /funds/{id}`.
- При изменении параметров под-формы кредита/копилки (`rate`, `termMonths`, `targetAmount`) — `POST /simulation/recompute`. Получаем новый delta-вектор, заменяем в state, перерисовываем.

### Empty / Loading / Error

- **Empty (нет items)**: hero-блок «Запишите первую хотелку — увидите, когда сможете её себе позволить» + кнопка «+ Добавить».
- **Loading**: скелетон графика (280 px) + 3 скелетона карточек.
- **Error**: «Не удалось загрузить» + кнопка «Повторить» (mirror `/strategy` error UI).
- **Partial**: если `capital trajectory` недоступна (нет ни одного `capital_item`) — график показывает только cashflow, верхний пользовательский callout: «Создайте записи в /capital, чтобы увидеть влияние на капитал».

### Удаление со страницы `/funds`

В этом же PR убираем со страницы `frontend/src/pages/Funds.tsx`:
- `<WishlistSection>` и его файлы `WishlistSection.tsx`, `WishlistItem.tsx`, `WishlistForm.tsx`
- `<SavingsStrategySection>` и его файлы `SavingsStrategySection.tsx`, `savingsStrategyUtils.ts`

Backend-эндпоинты, которые они использовали (`GET /events?priority=LOW`, `PATCH /events/{id}`) остаются — они используются новой страницей через расширенные сигнатуры (`?wishlist_status=`). Логика расчёта PMT/амортизации в `target_funds` остаётся в `TargetFundService` — она вызывается из `WishlistSimulationService`.

### Форматирование

Числовые значения:
- `≥ 1 000 000` → `1.2 М` (миллионы с одной цифрой после запятой).
- `≥ 1 000` → `1.5 к` (тысячи).
- Иначе → стандартный `Intl.NumberFormat('ru-RU')`.

Даты:
- В заголовке: `август 2026` (месяц прописью, без дня).
- В tooltip графика: `авг 2026` (сокращённо).

## Архитектура backend

### Java-классы

```
ru.selfin.backend.service
├─ WishlistSimulationService
│   ├─ getSimulation(horizonMonths) → WishlistSimulationDto
│   ├─ recomputeItemDelta(kind, params) → List<MonthDeltaDto>
│   └─ private computeDeltaForItem(item) → List<MonthDeltaDto>
├─ WishlistConversionService
│   └─ convertItem(itemId, request) → ConvertWishlistResponseDto
├─ UserSettingsService
│   ├─ getWishlistSettings() → WishlistThresholdsDto
│   └─ updateWishlistSettings(dto) → WishlistThresholdsDto

ru.selfin.backend.controller
├─ WishlistController                          (GET /simulation, POST /recompute, POST /items/{id}/convert)
├─ UserSettingsController                      (GET/PUT /settings/wishlist)
└─ existing FinancialEventController           (PATCH /events/{id}/wishlist-status — добавляется)
└─ existing FundController                     (PATCH /funds/{id}/wishlist-status — добавляется)

ru.selfin.backend.dto
├─ WishlistSimulationDto, WishlistTimelineBaselineDto
├─ WishlistItemDto, MonthDeltaDto
├─ WishlistThresholdsDto, WishlistConstraintsDto
├─ ConvertWishlistRequestDto, ConvertWishlistResponseDto
├─ WishlistStatusUpdateDto

ru.selfin.backend.model
├─ FinancialEvent — добавляется поле wishlist_status, converted_to_event_id
├─ TargetFund — добавляется поле wishlist_status, converted_to_event_id
├─ UserSettings — новая сущность
├─ enums.WishlistStatus  (OPEN, FIXED, DISMISSED)

ru.selfin.backend.repository
├─ UserSettingsRepository (extends JpaRepository, findBySettingsKey)
├─ existing FinancialEventRepository  — добавляется findLowEventsByWishlistStatus
└─ existing TargetFundRepository — добавляется findActiveFundsByWishlistStatus
```

### Расширение `StrategyTimelineService`

Сейчас baseline собирается из `events + recurring + prediction`. Добавляем четвёртый источник — items со статусом `FIXED`:

```
public StrategyTimelineDto buildTimeline(int horizonMonths) {
    // existing: events + recurring + prediction → baselinePoints
    // NEW: for each FIXED wishlist item (events with wishlist_status=FIXED and funds with wishlist_status=FIXED),
    //      compute its delta via WishlistSimulationService.computeDeltaForItem()
    //      and sum into baselinePoints.
}
```

Технически: в `StrategyTimelineService` инжектится `WishlistSimulationService`. Чтобы избежать циклической зависимости (`WishlistSimulationService` сам использует `StrategyTimelineService` для baseline), вводим внутренний метод `StrategyTimelineService.buildBaselineWithoutWishlist()`. Это «честный» baseline без хотелок, используемый и для `/strategy` (как раньше), и для `WishlistSimulationService` (тоже как baseline). На `/strategy` к нему сверху накладываются deltas всех FIXED items — это и есть «новое» поведение.

## Жизненный цикл item'а

Состояния и переходы:

```
                ┌─────────────────────────────────────────────┐
                │                  СОЗДАНИЕ                    │
                │  user тыкает [+ Добавить хотелку]            │
                │  диалог: WISHLIST / SAVINGS / CREDIT         │
                │  заполняет: имя, сумма, дата (+ параметры)   │
                └────────────────┬────────────────────────────┘
                                 │
                                 ▼
                          ┌──────────────┐
                ┌────────▶│     OPEN     │◀─────┐
                │         │  (видна в    │       │
                │         │   списке     │       │  «вернуть в обсуждение»
                │         │  активных)   │       │   (PATCH OPEN)
                │         └─┬──┬────┬────┘       │
                │           │  │    │            │
                │           │  │    │ DELETE     │
                │           │  │    └──────────▶ ╳ ушла навсегда
                │           │  │
                │           │  │ PATCH FIXED + диалог конверсии
                │           │  ▼
                │      ┌────────────────┐
                │      │     FIXED      │
                │      │  + опционально │
                │      │ converted_to:  │
                │      │  PLAN_EVENT /  │
                │      │  FUND / CREDIT │
                │      └─┬──────────┬───┘
                │        │          │
                │  PATCH │          │ DELETE
                │  OPEN  │          │ (с диалогом)
                │        │          ▼
                └────────┘          ╳
                                 ▲
                                 │
                          PATCH OPEN
                                 │
                          ┌──────┴───────┐
                          │  DISMISSED   │
                          │   (видна в   │
                          │  свёрнутой   │
                          │   секции)    │
                          └──────────────┘
                                ▲
                                │
                          PATCH DISMISSED
                                │
                          из OPEN или FIXED
```

### Правила переходов

| Из | В | Что происходит |
|---|---|---|
| (нет) | OPEN | Создание через «+ Добавить» |
| OPEN | FIXED | Кнопка «Зафиксировать» → диалог конверсии. Опционально создаётся артефакт. |
| OPEN | DISMISSED | Иконка «Отклонить». |
| FIXED | OPEN | Кнопка «Вернуть в обсуждение». Артефакт остаётся. |
| FIXED | DISMISSED | С подтверждением. Артефакт остаётся. |
| DISMISSED | OPEN | Кнопка «Восстановить». |
| любое | (удалено) | Кнопка-корзина. Если был артефакт — диалог: удалить также? |

### Идемпотентность

- `PATCH /wishlist-status` идемпотентен — переход в тот же статус возвращает 200.
- `POST /convert` повторный → 409 с body `{ existingArtifactId, artifactKind }`.

## Тестирование

### Backend Unit-тесты (Mockito)

**`WishlistSimulationServiceTest`** (≥ 7 тестов):
- `computeDeltaForItem_wishlist_returnsSingleMonthExpense`
- `computeDeltaForItem_savings_returnsMonthlyContributionsPlusFinalPurchase`
- `computeDeltaForItem_credit_returnsLumpSumPlusMonthlyPMT`
- `computeDeltaForItem_pastDate_returnsEmpty`
- `computeDeltaForItem_horizonExceeded_clipsToHorizon`
- `simulation_includesOnlyActiveItems` (DISMISSED отфильтрованы)
- `simulation_baselineExcludesFixedItems` (baseline без хотелок, deltas отдельно)

**`UserSettingsServiceTest`**:
- `getOrInitWishlistSettings_firstCall_returnsDefaults`
- `updateThresholds_invalidValues_throws400`
- `updateThresholds_nullCapitalThreshold_disablesCapitalRisk`

**`WishlistConversionServiceTest`**:
- `convert_planEvent_createsLinkedEvent_andTransitionsToFixed`
- `convert_planEvent_failureRollsBack`
- `convert_alreadyConverted_throws409`
- `convert_credit_withRecurringPayments_createsRecurringRule`
- `setStatus_onNonLowEvent_throws400`
- `setStatus_idempotent_returnsCurrent`

### Backend Integration-тесты (Testcontainers + MockMvc)

**`WishlistControllerIT`** (≥ 8 тестов):
- `GET /wishlist/simulation` на пустой БД → пустой `items[]` + дефолтный `thresholds`
- `GET /wishlist/simulation` с тремя items разных kinds → все deltas корректные
- `POST /convert WISHLIST → PLAN_EVENT` end-to-end, проверка существования нового event в `/budget`
- Повторный `POST /convert` → 409 с body содержащим existing ID
- `POST /convert CREDIT → FUND_WITH_CREDIT` с `createRecurringPayments=true` создаёт TargetFund + RecurringRule
- `PATCH /wishlist-status FIXED→OPEN`, артефакт остаётся, ссылка сохраняется
- `PUT /settings/wishlist` затем `GET` возвращает то же
- `GET /strategy/timeline` после создания FIXED-without-conversion item'а отражает его влияние на timeline

### Frontend Unit-тесты (vitest)

**`composeTimeline.test.ts`**:
- `emptyItems_returnsBaseline`
- `singleWishlist_appliesDeltaAtCorrectMonth`
- `multipleItems_sumsDeltas`
- `disabledItem_excluded`
- `scalingByAmount_linear`

**`riskZones.test.ts`**:
- `accountNegative_isRed`
- `accountBelowBuffer_isYellow`
- `capitalBelowThreshold_isRed`
- `capitalNearThreshold_isYellow`
- `nullCapitalThreshold_capitalCriterionDisabled`
- `combinedRisk_maxOfTwo`

**`useWishlistSimulation.test.ts`** (MSW):
- `loadsBaseline_setsActiveByDefault`
- `toggleItem_recomputesTimelineLocally`
- `dragAmountSlider_scalesDelta_locally` (без сетевого запроса)
- `changeCreditTerm_triggersRecomputeEndpoint`

**`WishlistItemCard.test.tsx`**:
- `wishlistKind_rendersTwoSliders`
- `creditKind_rendersExpandableForm`
- `disabledCheckbox_passesActiveFalseToOnChange`
- `riskBadge_redWhenSoloRiskRed`

**`FixWishlistDialog.test.tsx`**:
- `defaultTargetByKind` (WISHLIST → PLAN_EVENT, SAVINGS → FUND, CREDIT → FUND_WITH_CREDIT)
- `withoutConversion_sendsStatusFixedOnly`
- `creditTarget_offersRecurringPaymentsCheckbox`

### Что не покрываем

- Визуальные/snapshot-тесты recharts. Проверяем что `data` prop корректен.
- Performance бенчмарки. Целевой «60 fps при 10 items × 36 мес» — manual smoke.
- Аутентификация. Single-user.

### Manual smoke matrix

- Создание item каждого kind → видна в OPEN
- Toggle disable → линия пропадает с графика, бейдж становится серым
- Drag amount → перерисовка без сетевых запросов (Network в DevTools пустой)
- Изменение `termMonths` кредита в под-форме → `POST /simulation/recompute`, delta обновляется
- Конверсия WISHLIST → PLAN_EVENT → появление в `/budget`
- Конверсия CREDIT → FUND_WITH_CREDIT с recurring → появление в `/funds` и регулярные платежи в Budget
- Переход FIXED → OPEN, артефакт остался
- Изменение thresholds в шапке, debounce 800 ms, цвета пересчитались
- Empty state на пустой БД
- Funds-страница после удаления секций выглядит ОК

## Будущие итерации (out of scope этого PR)

- Drag-and-drop точки на графике.
- Сравнение нескольких сценариев одновременно («с машиной» vs «без машины»).
- Расширенная разбивка delta по категориям (сейчас только cashflow и capital).
- Совместный hover между `/wishlist` и `/strategy`, `/capital`.
- Multi-user.
- Telegram-нотификации о попадании timeline в красную зону при изменениях.
- Машинный совет «возьми эту хотелку через N месяцев чтобы остаться в зелёной зоне».
