# Journal UX Improvements Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Improve readability of the "Журнал" event list with visual plan/fact differentiation, income colour, priority dots, fact priority input, and better sort order.

**Architecture:** Four independent frontend changes in `Budget.tsx`, `PriorityButton.tsx`, and `FactCreateSheet.tsx`, plus a single backend extension to `FactCreateDto` and `FinancialEventService`. Backend is done first so the API is ready before the frontend form is wired up. A shared `priority.ts` module holds the dot config used by both priority components.

**Tech Stack:** Spring Boot (Java 21, records, Lombok), React 18, TypeScript, Tailwind CSS. Backend tests use JUnit 5 + Mockito (unit) and Testcontainers (integration). Run backend tests with `JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test` from the repo root.

---

## Chunk 1: All Tasks

### Task 1: Backend — add `priority` to linked fact creation

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/dto/FactCreateDto.java`
- Modify: `backend/src/main/java/ru/selfin/backend/service/FinancialEventService.java:228-244`
- Modify: `backend/src/test/java/ru/selfin/backend/service/FinancialEventServiceTest.java`
- Modify: `backend/src/test/java/ru/selfin/backend/FinancialEventControllerIT.java`

- [ ] **Step 1: Write two failing unit tests**

Add to `FinancialEventServiceTest.java` after the existing `createLinkedFact_success_createsFact` test:

```java
@Test
@DisplayName("createLinkedFact: без priority в DTO — наследует от плана")
void createLinkedFact_noPriority_inheritsPlanPriority() {
    UUID planId = UUID.randomUUID();
    Category cat = category(); // returns Priority.HIGH
    FinancialEvent plan = aPlan(planId, cat, EventStatus.PLANNED);

    when(eventRepository.findById(planId)).thenReturn(Optional.of(plan));
    when(targetFundRepository.findById(any())).thenReturn(Optional.empty());

    FinancialEvent[] saved = new FinancialEvent[1];
    when(eventRepository.save(any())).thenAnswer(inv -> {
        FinancialEvent e = inv.getArgument(0);
        if (e.getEventKind() == EventKind.FACT) saved[0] = e;
        return e;
    });
    when(eventRepository.findFactAggregatesByPlanIds(any())).thenReturn(Collections.emptyList());

    service.createLinkedFact(planId,
            new FactCreateDto(LocalDate.now(), BigDecimal.TEN, null, null));

    assertThat(saved[0].getPriority()).isEqualTo(Priority.HIGH);
}

@Test
@DisplayName("createLinkedFact: priority в DTO — использует его, не наследует")
void createLinkedFact_withPriority_usesDtoPriority() {
    UUID planId = UUID.randomUUID();
    Category cat = category(); // returns Priority.HIGH
    FinancialEvent plan = aPlan(planId, cat, EventStatus.PLANNED);

    when(eventRepository.findById(planId)).thenReturn(Optional.of(plan));
    when(targetFundRepository.findById(any())).thenReturn(Optional.empty());

    FinancialEvent[] saved = new FinancialEvent[1];
    when(eventRepository.save(any())).thenAnswer(inv -> {
        FinancialEvent e = inv.getArgument(0);
        if (e.getEventKind() == EventKind.FACT) saved[0] = e;
        return e;
    });
    when(eventRepository.findFactAggregatesByPlanIds(any())).thenReturn(Collections.emptyList());

    service.createLinkedFact(planId,
            new FactCreateDto(LocalDate.now(), BigDecimal.TEN, null, Priority.LOW));

    assertThat(saved[0].getPriority()).isEqualTo(Priority.LOW);
}
```

Also ensure this import is present at the top of `FinancialEventServiceTest.java`:
```java
import ru.selfin.backend.model.enums.Priority;
```

- [ ] **Step 2: Run the new tests to confirm they fail**

```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -pl backend \
  -Dtest="FinancialEventServiceTest#createLinkedFact_noPriority*+createLinkedFact_withPriority*" \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: compilation error — `FactCreateDto` constructor doesn't accept 4 args yet.

- [ ] **Step 3: Add `priority` field to `FactCreateDto`**

Replace the entire contents of `backend/src/main/java/ru/selfin/backend/dto/FactCreateDto.java`:

```java
package ru.selfin.backend.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import ru.selfin.backend.model.enums.Priority;

import java.math.BigDecimal;
import java.time.LocalDate;

public record FactCreateDto(
        @NotNull LocalDate date,
        @NotNull @Positive BigDecimal factAmount,
        String description,
        Priority priority) {
}
```

