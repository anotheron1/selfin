# Дизайн: Планировщик стратегии накоплений

**Дата:** 2026-03-17
**Статус:** Approved
**Область:** `frontend/src/components/funds/SavingsStrategySection.tsx`

---

## 1. Контекст

В приложении уже реализована базовая структура планировщика (`SavingsStrategySection`):
- Компонент разворачивается внизу страницы Funds
- Карточки копилок со слайдерами 0–50%
- График 4 линий через Recharts
- Кредитный калькулятор (аннуитетный платёж, "потяну/не потяну")
- Бэкенд-endpoint `GET /api/v1/funds/planner` с агрегацией на 36 месяцев
- Поля `purchaseType`, `creditRate`, `creditTermMonths` в модели и DTO

**В текущей реализации отсутствуют:**
1. Жёсткий лимит суммарного % (50%) с пропорциональным перераспределением
2. Симуляция завершения копилок (freed-up cash) — сейчас % применяется бесконечно
3. Per-fund ограничение max слайдера (привязка к targetAmount)
4. Чекбокс "Учитывать подработки"

---

## 2. Функциональные требования

### 2.1 Бюджетный пирог (total cap)

- Суммарный процент по всем фондам не может превышать **50% планового дохода** (без "подработок")
- При увеличении слайдера копилки A до значения X, если `sum(all percents) > 50`:
  - Рассчитывается `excess = newTotal - 50`
  - Остальные копилки уменьшаются пропорционально своим текущим значениям:
    `newPercent(B) = max(0, currentPercent(B) - currentPercent(B) / sum(others) * excess)`
  - Если все остальные уже на 0 — слайдер A ограничивается остатком от 50%. Это обеспечивается строкой `proposed = Math.min(newValue, cap)` в утилите `rebalancePercents` (см. секцию 3.1)
- Индикатор вверху секции: **"Распределено: X% из 50% (Y ₽/мес)"**

### 2.2 Per-fund ограничение слайдера

- Для SAVINGS-фонда с `targetAmount != null`:
  `maxPercent = min(50, ceil(targetAmount / avgIncome * 100))`
  Смысл: платить больше чем targetAmount за месяц бессмысленно
- Для фондов без `targetAmount` (Pocket-like) или CREDIT — ограничение только общим лимитом 50%

### 2.3 Алгоритм симуляции (freed-up cash)

График пересчитывается каждый раз при изменении слайдеров. Алгоритм:

```
fundState = { fundId → { accumulated: 0, complete: false } }

Для каждого месяца t ∈ [0..35]:
  totalContribution = 0

  Для каждой SAVINGS-копилки f:
    если !fundState[f.id].complete:
      monthly = income[t] * fundPercents[f.id] / 100
      remaining = f.targetAmount - fundState[f.id].accumulated
      contribution = min(monthly, remaining)   // не переплачиваем
      fundState[f.id].accumulated += contribution
      totalContribution += contribution
      если accumulated >= targetAmount → fundState[f.id].complete = true
    иначе:
      contribution = 0   // копилка закрыта — деньги освобождаются

  Для каждой CREDIT-копилки f:
    если t < f.creditTermMonths (и параметры заданы):
      totalContribution += calcPMT(f.targetAmount, f.creditRate, f.creditTermMonths)

  chartPoint[t]["Расходы + копилки"] = allExpenses[t] + totalContribution
```

**Эффект**: линия "Расходы + копилки" снижается в месяц закрытия каждой копилки.

**SAVINGS-копилки без `targetAmount`**: `remaining = Infinity`, поэтому `contribution = monthly` в каждом периоде — такая копилка никогда не завершается и всегда добавляет фиксированный % к нагрузке. Freed-up cash виден только от копилок с конечной целью.

### 2.4 Маркеры завершения на графике

Для каждой SAVINGS-копилки, достигающей цели в месяц `t`:
- В данных `chartData[t]` добавляется поле `completionLabel` = `"{fund.name}"`.
- На графике через `<ReferenceLine x={label} stroke="var(--color-border)" strokeDasharray="4 4">` с `<Label>` отображается пунктирная вертикальная линия и имя копилки.

### 2.5 Чекбокс "Учитывать подработки"

