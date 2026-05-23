# Strategic Graph Redesign — Design Spec

**Дата:** 2026-05-21
**Статус:** Draft
**Область:** backend (новый сервис, контроллер, расширение `PredictionService`), frontend (новый маршрут `/strategy`, страница, два графика, tooltip с разбивкой, переключатели слоёв).

## Цель

Заменить текущий помесячно-агрегированный график в `SavingsStrategySection` (живёт на `/funds`) полноценным **стратегическим экраном** `/strategy`, на котором пользователь видит финансовую траекторию от первого месяца ведения финансов до 3 лет вперёд. Ключевое отличие — кумулятивная линия баланса с конусом неопределённости (fan chart) на будущее, отдельный график капитала под ним, и детальный tooltip с разбивкой по категориям.

Главный вопрос, на который должен отвечать экран — «переживу ли я следующие N месяцев». Сегодня этого не видно: текущий график показывает помесячные суммы, баланс приходится складывать в уме.

## Scope

**В scope:**
- Новая страница `/strategy` в нижней навигации.
- Два графика стопкой с общей осью X: верхний — cashflow (линия баланса + столбики нетто-потока + fan chart), нижний — капитал.
- Старт оси с первого месяца активности пользователя (минимум из first FACT event, first `BalanceCheckpoint`, first `CapitalRevaluation`), горизонт +36 месяцев вперёд.
- Расширение `PredictionService` на произвольный будущий месяц с возвратом percentile-статистик.
- Fan chart P25–P75 с расширением по `√t` (random walk).
- Tooltip с разбивкой income/expense по категориям, recurring помечены иконкой ↻, прогнозные — пометкой «прогноз», ссылка «Открыть Budget месяца».
- Toggles в шапке каждой карточки: fan chart on/off, «только подтверждённое», нетто-столбики, активы/обязательства раздельно.
- Синхронизированный hover между двумя графиками через recharts `syncId`.
- Новый endpoint `GET /api/v1/strategy/timeline` с опциональными параметрами `horizonMonths`, `withBreakdown`.
- Loading / Error / Empty / Partial состояния.

**Out of scope (этого PR):**
- UI хотелок с зонами «зелёная/жёлтая/красная» и ограничениями капитала — отдельный PR следующей итерацией.
- Сезонность в прогнозе (год-к-году, день недели). Сейчас прогноз — плоская медиана.
- Прогноз дохода. Сейчас prediction только для расходов.
- Persistence toggles слоёв в localStorage. Дефолты при каждом открытии.
- Удаление существующего графика на `/funds`. Старый `SavingsStrategySection` остаётся как есть (используется для конфигурации копилок и кредитов).
- Mobile-специфичный layout. Используем тот же дизайн с вертикальным скроллом.
- Авто-обновление при изменении событий в Budget (нет real-time). Хук имеет `refetch`, но автотриггера нет.

## Архитектура

```
            ┌─────────────────────────────────────────┐
            │  Frontend: /strategy                     │
            │  ┌─────────────────────┐                 │
            │  │ CashflowChartCard   │ ← toggles       │
            │  │  • CashflowChart    │                 │
            │  │  • MonthTooltip     │ on hover        │
            │  └─────────────────────┘                 │
            │  ┌─────────────────────┐                 │
            │  │ CapitalTrajectoryCard│ ← toggles      │
            │  │  • CapitalChart     │                 │
            │  │  • MonthTooltip     │ same on hover   │
            │  └─────────────────────┘                 │
            └────────────────┬────────────────────────┘
                             │
                  GET /api/v1/strategy/timeline
                             │
            ┌────────────────▼────────────────────────┐
            │  StrategyTimelineController              │
            └────────────────┬────────────────────────┘
                             │
            ┌────────────────▼────────────────────────┐
            │  StrategyTimelineService (новый)         │
            │  • firstActivityMonth()                  │
            │  • buildPastPoints(from, current)        │
            │  • buildFuturePoints(current, to)        │
            │  • enrichWithCapital(points)             │
            │  • enrichWithBreakdown(points)           │
            └─┬──────────┬─────────┬───────────────┬──┘
              │          │         │               │
        ┌─────▼─────┐ ┌─▼────┐ ┌──▼─────────┐ ┌──▼────────────────┐
        │Prediction │ │Capit.│ │ Financial  │ │ BalanceCheckpoint │
        │Service    │ │Servic│ │ Event Repo │ │ Repository         │
        │ +new      │ │ +pub │ │            │ │                   │
        │ method    │ │liquid│ │            │ │                   │
        └───────────┘ └──────┘ └────────────┘ └───────────────────┘
                     ▲           ▲                ▲
                     │           │                │
                     └─CategoryRepo (+new derived query)
```

