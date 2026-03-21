-- V12: Split plan/fact — add event_kind and parent_event_id columns.
-- Existing events with fact_amount != null are split into:
--   original record  → PLAN  (fact_amount cleared, status EXECUTED)
--   new record       → FACT  (fact_amount copied, parent_event_id = original id)

-- 1. Add columns (nullable first for safe migration)
ALTER TABLE financial_events
    ADD COLUMN event_kind      VARCHAR(10),
    ADD COLUMN parent_event_id UUID REFERENCES financial_events(id);

-- 2. Mark all existing records as PLANs
UPDATE financial_events SET event_kind = 'PLAN';

-- 3. Insert FACT records for all executed events (fact_amount IS NOT NULL)
INSERT INTO financial_events (
    id, idempotency_key, date, category_id, type,
    planned_amount, fact_amount, status, priority,
    description, raw_input, url, target_fund_id,
    is_deleted, created_at, updated_at,
    event_kind, parent_event_id
)
SELECT
    gen_random_uuid(),   -- new id for FACT
    gen_random_uuid(),   -- new idempotency_key (must be unique)
    date, category_id, type,
    NULL,                -- PLANs own the plannedAmount; FACTs have none
    fact_amount,
    'EXECUTED',
    priority, description, raw_input, url, target_fund_id,
    FALSE, NOW(), NOW(),
    'FACT',
    id                   -- link back to PLAN
FROM financial_events
WHERE fact_amount IS NOT NULL
  AND is_deleted = FALSE;

-- 4. Clear fact_amount from original PLAN records
UPDATE financial_events
SET fact_amount = NULL
WHERE fact_amount IS NOT NULL
  AND event_kind = 'PLAN';

-- 5. Make event_kind NOT NULL now that all rows have a value
ALTER TABLE financial_events
    ALTER COLUMN event_kind SET NOT NULL;
