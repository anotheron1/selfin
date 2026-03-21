# Spec: Plan/Fact Transaction Split + Overdue Mandatory Balance Fix

**Date:** 2026-03-19

---

## Context

The current `FinancialEvent` entity conflates planned and actual transactions in a single record: both `plannedAmount` and `factAmount` live in one row, and one plan can only have one fact. This creates problems:

- One plan cannot cover multiple partial payments (1:N plan-to-fact impossible)
- Edit forms show irrelevant fields (planned event shows factAmount field)
- The journal cannot cleanly separate plans from facts
- Overdue mandatory plans disappear from balance calculations — `FundPlannerService` month-0 only counts events from today onward, so a past-dated mandatory expense (e.g., utility bill planned March 1, not paid by March 19) is silently excluded from projections and pocket balance

**Goal:** Split `FinancialEvent` into two explicit roles (PLAN and FACT) in the same table via `event_kind` + `parent_event_id`, wire up the journal UI with plan-fact linking, and fix balance calculations to treat overdue mandatory plans as reserved obligations.

---

## Data Model

### DB changes

Two new columns on `financial_event`:

```sql
ALTER TABLE financial_event
    ADD COLUMN event_kind      VARCHAR(10) NOT NULL DEFAULT 'PLAN',
    ADD COLUMN parent_event_id UUID REFERENCES financial_event(id);
```

### Semantics

| event_kind | parent_event_id | plannedAmount | factAmount | Meaning |
|-----------|-----------------|---------------|------------|---------|
| PLAN      | null            | set           | null       | Planned transaction |
| FACT      | null            | null          | set        | Standalone actual (no plan) |
| FACT      | uuid            | null          | set        | Actual linked to a plan |

### Rules

