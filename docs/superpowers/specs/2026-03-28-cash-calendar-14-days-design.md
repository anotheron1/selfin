# Кассовый календарь: 14 дней вперед

## Проблема

Кассовый календарь на Dashboard ограничен последним днем текущего месяца. В конце месяца (например, 28 марта) видно всего 2-3 дня вперед, что бесполезно для планирования.

## Решение

Показывать всегда 14 дней вперед от сегодня, вне зависимости от границ месяца. Горизонтальный скролл остается.

## Изменения

### Backend: `AnalyticsService.getReport()`

Файл: `backend/src/main/java/ru/selfin/backend/service/AnalyticsService.java`

1. Ввести константу `CASH_FLOW_HORIZON_DAYS = 14`.
2. Вычислить `calendarEnd = max(monthEnd, today + 14)`.
3. Передать `calendarEnd` вместо `monthEnd` в:
   - выборку событий: `findAllByDeletedFalseAndDateBetween(monthStart, calendarEnd)` — чтобы события следующего месяца попали в расчет баланса.
   - цикл `buildCashFlow` — чтобы ячейки генерировались до `calendarEnd`.
4. Остальные секции отчета (`buildPlanFact`, `buildMandatoryBurnRate`, `buildIncomeGap`) продолжают использовать `monthEnd` — они привязаны к текущему месяцу.

### Frontend

Без изменений. `CashFlowSection` уже фильтрует `d.isFuture` и рендерит все будущие дни из ответа.

## Что НЕ меняется

- Алерт кассового разрыва (DashboardService) — у него свой горизонт (FORECAST_HORIZON_DAYS = 70).
- Логика `effectiveAmount` (fact vs plan) — без изменений.
- Фильтрация "показывать день только если есть события или это сегодня" — без изменений.
- Фронтенд-компоненты — без изменений.
