-- V18: wishlist planning — статусы хотелок/копилок/кредитов + конверсия + user_settings

-- 1. wishlist_status + конверсия на financial_events
ALTER TABLE financial_events ADD COLUMN wishlist_status VARCHAR(16);
ALTER TABLE financial_events ADD COLUMN converted_to_event_id UUID REFERENCES financial_events(id) ON DELETE SET NULL;
ALTER TABLE financial_events ADD COLUMN converted_to_fund_id  UUID REFERENCES target_funds(id)    ON DELETE SET NULL;

ALTER TABLE financial_events
    ADD CONSTRAINT chk_wishlist_status_only_low
    CHECK (wishlist_status IS NULL OR priority = 'LOW');
-- NB: a converted item may legitimately return to OPEN/DISMISSED while keeping its conversion link
--     (lifecycle "вернуть в обсуждение, артефакт остаётся"), so we do NOT constrain converted rows to FIXED.
ALTER TABLE financial_events
    ADD CONSTRAINT chk_event_single_conversion
    CHECK (NOT (converted_to_event_id IS NOT NULL AND converted_to_fund_id IS NOT NULL));

CREATE INDEX idx_events_wishlist_status
    ON financial_events (wishlist_status) WHERE wishlist_status IS NOT NULL;

-- 2. wishlist_status + конверсия на target_funds
ALTER TABLE target_funds ADD COLUMN wishlist_status VARCHAR(16);
ALTER TABLE target_funds ADD COLUMN converted_to_event_id UUID REFERENCES financial_events(id) ON DELETE SET NULL;
ALTER TABLE target_funds ADD COLUMN converted_to_fund_id  UUID REFERENCES target_funds(id)    ON DELETE SET NULL;

-- NB: same lifecycle rule as financial_events — a converted fund may return to OPEN/DISMISSED
--     while keeping its conversion link, so we do NOT constrain converted rows to FIXED.
ALTER TABLE target_funds
    ADD CONSTRAINT chk_fund_single_conversion
    CHECK (NOT (converted_to_event_id IS NOT NULL AND converted_to_fund_id IS NOT NULL));

CREATE INDEX idx_funds_wishlist_status
    ON target_funds (wishlist_status) WHERE wishlist_status IS NOT NULL;

-- 3. Backfill: активные (FUNDING) копилки/кредиты → FIXED
-- FundStatus enum в коде = {FUNDING, REACHED}.
UPDATE target_funds SET wishlist_status = 'FIXED' WHERE status = 'FUNDING' AND is_deleted = FALSE;

-- 4. Backfill: хотелки (LOW без даты) → OPEN
UPDATE financial_events SET wishlist_status = 'OPEN'
WHERE priority = 'LOW' AND date IS NULL AND is_deleted = FALSE;

-- 5. user_settings (key/value JSONB)
CREATE TABLE user_settings (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    settings_key   VARCHAR(64) NOT NULL UNIQUE,
    settings_value JSONB       NOT NULL,
    updated_at     TIMESTAMP   NOT NULL DEFAULT NOW()
);