**Границы ответственности:**
- `StrategyTimelineService` — только чтение и агрегация; никакого CRUD.
- `PredictionService` — расширение аддитивное (новый метод `getStatsForCategory`), существующие методы для Dashboard не трогаем.
- `CapitalService.trajectory(from, to)` — переиспользуем напрямую. Метод `liquidAt(LocalDate)` сделать публичным (сейчас private).
- `FinancialEventRepository` — переиспользуем существующие методы для построения breakdown.
- `BalanceCheckpointRepository` — переиспользуем напрямую (по аналогии с `CapitalService`).
- `CategoryRepository` — добавляем `findAllByForecastEnabledTrueAndDeletedFalse()`.
- `FundPlannerService` НЕ используется (`StrategyTimelineService` строит данные с нуля, не дублирует helper).
- Frontend только рисует. Никакой агрегации, никакого пересчёта.

## Раздел 1. Что включается в скоуп — что переезжает, что нового

**Backend (новое):**
- `StrategyTimelineService` — оркестратор.
- `StrategyTimelineDto`, `StrategyTimelinePointDto`, `StrategyPointPhase` (enum), `BreakdownDto`, `BreakdownItemDto`.
- `StrategyTimelineController` с endpoint'ом `GET /api/v1/strategy/timeline`.
- Расширение `PredictionService` — новый метод `getStatsForCategory(Category, int historyWindowMonths)`.
- Новый record `CategoryMonthStats` для возврата прогноза.

**Backend (без изменений):**
- Существующие endpoints `/funds/planner`, `/capital/trajectory`, `/analytics/forecast` — не трогаем.
- Существующие методы `PredictionService` — не меняются.
- Никаких миграций БД.

**Frontend (новое):**
- `frontend/src/pages/Strategy.tsx`
- `frontend/src/components/strategy/`:
  - `CashflowChartCard.tsx`
  - `CashflowChart.tsx`
  - `CapitalTrajectoryCard.tsx`
  - `CapitalTrajectoryChart.tsx`
  - `MonthTooltip.tsx`
  - `ChartLegendToggles.tsx`
  - `useStrategyTimeline.ts`
  - `strategyChartUtils.ts`
- Расширение `frontend/src/types/api.ts` (5 новых типов).
- Расширение `frontend/src/api/index.ts` (1 новая функция).
- Расширение `frontend/src/components/BottomNav.tsx` (пункт «Стратегия», иконка `TrendingUp`).
- Регистрация маршрута в `App.tsx`.

**Frontend (без изменений):**
- `SavingsStrategySection.tsx` и всё в `frontend/src/components/funds/` — не трогаем.
- Страница `Funds.tsx` остаётся как есть.

## Раздел 2. Backend сервис и DTO

### Endpoint contract

```
GET /api/v1/strategy/timeline?horizonMonths=36&withBreakdown=true
```

- `horizonMonths` (default 36, max 60) — горизонт вперёд от текущего месяца включительно.
- `withBreakdown` (default true) — включать ли разбивку по категориям. На медленных клиентах можно установить false для уменьшения размера ответа.

**Размер response с breakdown:** при 36 мес прошлого + 36 мес будущего = 72 точки, каждая со средним 8-15 breakdown items = ~30-50 KB JSON в типичном случае. Для пользователя с обширной историей (5 лет, 25 категорий) — до 200 KB. Это укладывается в нормальный bundle/payload bracket для современного веб-приложения. Если потребуется оптимизация — `withBreakdown=false` для первичной загрузки + ленивая подгрузка breakdown по hover отдельным endpoint'ом (не делаем в этом PR).

### Response — `StrategyTimelineDto`

```
{
  "firstActivityMonth": "2024-03",     // YYYY-MM начала оси
  "currentMonth": "2026-05",            // YYYY-MM маркер сегодня
  "horizonEnd": "2029-05",              // YYYY-MM конец оси (current + horizonMonths)
  "predictionWindowMonths": 6,          // сколько месяцев истории использовано
  "fanEnabled": true,                   // false если меньше 3 категорий с >=3 мес истории
  "points": [ /* StrategyTimelinePointDto */, ... ]
}
```

Длина `points` = количество месяцев от `firstActivityMonth` до `horizonEnd` включительно. Каждая точка — отдельный месяц.

### Точка — `StrategyTimelinePointDto`

