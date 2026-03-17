# Дизайн: Планировщик стратегии накоплений

**Дата:** 2026-03-17
**Статус:** Implemented
**Область:** `frontend/src/components/funds/`

---

## 1. Контекст

Компонент `SavingsStrategySection` разворачивается внизу страницы Funds и предоставляет:
- Карточки копилок со слайдерами 0–N% дохода
- Симуляцию накоплений на 36 месяцев с freed-up cash
- График 4–5 линий через Recharts
- Кредитный калькулятор (аннуитетный платёж, "потяну/не потяну")
- Чекбокс "Учитывать подработки"
- Маркеры завершения копилок на графике

Бэкенд-endpoint: `GET /api/v1/funds/planner` — агрегация на 36 месяцев без изменений.

---

## 2. Ключевые файлы

- `frontend/src/components/funds/savingsStrategyUtils.ts` — чистые функции (без React, без побочных эффектов)
- `frontend/src/components/funds/SavingsStrategySection.tsx` — основной компонент

---

## 3. Алгоритм симуляции (freed-up cash)

Реализован в `buildChartData(months, funds, fundPercents, allowOvertime): BuildResult`.

```
fundState = { fundId → { accumulated: 0, complete: false } }  // только SAVINGS-фонды

Для каждого месяца t ∈ [0..35]:
  totalContribution = 0

  Для каждой CREDIT-копилки f:
    если creditRate, creditTermMonths, targetAmount заданы И t < creditTermMonths:
      totalContribution += calcPMT(targetAmount, creditRate, creditTermMonths)

  Для каждой SAVINGS-копилки f:
    если fundState[f.id].complete → пропустить (деньги освобождаются)
    monthly = m.plannedIncome * fundPercents[f.id] / 100
    remaining = f.targetAmount != null ? (targetAmount − accumulated) : Infinity
    contribution = min(monthly, remaining)          // частичный взнос в финальный месяц
    state.accumulated += contribution
    totalContribution += contribution
    если accumulated >= targetAmount:
      state.complete = true
      completionLabels.push({ label: fmtYearMonth(m.yearMonth), name: f.name })

  chartPoint = {
    'Доход': plannedIncome,
    'Обяз. расходы': mandatoryExpenses,
    'Все расходы': allPlannedExpenses,
    'Расходы + копилки': allPlannedExpenses + totalContribution,
    'Доход + подработки': plannedIncome * 1.5  // только если allowOvertime=true
  }
```

**Эффект**: "Расходы + копилки" снижается в месяц закрытия каждой SAVINGS-копилки.

**Специальные случаи:**
- SAVINGS с `targetAmount = null` — `remaining = Infinity`, никогда не завершается, нет маркера.
- CREDIT без параметров — не добавляет нагрузку на график.

Возвращаемый тип:
```ts
type BuildResult = {
    chartData: ChartPoint[];
    completionLabels: { label: string; name: string }[];
};
```

---

## 4. Маркеры завершения на графике

`completionLabels` накапливается внутри `buildChartData` в момент `state.complete = true` (не в отдельном проходе — гарантирует правильный месяц и отсутствие повторных срабатываний).

В JSX:
```tsx
{completionLabels.map(({ label, name }) => (
    <ReferenceLine key={`${label}-${name}`} x={label}
        stroke="var(--color-border)" strokeDasharray="4 4">
        <Label value={name} position="insideTopRight"
            style={{ fontSize: 10, fill: 'var(--color-text-muted)' }} />
    </ReferenceLine>
))}
```

---

## 5. Суммарный лимит (50% cap) и пропорциональное перераспределение

### 5.1 Переменные в компоненте

```ts
const totalPercent = Object.values(fundPercents).reduce((s, v) => s + v, 0);
const totalMonthly = Math.round(avgIncome * totalPercent / 100);
const cap = allowOvertime ? 100 : 50;
```

`avgIncome` = среднее `plannedIncome` по первым 3 месяцам из `plannerData`.

### 5.2 `rebalancePercents` — вызывается из `onChange` слайдера

```ts
export function rebalancePercents(
    fundId: string,
    newValue: number,
    current: Record<string, number>,
    cap: number,
): Record<string, number>
```

Логика:
1. `proposed = Math.min(newValue, cap)` — жёсткий потолок для текущего фонда.
2. `available = cap - proposed`.
3. Если сумма остальных `<= available` — просто подставить `proposed`, остальные не трогать.
4. Иначе — уменьшить каждый другой фонд пропорционально его текущему значению:
   `next[id] = max(0, v - (v / otherSum) * excess)`.

### 5.3 `scalePercentsToFit` — вызывается при выключении чекбокса

```ts
export function scalePercentsToFit(
    current: Record<string, number>,
    cap: number,
): Record<string, number>
```

Если `total <= cap` — возвращает объект без изменений. Иначе масштабирует все значения коэффициентом `cap / total`, сохраняя пропорции.

---

## 6. Per-fund ограничение слайдера

```ts
export function maxPercent(
    targetAmount: number | null | undefined,
    purchaseType: PurchaseType,
    avgIncome: number,
    globalCap: number,
): number
```

- CREDIT-фонды или `targetAmount = null` или `avgIncome = 0` → возвращает `globalCap`.
- SAVINGS с `targetAmount` → `min(globalCap, ceil(targetAmount / avgIncome * 100))`.

Смысл верхней границы: нет смысла откладывать больше `targetAmount` за один месяц.
`globalCap` = `cap` из компонента (50 или 100).

---

## 7. Чекбокс "Учитывать подработки"

Находится в summary-баре рядом с индикатором распределения.

**При включении (`allowOvertime = true`):**
- `cap` повышается с 50 до 100; `rebalancePercents` получает `cap=100`.
- `maxPercent` на каждом слайдере пересчитывается с новым `cap`.
- `buildChartData` добавляет в каждую точку поле `'Доход + подработки' = plannedIncome * 1.5`.
- На графике появляется пятая линия: серый пунктир `stroke="#6b7280"`, `strokeDasharray="6 3"`, `strokeWidth=1.5`.

**При выключении (`allowOvertime = false`):**
```ts
setFundPercents(prev => scalePercentsToFit(prev, 50));
```
Все слайдеры масштабируются одновременно, пропорции сохраняются.

---

## 8. Summary-бар и предупреждение

```
Плановый доход: ~{avgIncome}/мес  |  Распределено: {totalPercent}% из {cap}% ({totalMonthly}/мес)  [Учитывать подработки]
```

Цвет значения "Распределено" — оранжевый (`#f97316`) когда `totalPercent > cap`, иначе `var(--color-text)`.

---

## 9. Граничные случаи

| Ситуация | Поведение |
|---|---|
| `fund.targetAmount = null` | Нет per-fund ограничения слайдера; копилка никогда не завершается на графике |
| Все фонды на 0% | "Расходы + копилки" совпадает с "Все расходы" |
| CREDIT-фонд без параметров | Не добавляет нагрузку на график; показывает "Укажите параметры кредита" |
| `avgIncome = 0` | `maxPercent` возвращает `globalCap`; рублёвые суммы = 0 |
| Чекбокс выкл → вкл → выкл | При выключении слайдеры масштабируются до 50% через `scalePercentsToFit` |
| Несколько копилок завершаются в один месяц | Несколько `ReferenceLine` с одинаковым `x` — `key` = `${label}-${name}` |

---

## 10. Что НЕ меняется

- Бэкенд: ни одного изменения
- Модель данных: ни одного изменения
- CreditParams sub-component: без изменений
- Логика открытия/закрытия секции: без изменений
- Остальные компоненты Funds страницы: без изменений
