# PR 2: Fund Transfers as Budget Events

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make fund transfers visible in the monthly budget plan-fact view. Both the "quick transfer" button and planned FUND_TRANSFER events should create/link to a `FinancialEvent` of type `FUND_TRANSFER`.

**Architecture:** Add `target_fund_id` column to `financial_events`. The existing `POST /funds/{id}/transfer` endpoint now also creates an EXECUTED event. When a planned `FUND_TRANSFER` event gets its `factAmount` set, `FinancialEventService.updateFact()` triggers the actual fund transfer. Pocket balance formula is unchanged (fund balances remain the source of truth).

**Tech Stack:** Spring Boot, JPA, Flyway, PostgreSQL, React/TypeScript/Shadcn

---

## Task 1: Flyway migration — add target_fund_id to financial_events

**Files:**
- Create: `backend/src/main/resources/db/migration/V6__add_target_fund_id_to_events.sql`

**Step 1: Write migration**

```sql
ALTER TABLE financial_events
    ADD COLUMN target_fund_id UUID REFERENCES target_fund(id);
```

**Step 2: Apply and verify**

```bash
cd backend
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw spring-boot:run 2>&1 | grep -E "Flyway|ERROR"
```

Expected: `Successfully applied 1 migration`

**Step 3: Commit**

```bash
git add backend/src/main/resources/db/migration/V6__add_target_fund_id_to_events.sql
git commit -m "feat(db): add target_fund_id column to financial_events"
```

---

## Task 2: Backend model + DTO changes

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/model/FinancialEvent.java`
- Modify: `backend/src/main/java/ru/selfin/backend/dto/FinancialEventCreateDto.java`
- Modify: `backend/src/main/java/ru/selfin/backend/dto/FinancialEventDto.java`

**Step 1: Add targetFundId to FinancialEvent entity**

In `FinancialEvent.java`, add after the `recurringRuleId` field:
```java
/** FK to target_fund. Populated only when type = FUND_TRANSFER. */
@Column(name = "target_fund_id")
private UUID targetFundId;
```

**Step 2: Add targetFundId to FinancialEventCreateDto**

Read the current record. Add `UUID targetFundId` (nullable, after `rawInput`):
```java
public record FinancialEventCreateDto(
        @NotNull LocalDate date,
        @NotNull UUID categoryId,
        @NotNull EventType type,
        @PositiveOrZero BigDecimal plannedAmount,
        @PositiveOrZero BigDecimal factAmount,
        Boolean mandatory,
        String description,
        String rawInput,
        UUID targetFundId      // NEW — required when type = FUND_TRANSFER
) {}
```

**Step 3: Add targetFundId + targetFundName to FinancialEventDto**

Add two fields at the end of the record:
```java
public record FinancialEventDto(
        UUID id,
        LocalDate date,
        UUID categoryId,
        String categoryName,
        EventType type,
        BigDecimal plannedAmount,
        BigDecimal factAmount,
        EventStatus status,
        boolean mandatory,
        String description,
        String rawInput,
        LocalDateTime createdAt,
        UUID recurringRuleId,
        UUID targetFundId,       // NEW
        String targetFundName    // NEW — denormalized for frontend display
) {}
```

**Step 4: Update FinancialEventService.toDto() to include the new fields**

In `toDto(FinancialEvent e)`, we need the fund name. The `FinancialEvent` entity doesn't hold the fund entity — just the UUID. We need to look it up or fetch it lazily.

Simplest: inject `TargetFundRepository` into `FinancialEventService` and do a conditional lookup:
```java
private final TargetFundRepository targetFundRepository;