```
{
  "yearMonth": "2026-09",
  "phase": "PAST" | "CURRENT" | "FUTURE",

  // Кэшфлоу — всегда заполнено
  "balance":        324500,    // кумулятивный баланс на конец месяца (рубли)
  "income":         215000,    // суммарный доход месяца
  "expense":        178200,    // суммарный расход месяца
  "nettoFlow":       36800,    // income − expense

  // Только для FUTURE и CURRENT
  "balanceConfirmed": 312300,  // баланс без прогноза (recurring + manual planned)
  "balanceLow":       311000,  // P25-граница fan chart
  "balanceHigh":      339200,  // P75-граница fan chart

  // Капитал — всегда заполнено
  "capital":        4520000,   // активы − обязательства
  "assets":         5320000,
  "liabilities":     800000,

  // Разбивка для tooltip (опционально)
  "breakdown": {
    "incomeItems": [
      { "category": "Зарплата", "amount": 200000, "isRecurring": false, "isPredicted": false },
      { "category": "Аванс",    "amount":  15000, "isRecurring": true,  "isPredicted": false }
    ],
    "expenseItems": [
      { "category": "Ипотека",  "amount":  80000, "isRecurring": true,  "isPredicted": false },
      { "category": "Продукты", "amount":  38000, "isRecurring": false, "isPredicted": true  }
    ]
  }
}
```

### `StrategyPointPhase` enum

- `PAST` — yearMonth < currentMonth. `balance` восстановлен из фактов, fan-поля null.
- `CURRENT` — yearMonth == currentMonth. Часть фактов есть, остальное прогноз. Заполнены все поля. **`balance` для CURRENT — это `liquidAt(today)` (живой баланс счёта + копилок прямо сейчас), а НЕ end-of-month проекция.** Это обеспечивает согласованность с Dashboard и инвариантом I8.
- `FUTURE` — yearMonth > currentMonth. `balance` = `balanceMedian`. Заполнены все поля.

### `StrategyTimelineService` — публичный контракт

```java
public StrategyTimelineDto getTimeline(int horizonMonths, boolean withBreakdown)
```

Алгоритм:

1. **`firstActivityMonth()`** — `min(first FACT event date, first BalanceCheckpoint at, first CapitalRevaluation valuedAt)`, округление до первого числа месяца. Если ничего нет — `LocalDate.now().minusMonths(1).withDayOfMonth(1)`.

2. **`buildPastPoints(from, currentMonth)`** — для каждого прошлого месяца:
   - Берёт fact-события (INCOME + EXPENSE + FUND_TRANSFER) с агрегированием по категориям. FUND_TRANSFER важен — переводы в копилки уменьшают расчётный счёт, но НЕ меняют общий liquid (`AccountBalance + Σ FundBalance`), как описано в спеке капитала.
   - **Баланс восстанавливается через делегирование:** для каждого прошлого месяца вызывается `liquidAt(endOfMonth)` — нужно сделать метод `liquidAt(LocalDate)` публичным на `CapitalService` (сейчас private), либо вынести в общий `LiquidityService`. Это переиспользует уже корректную формулу `checkpoint + Σ INCOME факт − Σ EXPENSE факт` и автоматически согласуется с Dashboard и `/capital` (инвариант I8).
   - Поля `balanceConfirmed`, `balanceLow`, `balanceHigh` оставляет null.

3. **`buildFuturePoints(currentMonth, to)`** — для каждого будущего месяца:
   - `confirmedExpense` = Σ recurring + manual planned expense события на этот месяц.
   - `confirmedIncome` = Σ recurring + manual planned income события на этот месяц.
   - `balanceConfirmed[k]` = `balanceConfirmed[k-1]` + `confirmedIncome[k]` − `confirmedExpense[k]`.
   - `balanceMedian[k]` = `balanceConfirmed[k]` − `sumMedian × k`, где `sumMedian` = сумма медианных прогнозов по всем `forecast_enabled` категориям. Эта формула — кумулятивное вычитание типичного «непланового» расхода за k прошедших с сегодня месяцев.
   - `balanceLow[k]` / `balanceHigh[k]` — формула в разделе 3.

4. **`enrichWithCapital(points)`** — вызывает `capitalService.trajectory(firstActivityMonth.atDay(1), LocalDate.now())` (метод существует) для прошлых точек. Для будущих точек берёт последние известные `capital`, `assets`, `liabilities` значения из возврата и устанавливает плоскую линию вперёд. `CapitalTrajectoryDto.Point` содержит `LocalDate date` — нужно мапить на `YearMonth` для согласования с `yearMonth` ключом точки timeline. Если `valued_at` ретроактивных revaluation попадает в будущее (пользователь сказал «у меня будет квартира через год») — этот factor подхватывается автоматически тем же `trajectory` вызовом расширенного диапазона; обновляем семантику: вызов делается на `(firstActivityMonth.atDay(1), horizonEnd.atEndOfMonth())` чтобы покрыть и будущие revaluations.