- Чекбокс с подписью "Учитывать подработки" в summary-баре
- **Когда включён:**
  - Общий лимит поднимается с 50% до 100% (не применяется автоматическое перераспределение выше 50%)
  - На график добавляется пятая линия **"Доход + подработки"** = `income[t] * 1.5` (пунктир, серый/нейтральный)
  - Зона между "Доход" и "Доход + подработки" подсвечивается: это "зона подработок"
- **Когда выключён:**
  - Пятая линия скрывается
  - Лимит возвращается к 50%; если текущее суммарное значение > 50%, слайдеры обрезаются глобально пропорционально:
    ```ts
    // При setAllowOvertime(false):
    const total = Object.values(fundPercents).reduce((s, v) => s + v, 0);
    if (total > 50) {
      setFundPercents(prev => {
        const next: Record<string, number> = {};
        for (const [id, v] of Object.entries(prev)) {
          next[id] = v * 50 / total;
        }
        return next;
      });
    }
    ```
    Все слайдеры масштабируются одновременно (order-independent), сохраняя пропорции.

---

## 3. Изменения в коде

### 3.1 Только frontend — `SavingsStrategySection.tsx`

Все изменения — чисто фронтенд, бэкенд не трогаем.

#### Определения вычисляемых переменных

```ts
// avgIncome — среднее plannedIncome по первым 3 месяцам из plannerData
// (идентично существующей функции avgFirstMonths(plannerData.months, 3))
const avgIncome = plannerData ? avgFirstMonths(plannerData.months, 3) : 0;

// totalPercent — сумма всех слайдеров
const totalPercent = Object.values(fundPercents).reduce((s, v) => s + v, 0);

// totalMonthly — сколько рублей в месяц уходит в копилки
const totalMonthly = Math.round(avgIncome * totalPercent / 100);

// cap — текущий лимит (зависит от чекбокса)
const cap = allowOvertime ? 100 : 50;
```

`avgFirstMonths` уже определена в компоненте. Выбор "первых 3 месяцев" (а не 36) обоснован тем, что дальние плановые события ненадёжны.

#### Новые state-переменные

```ts
const [allowOvertime, setAllowOvertime] = useState(false);
```

`fundPercents` остаётся прежним (`Record<string, number>`).

#### Утилита пропорционального перераспределения

```ts
function rebalancePercents(
  fundId: string,
  newValue: number,
  current: Record<string, number>,
  cap: number        // 50 или 100 в зависимости от allowOvertime
): Record<string, number> {
  const others = Object.entries(current).filter(([id]) => id !== fundId);
  const otherSum = others.reduce((s, [, v]) => s + v, 0);
  const proposed = Math.min(newValue, cap);
  const available = cap - proposed;

  if (otherSum <= available) {
    return { ...current, [fundId]: proposed };
  }

  const excess = otherSum - available;
  const next: Record<string, number> = { ...current, [fundId]: proposed };
  for (const [id, v] of others) {
    next[id] = Math.max(0, v - (v / otherSum) * excess);
  }
  return next;
}
```

Вызов из `onChange` слайдера:
```ts
onChange={e => setFundPercents(prev =>
  rebalancePercents(fund.id, Number(e.target.value), prev, allowOvertime ? 100 : 50)
)}
```

#### Утилита расчёта chartData (симуляция)

Заменяет текущий `plannerData.months.map(...)`:

```ts
function buildChartData(
  months: FundPlannerMonth[],
  funds: TargetFund[],
  fundPercents: Record<string, number>,
  allowOvertime: boolean
): ChartPoint[] {
  const fundState: Record<string, { accumulated: number; complete: boolean }> = {};
  for (const f of funds) {
    if (f.purchaseType !== 'CREDIT') {
      fundState[f.id] = { accumulated: 0, complete: false };
    }
  }

  return months.map((m, idx) => {
    let totalContribution = 0;

    for (const f of funds) {
      if (f.purchaseType === 'CREDIT') {
        if (f.creditRate != null && f.creditTermMonths != null && f.targetAmount != null) {
          // idx is passed from the outer map callback (see below)
          // using map index parameter — not indexOf — to avoid O(n²)
          if (idx < f.creditTermMonths) {
            totalContribution += calcPMT(f.targetAmount, f.creditRate, f.creditTermMonths);
          }
        }
        continue;
      }

      const state = fundState[f.id];
      if (!state || state.complete) continue;

      const monthly = m.plannedIncome * (fundPercents[f.id] ?? 0) / 100;
      const remaining = f.targetAmount != null ? f.targetAmount - state.accumulated : Infinity;
      const contribution = Math.min(monthly, remaining);
      state.accumulated += contribution;
      totalContribution += contribution;

      if (f.targetAmount != null && state.accumulated >= f.targetAmount) {
        state.complete = true;
      }
    }

    const point: ChartPoint = {
      label: fmtYearMonth(m.yearMonth),
      'Доход': Math.round(m.plannedIncome),
      'Обяз. расходы': Math.round(m.mandatoryExpenses),
      'Все расходы': Math.round(m.allPlannedExpenses),
      'Расходы + копилки': Math.round(m.allPlannedExpenses + totalContribution),
    };

    if (allowOvertime) {
      point['Доход + подработки'] = Math.round(m.plannedIncome * 1.5);
    }

    return point;
  });
}
```