public FinancialEventDto toDto(FinancialEvent e) {
    String fundName = null;
    if (e.getTargetFundId() != null) {
        fundName = targetFundRepository.findById(e.getTargetFundId())
                .map(f -> f.getName())
                .orElse(null);
    }
    return new FinancialEventDto(
            e.getId(), e.getDate(),
            e.getCategory().getId(), e.getCategory().getName(),
            e.getType(), e.getPlannedAmount(), e.getFactAmount(),
            e.getStatus(), e.isMandatory(), e.getDescription(),
            e.getRawInput(), e.getCreatedAt(),
            e.getRecurringRuleId(),
            e.getTargetFundId(), fundName);
}
```

**Step 5: Build**

```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw compile -q
```

Expected: `BUILD SUCCESS`

**Step 6: Commit**

```bash
git add backend/src/main/java/ru/selfin/backend/
git commit -m "feat(backend): add targetFundId/targetFundName to FinancialEvent model and DTOs"
```

---

## Task 3: TargetFundService — create EXECUTED event on transfer

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/service/TargetFundService.java`

**Step 1: Read TargetFundService.java** (important — understand `doTransfer` method)

The relevant method creates a `FundTransaction`. We add event creation after it.

**Step 2: Inject FinancialEventRepository into TargetFundService**

TargetFundService already uses `FinancialEventRepository` for the pocket balance query. It's already injected. Add also `CategoryRepository` if not present — we need a "system" category for unplanned transfers.

Actually: FUND_TRANSFER events require a `category_id` (NOT NULL in DB). But an unplanned "quick transfer" has no user-selected category. Options:
1. Create a dedicated system category "Переводы в копилки" on first use
2. Make category nullable for FUND_TRANSFER events

**Chosen approach:** On first transfer, look up or auto-create a category named `"Переводы в копилки"` of type `EXPENSE`. This is idiomatic — it shows up in the category list as a budget line.

In `TargetFundService`, in the `doTransfer` method, after creating the `FundTransaction`:

```java
// Create a FUND_TRANSFER event for visibility in the budget
Category fundTransferCategory = getOrCreateFundTransferCategory();
FinancialEvent transferEvent = FinancialEvent.builder()
        .type(EventType.FUND_TRANSFER)
        .status(EventStatus.EXECUTED)
        .factAmount(amount)
        .date(LocalDate.now())
        .category(fundTransferCategory)
        .targetFundId(fund.getId())
        .description("В копилку: " + fund.getName())
        .build();
eventRepository.save(transferEvent);
```

Add the helper:
```java
private Category getOrCreateFundTransferCategory() {
    return categoryRepository.findByNameAndDeletedFalse("Переводы в копилки")
            .orElseGet(() -> {
                Category c = Category.builder()
                        .name("Переводы в копилки")
                        .type(CategoryType.EXPENSE)
                        .mandatory(false)
                        .build();
                return categoryRepository.save(c);
            });
}
```

You'll need to add `findByNameAndDeletedFalse(String name)` to `CategoryRepository`.

**Step 3: Add CategoryRepository.findByNameAndDeletedFalse**

```java
Optional<Category> findByNameAndDeletedFalse(String name);
```

**Step 4: Build**

```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw compile -q
```

Expected: `BUILD SUCCESS`

**Step 5: Smoke test**

Start backend + open Funds page → click "Пополнить" → enter an amount → submit. Then:
```bash
curl -s "http://localhost:8080/api/v1/events?startDate=$(date +%Y-%m-01)&endDate=$(date +%Y-%m-31)" | jq '[.[] | select(.type == "FUND_TRANSFER")]'
```

Expected: one event with type `FUND_TRANSFER`, `status: "EXECUTED"`, `targetFundName: "<fund name>"`.

**Step 6: Commit**

```bash
git add backend/src/main/java/ru/selfin/backend/service/TargetFundService.java
git add backend/src/main/java/ru/selfin/backend/repository/CategoryRepository.java
git commit -m "feat(backend): quick fund transfer now creates FUND_TRANSFER event in budget"
```

---