5. **`enrichWithBreakdown(points)`** — если `withBreakdown=true`:
   - Для PAST: агрегированные фактические события по категориям.
   - Для FUTURE: recurring + planned + predicted (соответствующие пометки в полях `isRecurring`, `isPredicted`).
   - Для CURRENT: смешанные фактические события (до сегодня) + прогноз остатка месяца.

### Где живут классы

```
backend/src/main/java/ru/selfin/backend/
├── controller/StrategyTimelineController.java        — новый
├── service/StrategyTimelineService.java              — новый
├── service/PredictionService.java                    — extend новым методом
└── dto/strategy/
    ├── StrategyTimelineDto.java                      — новый
    ├── StrategyTimelinePointDto.java                 — новый
    ├── StrategyPointPhase.java (enum)                — новый
    ├── BreakdownDto.java                             — новый
    └── BreakdownItemDto.java                         — новый
```

### Зависимости от существующих сервисов

**`CapitalService.trajectory(LocalDate from, LocalDate to)`** — метод существует, возвращает `CapitalTrajectoryDto` с точками `(date, capital, assets, liabilities)`. Принимает диапазон, что нам и нужно. Вызывается ОДИН раз на построение timeline.

**`CapitalService.liquidAt(LocalDate t)`** — сейчас private. Нужно сделать публичным (или вынести в отдельный `LiquidityService` если хочется чище разделить ответственности). Это самый компактный фикс: один-два символа в сигнатуре + переисполнить тесты CapitalService.

**`BalanceCheckpointRepository`** инжектится напрямую в `StrategyTimelineService` (по аналогии с `CapitalService`). Существующий `BalanceCheckpointService` не имеет нужных методов поиска чекпоинта на дату — добавлять их специально для StrategyTimeline не нужно, прямой доступ к репозиторию через JPQL запрос «найти ближайший чекпоинт ≤ дата» проще. Метод репозитория `findTopByDateLessThanEqualOrderByDateDesc(LocalDate)` — добавляется при необходимости (если `liquidAt` уже его использует — переиспользуем).

**`CategoryRepository.findAllByForecastEnabledTrueAndDeletedFalse()`** — НОВЫЙ Spring Data derived-query метод, добавляется в этом PR. Нет миграции БД, только Java-метод.

**`FundPlannerService`** — не используется напрямую (`StrategyTimelineService` строит данные с нуля, не через готовый planner DTO). При желании можно переиспользовать `FundPlannerService.getPlanner()` для получения месячных агрегатов, но это даст double-counting с собственным агрегатором. **Решение:** не зависим от `FundPlannerService`. Архитектурная схема выше — устаревшая в этой части.

## Раздел 3. Алгоритм прогноза и fan chart

### Расширение `PredictionService`

**Новый метод:**

```java
public CategoryMonthStats getStatsForCategory(Category cat, int historyWindowMonths)
```

**Возвращаемое значение:**

```java
public record CategoryMonthStats(
    UUID categoryId,
    int monthsOfHistory,      // 0–historyWindowMonths
    BigDecimal median,         // P50 за окно
    BigDecimal p25,
    BigDecimal p75
)
```

**Логика:**
1. Берём fact-расходы по категории за последние `historyWindowMonths` полных месяцев. Фильтр: `eventKind = FACT`, `deleted = false` — тот же критерий что в существующем `PredictionService.sumFacts()`. `EventStatus` не используется — все FACT-события считаются учётной транзакцией. Агрегируем по месяцу.
2. `monthsOfHistory` = количество месяцев с хотя бы одним фактом.
3. Если `monthsOfHistory == 0` — возвращаем все нули.
4. Если `monthsOfHistory < 3` — возвращаем `median`/p25/p75 на тех данных что есть; caller знает что эту категорию не учитывать в fan.
5. Percentile-вычисление через стандартный подход (линейная интерполяция между точками).

**Что не меняется:** существующие методы `PredictionService` для Dashboard — без изменений. Это аддитивное расширение.

### Алгоритм fan chart в `StrategyTimelineService`

**Шаг 1 (один раз в начале `buildFuturePoints`):**