#### Per-fund max слайдера

```ts
const maxPercent = (fund: TargetFund, avgIncome: number, globalCap: number): number => {
  if (fund.purchaseType === 'CREDIT' || fund.targetAmount == null || avgIncome === 0) {
    return globalCap;
  }
  const fundMax = Math.ceil((fund.targetAmount / avgIncome) * 100);
  return Math.min(globalCap, fundMax);
};
```

#### Возвращаемый тип buildChartData — кортеж

`buildChartData` возвращает кортеж, чтобы не дублировать симуляцию:

```ts
type BuildResult = {
  chartData: ChartPoint[];
  completionLabels: { label: string; name: string }[];
};

function buildChartData(...): BuildResult {
  // ...fundState инициализация...
  const completionLabels: { label: string; name: string }[] = [];

  const chartData = months.map((m, idx) => {
    // ...основная симуляция...

    // Маркер завершения пушится прямо внутри SAVINGS-цикла в момент перехода:
    // if (f.targetAmount != null && state.accumulated >= f.targetAmount) {
    //   state.complete = true;
    //   completionLabels.push({ label: fmtYearMonth(m.yearMonth), name: f.name });
    // }
    // (не нужен отдельный проход — flag устанавливается единожды в нужном idx)

    return point;
  });

  return { chartData, completionLabels };
}
```

Маркер пушится непосредственно в момент `state.complete = true` внутри SAVINGS-цикла (не в отдельном проходе), что гарантирует правильный `idx` и исключает повторное срабатывание.

В JSX:
```tsx
const { chartData, completionLabels } = buildChartData(...);

{completionLabels.map(({ label, name }) => (
  <ReferenceLine key={label} x={label} stroke="var(--color-border)" strokeDasharray="4 4">
    <Label value={name} position="insideTopRight" style={{ fontSize: 10 }} />
  </ReferenceLine>
))}
```

#### Summary-бар обновление

Показывать оставшийся доступный %:

```
Распределено: {totalPercent}% из {cap}% ({fmtRub(totalMonthly)}/мес)
```

Если `totalPercent > 50` и чекбокс включён — добавить badge "подработки".

---

## 4. Что НЕ меняется

- Бэкенд: ни одного изменения
- Модель данных: ни одного изменения (V8 миграция уже в проде)
- CreditParams sub-component: без изменений
- Логика открытия/закрытия секции: без изменений
- Остальные компоненты Funds страницы: без изменений

---

## 5. Приоритет изменений

1. **Алгоритм симуляции** — критично, иначе график вводит в заблуждение
2. **Пропорциональное перераспределение + лимит 50%** — защита от нереальных сценариев
3. **Per-fund max слайдера** — UX-улучшение
4. **Маркеры завершения** — визуальное обогащение, non-critical
5. **Чекбокс "Подработки"** — опциональная фича

---

## 6. Граничные случаи

| Ситуация | Поведение |
|---|---|
| fund.targetAmount = null | Слайдер без per-fund ограничения; копилка никогда не "завершается" на графике |
| Все фонды на 0% | Линия "Расходы + копилки" совпадает с "Все расходы" |
| CREDIT-фонд без параметров | Не добавляет нагрузку на график; индикатор "Укажите параметры" |
| avgIncome = 0 | Слайдеры заблокированы/показывают 0 ₽/мес, расчёты не падают |
| Чекбокс выкл → вкл → выкл | При выключении: слайдеры обрезаются до 50% пропорционально |