## Task 4: FinancialEventService — execute planned FUND_TRANSFER event

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/service/FinancialEventService.java`

**Step 1: Inject TargetFundService (careful: avoid circular dependency)**

`TargetFundService` → `FinancialEventRepository` → `FinancialEventService` would be circular.

Solution: inject via `@Lazy` or extract transfer logic into a thin `FundTransferHelper` bean. Easiest: use `@Lazy` annotation on the `TargetFundService` injection:

```java
@Lazy
private final TargetFundService targetFundService;
```

**Step 2: In `updateFact()`, trigger transfer when executing a FUND_TRANSFER event**

Add after the status update block:
```java
// If this is a FUND_TRANSFER event being executed for the first time, trigger the actual transfer
if (event.getType() == EventType.FUND_TRANSFER
        && event.getTargetFundId() != null
        && dto.factAmount() != null
        && oldFact == null) {          // only on first execution, not on correction
    targetFundService.doTransferForEvent(event.getTargetFundId(), dto.factAmount(), event.getIdempotencyKey());
}
```

**Step 3: Add `doTransferForEvent` to TargetFundService**

Extract the core transfer logic (update balance + save FundTransaction) into a method that does NOT create a new FinancialEvent (since the event already exists):

```java
/**
 * Performs the fund balance update for an already-existing FUND_TRANSFER event.
 * Does NOT create a new FinancialEvent (it already exists).
 */
@Transactional
public void doTransferForEvent(UUID fundId, BigDecimal amount, UUID idempotencyKey) {
    // Check idempotency
    if (fundTransactionRepository.existsByIdempotencyKey(idempotencyKey)) return;

    TargetFund fund = fundRepository.findById(fundId)
            .filter(f -> !f.isDeleted())
            .orElseThrow(() -> new ResourceNotFoundException("TargetFund", fundId));

    fund.setCurrentBalance(fund.getCurrentBalance().add(amount));
    if (fund.getTargetAmount() != null && fund.getCurrentBalance().compareTo(fund.getTargetAmount()) >= 0) {
        fund.setStatus(FundStatus.REACHED);
    }
    fundRepository.save(fund);

    FundTransaction tx = FundTransaction.builder()
            .fund(fund)
            .idempotencyKey(idempotencyKey)
            .amount(amount)
            .transactionDate(LocalDate.now())
            .build();
    fundTransactionRepository.save(tx);
}
```

Add `existsByIdempotencyKey(UUID key)` to `FundTransactionRepository`.

**Step 4: Build**

```bash
JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw compile -q
```

Expected: `BUILD SUCCESS`

**Step 5: Test planned transfer flow**

1. Create a new FUND_TRANSFER event via API (or UI):
```bash
curl -s -X POST http://localhost:8080/api/v1/events \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d "{\"date\":\"$(date +%Y-%m-%d)\",\"categoryId\":\"<fund-transfer-category-uuid>\",\"type\":\"FUND_TRANSFER\",\"plannedAmount\":3000,\"targetFundId\":\"<fund-uuid>\"}"
```

2. Execute it (set factAmount):
```bash
curl -s -X PATCH http://localhost:8080/api/v1/events/<event-uuid>/fact \
  -H "Content-Type: application/json" \
  -d '{"factAmount":3000}'
```

3. Verify fund balance increased by 3000:
```bash
curl -s http://localhost:8080/api/v1/funds | jq '.funds[] | select(.id == "<fund-uuid>") | .currentBalance'
```

**Step 6: Commit**

```bash
git add backend/src/main/java/ru/selfin/backend/service/
git add backend/src/main/java/ru/selfin/backend/repository/FundTransactionRepository.java
git commit -m "feat(backend): executing a planned FUND_TRANSFER event now triggers actual fund transfer"
```

---

## Task 5: Frontend — FUND_TRANSFER type in QuickAddModal + display in Budget

**Files:**
- Modify: `frontend/src/components/Fab.tsx`
- Modify: `frontend/src/pages/Budget.tsx`
- Modify: `frontend/src/types/api.ts`
- Modify: `frontend/src/api/index.ts`

**Step 1: Update api.ts types**

In `FinancialEvent` interface, add:
```typescript
targetFundId?: string;
targetFundName?: string;
```

In `FinancialEventCreateDto`, add:
```typescript
targetFundId?: string;
```

**Step 2: Add fetchFunds to api/index.ts (if not already importable)**

We need to load the active funds list in QuickAddModal when type is FUND_TRANSFER. `fetchFunds()` is already exported from `api/index.ts`.

**Step 3: Add FUND_TRANSFER type button to QuickAddModal in Fab.tsx**

Add a third type button and state for fund selection:

```tsx
// State
const [funds, setFunds] = useState<TargetFund[]>([]);