- [ ] **Step 4: Fix `FinancialEventService.createLinkedFact` — inherit priority from plan**

In `FinancialEventService.java`, in the `createLinkedFact` method, find the builder line:

```java
                .priority(Priority.MEDIUM)
```

Replace it with:

```java
                .priority(dto.priority() != null ? dto.priority() : plan.getPriority())
```

- [ ] **Step 5: Fix existing 3-arg `FactCreateDto` constructor calls in test files**

Search for `new FactCreateDto(` in both test files and add `null` as the fourth argument to every occurrence. Do not use line numbers — search by string.

In `FinancialEventServiceTest.java` the calls are:
```java
// Find and replace each of these:
new FactCreateDto(LocalDate.now(), BigDecimal.TEN, null)
// → becomes:
new FactCreateDto(LocalDate.now(), BigDecimal.TEN, null, null)

new FactCreateDto(LocalDate.now(), BigDecimal.TEN, "оплатил")
// → becomes:
new FactCreateDto(LocalDate.now(), BigDecimal.TEN, "оплатил", null)
```

In `FinancialEventControllerIT.java` the calls are:
```java
// Find and replace:
new FactCreateDto(LocalDate.now(), BigDecimal.valueOf(4850), "Фактический расход")
// → becomes:
new FactCreateDto(LocalDate.now(), BigDecimal.valueOf(4850), "Фактический расход", null)

new FactCreateDto(LocalDate.now(), BigDecimal.valueOf(2900), null)
// → becomes:
new FactCreateDto(LocalDate.now(), BigDecimal.valueOf(2900), null, null)
```

- [ ] **Step 6: Add an IT test that verifies the priority is persisted**

First verify the `FinancialEventCreateDto` record signature by reading `backend/src/main/java/ru/selfin/backend/dto/FinancialEventCreateDto.java`. It has 8 components in this order: `date, categoryId, type, plannedAmount, priority, description, rawInput, targetFundId`.

Also add `import ru.selfin.backend.model.enums.Priority;` to the imports section of `FinancialEventControllerIT.java`.

Then add this test to `FinancialEventControllerIT.java` after `createLinkedFact_success`:

```java
@Test
void createLinkedFact_withPriority_persistsPriority() throws Exception {
    String catId = getFirstCategoryId();
    FinancialEventCreateDto planDto = new FinancialEventCreateDto(
            LocalDate.now(), UUID.fromString(catId), EventType.EXPENSE,
            BigDecimal.valueOf(5000), null, "Тест приоритета", null, null);

    String planBody = mockMvc.perform(post("/api/v1/events")
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(planDto)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

    String planId = objectMapper.readTree(planBody).get("id").asText();

    FactCreateDto factDto = new FactCreateDto(LocalDate.now(), BigDecimal.valueOf(5000), null,
            Priority.LOW);

    mockMvc.perform(post("/api/v1/events/" + planId + "/facts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(factDto)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.priority").value("LOW"));
}
```

- [ ] **Step 7: Run all backend tests**

```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -pl backend
```

Expected: all tests pass (green). IT tests require Docker to be running for Testcontainers.

- [ ] **Step 8: Commit backend changes**

```bash
git add backend/src/main/java/ru/selfin/backend/dto/FactCreateDto.java \
        backend/src/main/java/ru/selfin/backend/service/FinancialEventService.java \
        backend/src/test/java/ru/selfin/backend/service/FinancialEventServiceTest.java \
        backend/src/test/java/ru/selfin/backend/FinancialEventControllerIT.java
git commit -m "feat(backend): add priority field to linked fact creation, inherit from plan"
```

---

### Task 2: Shared priority constants + TypeScript types + PriorityButton dots

**Files:**
- Create: `frontend/src/lib/priority.ts`
- Modify: `frontend/src/types/api.ts:60-64`
- Modify: `frontend/src/components/PriorityButton.tsx`

- [ ] **Step 1: Create shared priority constants module**

Create `frontend/src/lib/priority.ts`:

```typescript
import type { Priority } from '../types/api';

export const PRIORITY_DOT_CONFIG: Record<Priority, { color: string; title: string }> = {
    HIGH:   { color: '#f87171', title: 'обязательно' },
    MEDIUM: { color: '#facc15', title: 'по плану' },
    LOW:    { color: '#60a5fa', title: 'хотелка' },
};

export const PRIORITY_ORDER: Priority[] = ['HIGH', 'MEDIUM', 'LOW'];
```