```
forecastEnabledCategories = categoryRepo.findAllByForecastEnabledTrueAndDeletedFalse()
allStats = forecastEnabledCategories.map(cat ->
    predictionService.getStatsForCategory(cat, 6))

eligibleStats = allStats.filter(s -> s.monthsOfHistory >= 3)

sumMedian = Σ eligibleStats.median
sumHalfIqr = sqrt(Σ ((s.p75 - s.p25) / 2)² for s in eligibleStats)

fanEnabled = eligibleStats.size() >= 3
```

`sumMedian` — сколько в среднем уходит на forecast-категории за один месяц.
`sumHalfIqr` — типичный разброс одного месяца (root-sum-of-squares, предположение независимости).
`fanEnabled` — выключаем fan если меньше 3 категорий имеют достаточно истории.

**Шаг 2 (для каждой будущей точки k, где k=1 = next month, k=horizonMonths = последний):**

```
// Начальное значение (seed):
balanceConfirmed[0] = liquidAt(today)   // тот же баланс что у CURRENT точки

confirmedExpense[k] = Σ (recurring + manual planned events of EXPENSE type for month yearMonth(now+k))
confirmedIncome[k]  = Σ (recurring + manual planned events of INCOME type for month yearMonth(now+k))

balanceConfirmed[k] = balanceConfirmed[k-1] + confirmedIncome[k] - confirmedExpense[k]
balanceMedian[k]    = balanceConfirmed[k] - sumMedian * k

// Random walk neopredelyennost — std rastyot kak sqrt(t):
accumulatedHalfIqr[k] = sumHalfIqr * sqrt(k)

balanceLow[k]  = balanceMedian[k] - accumulatedHalfIqr[k]
balanceHigh[k] = balanceMedian[k] + accumulatedHalfIqr[k]

balance[k] = balanceMedian[k]   // основное значение для линии
```

**Если `fanEnabled == false`:**
- `balanceLow[k]` = `balanceMedian[k]`
- `balanceHigh[k]` = `balanceMedian[k]`
- Frontend этого не рисует (нулевая площадь).

**Cap на ширину конуса:** чтобы патологические случаи (одна категория с гигантским IQR на ранней истории) не делали конус визуально абсурдным, ограничиваем:
```
accumulatedHalfIqr[k] = min(sumHalfIqr × sqrt(k), 2 × |balanceMedian[k]|)
```
Конус никогда не шире чем 4× от медианного баланса в точке. Граница условная, может быть отрегулирована по фидбеку — главное чтобы был сам факт ограничения.

### Граничные случаи

| Ситуация | Поведение |
|---------|-----------|
| Пользователь новый, истории нет | `fanEnabled=false`. Линия `balance` = `balanceConfirmed` (без вычитания прогноза). Fan ширина = 0. |
| Категория `is_deleted = true` | Исключается из forecast суммы. Прошлые факты не учитываются в новых прогнозах. |
| Recurring правило бессрочное, до 36+ мес | Уже работает (lazy-extend). `TimelineService` просто читает события. |
| Капитал на будущее | Last known value — плоская линия. Если есть `CapitalRevaluation` с `valued_at` в будущем (ретроактивно введённая «у меня будет квартира через год») — учитываем. |
| Текущий месяц (`CURRENT`) | Прогноз применяется только к остатку месяца. Логика: `predictedExpenseRemainder = sumMedian × (1 - daysPassed/daysInMonth)`. Это «честный» прогноз — в начале месяца ширина больше, в конце меньше. |

### Производительность

- `buildFuturePoints` — `O(horizonMonths × forecastCategories)`. При 36 мес × 20 категорий — 720 операций, копейки.
- `buildPastPoints` — `O(pastMonths × categories × events)`. Главное — избежать N+1: события за весь диапазон получаются одним SELECT с группировкой.
- Capital trajectory вызывается один раз, кешируется в памяти на время запроса.
- Полный response должен выполняться <200 мс при разумных объёмах (≤5 лет истории, ≤20 категорий, ≤30 recurring правил).

## Раздел 4. Frontend layout страницы `/strategy`

Иерархия и расположение:

```
<StrategyPage>
  <PageHeader>
    "Стратегия"
    "Финансовая траектория с <firstMonth> на 3 года вперёд"
  </PageHeader>

  <CashflowChartCard>
    <ChartLegendToggles>
      "Баланс" (always on, не toggle)
      [Диапазон] toggle
      [Нетто-потоки] toggle
      [Только подтверждённое] toggle
    </ChartLegendToggles>
    <CashflowChart>
      • Линия balance (фиолетовая, сплошная)
      • Линия balanceConfirmed (приглушённая, пунктирная) — если toggle on
      • Area от balanceLow до balanceHigh (полупрозрачная) — если toggle on
      • Bars nettoFlow (зелёные/красные, полупрозрачные) — если toggle on
      • ReferenceLine на currentMonth — жёлтая пунктирная вертикаль
      <MonthTooltip /> on hover
    </CashflowChart>
  </CashflowChartCard>

  <CapitalTrajectoryCard>
    <ChartLegendToggles>
      "Чистый" (always on)
      [Активы] toggle
      [Обязательства] toggle
    </ChartLegendToggles>
    <CapitalTrajectoryChart>
      • Линия capital (зелёная)
      • Линия assets (приглушённая) — если toggle on
      • Линия liabilities (приглушённая) — если toggle on
      • Тот же ReferenceLine на currentMonth
      <MonthTooltip /> on hover
    </CapitalTrajectoryChart>
  </CapitalTrajectoryCard>
</StrategyPage>
```

Цвета:
- Линия баланса: `#6c63ff` (existing primary).
- Fan chart area: `rgba(108, 99, 255, 0.12)`.
- Линия «подтверждённое»: `#9da9b8` strokeDasharray `4 3`.
- Bars positive: `rgba(34, 197, 94, 0.4)`.
- Bars negative: `rgba(239, 68, 68, 0.4)`.
- Маркер «Сегодня»: `#fbbf24` strokeDasharray `3 3`.
- Линия капитала: `#22c55e`.
- Activs/liabilities: `rgba(255,255,255,0.3)`.

Layout: вертикальная стопка с gap 12px между карточками. Карточки имеют `border: 1px solid var(--color-border)`, `border-radius: 10px`, padding `14px`. Внутри карточки: заголовок + toggles в одну строку, ниже chart canvas.

Высота: верхняя карточка ~240px (более информативная), нижняя ~150px (одна-три линии без bars и fan).

## Раздел 5. Frontend компоненты, state, API

### Файловая структура

```
frontend/src/pages/Strategy.tsx
frontend/src/components/strategy/
├── CashflowChartCard.tsx
├── CashflowChart.tsx
├── CapitalTrajectoryCard.tsx
├── CapitalTrajectoryChart.tsx
├── MonthTooltip.tsx
├── ChartLegendToggles.tsx
├── useStrategyTimeline.ts
└── strategyChartUtils.ts
```

### `useStrategyTimeline` hook

```typescript
export function useStrategyTimeline(params?: {
    horizonMonths?: number;
    withBreakdown?: boolean;
}): {
    data: StrategyTimelineDto | null;
    isLoading: boolean;
    error: string | null;
    refetch: () => void;
}
```

Реализация: `useEffect` + `fetchStrategyTimeline()` из api. In-memory кэш через `useRef` — повторный hover/re-render не вызывает повторного fetch. Refetch на ручной trigger. Авторефетч при focus нет.

### Per-card local state для toggles

```typescript
// CashflowChartCard:
const [showFan, setShowFan] = useState(true);
const [showNettoBars, setShowNettoBars] = useState(true);
const [showConfirmed, setShowConfirmed] = useState(false);

// CapitalTrajectoryCard:
const [showAssets, setShowAssets] = useState(false);
const [showLiabilities, setShowLiabilities] = useState(false);
```

Состояние НЕ персистентится. Открыл — дефолты.

### API client

`frontend/src/api/index.ts`:

```typescript
export const fetchStrategyTimeline = (params?: {
    horizonMonths?: number;
    withBreakdown?: boolean;
}) => {
    const qs = new URLSearchParams();
    if (params?.horizonMonths !== undefined) qs.set('horizonMonths', String(params.horizonMonths));
    if (params?.withBreakdown !== undefined) qs.set('withBreakdown', String(params.withBreakdown));
    const query = qs.toString();
    return get<StrategyTimelineDto>(`/strategy/timeline${query ? '?' + query : ''}`);
};
```

### Recharts детали

**`CashflowChart`** — `ComposedChart` (умеет смешивать Line/Bar/Area):
- **Fan chart реализация:** предпочтительный подход — добавить в данные дополнительное поле `balanceRange = [balanceLow, balanceHigh]` (массив двух чисел), и использовать `<Area dataKey="balanceRange" stroke="none" fill={...} />` — recharts корректно интерпретирует массив как нижнюю+верхнюю границу полосы. Это избегает известного трюка с «вычитанием» нижней Area через `fill="var(--color-bg)"`, который ломается при полупрозрачных карточках. Поле `balanceRange` собирается в `strategyChartUtils.ts` при маппинге DTO в chart data.
- `<Bar dataKey="nettoFlow">` + `<Cell fill={...}/>` с условным цветом
- `<Line dataKey="balance" stroke="#6c63ff" strokeWidth={2}/>`
- `<Line dataKey="balanceConfirmed" stroke="#9da9b8" strokeDasharray="4 3"/>`
- `<ReferenceLine x={currentMonth} stroke="#fbbf24"/>`
- `<Tooltip content={<MonthTooltip />} />` — кастомный
- `syncId="strategyTimeline"`

