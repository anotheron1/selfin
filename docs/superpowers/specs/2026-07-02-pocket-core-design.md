# Кармашек: единое ядро расчёта (ANO-12)

Дата: 2026-07-02. Статус: дизайн согласован устно, спека на ревью.
Linear: [ANO-12](https://linear.app/anotheron1space/issue/ANO-12/karmashek-re-vision-logiki-ostatok-plan) (эпик ANO-8). Контекст пересборки: `2026-06-19-product-rethink-design.md` §11.

## 1. Цель и рамка

Боль: кармашек считается в трёх местах тремя разными формулами (`TargetFundService.calcPocketBalance`, `DashboardService`, `FundPlannerService` + скрытый `adjustedPocket`), пользователь не понимает «почему столько», расчёты расходятся (вероятный источник бага ANO-6).

Рамка: **кармашек — ОДНА сущность** (forward discretionary / safe-to-spend, dip-aware), Funds / Dashboard / кассовый календарь — **представления одного расчёта**. Пересборка существующего, не с нуля.

Принятая формула (решение 2026-07-02):

> **Кармашек(скоуп) = минимум прогнозной траектории баланса внутри скоупа − буфер.**

На дефолтном скоупе «до следующего дохода» траектория монотонно убывает (доходов внутри нет по определению), минимум = конец горизонта — формула ведёт себя как простое «остаток − плановые траты». На растянутых скоупах минимум защищает от провалов в середине периода. День минимума = «ближайшая угроза» (вход для ANO-13).

## 2. Архитектура

```
Репозитории → PocketService (обвязка) → PocketEngine (pure) → PocketResult
                     ↓
      GET /api/v1/pocket?scope=...  ← Funds, Dashboard, календарь, (ANO-16 sandbox)
```

Два юнита:

- **`PocketEngine`** — класс со статическими pure-методами (паттерн `WishlistSimulationService`). Ноль обращений к БД, ноль Spring-зависимостей. Вход и выход — простые DTO. Вся формула живёт здесь.
- **`PocketService`** — тонкая Spring-обвязка: собирает вход из репозиториев (`BalanceCheckpointRepository`, `FinancialEventRepository`, `UserSettingsService`, `PredictionService`), вызывает движок, отдаёт результат контроллеру `PocketController`.

## 3. PocketEngine

### 3.1 Вход (`PocketInput`)

| Поле | Тип | Источник (собирает PocketService) |
|---|---|---|
| `asOfDate` | LocalDate | обычно сегодня |
| `checkpointAmount`, `checkpointDate` | BigDecimal, LocalDate | последний `BalanceCheckpoint`; если нет — 0 и null (расчёт от нуля, обратная совместимость) |
| `events` | List<EventSnapshot> | события `deleted=false` от даты чекпоинта (или самого раннего события) до конца скоупа |
| `overdueReserve` | BigDecimal | отдельный запрос (см. 3.4) |
| `scope` | PocketScope | из query-параметра |
| `horizonEnd` | LocalDate | вычислен сервисом по скоупу (см. §4) |
| `bufferAmount` | BigDecimal | настройки `pocket`, дефолт 0 |
| `unplannedForecast` | BigDecimal | `PredictionService.forecastFromEvents(...).netPredictionDelta()`, ≥ 0; 0 если прогноза нет |

`EventSnapshot`: `date, type (INCOME/EXPENSE/FUND_TRANSFER), eventKind, status, priority, plannedAmount, factAmount, wishlistStatus`.

### 3.2 Правило эффективной суммы (одно на всё)

- запись с `factAmount != null` → **факт** (покрывает и причуду FUND_TRANSFER, где `doTransfer` создаёт запись с `eventKind=PLAN`, но с factAmount);
- `PLAN(PLANNED)` без factAmount → **план** (plannedAmount);
- `PLAN(EXECUTED)` без factAmount → **пропуск** (вклад уже учтён FACT-ребёнком).

Знак: INCOME +, EXPENSE/FUND_TRANSFER −.

### 3.3 Алгоритм

1. **Текущий баланс** = checkpoint + Σ фактов (правило 3.2, только записи-факты) с даты чекпоинта по `asOfDate` включительно. Прошлые `PLAN(PLANNED)` в баланс НЕ входят (деньги не потрачены — баланс не убываем).
2. **День 0**: `currentBalance − overdueReserve − плановые расходы с датой = asOfDate` (ещё предстоит потратить сегодня). **Плановые доходы с датой ≤ asOfDate не учитываются** — ждём факта. Асимметрия намеренная и консервативная: не показывать деньги, которых ещё нет (угол «граница сегодня»: в день зп до прихода факта кармашек не раздувается).
3. **Траектория**: по дням от `asOfDate+1` до `horizonEnd`; каждый день применяются `PLAN(PLANNED)` этого дня + дневная доля `unplannedForecast` (см. 3.5).
4. **Min-точка** = минимальное значение траектории, включая день 0; при равенстве — ранняя дата.
5. **Кармашек** = min − buffer.
6. **Хотелки-кандидаты** (`wishlistStatus = OPEN`) в баланс и траекторию **не входят** — собираются в отдельное поле результата (сумма + список), информационная строка breakdown.

### 3.4 Резерв просрочки

Новый запрос в `FinancialEventRepository` — вариант существующего `sumOverdueMandatoryExpenses` **без нижней границы месяца**: `PLAN(PLANNED)`, EXPENSE, HIGH, `date < asOfDate`, `deleted=false`, `NOT EXISTS` FACT-детей (защита от двойного резервирования сохраняется). Старый двухпараметровый запрос остаётся для нетронутых потребителей до их миграции.

Осознанное ограничение v1: просроченные **не-HIGH** планы не резервируются («обязательность» = HIGH). Градиент обязательности — вне скоупа (углы в комментариях ANO-12, транзакционная модель — ANO-9).

### 3.5 Прогноз незапланированных трат

v1 повторяет текущую семантику `PredictionService` (линейные категории, горизонт — текущий месяц): сервис передаёт движку одну сумму, движок равномерно распределяет её по дням от `asOfDate+1` до `min(конец текущего месяца, horizonEnd)`. За пределами текущего месяца прогноз не экстраполируется. Порог 100₽ из старого `adjustedPocket` убран — строка показывается при любой ненулевой сумме. Пересмотр логики и подачи прогноза — [ANO-22](https://linear.app/anotheron1space/issue/ANO-22/peresmotr-prognoza-nezaplanirovannyh-trat-predictionservice-ux-logika) (не блокирует: движок получает готовую сумму, при пересмотре меняется только источник).

### 3.6 Выход (`PocketResult`)

`pocket`, `currentBalance`, `minPoint {date, balance}`, `horizon {type, endDate, label, fallback:boolean}`, `buffer`, `trajectory [{date, balance}]`, `breakdown [строки, см. §5]`, `wishlistCandidates [{id, description, plannedAmount, date?}]`.

## 4. Скоупы

| Скоуп | Query | Конец горизонта |
|---|---|---|
| До следующего дохода (дефолт) | `scope=NEXT_INCOME` или отсутствует | дата ближайшего будущего `PLAN(PLANNED)` INCOME **любой категории** (включительно — день дохода виден в траектории). Решает аванс-кейс без «надёжного признака дохода» (тот флаг — про Dashboard, не блокирует). Фолбэк, если будущих плановых доходов нет: `asOfDate + 30 дней`, `horizon.fallback=true`, label «горизонт условный — нет плановых доходов» |
| +N месяцев | `scope=MONTHS:3` (1..36) | `asOfDate + N месяцев` |
| До даты | `scope=DATE:2027-03-01` (строго в будущем, ≤ asOfDate+36 мес) | указанная дата. Для ANO-16 «до реализации хотелки» |

Невалидный скоуп → 400 (`GlobalExceptionHandler`).

## 5. Breakdown — «почему столько»

Список типизированных строк `{type, label, amount, details?}`; фронт рендерит по порядку без своей логики. Типы (enum `BreakdownType`):

| type | Пример label | amount |
|---|---|---|
| STARTING_BALANCE | «Остаток на счёте (чекпоинт 28.06 + движение)» | 80 000 |
| OVERDUE_RESERVE | «Просроченные обязательства (2 шт)» | −6 000 |
| PLANNED_EXPENSES | «Плановые расходы до 15.07» | −24 000 |
| PLANNED_INCOME | «Плановые доходы до 15.07» (только на растянутых скоупах) | +90 000 |
| UNPLANNED_FORECAST | «Прогноз незапланированных (продукты, такси)» | −4 000 |
| TRAJECTORY_MIN | «Минимум траектории (12.07)» | 46 000 |
| BUFFER | «Буфер (настройка)» | −10 000 |
| POCKET | «Кармашек» | 36 000 |
| WISHLIST_INFO | «Хотелки-кандидаты (не вычтены)» | 20 000 |

Строки с нулевой суммой (кроме STARTING_BALANCE, TRAJECTORY_MIN, POCKET) опускаются. `details` — опциональный список подстрок (например, состав просрочки), v1: заполняется для OVERDUE_RESERVE и UNPLANNED_FORECAST (имена категорий).

## 6. API

`GET /api/v1/pocket?scope=...` → 200:

```json
{
  "pocket": 36000, "currentBalance": 80000, "buffer": 10000,
  "horizon": {"type": "NEXT_INCOME", "endDate": "2026-07-15", "label": "до зп 15.07", "fallback": false},
  "minPoint": {"date": "2026-07-12", "balance": 46000},
  "breakdown": [...], "trajectory": [{"date": "2026-07-02", "balance": 74000}, ...],
  "wishlistCandidates": [...]
}
```

Один ответ кормит: число (Funds/Dashboard), фразу ANO-13 (`minPoint`), календарь-близнец ANO-14 (`trajectory`), сценарии ANO-16 (движок с подменённым входом).

## 7. Буфер (настройка)

Ключ `pocket` в `user_settings` (паттерн ключа `wishlist`): `{"bufferAmount": 10000}`. `GET/PUT /api/v1/settings/pocket` в `UserSettingsController`/`UserSettingsService`. Валидация: `bufferAmount ≥ 0`, иначе 400. Дефолт при отсутствии ключа: 0. Миграции БД не требуется. Динамический буфер — [ANO-21](https://linear.app/anotheron1space/issue/ANO-21/karmashek-dinamicheskij-bufer-bezopasnosti-posle-peresborki), вне скоупа.

## 8. Миграция представлений (по шагам, каждый шаг зелёный)

1. **Ядро**: `PocketEngine` + `PocketService` + `PocketController` + настройка буфера + тесты. Существующие страницы не тронуты.
2. **Funds → `/pocket`**: виджет кармашка берёт `pocket` + breakdown из нового endpoint (минимальный UI: число + список строк breakdown; полноценная подача — ANO-13/14). `calcPocketBalance`, `afterAllExpenses`, `adjustedPocket` и их поля в `FundsOverviewDto` удаляются вместе с фронт-потребителями.
3. **Dashboard**: число кармашка из `/pocket`. Блок двух зарплатных горизонтов и `detectCashGap` не трогаем (территория ANO-13/14; зп-детект по имени — флаг в ANO-12).
4. **Кассовый календарь → `trajectory`** из того же ответа. Здесь верификация ANO-6: календарь и кармашек обязаны сходиться, потому что это один расчёт.

Шаги 1–2 — минимальный обязательный объём ANO-12; шаги 3–4 — тот же тикет, отдельные PR-ы (шаг 4 закрывает ANO-6 или сужает его до фронтового стейла, если баг не в расчёте).

## 9. Тестирование

**Юнит (табличные, чистый движок, без Testcontainers):**
- мартовский пример из `2026-03-22-free-money-calculation.md` — сходимость с эталоном на дефолтном скоупе;
- просрочка через границу месяца (аренда 30.06, взгляд 02.07 → зарезервирована);
- аванс+зп: горизонт до аванса (INCOME любой категории);
- граница «сегодня»: плановый доход сегодня не считается, плановый расход сегодня — считается;
- провал в середине на `MONTHS:3` (min ≠ конец);
- буфер вычитается; буфер 0 = min;
- хотелки OPEN не вычитаются, попадают в wishlistCandidates;
- unplannedForecast размазан по дням и виден строкой;
- факт вытесняет план (PLAN(EXECUTED) пропущен, FACT посчитан);
- FUND_TRANSFER с factAmount при eventKind=PLAN учтён как факт;
- нет чекпоинта (от нуля); нет будущих доходов (фолбэк 30 дней, flag);
- невалидный скоуп → ошибка.

**Интеграционные:** контракт `GET /pocket` (200 + структура), `GET/PUT /settings/pocket` (валидация), сценарий ANO-6 (создать факт → повторный `GET /pocket` изменился), после шага 2 — Funds и `/pocket` показывают одно число.

## 10. Вне скоупа

Мультисчёт/кредитка/поведенческие модели и ревизия транзакционной модели → ANO-9; фраза-ответ UI → ANO-13; календарь-близнец UI → ANO-14; ре-якорь → ANO-15; sandbox-сценарии → ANO-16 (движок уже готов к ним контрактом); динамический буфер → ANO-21; пересмотр прогноза → ANO-22; надёжный признак дохода для Dashboard → флаг в комментариях ANO-12.
