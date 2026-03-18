# Planner Chart Improvements — Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Три улучшения планировщика копилок: первый месяц на графике обрезается с сегодня, строка метрик показывает avg+min по всему горизонту, добавлен тултип.

**Architecture:** Backend-изменение в `FundPlannerService` разделяет события на `allMonthEvents` (все в месяце, для factExpenses) и `monthEvents` (только с сегодня для i==0, для плановых агрегатов). Вычисление avg/min выносится в чистую функцию `calcPlannerStats` в `savingsStrategyUtils.ts` для тестируемости. Тултип добавляется локальным состоянием в `SavingsStrategySection`.

**Tech Stack:** Java 21 / Spring Boot / JUnit 5 / Mockito; TypeScript / React / Vitest

---

## Chunk 1: Backend — filter first month to today

### Task 1: FundPlannerService — split allMonthEvents / monthEvents

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/service/FundPlannerService.java`
- Create: `backend/src/test/java/ru/selfin/backend/service/FundPlannerServiceTest.java`

- [ ] **Step 1: Write failing test**

Создай файл `backend/src/test/java/ru/selfin/backend/service/FundPlannerServiceTest.java`:

```java
package ru.selfin.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.enums.EventStatus;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.Priority;
import ru.selfin.backend.repository.FinancialEventRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FundPlannerServiceTest {

    private FinancialEventRepository eventRepository;
    private FundPlannerService service;

    @BeforeEach
    void setUp() {
        eventRepository = mock(FinancialEventRepository.class);
        service = new FundPlannerService(eventRepository);
    }

    private FinancialEvent makeEvent(LocalDate date, EventType type, EventStatus status,
                                     Priority priority, BigDecimal planned, BigDecimal fact) {
        FinancialEvent e = new FinancialEvent();
        e.setId(UUID.randomUUID());
        e.setDate(date);
        e.setType(type);
        e.setStatus(status);
        e.setPriority(priority);
        e.setPlannedAmount(planned);
        e.setFactAmount(fact);
        e.setDeleted(false);
        return e;
    }

    @Test
    @DisplayName("first month plannedIncome excludes past events")
    void firstMonthExcludesPastPlannedIncome() {
        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);
        LocalDate tomorrow = today.plusDays(1);

        // past INCOME (should be excluded from plannedIncome month[0])
        FinancialEvent past = makeEvent(yesterday, EventType.INCOME, EventStatus.PLANNED,
                Priority.MEDIUM, new BigDecimal("10000"), null);
        // future INCOME (should be included)
        FinancialEvent future = makeEvent(tomorrow, EventType.INCOME, EventStatus.PLANNED,
                Priority.MEDIUM, new BigDecimal("5000"), null);

        when(eventRepository.findAllByDeletedFalseAndStatusNot(EventStatus.CANCELLED))
                .thenReturn(List.of(past, future));

        var result = service.getPlanner();
        var month0 = result.months().get(0);

        assertThat(month0.plannedIncome()).isEqualByComparingTo(new BigDecimal("5000"));
    }

    @Test
    @DisplayName("first month factExpenses includes all executed expenses (past + future)")
    void firstMonthFactExpensesIncludesPastExecuted() {
        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);
        LocalDate tomorrow = today.plusDays(1);

        // past EXECUTED EXPENSE — should be included in factExpenses
        FinancialEvent pastExecuted = makeEvent(yesterday, EventType.EXPENSE, EventStatus.EXECUTED,
                Priority.MEDIUM, new BigDecimal("3000"), new BigDecimal("3000"));
        // future PLANNED EXPENSE — should NOT be in factExpenses (not executed)
        FinancialEvent futurePlanned = makeEvent(tomorrow, EventType.EXPENSE, EventStatus.PLANNED,
                Priority.MEDIUM, new BigDecimal("2000"), null);

        when(eventRepository.findAllByDeletedFalseAndStatusNot(EventStatus.CANCELLED))
                .thenReturn(List.of(pastExecuted, futurePlanned));

        var result = service.getPlanner();
        var month0 = result.months().get(0);

        assertThat(month0.factExpenses()).isEqualByComparingTo(new BigDecimal("3000"));
        // futurePlanned has no factAmount so allPlannedExpenses for month0 counts only future events
        assertThat(month0.allPlannedExpenses()).isEqualByComparingTo(new BigDecimal("2000"));
    }

    @Test
    @DisplayName("second month is not filtered — includes all events in that month")
    void secondMonthNotFiltered() {
        LocalDate firstDayNextMonth = LocalDate.now().plusMonths(1).withDayOfMonth(1);

        FinancialEvent e = makeEvent(firstDayNextMonth, EventType.INCOME, EventStatus.PLANNED,
                Priority.MEDIUM, new BigDecimal("8000"), null);

        when(eventRepository.findAllByDeletedFalseAndStatusNot(EventStatus.CANCELLED))
                .thenReturn(List.of(e));

        var result = service.getPlanner();
        var month1 = result.months().get(1);

        assertThat(month1.plannedIncome()).isEqualByComparingTo(new BigDecimal("8000"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend && JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -Dtest="FundPlannerServiceTest" --no-transfer-progress -q
```

Expected: FAIL — тест компилируется, но `firstMonthExcludesPastPlannedIncome` падает (сейчас past-события включаются).

- [ ] **Step 3: Implement the fix in FundPlannerService**

Замени тело цикла `for (int i = 0; i < 36; i++)` в `FundPlannerService.getPlanner()`:

```java
for (int i = 0; i < 36; i++) {
    YearMonth month = current.plusMonths(i);

    // All events in the month — used for factExpenses (must include past executed)
    List<FinancialEvent> allMonthEvents = events.stream()
            .filter(e -> e.getDate() != null
                    && YearMonth.from(e.getDate()).equals(month))
            .toList();

    // For the current month: only events from today onwards feed the planned aggregates.
    // For future months: use all events in the month.
    List<FinancialEvent> monthEvents = (i == 0)
            ? allMonthEvents.stream()
                    .filter(e -> !e.getDate().isBefore(LocalDate.now()))
                    .toList()
            : allMonthEvents;

    BigDecimal plannedIncome = monthEvents.stream()
            .filter(e -> e.getType() == EventType.INCOME)
            .map(e -> Objects.requireNonNullElse(e.getPlannedAmount(), BigDecimal.ZERO))
            .reduce(BigDecimal.ZERO, BigDecimal::add);

    BigDecimal mandatoryExpenses = monthEvents.stream()
            .filter(e -> e.getPriority() == Priority.HIGH && e.getType() == EventType.EXPENSE)
            .map(e -> Objects.requireNonNullElse(e.getPlannedAmount(), BigDecimal.ZERO))
            .reduce(BigDecimal.ZERO, BigDecimal::add);

    BigDecimal allPlannedExpenses = monthEvents.stream()
            .filter(e -> e.getType() == EventType.EXPENSE)
            .map(e -> Objects.requireNonNullElse(e.getPlannedAmount(), BigDecimal.ZERO))
            .reduce(BigDecimal.ZERO, BigDecimal::add);

    BigDecimal factExpenses = null;
    if (i == 0) {
        factExpenses = allMonthEvents.stream()
                .filter(e -> e.getType() == EventType.EXPENSE
                        && e.getStatus() == EventStatus.EXECUTED
                        && e.getFactAmount() != null)
                .map(FinancialEvent::getFactAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    months.add(new FundPlannerMonthDto(
            month.toString(),
            plannedIncome,
            mandatoryExpenses,
            allPlannedExpenses,
            factExpenses));
}
```

Добавь import `java.time.LocalDate` если его нет.

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd backend && JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -Dtest="FundPlannerServiceTest" --no-transfer-progress -q
```

Expected: 3 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/ru/selfin/backend/service/FundPlannerService.java \
        backend/src/test/java/ru/selfin/backend/service/FundPlannerServiceTest.java
git commit -m "feat: planner first month aggregates only future events (date >= today)"
```

---

## Chunk 2: Frontend — stats helper + tooltip

### Task 2: Extract calcPlannerStats to savingsStrategyUtils.ts

**Files:**
- Modify: `frontend/src/components/funds/savingsStrategyUtils.ts`
- Modify: `frontend/src/components/funds/__tests__/savingsStrategyUtils.test.ts`

- [ ] **Step 1: Write failing tests for calcPlannerStats**

В `savingsStrategyUtils.test.ts` добавь `calcPlannerStats` в уже существующую строку импорта из `../savingsStrategyUtils` (в самом верху файла):

```typescript
import {
    rebalancePercents,
    scalePercentsToFit,
    maxPercent,
    buildChartData,
    calcPMT,
    calcPlannerStats,   // добавить
} from '../savingsStrategyUtils';
```

Затем добавь в конец файла новый describe-блок:

```typescript
// ─── calcPlannerStats ─────────────────────────────────────────────────────────

describe('calcPlannerStats', () => {
    const makeMonth = (
        plannedIncome: number,
        mandatoryExpenses: number,
        allPlannedExpenses: number,
        yearMonth = '2026-03',
    ): FundPlannerMonth => ({
        yearMonth,
        plannedIncome,
        mandatoryExpenses,
        allPlannedExpenses,
        factExpenses: null,
    });

    it('returns zeros when no active months', () => {
        const stats = calcPlannerStats([]);
        expect(stats.avgIncome).toBe(0);
        expect(stats.avgAfterMandatory).toBe(0);
        expect(stats.avgAfterAll).toBe(0);
        expect(stats.minAfterMandatory).toBe(null);
        expect(stats.minAfterAll).toBe(null);
    });

    it('excludes months with zero income from calculations', () => {
        const months = [
            makeMonth(100000, 30000, 50000, '2026-03'),
            makeMonth(0, 0, 0, '2026-04'),           // zero — excluded
            makeMonth(80000, 20000, 40000, '2026-05'),
        ];
        const stats = calcPlannerStats(months);
        // avgIncome: (100000 + 80000) / 2 = 90000
        expect(stats.avgIncome).toBe(90000);
        // avgAfterAll: ((100000-50000) + (80000-40000)) / 2 = (50000+40000)/2 = 45000
        expect(stats.avgAfterAll).toBe(45000);
    });

    it('computes avgAfterMandatory correctly', () => {
        const months = [
            makeMonth(100000, 60000, 80000, '2026-03'),
            makeMonth(100000, 40000, 70000, '2026-04'),
        ];
        const stats = calcPlannerStats(months);
        // afterMandatory: [40000, 60000] → avg 50000
        expect(stats.avgAfterMandatory).toBe(50000);
    });

    it('finds minimum month for afterAll', () => {
        const months = [
            makeMonth(100000, 30000, 50000, '2026-03'),
            makeMonth(100000, 30000, 90000, '2026-08'), // worst
        ];
        const stats = calcPlannerStats(months);
        expect(stats.minAfterAll).not.toBeNull();
        expect(stats.minAfterAll!.value).toBe(10000);     // 100000 - 90000
        expect(stats.minAfterAll!.label).toBe('авг 26');
    });

    it('returns null min when only one active month (min equals avg)', () => {
        const months = [makeMonth(100000, 30000, 50000, '2026-03')];
        const stats = calcPlannerStats(months);
        // min === avg → return null to suppress display
        expect(stats.minAfterAll).toBeNull();
        expect(stats.minAfterMandatory).toBeNull();
    });

    it('handles negative remainder (expenses exceed income)', () => {
        const months = [
            makeMonth(50000, 60000, 70000, '2026-03'),
        ];
        const stats = calcPlannerStats(months);
        expect(stats.avgAfterMandatory).toBe(-10000);
        expect(stats.avgAfterAll).toBe(-20000);
    });
});
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd frontend && C:/nvm4w/nodejs/node.exe node_modules/.bin/vitest run src/components/funds/__tests__/savingsStrategyUtils.test.ts 2>&1 | tail -20
```

Expected: FAIL — `calcPlannerStats is not exported`.

- [ ] **Step 3: Implement calcPlannerStats in savingsStrategyUtils.ts**

Добавь в конец `savingsStrategyUtils.ts` (перед закрывающей строкой):

```typescript
// ─── Planner stats ────────────────────────────────────────────────────────────

export type MinPoint = { value: number; label: string };

export type PlannerStats = {
    /** Average planned income across active months (plannedIncome > 0) */
    avgIncome: number;
    /** Average (income − mandatory expenses) across active months */
    avgAfterMandatory: number;
    /** Average (income − all planned expenses) across active months */
    avgAfterAll: number;
    /** Worst month for (income − mandatory). null if equals avg (suppress display). */
    minAfterMandatory: MinPoint | null;
    /** Worst month for (income − all). null if equals avg (suppress display). */
    minAfterAll: MinPoint | null;
};

/**
 * Computes summary statistics for the planner summary bar.
 * Only months with plannedIncome > 0 are included (active horizon).
 * If min equals avg, minAfterX is returned as null (no parenthetical shown).
 */
export function calcPlannerStats(months: FundPlannerMonth[]): PlannerStats {
    const active = months.filter(m => m.plannedIncome > 0);

    if (active.length === 0) {
        return { avgIncome: 0, avgAfterMandatory: 0, avgAfterAll: 0, minAfterMandatory: null, minAfterAll: null };
    }

    const sum = (arr: number[]) => arr.reduce((s, v) => s + v, 0);
    const avg = (arr: number[]) => Math.round(sum(arr) / arr.length);

    const incomes = active.map(m => m.plannedIncome);
    const afterMandatory = active.map(m => m.plannedIncome - m.mandatoryExpenses);
    const afterAll = active.map(m => m.plannedIncome - m.allPlannedExpenses);

    const avgIncome = avg(incomes);
    const avgAfterMandatory = avg(afterMandatory);
    const avgAfterAll = avg(afterAll);

    const findMin = (values: number[]): MinPoint | null => {
        const minVal = Math.round(Math.min(...values));
        const avgVal = Math.round(sum(values) / values.length);
        if (minVal === avgVal) return null; // suppress display
        const idx = values.findIndex(v => Math.round(v) === minVal);
        return { value: minVal, label: fmtYearMonth(active[idx].yearMonth) };
    };

    return {
        avgIncome,
        avgAfterMandatory,
        avgAfterAll,
        minAfterMandatory: findMin(afterMandatory),
        minAfterAll: findMin(afterAll),
    };
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd frontend && C:/nvm4w/nodejs/node.exe node_modules/.bin/vitest run src/components/funds/__tests__/savingsStrategyUtils.test.ts 2>&1 | tail -20
```

Expected: все тесты PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/funds/savingsStrategyUtils.ts \
        frontend/src/components/funds/__tests__/savingsStrategyUtils.test.ts
git commit -m "feat: add calcPlannerStats helper for full-horizon avg+min metrics"
```

---

### Task 3: Update SavingsStrategySection — use calcPlannerStats + add tooltip

**Files:**
- Modify: `frontend/src/components/funds/SavingsStrategySection.tsx`

- [ ] **Step 1: Add HelpCircle import and calcPlannerStats import**

В начале файла найди строку с импортами из `lucide-react` (если есть) или из `./savingsStrategyUtils`.

Добавь `HelpCircle` в импорт из `lucide-react` (создай его если нет):

```typescript
import { HelpCircle } from 'lucide-react';
```

Добавь `calcPlannerStats` в импорт из `./savingsStrategyUtils`:

```typescript
import {
    calcPMT,
    fmtYearMonth,
    buildChartData,
    rebalancePercents,
    maxPercent,
    calcPlannerStats,
} from './savingsStrategyUtils';
```

- [ ] **Step 2: Add showPlannerHelp state and replace old avg calculations**

Внутри компонента `SavingsStrategySection`, после строки с `const [isOpen, setIsOpen] = useState(false);` добавь:

> Важно: старый код зажимал `remainingAfterAll` через `Math.max(0, ...)` — это убирается намеренно. `calcPlannerStats` возвращает реальное значение, в том числе отрицательное. `cap` ниже защищён собственным `Math.max(1, ...)`, поэтому изменение безопасно.

```typescript
const [showPlannerHelp, setShowPlannerHelp] = useState(false);
```

Замени блок вычислений (строки с `avgFirstMonths`, `avgIncome`, `avgMandatory`, `avgAll`, `remainingAfterMandatory`, `remainingAfterAll`):

**Было:**
```typescript
const avgIncome = plannerData ? avgFirstMonths(plannerData.months, 3) : 0;
const avgMandatory = plannerData
    ? Math.round(plannerData.months.slice(0, 3).reduce((s, m) => s + (m.mandatoryExpenses ?? 0), 0) / Math.min(3, plannerData.months.length))
    : 0;
const avgAll = plannerData
    ? Math.round(plannerData.months.slice(0, 3).reduce((s, m) => s + (m.allPlannedExpenses ?? 0), 0) / Math.min(3, plannerData.months.length))
    : 0;
const remainingAfterMandatory = Math.max(0, avgIncome - avgMandatory);
const remainingAfterAll = Math.max(0, avgIncome - avgAll);
```

**Стало:**
```typescript
const stats = plannerData ? calcPlannerStats(plannerData.months) : null;
const avgIncome = stats?.avgIncome ?? 0;
const remainingAfterMandatory = stats?.avgAfterMandatory ?? 0;
const remainingAfterAll = stats?.avgAfterAll ?? 0;
```

- [ ] **Step 3: Update summary bar display**

Найди блок summary bar (начинается с `<div className="flex flex-wrap gap-x-4 gap-y-1 text-sm rounded-xl...`).

Замени три `<span>` метрики:

```tsx
{/* Плановый доход */}
<span style={{ color: 'var(--color-text-muted)' }}>
    Плановый доход:{' '}
    <span className="font-medium" style={{ color: 'var(--color-text)' }}>
        ~{avgIncome.toLocaleString()} ₽/мес
    </span>
</span>
<span style={{ color: 'var(--color-text-muted)' }}>|</span>

{/* После обяз. расходов */}
<span style={{ color: 'var(--color-text-muted)' }}>
    После обяз. расходов:{' '}
    <span className="font-medium" style={{ color: 'var(--color-text)' }}>
        ~{remainingAfterMandatory.toLocaleString()} ₽/мес
        {stats?.minAfterMandatory != null && (
            <span className="font-normal" style={{ color: 'var(--color-text-muted)' }}>
                {' '}(min {stats.minAfterMandatory.value.toLocaleString()} ₽ в {stats.minAfterMandatory.label})
            </span>
        )}
    </span>
</span>
<span style={{ color: 'var(--color-text-muted)' }}>|</span>

{/* Доступно для копилок */}
<span style={{ color: 'var(--color-text-muted)' }}>
    Доступно для копилок:{' '}
    <span className="font-medium" style={{ color: 'var(--color-text)' }}>
        ~{remainingAfterAll.toLocaleString()} ₽/мес
        {stats?.minAfterAll != null && (
            <span className="font-normal" style={{ color: 'var(--color-text-muted)' }}>
                {' '}(min {stats.minAfterAll.value.toLocaleString()} ₽ в {stats.minAfterAll.label})
            </span>
        )}
    </span>
</span>
```

- [ ] **Step 4: Add tooltip toggle button to section header**

Найди строку с `<span className="font-semibold">Планировщик копилок</span>`.

Замени на:

```tsx
<span className="flex items-center gap-1.5">
    <span className="font-semibold">Планировщик копилок</span>
    <button
        onClick={e => { e.stopPropagation(); setShowPlannerHelp(h => !h); }}
        className="transition-colors"
        style={{ color: 'var(--color-text-muted)' }}
        aria-label="Что такое планировщик копилок">
        <HelpCircle size={14} />
    </button>
</span>
```

- [ ] **Step 5: Add tooltip content block (inside isOpen section)**

Сразу после `{isOpen && (` и открывающего `<div className="px-5 pb-5 space-y-4">`, добавь блок тултипа перед summary bar:

```tsx
{showPlannerHelp && (
    <div
        className="text-xs leading-relaxed rounded-xl px-3 py-2 space-y-2"
        style={{ background: 'var(--color-bg)', border: '1px solid var(--color-border)', color: 'var(--color-text-muted)' }}>
        <div>
            <b style={{ color: 'var(--color-text)' }}>Плановый доход</b> — среднее плановых поступлений
            по месяцам в горизонте планирования (только месяцы с ненулевым доходом).
        </div>
        <div>
            <b style={{ color: 'var(--color-text)' }}>После обяз. расходов</b> — сколько остаётся
            в типичный месяц, если вычесть только HIGH-priority расходы (ипотека, коммуналка и т.д.).
            Это теоретический максимум, который можно направить в копилки. В скобках — худший месяц за горизонт.
        </div>
        <div>
            <b style={{ color: 'var(--color-text)' }}>Доступно для копилок</b> — сколько остаётся
            после всех запланированных расходов. Именно на эту сумму ориентируются слайдеры
            распределения. В скобках — худший месяц за горизонт.
        </div>
    </div>
)}
```

- [ ] **Step 6: Remove avgFirstMonths helper (no longer used)**

Удали функцию `avgFirstMonths` из файла (она заменена `calcPlannerStats`).

- [ ] **Step 7: Verify TypeScript compiles**

```bash
cd frontend && C:/nvm4w/nodejs/node.exe node_modules/typescript/bin/tsc --noEmit 2>&1 | head -30
```

Expected: нет ошибок.

- [ ] **Step 8: Commit**

```bash
git add frontend/src/components/funds/SavingsStrategySection.tsx
git commit -m "feat: planner summary uses full-horizon avg+min, add tooltip"
```