- [ ] **Step 2: Add `priority` to `FactCreateDto` in TypeScript**

In `frontend/src/types/api.ts`, update the `FactCreateDto` interface from:

```typescript
export interface FactCreateDto {
    date: string;
    factAmount: number;
    description?: string;
}
```

to:

```typescript
export interface FactCreateDto {
    date: string;
    factAmount: number;
    description?: string;
    priority?: Priority;
}
```

- [ ] **Step 3: Rewrite `PriorityButton.tsx` to use colour dots**

Replace the entire file contents:

```typescript
import { PRIORITY_DOT_CONFIG } from '../lib/priority';
import type { Priority } from '../types/api';

interface PriorityButtonProps {
    priority: Priority;
    onCycle?: () => void;
    disabled?: boolean;
}

/**
 * Цветная точка приоритета. Если передан onCycle — кликабельна (циклично меняет приоритет).
 * HIGH → красная (#f87171), MEDIUM → жёлтая (#facc15), LOW → голубая (#60a5fa).
 */
export default function PriorityButton({ priority, onCycle, disabled }: PriorityButtonProps) {
    const { color, title } = PRIORITY_DOT_CONFIG[priority];
    const interactive = !!onCycle && !disabled;

    return (
        <span
            title={title}
            onClick={interactive ? (e) => { e.stopPropagation(); onCycle!(); } : undefined}
            style={{
                display: 'inline-block',
                width: 8,
                height: 8,
                borderRadius: '50%',
                backgroundColor: color,
                flexShrink: 0,
                cursor: interactive ? 'pointer' : 'default',
            }}
        />
    );
}
```

Key changes from old version:
- `onCycle` is now optional — omitting it makes the dot non-interactive (used for FACT rows)
- Three `if` branches replaced by a single `PRIORITY_DOT_CONFIG` lookup
- `<button>` replaced by `<span>` (semantically correct for a non-form display element; click is still handled when interactive)

- [ ] **Step 4: Verify TypeScript compiles with no errors**

```bash
cd frontend && npx tsc --noEmit
```

Expected: exit code 0, no output.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/lib/priority.ts \
        frontend/src/types/api.ts \
        frontend/src/components/PriorityButton.tsx
git commit -m "feat(frontend): replace priority text badges with colour dots"
```

---

### Task 3: Budget.tsx — border, income colour, fact sort, PriorityButton wiring

**Files:**
- Modify: `frontend/src/pages/Budget.tsx`

This task makes three visual changes and one wiring fix, all in `Budget.tsx`.

#### 3a — Left border on plan/fact rows

- [ ] **Step 1: Add `style` prop with border-left to the event row div**

In `Budget.tsx`, locate the event row `<div>` that starts with `key={event.id}`. It looks like this (full opening tag):

```typescript
// Before — full opening tag of the event row div:
<div key={event.id}
    data-group={groupId}
    onClick={() => setSelectedEvent(event)}
    onMouseEnter={() => pfHandlers.handleMouseEnter(groupId)}
    onMouseLeave={() => pfHandlers.handleMouseLeave(groupId)}
    className={`pr-5 py-3 flex items-center justify-between gap-3 cursor-pointer hover:bg-white/5 transition-colors${isPlan ? ' pf-is-plan' : ''}${isFact ? ' pf-is-fact' : ''}${isLowPlanned ? ' opacity-60' : ''}`}>
```

Add a `style` prop (between `className` and the closing `>`):

```typescript
// After:
<div key={event.id}
    data-group={groupId}
    onClick={() => setSelectedEvent(event)}
    onMouseEnter={() => pfHandlers.handleMouseEnter(groupId)}
    onMouseLeave={() => pfHandlers.handleMouseLeave(groupId)}
    className={`pr-5 py-3 flex items-center justify-between gap-3 cursor-pointer hover:bg-white/5 transition-colors${isPlan ? ' pf-is-plan' : ''}${isFact ? ' pf-is-fact' : ''}${isLowPlanned ? ' opacity-60' : ''}`}
    style={{ borderLeft: isPlan ? '3px solid rgba(255,255,255,0.12)' : '3px solid hsl(var(--primary))' }}>
```

#### 3b — Muted green for PLAN income amount

- [ ] **Step 2: Update the PLAN amount span colour**

In `Budget.tsx`, in the PLAN branch of the amount display, locate this span:

```typescript
<span style={{ color: 'var(--color-text-muted)', fontSize: '12px' }}>
    {isIncome ? '+' : '-'}{fmt(event.plannedAmount)}
