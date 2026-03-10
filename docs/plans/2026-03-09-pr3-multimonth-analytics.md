# PR 3: Multi-month Analytics

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add a table view that shows income, expenses, and fund transfers by category across multiple months.

**Architecture:** New endpoint `GET /api/v1/analytics/multi-month` aggregates existing events by month and category. Returns a flat list of rows (totals + per-category) with planned/actual amounts per month. New `Analytics.tsx` page renders this as a sticky-column table. No new DB tables needed.

**Tech Stack:** Spring Boot, Java streams/grouping, React/TypeScript/Shadcn, Tailwind

---

## Task 1: Backend — MultiMonthReportDto

**Files:**
- Create: `backend/src/main/java/ru/selfin/backend/dto/MultiMonthReportDto.java`

**Step 1: Write the DTO**

```java
package ru.selfin.backend.dto;

import ru.selfin.backend.model.enums.CategoryType;

import java.math.BigDecimal;
import java.util.List;

public record MultiMonthReportDto(
        List<String> months,   // ["2026-01", "2026-02", ...]
        List<RowDto> rows
) {

    public record RowDto(
            RowType type,
            String label,
            CategoryType categoryType,   // null for total/balance rows
            List<MonthValueDto> values
    ) {}

    public record MonthValueDto(
            String month,
            BigDecimal planned,
            BigDecimal actual            // null = month in the future (no facts yet)
    ) {}

    public enum RowType {
        TOTAL_INCOME,
        TOTAL_EXPENSE,
        TOTAL_FUND_TRANSFER,
        CATEGORY,
        BALANCE
    }
}
```

**Step 2: Build**

```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw compile -q
```

Expected: `BUILD SUCCESS`

**Step 3: Commit**

```bash
git add backend/src/main/java/ru/selfin/backend/dto/MultiMonthReportDto.java
git commit -m "feat(backend): add MultiMonthReportDto"
```

---

## Task 2: Backend — AnalyticsService.getMultiMonthReport()

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/service/AnalyticsService.java`

**Step 1: Add the method**

```java
import ru.selfin.backend.dto.MultiMonthReportDto;
import ru.selfin.backend.dto.MultiMonthReportDto.*;
import ru.selfin.backend.model.enums.CategoryType;
import ru.selfin.backend.model.enums.EventType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

// Inside AnalyticsService (already has eventRepository injected):

