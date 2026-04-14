# Journal UX Improvements — Design Spec

**Date:** 2026-04-14  
**Status:** Approved  
**Scope:** One PR, frontend + minor backend change

---

## Problem

The "Журнал" (Budget page event list) has four readability and usability gaps discovered during active use:

1. Plan and fact rows look nearly identical — hard to distinguish at a glance.
2. Income (INCOME type) plan events are styled the same grey as expense plans — no visual cue that money is coming in.
3. Priority labels ("обяз"/"план"/"хотелка") are text badges that take space and impose rigid ideological framing; there is no way to set priority when recording a fact.
4. Within the fact group for a day, linked facts and unplanned facts are mixed in alphabetical order — harder to scan for matched plan-fact pairs.

---

## Solution Overview

Four targeted changes delivered in a single PR:

| # | Feature | Layers touched |
|---|---------|---------------|
| 1 | Left border accent for plan vs fact rows | Frontend only |
| 2 | Muted green amount for income plan events | Frontend only |
| 3a | Replace priority text badges with colored dots | Frontend only |
| 3b | Priority selector in "record fact" form | Frontend + Backend |
| 4 | Sort linked facts before unplanned facts within a day | Frontend only |

---

## Files Changed

| File | Change |
|------|--------|
| `frontend/src/pages/Budget.tsx` | Border styling (F1), income amount colour (F2), fact sort order (F4) |
| `frontend/src/components/PriorityButton.tsx` | Replace text badges with 8px colour dots (F3a) |
| `frontend/src/components/FactCreateSheet.tsx` | Add priority dot-selector row (F3b) |
| `frontend/src/types/api.ts` | Add `priority?: Priority` to `FactCreateDto` |
| `backend/.../dto/FactCreateDto.java` | Add optional `Priority priority` field |
| `backend/.../service/FinancialEventService.java` | Use provided priority or inherit from parent plan |

---

## Feature 1 — Plan / Fact Border

Add a left border to every event row in the journal to make plan vs fact visually immediate.

- **PLAN rows:** `border-left: 3px solid rgba(255, 255, 255, 0.12)` — subtle light grey
- **FACT rows:** `border-left: 3px solid hsl(var(--primary))` — accent purple

**Constraints:**
- The existing hover background highlighting of linked plan↔fact pairs is not touched.
- The dashed horizontal divider between the plan group and fact group within a day is kept.

---

## Feature 2 — Income Colour in Plan Rows

Distinguish incoming money from outgoing money in the plan section of the journal.

- **PLAN + `type === 'INCOME'`:** amount text colour `rgba(74, 222, 128, 0.5)` — muted green
- **FACT + `type === 'INCOME'`:** stays `var(--color-success)` — bright green (no change)

The brightness difference preserves the existing visual language: bright = recorded reality, muted = expectation.

---

## Feature 3a — Priority Colour Dots

Replace the three text-label priority badges with a single 8 px filled circle.

| Priority | Old label | New colour |
|----------|-----------|------------|
| HIGH | обяз | `#f87171` — red |
| MEDIUM | план | `#facc15` — yellow |
| LOW | хотелка | `#60a5fa` — blue |

**Behaviour (unchanged):** clicking the dot cycles HIGH → MEDIUM → LOW → HIGH via the existing `cycleEventPriority()` call.

**Accessibility:** the dot element gets a `title` attribute with the human-readable label: HIGH → "обязательно", MEDIUM → "по плану", LOW → "хотелка".

**Clickability on fact rows:** Priority dots on FACT rows are rendered as non-interactive in this PR (no `onClick`, `cursor: default`). Changing fact priority post-creation is not in scope.

**Rationale for blurring labels:** the same HIGH/MEDIUM/LOW scale is now reused on fact events to express necessity in hindsight (see 3b), not just forward-looking priority. Colour dots are neutral enough to carry both meanings.

---

## Feature 3b — Priority in "Record Fact" Form

When a user records a fact against a plan event, allow them to tag how necessary that expense actually turned out to be.

**Scope:** `FactCreateSheet` is used exclusively for linked facts (opened via "+ записать факт" on a plan row). Unplanned facts created via the FAB already go through `FinancialEventCreateDto`, which has a `priority` field — that flow is not changed in this PR.

**UI:** Add a priority selector row to `FactCreateSheet` above the comment field. The selector shows three colour dots (same as 3a); the active one is highlighted. Clicking a dot selects it.

**Props change (`FactCreateSheet`):** Add `planPriority: Priority` to the `Props` interface. `Budget.tsx` already has access to the parent plan event (via `events.find(e => e.id === factSheetPlanId)`) — pass `plan.priority` when rendering `<FactCreateSheet>`.

**Default and reset behaviour:** Each time the sheet opens, the priority selector initialises to `planPriority`. It resets on every open (same as `date` and `amount`), so there is no stale state from a previous form submission.

**Backend — `FactCreateDto`:**
```java
@Nullable
private Priority priority;
```
The field is optional. In `FinancialEventService`, in the fact-creation branch (where the new fact entity is built from the DTO), replace the current hard-coded `Priority.MEDIUM` default with: use `dto.getPriority()` if non-null, otherwise `parentEvent.getPriority()`.

**No new endpoint** — the existing `POST /events/{planId}/facts` accepts the extended DTO.

---

## Feature 4 — Fact Sort Order Within a Day

**Current sort:** all facts for a day sorted alphabetically by display name.

**New sort:** linked facts first (sorted alphabetically), then unplanned facts (sorted alphabetically).

A fact is "linked" if `parentEventId !== null`.

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

`getDisplayName` is an existing helper in `Budget.tsx` (line 78): returns `targetFundName` for transfers, otherwise `description ?? rawInput ?? categoryName`.

**Rationale:** linked facts visually correspond to plan rows above the dashed divider. Putting them first makes the plan↔fact relationship easier to trace.

---

## Out of Scope

- Any analytics page changes — priority on facts feeds future analytics work but that is a separate PR.
- Ctrl+N shortcut — separate backlog item.