</span>
```

Replace with:

```typescript
<span style={{
    color: isIncome ? 'rgba(74,222,128,0.5)' : 'var(--color-text-muted)',
    fontSize: '12px'
}}>
    {isIncome ? '+' : '-'}{fmt(event.plannedAmount)}
</span>
```

#### 3c — Fix fact sort order (linked before unplanned)

- [ ] **Step 3: Update `factEvents` sort**

In `Budget.tsx`, locate:

```typescript
const factEvents = dayEvts
    .filter(e => e.eventKind === 'FACT')
    .sort((a, b) => getDisplayName(a).localeCompare(getDisplayName(b), 'ru'));
```

Replace with:

```typescript
const factEvents = dayEvts
    .filter(e => e.eventKind === 'FACT')
    .sort((a, b) => {
        const aLinked = a.parentEventId !== null ? 0 : 1;
        const bLinked = b.parentEventId !== null ? 0 : 1;
        if (aLinked !== bLinked) return aLinked - bLinked;
        return getDisplayName(a).localeCompare(getDisplayName(b), 'ru');
    });
```

#### 3d — Make PriorityButton non-interactive on FACT rows

- [ ] **Step 4: Pass `onCycle` only for PLAN rows**

In `Budget.tsx`, locate the `<PriorityButton>` render:

```typescript
<PriorityButton
    priority={event.priority}
    onCycle={() => cycleEventPriority(event.id).then(() => load(true))}
/>
```

Replace with:

```typescript
<PriorityButton
    priority={event.priority}
    onCycle={isPlan ? () => cycleEventPriority(event.id).then(() => load(true)) : undefined}
/>
```

- [ ] **Step 5: Commit**

```bash
git add frontend/src/pages/Budget.tsx
git commit -m "feat(frontend): plan/fact border, income colour, fact sort order, dot interactivity"
```

---

### Task 4: FactCreateSheet — priority selector

**Files:**
- Modify: `frontend/src/components/FactCreateSheet.tsx`
- Modify: `frontend/src/pages/Budget.tsx` (pass `planPriority` prop)

#### 4a — Add priority selector to the form

- [ ] **Step 1: Rewrite `FactCreateSheet.tsx`**

```typescript
import { useState } from 'react';
import { Sheet, SheetContent, SheetHeader, SheetTitle } from './ui/sheet';
import { Input } from './ui/input';
import { Button } from './ui/button';
import { createLinkedFact } from '../api';
import { PRIORITY_DOT_CONFIG, PRIORITY_ORDER } from '../lib/priority';
import type { FactCreateDto, Priority } from '../types/api';

interface Props {
    planId: string;
    planDescription: string;
    planPriority: Priority;
    open: boolean;
    onClose: () => void;
    onCreated: () => void;
}

