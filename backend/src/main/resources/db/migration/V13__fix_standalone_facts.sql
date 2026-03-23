-- V13: Fix V12 migration bug — standalone facts (no planned amount) were
-- incorrectly split into empty PLAN stubs + linked FACTs.
-- These should be standalone FACTs with parent_event_id = NULL.

-- 1. Unlink FACTs from stub PLANs (PLAN has no planned_amount)
UPDATE financial_events f
SET parent_event_id = NULL
FROM financial_events p
WHERE f.parent_event_id = p.id
  AND f.event_kind = 'FACT'
  AND f.is_deleted = FALSE
  AND p.event_kind = 'PLAN'
  AND p.planned_amount IS NULL
  AND p.is_deleted = FALSE;

-- 2. Soft-delete the now-orphaned PLAN stubs
UPDATE financial_events
SET is_deleted = TRUE, updated_at = NOW()
WHERE event_kind = 'PLAN'
  AND planned_amount IS NULL
  AND is_deleted = FALSE
  AND id NOT IN (
      SELECT DISTINCT parent_event_id
      FROM financial_events
      WHERE parent_event_id IS NOT NULL
        AND is_deleted = FALSE
  );