**`CapitalTrajectoryChart`** — простой `LineChart`:
- `<Line dataKey="capital" stroke="#22c55e"/>`
- `<Line dataKey="assets" stroke="rgba(...)"/>` (если toggle)
- `<Line dataKey="liabilities" stroke="rgba(...)"/>` (если toggle)
- Тот же `ReferenceLine` и `syncId`

**Синхронизация hover** — `syncId="strategyTimeline"` на обоих графиках. Recharts автоматически распространяет hover-cursor между ними.

### `MonthTooltip` — общий компонент

```typescript
interface MonthTooltipProps {
    active?: boolean;
    payload?: Array<{ payload: StrategyTimelinePointDto }>;
    label?: string;
}
```

Получает `payload[0].payload` — это `StrategyTimelinePointDto` (типизированно, не `any`). Извлекает breakdown и рендерит:
- Шапка с `yearMonth` (формат «Сентябрь 2026 (через N мес)» если future)
- Баланс на конец + диапазон если future
- Блок дохода: `incomeItems`, recurring с иконкой ↻ перед текстом, прогнозные с пометкой «(прогноз)»
- Блок расхода: симметрично
- Ссылка «Открыть Budget этого месяца →» (navigate router-link)

### Loading / Error / Empty / Partial

| Состояние | Что показываем |
|-----------|----------------|
| Loading | Skeleton каждой карточки — серая плашка с CSS pulse. |
| Error | Карточка-сообщение «Не удалось загрузить» + кнопка «Повторить» (вызывает `refetch`). |
| Empty (новый пользователь) | Карточка-плейсхолдер: «Здесь появится финансовая траектория, когда вы начнёте записывать события и снимки баланса». Линк на Budget. |
| Partial (fan недоступен) | Linии рисуем; в шапке cashflow-карточки маленький инфо-badge «Прогноз пока недоступен (нужно ≥3 месяца истории)». |

### Навигация

`BottomNav.tsx`: добавить пункт «Стратегия» между «Capital» и «Settings». Иконка `TrendingUp` из lucide-react. Маршрут `/strategy` в `App.tsx`.

### Зависимости

- `recharts` — уже в проекте.
- `lucide-react` — уже в проекте.
- Никаких новых npm-зависимостей.

## Раздел 6. Тестирование

### Backend unit tests

**`PredictionServiceTest` — расширение:**

| Тест | Что проверяет |
|------|---------------|
| `getStatsForCategory_with_6mo_history_returns_correct_percentiles` | Подаём фейк-историю 6 месяцев, проверяем median/P25/P75 численно |
| `getStatsForCategory_with_2mo_history_returns_low_history` | `monthsOfHistory < 3`, статы возвращаются на доступных данных |
| `getStatsForCategory_with_zero_history_returns_zero_stats` | Новая категория |
| `getStatsForCategory_ignores_soft_deleted_events` | `deleted=true` не учитывается |
| `getStatsForCategory_uses_only_EXPENSE_FACT_events` | INCOME, PLAN-без-FACT, FUND_TRANSFER игнорируются |

**`StrategyTimelineServiceTest` — новый класс, Mockito:**

| Тест | Что проверяет |
|------|---------------|
| `firstActivityMonth_with_only_fact_event` | Возвращает месяц этого события |
| `firstActivityMonth_with_only_checkpoint` | Возвращает месяц чекпоинта |
| `firstActivityMonth_with_only_capital_revaluation` | Возвращает месяц revaluation |
| `firstActivityMonth_with_all_three_returns_earliest` | Min из трёх |
| `firstActivityMonth_with_no_data_returns_previous_month` | Fallback на `today.minusMonths(1)` |
| `getTimeline_past_points_use_only_facts` | Прошлые точки — баланс из чекпоинта + интегрирование |
| `getTimeline_future_points_have_balanceConfirmed_and_balanceMedian_and_fan_bounds` | Будущие точки имеют все 5 балансовых полей; fan расширяется по √k |
| `getTimeline_fanEnabled_false_when_fewer_than_3_categories_have_history` | Конус не строится, low/high = median |
| `getTimeline_capital_for_future_uses_last_known_value` | Капитал в будущем — flat линия |
| `getTimeline_with_breakdown_false_omits_breakdown_field` | Опция работает |
| `getTimeline_recurring_events_are_in_balanceConfirmed_not_in_prediction` | Recurring не дублируются в прогнозе |
| `getTimeline_current_month_uses_partial_prediction` | Прогноз только на остаток текущего месяца |