export default function FactCreateSheet({ planId, planDescription, planPriority, open, onClose, onCreated }: Props) {
    const today = new Date().toISOString().slice(0, 10);
    const [date, setDate] = useState(today);
    const [amount, setAmount] = useState('');
    const [description, setDescription] = useState('');
    const [priority, setPriority] = useState<Priority>(planPriority);
    const [loading, setLoading] = useState(false);

    // Reset all fields to fresh state whenever the sheet opens (e.g. for a different plan)
    function handleOpenChange(isOpen: boolean) {
        if (!isOpen) {
            onClose();
        } else {
            setDate(new Date().toISOString().slice(0, 10));
            setAmount('');
            setDescription('');
            setPriority(planPriority);
        }
    }

    async function handleSubmit(e: React.FormEvent) {
        e.preventDefault();
        if (!amount) return;
        setLoading(true);
        try {
            const dto: FactCreateDto = {
                date,
                factAmount: parseFloat(amount),
                description: description || undefined,
                priority,
            };
            await createLinkedFact(planId, dto);
            onCreated();
            onClose();
        } catch (err) {
            console.error(err);
        } finally {
            setLoading(false);
        }
    }

    return (
        <Sheet open={open} onOpenChange={handleOpenChange}>
            <SheetContent side="bottom" className="max-w-2xl mx-auto rounded-t-2xl">
                <SheetHeader>
                    <SheetTitle>Записать факт</SheetTitle>
                    <p className="text-sm" style={{ color: 'var(--color-text-muted)' }}>{planDescription}</p>
                </SheetHeader>
                <form onSubmit={handleSubmit} className="space-y-3 mt-4">
                    <div>
                        <label className="text-xs text-muted-foreground block mb-1">Дата</label>
                        <Input type="date" value={date} onChange={e => setDate(e.target.value)} required />
                    </div>
                    <div>
                        <label className="text-xs text-muted-foreground block mb-1">Фактическая сумма, ₽</label>
                        <Input
                            type="number"
                            min="0.01"
                            step="0.01"
                            placeholder="0"
                            value={amount}
                            onChange={e => setAmount(e.target.value)}
                            required
                        />
                    </div>
                    <div>
                        <label className="text-xs text-muted-foreground block mb-1">Необходимость</label>
                        <div className="flex items-center gap-3 pt-1">
                            {PRIORITY_ORDER.map(p => (
                                <button
                                    key={p}
                                    type="button"
                                    title={PRIORITY_DOT_CONFIG[p].title}
                                    onClick={() => setPriority(p)}
                                    style={{
                                        display: 'flex',
                                        alignItems: 'center',
                                        gap: 6,
                                        opacity: priority === p ? 1 : 0.35,
                                        cursor: 'pointer',
                                        background: 'none',
                                        border: 'none',
                                        padding: 0,
                                        transition: 'opacity 0.15s',
                                    }}
                                >
                                    <span style={{
                                        display: 'inline-block',
                                        width: 10,
                                        height: 10,
                                        borderRadius: '50%',
                                        backgroundColor: PRIORITY_DOT_CONFIG[p].color,
                                    }} />
                                    <span className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
                                        {PRIORITY_DOT_CONFIG[p].title}
                                    </span>
                                </button>
                            ))}
                        </div>
                    </div>
                    <div>
                        <label className="text-xs text-muted-foreground block mb-1">Комментарий</label>
                        <Input
                            placeholder="Необязательно..."
                            value={description}
                            onChange={e => setDescription(e.target.value)}
                        />
                    </div>
                    <Button type="submit" className="w-full" disabled={loading}>
                        {loading ? 'Сохраняю...' : 'Сохранить факт'}
                    </Button>
                </form>
            </SheetContent>
        </Sheet>
    );
}
```

#### 4b — Pass `planPriority` from Budget.tsx

- [ ] **Step 2: Update `<FactCreateSheet>` call in `Budget.tsx`**

Locate the `<FactCreateSheet>` JSX block (search for `factSheetPlanId && (`):

```typescript
{factSheetPlanId && (
    <FactCreateSheet
        planId={factSheetPlanId}
        planDescription={events.find(e => e.id === factSheetPlanId)?.description ?? events.find(e => e.id === factSheetPlanId)?.categoryName ?? 'План'}
        open={!!factSheetPlanId}
        onClose={() => setFactSheetPlanId(null)}
        onCreated={() => { load(true); setFactSheetPlanId(null); }}
    />
)}
```

Replace with:

```typescript
{factSheetPlanId && (() => {
    const planEvent = events.find(e => e.id === factSheetPlanId);
    return (
        <FactCreateSheet
            planId={factSheetPlanId}
            planDescription={planEvent?.description ?? planEvent?.categoryName ?? 'План'}
            planPriority={planEvent?.priority ?? 'MEDIUM'}
            open={!!factSheetPlanId}
            onClose={() => setFactSheetPlanId(null)}
            onCreated={() => { load(true); setFactSheetPlanId(null); }}
        />
    );
})()}
```

- [ ] **Step 3: Verify TypeScript compiles with no errors**

```bash
cd frontend && npx tsc --noEmit
```

Expected: exit code 0, no output.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/components/FactCreateSheet.tsx \
        frontend/src/pages/Budget.tsx
git commit -m "feat(frontend): priority selector in record-fact form"
```

---

## Final verification

- [ ] Start the dev server and open the Budget/Journal page:

```bash
cd frontend && npm run dev
```

Manually verify:
1. Plan rows have a faint left border, fact rows have a purple left border
2. Income plan events show a muted green amount (`+300 000 ₽` in pale green); income fact amounts remain bright green
3. Priority badges are now coloured dots; clicking a dot on a PLAN row cycles it; dots on FACT rows do not show a pointer cursor and do nothing on click
4. Clicking "+ записать факт" opens the sheet with a "Необходимость" row showing three dots pre-selected to the plan's priority; clicking a different dot selects it; submitting saves that priority
5. In a day with both linked facts and unplanned facts, linked facts appear first in the FACT group