public MultiMonthReportDto getMultiMonthReport(LocalDate startDate, LocalDate endDate) {
    List<FinancialEvent> events = eventRepository
            .findAllByDeletedFalseAndDateBetween(startDate, endDate);

    // Build sorted month list
    List<YearMonth> months = new ArrayList<>();
    YearMonth current = YearMonth.from(startDate);
    YearMonth last = YearMonth.from(endDate);
    while (!current.isAfter(last)) {
        months.add(current);
        current = current.plusMonths(1);
    }
    List<String> monthLabels = months.stream()
            .map(ym -> ym.format(DateTimeFormatter.ofPattern("yyyy-MM")))
            .toList();

    // Group events by (yearMonth, categoryId)
    // key: "YYYY-MM|categoryId"
    record EventKey(YearMonth month, UUID categoryId) {}

    Map<EventKey, List<FinancialEvent>> grouped = events.stream()
            .collect(Collectors.groupingBy(e -> new EventKey(YearMonth.from(e.getDate()), e.getCategory().getId())));

    // Collect all categories that appear in the data
    Map<UUID, String> categoryNames = events.stream()
            .collect(Collectors.toMap(e -> e.getCategory().getId(), e -> e.getCategory().getName(), (a, b) -> a));
    Map<UUID, CategoryType> categoryTypes = events.stream()
            .collect(Collectors.toMap(e -> e.getCategory().getId(), e -> e.getCategory().getType(), (a, b) -> a));
    Map<UUID, EventType> categoryEventTypes = events.stream()
            .collect(Collectors.toMap(e -> e.getCategory().getId(), e -> e.getType(), (a, b) -> a));

    // For each category, build a RowDto with monthly values
    List<RowDto> rows = new ArrayList<>();

    // Totals accumulators
    Map<String, BigDecimal> totalIncomePlanned = new HashMap<>();
    Map<String, BigDecimal> totalIncomeActual = new HashMap<>();
    Map<String, BigDecimal> totalExpensePlanned = new HashMap<>();
    Map<String, BigDecimal> totalExpenseActual = new HashMap<>();
    Map<String, BigDecimal> totalFundTransferPlanned = new HashMap<>();
    Map<String, BigDecimal> totalFundTransferActual = new HashMap<>();

    // Build per-category rows (income first, then expense, then fund_transfer)
    List<UUID> sortedCategories = categoryNames.keySet().stream()
            .sorted(Comparator.comparing(categoryNames::get))
            .toList();

    for (UUID catId : sortedCategories) {
        EventType evtType = categoryEventTypes.get(catId);
        CategoryType catType = categoryTypes.get(catId);
        List<MonthValueDto> values = new ArrayList<>();

        for (YearMonth ym : months) {
            String monthLabel = ym.format(DateTimeFormatter.ofPattern("yyyy-MM"));
            List<FinancialEvent> monthEvents = grouped.getOrDefault(new EventKey(ym, catId), List.of());

            BigDecimal planned = monthEvents.stream()
                    .map(e -> e.getPlannedAmount() != null ? e.getPlannedAmount() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal actual = monthEvents.stream()
                    .filter(e -> e.getFactAmount() != null)
                    .map(FinancialEvent::getFactAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            boolean hasAnyFact = monthEvents.stream().anyMatch(e -> e.getFactAmount() != null);

            values.add(new MonthValueDto(monthLabel, planned, hasAnyFact ? actual : null));

            // Accumulate totals
            if (evtType == EventType.INCOME) {
                totalIncomePlanned.merge(monthLabel, planned, BigDecimal::add);
                if (hasAnyFact) totalIncomeActual.merge(monthLabel, actual, BigDecimal::add);
            } else if (evtType == EventType.EXPENSE) {
                totalExpensePlanned.merge(monthLabel, planned, BigDecimal::add);
                if (hasAnyFact) totalExpenseActual.merge(monthLabel, actual, BigDecimal::add);
            } else if (evtType == EventType.FUND_TRANSFER) {
                totalFundTransferPlanned.merge(monthLabel, planned, BigDecimal::add);
                if (hasAnyFact) totalFundTransferActual.merge(monthLabel, actual, BigDecimal::add);
            }
        }
        rows.add(new RowDto(RowType.CATEGORY, categoryNames.get(catId), catType, values));
    }

    // Build total rows
    rows.add(0, buildTotalRow(RowType.TOTAL_INCOME, "Доходы", null, monthLabels, totalIncomePlanned, totalIncomeActual));
    // Insert income categories before TOTAL_EXPENSE... (alternatively, sort after building)

    // Simpler: just put totals at specific positions at the end
    List<RowDto> result = new ArrayList<>();
    result.add(buildTotalRow(RowType.TOTAL_INCOME, "Доходы", null, monthLabels, totalIncomePlanned, totalIncomeActual));
    rows.stream().filter(r -> r.type() == RowType.CATEGORY && r.categoryType() == CategoryType.INCOME)
            .forEach(result::add);
    result.add(buildTotalRow(RowType.TOTAL_EXPENSE, "Расходы", null, monthLabels, totalExpensePlanned, totalExpenseActual));
    rows.stream().filter(r -> r.type() == RowType.CATEGORY && r.categoryType() == CategoryType.EXPENSE)
            .forEach(result::add);
    result.add(buildTotalRow(RowType.TOTAL_FUND_TRANSFER, "Переводы в копилки", null, monthLabels, totalFundTransferPlanned, totalFundTransferActual));

    // Balance row
    List<MonthValueDto> balanceValues = monthLabels.stream().map(m -> {
        BigDecimal plannedBalance = totalIncomePlanned.getOrDefault(m, BigDecimal.ZERO)
                .subtract(totalExpensePlanned.getOrDefault(m, BigDecimal.ZERO))
                .subtract(totalFundTransferPlanned.getOrDefault(m, BigDecimal.ZERO));
        BigDecimal actualBalance = totalIncomeActual.getOrDefault(m, BigDecimal.ZERO)
                .subtract(totalExpenseActual.getOrDefault(m, BigDecimal.ZERO))
                .subtract(totalFundTransferActual.getOrDefault(m, BigDecimal.ZERO));
        boolean hasActual = totalIncomeActual.containsKey(m) || totalExpenseActual.containsKey(m);
        return new MonthValueDto(m, plannedBalance, hasActual ? actualBalance : null);
    }).toList();
    result.add(new RowDto(RowType.BALANCE, "Баланс", null, balanceValues));

    return new MultiMonthReportDto(monthLabels, result);
}

private RowDto buildTotalRow(RowType type, String label, CategoryType catType,
                              List<String> months,
                              Map<String, BigDecimal> planned, Map<String, BigDecimal> actual) {
    List<MonthValueDto> values = months.stream().map(m ->
            new MonthValueDto(m,
                    planned.getOrDefault(m, BigDecimal.ZERO),
                    actual.containsKey(m) ? actual.get(m) : null)
    ).toList();
    return new RowDto(type, label, catType, values);
}
```

**Step 2: Build**

```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw compile -q
```

Expected: `BUILD SUCCESS`

**Step 3: Commit**

```bash
git add backend/src/main/java/ru/selfin/backend/service/AnalyticsService.java
git commit -m "feat(backend): add getMultiMonthReport to AnalyticsService"
```

---

## Task 3: Backend — AnalyticsController endpoint

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/controller/AnalyticsController.java`

**Step 1: Read the current AnalyticsController** (it has the dashboard endpoint)

**Step 2: Add the new endpoint**

```java
import ru.selfin.backend.dto.MultiMonthReportDto;
import org.springframework.format.annotation.DateTimeFormat;

// Add inside the controller class:

@GetMapping("/multi-month")
public MultiMonthReportDto getMultiMonth(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
    return analyticsService.getMultiMonthReport(startDate, endDate);
}
```

**Step 3: Smoke test**

```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw spring-boot:run &
sleep 10

curl -s "http://localhost:8080/api/v1/analytics/multi-month?startDate=2026-01-01&endDate=2026-03-31" | jq '.months, (.rows | length)'
```

Expected: `["2026-01","2026-02","2026-03"]` and a row count > 0 (at least the BALANCE row).

**Step 4: Commit**

```bash
git add backend/src/main/java/ru/selfin/backend/controller/AnalyticsController.java
git commit -m "feat(backend): add GET /api/v1/analytics/multi-month endpoint"
```

---

## Task 4: Frontend — types + API client

**Files:**
- Modify: `frontend/src/types/api.ts`
- Modify: `frontend/src/api/index.ts`

**Step 1: Add types to api.ts**

```typescript
export type MultiMonthRowType =
    | 'TOTAL_INCOME'
    | 'TOTAL_EXPENSE'
    | 'TOTAL_FUND_TRANSFER'
    | 'CATEGORY'
    | 'BALANCE';

export interface MonthValue {
    month: string;
    planned: number;
    actual: number | null;
}

export interface MultiMonthRow {
    type: MultiMonthRowType;
    label: string;
    categoryType: 'INCOME' | 'EXPENSE' | null;
    values: MonthValue[];
}

export interface MultiMonthReport {
    months: string[];
    rows: MultiMonthRow[];
}
```

**Step 2: Add fetchMultiMonthReport to api/index.ts**

```typescript
export async function fetchMultiMonthReport(
    startDate: string,
    endDate: string
): Promise<MultiMonthReport> {
    const res = await fetch(`${BASE_URL}/analytics/multi-month?startDate=${startDate}&endDate=${endDate}`);
    if (!res.ok) throw new Error('Failed to fetch multi-month report');
    return res.json();
}
```

**Step 3: Commit**

```bash
git add frontend/src/types/api.ts frontend/src/api/index.ts
git commit -m "feat(frontend): add MultiMonthReport types and fetchMultiMonthReport API client"
```

---

## Task 5: Frontend — Analytics.tsx page

**Files:**
- Create: `frontend/src/pages/Analytics.tsx`

**Step 1: Create the page**

```tsx
import { useEffect, useState } from 'react';
import { fetchMultiMonthReport } from '../api';
import type { MultiMonthReport, MultiMonthRow } from '../types/api';
import { ScrollArea } from '../components/ui/scroll-area';
import { Badge } from '../components/ui/badge';
import { Button } from '../components/ui/button';

const fmt = (n: number | null) =>
    n != null
        ? new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 0 }).format(n) + ' ₽'
        : '—';

function getDateRange(preset: '3m' | '6m' | '12m'): { startDate: string; endDate: string } {
    const today = new Date();
    const end = new Date(today.getFullYear(), today.getMonth(), 1); // start of current month
    const months = preset === '3m' ? 3 : preset === '6m' ? 6 : 12;
    const start = new Date(end);
    start.setMonth(start.getMonth() - months + 1);
    return {
        startDate: start.toISOString().slice(0, 7) + '-01',
        endDate: new Date(end.getFullYear(), end.getMonth() + 1, 0).toISOString().slice(0, 10),
    };
}

const ROW_STYLE: Record<string, string> = {
    TOTAL_INCOME: 'font-semibold bg-card',
    TOTAL_EXPENSE: 'font-semibold bg-card',
    TOTAL_FUND_TRANSFER: 'font-semibold bg-card',
    BALANCE: 'font-bold bg-secondary',
    CATEGORY: '',
};

export default function Analytics() {
    const [preset, setPreset] = useState<'3m' | '6m' | '12m'>('3m');
    const [report, setReport] = useState<MultiMonthReport | null>(null);
    const [loading, setLoading] = useState(true);

    useEffect(() => {
        const { startDate, endDate } = getDateRange(preset);
        setLoading(true);
        fetchMultiMonthReport(startDate, endDate)
            .then(setReport)
            .finally(() => setLoading(false));
    }, [preset]);

    return (
        <div className="flex flex-col h-[calc(100dvh-var(--nav-height))]">
            {/* Period selector */}
            <div className="flex gap-2 px-4 pt-4 pb-2 shrink-0">
                {(['3m', '6m', '12m'] as const).map(p => (
                    <Button
                        key={p}
                        size="sm"
                        variant={preset === p ? 'default' : 'secondary'}
                        onClick={() => setPreset(p)}
                    >
                        {p === '3m' ? '3 мес' : p === '6m' ? '6 мес' : '12 мес'}
                    </Button>
                ))}
            </div>

            {loading && (
                <p className="text-center py-10 text-sm text-muted-foreground animate-pulse">Загрузка...</p>
            )}

            {!loading && report && (
                <ScrollArea className="flex-1">
                    {/* Horizontal scroll wrapper */}
                    <div className="overflow-x-auto">
                        <table className="w-full text-sm border-collapse min-w-max">
                            <thead>
                                <tr className="border-b border-border">
                                    {/* Sticky label column */}
                                    <th className="sticky left-0 z-10 bg-background text-left px-4 py-2 font-medium text-muted-foreground min-w-[160px]">
                                        Статья
                                    </th>
                                    {report.months.map(m => (
                                        <th key={m} className="text-right px-3 py-2 font-medium text-muted-foreground min-w-[100px]">
                                            {new Date(m + '-01').toLocaleDateString('ru-RU', { month: 'short', year: '2-digit' })}
                                        </th>
                                    ))}
                                </tr>
                            </thead>
                            <tbody>
                                {report.rows.map((row, i) => (
                                    <AnalyticsRow key={i} row={row} months={report.months} />
                                ))}
                            </tbody>
                        </table>
                    </div>
                </ScrollArea>
            )}
        </div>
    );
}

function AnalyticsRow({ row, months }: { row: MultiMonthRow; months: string[] }) {
    const isTotal = row.type !== 'CATEGORY';
    const isBalance = row.type === 'BALANCE';
    const isIncome = row.type === 'TOTAL_INCOME' || row.categoryType === 'INCOME';

    // Build a map from month to value for quick lookup
    const valueMap = new Map(row.values.map(v => [v.month, v]));

    return (
        <tr className={`border-b border-border/50 ${isTotal ? 'bg-card' : 'hover:bg-card/50'}`}>
            {/* Sticky label */}
            <td className={`sticky left-0 z-10 px-4 py-2 ${isTotal ? 'bg-card font-semibold' : 'bg-background'} ${isBalance ? 'bg-secondary font-bold' : ''}`}>
                {!isTotal && <span className="text-muted-foreground mr-1">└</span>}
                {row.label}
            </td>
            {months.map(m => {
                const v = valueMap.get(m);
                if (!v) return <td key={m} className="text-right px-3 py-2 text-muted-foreground">—</td>;

                const isNegativeBalance = isBalance && v.actual != null && v.actual < 0;
                const actualColor = isBalance
                    ? (v.actual != null && v.actual >= 0 ? 'text-green-500' : 'text-destructive')
                    : isIncome ? 'text-green-500' : 'text-foreground';

                return (
                    <td key={m} className={`text-right px-3 py-2 ${isNegativeBalance ? 'bg-destructive/10' : ''}`}>
                        {v.actual != null ? (
                            <div>
                                <div className={`font-medium ${actualColor}`}>{fmt(v.actual)}</div>
                                <div className="text-xs text-muted-foreground">{fmt(v.planned)}</div>
                            </div>
                        ) : (
                            <div className="text-muted-foreground">{fmt(v.planned)}</div>
                        )}
                    </td>
                );
            })}
        </tr>
    );
}
```

**Step 2: Build check**

```bash
npm run build 2>&1 | tail -10
```

Expected: no TypeScript errors.

**Step 3: Commit**

```bash
git add frontend/src/pages/Analytics.tsx
git commit -m "feat(frontend): add Analytics page with multi-month table"
```

---

## Task 6: Register Analytics route + add to BottomNav

**Files:**
- Modify: `frontend/src/App.tsx` (or wherever routes are defined)
- Modify: `frontend/src/components/BottomNav.tsx`

**Step 1: Find and update the router**

Look for where `<Route>` components are defined (likely `App.tsx` or `main.tsx`). Add:

```tsx
import Analytics from './pages/Analytics';

// Inside the router:
<Route path="/analytics" element={<Analytics />} />
```

**Step 2: Add Analytics to BottomNav**

The current nav has 4 items: Дашборд, Бюджет, Фонды, Настройки.

Replace "Настройки" position with "Аналитика" and move Settings to a 5th slot, OR add a 5th item and accept a slightly tighter layout.

Recommended: replace the current `navItems` array with 5 items. On mobile, 5 items fit well at ~64px height with icon + label:

```tsx
import { LayoutDashboard, CalendarDays, PiggyBank, BarChart2, Settings } from 'lucide-react';

const navItems = [
    { to: '/', icon: LayoutDashboard, label: 'Дашборд' },
    { to: '/budget', icon: CalendarDays, label: 'Бюджет' },
    { to: '/funds', icon: PiggyBank, label: 'Фонды' },
    { to: '/analytics', icon: BarChart2, label: 'Аналитика' },
    { to: '/settings', icon: Settings, label: 'Настройки' },
];
```

**Step 3: Test navigation**

Open app → tap "Аналитика" → Analytics page loads with preset period selector and table.

Verify:
- Table shows months as columns
- Income rows in green
- Negative balance cells have red background
- Horizontal scroll works on narrow screen (using ScrollArea)

**Step 4: Final commit**

```bash
git add frontend/src/
git commit -m "feat(frontend): register Analytics route and add to BottomNav"
```

---

## Verification checklist

- [ ] Navigate to Analytics → select "3 мес"
- [ ] Table shows 3 month columns
- [ ] "Доходы" total matches Budget page income total for same month
- [ ] "Расходы" total matches Budget page expense total
- [ ] A month with more expenses than income → Balance cell has red background
- [ ] Future months (no facts) show only planned amounts in muted color
- [ ] Horizontal scroll works correctly (no native Windows scrollbar visible)
- [ ] BottomNav shows 5 items without layout issues