### Backend integration test

**`StrategyTimelineControllerIT` через Testcontainers + MockMvc:**

| IT | Сценарий |
|----|----------|
| 1 | GET endpoint возвращает 200 + валидный JSON. Сидим 3 факта + 1 recurring + 1 капитал-item, проверяем основные поля |
| 2 | `?horizonMonths=12` ограничивает горизонт — `points` нужной длины |
| 3 | `?withBreakdown=false` исключает breakdown |
| 4 | Пустая база — endpoint возвращает 200, `firstActivityMonth = today.minusMonths(1)`, `fanEnabled=false`, points есть для каждого месяца |
| 5 | После создания recurring-правила (через POST /api/v1/events с `recurringConfig`, что приводит к материализации событий через RecurringRuleService) — платежи в `balanceConfirmed` будущих точек. Тест НЕ должен напрямую инсертить события в БД — нужно идти через реальный flow создания, чтобы валидировать интеграцию с recurring infrastructure. |
| 6 | После PATCH-факта в текущем месяце — этот факт в breakdown CURRENT точки |

### Frontend тесты

Следуем существующей практике проекта — для большинства компонентов unit-тесты не пишем, проверка через build + typecheck + manual smoke. Для `useStrategyTimeline` и `strategyChartUtils.ts` опциональные минимальные unit-тесты на vitest, не блокируем PR на их отсутствии.

### Manual smoke matrix

- [ ] Open `/strategy` на новом аккаунте (empty state) — видна подсказка.
- [ ] Открыть на аккаунте с историей — две карточки, маркер «Сегодня» на правильной X-позиции.
- [ ] Hover на месяц в прошлом — tooltip с фактическими income/expense items.
- [ ] Hover на месяц в будущем — tooltip с recurring (↻) + prediction (пометка).
- [ ] Toggle Fan chart off → конус исчезает.
- [ ] Toggle «Только подтверждённое» on → появляется пунктирная линия.
- [ ] Toggle Активы on → появляется вторая линия на капитале.
- [ ] Hover на cashflow → синхронно подсвечивается курсор на капитальном.
- [ ] Кнопка «Открыть Budget» в tooltip — переход в Budget на нужный месяц.
- [ ] Resize окна — графики перерисовываются.
- [ ] Mobile-ширина — нет горизонтального скролла, графики читаемы.

### Edge case manual checks

- Ретроактивная capital revaluation → траектория капитала корректно «прыгает» назад.
- Recurring правило начинается через 2 года → его платежи появляются на графике с правильного месяца.
- Категория без `forecast_enabled` — не учитывается в fan.
- Прогноз для CURRENT месяца уменьшается по мере прохождения дней.

## Инварианты

- **I1** — `firstActivityMonth ≤ currentMonth ≤ horizonEnd`.
- **I2** — `points` отсортированы по `yearMonth` возрастающе, без пропусков (по одной точке на каждый месяц).
- **I3** — для PAST точек `balanceConfirmed`, `balanceLow`, `balanceHigh` равны null (или равны `balance`, на выбор реализации; контракт DTO — null).
- **I4** — для FUTURE точек `balanceLow ≤ balance ≤ balanceHigh`.
- **I5** — при `fanEnabled=false` для всех точек `balanceLow == balance == balanceHigh`.
- **I6** — `breakdown` либо целиком null (если `withBreakdown=false`), либо целиком заполнен.
- **I7** — суммы из `breakdown.incomeItems` сходятся с `income` точки (с точностью до округления). То же для expense.
- **I8** — `balance[currentMonth]` равен текущему фактическому балансу счёта + Σ балансов копилок.

## Будущие итерации (Out of scope этого PR, отдельные задачи)

- UI хотелок с зонами и ограничениями капитала.
- Сезонность в прогнозе.
- Прогноз дохода (сейчас только расходы).
- Persistence toggles в localStorage.
- Удаление старого `SavingsStrategySection` после миграции пользователей.
- Совместный hover между `/strategy` и `/capital` (cross-page).
- Drill-down — клик на точку открывает мини-страницу с детальным разбором этого месяца.
- Цели и контрольные точки (миля «достичь капитала 10М к 2030»).