- A PLAN can have zero or many linked FACTs
- A FACT always has a `date` (mandatory, even if parent plan's date differs)
- Linked FACT inherits `category` and `type` from parent PLAN (not user-settable on fact)
- Standalone FACT requires the user to choose `category`
- Plan `status`: auto-set to EXECUTED when ≥1 FACT is linked; reverts to PLANNED if all linked FACTs are deleted
- FACTs have no `status` field — their existence implies execution
- `priority` lives on PLANs only; standalone FACTs default to MEDIUM

### Java additions

- `EventKind.java` — enum `{ PLAN, FACT }`
- `Transaction.java` — interface with: `getId()`, `getDate()`, `getCategory()`, `getType()`, `getDescription()`
- `FinancialEvent` implements `Transaction`, adds `eventKind` and `parentEventId` fields

---

## Data Migration (Flyway)

Script: `V{next}__split_plan_fact.sql`

```sql
-- 1. Add columns
ALTER TABLE financial_event
    ADD COLUMN event_kind      VARCHAR(10),
    ADD COLUMN parent_event_id UUID REFERENCES financial_event(id);

-- 2. Mark all existing records as PLANs
UPDATE financial_event SET event_kind = 'PLAN';

-- 3. For executed events (have factAmount): insert FACT rows, clear factAmount from PLANs
INSERT INTO financial_event (id, idempotency_key, date, category_id, type,
    planned_amount, fact_amount, status, priority, description, event_kind, parent_event_id,
    created_at, updated_at, deleted)
SELECT gen_random_uuid(), gen_random_uuid(), date, category_id, type,
    null, fact_amount, 'EXECUTED', priority, description, 'FACT', id,
    now(), now(), false
FROM financial_event
WHERE fact_amount IS NOT NULL AND deleted = false;

UPDATE financial_event
SET fact_amount = null, status = 'EXECUTED'
WHERE fact_amount IS NOT NULL AND event_kind = 'PLAN';

-- 4. Make event_kind NOT NULL
ALTER TABLE financial_event ALTER COLUMN event_kind SET NOT NULL;
```

---

## API

### Updated `FinancialEventDto`

New fields:

| Field | Type | Description |
|-------|------|-------------|
| `eventKind` | `"PLAN" \| "FACT"` | Role of this record |
| `parentEventId` | `UUID \| null` | FACTs only: parent plan ID |
| `linkedFactsCount` | `int` | PLANs only: number of linked facts |
| `linkedFactsAmount` | `BigDecimal \| null` | PLANs only: total of all linked fact amounts |
| `parentPlanDescription` | `String \| null` | FACTs only: description or category name of parent plan |

### New endpoint

`POST /api/v1/events/{planId}/facts`

```json
// Request body
{
  "date": "2026-03-19",
  "factAmount": 4850.00,
  "description": "оплатил онлайн"   // optional
}
```

- Validates `{planId}` exists and `eventKind = PLAN`
- Inherits `category`, `type` from plan automatically
- Creates FACT record with `parent_event_id = planId`
- Auto-sets plan `status = EXECUTED`
- Returns new `FinancialEventDto`

### Modified endpoints

- `GET /api/v1/events?startDate=&endDate=` — returns PLANs and FACTs, enriched with computed fields above
- `PUT /api/v1/events/{id}` — allowed fields per role:
  - PLAN: `date`, `categoryId`, `type`, `plannedAmount`, `priority`, `description`
  - FACT: `date`, `factAmount`, `description`
- `PATCH /api/v1/events/{id}/fact` — removed (superseded by `POST /{planId}/facts`)
- `DELETE /api/v1/events/{id}`:
  - Deleting a PLAN that has linked FACTs → 409 Conflict (user must delete facts first)
  - Deleting a FACT → soft-delete; if it was the last FACT on a PLAN, revert plan status to PLANNED

---

## Backend Service Changes

### `FinancialEventService`

New methods:
- `createLinkedFact(UUID planId, FactCreateDto dto)` — validates plan, creates FACT, updates plan status

Changed methods:
- `findByPeriod(start, end)` — enriches each PLAN with `linkedFactsCount` + `linkedFactsAmount` via aggregate query; enriches FACTs with `parentPlanDescription`
- `update(id, dto)` — validates fields allowed per `eventKind`
- `softDelete(id)` — check for linked FACTs before deleting a PLAN; recompute plan status after deleting a FACT

### `FinancialEventRepository`

New queries:
- `sumOverdueMandatoryExpenses(LocalDate monthStart, LocalDate today)` — `SUM(planned_amount)` where `event_kind=PLAN AND priority=HIGH AND type=EXPENSE AND status=PLANNED AND date >= monthStart AND date < today AND deleted=false`
- `countAndSumFactsByPlanIds(List<UUID> planIds)` — returns aggregated `(parentEventId, count, sum)` for enriching batch DTO response

### `TargetFundService.calcPocketBalance()`

Add deduction of overdue mandatory plans from current month:

```
pocketBalance = checkpoint
              + executedIncome (from checkpoint date)
              - executedExpense (from checkpoint date)
              - fundBalances
              - overdueHighExpenses (current month, date < today, still PLANNED)
```

### `FundPlannerService.getPlanner()` — month 0

```java
// Current: only counts events where date >= today
// Fix: add overdue mandatory plans from current month start to today
BigDecimal overdueMandate = repo.sumOverdueMandatoryExpenses(monthStart, today);
mandatoryExpenses = futureHighExpenses.add(overdueMandate);
```

### `DashboardService.getDashboard()`

Include overdue mandatory plans in end-of-month forecast:

```java
BigDecimal overdueMandate = repo.sumOverdueMandatoryExpenses(monthStart, today);
endOfMonthForecast = currentBalance
                   + futureIncome
                   - futureExpenses
                   - overdueMandate;
```

---

## Frontend Changes

### Journal (`Budget.tsx`)

**Within-day grouping:**
1. All PLANs — sorted alphabetically by `description ?? categoryName`
2. Dashed separator
3. All FACTs — sorted alphabetically

**PLAN card additions:**
- Badge: `"N фактов"` when `linkedFactsCount > 0`
- Amount delta badge: `linkedFactsAmount` vs `plannedAmount`
- Button "Записать факт" (always visible on PLAN with status=PLANNED) → opens `FactCreateSheet`

**FACT card additions:**
- Subtitle: `"← {parentPlanDescription}"` when `parentEventId != null`
- No priority badge (priority belongs to the plan)

### New component: `FactCreateSheet`

Fields:
- Date (required, defaults to today)
- Amount / factAmount (required)
- Description (optional)

On submit: `POST /api/v1/events/{planId}/facts`

### Edit form (`EventSheet`)

Conditional fields by `eventKind`:
- PLAN: date, category, type, plannedAmount, priority, description
- FACT: date, factAmount, description (category shown read-only if linked)

### `src/types/api.ts`

Add new fields to `FinancialEvent` interface.

### `src/api/client.ts`

New function: `createLinkedFact(planId, dto)` → `POST /api/v1/events/{planId}/facts`

---

## Files to Modify

**Backend (`backend/src/main/java/ru/selfin/backend/`):**
- `model/FinancialEvent.java`
- `model/EventKind.java` ← new
- `model/Transaction.java` ← new interface
- `dto/FinancialEventDto.java`
- `dto/FactCreateDto.java` ← new
- `service/FinancialEventService.java`
- `repository/FinancialEventRepository.java`
- `controller/FinancialEventController.java`
- `service/TargetFundService.java`
- `service/FundPlannerService.java`
- `service/DashboardService.java`
- `resources/db/migration/V{N}__split_plan_fact.sql` ← new

**Frontend (`frontend/src/`):**
- `pages/Budget.tsx`
- `components/FactCreateSheet.tsx` ← new
- `components/EventSheet.tsx` (or edit dialog equivalent)
- `api/client.ts`
- `types/api.ts`

---

## Verification

1. Flyway migration runs cleanly; existing executed events appear as PLAN+FACT pairs
2. `GET /api/v1/events` returns `eventKind`, `linkedFactsCount`, `parentPlanDescription`
3. `POST /api/v1/events/{planId}/facts` creates linked fact, plan status → EXECUTED
4. `DELETE` on a PLAN with linked facts returns 409
5. Pocket balance decreases when a HIGH/EXPENSE PLAN dated yesterday exists (no fact)
6. Planner month-0 `mandatoryExpenses` includes overdue HIGH plans
7. Journal: PLANs before FACTs within a day; FACT card shows parent plan name
8. Edit PLAN: factAmount not shown; edit FACT: plannedAmount/priority not shown
9. `JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test` — all tests pass