// Load funds when type switches to FUND_TRANSFER
useEffect(() => {
    if (form.type === 'FUND_TRANSFER') {
        fetchFunds().then(data => setFunds(data.funds));
    }
}, [form.type]);

// Type buttons — add third option
<Button
    type="button"
    className="flex-1"
    variant={form.type === 'FUND_TRANSFER' ? 'default' : 'secondary'}
    style={form.type === 'FUND_TRANSFER' ? { background: 'hsl(var(--primary))' } : {}}
    onClick={() => setForm(f => ({ ...f, type: 'FUND_TRANSFER', categoryId: undefined, targetFundId: undefined }))}
>
    В копилку
</Button>

// Fund selector — shown only when type = FUND_TRANSFER
{form.type === 'FUND_TRANSFER' && (
    <Select
        value={form.targetFundId || ''}
        onValueChange={val => setForm(f => ({ ...f, targetFundId: val }))}
    >
        <SelectTrigger>
            <SelectValue placeholder="Выбери копилку" />
        </SelectTrigger>
        <SelectContent>
            {funds.filter(f => f.status !== 'REACHED').map(f => (
                <SelectItem key={f.id} value={f.id}>{f.name}</SelectItem>
            ))}
        </SelectContent>
    </Select>
)}
```

When type = `FUND_TRANSFER`, the category selector is hidden. In `handleSubmit`, for FUND_TRANSFER:
- Skip category validation (categoryId can be absent — backend will use/create the system category)
- Pass `targetFundId` in the DTO

Actually, the backend requires `categoryId` to be non-null. We need to either:
1. Pass the fund-transfer system category UUID (requires knowing it upfront)
2. Have the backend accept null categoryId for FUND_TRANSFER and look up the system category itself

**Chosen approach:** the backend `FinancialEventService.createIdempotent()` checks: if `type == FUND_TRANSFER && categoryId == null`, auto-use `getOrCreateFundTransferCategory()`. This is cleaner than requiring the frontend to know system category UUIDs.

Update `FinancialEventService.createIdempotent()`:
```java
Category category;
if (dto.type() == EventType.FUND_TRANSFER && dto.categoryId() == null) {
    category = targetFundService.getOrCreateFundTransferCategory();
} else {
    category = categoryRepository.findById(dto.categoryId())
            .filter(c -> !c.isDeleted())
            .orElseThrow(() -> new ResourceNotFoundException("Category", dto.categoryId()));
}
```

Also make `categoryId` nullable in `FinancialEventCreateDto` (remove `@NotNull`).

**Step 4: Display FUND_TRANSFER events in Budget.tsx**

Import `PiggyBank` from lucide-react.

In the event row render:
```tsx
const isFundTransfer = event.type === 'FUND_TRANSFER';

// In the category name area, add fund name
<span className="font-medium text-sm truncate">
    {isFundTransfer ? `↪ ${event.targetFundName ?? 'Копилка'}` : event.categoryName}
</span>

// Color: use accent purple for fund transfers
style={{ color: isIncome ? 'var(--color-success)' : isFundTransfer ? 'hsl(var(--primary))' : ... }}
```

**Step 5: Test full flow**

1. Open FAB → select "В копилку" → choose vacation fund → enter 5000 → submit
2. Verify event appears in budget as "↪ Отпуск" with purple color and 5000 ₽ EXECUTED
3. Create planned FUND_TRANSFER: open FAB → "В копилку" → choose fund → enter planned 5000, no fact → save
4. Verify event appears in budget as PLANNED
5. Click the PLANNED event → enter factAmount 5000 → save
6. Verify fund balance increased by 5000

**Step 6: Commit**

```bash
git add frontend/src/
git commit -m "feat(frontend): add FUND_TRANSFER type to QuickAddModal and display fund transfer events in Budget"
```
